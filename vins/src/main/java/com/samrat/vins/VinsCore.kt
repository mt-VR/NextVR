package com.samrat.vins

/**
 * VINS-Mono, as far as Android can reach it: the upstream visual-inertial estimator in
 * `libvins_jni.so`, fed with camera frames and IMU readings and asked for a pose.
 *
 * Nothing here knows about NextVR. The app pushes what the sensors report and reads the newest pose
 * (see `vins/src/main/cpp/runtime/vins_runtime.h` for the numbers), and keeps its own idea of where
 * the room is. Keeping this small is what lets the estimator stay upstream code: the adaptation lives
 * in NextVR, not inside VINS-Mono.
 *
 * Clocks: both the frames and the IMU readings are stamped in `SystemClock.elapsedRealtimeNanos`,
 * the timebase Android's sensors use. A camera frame's own timestamp is converted into it by the
 * caller; whatever is left of the difference is taken up by VINS-Mono's online temporal calibration
 * (`estimate_td`), which is what it is for.
 */
object VinsCore {

    /** A pose of the headset, filled in place by [readPose] so a render frame allocates nothing. */
    class Pose {
        /** Position in the estimator's world, metres: x, y, z, with z up. */
        var x = 0.0
        var y = 0.0
        var z = 0.0
        /** Rotation of the camera in that world, as a quaternion. */
        var qx = 0.0
        var qy = 0.0
        var qz = 0.0
        var qw = 1.0
        /** Velocity, m/s — useful for telling a still room from a walking one. */
        var vx = 0.0
        var vy = 0.0
        var vz = 0.0
        /** The same pose carried forward by the IMU to the newest reading: what to draw for "now". */
        var propagatedX = 0.0
        var propagatedY = 0.0
        var propagatedZ = 0.0
        var propagatedQx = 0.0
        var propagatedQy = 0.0
        var propagatedQz = 0.0
        var propagatedQw = 1.0
        /** Seconds (`elapsedRealtimeNanos`/1e9) of the frame this pose was solved for. */
        var stampSeconds = 0.0
        /** True once the sliding window has been solved: only then do the numbers mean anything. */
        var tracked = false
        /** True while the estimator is solving rather than initialising. */
        var solving = false
        /** Enough features to trust the frame: the front end found what it needs. */
        var featuresEnough = false
        /** Seconds since the last solved frame; tracking is lost when it grows. */
        var ageSeconds = 0.0
        /** The image-to-IMU time offset the estimator settled on, seconds. */
        var timeOffsetSeconds = 0.0
    }

    /** The pose in a flat array; the indices are the ones `vins_jni.cpp` writes. */
    private val raw = DoubleArray(21)

    /** Whether this APK carries the native core at all (it is missing when the module was switched off). */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("vins_jni") }.isSuccess
    }.also { loaded -> if (!loaded) android.util.Log.w(TAG, "libvins_jni.so is not in this build") }

    /**
     * Starts the estimator with the device's config file (a YAML in the shape of upstream's
     * `config/euroc/euroc_config.yaml`). Null when it is running; otherwise the reason, in words.
     */
    fun start(configPath: String): String? {
        if (!available) return "the VINS-Mono core is not in this build"
        val answer = runCatching { nativeStart(configPath) }.getOrNull()
        return when {
            answer == null -> "the VINS-Mono core could not start"
            answer == "OK" -> null
            else -> answer
        }
    }

    /** Stops the estimator's thread and lets go of everything it held. */
    fun stop() {
        if (available) runCatching { nativeStop() }
    }

    /** True between [start] and [stop]. */
    fun isRunning(): Boolean = available && runCatching { nativeRunning() }.getOrDefault(false)

    /**
     * One IMU pair. [timestampNs] is `elapsedRealtimeNanos` of the reading, [acceleration] is the raw
     * accelerometer (m/s², gravity in it — the estimator works out its own bias) and [gyroscope] the
     * gyroscope in rad/s.
     */
    fun pushImu(timestampNs: Long, acceleration: FloatArray, gyroscope: FloatArray) {
        if (!available) return
        accel6[0] = acceleration[0].toDouble(); accel6[1] = acceleration[1].toDouble(); accel6[2] = acceleration[2].toDouble()
        gyro6[0] = gyroscope[0].toDouble(); gyro6[1] = gyroscope[1].toDouble(); gyro6[2] = gyroscope[2].toDouble()
        runCatching { nativePushImu(timestampNs, accel6, gyro6) }
    }

    /**
     * One raw grayscale frame: the camera's Y plane, unrotated and undistorted, with the sensor's own
     * row stride. VINS-Mono undistorts the feature points itself, through the model in the config file.
     */
    fun pushFrame(gray: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, timestampNs: Long) {
        if (!available) return
        runCatching { nativePushFrame(gray, width, height, rowStride, timestampNs) }
    }

    /** Back to the beginning: the current spot becomes the origin of the world again. */
    fun reset() {
        if (available) runCatching { nativeReset() }
    }

    /** The newest pose into [into]; false when nothing has been solved yet. */
    fun readPose(into: Pose): Boolean {
        if (!available) return false
        val fresh = runCatching { nativePollPose(raw) }.getOrDefault(false)
        into.x = raw[0]; into.y = raw[1]; into.z = raw[2]
        into.qx = raw[3]; into.qy = raw[4]; into.qz = raw[5]; into.qw = raw[6]
        into.vx = raw[7]; into.vy = raw[8]; into.vz = raw[9]
        into.propagatedX = raw[10]; into.propagatedY = raw[11]; into.propagatedZ = raw[12]
        into.propagatedQx = raw[13]; into.propagatedQy = raw[14]; into.propagatedQz = raw[15]; into.propagatedQw = raw[16]
        into.stampSeconds = raw[17]
        into.tracked = raw[18] >= 1.0
        into.solving = raw[18] >= 2.0
        into.featuresEnough = raw[18] >= 4.0
        into.ageSeconds = raw[19]
        into.timeOffsetSeconds = raw[20]
        return fresh
    }

    private val accel6 = DoubleArray(3)
    private val gyro6 = DoubleArray(3)

    private const val TAG = "PhoneXR-VINS"

    private external fun nativeStart(configPath: String): String
    private external fun nativeStop()
    private external fun nativeRunning(): Boolean
    private external fun nativePushImu(timestampNs: Long, acceleration: DoubleArray, gyroscope: DoubleArray)
    private external fun nativePushFrame(
        gray: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        timestampNs: Long,
    )
    private external fun nativePollPose(out: DoubleArray): Boolean
    private external fun nativeReset()
}
