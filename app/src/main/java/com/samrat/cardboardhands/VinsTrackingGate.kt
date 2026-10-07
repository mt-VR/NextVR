package com.samrat.cardboardhands

import kotlin.math.sqrt

/**
 * Confidence gate for VINS-Mono's first and recovered poses. The sliding-window solver can report a
 * pose as soon as it becomes nonlinear even when the image has too few stable tracks to constrain
 * translation. Let a few good frames establish the map, and stop feeding visibly under-constrained or
 * stale poses into the VR world's position.
 *
 * The caller supplies a new frame stamp with each solved image. Repeated render frames for the same
 * pose do not advance the warm-up counter.
 */
internal class VinsTrackingGate(
    private val minFeatures: Int = DEFAULT_MIN_FEATURES,
    private val stableFramesRequired: Int = DEFAULT_STABLE_FRAMES,
    private val badFramesToLose: Int = DEFAULT_BAD_FRAMES_TO_LOSE,
    private val maxSpeedMetersPerSecond: Double = DEFAULT_MAX_SPEED,
    private val maxAgeSeconds: Double = DEFAULT_MAX_AGE_SECONDS,
) {
    private var lastStampSeconds = Double.NEGATIVE_INFINITY
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var hasPosition = false
    private var goodFrames = 0
    private var badFrames = 0
    private var ready = false

    /** True once the tracker has a recent, stable visual estimate. */
    @Synchronized
    fun update(
        tracked: Boolean,
        features: Int,
        x: Double,
        y: Double,
        z: Double,
        vx: Double,
        vy: Double,
        vz: Double,
        stampSeconds: Double,
        ageSeconds: Double,
    ): Boolean {
        if (
            !tracked || !stampSeconds.isFinite() || !ageSeconds.isFinite() ||
            ageSeconds < 0.0 || ageSeconds > maxAgeSeconds
        ) {
            loseImmediately()
            return false
        }

        // A native restart (or a reset to a new sensor clock) starts a fresh stability window.
        if (stampSeconds < lastStampSeconds) reset()
        if (stampSeconds <= lastStampSeconds + STAMP_EPSILON_SECONDS) return ready

        val deltaSeconds = stampSeconds - lastStampSeconds
        val speed = sqrt(vx * vx + vy * vy + vz * vz)
        val finitePose = x.isFinite() && y.isFinite() && z.isFinite() && speed.isFinite()
        val positionJump = finitePose && hasPosition && sqrt(
            (x - lastX) * (x - lastX) +
                (y - lastY) * (y - lastY) +
                (z - lastZ) * (z - lastZ),
        ) > maxSpeedMetersPerSecond * deltaSeconds + POSITION_STEP_SLACK_METERS
        lastStampSeconds = stampSeconds

        if (positionJump) {
            // The reported velocity is not always reliable during a map correction. Drop this offset,
            // use it as a temporary comparison baseline, and let the caller re-anchor after warm-up.
            rememberPosition(x, y, z)
            loseImmediately()
            return false
        }
        if (finitePose) rememberPosition(x, y, z)

        val good = finitePose && features >= minFeatures && speed <= maxSpeedMetersPerSecond
        if (good) {
            goodFrames++
            badFrames = 0
            if (goodFrames >= stableFramesRequired) ready = true
        } else {
            // A non-finite pose or an implausible velocity is a single-frame outlier: treat it
            // as a momentary glitch rather than a multi-frame loss, so the gate recovers on the
            // very next good frame instead of waiting out the hysteresis window. Sustained low
            // feature counts still need badFramesToLose frames before reporting loss.
            val outlier = !finitePose || speed > maxSpeedMetersPerSecond
            if (outlier) {
                goodFrames = 0
                ready = false
            } else {
                goodFrames = 0
                badFrames++
                if (badFrames >= badFramesToLose) ready = false
            }
        }
        return ready
    }

    private fun rememberPosition(x: Double, y: Double, z: Double) {
        lastX = x
        lastY = y
        lastZ = z
        hasPosition = true
    }

    @Synchronized
    fun reset() {
        lastStampSeconds = Double.NEGATIVE_INFINITY
        hasPosition = false
        goodFrames = 0
        badFrames = 0
        ready = false
    }

    private fun loseImmediately() {
        goodFrames = 0
        badFrames = 0
        ready = false
    }

    private companion object {
        // A head-worn camera is closer to stationary than a hand-held one, so the minimum feature
        // count for one frame to count as good is a little lower than upstream's 30 but the gate
        // tolerates more bad frames in a row before reporting loss — brief dips (a blank wall
        // glanced at for one frame, a fast pan that blurs KLT tracks) must not flicker 6DoF off.
        const val DEFAULT_MIN_FEATURES = 10
        const val DEFAULT_STABLE_FRAMES = 3
        // Five bad frames at 15 Hz ≈ 330 ms of weak vision before we admit tracking is gone, so a
        // short glance at a textureless wall or a quick head snap does not drop position; on a
        // real loss (lights out, camera covered) the ageSeconds check fires first anyway.
        const val DEFAULT_BAD_FRAMES_TO_LOSE = 5
        // A user walking fast in a small room peaks around 2 m/s; allow some head-bob overshoot
        // without treating it as a pose discontinuity.
        const val DEFAULT_MAX_SPEED = 4.0
        // 700 ms without a solved frame is old. The IMU-propagated pose is still drawn, but the
        // gate reports loss so the aligner can hold the last trusted position rather than follow
        // pure integration for seconds.
        const val DEFAULT_MAX_AGE_SECONDS = 0.7
        // A little slack on the position-jump check: the estimator can correct 10 cm in one step
        // when marginalising a bad frame, and that is a map update, not the user teleporting.
        const val POSITION_STEP_SLACK_METERS = 0.15
        const val STAMP_EPSILON_SECONDS = 1e-6
    }
}
