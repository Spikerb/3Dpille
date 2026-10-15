package com.stockpile.volume.measure

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

// Pure Kotlin (no Android imports) so it can be unit-tested on a desktop JVM.

class GeoSample(val lat: Double, val lon: Double, val accuracyM: Double)

class GeoEstimate(val lat: Double, val lon: Double, val accuracyM: Double, val used: Int, val total: Int)

/**
 * Averages GPS fixes taken while standing still.
 *
 * Phone GPS errors are strongly correlated over minutes (multipath, atmosphere), so averaging does
 * NOT shrink the error like 1/√n. The reported accuracy is therefore deliberately conservative:
 * never better than 70 % of the best single fix and never better than the scatter of the fixes.
 */
object GeoAverage {
    private const val M_PER_DEG = 111_320.0

    fun estimate(samples: List<GeoSample>): GeoEstimate? {
        val valid = samples.filter { it.accuracyM > 0 && it.accuracyM < 100 }
        if (valid.isEmpty()) return null
        val best = valid.minOf { it.accuracyM }
        // Drop fixes much worse than the best one.
        val use = valid.filter { it.accuracyM <= max(best * 2.0, best + 3.0) }
        var wSum = 0.0; var latSum = 0.0; var lonSum = 0.0
        for (s in use) {
            val w = 1.0 / (s.accuracyM * s.accuracyM)
            wSum += w; latSum += s.lat * w; lonSum += s.lon * w
        }
        val lat = latSum / wSum; val lon = lonSum / wSum
        val mPerDegLon = M_PER_DEG * cos(Math.toRadians(lat))
        var spread2 = 0.0
        for (s in use) {
            val dy = (s.lat - lat) * M_PER_DEG; val dx = (s.lon - lon) * mPerDegLon
            spread2 += (dx * dx + dy * dy) / (s.accuracyM * s.accuracyM)
        }
        val spread = if (use.size > 1) sqrt(spread2 / wSum) else best
        val statistical = sqrt(1.0 / wSum)
        val acc = maxOf(statistical, best * 0.7, spread)
        return GeoEstimate(lat, lon, acc, use.size, samples.size)
    }

    /** 5 decimal places ≈ 1.1 m (latitude) – the resolution stored in the record and on the ledger. */
    fun round5(v: Double) = Math.round(v * 100_000.0) / 100_000.0
}
