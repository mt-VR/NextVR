package com.samrat.cardboardhands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGesturesTest {
    @Test
    fun pinchIsSlightlyMoreForgivingWithoutTriggeringOnAnOpenHand() {
        val latch = HandGestures.PinchLatch()

        assertFalse(latch.update(shape(gap = .40f)))
        assertFalse(latch.pinching)
        assertTrue(latch.update(shape(gap = .31f))) // within the small increase from the old .30 threshold
        assertTrue(latch.update(shape(gap = .49f))) // hysteresis keeps the click latched until clearly open
        assertFalse(latch.update(shape(gap = .50f)))
    }

    @Test
    fun fistStillCancelsAPinch() {
        val latch = HandGestures.PinchLatch()

        assertTrue(latch.update(shape(gap = .20f)))
        assertFalse(latch.update(shape(gap = .10f, fist = true)))
        assertFalse(latch.pinching)
    }

    private fun shape(gap: Float, fist: Boolean = false) = HandGestures.Shape(
        pinch = !fist && gap < HandGestures.PINCH_CLOSE_GAP,
        pinchStrength = 0f,
        fist = fist,
        palmToFace = false,
        pinchX = 0f,
        pinchY = 0f,
        palmWidth = 1f,
        pinchGap = gap,
    )
}
