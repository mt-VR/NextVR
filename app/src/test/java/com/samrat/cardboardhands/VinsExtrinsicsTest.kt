package com.samrat.cardboardhands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The camera-from-IMU rotation VINS-Mono starts from. The matrix is the difference between a room
 * tracked in metres and a room tracked in nonsense, so its derivation is checked here rather than
 * trusted: the axes must read off the sensor's mount exactly, and every orientation must be a
 * proper rotation.
 */
class VinsExtrinsicsTest {

    private fun matrixOf(orientation: Int): Array<DoubleArray> =
        VinsExtrinsics.cameraFromImu(orientation).toList().chunked(3).map { it.toDoubleArray() }.toTypedArray()

    /** VINS-Mono's RIC maps IMU vectors to camera vectors: image right, image down, into the scene. */
    private fun apply(matrix: Array<DoubleArray>, imuX: Double, imuY: Double, imuZ: Double): DoubleArray =
        doubleArrayOf(
            matrix[0][0] * imuX + matrix[0][1] * imuY + matrix[0][2] * imuZ,
            matrix[1][0] * imuX + matrix[1][1] * imuY + matrix[1][2] * imuZ,
            matrix[2][0] * imuX + matrix[2][1] * imuY + matrix[2][2] * imuZ,
        )

    @Test
    fun theOpticalAxisAlwaysLeavesThroughTheBackOfThePhone() {
        // The device's +z points out of the screen, at the user; the rear camera looks the other way.
        for (orientation in intArrayOf(0, 90, 180, 270)) {
            val intoScene = apply(matrixOf(orientation), 0.0, 0.0, 1.0)
            assertTrue(
                "orientation $orientation maps the device z to ${intoScene.toList()}",
                intoScene[0] == 0.0 && intoScene[1] == 0.0 && intoScene[2] == -1.0,
            )
        }
    }

    /**
     * The common mount, 90 degrees: Camera2 turns the raw buffer 90 degrees clockwise upright, and
     * a clockwise turn carries the top edge to the right. So the device's +x (screen right, east)
     * was at the buffer's top, and the device's +y (screen top, the sky) at the buffer's left.
     */
    @Test
    fun theNinetyDegreeMountReadsOffTheClockwiseRotation() {
        val matrix = matrixOf(90)
        // Device right (+x) is image up (-y in the camera's own axes).
        val right = apply(matrix, 1.0, 0.0, 0.0)
        assertEquals(0.0, right[0], 1e-12)
        assertEquals(-1.0, right[1], 1e-12)
        // Device up (+y) is image left (-x).
        val up = apply(matrix, 0.0, 1.0, 0.0)
        assertEquals(-1.0, up[0], 1e-12)
        assertEquals(0.0, up[1], 1e-12)
    }

    /** Half a turn the other way: the buffer's right is the device's +y, its down the device's +x. */
    @Test
    fun theTwoHundredAndSeventyDegreeMountIsTheMirrorOfTheNinety() {
        val matrix = matrixOf(270)
        val right = apply(matrix, 0.0, 1.0, 0.0)  // device up is image right
        assertEquals(1.0, right[0], 1e-12)
        val up = apply(matrix, 1.0, 0.0, 0.0)     // device right is image down
        assertEquals(1.0, up[1], 1e-12)
    }

    @Test
    fun theFlatMountsMatchTheDeviceAxes() {
        // 0 degrees: the buffer already upright — right is +x, down is -y.
        val flat = matrixOf(0)
        assertEquals(1.0, apply(flat, 1.0, 0.0, 0.0)[0], 1e-12)
        assertEquals(-1.0, apply(flat, 0.0, 1.0, 0.0)[1], 1e-12)
        // 180 degrees: both flipped, forward still through the back.
        val flipped = matrixOf(180)
        assertEquals(-1.0, apply(flipped, 1.0, 0.0, 0.0)[0], 1e-12)
        assertEquals(1.0, apply(flipped, 0.0, 1.0, 0.0)[1], 1e-12)
    }

    /** A rotation, not a reflection: the hand the estimator solves with must be the right one. */
    @Test
    fun everyOrientationIsAProperRotation() {
        for (orientation in intArrayOf(0, 90, 180, 270)) {
            val matrix = matrixOf(orientation)
            val det = matrix[0][0] * (matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1]) -
                matrix[0][1] * (matrix[1][0] * matrix[2][2] - matrix[1][2] * matrix[2][0]) +
                matrix[0][2] * (matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0])
            assertEquals("orientation $orientation", 1.0, det, 1e-12)
            // Rows orthonormal: the axes the camera model projects with.
            for (row in 0..2) {
                val length = matrix[row].sumOf { it * it }
                assertEquals("orientation $orientation row $row", 1.0, length, 1e-12)
            }
        }
    }

    /** Anything off the four multiples of 90 snaps to the nearest one instead of crashing. */
    @Test
    fun anUnusualOrientationStillAnswers() {
        assertTrue(
            "45 should snap to 90, got ${VinsExtrinsics.cameraFromImu(45).toList()}",
            VinsExtrinsics.cameraFromImu(45).contentEquals(VinsExtrinsics.cameraFromImu(90)),
        )
        assertTrue(
            "-90 should normalise to 270, got ${VinsExtrinsics.cameraFromImu(-90).toList()}",
            VinsExtrinsics.cameraFromImu(-90).contentEquals(VinsExtrinsics.cameraFromImu(270)),
        )
        assertTrue(
            "450 should normalise to 90, got ${VinsExtrinsics.cameraFromImu(450).toList()}",
            VinsExtrinsics.cameraFromImu(450).contentEquals(VinsExtrinsics.cameraFromImu(90)),
        )
        assertTrue(
            "360 should normalise to 0, got ${VinsExtrinsics.cameraFromImu(360).toList()}",
            VinsExtrinsics.cameraFromImu(360).contentEquals(VinsExtrinsics.cameraFromImu(0)),
        )
    }
}
