package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shared part of every 6DoF backend: a tracker's own world turned into the head's. These are the
 * three promises the VR home leans on — start where you stand, follow the metres walked, and never
 * lurch when a tracker corrects itself.
 */
class SixDofAlignerTest {
    /** A turn about the vertical, in the head tracker's own convention (y up, straight ahead -z). */
    private fun yawed(yaw: Float, x: Float = 0f, y: Float = 0f, z: Float = 0f): FloatArray {
        val c = cos(yaw)
        val s = sin(yaw)
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, x, y, z, 1f)
    }

    private fun step(aligner: SixDofAligner, yaw: Float, x: Float, y: Float, z: Float, frame: Int): FloatArray {
        aligner.update(yawed(yaw, x, y, z), yawed(yaw), FRAME_NS * frame)
        return aligner.position.copyOf()
    }

    @Test
    fun theSpotTrackingStartsAtIsTheCentreOfTheRoom() {
        val aligner = SixDofAligner()
        val at = step(aligner, yaw = .7f, x = 12.5f, y = 1.6f, z = -3.2f, frame = 1)
        assertEquals(0f, at[0], 1e-4f)
        assertEquals(0f, at[1], 1e-4f)
        assertEquals(0f, at[2], 1e-4f)
        assertEquals(12.5f, aligner.origin!![0], 1e-4f)
    }

    /**
     * Half a metre forward, in a world whose heading the aligner has already agreed with the head's:
     * that is half a metre forward in the home too, and the filter is nearly there within a second.
     */
    @Test
    fun walkingForwardMovesTheHeadForward() {
        val aligner = SixDofAligner()
        step(aligner, yaw = 0f, x = 0f, y = 0f, z = 0f, frame = 1)
        var last = FloatArray(3)
        for (frame in 1..40) last = step(aligner, yaw = 0f, x = 0f, y = 0f, z = -.5f, frame = 1 + frame)
        assertTrue("the head moved the wrong way: ${last.toList()}", last[2] < -.25f)
        assertEquals(.5f, -last[2], .25f)
        assertTrue("sideways drift ${last.toList()}", kotlin.math.abs(last[0]) < .05f)
        assertTrue("vertical drift ${last.toList()}", kotlin.math.abs(last[1]) < .05f)
    }

    /**
     * A tracker that snaps to a corrected map is not the user walking: the room must stay where it was
     * put, so a jump of more than half a metre in one frame moves the origin with it.
     */
    @Test
    fun aSnapOfTheTrackerDoesNotThrowTheRoomAcrossTheHouse() {
        val aligner = SixDofAligner()
        step(aligner, yaw = 0f, x = 0f, y = 0f, z = 0f, frame = 1)
        var before = FloatArray(3)
        for (frame in 1..20) before = step(aligner, yaw = 0f, x = 0f, y = 0f, z = -.2f, frame = 1 + frame)
        val after = step(aligner, yaw = 0f, x = 4f, y = 1f, z = -6f, frame = 30)
        for (axis in 0..2) assertTrue(
            "the room lurched on axis $axis: ${before.toList()} -> ${after.toList()}",
            kotlin.math.abs(after[axis] - before[axis]) < .3f,
        )
    }

    /** Recentering — a tap on the view — puts "here" and "straight ahead" back where the head is. */
    @Test
    fun recenteringStartsTheRoomAgain() {
        val aligner = SixDofAligner()
        step(aligner, yaw = 0f, x = 1f, y = 0f, z = -1f, frame = 1)
        step(aligner, yaw = 0f, x = 1.4f, y = .1f, z = -1.2f, frame = 2)
        aligner.reset()
        assertEquals(0f, aligner.position[0], 0f)
        assertEquals(0f, aligner.position[2], 0f)
        assertTrue(aligner.origin == null)
        assertTrue(aligner.alignYaw.isNaN())
        val at = step(aligner, yaw = 1.2f, x = 7f, y = 1f, z = 3f, frame = 3)
        assertEquals(0f, at[0], 1e-4f)
        assertEquals(0f, at[2], 1e-4f)
    }

    private companion object {
        /** A frame every 25 ms: the filter's idea of how fast the head is moving. */
        const val FRAME_NS = 25_000_000L
    }
}
