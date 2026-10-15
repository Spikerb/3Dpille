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
}
