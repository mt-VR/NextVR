package com.samrat.cardboardhands

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn from VINS-Mono's world (z up) into the VR home's (y up, straight ahead along -z).
 *
 * This matrix used to be singular: one `-1f` sat in the wrong column of a column-major float[16],
 * and the second row came out all zero. That did not crash, and it did not look obviously wrong —
 * it mapped a metre forward to no movement at all, and a metre up to a metre sideways, so the room
 * only ever seemed to drift. These are the properties that were lost, checked here so the matrix
 * cannot quietly go back to being a non-rotation.
 */
class VinsWorldTest {

    /** Read [VinsWorld.REMAP] as rows: Android's index is row + 4*column. */
    private fun row(index: Int): DoubleArray =
        doubleArrayOf(
            VinsWorld.REMAP[index].toDouble(),
            VinsWorld.REMAP[index + 4].toDouble(),
            VinsWorld.REMAP[index + 8].toDouble(),
        )

    private fun at(r: Int, c: Int): Double = VinsWorld.REMAP[r + 4 * c].toDouble()

    private fun determinant3(): Double =
        at(0, 0) * (at(1, 1) * at(2, 2) - at(1, 2) * at(2, 1)) -
            at(0, 1) * (at(1, 0) * at(2, 2) - at(1, 2) * at(2, 0)) +
            at(0, 2) * (at(1, 0) * at(2, 1) - at(1, 1) * at(2, 0))

    /** A quarter turn: its determinant must be +1, and never 0. */
    @Test
    fun theRemapIsARotationAndNotASingularity() {
        assertEquals("the turn must not collapse any axis", 1.0, determinant3(), 1e-12)
    }

    @Test
    fun theRowsAreOrthonormalSoItIsAlsoAReflectionFreeTurn() {
        for (index in 0..2) {
            val r = row(index)
            val length = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
            assertEquals("row $index must be a unit vector", 1.0, length, 1e-12)
        }
        // Distinct axes must stay distinct rows, or an axis has been merged with another.
        assertEquals("row 0 . row 1", 0.0, dot(row(0), row(1)), 1e-12)
        assertEquals("row 0 . row 2", 0.0, dot(row(0), row(2)), 1e-12)
        assertEquals("row 1 . row 2", 0.0, dot(row(1), row(2)), 1e-12)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Right stays right: the only axis the broken matrix got right. */
    @Test
    fun sidewaysStaysSideways() {
        assertEquals(1.0, at(0, 0), 1e-12)
    }

    /**
     * The axis the broken matrix zeroed: VINS's up (z) has to arrive as the home's up (y). This is
     * what makes jumping up move the head up rather than sideways.
     */
    @Test
    fun upStaysUp() {
        assertEquals("vins.z must become home.y", 1.0, at(1, 2), 1e-12)
        assertEquals("nothing else may feed home.y", 0.0, at(1, 0), 1e-12)
        assertEquals("nothing else may feed home.y", 0.0, at(1, 1), 1e-12)
    }

    /**
     * The axis the broken matrix turned into sideways: walking forward must move the head along
     * the home's -z, which is what makes the world come toward you when you step forward.
     */
    @Test
    fun forwardBecomesTheHomesForward() {
        assertEquals("vins.y must become -home.z", -1.0, at(2, 1), 1e-12)
        assertEquals("nothing else may feed home.z", 0.0, at(2, 0), 1e-12)
        assertEquals("nothing else may feed home.z", 0.0, at(2, 2), 1e-12)
    }

    /**
     * The aligner reads its heading out of column 2 (`atan2(m[8], m[10])` = row 0 against row 2 of
     * that column). With the broken matrix both entries were constants, so `alignYaw` was frozen
     * and the aligner could never align VINS's heading to the head's. A real turn must let this
     * pair vary, which is what the three assertions below stand in for.
     */
    @Test
    fun theHeadingColumnIsNotFrozen() {
        val column2 = doubleArrayOf(at(0, 2), at(1, 2), at(2, 2))
        assertTrue(
            "column 2 must carry a real direction, was ${column2.toList()}",
            column2.any { it != 0.0 },
        )
        assertEquals("column 2 is the home's up axis", 1.0, at(1, 2), 1e-12)
        assertEquals("column 2 must not also carry the heading", 0.0, at(0, 2), 1e-12)
    }

    /**
     * The same three turns, through [VinsWorld.position], in metres.
     *
     * Compared with a tolerance rather than by list equality, because negating a zero yields -0.0f
     * and [Float.equals] — which list equality uses — tells -0.0f and 0.0f apart.
     */
    @Test
    fun positionPutsEachAxisWhereItBelongs() {
        val out = FloatArray(3)

        VinsWorld.position(1.0, 0.0, 0.0, out)
        assertArrayEquals("a metre to the right", floatArrayOf(1f, 0f, 0f), out, 1e-6f)

        VinsWorld.position(0.0, 1.0, 0.0, out)
        assertArrayEquals("a metre forward arrives along -z", floatArrayOf(0f, 0f, -1f), out, 1e-6f)

        VinsWorld.position(0.0, 0.0, 1.0, out)
        assertArrayEquals("a metre up arrives along +y", floatArrayOf(0f, 1f, 0f), out, 1e-6f)
    }

    /** Distances must survive the turn: it is a change of basis, not a squash. */
    @Test
    fun theTurnPreservesDistance() {
        val out = FloatArray(3)
        for (point in listOf(Triple(0.3, -1.7, 2.4), Triple(-5.0, 0.25, 0.0), Triple(0.0, 0.0, -3.3))) {
            val (x, y, z) = point
            VinsWorld.position(x, y, z, out)
            val before = Math.sqrt(x * x + y * y + z * z)
            val after = Math.sqrt((out[0] * out[0] + out[1] * out[1] + out[2] * out[2]).toDouble())
            assertEquals(before, after, 1e-5)
        }
    }
}