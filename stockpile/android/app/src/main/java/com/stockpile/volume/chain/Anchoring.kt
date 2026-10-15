package com.stockpile.volume.chain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.stockpile.volume.data.ChainAnchor
import com.stockpile.volume.data.MeasurementRecord
import com.stockpile.volume.data.ProjectStore
import com.stockpile.volume.data.Settings
import com.stockpile.volume.measure.MeasurementSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.UUID

/**
 * Writes a measurement to the XRP Ledger: an AccountSet transaction (changes nothing on the account,
 * costs ~0.00001 XRP) whose memo carries the key figures and the SHA-256 of record.json.
 * The user approves it in Xaman; we then confirm it independently on public XRPL nodes.
 */
object Anchoring {
    const val MEMO_TYPE = "stockpile-volume/1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // Channels, not SharedFlows: an event sent before the UI starts collecting (cold start from a
    // Xaman deep link) is kept until it's consumed, and is consumed only once.
    private val _messages = Channel<String>(Channel.BUFFERED)
    /** Short user-facing messages (shown as toasts by MainActivity). */
    val messages: Flow<String> = _messages.receiveAsFlow()
    private val _openProject = Channel<String>(Channel.CONFLATED)
    /** Project the app should navigate to (after returning from Xaman). */
    val openProject: Flow<String> = _openProject.receiveAsFlow()

    private fun say(msg: String) { _messages.trySend(msg) }

    /** Public memo content: key figures, GPS position (5 decimals ≈ 1 m) and the hash of the full record. */
    fun memoJson(r: MeasurementRecord, sha256: String): String = buildJsonObject {
        put("sha256", sha256)
        put("id", r.id)
        put("name", r.project.take(60))
        put("measured_at", r.measuredAt)
        put("volume_m3", r.volumeM3)
        r.tonnes?.let { put("tonnes", it) }
        r.waterCoef?.let { put("water_coef", it) }
        r.waterM3?.let { put("water_m3", it) }
        r.dryVolumeM3?.let { put("dry_m3", it) }
        r.dryTonnes?.let { put("dry_t", it) }
        put("footprint_m2", r.footprintM2)
        put("max_height_m", r.maxHeightM)
        put("method", r.method)
        r.location?.let { g ->
            put("lat", g.lat)
            put("lon", g.lon)
            g.accuracyM?.let { put("gps_acc_m", it) }
        }
    }.toString()

    // ------------------------------------------------------------------ start

    fun start(context: Context, projectId: String) {
        val settings = Settings(context)
        if (settings.xamanApiKey.isBlank()) { say("Nejdřív zadejte Xaman API klíč v Nastavení."); return }
        if (!settings.xamanSignedIn) {
            settings.pendingAnchorProject = projectId
            say("Přihlaste se v Xamanu – potom se žádost o podpis vytvoří sama.")
            runCatching { Xaman.startSignIn(context, settings) }.onFailure { say(it.message ?: "Chyba přihlášení") }
            return
        }
        scope.launch { create(context.applicationContext, projectId) }
    }

    private suspend fun create(context: Context, projectId: String) {
        val store = ProjectStore.get(context)
        val settings = Settings(context)
        val p = store.get(projectId) ?: return
        val record = p.measurement ?: run { say("Projekt nemá měření."); return }
        val hash = p.recordSha256 ?: return
        if (MeasurementSaver.fileHash(store, projectId) != hash) {
            say("Soubor s měřením byl změněn – zápis zastaven."); return
        }
        val memo = memoJson(record, hash)
        val body: JsonObject = buildJsonObject {
            putJsonObject("txjson") {
                put("TransactionType", "AccountSet")
                put("Memos", buildJsonArray {
                    add(buildJsonObject {
                        putJsonObject("Memo") {
                            put("MemoType", Hex.utf8(MEMO_TYPE))
                            put("MemoFormat", Hex.utf8("application/json"))
                            put("MemoData", Hex.utf8(memo))
                        }
                    })
                })
            }
            putJsonObject("options") {
                put("submit", true)
                put("force_network", settings.network)
                putJsonObject("return_url") { put("app", Xaman.RETURN_URL); put("web", Xaman.RETURN_URL) }
            }
            putJsonObject("custom_meta") {
                put("identifier", "sv-" + UUID.randomUUID().toString().replace("-", "").take(32))
                put("instruction", "Zápis měření „${record.project.take(80)}“: " + "%.1f".format(record.volumeM3) + " m³ do XRP Ledger")
            }
        }
        try {
            val created = Xaman.createPayload(settings, body)
            store.update(projectId) {
                it.copy(anchor = ChainAnchor(network = settings.network, recordSha256 = hash, payloadUuid = created.uuid, signUrl = created.signUrl))
            }
            openXaman(context, created.signUrl)
        } catch (e: XamanException) {
            if (e.authExpired) {
                settings.signOutXaman()
                start(context, projectId)
            } else say(e.message ?: "Chyba Xamanu")
        } catch (e: Exception) {
            say("Žádost o podpis se nepodařilo vytvořit: ${e.message}")
        }
    }

    fun openXaman(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { say("Nainstalujte aplikaci Xaman.") }
    }

