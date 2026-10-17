package com.stockpile.volume.measure

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Pure Kotlin (no Android imports) so it can be unit-tested on a desktop JVM.

class MeasureException(message: String) : Exception(message)

/** Height of the pile above the base plane on a regular grid (NaN = outside the boundary). */
class HeightGrid(
    val cols: Int, val rows: Int, val cellSize: Float,
    val originX: Float, val originZ: Float,
    val heights: FloatArray,
)

/** Top-down surface of the scan: absolute heights (m, NaN = not seen) on a regular grid. */
class SurfaceGrid(
    val cols: Int, val rows: Int, val cell: Double,
    val originX: Double, val originZ: Double,
    val surface: FloatArray,
    /** Convex hull of the walked path (x, z). */
    val pathHull: List<DoubleArray>,
    val pathLengthM: Double,
)

class VolumeOutcome(
    val volumeM3: Double,
    val fillM3: Double,
    val cutM3: Double,
    val footprintM2: Double,
    val boundaryAreaM2: Double,
    val maxHeightM: Double,
    val baseSlopePct: Double,
    val baseRmseM: Double,
    val coveragePct: Double,
    val cellSizeM: Double,
    val pathLengthM: Double,
    val warnings: List<String>,
    val grid: HeightGrid,
)

/**
 * Same method as the server version (server/stockpile_api/volume.py):
 *  1. boundary  = convex hull of the path the phone was carried along (walk around the pile);
 *  2. surface   = per grid cell, the top of the reliable voxels in that column;
 *  3. base      = plane robustly fitted to the surface near the boundary (the ground you walked on);
 *  4. volume    = integral of (surface - base) inside the boundary; unseen cells are filled
 *                 from their neighbours, layer by layer, and reported as coverage.
 */
object VolumeCalculator {

    fun compute(
        cloud: VoxelSnapshot,
        path: List<Vec3>,
        targetCell: Float = 0.05f,
        baseBandM: Float = 1.5f,
        minHits: Int = 2,
    ): VolumeOutcome {
        val surface = buildSurface(cloud, path, targetCell, minHits)
        return volumeIn(surface, surface.pathHull, BoundaryKind.WALKED_PATH, baseBandM.toDouble())
    }

    /** How the boundary was obtained – decides where the ground (base plane) is sampled. */
    enum class BoundaryKind {
        /** Convex hull of the walked path: ground is the band just inside it (between you and the pile). */
        WALKED_PATH,
        /** Drawn by the user tightly around the pile toe: ground is the band just outside it. */
        USER,
    }

    /**
     * Step 1: turns the voxel cloud into a top-down surface grid (absolute heights, NaN = not seen).
     * The grid covers the walked area plus a 1 m margin and can be reused with any boundary.
     */
    fun buildSurface(cloud: VoxelSnapshot, path: List<Vec3>, targetCell: Float = 0.05f, minHits: Int = 2): SurfaceGrid {
        if (path.size < 3) throw MeasureException("Obejděte hromadu dokola – trasa je příliš krátká.")
        val hull = convexHull(path.map { doubleArrayOf(it.x.toDouble(), it.z.toDouble()) })
        val hullArea = polygonArea(hull)
        if (hull.size < 3 || hullArea < 2.0) throw MeasureException("Trasa neobepíná žádnou plochu – obejděte celou hromadu.")

        val margin = 1.0
        val minX = hull.minOf { it[0] } - margin; val maxX = hull.maxOf { it[0] } + margin
        val minZ = hull.minOf { it[1] } - margin; val maxZ = hull.maxOf { it[1] } + margin
        var cell = targetCell.toDouble()
        while (((maxX - minX) / cell) * ((maxZ - minZ) / cell) > 2_500_000) cell *= 1.5
        val cols = ceil((maxX - minX) / cell).toInt()
        val rows = ceil((maxZ - minZ) / cell).toInt()
        val n = cols * rows

        // Reliable voxels: seen several times and not isolated (kills "flying pixels").
        val keySet = HashSet<Long>(cloud.size * 2)
        for (i in 0 until cloud.size) if (cloud.count[i] >= minHits) keySet.add(cloud.keys[i])
        fun neighbours(key: Long): Int {
            val mask = (1L shl 21) - 1
            val ix = (key and mask).toInt(); val iy = ((key shr 21) and mask).toInt(); val iz = ((key shr 42) and mask).toInt()
            var c = 0
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val k = ((ix + dx).toLong() and mask) or (((iy + dy).toLong() and mask) shl 21) or (((iz + dz).toLong() and mask) shl 42)
                if (k in keySet) c++
            }
            return c
        }

