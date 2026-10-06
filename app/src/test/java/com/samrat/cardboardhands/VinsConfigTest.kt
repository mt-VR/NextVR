package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The estimator config the VINS-Mono mode writes.
 *
 * Nothing here is arbitrary. Each of these values was a different value first, and each of the old
 * ones has a failure behind it that the user could feel: a session that kept dropping back to
 * "looking for the room", and a head that drifted out more slowly than it actually moved. The
 * reasoning lives beside each key in [VinsConfig]; this is what stops the next person (or a
 * merge from upstream) putting them back.
 */
class VinsConfigTest {

    private val optics = VinsConfig.Optics(
        fx = 461.6, fy = 460.3, cx = 320.0, cy = 240.0,
        k1 = -0.2917, k2 = 0.08228, p1 = 5.333e-05, p2 = -1.578e-04,
        extrinsicRotation = VinsExtrinsics.cameraFromImu(90),
    )

    private val yaml = VinsConfig.text(640, 480, optics, "a test phone")

    private fun value(key: String): Double {
        // The camera-model keys are nested under their section, so match on the trimmed line but
        // require the colon to be there: "k1" must not match "k1x", and "td" must not match
        // "estimate_td".
        val line = yaml.lines().firstOrNull { it.trimStart().startsWith("$key:") }
            ?: throw AssertionError("$key is not in the config at all:\n$yaml")
        return line.substringAfter(':').trim().toDouble()
    }

    /**
     * The four numbers that decide whether the session survives.
     *
     * `acc_w` and `gyr_w` are the random walk of the accelerometer and gyroscope biases the
     * estimator estimates, and `Estimator::failureDetection()` discards the entire sliding window
     * once `|Ba|` passes 2.5 or `|Bg|` passes 1.0. An order of magnitude of extra walk there is
     * not a tuning preference: it is the difference between a session that settles and one that
     * restarts initialising every few seconds.
     */
    @Test
    fun theImuBiasRandomWalkIsUpstreamsNotAHundredTimesItsOwn() {
        assertEquals(4.0e-5, value("acc_w"), 1e-12)
        assertEquals(2.0e-6, value("gyr_w"), 1e-12)
        // The measurement noises are more forgiving but were still 2.5x and 1.25x too large.
        assertEquals(0.08, value("acc_n"), 1e-12)
        assertEquals(0.004, value("gyr_n"), 1e-12)
    }

    /**
     * The biases are subtracted from the accelerometer before it is integrated, so an inflated `Ba`
     * does not only risk the failure above: it eats the real acceleration too, and the pose comes
     * out with less movement in it than the head made. That is the whole of "my movements are
     * weakened", so it is worth a test of its own.
     */
    @Test
    fun theEstimatedBiasCannotSwallowTheAcceleration() {
        // A random walk of step `w` over n steps has a standard deviation of sqrt(n)*w. At 200 Hz
        // a minute is 12_000 samples, and the previous acc_w of 0.001 put sigma at 0.11 m/s^2 —
        // within a minute of ordinary use, the accelerometer bias has grown to a tenth of gravity
        // and the estimator tears the window down at 2.5.
        val aMinuteAt200Hz = 12_000.0
        assertTrue(
            "acc_w walks Ba to ${Math.sqrt(aMinuteAt200Hz) * value("acc_w")}",
            Math.sqrt(aMinuteAt200Hz) * value("acc_w") < 0.01,
        )
        assertTrue(
            "gyr_w walks Bg to ${Math.sqrt(aMinuteAt200Hz) * value("gyr_w")}",
            Math.sqrt(aMinuteAt200Hz) * value("gyr_w") < 0.004,
        )
    }

