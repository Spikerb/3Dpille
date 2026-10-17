package com.stockpile.volume.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class XrplMemo(val type: String?, val format: String?, val data: String?)

data class XrplTx(
    val hash: String,
    val validated: Boolean,
    val result: String?,
    val ledgerIndex: Long?,
    val account: String?,
    val memos: List<XrplMemo>,
)

/** Reads transactions straight from public XRP Ledger nodes (JSON-RPC), independent of Xaman. */
object Xrpl {
    const val MAINNET = "MAINNET"
    const val TESTNET = "TESTNET"

    private val nodes = mapOf(
        MAINNET to listOf("https://xrplcluster.com/", "https://s1.ripple.com:51234/", "https://s2.ripple.com:51234/"),
        TESTNET to listOf("https://s.altnet.rippletest.net:51234/", "https://testnet.xrpl-labs.com/"),
    )

    fun explorerUrl(network: String, txid: String) =
        if (network == TESTNET) "https://testnet.xrpl.org/transactions/$txid" else "https://livenet.xrpl.org/transactions/$txid"

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null if the transaction isn't known (yet). */
    suspend fun tx(network: String, txid: String): XrplTx? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("method", "tx")
            put("params", buildJsonArray { add(buildJsonObject { put("transaction", txid); put("binary", false) }) })
        }.toString().toRequestBody("application/json".toMediaType())
        var lastError: Exception? = null
        for (url in nodes[network] ?: nodes.getValue(MAINNET)) {
            try {
                val text = http.newCall(Request.Builder().url(url).post(body).build()).execute().use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body?.string().orEmpty()
                }
                val result = json.parseToJsonElement(text).jsonObject["result"]?.jsonObject ?: continue
                val err = result["error"]?.jsonPrimitive?.contentOrNull
                if (err == "txnNotFound") return@withContext null
                if (err != null) throw IOException(err)
                return@withContext parse(result, txid)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("XRPL uzel nedostupný: ${lastError?.message}")
    }

    private fun parse(r: JsonObject, txid: String): XrplTx {
        // API v1 has the fields at top level, API v2 under "tx_json".
        val tx = r["tx_json"]?.jsonObject ?: r
        val memos = (tx["Memos"] as? JsonArray)?.mapNotNull { m ->
            val memo = m.jsonObject["Memo"]?.jsonObject ?: return@mapNotNull null
            fun f(k: String) = memo[k]?.jsonPrimitive?.contentOrNull?.let { runCatching { Hex.utf8FromHex(it) }.getOrNull() }
            XrplMemo(f("MemoType"), f("MemoFormat"), f("MemoData"))
        }.orEmpty()
        return XrplTx(
            hash = r["hash"]?.jsonPrimitive?.contentOrNull ?: txid,
            validated = r["validated"]?.jsonPrimitive?.booleanOrNull == true,
            result = r["meta"]?.jsonObject?.get("TransactionResult")?.jsonPrimitive?.contentOrNull,
            ledgerIndex = (r["ledger_index"] ?: tx["ledger_index"])?.jsonPrimitive?.longOrNull,
            account = tx["Account"]?.jsonPrimitive?.contentOrNull,
            memos = memos,
        )
    }

    // ------------------------------------------------------------------ registry (duplicate check)

    private fun rpc(network: String, method: String, params: JsonObject): JsonObject {
        val body = buildJsonObject {
            put("method", method)
            put("params", buildJsonArray { add(params) })
        }.toString().toRequestBody("application/json".toMediaType())
        var lastError: Exception? = null
        for (url in nodes[network] ?: nodes.getValue(MAINNET)) {
            try {
                val text = http.newCall(Request.Builder().url(url).post(body).build()).execute().use {
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    it.body?.string().orEmpty()
                }
                val result = json.parseToJsonElement(text).jsonObject["result"]?.jsonObject
                    ?: throw IOException("prázdná odpověď")
                val err = result["error"]?.jsonPrimitive?.contentOrNull
                if (err != null && err != "actNotFound") throw IOException(err)
                return result
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("XRPL uzel nedostupný: ${lastError?.message}")
    }

    /**
     * All successful measurement records sent to [registry] since [since], newest first.
     * Reads the registry's whole history page by page until it reaches older ledgers.
     */
    suspend fun registryEntries(network: String, registry: String, since: java.time.Instant): List<RegistryEntry> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<RegistryEntry>()
            var marker: kotlinx.serialization.json.JsonElement? = null
            var pages = 0
            while (pages++ < 100) {
                val params = buildJsonObject {
                    put("account", registry)
                    put("ledger_index_min", -1)
                    put("ledger_index_max", -1)
                    put("limit", 200)
                    put("forward", false)
                    marker?.let { put("marker", it) }
                }
                val r = rpc(network, "account_tx", params)
                if (r["error"]?.jsonPrimitive?.contentOrNull == "actNotFound") {
                    throw IOException("Evidenční účet $registry na síti $network neexistuje (není aktivovaný).")
                }
                val txs = r["transactions"] as? JsonArray ?: break
                var reachedOld = false
                for (item in txs) {
                    val o = item.jsonObject
                    val tx = (o["tx_json"] ?: o["tx"])?.jsonObject ?: continue
                    val closeTime = o["close_time_iso"]?.jsonPrimitive?.contentOrNull?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                        ?: tx["date"]?.jsonPrimitive?.longOrNull?.let { java.time.Instant.ofEpochSecond(it + 946_684_800L) }
                    if (closeTime != null && closeTime.isBefore(since)) { reachedOld = true; continue }
                    if (tx["TransactionType"]?.jsonPrimitive?.contentOrNull != "Payment") continue
                    if (tx["Destination"]?.jsonPrimitive?.contentOrNull != registry) continue
                    val result = o["meta"]?.jsonObject?.get("TransactionResult")?.jsonPrimitive?.contentOrNull
                    if (result != "tesSUCCESS") continue
                    val hash = (o["hash"] ?: tx["hash"])?.jsonPrimitive?.contentOrNull ?: ""
                    val memos = (tx["Memos"] as? JsonArray) ?: continue
                    for (m in memos) {
                        val memo = m.jsonObject["Memo"]?.jsonObject ?: continue
                        val type = memo["MemoType"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Hex.utf8FromHex(it) }.getOrNull() }
                        if (type != Anchoring.MEMO_TYPE) continue
                        val data = memo["MemoData"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Hex.utf8FromHex(it) }.getOrNull() } ?: continue
                        val d = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                        val lat = d["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                        val lon = d["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                        val measured = d["measured_at"]?.jsonPrimitive?.contentOrNull?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                            ?: closeTime ?: continue
                        out += RegistryEntry(hash, d["id"]?.jsonPrimitive?.contentOrNull, d["name"]?.jsonPrimitive?.contentOrNull, lat, lon, measured)
                    }
                }
                marker = r["marker"]
                if (reachedOld || marker == null || txs.isEmpty()) break
            }
            out
        }
}