        // Surface per grid column = weighted mean of reliable voxels within 15 cm of the column top.
        val top = FloatArray(n) { Float.NaN }
        val good = BooleanArray(cloud.size)
        for (i in 0 until cloud.size) {
            if (cloud.count[i] < minHits) continue
            val c = colIndex(cloud.x[i], cloud.z[i], minX, minZ, cell, cols, rows)
            if (c < 0) continue
            if (neighbours(cloud.keys[i]) < 2) continue
            good[i] = true
            if (top[c].isNaN() || cloud.y[i] > top[c]) top[c] = cloud.y[i]
        }
        val wSum = DoubleArray(n); val ySum = DoubleArray(n)
        for (i in 0 until cloud.size) {
            if (!good[i]) continue
            val c = colIndex(cloud.x[i], cloud.z[i], minX, minZ, cell, cols, rows)
            if (cloud.y[i] >= top[c] - 0.15f) {
                wSum[c] += cloud.count[i].toDouble(); ySum[c] += cloud.y[i] * cloud.count[i].toDouble()
            }
        }
        val surface = FloatArray(n) { if (wSum[it] > 0) (ySum[it] / wSum[it]).toFloat() else Float.NaN }

        var pathLen = 0.0
        for (k in 1 until path.size) pathLen += hypot((path[k].x - path[k - 1].x).toDouble(), (path[k].z - path[k - 1].z).toDouble())
        return SurfaceGrid(cols, rows, cell, minX, minZ, surface, hull, pathLen)
    }

    /** Circle boundary as a polygon (72 vertices). */
    fun circle(cx: Double, cz: Double, r: Double): List<DoubleArray> =
        (0 until 72).map { val a = it * 2 * Math.PI / 72; doubleArrayOf(cx + r * kotlin.math.cos(a), cz + r * kotlin.math.sin(a)) }

    /** Step 2: volume of the surface inside [boundary] (any simple polygon, x/z in metres). */
    fun volumeIn(s: SurfaceGrid, boundary: List<DoubleArray>, kind: BoundaryKind, baseBandM: Double = 1.5): VolumeOutcome {
        if (boundary.size < 3 || polygonArea(boundary) < 0.5) throw MeasureException("Hranice je příliš malá.")
        val cols = s.cols; val rows = s.rows; val cell = s.cell; val n = cols * rows
        val margin = 1.0
        val surface = DoubleArray(n) { s.surface[it].toDouble() }

        // ---- Inside mask and distance to the boundary.
        val inside = BooleanArray(n)
        val edgeDist = DoubleArray(n)
        var insideCount = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val px = s.originX + (c + 0.5) * cell; val pz = s.originZ + (r + 0.5) * cell
            val i = r * cols + c
            inside[i] = pointInPolygon(boundary, px, pz)
            edgeDist[i] = distToPolygon(boundary, px, pz)
            if (inside[i]) insideCount++
        }
        if (insideCount == 0) throw MeasureException("Hranice leží mimo naskenovanou plochu.")

        // ---- Base plane: ground band along the boundary.
        val bx = ArrayList<Double>(); val bz = ArrayList<Double>(); val by = ArrayList<Double>()
        fun collect(test: (Int) -> Boolean) {
            for (r in 0 until rows) for (c in 0 until cols) {
                val i = r * cols + c
                if (surface[i].isNaN() || !test(i)) continue
                bx.add(s.originX + (c + 0.5) * cell); bz.add(s.originZ + (r + 0.5) * cell); by.add(surface[i])
            }
        }
        when (kind) {
            BoundaryKind.WALKED_PATH -> collect { i -> (inside[i] && edgeDist[i] <= baseBandM) || (!inside[i] && edgeDist[i] <= margin) }
            BoundaryKind.USER -> {
                collect { i -> !inside[i] && edgeDist[i] in 0.1..baseBandM }
                if (bx.size < 200) collect { i -> !inside[i] && edgeDist[i] in baseBandM..(baseBandM * 2) }
            }
        }
        if (bx.size < 200) throw MeasureException(
            if (kind == BoundaryKind.USER) "Kolem nakreslené hranice je naskenováno příliš málo terénu. Nakreslete hranici tak, aby kolem ní byla vidět zem."
            else "Kolem hromady je naskenováno příliš málo terénu. Při obcházení mířte i na zem mezi sebou a patou hromady."
        )
        val plane = robustPlane(bx.toDoubleArray(), bz.toDoubleArray(), by.toDoubleArray())

        // ---- Fill unseen cells inside the boundary, layer by layer from the known neighbours.
        var observedInside = 0
        for (i in 0 until n) if (inside[i] && !surface[i].isNaN()) observedInside++
        val coverage = observedInside * 100.0 / insideCount
        fillHoles(surface, inside, cols, rows)

        // ---- Integrate.
        val heights = FloatArray(n) { Float.NaN }
        val area = cell * cell
        var fill = 0.0; var cut = 0.0; var footprint = 0.0; var maxH = 0.0
        val edgeHeights = ArrayList<Double>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = r * cols + c
            if (!inside[i]) continue
            val px = s.originX + (c + 0.5) * cell; val pz = s.originZ + (r + 0.5) * cell
            val v = if (surface[i].isNaN()) plane.at(px, pz) else surface[i]
            val dz = v - plane.at(px, pz)
            heights[i] = dz.toFloat()
            if (dz > 0) fill += dz * area else cut -= dz * area
            if (dz > 0.10) footprint += area
            if (dz > maxH) maxH = dz
            if (edgeDist[i] <= 0.5) edgeHeights.add(dz)
        }

        val warnings = ArrayList<String>()
        if (plane.rmse > 0.25) warnings.add("Terén kolem hromady je nerovný (±%.2f m), základní rovina může být nepřesná.".format(plane.rmse))
        if (plane.slopePct > 15) warnings.add("Sklon terénu vychází %.0f %% – zkontrolujte, zda je opravdu tak strmý.".format(plane.slopePct))
        if (coverage < 85) warnings.add("Naskenováno jen %.0f %% plochy, zbytek byl dopočten. Projděte i vyšší místa / druhé kolo.".format(coverage))
        if (cut > 0.15 * max(fill, 1e-9) && fill > 0) warnings.add("Velký objem pod úrovní terénu – terén uvnitř je níž než okolo.")
        if (edgeHeights.size > 20) {
            edgeHeights.sort()
            if (edgeHeights[(edgeHeights.size * 0.95).toInt()] > 0.5)
                warnings.add(
                    if (kind == BoundaryKind.USER) "Hromada sahá až k nakreslené hranici – zvažte hranici o kus větší."
                    else "Hromada sahá až k hranici – příště obcházejte dál od paty hromady."
                )
        }

        return VolumeOutcome(
            volumeM3 = fill - cut, fillM3 = fill, cutM3 = cut, footprintM2 = footprint,
            boundaryAreaM2 = polygonArea(boundary), maxHeightM = maxH,
            baseSlopePct = plane.slopePct, baseRmseM = plane.rmse, coveragePct = coverage,
            cellSizeM = cell, pathLengthM = s.pathLengthM, warnings = warnings,
            grid = HeightGrid(cols, rows, cell.toFloat(), s.originX.toFloat(), s.originZ.toFloat(), heights),
        )
    }

    private fun colIndex(x: Float, z: Float, minX: Double, minZ: Double, cell: Double, cols: Int, rows: Int): Int {
        val c = floor((x - minX) / cell).toInt(); val r = floor((z - minZ) / cell).toInt()
        return if (c < 0 || r < 0 || c >= cols || r >= rows) -1 else r * cols + c
    }

    private fun fillHoles(s: DoubleArray, inside: BooleanArray, cols: Int, rows: Int) {
        val todo = ArrayList<Int>()
        for (i in s.indices) if (inside[i] && s[i].isNaN()) todo.add(i)
        var guard = 0
        while (todo.isNotEmpty() && guard++ < 5000) {
            val newly = ArrayList<Pair<Int, Double>>()
            val rest = ArrayList<Int>()
            for (i in todo) {
                val r = i / cols; val c = i % cols
                var sum = 0.0; var k = 0
                for (dr in -1..1) for (dc in -1..1) {
                    val rr = r + dr; val cc = c + dc
                    if (rr < 0 || cc < 0 || rr >= rows || cc >= cols) continue
                    val v = s[rr * cols + cc]
                    if (!v.isNaN()) { sum += v; k++ }
                }
                if (k > 0) newly.add(i to sum / k) else rest.add(i)
            }
            if (newly.isEmpty()) break
            for ((i, v) in newly) s[i] = v
            todo.clear(); todo.addAll(rest)
        }
    }

    // ------------------------------------------------------------------ geometry helpers

    class Plane(private val a: Double, private val b: Double, private val c: Double,
                private val x0: Double, private val z0: Double, val rmse: Double) {
        fun at(x: Double, z: Double) = a * (x - x0) + b * (z - z0) + c
        val slopePct get() = hypot(a, b) * 100
    }

    /** Least-squares plane y = a x + b z + c, iteratively rejecting points far above (pile) or below. */
    fun robustPlane(x: DoubleArray, z: DoubleArray, y: DoubleArray): Plane {
        val n = x.size
        val x0 = x.average(); val z0 = z.average()
        var keep = BooleanArray(n) { true }
        var coef = doubleArrayOf(0.0, 0.0, y.sorted()[n / 2])
        repeat(8) {
            val k = keep.count { it }
            if (k < 10) return@repeat
            // Normal equations for 3 unknowns.
            var sxx = 0.0; var sxz = 0.0; var sx = 0.0; var szz = 0.0; var sz = 0.0; var s1 = 0.0
            var sxy = 0.0; var szy = 0.0; var sy = 0.0
            for (i in 0 until n) if (keep[i]) {
                val dx = x[i] - x0; val dz = z[i] - z0
                sxx += dx * dx; sxz += dx * dz; sx += dx; szz += dz * dz; sz += dz; s1 += 1.0
                sxy += dx * y[i]; szy += dz * y[i]; sy += y[i]
            }
            coef = solve3(
                arrayOf(doubleArrayOf(sxx, sxz, sx), doubleArrayOf(sxz, szz, sz), doubleArrayOf(sx, sz, s1)),
                doubleArrayOf(sxy, szy, sy),
            ) ?: return@repeat
            val res = DoubleArray(n) { y[it] - (coef[0] * (x[it] - x0) + coef[1] * (z[it] - z0) + coef[2]) }
            val kept = res.filterIndexed { i, _ -> keep[i] }.sorted()
            val med = kept[kept.size / 2]
            val mad = max(kept.map { abs(it - med) }.sorted()[kept.size / 2] * 1.4826, 0.02)
            val newKeep = BooleanArray(n) { res[it] < 2.0 * mad && res[it] > -3.0 * mad }
            if (newKeep.contentEquals(keep)) return@repeat
            keep = newKeep
        }
        var ss = 0.0; var m = 0
        for (i in 0 until n) if (keep[i]) {
            val r = y[i] - (coef[0] * (x[i] - x0) + coef[1] * (z[i] - z0) + coef[2]); ss += r * r; m++
        }
        return Plane(coef[0], coef[1], coef[2], x0, z0, if (m > 0) sqrt(ss / m) else Double.NaN)
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { r -> doubleArrayOf(a[r][0], a[r][1], a[r][2], b[r]) }
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val t = m[col]; m[col] = m[piv]; m[piv] = t
            for (r in 0 until 3) if (r != col) {
                val f = m[r][col] / m[col][col]
                for (k in col until 4) m[r][k] -= f * m[col][k]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    /** Andrew's monotone chain; returns counter-clockwise hull without repeated end point. */
    fun convexHull(pts: List<DoubleArray>): List<DoubleArray> {
        val p = pts.distinctBy { it[0] to it[1] }.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (p.size < 3) return p
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<DoubleArray>()
        for (q in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.size - 1); lower.add(q) }
        val upper = ArrayList<DoubleArray>()
        for (q in p.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.size - 1); upper.add(q) }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    fun polygonArea(poly: List<DoubleArray>): Double {
        var s = 0.0
        for (i in poly.indices) { val a = poly[i]; val b = poly[(i + 1) % poly.size]; s += a[0] * b[1] - b[0] * a[1] }
        return abs(s) / 2
    }

    /** Ray casting – works for any simple polygon (convex or not). */
    fun pointInPolygon(poly: List<DoubleArray>, x: Double, z: Double): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a[1] > z) != (b[1] > z) && x < (b[0] - a[0]) * (z - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside
            j = i
        }
        return inside
    }

    private fun distToPolygon(poly: List<DoubleArray>, x: Double, z: Double): Double {
        var best = Double.MAX_VALUE
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val vx = b[0] - a[0]; val vz = b[1] - a[1]
            val len2 = vx * vx + vz * vz
            val t = if (len2 == 0.0) 0.0 else min(1.0, max(0.0, ((x - a[0]) * vx + (z - a[1]) * vz) / len2))
            val d = hypot(x - (a[0] + t * vx), z - (a[1] + t * vz))
            if (d < best) best = d
        }
        return best
    }
}
