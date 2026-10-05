package com.samrat.cardboardhands

/**
 * The turn from VINS-Mono's world into the VR home's, as the one matrix both are reached through.
 *
 * VINS-Mono builds a world with gravity along its own z ("z up"), while the home is Android's: y up,
 * straight ahead along -z. Nothing but this turn is needed to make the two rooms coincide, because
 * gravity fixes the roll and the tilt — only the heading is still free, and [SixDofAligner] follows
 * that slowly so a relocalisation cannot spin the room.
 *
 * The three rows are therefore:
 *
 *     home.x =  vins.x      right stays right
 *     home.y =  vins.z      VINS's up axis is the home's up axis
 *     home.z = -vins.y      VINS's forward axis is the home's -z
 *
 * A quarter turn about x, and nothing else. Android's `float[16]` matrices are **column-major**
 * (index = row + 4*column), so each line of [REMAP] below is one *column*, read top to bottom. Write
 * it as rows instead and the matrix quietly stops being a rotation: the version this replaces had a
 * single `-1f` in the wrong column and an all-zero second row, which is singular — it mapped
 * "forward" to nothing at all and "up" to sideways, so walking around the room produced a sideways
 * wobble that read as drift. [VinsWorldTest] pins the properties below so that cannot come back.
 */
object VinsWorld {

    /** The rotation, column-major, ready to hand to `android.opengl.Matrix`. */
    val REMAP: FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,      // column 0 (home.x): vins.x
        0f, 0f, -1f, 0f,     // column 1 (home.z): -vins.y
        0f, 1f, 0f, 0f,      // column 2 (home.y):  vins.z
        0f, 0f, 0f, 1f,      // column 3: the homogeneous 1
    )

    /**
     * The estimator's position turned into the home's, into [out] (a FloatArray(3)).
     *
     * Kept separate from the matrix so the arithmetic is plain and checkable without an Android
     * runtime — this is the part that was wrong, and it is worth being able to test it directly.
     */
    fun position(vinsX: Double, vinsY: Double, vinsZ: Double, out: FloatArray) {
        out[0] = vinsX.toFloat()
        out[1] = vinsZ.toFloat()
        out[2] = (-vinsY).toFloat()
    }
}