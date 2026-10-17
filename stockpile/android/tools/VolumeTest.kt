// Desktop test of the on-phone volume algorithm (no Android needed):
//   kotlinc tools/VolumeTest.kt app/src/main/java/com/stockpile/volume/measure/*.kt -include-runtime -d vt.jar && java -jar vt.jar
import com.stockpile.volume.measure.*
import kotlin.math.*
import kotlin.random.Random

fun scenario(name: String, R: Double, H: Double, slope: Pair<Double, Double>, noise: Double, holeR: Double, walkR: Double): Boolean {
    val rnd = Random(7)
    val cloud = VoxelCloud(0.05f)
    val gy = 0.0
    fun ground(x: Double, z: Double) = gy + slope.first * x + slope.second * z
    fun height(x: Double, z: Double) = ground(x, z) + max(0.0, H * (1 - hypot(x, z) / R))
    val ext = walkR + 1.5
    var x = -ext
    // Surface seen ~4 times each, from the walking circle inward to about 1.5 m outside it.
    while (x <= ext) {
        var z = -ext
        while (z <= ext) {
            val r = hypot(x, z)
            if (r < walkR + 1.5 && r > holeR) {
                repeat(4) {
                    val jx = x + rnd.nextDouble(-0.02, 0.02); val jz = z + rnd.nextDouble(-0.02, 0.02)
                    cloud.add(jx.toFloat(), (height(jx, jz) + rnd.nextDouble() * 2 * noise - noise + rnd.nextDouble(-noise, noise)).toFloat(), jz.toFloat())
                }
            }
            z += 0.04
        }
        x += 0.04
    }
    // Flying-pixel outliers floating above the pile.
    repeat(20000) {
        val ox = rnd.nextDouble(-walkR, walkR); val oz = rnd.nextDouble(-walkR, walkR)
        cloud.add(ox.toFloat(), (height(ox, oz) + rnd.nextDouble(0.3, 3.0)).toFloat(), oz.toFloat())
    }
    val path = (0 until 200).map { val a = it * 2 * PI / 200; Vec3((walkR * cos(a)).toFloat(), (ground(walkR * cos(a), walkR * sin(a)) + 1.5).toFloat(), (walkR * sin(a)).toFloat()) }
    val t0 = System.currentTimeMillis()
    val res = VolumeCalculator.compute(cloud.snapshot(), path)
    val truth = PI * R * R * H / 3
    val err = (res.volumeM3 - truth) / truth * 100
    println("%-28s truth %7.1f m3  measured %7.1f m3  err %+5.2f %%  footprint %6.1f  maxH %.2f  slope %.1f%%  rmse %.3f  cov %.1f%%  voxels %d  %d ms  warn=%s".format(
        name, truth, res.volumeM3, err, res.footprintM2, res.maxHeightM, res.baseSlopePct, res.baseRmseM, res.coveragePct, cloud.size, System.currentTimeMillis() - t0, res.warnings))
    return abs(err) < 3
}

/** Pile with other objects around it inside the walked area; user then draws a circle / polygon around the pile. */
fun clutterScenario(): Boolean {
    val rnd = Random(11)
    val cloud = VoxelCloud(0.05f)
    val R = 6.0; val H = 3.0; val walkR = 16.0
    fun ground(x: Double, z: Double) = 0.02 * x - 0.01 * z
    fun obj(x: Double, z: Double): Double {
        var h = max(0.0, H * (1 - hypot(x, z) / R))
        // two boxes 1.5 m high and a car-like block nearby
        if (x in 9.0..11.0 && z in -1.0..1.0) h = max(h, 1.5)
        if (x in -12.0..-9.0 && z in 4.0..6.0) h = max(h, 1.5)
        if (x in -2.0..2.0 && z in 9.0..13.0) h = max(h, 1.2)
        return ground(x, z) + h
    }
    var x = -walkR - 1
    while (x <= walkR + 1) {
        var z = -walkR - 1
        while (z <= walkR + 1) {
            if (hypot(x, z) < walkR + 1) repeat(3) { cloud.add(x.toFloat(), (obj(x, z) + rnd.nextDouble(-0.02, 0.02)).toFloat(), z.toFloat()) }
            z += 0.05
        }
        x += 0.05
    }
    val path = (0 until 240).map { val a = it * 2 * PI / 240; Vec3((walkR * cos(a)).toFloat(), 1.5f, (walkR * sin(a)).toFloat()) }
    val truth = PI * R * R * H / 3
    val surface = VolumeCalculator.buildSurface(cloud.snapshot(), path)
    val walked = VolumeCalculator.volumeIn(surface, surface.pathHull, VolumeCalculator.BoundaryKind.WALKED_PATH)
    val circle = VolumeCalculator.volumeIn(surface, VolumeCalculator.circle(0.0, 0.0, 7.0), VolumeCalculator.BoundaryKind.USER)
    val poly = listOf(doubleArrayOf(-7.0, -7.0), doubleArrayOf(7.0, -7.0), doubleArrayOf(7.5, 0.0), doubleArrayOf(7.0, 7.0), doubleArrayOf(-7.0, 7.0))
    val polygon = VolumeCalculator.volumeIn(surface, poly, VolumeCalculator.BoundaryKind.USER)
    fun e(v: Double) = (v - truth) / truth * 100
    println("clutter: truth %.1f | walked hull %.1f (%+.1f%%) | circle r7 %.1f (%+.2f%%) | polygon %.1f (%+.2f%%)".format(
        truth, walked.volumeM3, e(walked.volumeM3), circle.volumeM3, e(circle.volumeM3), polygon.volumeM3, e(polygon.volumeM3)))
    return abs(e(circle.volumeM3)) < 2 && abs(e(polygon.volumeM3)) < 2 && e(walked.volumeM3) > 10
}

fun main() {
    val ok = listOf(
        scenario("cone 10m/5m, sloped, hole", 10.0, 5.0, 0.03 to -0.02, 0.03, 1.5, 12.0),
        scenario("small pile 3m/1.5m flat", 3.0, 1.5, 0.0 to 0.0, 0.02, 0.0, 4.5),
        scenario("cone 6m/3m steep ground", 6.0, 3.0, 0.08 to 0.05, 0.04, 1.0, 8.0),
    ).all { it } && clutterScenario()
    println(if (ok) "PASS" else "FAIL")
    if (!ok) kotlin.system.exitProcess(1)
}
