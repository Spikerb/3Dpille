@file:OptIn(ExperimentalMaterial3Api::class)

package com.stockpile.volume.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import com.google.ar.core.ArCoreApk
import androidx.work.WorkManager
import com.stockpile.volume.chain.Anchoring
import com.stockpile.volume.chain.Xrpl
import com.stockpile.volume.data.ChainAnchor
import com.stockpile.volume.data.MeasurementRecord
import com.stockpile.volume.data.Project
import com.stockpile.volume.data.ProjectStore
import com.stockpile.volume.data.RemoteJob
import com.stockpile.volume.data.Settings
import com.stockpile.volume.measure.MeasurementSaver
import com.stockpile.volume.report.Report
import com.stockpile.volume.report.ReportLang
import com.stockpile.volume.work.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ProjectScreen(
    projectId: String,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onBoundary: () -> Unit,
    onCapture: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ProjectStore.get(context) }
    val settings = remember { Settings(context) }
    val projects by store.projects.collectAsState()
    val p = projects.firstOrNull { it.id == projectId }
    var confirmDelete by remember { mutableStateOf(false) }
    // null = unknown yet, false = this phone can't run ARCore (offer manual measurement instead)
    val arSupported = remember {
        runCatching {
            val a = ArCoreApk.getInstance().checkAvailability(context)
            if (a == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) false else if (a.isSupported) true else null
        }.getOrNull()
    }

    if (p == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") } },
                actions = {
                    if (p.measurement != null) {
                        IconButton(onClick = { shareMeasurement(context, store, p) }) { Icon(Icons.Default.Share, "Sdílet") }
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Smazat") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val m = p.measurement
            if (m == null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Změřit hromadu", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Telefon hromadu naskenuje ve 3D během obcházení a objem spočítá sám – bez internetu. " +
                                "Hodí se pro hromady zhruba do 15 m průměru.",
                        )
                        if (arSupported != false) {
                            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Spustit 3D sken") }
                            OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Ruční měření (bez AR)") }
                        } else {
                            Text(
                                "Tento telefon nepodporuje ARCore, 3D sken tu nepůjde. Použijte ruční měření: " +
                                    "zvolíte tvar hromady a zadáte rozměry, výšku pomůže změřit sklonoměr.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Ruční měření") }
                        }
                    }
                }
            } else {
                MeasurementCard(store, p, m, onBoundary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (arSupported != false) {
                        OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Znovu skenem") }
                    }
                    OutlinedButton(onClick = onManual, modifier = Modifier.weight(1f)) { Text("Znovu ručně") }
                }
                ChainCard(p, settings, onSettings)
            }

            if (settings.isConfigured || p.photoCount > 0 || p.remote != null) {
                ServerCard(p, settings, onCapture, onSettings)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Smazat měření?") },
            text = {
                Text(
                    "Všechna data „${p.name}“ budou z telefonu smazána." +
                        if (p.anchor?.status == ChainAnchor.VALIDATED) " Zápis v blockchainu zůstane (nelze ho smazat), ale bez souboru record.json už nepůjde ověřit." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    WorkManager.getInstance(context).cancelUniqueWork(UploadWorker.workName(projectId))
                    val jobId = p.remoteJobId
                    val api = settings.client()
                    if (jobId != null && api != null) {
                        CoroutineScope(Dispatchers.IO).launch { runCatching { api.deleteJob(jobId) } }
                    }
                    store.delete(projectId)
                    onBack()
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušit") } },
        )
    }
}

// ---------------------------------------------------------------------------- measurement