    /**
     * The camera-to-IMU rotation is derived from `SENSOR_ORIENTATION`, so there is nothing left for
     * the estimator to find, and finding it costs seven free parameters inside a 40 ms Ceres solve
     * (`Estimator::optimization` only calls `SetParameterBlockConstant` when this is 0).
     */
    @Test
    fun theExtrinsicIsTrustedRatherThanEstimated() {
        assertEquals(0.0, value("estimate_extrinsic"), 0.0)
        // The whole derived matrix, in order, as readIntrinsicParameter() will read it back. The config
        // wraps it over three lines, so compare with the line breaks collapsed.
        val flat = yaml.replace(Regex("\\s+"), " ")
        val expected = VinsExtrinsics.cameraFromImu(90).joinToString(", ")
        assertTrue("the derived matrix is not in the config:\n$yaml", flat.contains(expected))
    }

    /**
     * Android's camera timestamps and its sensor timestamps are both nanoseconds on
     * `SystemClock.elapsedRealtimeNanos()`, so there is no offset to estimate. Estimating one adds
     * a ninth free parameter and swaps every `ProjectionFactor` for a `ProjectionTdFactor`.
     */
    @Test
    fun thereIsNoCameraToImuOffsetToEstimate() {
        assertEquals(0.0, value("estimate_td"), 0.0)
        assertEquals(0.0, value("td"), 0.0)
    }

    /**
     * RANSAC's threshold, in pixels. At the 2.0 that used to be written here the front end kept
     * enough outliers to spoil the five-point solve initialisation starts from.
     */
    @Test
    fun theFrontEndDoesNotAdmitOutliers() {
        assertEquals(1.0, value("F_threshold"), 1e-12)
    }

    /**
     * Keyframe selection is in pixels of median feature flow per frame. Upstream's 10 assumes a
     * hand-held camera waved in front of the user; below it `solveOdometry()` deliberately drops
     * frames from the window, which on a head that is mostly looking around is most frames.
     */
    @Test
    fun keyframesAreReachableByAHeadThatOnlyLooksAround() {
        assertEquals(4.0, value("keyframe_parallax"), 1e-12)
    }

    /**
     * A fallback camera with square pixels has the same focal length in both axes. Multiplying fy by
     * 480/640 made an otherwise ordinary room look vertically stretched to VINS and biased its pose.
     */
    @Test
    fun fallbackIntrinsicsUseSquarePixels() {
        val fallback = VinsConfig.fallbackOptics(640, 480, 65.0, VinsExtrinsics.cameraFromImu(90))
        assertEquals(fallback.fx, fallback.fy, 1e-9)
        assertEquals(640.0 / 2.0 / kotlin.math.tan(Math.toRadians(65.0 / 2.0)), fallback.fx, 1e-9)
        assertEquals(320.0, fallback.cx, 0.0)
        assertEquals(240.0, fallback.cy, 0.0)
    }

    /** The rest is upstream's EuRoC file, unchanged, and the frame size the camera actually gave. */
    @Test
    fun theSolverAndFrontEndKeepUpstreamsFigures() {
        assertEquals(150.0, value("max_cnt"), 1e-12)
        assertEquals(30.0, value("min_dist"), 1e-12)
        assertEquals(10.0, value("freq"), 1e-12)
        assertEquals(0.04, value("max_solver_time"), 1e-12)
        assertEquals(8.0, value("max_num_iterations"), 1e-12)
        assertEquals(9.81007, value("g_norm"), 1e-9)
        assertEquals(1.0, value("equalize"), 1e-12)
        assertEquals(640.0, value("image_width"), 1e-12)
        assertEquals(480.0, value("image_height"), 1e-12)
        assertEquals(optics.fx, value("fx"), 1e-9)
        assertEquals(optics.cy, value("cy"), 1e-9)
        assertEquals(optics.k1, value("k1"), 1e-12)
        assertTrue("the device is not named", yaml.contains("a test phone"))
        // The pose graph is not in this APK: no loop closure, and nothing should imply otherwise.
        assertEquals(0.0, value("loop_closure"), 0.0)
    }
}