package com.samrat.cardboardhands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VinsTrackingGateTest {
    private fun frame(
        stamp: Double,
        features: Int = 40,
        speed: Double = 0.2,
        x: Double = 0.0,
        y: Double = 0.0,
        z: Double = 0.0,
        tracked: Boolean = true,
        age: Double = 0.02,
    ): Boolean = gate.update(
        tracked = tracked,
        features = features,
        x = x,
        y = y,
        z = z,
        vx = speed,
        vy = 0.0,
        vz = 0.0,
        stampSeconds = stamp,
        ageSeconds = age,
    )

    private val gate = VinsTrackingGate()

    @Test
    fun startupWaitsForThreeDistinctGoodVisualFrames() {
        assertFalse(frame(1.0))
        assertFalse("rendering the same pose again is not another solved image", frame(1.0))
        assertFalse(frame(1.1))
        assertTrue(frame(1.2))
    }

    @Test
    fun aBriefFeatureDipDoesNotFlickerButSustainedWeakVisionHoldsPosition() {
        assertFalse(frame(1.0))
        assertFalse(frame(1.1))
        assertTrue(frame(1.2))
        // Short weak-vision dips (glanced at a blank wall, motion blur from a head snap) should
        // not drop 6DoF — that is the whole point of the hysteresis. Five bad frames is the bar
        // (≈ 330 ms at 15 Hz), so frames 1.3-1.6 must still report ready.
        assertTrue(frame(1.3, features = 8))
        assertTrue(frame(1.4, features = 8))
        assertTrue(frame(1.5, features = 8))
        assertTrue(frame(1.6, features = 8))
        assertTrue(frame(1.7, features = 8))
        // The fifth bad frame in a row is where we finally admit tracking is lost.
        assertFalse(frame(1.8, features = 8))
        // Recovery still needs three consecutive good frames (warm-up), not one.
        assertFalse(frame(1.9, features = 40))
        assertFalse(frame(2.0, features = 40))
        assertTrue(frame(2.1, features = 40))
    }

    @Test
    fun staleOrPhysicallyImpossiblePosesAreRejected() {
        assertFalse(frame(1.0))
        assertFalse(frame(1.1))
        assertTrue(frame(1.2))
        // A physically implausible speed is a single-frame outlier, rejected on the frame it arrives.
        assertFalse(frame(1.3, speed = 8.0))
        // Speed outlier resets the good-frame counter but does not trigger loseImmediately(), so
        // three more good frames recover the gate just like at startup.
        assertFalse(frame(1.4))
        assertFalse(frame(1.5))
        assertTrue(frame(1.6))
        // A solved frame older than the max age (0.7 s) is treated as lost: IMU propagation has
        // had enough time to drift in translation.
        assertFalse(frame(1.7, age = 0.8))
    }

    @Test
    fun aSuddenPositionJumpIsRejectedEvenWhenReportedVelocityLooksSmall() {
        assertFalse(frame(1.0))
        assertFalse(frame(1.1))
        assertTrue(frame(1.2))
        assertFalse(frame(1.3, x = 2.0))
        assertFalse(frame(1.4, x = 2.02))
        assertFalse(frame(1.5, x = 2.04))
        assertTrue(frame(1.6, x = 2.06))
    }

    @Test
    fun anEstimatorRestartStartsANewWarmupWindow() {
        assertFalse(frame(5.0))
        assertFalse(frame(5.1))
        assertTrue(frame(5.2))
        assertFalse(frame(4.0)) // new run's timestamps can be lower than the last solved frame
        assertFalse(frame(4.1))
        assertTrue(frame(4.2))
    }
}
