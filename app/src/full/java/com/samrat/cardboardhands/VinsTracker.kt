package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.samrat.vins.VinsCore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.tan

/**
 * 6DoF without ARCore: VINS-Mono, reading the room through the phone's own camera and IMU.
 *
 * The estimator runs in `libvins_jni.so` (see the `vins` module, which carries upstream VINS-Mono and
 * the two lines that hand it frames and sensors) and answers with where the camera is in a world of
 * its own making: gravity up, heading wherever it happened to start. [SixDofAligner] turns that into
 * the [HeadTracker]'s world, so a VINS-Mono room and an ARCore room sit the same way around the
 * windows of the home. Rotation never comes from here — the phone's own fast head tracker keeps it.
 *
 * Two things make this mode different from ARCore, and both are visible to the user:
 *  - it wants the camera, and shares it: the frames come from the home's CameraX analysis stream
 *    ([SixDofCameraFeed]) rather than from a session it owns, so passthrough and hands go on working;
 *  - it has no idea what a wall is. It tracks features, not geometry, so [supportsRoomScan] stays
 *    false: no floor grid, no table for the keyboard, and nothing to save into the room scan.
 */
class VinsTracker private constructor(private val appContext: Context) : SixDof, SixDofCameraFeed, SensorEventListener {

    private val sensors = appContext.getSystemService(SensorManager::class.java)!!
    private val pose = VinsCore.Pose()
    private val aligner = SixDofAligner()
    private val config = File(appContext.filesDir, "vins/vins_config.yaml")
    /** Where the room is, metres, read by the render thread; [raw] is the same numbers as the sensors give. */
    private val position = FloatArray(3)
    private val raw = FloatArray(4)
    private val quaternion = FloatArray(4)
    private val rotationMatrix = FloatArray(9)
    private val cameraRotation = FloatArray(16)

    private val latestAccel = FloatArray(3)
    private val latestGyro = FloatArray(3)
    private var haveAccel = false
    private var haveGyro = false
    /** Scratch, only used when a camera's Y plane is not one byte per pixel. */
    private var scratch: ByteBuffer? = null

    /** The frame size the estimator was configured for; the config is written once, from the first frame. */
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var attemptedWidth = 0
    private var attemptedHeight = 0
    private var started = false
    private var failure: String? = null

    @Volatile override var tracking = false
        private set

