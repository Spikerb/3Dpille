// kotlinc tools/ManualTest.kt app/src/main/java/com/stockpile/volume/measure/*.kt (pure files) -include-runtime -d mt.jar
import com.stockpile.volume.measure.*
import kotlin.math.*

fun check(name: String, i: ManualInput, expect: Double): Boolean {
    val v = ManualShapes.volume(i); val o = ManualShapes.outcome(i); val g = ManualShapes.gridVolume(o)
    val e1 = (v - expect) / expect * 100; val e2 = (g - v) / v * 100
    println("%-30s formula %8.2f  expected %8.2f (%+.2f%%)  grid %8.2f (%+.2f%%)  grid %dx%d".format(name, v, expect, e1, g, e2, o.grid.cols, o.grid.rows))
    return abs(e1) < 0.01 && abs(e2) < 2
}

fun main() {
    val ok = listOf(
        check("cone D=10 H=4", ManualInput(PileShape.CONE, 4.0, diameter = 10.0), PI * 25 * 4 / 3),
        check("cone by perimeter", ManualInput(PileShape.CONE, 4.0, perimeter = 10 * PI), PI * 25 * 4 / 3),
        check("windrow 20x6 H3", ManualInput(PileShape.WINDROW, 3.0, length = 20.0, width = 6.0), 6 * 3 / 2.0 * 14 + PI * 9 * 3 / 3),
        check("frustum 12x8 top 4x2 H3", ManualInput(PileShape.FRUSTUM, 3.0, length = 12.0, width = 8.0, topLength = 4.0, topWidth = 2.0),
            3.0 / 6 * (96 + 4 * 8 * 5 + 8)),
        check("frustum = box when top=base", ManualInput(PileShape.FRUSTUM, 2.0, length = 5.0, width = 4.0, topLength = 5.0, topWidth = 4.0), 40.0),
    ).all { it }
    println("height from angle 10 m, 20°, eye 1.5 m = %.2f m".format(ManualShapes.heightFromAngle(10.0, 20.0, 1.5)))
    println(ManualShapes.validate(ManualInput(PileShape.WINDROW, 3.0, length = 5.0, width = 8.0)))
    println(if (ok) "PASS" else "FAIL")
}
