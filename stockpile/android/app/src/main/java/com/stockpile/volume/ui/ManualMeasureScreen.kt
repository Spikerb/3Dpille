@file:OptIn(ExperimentalMaterial3Api::class)

package com.stockpile.volume.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.stockpile.volume.capture.rememberLocation
import com.stockpile.volume.data.ProjectStore
import com.stockpile.volume.measure.ManualInput
import com.stockpile.volume.measure.ManualShapes
import com.stockpile.volume.measure.MeasurementSaver
import com.stockpile.volume.measure.PileShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.asin

private fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun ManualMeasureScreen(projectId: String, onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProjectStore.get(context) }
    val project = remember { store.get(projectId) }
    val scope = rememberCoroutineScope()

    var shape by remember { mutableStateOf(PileShape.CONE) }
    var byPerimeter by remember { mutableStateOf(true) }
    var height by remember { mutableStateOf("") }
    var perimeter by remember { mutableStateOf("") }
    var diameter by remember { mutableStateOf("") }
    var length by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("") }
    var topLength by remember { mutableStateOf("") }
    var topWidth by remember { mutableStateOf("") }
    var showClinometer by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // GPS is optional – only used if the permission was already granted.
    val hasGps = remember { context.granted(Manifest.permission.ACCESS_FINE_LOCATION) }
    val noLocation = remember { mutableStateOf<Location?>(null) }
    val location by if (hasGps) rememberLocation() else noLocation

    val input = ManualInput(
        shape = shape,
        height = num(height) ?: 0.0,
        perimeter = if (shape == PileShape.CONE && byPerimeter) num(perimeter) else null,
        diameter = if (shape == PileShape.CONE && !byPerimeter) num(diameter) else null,
        length = if (shape != PileShape.CONE) num(length) else null,
        width = if (shape != PileShape.CONE) num(width) else null,
        topLength = if (shape == PileShape.FRUSTUM) num(topLength) else null,
        topWidth = if (shape == PileShape.FRUSTUM) num(topWidth) else null,
    )
    val problem = ManualShapes.validate(input)
    val volume = if (problem == null) ManualShapes.volume(input) else null

    if (showClinometer) {
        Clinometer(
            onResult = { h -> height = "%.2f".format(h).replace(',', '.'); showClinometer = false },
            onCancel = { showClinometer = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ruční měření") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Tvar hromady", style = MaterialTheme.typography.titleMedium)
            PileShape.entries.forEach { s ->
                FilterChip(selected = shape == s, onClick = { shape = s }, label = { Text(s.label) }, modifier = Modifier.fillMaxWidth())
            }
            Text(shape.hint, style = MaterialTheme.typography.bodySmall)

            Text("Rozměry (metry)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            when (shape) {
                PileShape.CONE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = byPerimeter, onClick = { byPerimeter = true }, label = { Text("Obvod paty") })
                        FilterChip(selected = !byPerimeter, onClick = { byPerimeter = false }, label = { Text("Průměr paty") })
                    }
                    if (byPerimeter) NumField("Obvod paty", perimeter, "obejděte patu s pásmem, nebo počet kroků × délka kroku") { perimeter = it }
                    else NumField("Průměr paty", diameter, "přes střed hromady, od paty k patě") { diameter = it }
                }
                PileShape.WINDROW -> {
                    NumField("Délka paty", length, "od konce ke konci") { length = it }
                    NumField("Šířka paty", width, "napříč valem") { width = it }
                }
                PileShape.FRUSTUM -> {
                    NumField("Délka paty", length, null) { length = it }
                    NumField("Šířka paty", width, null) { width = it }
                    NumField("Délka horní plochy", topLength, "0 = vrchol do špičky") { topLength = it }
                    NumField("Šířka horní plochy", topWidth, null) { topWidth = it }
                }
            }
            NumField("Výška", height, "svisle od paty k vrcholu") { height = it }
            OutlinedButton(onClick = { showClinometer = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Změřit výšku sklonoměrem")
            }

            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    if (volume != null) {
                        Text("Objem", style = MaterialTheme.typography.labelLarge)
                        Text("${fmt(volume, 1)} m³", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        project?.densityTM3?.let { Text("≈ ${fmt(volume * it, 1)} t", style = MaterialTheme.typography.titleMedium) }
                        Text("Půdorys ${fmt(ManualShapes.footprint(input), 1)} m²", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(problem ?: "", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(
                "Ruční měření je odhad podle zvoleného tvaru (přesnost asi ±10–20 %). Uloží se a jde zapsat do blockchainu stejně jako 3D sken.",
                style = MaterialTheme.typography.bodySmall,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = volume != null && !saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    saving = true
                    scope.launch {
                        val r = withContext(Dispatchers.Default) {
                            runCatching {
                                MeasurementSaver.save(
                                    store, projectId, ManualShapes.outcome(input), shape.method,
                                    voxels = 0, location = location, inputs = input.asMap(),
                                )
                            }
                        }
                        saving = false
                        r.onSuccess { onDone() }.onFailure { error = "Uložení selhalo: ${it.message}" }
                    }
                },
            ) { Text(if (saving) "Ukládám…" else "Uložit měření") }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, help: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        supportingText = if (help != null) { { Text(help) } } else null,
        singleLine = true,
        suffix = { Text("m") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------- clinometer

/** Elevation of the back camera's optical axis above the horizon, in degrees (smoothed). */
@Composable
private fun rememberCameraElevation(): State<Float> {
    val context = LocalContext.current
    val state = remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val r = FloatArray(9)
        var smooth = Float.NaN
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, e.values)
                // Camera looks along the device's -Z axis; r[8] is the world-up component of +Z.
                val deg = Math.toDegrees(asin((-r[8]).coerceIn(-1f, 1f).toDouble())).toFloat()
                smooth = if (smooth.isNaN()) deg else smooth + (deg - smooth) * 0.15f
                state.floatValue = smooth
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}

@Composable
private fun Clinometer(onResult: (Double) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camOk by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camOk = it }
    LaunchedEffect(Unit) { if (!camOk) launcher.launch(Manifest.permission.CAMERA) }

    val angle by rememberCameraElevation()
    var locked by remember { mutableStateOf<Float?>(null) }
    var distance by remember { mutableStateOf("") }
    var eye by remember { mutableStateOf("1.5") }
    val shown = locked ?: angle
    val d = num(distance); val e = num(eye)
    val h = if (d != null && d > 0 && e != null && locked != null) ManualShapes.heightFromAngle(d, locked!!.toDouble(), e) else null

    val previewView = remember { PreviewView(context) }
    LaunchedEffect(camOk) {
        if (!camOk) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (camOk) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Crosshair
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            drawLine(Color.Red, Offset(c.x - 60, c.y), Offset(c.x + 60, c.y), strokeWidth = 4f)
            drawLine(Color.Red, Offset(c.x, c.y - 60), Offset(c.x, c.y + 60), strokeWidth = 4f)
            drawCircle(Color.Red, radius = 10f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        }
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)
                .background(Color(0xAA000000), RoundedCornerShape(12.dp)).padding(12.dp)
        ) {
            Text(
                "Postavte se tak, abyste viděli vrchol. Zaměřte červený kříž na vrchol hromady a klepněte na „Zamknout úhel“. " +
                    "Pak zadejte vodorovnou vzdálenost od sebe ke středu hromady (pod vrcholem) a výšku očí.",
                color = Color.White, fontSize = 13.sp,
            )
            Text("Úhel: %.1f°".format(shown) + if (locked != null) " (zamčeno)" else "",
                color = if (locked != null) Color(0xFFB9F6CA) else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { locked = angle }, modifier = Modifier.weight(1f)) { Text(if (locked == null) "Zamknout úhel" else "Zaměřit znovu") }
                if (locked != null) TextButton(onClick = { locked = null }) { Text("Uvolnit") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(distance, { distance = it }, label = { Text("Vzdálenost ke středu") }, suffix = { Text("m") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(eye, { eye = it }, label = { Text("Výška očí") }, suffix = { Text("m") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            Text(if (h != null) "Výška hromady ≈ %.2f m".format(h) else "Zamkněte úhel a zadejte vzdálenost.",
                fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Zrušit") }
                Button(enabled = h != null && h > 0, onClick = { h?.let(onResult) }, modifier = Modifier.weight(1f)) { Text("Použít výšku") }
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}
