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
        // More tracked features = stronger multi-view geometry and less drift per frame. The front
        // end is KLT-based and already throttled to [freq] Hz, so raising max_cnt from 150 to 220
        // gives the solver more constraints without a proportional CPU cost, and 25 px minimum
        // spacing keeps features spread across the frame (clustered features give a weak depth
        // solution that reads as drift when the head translates).
        append("max_cnt: 220\n")
        append("min_dist: 25\n")
        // 15 Hz visual fixes instead of 10. At 10 Hz the head can move 6-7 cm between solves during
        // a quick turn, and the IMU propagation — which is unobservable for translation scale on
        // its own — walks off until the next visual fix lands. 15 Hz (66 ms) keeps that gap small
        // enough that the bias estimate cannot accumulate much error in between. It is still well
        // within the budget every phone that ran 10 Hz could afford.
        append("freq: 15\n")
        // Upstream's RANSAC threshold, in pixels. At the 2.0 that used to be written here the
        // front end kept enough outliers to spoil the five-point solve that initialisation starts
        // from, which showed up as the same "Not enough features or parallax" retry over and over.
        append("F_threshold: 1.0\n")
        append("show_track: 0\n")
        append("equalize: 1\n")
        append("fisheye: 0\n")
        append("\n# optimisation: give the solver enough of the frame budget to actually converge.\n")
        append("# A half-converged pose is biased, and that biased pose is marginalised into the\n")
        append("# prior on the next frame — the accumulated bias is slow drift that later frames\n")
        append("# cannot undo. 60 ms and 10 iterations still fit between two 60 Hz frames and are\n")
        append("# well under one 90 Hz frame on the cores the render loop leaves the estimator.\n")
        append("max_solver_time: 0.06\n")
        append("max_num_iterations: 10\n")
        // Keyframe selection, in pixels of median feature flow per frame (VINS-Mono divides this by
        // its 460 px reference focal length). Upstream's 10 is a hand-held camera waved in front of
        // the user. A camera on a headset sees far less translation, and below this threshold
        // solveOdometry throws good frames away on purpose ("bad solver, drop 1 to 2 frame"), which
        // on a head that is mostly looking around is most of them. 3 px at 15 Hz is ordinary head
        // movement across a room — it still rejects a frame that only repeats the one before it,
        // but keys in often enough that the IMU bias does not have time to walk off between fixes.
        append("keyframe_parallax: 3.0\n")
        append("\n# IMU noise. These numbers describe a MEMS IMU like the ones Android phones carry. They\n")
        append("# tell the estimator how much to trust the sensors: smaller n = trust the reading,\n")
        append("# smaller w = hold the estimated bias still between solves. Tightening acc_w / gyr_w\n")
        append("# is what actually stops slow integration drift, because a bias that cannot drift\n")
        append("# between visual fixes cannot soak up real acceleration when it integrates - which\n")
        append("# was the cause of the head drifting more slowly than it actually walked.\n")
        append("#\n")
        append("# failureDetection() throws the sliding window away and restarts initialisation when\n")
        append("# the accelerometer bias passes 2.5 or the gyroscope bias passes 1.0, so these must\n")
        append("# stay well below those limits.\n")
        append("acc_n: 0.08\n")
        append("acc_w: 0.00002\n")
        append("gyr_n: 0.004\n")
        append("gyr_w: 0.000001\n")
        append("g_norm: 9.81007\n")
        append("\n# the pose graph is not built into this APK, so no loop closure and no map reuse\n")
        append("loop_closure: 0\n")
        // Both ImageInfo.timestamp and SensorEvent.timestamp are nanoseconds on
        // SystemClock.elapsedRealtimeNanos(), so there is no camera-to-IMU offset here to find.
        // estimate_td: 1 would add para_Td as a ninth free parameter to the same short solve and
        // swap every ProjectionFactor for a ProjectionTdFactor, so it can only cost convergence.
        append("\n# The camera's clock and the IMU's ARE one clock on Android, so there is no offset\n")
        append("# here for the estimator to find. Upstream's EuRoC file ships 0 for the same reason.\n")
        append("estimate_td: 0\n")
        append("td: 0.0\n")
        append("\nrolling_shutter: 0\n")
        append("rolling_shutter_tr: 0\n")
    }
}
