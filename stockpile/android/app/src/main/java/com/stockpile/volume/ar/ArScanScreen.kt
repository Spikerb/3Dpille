package com.stockpile.volume.ar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.opengl.GLSurfaceView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import com.stockpile.volume.capture.rememberLocation
import com.stockpile.volume.data.ProjectStore
import com.stockpile.volume.measure.MeasureException
import com.stockpile.volume.measure.MeasurementSaver
import com.stockpile.volume.measure.VolumeCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PERMS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun ArScanScreen(projectId: String, onFinished: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var camOk by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camOk = context.granted(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) { if (!camOk || !context.granted(Manifest.permission.ACCESS_FINE_LOCATION)) launcher.launch(PERMS) }

    if (!camOk) {
        Message(
            "Ke skenování je potřeba kamera. Poloha (GPS) je volitelná – zapíše se do záznamu měření.",
            primary = "Povolit" to { launcher.launch(PERMS) },
            secondary = "Zpět" to onCancel,
        )
        return
    }
    ArScanContent(projectId, onFinished, onCancel)
}

@Composable
private fun ArScanContent(projectId: String, onFinished: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember { ProjectStore.get(context) }
    val scope = rememberCoroutineScope()
    val hasGps = remember { context.granted(Manifest.permission.ACCESS_FINE_LOCATION) }
    val noLocation = remember { mutableStateOf<Location?>(null) }
    val location by if (hasGps) rememberLocation() else noLocation

    var scanner by remember { mutableStateOf<ArScanner?>(null) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installRequested by remember { mutableStateOf(false) }
    var computing by remember { mutableStateOf(false) }
    var confirmOpenLoop by remember { mutableStateOf(false) }
    var computeError by remember { mutableStateOf<String?>(null) }
    val idleStats = remember { MutableStateFlow(ScanStats()) }

    // Keep the screen on while scanning.
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (scanner == null && error == null) {
                        try {
                            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return@LifecycleEventObserver }
                                ArCoreApk.InstallStatus.INSTALLED -> Unit
                            }
                            val session = Session(context)
                            val source = ArScanner.configure(session)
                            @Suppress("DEPRECATION")
                            val s = ArScanner(session, source) { activity.windowManager.defaultDisplay.rotation }
                            scanner = s
                            glView = GLSurfaceView(context).apply {
                                preserveEGLContextOnPause = true
                                setEGLContextClientVersion(2)
                                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                                setRenderer(s)
                                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                                setWillNotDraw(false)
                            }
                        } catch (e: UnavailableException) {
                            error = "Tento telefon nepodporuje ARCore (Google Play služby pro AR), nebo nejsou nainstalované.\n(${e.javaClass.simpleName})"
                            return@LifecycleEventObserver
                        } catch (e: Exception) {
                            error = "AR se nepodařilo spustit: ${e.message}"
                            return@LifecycleEventObserver
                        }
                    }
                    if (!computing) {
                        runCatching { scanner?.session?.resume() }.onFailure { error = "Kameru nelze spustit: ${it.message}" }
                        glView?.onResume()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    glView?.onPause()
                    scanner?.session?.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView?.onPause()
            scanner?.session?.close()
        }
    }

    error?.let {
        Message(it, primary = "Zpět" to onCancel)
        return
    }

    fun finish() {
        val s = scanner ?: return
        s.recording = false
        computing = true
        glView?.onPause()
        s.session.pause()
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val snap = s.cloud.snapshot()
                    val outcome = VolumeCalculator.compute(snap, s.pathSnapshot())
                    MeasurementSaver.save(store, projectId, outcome, s.depthSource.method, snap.size, location)
                }
            }
            result.onSuccess { onFinished() }.onFailure { e ->
                computing = false
                computeError = if (e is MeasureException) e.message else "Výpočet selhal: ${e.message}"
            }
        }
    }

    val stats by (scanner?.stats ?: idleStats).collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        glView?.let { v -> AndroidView(factory = { v }, modifier = Modifier.fillMaxSize()) }

        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)
                .background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(12.dp)
        ) {
            Text(
                "Obejděte pomalu celou hromadu 2–4 m od její paty. Mířte na hromadu i na zem před ní, " +
                    "telefon držte klidně. Na konci se vraťte na místo, kde jste začali.",
                color = Color.White, fontSize = 13.sp,
            )
            Spacer(Modifier.size(6.dp))
            val hint = stats.hint
            Text(
                hint ?: "Skenuji (${scanner?.depthSource?.label ?: "…"})",
                color = if (hint != null || !stats.tracking) Color(0xFFFFD180) else Color(0xFFB9F6CA),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            if (location == null) Text("GPS: čekám na polohu (nepovinné)", color = Color(0xFFBBBBBB), fontSize = 12.sp)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .background(Color(0xAA000000)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Trasa %.1f m".format(stats.pathLengthM), color = Color.White)
                Text("Bodů %,d".format(stats.voxels), color = Color.White)
            }
            Text(
                when {
                    stats.loopClosed -> "✓ Okruh uzavřen – můžete dokončit"
                    stats.pathLengthM > 5 -> "Ke startu zbývá %.1f m".format(stats.distanceToStartM)
                    else -> "Začněte obcházet hromadu"
                },
                color = if (stats.loopClosed) Color(0xFFB9F6CA) else Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel, enabled = !computing) { Text("Zrušit", color = Color.White) }
                Button(
                    onClick = { if (stats.loopClosed) finish() else confirmOpenLoop = true },
                    enabled = !computing && stats.voxels > 2000 && stats.pathLengthM > 3,
                    modifier = Modifier.weight(1f),
                ) { Text("Dokončit měření") }
            }
        }

        if (computing) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(12.dp))
                    Text("Počítám objem…", color = Color.White)
                }
            }
        }
    }

    computeError?.let { msg ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Měření nelze dokončit") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    computeError = null
                    // Carry on scanning with everything collected so far.
                    runCatching { scanner?.session?.resume() }
                    glView?.onResume()
                    scanner?.recording = true
                }) { Text("Skenovat dál") }
            },
            dismissButton = { TextButton(onClick = { computeError = null; onCancel() }) { Text("Zrušit sken") } },
        )
    }

    if (confirmOpenLoop) {
        AlertDialog(
            onDismissRequest = { confirmOpenLoop = false },
            title = { Text("Okruh není uzavřený") },
            text = { Text("Hromada nebyla obejita celá. Objem bude počítán jen uvnitř prošlé trasy a může vyjít menší. Dokončit i tak?") },
            confirmButton = { TextButton(onClick = { confirmOpenLoop = false; finish() }) { Text("Dokončit") } },
            dismissButton = { TextButton(onClick = { confirmOpenLoop = false }) { Text("Pokračovat ve skenu") } },
        )
    }
}

@Composable
private fun Message(text: String, primary: Pair<String, () -> Unit>, secondary: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(16.dp))
        Button(onClick = primary.second) { Text(primary.first) }
        secondary?.let { TextButton(onClick = it.second) { Text(it.first) } }
    }
}