    // VINS-Mono's world is z up; the home's is y up with straight ahead along -z. This turns a
    // vector from one to the other, and [cameraRotation] carries the head's turn with it.
    private val upRemap = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f)

    override val ownsCamera get() = false
    override val supportsRoomScan get() = false
    override fun statusText(): String = when {
        failure != null -> "6DoF · VINS-Mono: ${failure}"
        !started -> "6DoF · VINS-Mono is waiting for the camera"
        !tracking -> "6DoF · VINS-Mono is looking for the room…"
        else -> "6DoF · VINS-Mono, the room is tracked"
    }

    override fun copyPosition(out: FloatArray) = synchronized(position) { System.arraycopy(position, 0, out, 0, 3) }

    /**
     * One step on the GL thread: read the newest pose and put it in the head's world. Frames are not
     * taken from here — the camera belongs to CameraX in this mode — so no hand image comes back.
     */
    override fun update(sensorHead: FloatArray, wantImage: Boolean): (() -> ArTracker.CameraFrame?)? {
        if (!started || !VinsCore.readPose(pose)) {
            tracking = false
            return null
        }
        if (!pose.tracked) {
            // Initialising, or the room went out of sight: say so, and let the home's neck model carry
            // the view until the estimator has the walls again.
            tracking = false
            return null
        }
        // The solved frame's pose, or the IMU-propagated one when it is fresher: the same choice the
        // AR demo made between "odometry" and "imu_propagate", with a shorter wait for the head.
        val usePropagated = pose.ageSeconds < .25
        val x = if (usePropagated) pose.propagatedX else pose.x
        val y = if (usePropagated) pose.propagatedY else pose.y
        val z = if (usePropagated) pose.propagatedZ else pose.z
        val qx = if (usePropagated) pose.propagatedQx else pose.qx
        val qy = if (usePropagated) pose.propagatedQy else pose.qy
        val qz = if (usePropagated) pose.propagatedQz else pose.qz
        val qw = if (usePropagated) pose.propagatedQw else pose.qw

        // VINS-Mono's world is z up; the home's is y up with straight ahead along -z. Only the up
        // axis is fixed by that — the heading the estimator started with is the aligner's business.
        quaternion[0] = qx.toFloat(); quaternion[1] = qy.toFloat(); quaternion[2] = qz.toFloat(); quaternion[3] = qw.toFloat()
        SensorManager.getRotationMatrixFromVector(rotationMatrix, quaternion)
        for (row in 0..2) for (col in 0..2) cameraRotation[col * 4 + row] = rotationMatrix[row * 3 + col]
        cameraRotation[3] = 0f; cameraRotation[7] = 0f; cameraRotation[11] = 0f; cameraRotation[15] = 1f
        raw[0] = x.toFloat(); raw[1] = y.toFloat(); raw[2] = z.toFloat(); raw[3] = 1f
        Matrix.multiplyMV(raw, 0, upRemap, 0, raw, 0)
        Matrix.multiplyMM(cameraRotation, 0, upRemap, 0, cameraRotation, 0)
        cameraRotation[12] = raw[0].toFloat()
        cameraRotation[13] = raw[1].toFloat()
        cameraRotation[14] = raw[2].toFloat()
        val atNs = ((if (usePropagated) pose.stampSeconds + pose.ageSeconds else pose.stampSeconds) * 1e9).toLong()
        aligner.update(cameraRotation, sensorHead, atNs)
        synchronized(position) { System.arraycopy(aligner.position, 0, position, 0, 3) }

        tracking = pose.ageSeconds < LOST_AFTER_SECONDS
        return null
    }

    override fun resume(): Boolean {
        startSensors()
        return true
    }

    override fun pause() {
        stopSensors()
    }

    override fun close() {
        stopSensors()
        if (started) VinsCore.stop()
        started = false
    }

    override fun recenter() {
        aligner.reset()
        VinsCore.reset()
    }

    // --- the camera, which CameraX owns and lends here -------------------------------------------

    /** The size the estimator's calibration is written for, so the stream must be exactly this. */
    override val wantedFrame get() = FRAME_WIDTH to FRAME_HEIGHT

    override fun onGrayFrame(gray: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, timestampNs: Long) {
        // The estimator is configured for the stream it is given, and it reads the frame as the sensor
        // delivers it: no rotation, because the home turns the head with its own sensor instead.
        if (width != configuredWidth || height != configuredHeight) ensureConfigured(width, height)
        if (!started) return
        val tight = pixelStride == 1 && gray.isDirect
        val plane = if (tight) gray else pack(gray, width, height, rowStride, pixelStride)
        if (plane == null || !plane.isDirect) return
        // The frame's own exposure time, moved onto the clock the IMU reads are stamped with; what is
        // left of the difference is taken up by VINS-Mono's online temporal calibration (estimate_td).
        val elapsedNs = timestampNs - (System.nanoTime() - SystemClock.elapsedRealtimeNanos())
        VinsCore.pushFrame(plane, width, height, if (tight) rowStride else width, elapsedNs)
    }

    /** Rows the sensor interleaved (a stride wider than the line, or one byte per N pixels). */
    private fun pack(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteBuffer? {
        val copy = scratch ?: ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder()).also { scratch = it }
        copy.clear()
        val row = ByteArray(width)
        val from = source.duplicate()
        for (y in 0 until height) {
            val start = y * rowStride
            if (start + width * pixelStride > from.limit()) return null
            if (pixelStride == 1) {
                from.position(start)
                from.get(row, 0, width)
            } else {
                for (x in 0 until width) row[x] = from.get(start + x * pixelStride)
            }
            copy.put(row, 0, width)
        }
        copy.flip()
        return copy
    }

    /** Says what went wrong, in the settings screen's words, and leaves the mode ready to try again. */
    private fun fail(reason: String) {
        failure = reason
        Log.w(TAG, "VINS-Mono: $reason")
    }

    /** Writes the device's calibration and starts both halves of the estimator, once, on the first frame. */
    private fun ensureConfigured(width: Int, height: Int) {
        if (started && width == configuredWidth && height == configuredHeight) return
        // Only a stream of a different shape is worth starting over for; the same rejected size must
        // not restart the estimator on every frame.
        if (width == attemptedWidth && height == attemptedHeight) return
        attemptedWidth = width
        attemptedHeight = height
        if (started) VinsCore.stop()
        val optics = readCameraOptics(appContext, width, height)
        config.parentFile?.mkdirs()
        runCatching { config.writeText(configText(width, height, optics)) }
            .onFailure { failure = "the calibration file could not be written"; Log.w(TAG, "VINS config", it); return }
        configuredWidth = width
        configuredHeight = height

        val error = runCatching { VinsCore.start(config.absolutePath) }.getOrElse {
            started = false
            fail("the VINS-Mono core is not in this build (${it.javaClass.simpleName})")
            return
        }
        if (error != null) {
            started = false
            fail(error)
            return
        }
        started = true
        failure = null
        startSensors()
        Log.i(TAG, "VINS-Mono started on a ${width}x${height} stream")
    }

    /** Upstream's config file, in its own shape, filled with what this phone's camera reports. */
    private fun configText(width: Int, height: Int, optics: Optics): String = buildString {
        append("%YAML:1.0\n")
        append("# Written by NextVR for ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}.\n")
        append("# The keys and their meaning are VINS-Mono's (vins/config/euroc/euroc_config.yaml);\n")
        append("# the numbers are this phone's own, from the camera characteristics and the sensors.\n")
        append("imu_topic: imu\n")
        append("image_topic: image\n")
        append("\n#camera calibration: the pinhole model of the frame the tracker is fed\n")
        append("model_type: PINHOLE\n")
        append("camera_name: camera\n")
        append("image_width: $width\n")
        append("image_height: $height\n")
        append("distortion_parameters:\n")
        append("   k1: ${optics.k1}\n")
        append("   k2: ${optics.k2}\n")
        append("   p1: ${optics.p1}\n")
        append("   p2: ${optics.p2}\n")
        append("projection_parameters:\n")
        append("   fx: ${optics.fx}\n")
        append("   fy: ${optics.fy}\n")
        append("   cx: ${optics.cx}\n")
        append("   cy: ${optics.cy}\n")
        append("\n# the camera-to-IMU turn is not published by Android: let the estimator find it,\n")
        append("# which is what estimate_extrinsic: 2 means, and why a little rotation helps at first\n")
        append("estimate_extrinsic: 2\n")
        append("\n# feature tracker\n")
        append("max_cnt: 150\n")
        append("min_dist: 25\n")
        append("freq: 30\n")
        append("F_threshold: 2.0\n")
        append("show_track: 0\n")
        append("equalize: 1\n")
        append("fisheye: 0\n")
        append("\n# optimisation: a phone has fewer cores to spare than the drones this was written for\n")
        append("max_solver_time: 0.02\n")
        append("max_num_iterations: 4\n")
        append("keyframe_parallax: 10.0\n")
        append("\n# IMU noise, the ordinary MEMS figures of a phone (upstream's EuRoC ones are tighter)\n")
        append("acc_n: 0.1\n")
        append("acc_w: 0.001\n")
        append("gyr_n: 0.01\n")
        append("gyr_w: 1.0e-4\n")
        append("g_norm: 9.81\n")
        append("\n# the pose graph is not built into this APK, so no loop closure and no map reuse\n")
        append("loop_closure: 0\n")
        append("\n# the camera's clock and the IMU's are not one clock on Android: follow the offset\n")
        append("estimate_td: 1\n")
        append("td: 0.0\n")
        append("\nrolling_shutter: 0\n")
        append("rolling_shutter_tr: 0\n")
    }

    // --- the IMU, which is what makes a monocular camera into a 6DoF tracker ---------------------

    private fun startSensors() {
        if (sensorsRegistered) return
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        val gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        if (accelerometer == null || gyroscope == null) {
            fail("no accelerometer and gyroscope to fuse")
            return
        }
        // The estimator's own rate: VINS-Mono integrates at whatever the sensors give, faster is better.
        sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
        sensorsRegistered = true
    }

    private fun stopSensors() {
        if (!sensorsRegistered) return
        sensors.unregisterListener(this)
        sensorsRegistered = false
    }

    private var sensorsRegistered = false
    private var lastPushNs = 0L

    /**
     * One reading. The estimator takes acceleration and rotation as a pair per instant, so the newest
     * gyroscope sample travels with each accelerometer one: 200 Hz of pairs, the rate a phone really
     * has, and neither sensor is asked to wait for the other.
     */
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> {
                latestAccel[0] = event.values[0]; latestAccel[1] = event.values[1]; latestAccel[2] = event.values[2]
                haveAccel = true
            }

            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                latestGyro[0] = event.values[0]; latestGyro[1] = event.values[1]; latestGyro[2] = event.values[2]
                haveGyro = true
                return
            }

            else -> return
        }
        if (!haveAccel || !haveGyro || !started) return
        val stamp = event.timestamp
        if (stamp <= lastPushNs) return
        lastPushNs = stamp
        VinsCore.pushImu(stamp, latestAccel, latestGyro)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** What the phone's own camera reports about its lens, at the size the tracker is fed. */
    private class Optics(
        val fx: Double, val fy: Double, val cx: Double, val cy: Double,
        val k1: Double, val k2: Double, val p1: Double, val p2: Double,
    )

    /**
     * The intrinsics of the analysis stream. Android publishes a camera's focal length in millimetres
     * and its sensor in millimetres too, which is enough for a pinhole model at any read-out size; the
     * distortion coefficients come from the lens profile where the platform has one.
     */
    private fun readCameraOptics(context: Context, width: Int, height: Int): Optics {
        val fallback = fallbackOptics(width, height)
        val manager = context.getSystemService(CameraManager::class.java) ?: return fallback
        val characteristics = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }?.let { manager.getCameraCharacteristics(it) }
        }.getOrNull() ?: return fallback
        val focalMm = runCatching { characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() }.getOrNull()
        val sensor = runCatching { characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) }.getOrNull()
        val array = runCatching { characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }.getOrNull()
        if (focalMm == null || sensor == null || array == null || array.width() <= 0 || sensor.width <= 0f) return fallback
        // Millimetres per pixel on the whole array, then the same lens at the read-out size.
        val pixelMm = sensor.width / array.width()
        if (pixelMm <= 0f) return fallback
        val scale = width.toFloat() / array.width()
        val focal = focalMm / pixelMm
        // No distortion coefficients: Android's lens profile (CameraCharacteristics.LENS_DISTORTION)
        // is not readable from an ordinary app, and VINS-Mono's pinhole camera model runs on zeros —
        // a phone's main camera is close enough at the 640x480 analysis size, and the estimator only
        // needs the features to land where the model says they should, frame after frame. The scale
        // that is not measured is the honest weakness of this mode, and it is in vins/README.txt.
        return Optics(
            fx = focal.toDouble() * scale,
            // The pixels are square, so the vertical focal length is the same number of pixels.
            fy = focal.toDouble() * scale,
            cx = width / 2.0,
            cy = height / 2.0,
            k1 = 0.0, k2 = 0.0, p1 = 0.0, p2 = 0.0,
        )
    }

    /** No published lens data: a phone's main camera sees about 65° across, so take that. */
    private fun fallbackOptics(width: Int, height: Int): Optics {
        val focal = width / 2.0 / tan(Math.toRadians(HORIZONTAL_DEGREES / 2.0))
        return Optics(focal, focal * height / width, width / 2.0, height / 2.0, 0.0, 0.0, 0.0, 0.0)
    }

    companion object {
        private const val TAG = "PhoneXR-VINS"
        /** What the estimator tracks: upstream's 752×480, rounded to a size every phone can stream. */
        private const val FRAME_WIDTH = 640
        private const val FRAME_HEIGHT = 480
        private const val HORIZONTAL_DEGREES = 65.0
        /** A second without a solved frame is the tracker losing the room, not the head moving. */
        private const val LOST_AFTER_SECONDS = 1.0

        /** Whether the native estimator is in this build of the app at all. */
        val available get() = VinsCore.available

        /** A tracker for this phone, or null when the mode cannot run here (see [SixDofSupport]). */
        fun create(activity: Activity): VinsTracker? {
            if (!available) return null
            if (!SixDofSupport.hasImu(activity) || !SixDofSupport.hasCamera(activity)) return null
            return runCatching { VinsTracker(activity.applicationContext) }.onFailure { Log.w(TAG, "VINS-Mono unavailable", it) }.getOrNull()
        }
    }
}
