@file:OptIn(ExperimentalMaterial3Api::class)

package com.stockpile.volume.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stockpile.volume.data.ProjectStore
import com.stockpile.volume.measure.MeasurementSaver
import com.stockpile.volume.measure.SurfaceGrid
import com.stockpile.volume.measure.VolumeCalculator
import com.stockpile.volume.measure.VolumeOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/** World point on the ground plane (metres, ARCore x/z). */
private data class P(val x: Double, val z: Double)

/**
 * Top-down view of a saved scan where the user outlines the pile with a circle or a polygon of
 * straight lines. Only the volume inside is counted; the ground is sampled just outside the line.
 */
@Composable
fun BoundaryEditorScreen(projectId: String, onDone: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProjectStore.get(context) }
    val scope = rememberCoroutineScope()

    var surface by remember { mutableStateOf<SurfaceGrid?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.IO) { MeasurementSaver.loadSurface(store, projectId) }
        if (s == null) loadError = "Data skenu nejsou k dispozici – hranici lze upravit jen u skenů pořízených touto verzí aplikace."
        else { surface = s; image = withContext(Dispatchers.Default) { renderSurface(s) } }
    }

    var circleMode by remember { mutableStateOf(true) }
    var center by remember { mutableStateOf<P?>(null) }
    var radius by remember { mutableStateOf<Double?>(null) }
    val vertices = remember { mutableStateListOf<P>() }
    var preview by remember { mutableStateOf<VolumeOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun boundary(): List<DoubleArray>? = if (circleMode) {
        val c = center; val r = radius
        if (c != null && r != null && r > 0.3) VolumeCalculator.circle(c.x, c.z, r) else null
    } else if (vertices.size >= 3) vertices.map { doubleArrayOf(it.x, it.z) } else null

    fun description(): String = if (circleMode) "circle r=%.2f m".format(java.util.Locale.US, radius ?: 0.0)
    else "polygon ${vertices.size} pts"

    fun reset() { center = null; radius = null; vertices.clear(); preview = null; error = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hranice hromady") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val err = loadError
            if (err != null) {
                Text(err, color = MaterialTheme.colorScheme.error)
                return@Column
            }
            val s = surface
            val img = image
            if (s == null || img == null) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = circleMode, onClick = { circleMode = true; reset() }, label = { Text("Kružnice") })
                FilterChip(selected = !circleMode, onClick = { circleMode = false; reset() }, label = { Text("Mnohoúhelník") })
            }
            Text(
                if (circleMode) "1. klepnutí = střed hromady, 2. klepnutí = okraj (těsně za patou). Dalším klepnutím okraj posunete."
                else "Klepejte postupně body kolem paty hromady – spojí se rovnými čarami a obrazec se sám uzavře.",
                style = MaterialTheme.typography.bodySmall,
            )

            // ---- Top-down map with the drawing on top
            Box(Modifier.fillMaxWidth().aspectRatio(s.cols.toFloat() / s.rows)) {
                Image(img, "Pohled shora", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(
                    Modifier.fillMaxSize().pointerInput(circleMode) {
                        detectTapGestures { o ->
                            val k = size.width.toDouble() / s.cols
                            val p = P(s.originX + o.x / k * s.cell, s.originZ + (s.rows - o.y / k) * s.cell)
                            preview = null; error = null
                            if (circleMode) {
                                val c = center
                                if (c == null) center = p else radius = hypot(p.x - c.x, p.z - c.z)
                            } else vertices.add(p)
                        }
                    }
                ) {
                    val k = size.width / s.cols
                    fun toScreen(x: Double, z: Double) = Offset(((x - s.originX) / s.cell * k).toFloat(), ((s.rows - (z - s.originZ) / s.cell) * k).toFloat())
                    // Walked path outline for orientation
                    if (s.pathHull.size >= 3) {
                        val hp = Path()
                        s.pathHull.forEachIndexed { i, q -> val o = toScreen(q[0], q[1]); if (i == 0) hp.moveTo(o.x, o.y) else hp.lineTo(o.x, o.y) }
                        hp.close()
                        drawPath(hp, Color(0x99FFFFFF), style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                    }
                    val line = Color(0xFFFF1744)
                    if (circleMode) {
                        center?.let { c ->
                            val o = toScreen(c.x, c.z)
                            drawCircle(line, 8f, o)
                            radius?.let { r -> drawCircle(line, (r / s.cell * k).toFloat(), o, style = Stroke(5f)) }
                        }
                    } else if (vertices.isNotEmpty()) {
                        val pts = vertices.map { toScreen(it.x, it.z) }
                        for (i in 1 until pts.size) drawLine(line, pts[i - 1], pts[i], 5f)
                        if (pts.size >= 3) drawLine(line, pts.last(), pts.first(), 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                        pts.forEach { drawCircle(Color.White, 9f, it); drawCircle(line, 6f, it) }
                    }
                }
            }
            Text("Bílá čárkovaná čára = vaše trasa při skenování. Barvy = výška (modrá nízko → červená vysoko, šedá = nenaskenováno).",
                style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!circleMode) OutlinedButton(enabled = vertices.isNotEmpty(), onClick = { vertices.removeAt(vertices.lastIndex); preview = null }) { Text("Zpět o bod") }
                OutlinedButton(onClick = { reset() }) { Text("Smazat") }
            }
            if (circleMode) radius?.let { Text("Poloměr %.2f m · plocha %.1f m²".format(it, Math.PI * it * it)) }
            else if (vertices.size >= 3) Text("Plocha %.1f m²".format(VolumeCalculator.polygonArea(vertices.map { doubleArrayOf(it.x, it.z) })))

            Button(
                enabled = boundary() != null && !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val b = boundary()
                    if (b != null) {
                        busy = true; error = null
                        scope.launch {
                            val r = withContext(Dispatchers.Default) { runCatching { VolumeCalculator.volumeIn(s, b, VolumeCalculator.BoundaryKind.USER) } }
                            busy = false
                            r.onSuccess { preview = it }.onFailure { error = it.message }
                        }
                    }
                },
            ) { Text(if (busy) "Počítám…" else "Spočítat objem v hranici") }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            preview?.let { o ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${fmt(o.volumeM3, 1)} m³", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Půdorys ${fmt(o.footprintM2, 1)} m² · max. výška ${fmt(o.maxHeightM, 2)} m · naskenováno ${fmt(o.coveragePct, 0)} %",
                            style = MaterialTheme.typography.bodySmall)
                        o.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val b = boundary() ?: emptyList()
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.Default) {
                                runCatching { MeasurementSaver.recomputeWithBoundary(store, projectId, b, description()) }
                            }
                            busy = false
                            r.onSuccess { onDone() }.onFailure { error = it.message }
                        }
                    },
                ) { Text("Uložit jako výsledek měření") }
            }
        }
    }
}

