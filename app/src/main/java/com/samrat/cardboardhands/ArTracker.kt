package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 6DoF for the VR home with ARCore: where the headset is in the room. The head's rotation still
 * comes from the fast [HeadTracker]; ARCore adds the position, turned into the same world (its yaw
 * is aligned to the head tracker's by [SixDofAligner]), so windows stay put while the user walks
 * around them. ARCore also owns the camera: it gives the passthrough texture and the frames for hand
 * tracking. Both jobs — the position and the camera — are what [SixDof] promises the home.
 */
class ArTracker private constructor(private val session: Session) : SixDof {
    enum class Availability { READY, CHECKING, MISSING }

    /** Camera frame for hands: upright bitmap, its time, and where it lies on the eye's view (0..1). */
    class CameraFrame(val bitmap: Bitmap, val timestampNs: Long, val viewLeft: Float, val viewTop: Float, val viewWidth: Float, val viewHeight: Float)

    @Volatile override var tracking = false
        private set
    @Volatile override var horizontalPlanes = 0
        private set
    @Volatile override var verticalPlanes = 0
        private set
    /** What the room scan found, in the VR home's world: floor, table, walls, drawn as a grid. */
    @Volatile override var surfaces: List<RoomScan.Surface> = emptyList()
        private set
    private var frames = 0
    private var lastPlanes: List<Plane> = emptyList()

    // Steady position, yaw alignment and jump rejection are the same work for every backend.
    private val aligner = SixDofAligner()
    private val quadNdc: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    /** Texture coordinates of the passthrough quad (per eye), updated when the display changes. */
    override val passthroughUv: FloatBuffer = floatBuffer(FloatArray(8))
    @Volatile private var hasUv = false
    /** The camera's projection for one eye's viewport: virtual things line up with the passthrough. */
    private val projectionMatrix = FloatArray(16).also { Matrix.perspectiveM(it, 0, 90f, 1f, .05f, 100f) }
    override val projection: FloatArray get() = projectionMatrix
    private var imageRotation = 0
    private var imageToView = floatArrayOf(0f, 0f, 1f, 1f)

    /** Must be called on the GL thread with the external texture ARCore draws the camera into. */
    override fun attachTexture(texture: Int) = session.setCameraTextureName(texture)

    override fun setDisplay(rotation: Int, eyeWidth: Int, height: Int) = session.setDisplayGeometry(rotation, eyeWidth, height)

    override fun resume() = runCatching { session.resume() }.onFailure { Log.w(TAG, "ARCore resume failed", it) }.isSuccess

    override fun pause() {
        runCatching { session.pause() }.onFailure { Log.w(TAG, "ARCore pause failed", it) }
    }

    override fun close() = session.close()

    /** Makes the current spot the centre of the room again (with the head tracker's recenter). */
    override fun recenter() {
        aligner.reset()
        horizontalPlanes = 0
        verticalPlanes = 0
    }

    override val ownsCamera get() = true
    // ARCore fits planes to the room, so this is the one backend that can scan it.
    override val supportsRoomScan get() = true

    override fun copyPosition(out: FloatArray) = synchronized(aligner.position) {
        System.arraycopy(aligner.position, 0, out, 0, 3)
    }

    override fun statusText(): String = when {
        !tracking -> "6DoF · ARCore is looking for the room…"
        else -> "6DoF · ARCore, the room is tracked"
    }

    /**
     * One ARCore frame on the GL thread. [sensorHead] is the head tracker's rotation, used to align
     * ARCore's world. Returns a CPU camera frame for the hands when [wantImage] and one is ready.
     */
    override fun update(sensorHead: FloatArray, wantImage: Boolean): (() -> CameraFrame?)? {
        val frame: Frame = runCatching { session.update() }.getOrElse { return null }
        if (frame.hasDisplayGeometryChanged() || !hasUv) {
            passthroughUv.position(0)
            quadNdc.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadNdc, Coordinates2d.TEXTURE_NORMALIZED, passthroughUv)
            passthroughUv.position(0)
            hasUv = true
            updateImageMapping(frame)
        }
        val camera = frame.camera
        tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            // The planes the scan is made of: tracked, and not already folded into a bigger one
            // (ARCore retires a plane once a larger one subsumes it).
            val planes = session.getAllTrackables(Plane::class.java).filter {
                it.trackingState == TrackingState.TRACKING && it.subsumedBy == null
            }
            horizontalPlanes = planes.count { it.type != Plane.Type.VERTICAL }
            verticalPlanes = planes.count { it.type == Plane.Type.VERTICAL }
            lastPlanes = planes
        }
        synchronized(projectionMatrix) { camera.getProjectionMatrix(projectionMatrix, 0, .05f, 100f) }
        if (tracking) {
            // ARCore's world and the head's hang from the same gravity; the aligner follows the
            // heading, drops the jump of a relocalisation and smooths the rest.
            val ar = FloatArray(16).also { camera.displayOrientedPose.toMatrix(it, 0) }
            aligner.update(ar, sensorHead, frame.timestamp)
        }
        // The scanned surfaces, a few times a second (their outlines grow slowly). Only once the
        // aligner has a world to map them into, so the grids land where the windows do.
        if (tracking && aligner.origin != null && !aligner.alignYaw.isNaN() && frames++ % 10 == 0) {
            surfaces = lastPlanes.mapNotNull { plane ->
                runCatching { RoomScan.surface(plane) { x, y, z -> aligner.transformPoint(x, y, z) } }.getOrNull()
            }
        }
        if (!wantImage) return null
        val image: Image = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return null
        // Copy the planes now (the image must go back to ARCore), convert later off the GL thread.
        val timestamp = image.timestamp
        val width = image.width
        val height = image.height
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        val yBytes = copy(y.buffer); val uBytes = copy(u.buffer); val vBytes = copy(v.buffer)
        val yStride = y.rowStride; val uvStride = u.rowStride; val uvPixel = u.pixelStride
        image.close()
        val rotation = imageRotation
        val map = imageToView.copyOf()
        return {
            val bitmap = yuvToBitmap(width, height, yBytes, uBytes, vBytes, yStride, uvStride, uvPixel).rotate(rotation)
            CameraFrame(bitmap, timestamp, map[0], map[1], map[2], map[3])
        }
    }

    /** How the CPU image sits on the eye's view: its rotation and the box it covers (may exceed 0..1). */
    private fun updateImageMapping(frame: Frame) {
        val size = frame.camera.imageIntrinsics.imageDimensions
        val w = size[0].toFloat(); val h = size[1].toFloat()
        val corners = floatArrayOf(0f, 0f, w, 0f, 0f, h, w, h)
        val out = FloatArray(8)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.VIEW_NORMALIZED, out)
        // Where the image's x axis points on the view (y down): that is how far to turn it upright.
        val ex = out[2] - out[0]; val ey = out[3] - out[1]
        imageRotation = ((Math.toDegrees(atan2(ey, ex).toDouble()) / 90.0).roundToInt() * 90 + 360) % 360
        val xs = listOf(out[0], out[2], out[4], out[6]); val ys = listOf(out[1], out[3], out[5], out[7])
        imageToView = floatArrayOf(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
    }

    companion object {
        private const val TAG = "PhoneXR-AR"

        /**
         * ARCore's answer can take a moment on the first call: CHECKING means ask again shortly.
         *
         * A [Context] is enough to ask, so the settings screens can grey a mode out without being
         * an activity; only [requestInstall] needs one, and only they raise the install prompt —
         * a screen that merely lists the modes must never send the user to the Play Store.
         */
        fun availability(context: Context, requestInstall: Boolean = false): Availability {
            val answer = runCatching { ArCoreApk.getInstance().checkAvailability(context.applicationContext) }
                .getOrNull() ?: return Availability.MISSING
            return when {
                answer == ArCoreApk.Availability.SUPPORTED_INSTALLED -> Availability.READY
                answer.isTransient -> Availability.CHECKING
                answer == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED || answer == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> {
                    if (requestInstall && context is Activity) {
                        runCatching { ArCoreApk.getInstance().requestInstall(context, true) }
                    }
                    Availability.MISSING
                }
                else -> Availability.MISSING
            }
        }

        /** An ARCore session, or null when it cannot start (then the home stays 3DoF). */
        fun create(activity: Activity): ArTracker? = runCatching {
            val session = Session(activity)
            val config = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
                // Room geometry exists only in 6DoF. Horizontal and vertical planes are the basis
                // for walls, tables and collision-aware PhoneXR experiences.
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
                lightEstimationMode = Config.LightEstimationMode.DISABLED
            }
            session.configure(config)
            ArTracker(session)
        }.onFailure { Log.w(TAG, "ARCore session failed", it) }.getOrNull()

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }

        private fun copy(buffer: ByteBuffer): ByteArray {
            buffer.rewind()
            return ByteArray(buffer.remaining()).also { buffer.get(it) }
        }

        private fun yuvToBitmap(
            width: Int, height: Int, yData: ByteArray, uData: ByteArray, vData: ByteArray,
            yStride: Int, uvStride: Int, uvPixel: Int,
        ): Bitmap {
            val pixels = IntArray(width * height)
            for (row in 0 until height) {
                val yRow = row * yStride
                val uvRow = (row shr 1) * uvStride
                for (col in 0 until width) {
                    val yy = (yData[yRow + col].toInt() and 0xff) - 16
                    val uvIndex = uvRow + (col shr 1) * uvPixel
                    val uu = (uData.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xff) - 128
                    val vv = (vData.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xff) - 128
                    val c = 1192 * yy.coerceAtLeast(0)
                    val r = ((c + 1634 * vv) shr 10).coerceIn(0, 255)
                    val g = ((c - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255)
                    val b = ((c + 2066 * uu) shr 10).coerceIn(0, 255)
                    pixels[row * width + col] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        }
    }
}
