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
import android.os.Handler
import android.os.HandlerThread
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
    /** Scratch for the remapped position: [raw] cannot be both the input and the output of a multiply. */
    private val remapped = FloatArray(4)

    private val latestAccel = FloatArray(3)
    private val latestGyro = FloatArray(3)
    private var haveAccel = false
    private var haveGyro = false
    /** Scratch, only used when a camera's Y plane is not one byte per pixel. */
    private var scratch: ByteBuffer? = null

    // Counters for the health line, and the window they are counted over. Without them a logcat
    // says nothing about whether the estimator is running, which is how a mode that had never once
    // solved a frame looked the same as one that was tracking.
    private var imuCount = 0
    private var frameCount = 0
    private var windowStartNs = 0L
    private var lastHealthNs = 0L

    /** The frame size the estimator was configured for; the config is written once, from the first frame. */
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var attemptedWidth = 0
    private var attemptedHeight = 0
    private var started = false
    private var failure: String? = null

    @Volatile override var tracking = false
        private set

    // VINS-Mono's world is z up; the home's is y up with straight ahead along -z. [VinsWorld] is that
    // turn (a quarter turn about x) and the test that keeps it a rotation; [cameraRotation] carries
    // the head's turn with it.
    private val upRemap = VinsWorld.REMAP

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
        // The position goes through its own output array: multiplyMV may not write a result over
        // the vector it is reading from, and doing so left the depth axis undefined.
        Matrix.multiplyMV(remapped, 0, upRemap, 0, raw, 0)
        Matrix.multiplyMM(cameraRotation, 0, upRemap, 0, cameraRotation, 0)
        cameraRotation[12] = remapped[0]
        cameraRotation[13] = remapped[1]
        cameraRotation[14] = remapped[2]
        val atNs = ((if (usePropagated) pose.stampSeconds + pose.ageSeconds else pose.stampSeconds) * 1e9).toLong()
        aligner.update(cameraRotation, sensorHead, atNs)
        synchronized(position) { System.arraycopy(aligner.position, 0, position, 0, 3) }

        tracking = pose.ageSeconds < LOST_AFTER_SECONDS
        health(x.toFloat(), y.toFloat(), z.toFloat())
        return null
    }

    /**
     * One line every two seconds, so a logcat shows what the estimator is doing without being
     * flooded: where the camera is in its own world, how fast it is said to be moving, how old the
     * solved frame is, and the two rates that decide whether the window can converge at all.
     */
    private fun health(x: Float, y: Float, z: Float) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastHealthNs < HEALTH_PERIOD_NS) return
        val elapsed = (now - windowStartNs).coerceAtLeast(1L)
        val imuHz = imuCount * 1e9 / elapsed
        val framesHz = frameCount * 1e9 / elapsed
        imuCount = 0
        frameCount = 0
        windowStartNs = now
        lastHealthNs = now
        val speed = kotlin.math.sqrt(pose.vx * pose.vx + pose.vy * pose.vy + pose.vz * pose.vz)
        Log.i(
            TAG,
            "pose=[%.3f %.3f %.3f] speed=%.2f m/s age=%.2f s solving=%b enough=%b imu=%.0f Hz frames=%.0f Hz".format(
                x, y, z, speed, pose.ageSeconds, pose.solving, pose.featuresEnough, imuHz, framesHz,
            ),
        )
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
        sensorThread.quitSafely()
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
        // The frame's own exposure time, on the monotonic clock (elapsedRealtimeNanos); what is
        // left of the difference is taken up by VINS-Mono's online temporal calibration (estimate_td).
        frameCount++
        VinsCore.pushFrame(plane, width, height, if (tight) rowStride else width, timestampNs)
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
        runCatching {
            config.writeText(
                VinsConfig.text(
                    width, height, optics,
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                ),
            )
        }.onFailure { failure = "the calibration file could not be written"; Log.w(TAG, "VINS config", it); return }
        configuredWidth = width
        configuredHeight = height
        // What was written, in one line: the lens numbers decide the world scale and the extrinsic
        // decides whether it is a world at all, and neither was ever in the log before.
        Log.i(
            TAG,
            "config ${width}x$height fx=%.1f fy=%.1f cx=%.1f cy=%.1f k=[%.4f %.4f %.4f %.4f] RIC=%s at %s".format(
                optics.fx, optics.fy, optics.cx, optics.cy,
                optics.k1, optics.k2, optics.p1, optics.p2,
                optics.extrinsicRotation.joinToString(","), config.absolutePath,
            ),
        )

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
        // A fresh run must not count frames and readings from the one before it.
        imuCount = 0
        frameCount = 0
        windowStartNs = SystemClock.elapsedRealtimeNanos()
        lastHealthNs = 0L
        startSensors()
        Log.i(TAG, "VINS-Mono started on a ${width}x$height stream")
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
        // The estimator's own rate: VINS-Mono integrates at whatever the sensors give, faster is
        // better. The readings arrive on a thread of this tracker's own — the render thread, which
        // is what they would land on otherwise, must not take the estimator's locks hundreds of
        // times a second.
        sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        sensorsRegistered = true
    }

    private val sensorThread = HandlerThread("PhoneXR-VINS-sensors").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    private fun stopSensors() {
        if (!sensorsRegistered) return
        sensors.unregisterListener(this)
        sensorsRegistered = false
    }

    private var sensorsRegistered = false
    private var lastPushNs = 0L

    /**
     * One reading. The estimator takes acceleration and rotation as a pair per instant, so every
     * sample carries the newest of the other sensor with it — a hold of a millisecond or two, far
     * under what the estimator can see. Pushing on *both* sensors' events keeps the gyroscope, the
     * one that carries the head's turn, integrated at its full rate (often twice the
     * accelerometer's) instead of pacing the rotation to the other sensor's clock.
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
            }

            else -> return
        }
        if (!haveAccel || !haveGyro || !started) return
        val stamp = event.timestamp
        if (stamp <= lastPushNs) return
        lastPushNs = stamp
        imuCount++
        VinsCore.pushImu(stamp, latestAccel, latestGyro)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * The intrinsics of the analysis stream. Android publishes a camera's focal length in millimetres
     * and its sensor in millimetres too, which is enough for a pinhole model at any read-out size;
     * where the phone carries a factory lens calibration, its distortion coefficients come with it.
     */
    private fun readCameraOptics(context: Context, width: Int, height: Int): VinsConfig.Optics {
        val fallback = fallbackOptics(width, height)
        val manager = context.getSystemService(CameraManager::class.java) ?: return fallback
        // The main camera is the one CameraX opens for DEFAULT_BACK_CAMERA, and it is the back-facing
        // sensor with the most silicon: the first back camera in the list is sometimes the
        // ultra-wide instead, and intrinsics read off the wrong lens are a wrong world scale.
        val characteristics = runCatching {
            manager.cameraIdList
                .mapNotNull { id ->
                    val candidate = manager.getCameraCharacteristics(id)
                    if (candidate.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) null
                    else candidate.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        ?.let { candidate to it.width() * it.height() }
                }
                .maxByOrNull { it.second }?.first
        }.getOrNull() ?: return fallback
        val focalMm = runCatching { characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() }.getOrNull()
        val sensor = runCatching { characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) }.getOrNull()
        val array = runCatching { characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }.getOrNull()
        val intrinsic = runCatching { characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) }.getOrNull()
        val preCorrection = runCatching { characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) }.getOrNull()
        val sensorOrientation = runCatching { characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) }.getOrNull() ?: 90
        // The camera-to-IMU rotation of the raw, unrotated buffer this tracker is fed — the axes the
        // estimator's projection factors must agree with. Derived, not guessed: VinsExtrinsics.
        val extrinsic = VinsExtrinsics.cameraFromImu(sensorOrientation)
        val distortion = platformDistortion(characteristics)
        // The factory's own pinhole model, when the phone carries one (DEPTH-capable modules do):
        // focal length measured in pixels of the pre-correction array. The distortion coefficients
        // above are normalised by exactly this focal length, so the two belong together — a better
        // scale than the millimetre derivation below whenever both exist.
        if (intrinsic != null && intrinsic.size >= 4 && preCorrection != null &&
            preCorrection.width() > 0 && preCorrection.height() > 0
        ) {
            val fxFraction = intrinsic[0].toDouble() / preCorrection.width()
            val fyFraction = intrinsic[1].toDouble() / preCorrection.height()
            if (fxFraction in 0.2..2.5 && fyFraction in 0.2..2.5) {
                return VinsConfig.Optics(
                    fxFraction * width, fyFraction * height, width / 2.0, height / 2.0,
                    distortion[0], distortion[1], distortion[2], distortion[3],
                    extrinsic,
                )
            }
        }
        if (focalMm == null || sensor == null || array == null || array.width() <= 0 || sensor.width <= 0f) {
            return VinsConfig.Optics(
                fallback.fx, fallback.fy, fallback.cx, fallback.cy,
                distortion[0], distortion[1], distortion[2], distortion[3],
                extrinsic,
            )
        }
        // Millimetres per pixel on the whole array, then the same lens at the read-out size.
        val pixelMm = sensor.width / array.width()
        if (pixelMm <= 0f) return fallback
        val scale = width.toFloat() / array.width()
        val focal = focalMm / pixelMm
        return VinsConfig.Optics(
            fx = focal.toDouble() * scale,
            // The pixels are square, so the vertical focal length is the same number of pixels.
            fy = focal.toDouble() * scale,
            cx = width / 2.0,
            cy = height / 2.0,
            k1 = distortion[0], k2 = distortion[1], p1 = distortion[2], p2 = distortion[3],
            extrinsicRotation = extrinsic,
        )
    }

    /**
     * The lens distortion the phone itself reports, as the four coefficients VINS-Mono's pinhole
     * model takes (k1, k2, p1, p2): zeros where the phone has no factory calibration to report.
     * `LENS_DISTORTION` is [kappa_1..kappa_5] — three radial, then two tangential — in the same
     * normalised units VINS-Mono's model works in (a HAL with no calibration to report answers all
     * zeros, which is what the check below turns away). The tangential pair maps onto OpenCV's p1
     * and p2 in order (kappa_4 → p1, kappa_5 → p2, same equations); the third radial term has no
     * place in the four-coefficient model, so it is dropped.
     */
    private fun platformDistortion(characteristics: CameraCharacteristics): DoubleArray {
        val coefficients = runCatching { characteristics.get(CameraCharacteristics.LENS_DISTORTION) }.getOrNull()
            ?: return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        if (coefficients.size < 5) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val (k1, k2, k3, p1, p2) = coefficients.map { it.toDouble() }
        if (k1 == 0.0 && k2 == 0.0 && k3 == 0.0 && p1 == 0.0 && p2 == 0.0) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        return doubleArrayOf(k1, k2, p1, p2)
    }

    /** No published lens data: a phone's main camera sees about 65° across, so take that. */
    private fun fallbackOptics(width: Int, height: Int): VinsConfig.Optics {
        val focal = width / 2.0 / tan(Math.toRadians(HORIZONTAL_DEGREES / 2.0))
        return VinsConfig.Optics(
            focal, focal * height / width, width / 2.0, height / 2.0,
            0.0, 0.0, 0.0, 0.0,
            // The commonest mount, and the one that cannot be read: most main cameras sit at 90°.
            VinsExtrinsics.cameraFromImu(90),
        )
    }

    companion object {
        private const val TAG = "PhoneXR-VINS"
        /** What the estimator tracks: upstream's 752×480, rounded to a size every phone can stream. */
        private const val FRAME_WIDTH = 640
        private const val FRAME_HEIGHT = 480
        private const val HORIZONTAL_DEGREES = 65.0
        /** A second without a solved frame is the tracker losing the room, not the head moving. */
        private const val LOST_AFTER_SECONDS = 1.0
        /** How often the health line is written: often enough to see a trend, rarely enough to read. */
        private const val HEALTH_PERIOD_NS = 2_000_000_000L

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