/** Colour map of the absolute surface: low → blue, high → red, unseen → light grey. Row 0 at the bottom. */
private fun renderSurface(s: SurfaceGrid): ImageBitmap {
    val valid = s.surface.filter { !it.isNaN() }.sorted()
    val lo = if (valid.isEmpty()) 0f else valid[(valid.size * 0.05).toInt()]
    val hi = if (valid.isEmpty()) 1f else valid[((valid.size - 1) * 0.995).toInt()]
    val span = (hi - lo).coerceAtLeast(0.3f)
    val bmp = Bitmap.createBitmap(s.cols, s.rows, Bitmap.Config.ARGB_8888)
    val stops = arrayOf(
        floatArrayOf(0.20f, 0.35f, 0.75f), floatArrayOf(0.20f, 0.70f, 0.45f),
        floatArrayOf(0.98f, 0.85f, 0.25f), floatArrayOf(0.85f, 0.25f, 0.15f),
    )
    val px = IntArray(s.cols * s.rows)
    for (r in 0 until s.rows) for (c in 0 until s.cols) {
        val h = s.surface[r * s.cols + c]
        px[(s.rows - 1 - r) * s.cols + c] = if (h.isNaN()) 0xFFDDDDDD.toInt() else {
            val t = ((h - lo) / span).coerceIn(0f, 1f) * (stops.size - 1)
            val i = t.toInt().coerceAtMost(stops.size - 2); val f = t - i
            val a = stops[i]; val b = stops[i + 1]
            android.graphics.Color.rgb(((a[0] + (b[0] - a[0]) * f) * 255).toInt(), ((a[1] + (b[1] - a[1]) * f) * 255).toInt(), ((a[2] + (b[2] - a[2]) * f) * 255).toInt())
        }
    }
    bmp.setPixels(px, 0, s.cols, 0, 0, s.cols, s.rows)
    return bmp.asImageBitmap()
}
