package com.stockpile.volume.ar

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.stockpile.volume.measure.Vec3
import com.stockpile.volume.measure.VoxelCloud
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

enum class DepthSource(val label: String, val method: String) {
    RAW("Raw Depth (nejpřesnější)", "arcore-raw-depth"),
    SMOOTH("Depth API", "arcore-depth"),
    FEATURE_POINTS("jen body obrazu – nízká přesnost", "arcore-feature-points"),
}

data class ScanStats(
    val tracking: Boolean = false,
    val hint: String? = null,
    val voxels: Int = 0,
    val pathLengthM: Double = 0.0,
    val distanceToStartM: Double = 0.0,
    val loopClosed: Boolean = false,
    val full: Boolean = false,
)

/**
 * Draws the camera image plus the scanned surface, and turns ARCore depth into a world-space voxel
 * cloud. Runs on the GLSurfaceView render thread; the session must be resumed/paused by the owner.
 */
class ArScanner(val session: Session, val depthSource: DepthSource, private val displayRotation: () -> Int) :
    GLSurfaceView.Renderer {

    val cloud = VoxelCloud(0.05f)
    private val path = ArrayList<Vec3>()
    private val _stats = MutableStateFlow(ScanStats())
    val stats: StateFlow<ScanStats> = _stats.asStateFlow()

    @Volatile var recording = true

    fun pathSnapshot(): List<Vec3> = synchronized(path) { ArrayList(path) }

    // ---------------------------------------------------------------- GL state
    private var cameraTex = 0
    private var bgProgram = 0
    private var ptProgram = 0
    private val quadNdc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadBuf = floatBuffer(quadNdc)
    private val texBuf = floatBuffer(FloatArray(8))
    private var pointVbo = 0
    private var pointCount = 0
    private var frameNo = 0L
    private var lastDepthTs = 0L
    private var pathLen = 0.0
    private var minY = Float.NaN
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val mvp = FloatArray(16)
    private val poseM = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        cameraTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        session.setCameraTextureName(cameraTex)

        bgProgram = program(BG_VS, BG_FS)
        ptProgram = program(PT_VS, PT_FS)
        val vbo = IntArray(1)
        GLES20.glGenBuffers(1, vbo, 0)
        pointVbo = vbo[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session.setDisplayGeometry(displayRotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame: Frame = try {
            session.update()
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e); return
        }
        if (frame.hasDisplayGeometryChanged()) {
            quadBuf.position(0); texBuf.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadBuf,
                Coordinates2d.TEXTURE_NORMALIZED, texBuf,
            )
        }
        if (frame.timestamp == 0L) return
        drawBackground()

        val camera = frame.camera
        frameNo++
        if (camera.trackingState != TrackingState.TRACKING) {
            _stats.value = _stats.value.copy(tracking = false, hint = hintFor(camera.trackingFailureReason))
            return
        }

        if (recording) {
            recordPath(camera.pose.tx(), camera.pose.ty(), camera.pose.tz())
            when (depthSource) {
                DepthSource.RAW, DepthSource.SMOOTH -> addDepth(frame)
                DepthSource.FEATURE_POINTS -> addFeaturePoints(frame)
            }
        }

        // Re-upload the preview points twice a second.
        if (frameNo % 15 == 0L) uploadPreview()
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        drawPoints()

        if (frameNo % 10 == 0L) publishStats(camera.pose.tx(), camera.pose.tz())
    }

    // ---------------------------------------------------------------- data capture

    private fun recordPath(x: Float, y: Float, z: Float) {
        synchronized(path) {
            val last = path.lastOrNull()
            if (last == null || hypot((x - last.x).toDouble(), (z - last.z).toDouble()) > 0.15) {
                if (last != null) pathLen += hypot((x - last.x).toDouble(), (z - last.z).toDouble())
                path.add(Vec3(x, y, z))
            }
        }
    }

    private fun addDepth(frame: Frame) {
        val raw = depthSource == DepthSource.RAW
        val depth = try {
            if (raw) frame.acquireRawDepthImage16Bits() else frame.acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return
        }
        depth.use { d ->
            if (d.timestamp == lastDepthTs) return
            lastDepthTs = d.timestamp
            val conf = if (raw) try { frame.acquireRawDepthConfidenceImage() } catch (e: NotYetAvailableException) { null } else null
            try {
                val intr = frame.camera.textureIntrinsics
                val f = intr.focalLength; val pp = intr.principalPoint; val dims = intr.imageDimensions
                val w = d.width; val h = d.height
                val fx = f[0] * w / dims[0]; val fy = f[1] * h / dims[1]
                val cx = pp[0] * w / dims[0]; val cy = pp[1] * h / dims[1]
                frame.camera.pose.toMatrix(poseM, 0)

                val plane = d.planes[0]
                val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
                val rs = plane.rowStride; val ps = plane.pixelStride
                val cPlane = conf?.planes?.get(0)
                val cBuf = cPlane?.buffer
                val cRs = cPlane?.rowStride ?: 0
                val cPs = cPlane?.pixelStride ?: 0
                val step = if (raw) 1 else 2
                var v = 0
                while (v < h) {
                    var u = 0
                    while (u < w) {
                        val mm = buf.getShort(v * rs + u * ps).toInt() and 0xFFFF
                        if (mm in 300..6000) {
                            val ok = cBuf == null ||
                                (cBuf.get(v * cRs + u * cPs).toInt() and 0xFF) >= 140
                            if (ok) {
                                val dm = mm / 1000f
                                // Camera space (OpenGL convention): +x right, +y up, looking down -z.
                                val xc = (u - cx) * dm / fx
                                val yc = -(v - cy) * dm / fy
                                val zc = -dm
                                val wx = poseM[0] * xc + poseM[4] * yc + poseM[8] * zc + poseM[12]
                                val wy = poseM[1] * xc + poseM[5] * yc + poseM[9] * zc + poseM[13]
                                val wz = poseM[2] * xc + poseM[6] * yc + poseM[10] * zc + poseM[14]
                                cloud.add(wx, wy, wz)
                            }
                        }
                        u += step
                    }
                    v += step
                }
            } finally {
                conf?.close()
            }
        }
    }

    private fun addFeaturePoints(frame: Frame) {
        frame.acquirePointCloud().use { pc ->
            val b = pc.points
            while (b.remaining() >= 4) {
                val x = b.get(); val y = b.get(); val z = b.get(); val c = b.get()
                if (c >= 0.3f) cloud.add(x, y, z)
            }
        }
    }

    private fun publishStats(x: Float, z: Float) {
        val start = synchronized(path) { path.firstOrNull() }
        val toStart = if (start == null) 0.0 else hypot((x - start.x).toDouble(), (z - start.z).toDouble())
        val closed = pathLen > 10.0 && toStart < 1.5
        _stats.value = ScanStats(
            tracking = true,
            hint = if (cloud.isFull) "Paměť skenu je plná – dokončete měření." else null,
            voxels = cloud.size,
            pathLengthM = pathLen,
            distanceToStartM = toStart,
            loopClosed = closed || _stats.value.loopClosed,
            full = cloud.isFull,
        )
    }

    private fun hintFor(r: TrackingFailureReason): String = when (r) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Málo světla."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Pohybujte telefonem pomaleji."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Mířte na povrch se strukturou (ne na oblohu či hladkou plochu)."
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Kamera není dostupná."
        else -> "Hledám okolí… pomalu pohybujte telefonem."
    }

    // ---------------------------------------------------------------- drawing

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        val pos = GLES20.glGetAttribLocation(bgProgram, "a_Position")
        val tex = GLES20.glGetAttribLocation(bgProgram, "a_TexCoord")
        quadBuf.position(0); texBuf.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quadBuf)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos); GLES20.glDisableVertexAttribArray(tex)
        GLES20.glDepthMask(true)
    }

    private fun uploadPreview() {
        val pts = cloud.previewPoints(60_000)
        pointCount = pts.size / 3
        if (pointCount == 0) return
        var lo = Float.MAX_VALUE
        for (i in 1 until pts.size step 3) if (pts[i] < lo) lo = pts[i]
        minY = lo
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pointVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, pts.size * 4, floatBuffer(pts), GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawPoints() {
        if (pointCount == 0) return
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(ptProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ptProgram, "u_MVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProgram, "u_BaseY"), minY)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(ptProgram, "u_PointSize"), 7f)
        val pos = GLES20.glGetAttribLocation(ptProgram, "a_Position")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, pointVbo)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, pointCount)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    companion object {
        private const val TAG = "ArScanner"

        fun configure(session: Session): DepthSource {
            val config = Config(session).apply {
                focusMode = Config.FocusMode.AUTO
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                lightEstimationMode = Config.LightEstimationMode.DISABLED
                updateMode = Config.UpdateMode.BLOCKING
            }
            val source = when {
                session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY) -> {
                    config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY; DepthSource.RAW
                }
                session.isDepthModeSupported(Config.DepthMode.AUTOMATIC) -> {
                    config.depthMode = Config.DepthMode.AUTOMATIC; DepthSource.SMOOTH
                }
                else -> {
                    config.depthMode = Config.DepthMode.DISABLED; DepthSource.FEATURE_POINTS
                }
            }
            session.configure(config)
            return source
        }

        private fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s))
            return s
        }

        private fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            return p
        }

        private const val BG_VS = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        private const val BG_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
        """
        // Points coloured by height above the lowest scanned point: blue (ground) → yellow → red (top).
        private const val PT_VS = """
            uniform mat4 u_MVP;
            uniform float u_BaseY;
            uniform float u_PointSize;
            attribute vec4 a_Position;
            varying float v_H;
            void main() {
                gl_Position = u_MVP * vec4(a_Position.xyz, 1.0);
                gl_PointSize = u_PointSize;
                v_H = clamp((a_Position.y - u_BaseY) / 4.0, 0.0, 1.0);
            }
        """
        private const val PT_FS = """
            precision mediump float;
            varying float v_H;
            void main() {
                vec3 low = vec3(0.15, 0.55, 1.0);
                vec3 mid = vec3(1.0, 0.9, 0.2);
                vec3 high = vec3(1.0, 0.25, 0.15);
                vec3 c = v_H < 0.5 ? mix(low, mid, v_H * 2.0) : mix(mid, high, (v_H - 0.5) * 2.0);
                gl_FragColor = vec4(c, 0.85);
            }
        """
    }
}
