package com.stockpile.volume.measure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Pure Kotlin (no Android imports) so it can be unit-tested on a desktop JVM.

/**
 * Manual measurement for phones without ARCore: the user picks the shape that best matches the
 * pile and enters a few dimensions. Volume uses the exact formula of that shape; a height grid of
 * the idealised shape is generated too, so the record looks the same as a scan (preview + hash).
 */
enum class PileShape(val label: String, val method: String, val hint: String) {
    CONE(
        "Kužel (kupa)", "manual-cone",
        "Kulatá hromada s jedním vrcholem. Obvod paty změřte pásmem nebo obejitím po krocích.",
    ),
    WINDROW(
        "Val (podlouhlá hromada)", "manual-windrow",
        "Podlouhlá hromada s hřbetem a zaoblenými konci. Délka a šířka se měří na patě.",
    ),
    FRUSTUM(
        "Komolý jehlan (plochý vršek)", "manual-frustum",
        "Hromada s rovným vrškem, např. po shrnutí nakladačem. Rozměry paty a horní plochy.",
    ),
}

class ManualInput(
    val shape: PileShape,
    val height: Double,
    /** CONE: perimeter of the base (m). Ignored when [diameter] is set. */
    val perimeter: Double? = null,
    /** CONE: base diameter (m). */
    val diameter: Double? = null,
    /** WINDROW / FRUSTUM: base length and width (m). */
    val length: Double? = null,
    val width: Double? = null,
    /** FRUSTUM: top length and width (m). */
    val topLength: Double? = null,
    val topWidth: Double? = null,
) {
    fun asMap(): Map<String, Double> = buildMap {
        put("height_m", height)
        perimeter?.let { put("perimeter_m", it) }
        diameter?.let { put("diameter_m", it) }
        length?.let { put("length_m", it) }
        width?.let { put("width_m", it) }
        topLength?.let { put("top_length_m", it) }
        topWidth?.let { put("top_width_m", it) }
    }
}

object ManualShapes {

    /** Height from the clinometer: eye height + horizontal distance × tan(elevation). */
    fun heightFromAngle(distanceM: Double, angleDeg: Double, eyeHeightM: Double): Double =
        eyeHeightM + distanceM * kotlin.math.tan(Math.toRadians(angleDeg))

    fun validate(i: ManualInput): String? {
        fun pos(v: Double?) = v != null && v > 0 && v < 1000
        if (!pos(i.height)) return "Zadejte výšku hromady."
        return when (i.shape) {
            PileShape.CONE -> if (!pos(i.diameter) && !pos(i.perimeter)) "Zadejte obvod nebo průměr paty." else null
            PileShape.WINDROW -> when {
                !pos(i.length) || !pos(i.width) -> "Zadejte délku a šířku paty."
                i.width!! > i.length!! -> "Šířka nemůže být větší než délka (prohoďte je)."
                else -> null
            }
            PileShape.FRUSTUM -> when {
                !pos(i.length) || !pos(i.width) -> "Zadejte délku a šířku paty."
                i.topLength == null || i.topWidth == null || i.topLength < 0 || i.topWidth < 0 -> "Zadejte rozměry horní plochy (0 = špičatý vrchol)."
                i.topLength > i.length!! || i.topWidth > i.width!! -> "Horní plocha nemůže být větší než pata."
                else -> null
            }
        }
    }

    fun volume(i: ManualInput): Double = when (i.shape) {
        PileShape.CONE -> {
            val d = diameter(i)
            PI * d * d * i.height / 12.0
        }
        // Triangular prism between the two ends + a half cone at each end (= one full cone).
        PileShape.WINDROW -> {
            val l = i.length!!; val w = i.width!!; val h = i.height
            w * h / 2.0 * (l - w) + PI * w * w * h / 12.0
        }
        // Prismoid formula (exact for a frustum with planar faces).
        PileShape.FRUSTUM -> {
            val a1 = i.length!! * i.width!!
            val a2 = i.topLength!! * i.topWidth!!
            val am = ((i.length + i.topLength) / 2.0) * ((i.width + i.topWidth) / 2.0)
            i.height / 6.0 * (a1 + 4 * am + a2)
        }
    }

