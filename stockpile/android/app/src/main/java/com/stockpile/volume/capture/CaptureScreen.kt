package com.stockpile.volume.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stockpile.volume.data.Project
import com.stockpile.volume.data.ProjectStore
import com.stockpile.volume.data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.sin

private val PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun Context.hasPermissions() = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
    .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun CaptureScreen(projectId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasPermissions()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.hasPermissions()
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(PERMISSIONS) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Je potřeba kamera a přesná poloha: každá fotka nese GPS, " +
                    "aby měl 3D model správnou polohu a měřítko.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = { launcher.launch(PERMISSIONS) }) { Text("Povolit") }
            TextButton(onClick = onDone) { Text("Zpět") }
        }
        return
    }
    CaptureContent(projectId, onDone)
}

private suspend fun Context.cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(this))
}

@Composable
private fun CaptureContent(projectId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val store = remember { ProjectStore.get(context) }
    val settings = remember { Settings(context) }
    val projects by store.projects.collectAsState()
    val project = projects.firstOrNull { it.id == projectId } ?: return

    val location by rememberLocation()
    val heading by rememberCameraHeading()

    val saveExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { saveExecutor.shutdown() } }

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()
    }

    LaunchedEffect(Unit) {
        val provider = context.cameraProvider()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
    }

    var busy by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }

    fun shoot() {
        if (busy) return
        busy = true
        val loc: Location? = location
        val shotHeading = heading
        val file = store.newPhotoFile(projectId)
        val metadata = ImageCapture.Metadata().apply { this.location = loc }
        val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
        imageCapture.takePicture(options, saveExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // Make sure the GPS tag is in the file, whatever the camera stack did.
                if (loc != null) runCatching {
                    ExifInterface(file.absolutePath).apply { setGpsInfo(loc); saveAttributes() }
                }
                store.addPhoto(projectId, file.name, shotHeading)
                flash = true
                busy = false
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                busy = false
                ContextCompat.getMainExecutor(context).execute {
                    Toast.makeText(context, "Fotka selhala: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    LaunchedEffect(auto) {
        while (auto) {
            shoot()
            delay((settings.autoIntervalSec * 1000).toLong())
        }
    }
    LaunchedEffect(flash) { if (flash) { delay(120); flash = false } }
    val flashAlpha by animateFloatAsState(if (flash) 0.6f else 0f, label = "flash")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().alpha(flashAlpha).background(Color.White))

        // Top: guidance + GPS status
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)
                .background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(12.dp)
        ) {
            Text(
                "Obcházejte hromadu 3–5 m od paty, celá hromada v záběru. " +
                    "Jedna fotka na krok. U vysokých hromad udělejte druhé, širší kolo.",
                color = Color.White, fontSize = 13.sp,
            )
            Spacer(Modifier.size(6.dp))
            GpsStatus(location)
        }

        // Bottom: coverage ring, shutter, auto toggle
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .background(Color(0x99000000)).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                CoverageRing(project, heading, Modifier.size(76.dp))
                Text("${project.coveredSectors * 100 / Project.SECTORS} % dokola", color = Color.White, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(6.dp)
                        .background(if (auto) Color(0xFFE53935) else Color.White, CircleShape)
                        .clickable(enabled = !auto) { shoot() }
                )
                Text("${project.photoCount} fotek", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                FilterChip(selected = auto, onClick = { auto = !auto }, label = { Text(if (auto) "Auto ZAP" else "Auto") })
                TextButton(onClick = { auto = false; onDone() }) { Text("Hotovo", color = Color.White) }
            }
        }
    }
}

@Composable
private fun GpsStatus(location: Location?) {
    val (text, color) = when {
        location == null -> "Čekám na GPS… fotky bez GPS nelze změřit" to Color(0xFFFF8A80)
        location.accuracy > 10f -> "Slabé GPS (±${location.accuracy.toInt()} m) – jděte pod otevřené nebe" to Color(0xFFFFD180)
        else -> "GPS v pořádku (±${location.accuracy.toInt()} m)" to Color(0xFFB9F6CA)
    }
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/** 36 compass sectors around the pile; green = photographed from that direction. */
@Composable
private fun CoverageRing(project: Project, heading: Float, modifier: Modifier) {
    val covered = remember(project.photoHeadings) {
        BooleanArray(Project.SECTORS).also { arr -> project.photoHeadings.values.forEach { arr[Project.sectorOf(it)] = true } }
    }
    Canvas(modifier) {
        val stroke = size.minDimension * 0.14f
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        for (i in 0 until Project.SECTORS) {
            drawArc(
                color = if (covered[i]) Color(0xFF66BB6A) else Color(0x55FFFFFF),
                startAngle = i * 10f - 90f + 0.8f, sweepAngle = 8.4f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke),
            )
        }
        // Needle: current camera direction (north = up)
        val r = size.minDimension / 2 - stroke * 1.4f
        val a = Math.toRadians((heading - 90f).toDouble())
        drawLine(
            Color.White, center,
            Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat()),
            strokeWidth = 4f,
        )
        drawCircle(Color.White, radius = 4f, center = center)
    }
}
