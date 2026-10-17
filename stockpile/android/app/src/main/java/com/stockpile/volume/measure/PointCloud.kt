package com.stockpile.volume.measure

// Pure Kotlin (no Android imports) so it can be unit-tested on a desktop JVM.
// Coordinates are ARCore world space: metres, Y points up (gravity aligned), X/Z horizontal.

data class Vec3(val x: Float, val y: Float, val z: Float)

/**
 * Accumulates millions of noisy depth samples into a sparse voxel grid.
 * Each voxel keeps the running mean of the samples that fell into it and how many there were,
 * so memory stays bounded however long the scan runs, and repeated observations average out noise.
 */
class VoxelCloud(val voxelSize: Float = 0.05f, private val maxVoxels: Int = 3_000_000) {
    private val index = HashMap<Long, Int>(1 shl 16)
    private var sx = FloatArray(1 shl 16)
    private var sy = FloatArray(1 shl 16)
    private var sz = FloatArray(1 shl 16)
    private var cnt = IntArray(1 shl 16)
    var size = 0
        private set
    var samples = 0L
        private set
    private var cx = 0.0
    private var cz = 0.0

    val isFull: Boolean get() = size >= maxVoxels

    /** Centre (x, z) of everything scanned so far – roughly the pile the camera keeps looking at. */
    @Synchronized
    fun centerXZ(): Pair<Double, Double>? = if (size == 0) null else (cx / size) to (cz / size)

    @Synchronized
    fun add(x: Float, y: Float, z: Float) {
        val key = key(cell(x), cell(y), cell(z))
        var i = index[key]
        if (i == null) {
            if (size >= maxVoxels) return
            if (size == cnt.size) grow()
            i = size++
            index[key] = i
            cx += x; cz += z
        }
        sx[i] += x; sy[i] += y; sz[i] += z; cnt[i]++
        samples++
    }

    private fun grow() {
        val n = cnt.size * 2
        sx = sx.copyOf(n); sy = sy.copyOf(n); sz = sz.copyOf(n); cnt = cnt.copyOf(n)
    }

    private fun cell(v: Float) = kotlin.math.floor(v / voxelSize).toInt()

    /** Copy of the voxels as mean positions + hit counts; safe to process on another thread. */
    @Synchronized
    fun snapshot(): VoxelSnapshot {
        val x = FloatArray(size); val y = FloatArray(size); val z = FloatArray(size); val c = IntArray(size)
        for (i in 0 until size) {
            val n = cnt[i].toFloat()
            x[i] = sx[i] / n; y[i] = sy[i] / n; z[i] = sz[i] / n; c[i] = cnt[i]
        }
        val keys = LongArray(size)
        for ((k, i) in index) keys[i] = k
        return VoxelSnapshot(voxelSize, x, y, z, c, keys)
    }

    /** Up to [max] voxel centres (with >= 2 hits) for on-screen preview, as x,y,z triples. */
    @Synchronized
    fun previewPoints(max: Int): FloatArray {
        val step = maxOf(1, size / max)
        val out = FloatArray(minOf(size, max) * 3 + 3)
        var o = 0
        var i = 0
        while (i < size && o + 3 <= out.size) {
            if (cnt[i] >= 2) {
                val n = cnt[i].toFloat()
                out[o++] = sx[i] / n; out[o++] = sy[i] / n; out[o++] = sz[i] / n
            }
            i += step
        }
        return out.copyOf(o)
    }

    companion object {
        private const val OFF = 1 shl 20
        private const val MASK = (1L shl 21) - 1
        fun key(ix: Int, iy: Int, iz: Int): Long =
            ((ix + OFF).toLong() and MASK) or (((iy + OFF).toLong() and MASK) shl 21) or (((iz + OFF).toLong() and MASK) shl 42)
    }
}

class VoxelSnapshot(
    val voxelSize: Float,
    val x: FloatArray, val y: FloatArray, val z: FloatArray,
    val count: IntArray,
    val keys: LongArray,
) {
    val size get() = x.size
}
