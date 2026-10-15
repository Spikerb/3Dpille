package com.stockpile.volume.measure

import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.os.Build
import com.stockpile.volume.BuildConfig
import com.stockpile.volume.chain.Hex
import com.stockpile.volume.data.GeoPoint
import com.stockpile.volume.data.HeightmapRef
import com.stockpile.volume.data.MeasurementRecord
import com.stockpile.volume.data.ProjectStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt

/** Turns a finished scan into the files that make up a measurement. */
object MeasurementSaver {
    const val RECORD_FILE = "record.json"
    const val HEIGHTMAP_FILE = "heightmap.f32"
    const val PREVIEW_FILE = "heightmap.png"

    /** Stable encoder: the bytes it writes are what gets hashed. */
    val recordJson = Json { encodeDefaults = true; explicitNulls = true; prettyPrint = false }

    fun save(
        store: ProjectStore,
        projectId: String,
        outcome: VolumeOutcome,
        method: String,
        voxels: Int,
        location: Location?,
        inputs: Map<String, Double>? = null,
    ): MeasurementRecord {
        val project = store.get(projectId) ?: error("Projekt neexistuje")
        val dir = File(store.dir(projectId), "measurement").apply { deleteRecursively(); mkdirs() }

        // Height grid: little-endian float32, row-major (rows × cols), metres above base, NaN outside.
        val g = outcome.grid
        val bb = ByteBuffer.allocate(g.heights.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        g.heights.forEach { bb.putFloat(it) }
        val gridFile = File(dir, HEIGHTMAP_FILE).apply { writeBytes(bb.array()) }
        writePreview(g, File(dir, PREVIEW_FILE))

        val density = project.densityTM3
        val record = MeasurementRecord(
            id = UUID.randomUUID().toString(),
            project = project.name,
            measuredAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            method = method,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            app = "StockpileVolume ${BuildConfig.VERSION_NAME}",
            location = location?.let { GeoPoint(r6(it.latitude), r6(it.longitude), r1(it.accuracy.toDouble())) },
            volumeM3 = r3(outcome.volumeM3),
            fillM3 = r3(outcome.fillM3),
            cutM3 = r3(outcome.cutM3),
            footprintM2 = r2(outcome.footprintM2),
            boundaryAreaM2 = r2(outcome.boundaryAreaM2),
            maxHeightM = r3(outcome.maxHeightM),
            baseSlopePct = r2(outcome.baseSlopePct),
            baseRmseM = r3(outcome.baseRmseM),
            coveragePct = r1(outcome.coveragePct),
            cellSizeM = r3(outcome.cellSizeM),
            pathLengthM = r1(outcome.pathLengthM),
            densityTM3 = density,
            tonnes = density?.let { r2(outcome.volumeM3 * it) },
            voxels = voxels,
            heightmap = HeightmapRef(g.cols, g.rows, r3(g.cellSize.toDouble()), HEIGHTMAP_FILE, Hex.sha256(gridFile.readBytes())),
            warnings = outcome.warnings,
            inputs = inputs,
        )
        val bytes = recordJson.encodeToString(record).toByteArray(Charsets.UTF_8)
        File(dir, RECORD_FILE).writeBytes(bytes)
        val hash = Hex.sha256(bytes)
        store.update(projectId) { it.copy(measurement = record, recordSha256 = hash, anchor = null) }
        return record
    }

    fun measurementDir(store: ProjectStore, projectId: String) = File(store.dir(projectId), "measurement")

    /** Recomputes the hash from the file on disk (detects tampering with the stored record). */
    fun fileHash(store: ProjectStore, projectId: String): String? =
        File(measurementDir(store, projectId), RECORD_FILE).takeIf { it.exists() }?.let { Hex.sha256(it.readBytes()) }

    private fun writePreview(g: HeightGrid, out: File) {
        var maxH = 0.5f
        g.heights.forEach { if (!it.isNaN() && it > maxH) maxH = it }
        // Rows grow with +Z; flip so the image is a top-down view.
        val bmp = Bitmap.createBitmap(g.cols, g.rows, Bitmap.Config.ARGB_8888)
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            val h = g.heights[r * g.cols + c]
            bmp.setPixel(c, g.rows - 1 - r, if (h.isNaN()) Color.TRANSPARENT else ramp((h / maxH).coerceIn(0f, 1f)))
        }
        val scale = maxOf(1, (800f / maxOf(g.cols, g.rows)).roundToInt())
        val scaled = if (scale > 1) Bitmap.createScaledBitmap(bmp, g.cols * scale, g.rows * scale, false) else bmp
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** blue (0) → green → yellow → red (max) */
    private fun ramp(t: Float): Int {
        val stops = arrayOf(
            floatArrayOf(0.20f, 0.35f, 0.75f), floatArrayOf(0.20f, 0.70f, 0.45f),
            floatArrayOf(0.98f, 0.85f, 0.25f), floatArrayOf(0.85f, 0.25f, 0.15f),
        )
        val x = t * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        val f = x - i
        val a = stops[i]; val b = stops[i + 1]
        return Color.rgb(
            ((a[0] + (b[0] - a[0]) * f) * 255).toInt(),
            ((a[1] + (b[1] - a[1]) * f) * 255).toInt(),
            ((a[2] + (b[2] - a[2]) * f) * 255).toInt(),
        )
    }

    private fun r1(v: Double) = Math.round(v * 10.0) / 10.0
    private fun r2(v: Double) = Math.round(v * 100.0) / 100.0
    private fun r3(v: Double) = Math.round(v * 1000.0) / 1000.0
    private fun r6(v: Double) = Math.round(v * 1_000_000.0) / 1_000_000.0
}