    fun footprint(i: ManualInput): Double = when (i.shape) {
        PileShape.CONE -> diameter(i).let { PI * it * it / 4.0 }
        PileShape.WINDROW -> i.width!! * (i.length!! - i.width) + PI * i.width * i.width / 4.0
        PileShape.FRUSTUM -> i.length!! * i.width!!
    }

    private fun diameter(i: ManualInput) = i.diameter?.takeIf { it > 0 } ?: (i.perimeter!! / PI)

    /** Height of the idealised shape at (x, z), centred on the origin; 0 outside the footprint. */
    fun heightAt(i: ManualInput, x: Double, z: Double): Double = when (i.shape) {
        PileShape.CONE -> {
            val r = diameter(i) / 2
            max(0.0, i.height * (1 - hypot(x, z) / r))
        }
        PileShape.WINDROW -> {
            val r = i.width!! / 2
            val half = (i.length!! - i.width) / 2
            val dx = max(0.0, abs(x) - half)            // distance beyond the straight part
            max(0.0, i.height * (1 - hypot(dx, z) / r))
        }
        PileShape.FRUSTUM -> {
            // Linear slope from the base edge to the top edge, separately in x and z; take the lower.
            fun axis(p: Double, base: Double, top: Double): Double {
                val b = base / 2; val t = top / 2
                val a = abs(p)
                return when {
                    a >= b -> 0.0
                    a <= t -> 1.0
                    else -> (b - a) / (b - t)
                }
            }
            i.height * min(axis(x, i.length!!, i.topLength!!), axis(z, i.width!!, i.topWidth!!))
        }
    }

    /** Builds a [VolumeOutcome] (analytic volume + generated height grid) for saving like a scan. */
    fun outcome(i: ManualInput): VolumeOutcome {
        val (sx, sz) = when (i.shape) {
            PileShape.CONE -> diameter(i).let { it to it }
            else -> i.length!! to i.width!!
        }
        var cell = 0.05
        while ((sx / cell) * (sz / cell) > 1_000_000) cell *= 1.5
        val cols = max(1, ceil(sx / cell).toInt()); val rows = max(1, ceil(sz / cell).toInt())
        val ox = -cols * cell / 2; val oz = -rows * cell / 2
        val heights = FloatArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = ox + (c + 0.5) * cell; val z = oz + (r + 0.5) * cell
            val inside = when (i.shape) {
                PileShape.CONE -> hypot(x, z) <= sx / 2
                PileShape.WINDROW -> hypot(max(0.0, abs(x) - (sx - sz) / 2), z) <= sz / 2
                PileShape.FRUSTUM -> true
            }
            heights[r * cols + c] = if (inside) heightAt(i, x, z).toFloat() else Float.NaN
        }
        val fp = footprint(i)
        return VolumeOutcome(
            volumeM3 = volume(i), fillM3 = volume(i), cutM3 = 0.0,
            footprintM2 = fp, boundaryAreaM2 = fp, maxHeightM = i.height,
            baseSlopePct = 0.0, baseRmseM = 0.0, coveragePct = 0.0,
            cellSizeM = cell, pathLengthM = 0.0,
            warnings = listOf("Ruční měření: objem je spočítán ze zadaných rozměrů a zvoleného tvaru, ne ze skenu. Přesnost je typicky ±10–20 %."),
            grid = HeightGrid(cols, rows, cell.toFloat(), ox.toFloat(), oz.toFloat(), heights),
        )
    }

    /** Volume integrated from the generated grid – used only by the tests to cross-check formulas. */
    fun gridVolume(o: VolumeOutcome): Double {
        var s = 0.0
        val a = o.grid.cellSize.toDouble() * o.grid.cellSize
        o.grid.heights.forEach { if (!it.isNaN()) s += it * a }
        return s
    }
}