@Composable
private fun MeasurementCard(store: ProjectStore, p: Project, m: MeasurementRecord, onBoundary: () -> Unit) {
    val canEditBoundary = remember(m.id) { MeasurementSaver.hasSurface(store, p.id) }
    var confirmBoundary by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var preview by remember(m.id) { mutableStateOf<ImageBitmap?>(null) }
    var showGps by remember { mutableStateOf(false) }
    var showWater by remember { mutableStateOf(false) }
    var confirmWater by remember { mutableStateOf(false) }
    var confirmGps by remember { mutableStateOf(false) }
    val anchored = p.anchor != null && p.anchorMatches && p.anchor.status != ChainAnchor.REJECTED &&
        p.anchor.status != ChainAnchor.EXPIRED && p.anchor.status != ChainAnchor.FAILED
    LaunchedEffect(m.id) {
        preview = withContext(Dispatchers.IO) {
            val f = File(MeasurementSaver.measurementDir(store, p.id), MeasurementSaver.PREVIEW_FILE)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Objem", style = MaterialTheme.typography.labelLarge)
            Text("${fmt(m.volumeM3, 1)} m³", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            m.tonnes?.let { Text("≈ ${fmt(it, 1)} t při ${fmt(m.densityTM3 ?: 0.0, 2)} t/m³", style = MaterialTheme.typography.titleMedium) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Stat("Změřeno", formatInstant(m.measuredAt))
            Stat("Půdorys", "${fmt(m.footprintM2, 0)} m²")
            Stat("Max. výška", "${fmt(m.maxHeightM, 2)} m")
            if (m.inputs == null) {
                Stat("Naskenováno plochy", "${fmt(m.coveragePct, 0)} %")
                Stat("Sklon / rovinnost terénu", "${fmt(m.baseSlopePct, 1)} % / ±${fmt(m.baseRmseM * 100, 0)} cm")
            } else {
                m.inputs.forEach { (k, v) -> Stat(inputLabel(k), "${fmt(v, 2)} m") }
            }
            Stat("Metoda", m.method)
            if (canEditBoundary) {
                Text(
                    if (m.boundary == null) "Hranice: trasa obchůzky (může zahrnout i okolní předměty)"
                    else "Hranice: nakreslená – " + m.boundary.replace("circle r=", "kružnice r = ").replace("polygon", "mnohoúhelník").replace(" pts", " bodů"),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { if (anchored) confirmBoundary = true else onBoundary() }) {
                    Text(if (m.boundary == null) "Ohraničit hromadu (kružnice / přímky)" else "Upravit hranici hromady")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Voda v objemu", style = MaterialTheme.typography.labelLarge)
            if (m.waterCoef == null) {
                Text("Koeficient vody není zadaný.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Stat("Koeficient vody", "${fmt(m.waterCoef, 3)} (${fmt(m.waterCoef * 100, 1)} %)")
                m.waterM3?.let { Stat("Voda", "${fmt(it, 2)} m³ ≈ ${fmt(it, 2)} t") }
                m.dryVolumeM3?.let { Stat("Objem bez vody", "${fmt(it, 2)} m³") }
                m.dryTonnes?.let { Stat("Sušina", "≈ ${fmt(it, 2)} t") }
            }
            OutlinedButton(onClick = { if (anchored) confirmWater = true else showWater = true }) {
                Text(if (m.waterCoef == null) "Zadat koeficient vody" else "Změnit koeficient vody")
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("GPS poloha", style = MaterialTheme.typography.labelLarge)
            val g = m.location
            if (g == null) {
                Text("Poloha zatím není zaměřená.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(String.format(java.util.Locale.US, "%.5f, %.5f", g.lat, g.lon), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                val acc = g.accuracyM
                Text(
                    (if (acc != null) "přesnost ±%.1f m".format(acc) else "přesnost neznámá") +
                        if (g.source == "gps-average") " · průměr z ${g.samples ?: 0} měření" else " · jednorázové měření",
                    color = if (acc != null && acc <= 1.0) MaterialTheme.colorScheme.tertiary
                    else if (acc != null && acc <= 3.0) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { if (anchored) confirmGps = true else showGps = true }) {
                Text(if (g == null) "Zaměřit GPS polohu" else "Zaměřit polohu přesněji")
            }
            m.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            preview?.let {
                Text("Výšková mapa (pohled shora)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Image(it, "Výšková mapa", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
            Text("SHA-256: ${p.recordSha256}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Button(onClick = { showReport = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("Závěrečný protokol (PDF)")
            }
        }
    }
    if (showReport) ReportDialog(p) { showReport = false }

    if (showGps) {
        GpsFixDialog(
            onResult = { geo -> showGps = false; MeasurementSaver.updateLocation(store, p.id, geo) },
            onDismiss = { showGps = false },
        )
    }
    if (showWater) {
        var text by remember { mutableStateOf(m.waterCoef?.let { fmt(it, 3) } ?: "") }
        val value = parseWaterCoef(text)
        val ok = text.isBlank() || value != null
        AlertDialog(
            onDismissRequest = { showWater = false },
            title = { Text("Koeficient vody") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WaterField(text, ok) { text = it }
                    if (value != null) {
                        Text(
                            "Voda: ${fmt(m.volumeM3 * value, 2)} m³ · bez vody: ${fmt(m.volumeM3 * (1 - value), 2)} m³",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    showWater = false
                    MeasurementSaver.updateWater(store, p.id, value)
                }) { Text(if (text.isBlank()) "Odebrat" else "Uložit") }
            },
            dismissButton = { TextButton(onClick = { showWater = false }) { Text("Zrušit") } },
        )
    }
    if (confirmBoundary) {
        AlertDialog(
            onDismissRequest = { confirmBoundary = false },
            title = { Text("Měření už je v blockchainu") },
            text = { Text("Nová hranice změní objem i záznam, takže ho bude potřeba zapsat do blockchainu znovu. Pokračovat?") },
            confirmButton = { TextButton(onClick = { confirmBoundary = false; onBoundary() }) { Text("Pokračovat") } },
            dismissButton = { TextButton(onClick = { confirmBoundary = false }) { Text("Zrušit") } },
        )
    }
    if (confirmWater) {
        AlertDialog(
            onDismissRequest = { confirmWater = false },
            title = { Text("Měření už je v blockchainu") },
            text = { Text("Změna koeficientu vody změní záznam, takže ho bude potřeba zapsat do blockchainu znovu. Pokračovat?") },
            confirmButton = { TextButton(onClick = { confirmWater = false; showWater = true }) { Text("Pokračovat") } },
            dismissButton = { TextButton(onClick = { confirmWater = false }) { Text("Zrušit") } },
        )
    }
    if (confirmGps) {
        AlertDialog(
            onDismissRequest = { confirmGps = false },
            title = { Text("Měření už je v blockchainu") },
            text = { Text("Nová poloha změní záznam, takže ho bude potřeba zapsat do blockchainu znovu. Původní zápis v ledgeru zůstane, ale nebude odpovídat novému záznamu. Pokračovat?") },
            confirmButton = { TextButton(onClick = { confirmGps = false; showGps = true }) { Text("Pokračovat") } },
            dismissButton = { TextButton(onClick = { confirmGps = false }) { Text("Zrušit") } },
        )
    }
}

// ---------------------------------------------------------------------------- blockchain

@Composable
private fun ChainCard(p: Project, settings: Settings, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = p.anchor
    var verification by remember { mutableStateOf<Anchoring.Verification?>(null) }
    var busy by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }

    // Follow the anchor until it is final: Xaman status first, then ledger validation.
    LaunchedEffect(a?.payloadUuid, a?.status) {
        if (a == null || a.isFinal) return@LaunchedEffect
        while (true) {
            refreshError = runCatching { Anchoring.refresh(context.applicationContext, p.id) }.exceptionOrNull()?.message
            delay(if (a.status == ChainAnchor.SIGNED) 3_000 else 5_000)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Blockchain (XRP Ledger)", style = MaterialTheme.typography.titleMedium)
            val stale = a != null && !p.anchorMatches
            if (a == null || stale) {
                    if (stale) Text("Zápis patří k předchozímu měření. Nové měření zatím zapsané není.", color = MaterialTheme.colorScheme.error)
                    Text(
                        "Zapíše do ${netName(settings.network)} objem, čas a otisk (SHA-256) celého záznamu. " +
                            "Zapíše se i GPS poloha (na ~1 m). Transakci potvrdíte v aplikaci Xaman; poplatek je zlomek haléře.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val loc = p.measurement?.location
                    if (loc == null || (loc.accuracyM ?: 99.0) > 1.0) {
                        Text(
                            "Tip: před zápisem zaměřte GPS polohu (tlačítko výše), aby byla v blockchainu co nejpřesnější.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Pravidlo: stejné místo (±30 m) lze zapsat jen jednou za 100 dní – aplikace to před zápisem ověří v blockchainu.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (p.measurement?.location == null) {
                        Text("Pro zápis je potřeba GPS poloha – zaměřte ji výše.", color = MaterialTheme.colorScheme.error)
                    } else if (!com.stockpile.volume.chain.Registry.isAddress(settings.registryAddress)) {
                        Text("Nejdřív zadejte v Nastavení evidenční XRPL adresu.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onSettings) { Text("Otevřít nastavení") }
                    } else if (settings.xamanApiKey.isBlank()) {
                        Text("Nejdřív zadejte Xaman API klíč.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onSettings) { Text("Otevřít nastavení") }
                    } else {
                        Button(onClick = { Anchoring.start(context, p.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (settings.xamanSignedIn) "Zapsat do blockchainu" else "Přihlásit se do Xamanu a zapsat")
                        }
                    }
            } else when (a.status) {
                ChainAnchor.PENDING -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.padding(2.dp))
                        Text("Čekám na podpis v Xamanu…")
                    }
                    a.signUrl?.let { url -> OutlinedButton(onClick = { Anchoring.openXaman(context, url) }) { Text("Otevřít Xaman") } }
                    TextButton(onClick = { Anchoring.start(context, p.id) }) { Text("Vytvořit novou žádost") }
                }
                ChainAnchor.SIGNED -> {
                    Text("Podepsáno, čekám na potvrzení v ledgeru…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    a.txid?.let { Mono("TX: $it") }
                }
                ChainAnchor.VALIDATED -> {
                    Text("✓ Zapsáno a potvrzeno", color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
                    Stat("Síť", netName(a.network))
                    a.ledgerIndex?.let { Stat("Ledger", "#$it") }
                    a.account?.let { Stat("Účet", it.take(8) + "…" + it.takeLast(6)) }
                    a.txid?.let { Mono("TX: $it") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.txid?.let { tx ->
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Xrpl.explorerUrl(a.network, tx))))
                            }) { Text("Průzkumník") }
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                verification = Anchoring.verify(context.applicationContext, p.id)
                                busy = false
                            }
                        }) { Text(if (busy) "Ověřuji…" else "Ověřit") }
                    }
                }
                else -> {
                    Text(
                        when (a.status) {
                            ChainAnchor.REJECTED -> "Podpis byl v Xamanu odmítnut."
                            ChainAnchor.EXPIRED -> "Žádost o podpis vypršela."
                            else -> a.error ?: "Zápis se nezdařil."
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { Anchoring.start(context, p.id) }, modifier = Modifier.fillMaxWidth()) { Text("Zkusit znovu") }
                }
            }
            refreshError?.let { Text("Stav nelze načíst: $it", style = MaterialTheme.typography.bodySmall) }
            verification?.let { v ->
                HorizontalDivider()
                Text(if (v.ok) "Ověření v pořádku" else "Ověření NEPROŠLO", fontWeight = FontWeight.Bold,
                    color = if (v.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                v.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

private fun inputLabel(k: String) = when (k) {
    "height_m" -> "Výška (zadáno)"
    "perimeter_m" -> "Obvod paty"
    "diameter_m" -> "Průměr paty"
    "length_m" -> "Délka paty"
    "width_m" -> "Šířka paty"
    "top_length_m" -> "Délka horní plochy"
    "top_width_m" -> "Šířka horní plochy"
    else -> k
}

private fun netName(n: String) = if (n == Xrpl.TESTNET) "XRPL Testnet" else "XRPL Mainnet"

// ---------------------------------------------------------------------------- optional server mode

@Composable
private fun ServerCard(p: Project, settings: Settings, onCapture: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProjectStore.get(context) }
    val workInfos by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(UploadWorker.workName(p.id))
        .collectAsState(initial = emptyList())
    val work = workInfos.firstOrNull()

    LaunchedEffect(p.remoteJobId, p.remote?.isActive) {
        val jobId = p.remoteJobId ?: return@LaunchedEffect
        if (p.remote?.isActive != true) return@LaunchedEffect
        val api = settings.client() ?: return@LaunchedEffect
        while (true) {
            runCatching { api.getJob(jobId) }.onSuccess { job ->
                store.update(p.id) { it.copy(remote = job) }
                if (!job.isActive) return@LaunchedEffect
            }
            delay(8_000)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fotogrammetrie na serveru (volitelné)", style = MaterialTheme.typography.titleMedium)
            Text("Pro velké hromady: fotky se zpracují na vašem serveru (OpenDroneMap).", style = MaterialTheme.typography.bodySmall)
            Text("${p.photoCount} fotek · ${p.coveredSectors * 100 / Project.SECTORS} % dokola")
            val remote = p.remote
            remote?.result?.let { r ->
                Text("Výsledek serveru: ${fmt(r.volumeM3, 1)} m³" + (r.tonnes?.let { " · ${fmt(it, 1)} t" } ?: ""), fontWeight = FontWeight.Bold)
            }
            val canAdd = remote == null || remote.status in setOf(RemoteJob.CREATED, RemoteJob.FAILED)
            val uploading = work?.state == WorkInfo.State.RUNNING || work?.state == WorkInfo.State.ENQUEUED
            when {
                work?.state == WorkInfo.State.RUNNING -> {
                    val done = work!!.progress.getInt(UploadWorker.KEY_DONE, 0)
                    val total = work!!.progress.getInt(UploadWorker.KEY_TOTAL, p.photoCount).coerceAtLeast(1)
                    Text("Nahrávám $done / $total…")
                    LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                }
                work?.state == WorkInfo.State.ENQUEUED -> Text("Čekám na připojení k internetu…")
                remote != null && remote.isActive -> {
                    Text("Server zpracovává… ${remote.progress} %")
                    LinearProgressIndicator(progress = { remote.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                remote?.status == RemoteJob.FAILED -> Text("Zpracování na serveru selhalo.", color = MaterialTheme.colorScheme.error)
                work?.state == WorkInfo.State.FAILED ->
                    Text(work!!.outputData.getString(UploadWorker.KEY_ERROR) ?: "Nahrávání selhalo.", color = MaterialTheme.colorScheme.error)
            }
            if (canAdd && !uploading) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCapture) { Text(if (p.photoCount == 0) "Fotit" else "Další fotky") }
                    if (!settings.isConfigured) OutlinedButton(onClick = onSettings) { Text("Nastavit server") }
                    else Button(enabled = p.photoCount >= 10, onClick = { UploadWorker.enqueue(context, p.id) }) { Text("Odeslat") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- helpers

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Mono(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

/** Shares record.json, the height map and the raw grid, plus a short text summary. */
private fun shareMeasurement(context: android.content.Context, store: ProjectStore, p: Project) {
    val m = p.measurement ?: return
    val dir = MeasurementSaver.measurementDir(store, p.id)
    val auth = context.packageName + ".files"
    val uris = arrayListOf<Uri>()
    listOf(MeasurementSaver.RECORD_FILE, MeasurementSaver.PREVIEW_FILE, MeasurementSaver.HEIGHTMAP_FILE).forEach { name ->
        val f = File(dir, name)
        if (f.exists()) uris += FileProvider.getUriForFile(context, auth, f)
    }
    val text = buildString {
        appendLine("Hromada: ${m.project}")
        appendLine("Objem: ${fmt(m.volumeM3, 1)} m³")
        m.tonnes?.let { appendLine("Hmotnost: ≈ ${fmt(it, 1)} t (${m.densityTM3} t/m³)") }
        m.waterCoef?.let { k ->
            appendLine("Koeficient vody: ${fmt(k, 3)} → voda ${fmt(m.waterM3 ?: 0.0, 2)} m³, bez vody ${fmt(m.dryVolumeM3 ?: 0.0, 2)} m³" +
                (m.dryTonnes?.let { ", sušina ≈ ${fmt(it, 2)} t" } ?: ""))
        }
        appendLine("Půdorys ${fmt(m.footprintM2, 0)} m², max. výška ${fmt(m.maxHeightM, 2)} m")
        appendLine("Změřeno: ${formatInstant(m.measuredAt)}")
        m.location?.let { g ->
            appendLine(String.format(java.util.Locale.US, "GPS: %.5f, %.5f", g.lat, g.lon) + (g.accuracyM?.let { " (±%.1f m)".format(it) } ?: ""))
            appendLine(String.format(java.util.Locale.US, "Mapa: https://maps.google.com/?q=%.5f,%.5f", g.lat, g.lon))
        }
        appendLine("SHA-256 záznamu: ${p.recordSha256}")
        val a = p.anchor
        if (a != null && a.status == ChainAnchor.VALIDATED && a.txid != null && p.anchorMatches) {
            append("Zapsáno v XRP Ledger: ${Xrpl.explorerUrl(a.network, a.txid)}")
        }
    }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "Měření hromady ${m.project}")
        if (uris.isNotEmpty()) {
            clipData = ClipData.newRawUri("", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Sdílet měření"))
}

@Composable
private fun ReportDialog(p: Project, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf(ReportLang.of(settings.reportLanguage)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Závěrečný protokol") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Jazyk protokolu", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReportLang.entries.forEach { l ->
                        FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(l.label) })
                    }
                }
                if (p.anchor?.status != ChainAnchor.VALIDATED || !p.anchorMatches) {
                    Text("Měření zatím není potvrzené v blockchainu – protokol to uvede.", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                settings.reportLanguage = lang.code
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Report.build(context, p, lang) } }
                    busy = false
                    r.onSuccess { f ->
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Protokol"))
                        onClose()
                    }.onFailure { error = "Protokol se nepodařilo vytvořit: ${it.message}" }
                }
            }) { Text(if (busy) "Vytvářím…" else "Vytvořit a sdílet") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Zrušit") } },
    )
}
