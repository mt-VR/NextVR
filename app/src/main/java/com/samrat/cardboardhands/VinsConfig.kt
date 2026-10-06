package com.samrat.cardboardhands

import kotlin.math.tan

/**
 * The YAML the VINS-Mono estimator reads: upstream's own config file, in upstream's own shape, filled
 * with what this phone reports about its lens and its sensors.
 *
 * It lives apart from [VinsTracker] so it can be written without a camera, an Activity or the native
 * core. That matters because most of what decides whether this mode ever finishes initialising is in
 * this file and nowhere else, and it is exactly the part that is easy to get wrong: the estimator is
 * tuned for a hand-held camera waved in front of the user, and a phone in a headset is a very
 * different thing to it. [VinsConfigTest] pins the numbers that are not free choices.
 */
object VinsConfig {

    /** What the phone's camera reports about its lens at the size the tracker is fed. */
    class Optics(
        val fx: Double, val fy: Double, val cx: Double, val cy: Double,
        val k1: Double, val k2: Double, val p1: Double, val p2: Double,
        /** The camera-from-IMU rotation, row-major, as `extrinsicRotation` wants it. */
        val extrinsicRotation: DoubleArray,
    )

    /**
     * Fallback pinhole calibration for a camera that publishes no usable intrinsics. With square
     * pixels, focal length is the same in x and y; scaling fy by the image aspect ratio distorts the
     * camera model (for a 640x480 frame it used to be 25% too short vertically).
     */
    fun fallbackOptics(
        width: Int,
        height: Int,
        horizontalFovDegrees: Double,
        extrinsicRotation: DoubleArray,
    ): Optics {
        require(width > 0 && height > 0) { "image dimensions must be positive" }
        require(horizontalFovDegrees in 1.0..179.0) { "horizontal FOV must be between 1 and 179 degrees" }
        val focalPixels = width / 2.0 / tan(Math.toRadians(horizontalFovDegrees / 2.0))
        return Optics(
            fx = focalPixels,
            fy = focalPixels,
            cx = width / 2.0,
            cy = height / 2.0,
            k1 = 0.0,
            k2 = 0.0,
            p1 = 0.0,
            p2 = 0.0,
            extrinsicRotation = extrinsicRotation,
        )
    }