    // ------------------------------------------------------------------ deep links back from Xaman

    /** Returns true if the URI was ours. */
    fun handleUri(context: Context, uri: Uri?): Boolean {
        if (uri == null || uri.scheme != "stockpilevolume" || uri.host != "xaman") return false
        val app = context.applicationContext
        when (uri.path) {
            "/auth" -> scope.launch {
                val settings = Settings(app)
                try {
                    val account = Xaman.completeSignIn(uri, settings)
                    say("Přihlášeno do Xamanu" + if (account.isNotBlank()) ": $account" else "")
                    val pending = settings.pendingAnchorProject
                    if (pending.isNotBlank()) {
                        settings.pendingAnchorProject = ""
                        _openProject.trySend(pending)
                        create(app, pending)
                    }
                } catch (e: Exception) {
                    say(e.message ?: "Přihlášení selhalo")
                }
            }
            "/signed" -> {
                val payload = uri.getQueryParameter("payload")
                val project = ProjectStore.get(app).projects.value.firstOrNull { it.anchor?.payloadUuid == payload }
                if (project != null) {
                    _openProject.trySend(project.id)
                    scope.launch { runCatching { refresh(app, project.id) } }
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ status

    /** Moves the anchor forward: pending → signed → validated (or rejected/expired/failed). */
    suspend fun refresh(context: Context, projectId: String): ChainAnchor? {
        val store = ProjectStore.get(context)
        val settings = Settings(context)
        var a = store.get(projectId)?.anchor ?: return null
        if (a.status == ChainAnchor.PENDING) {
            val s = try {
                Xaman.payloadStatus(settings, a.payloadUuid)
            } catch (e: XamanException) {
                if (e.authExpired) return a // can't see the payload any more; user can re-sign
                throw e
            }
            a = when {
                s.signed && s.txid != null -> a.copy(status = ChainAnchor.SIGNED, txid = s.txid, account = s.account, dispatchedResult = s.dispatchedResult)
                s.expired -> a.copy(status = ChainAnchor.EXPIRED)
                s.resolved && !s.signed -> a.copy(status = ChainAnchor.REJECTED)
                else -> a
            }
            store.update(projectId) { it.copy(anchor = a) }
        }
        if (a.status == ChainAnchor.SIGNED && a.txid != null) {
            val tx = Xrpl.tx(a.network, a.txid!!) ?: return a
            if (tx.validated) {
                val memoOk = tx.memos.any { it.type == MEMO_TYPE && it.data?.contains(a.recordSha256) == true }
                a = when {
                    tx.result != "tesSUCCESS" -> a.copy(status = ChainAnchor.FAILED, error = "Transakce selhala: ${tx.result}")
                    !memoOk -> a.copy(status = ChainAnchor.FAILED, error = "Transakce neobsahuje hash měření.")
                    else -> a.copy(status = ChainAnchor.VALIDATED, ledgerIndex = tx.ledgerIndex, account = tx.account ?: a.account,
                        validatedAt = Instant.now().toString())
                }
                store.update(projectId) { it.copy(anchor = a) }
                if (a.status == ChainAnchor.VALIDATED) say("Měření je zapsané a potvrzené v XRP Ledger.")
            }
        }
        return a
    }

    data class Verification(val ok: Boolean, val lines: List<String>)

    /** Independent check: file on the phone → its hash → memo of the validated transaction on the ledger. */
    suspend fun verify(context: Context, projectId: String): Verification {
        val store = ProjectStore.get(context)
        val p = store.get(projectId) ?: return Verification(false, listOf("Projekt nenalezen"))
        val a = p.anchor ?: return Verification(false, listOf("Měření ještě nebylo zapsáno."))
        val lines = ArrayList<String>()
        val fileHash = MeasurementSaver.fileHash(store, projectId)
        val fileOk = fileHash != null && fileHash == a.recordSha256
        lines += if (fileOk) "✓ Soubor record.json odpovídá zapsanému hashi" else "✗ Soubor record.json se liší od zapsaného hashe"
        val txid = a.txid ?: return Verification(false, lines + "✗ Transakce zatím není podepsaná")
        val tx = try { Xrpl.tx(a.network, txid) } catch (e: Exception) { return Verification(false, lines + "? Ledger nedostupný: ${e.message}") }
        if (tx == null) return Verification(false, lines + "✗ Transakce na ${a.network} nenalezena")
        lines += if (tx.validated) "✓ Transakce je ve validovaném ledgeru #${tx.ledgerIndex}" else "… Transakce čeká na validaci"
        lines += if (tx.result == "tesSUCCESS") "✓ Výsledek tesSUCCESS" else "✗ Výsledek ${tx.result}"
        val memoOk = tx.memos.any { it.type == MEMO_TYPE && it.data?.contains(a.recordSha256) == true }
        lines += if (memoOk) "✓ Memo obsahuje SHA-256 měření" else "✗ Memo neobsahuje hash měření"
        tx.account?.let { lines += "Podepsal účet $it" }
        return Verification(fileOk && tx.validated && tx.result == "tesSUCCESS" && memoOk, lines)
    }
}
