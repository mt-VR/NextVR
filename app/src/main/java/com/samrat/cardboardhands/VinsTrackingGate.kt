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
            goodFrames = 0
            badFrames++
            // A physically impossible velocity is a single-frame outlier; low feature count gets a
            // little hysteresis so one noisy image does not flicker 6DoF off.
            if (!finitePose || speed > maxSpeedMetersPerSecond || badFrames >= badFramesToLose) ready = false
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
        const val DEFAULT_MIN_FEATURES = 12
        const val DEFAULT_STABLE_FRAMES = 3
        const val DEFAULT_BAD_FRAMES_TO_LOSE = 3
        const val DEFAULT_MAX_SPEED = 3.5
        const val DEFAULT_MAX_AGE_SECONDS = 0.5
        const val POSITION_STEP_SLACK_METERS = 0.1
        const val STAMP_EPSILON_SECONDS = 1e-6
    }
}