    /**
     * Upstream's config file for the frame the tracker is fed, at the size the camera delivers.
     *
     * The keys and their meaning are VINS-Mono's (`vins/config/euroc/euroc_config.yaml`); only the
     * numbers are this phone's own. Where a value differs from upstream's EuRoC file the reason is
     * written next to it, because every one of those differences was a bug first.
     */
    fun text(width: Int, height: Int, optics: Optics, device: String = "this phone"): String = buildString {
        append("%YAML:1.0\n")
        append("# Written by NextVR for $device.\n")
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
        append("\n# The camera-to-IMU rotation, and the instruction to trust it.\n")
        // Estimating the extrinsic online is not affordable here: it turns para_Ex_Pose into seven
        // free parameters (Estimator::optimization only calls SetParameterBlockConstant when this
        // is 0) inside a Ceres solve that gets max_solver_time for all of it, on cores shared with
        // 90 Hz VR rendering and the hand tracker. Every solve then returns early on a biased pose
        // and the estimator never leaves INITIAL. Upstream's own EuRoC file ships 0, for the same
        // reason it trusts its own measured matrix.
        append("# estimate_extrinsic: 0 tells the estimator to take the matrix below as exact. It is\n")
        append("# derived from SENSOR_ORIENTATION rather than guessed (VinsExtrinsics, pinned by\n")
        append("# VinsExtrinsicsTest), so there is nothing left for the estimator to find.\n")
        append("estimate_extrinsic: 0\n")
        append("extrinsicRotation: !!opencv-matrix\n")
        append("   rows: 3\n")
        append("   cols: 3\n")
        append("   dt: d\n")
        append("   data: [ ${optics.extrinsicRotation[0]}, ${optics.extrinsicRotation[1]}, ${optics.extrinsicRotation[2]},\n")
        append("           ${optics.extrinsicRotation[3]}, ${optics.extrinsicRotation[4]}, ${optics.extrinsicRotation[5]},\n")
        append("           ${optics.extrinsicRotation[6]}, ${optics.extrinsicRotation[7]}, ${optics.extrinsicRotation[8]} ]\n")
        append("extrinsicTranslation: !!opencv-matrix\n")
        append("   rows: 3\n")
        append("   cols: 1\n")
        append("   dt: d\n")
        append("   data: [ 0.0, 0.0, 0.0 ]\n")
        append("\n# feature tracker\n")
        append("max_cnt: 150\n")
        append("min_dist: 30\n")
        // Upstream's reference runs the front end at 10 Hz. Every extra frame here is a KLT pass
        // competing with 90 Hz VR rendering and the hand tracker on the same cores, and the
        // estimator gains nothing from them: between solved frames the head rides the IMU
        // propagation, which is drift-free over a tenth of a second.
        append("freq: 10\n")
        // Upstream's RANSAC threshold, in pixels. At the 2.0 that used to be written here the
        // front end kept enough outliers to spoil the five-point solve that initialisation starts
        // from, which showed up as the same "Not enough features or parallax" retry over and over.
        append("F_threshold: 1.0\n")
        append("show_track: 0\n")
        append("equalize: 1\n")
        append("fisheye: 0\n")
        append("\n# optimisation: upstream's EuRoC reference values. An 11-frame window cannot\n")
        append("# converge in four iterations, and a solve cut short returns a biased pose every\n")
        append("# time - which reads as drift, and keeps the estimator in INITIAL forever.\n")
        append("max_solver_time: 0.04\n")
        append("max_num_iterations: 8\n")
        // Keyframe selection, in pixels of median feature flow per frame (VINS-Mono divides this by
        // its 460 px reference focal length). Upstream's 10 is a hand-held camera waved in front of
        // the user. A camera on a headset sees far less translation, and below this threshold
        // solveOdometry throws good frames away on purpose ("bad solver, drop 1 to 2 frame"), which
        // on a head that is mostly looking around is most of them. 4 px is ordinary head movement
        // across a room, and still rejects a frame that only repeats the one before it.
        append("keyframe_parallax: 4.0\n")
        append("\n# IMU noise. These four are upstream's EuRoC figures, and they are not cosmetic:\n")
        append("# they are how much the estimator is allowed to believe the sensors, and acc_w and\n")
        append("# gyr_w are the random walk of the accelerometer and gyroscope biases it estimates.\n")
        append("#\n")
        append("# failureDetection() throws the whole sliding window away and starts initialising\n")
        append("# again when the accelerometer bias passes 2.5 or the gyroscope bias passes 1.0. At\n")
        append("# the acc_w of 0.001 and gyr_w of 1.0e-4 that used to be written here - 25x and 50x\n")
        append("# upstream - the accelerometer bias random-walks to that limit within seconds, so a\n")
        append("# session fell back to INITIAL over and over and never settled. And that bias is\n")
        // The same subtraction is in predict() in the runtime and in processIMU() upstream, and it
        // is why the two complaints were one mis-tuning: a bias that has grown to soak up real
        // acceleration takes that acceleration out of the pose too, so the head drifted out more
        // slowly than it actually moved, which reads as a weakened movement rather than as noise.
        append("# subtracted from the accelerometer before it is integrated, so it also eats the\n")
        append("# real acceleration the head moved with.\n")
        append("acc_n: 0.08\n")
        append("acc_w: 0.00004\n")
        append("gyr_n: 0.004\n")
        append("gyr_w: 0.000002\n")
        append("g_norm: 9.81007\n")
        append("\n# the pose graph is not built into this APK, so no loop closure and no map reuse\n")
        append("loop_closure: 0\n")
        // Both ImageInfo.timestamp and SensorEvent.timestamp are nanoseconds on
        // SystemClock.elapsedRealtimeNanos(), so there is no camera-to-IMU offset here to find.
        // estimate_td: 1 would add para_Td as a ninth free parameter to the same 40 ms solve and
        // swap every ProjectionFactor for a ProjectionTdFactor, so it can only cost convergence.
        append("\n# The camera's clock and the IMU's ARE one clock on Android, so there is no offset\n")
        append("# here for the estimator to find. Upstream's EuRoC file ships 0 for the same reason.\n")
        append("estimate_td: 0\n")
        append("td: 0.0\n")
        append("\nrolling_shutter: 0\n")
        append("rolling_shutter_tr: 0\n")
    }
}