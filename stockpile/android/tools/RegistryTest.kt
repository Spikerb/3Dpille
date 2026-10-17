import com.stockpile.volume.chain.*
import java.time.Instant
import java.time.temporal.ChronoUnit
fun main() {
    val now = Instant.parse("2026-10-16T07:00:00Z")
    fun e(id: String, lat: Double, lon: Double, daysAgo: Long) = RegistryEntry("tx$id", id, "n$id", lat, lon, now.minus(daysAgo, ChronoUnit.DAYS))
    val base = 49.27828 to 16.60760
    // 20 m north ≈ 0.00018 deg lat; 40 m ≈ 0.00036
    val entries = listOf(
        e("near-old", base.first + 0.00018, base.second, 120),   // near but older than 100 days
        e("far-new", base.first + 0.00036, base.second, 5),     // new but 40 m away
        e("near-new", base.first + 0.00018, base.second, 30),    // 20 m, 30 days -> conflict
    )
    val c = Registry.findConflict(entries, base.first, base.second, now)
    println("conflict=${c?.entry?.recordId} dist=%.1f m allowedFrom=${c?.allowedFrom}".format(c?.distanceM))
    check(c?.entry?.recordId == "near-new")
    check(Registry.findConflict(entries.take(2), base.first, base.second, now) == null)
    val d = Registry.distanceM(49.0, 16.0, 49.0, 16.000412)  // ~30 m east at 49° N
    println("30 m east check: %.2f m".format(d))
    check(d in 29.5..30.5)
    check(Registry.isAddress("rPT1Sjq2YGrBMTttX4GZHjKu9dyfzbpAYe")); check(!Registry.isAddress("xyz"))
    println("PASS")
}
