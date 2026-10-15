import com.stockpile.volume.measure.*
import kotlin.random.Random
fun main() {
    val r = Random(3)
    // 60 fixes around a true point, phone-like: reported accuracy 3-5 m, real scatter ~2 m plus a shared 1.5 m bias.
    val tLat = 49.195000; val tLon = 16.663900
    val s = (0 until 60).map { GeoSample(tLat + (1.5 + r.nextDouble(-2.0, 2.0)) / 111320, tLon + r.nextDouble(-2.0, 2.0) / 72800, r.nextDouble(3.0, 5.0)) }
    val e = GeoAverage.estimate(s)!!
    val errM = Math.hypot((e.lat - tLat) * 111320, (e.lon - tLon) * 72800)
    println("phone-like: reported ±%.2f m, true error %.2f m, used %d".format(e.accuracyM, errM, e.used))
    // RTK-like: accuracy 0.02 m
    val s2 = (0 until 30).map { GeoSample(tLat + r.nextDouble(-0.02, 0.02) / 111320, tLon, 0.02) }
    val e2 = GeoAverage.estimate(s2)!!
    println("rtk-like: reported ±%.3f m".format(e2.accuracyM))
    println("round5: ${GeoAverage.round5(49.1949963)} ${GeoAverage.round5(16.6638951)}")
    check(e.accuracyM >= errM) { "reported accuracy must not be optimistic" }
    check(e2.accuracyM < 0.1)
    println("PASS")
}
