package com.stockpile.volume.chain

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Pure Kotlin (no Android imports) so the rule can be unit-tested on a desktop JVM.

/** One measurement already written to the registry address. */
data class RegistryEntry(
    val txid: String,
    val recordId: String?,
    val name: String?,
    val lat: Double,
    val lon: Double,
    /** When the pile was measured (falls back to the ledger close time). */
    val measuredAt: Instant,
)

data class Conflict(val entry: RegistryEntry, val distanceM: Double, val allowedFrom: Instant)

/**
 * Rule: a location may be written only once per [WINDOW_DAYS] days within [RADIUS_M] metres –
 * across all users, because every record is sent to the same registry address on the ledger.
 */
object Registry {
    const val RADIUS_M = 30.0
    const val WINDOW_DAYS = 100L

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** The closest blocking entry, or null when writing is allowed. */
    fun findConflict(entries: List<RegistryEntry>, lat: Double, lon: Double, now: Instant): Conflict? {
        val since = now.minus(WINDOW_DAYS, ChronoUnit.DAYS)
        return entries
            .filter { it.measuredAt.isAfter(since) }
            .map { it to distanceM(lat, lon, it.lat, it.lon) }
            .filter { it.second <= RADIUS_M }
            .minByOrNull { it.second }
            ?.let { (e, d) -> Conflict(e, d, e.measuredAt.plus(WINDOW_DAYS, ChronoUnit.DAYS)) }
    }

    /** Basic check of a classic XRPL address (r…, base58, 25–35 chars). */
    fun isAddress(s: String): Boolean =
        Regex("^r[1-9A-HJ-NP-Za-km-z]{24,34}$").matches(s.trim())
}
