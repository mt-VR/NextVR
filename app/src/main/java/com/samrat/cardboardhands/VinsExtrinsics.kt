package com.samrat.cardboardhands

/**
 * The rotation between the phone's IMU (the Android device frame) and the frame of the raw camera
 * image VINS-Mono is fed — the `extrinsicRotation` of the estimator's config. VINS-Mono multiplies
 * it as RIC, the matrix that turns a vector in IMU coordinates into a vector in camera coordinates,
 * so its rows are the camera's own axes written in IMU coordinates: image right, image down, and
 * into the scene (the pinhole convention: x right, y down, z forward).
 *
 * The derivation, for a rear camera (the only one the tracker reads):
 *
 *  - the Android device frame is x right, y up, z out of the screen toward the user. A rear camera
 *    looks through the back of the phone, so its optical axis is always z = -Z, however the sensor
 *    is mounted;
 *  - Camera2 defines SENSOR_ORIENTATION as the *clockwise* angle that turns the raw buffer upright
 *    on the screen in the device's natural orientation ("Clockwise angle through which the output
 *    image needs to be rotated..."). Rotating an image 90 degrees clockwise carries its top edge to
 *    the right edge;
 *  - the upright rear-camera preview is un-mirrored, like a window: the screen's right shows what
 *    is at the device's +x, the screen's top what is at the device's +y.
 *
 * Reading the raw buffer's axes off that rotation:
 *
 *    orientation  image right  image down   into the scene
 *       0 deg        +X           -Y            -Z
 *      90 deg        -Y           -X            -Z
 *     180 deg        -X           +Y            -Z
 *     270 deg        +Y           +X            -Z
 *
 * Each of the four is a proper rotation (the unit test checks the determinant), and each keeps the
 * camera's forward axis through the back of the phone. This matrix is only the *initial* guess —
 * the estimator refines it online (`estimate_extrinsic: 1`) — but a guess that is half a turn off
 * about the optical axis is one the visual-inertial alignment cannot walk back from, so the
 * derivation above is pinned down by VinsExtrinsicsTest rather than trusted.
 */
object VinsExtrinsics {

    /** The camera-from-IMU rotation for [sensorOrientation] (0, 90, 180 or 270), row-major. */
    fun cameraFromImu(sensorOrientation: Int): DoubleArray =
        when (
            (
                (Math.round(sensorOrientation / 90.0).toInt() * 90) % 360 + 360
                ) % 360
        ) {
            0 -> doubleArrayOf(
                1.0, 0.0, 0.0,   // image right = device +x
                0.0, -1.0, 0.0,  // image down  = device -y
                0.0, 0.0, -1.0,  // into the scene = device -z, out of the back
            )

            90 -> doubleArrayOf(
                0.0, -1.0, 0.0,  // image right = device -y (the device's +x lies at the buffer's top)
                -1.0, 0.0, 0.0,  // image down  = device -x (the device's +y lies at the buffer's left)
                0.0, 0.0, -1.0,
            )

            180 -> doubleArrayOf(
                -1.0, 0.0, 0.0,  // image right = device -x
                0.0, 1.0, 0.0,   // image down  = device +y
                0.0, 0.0, -1.0,
            )

            else -> doubleArrayOf(  // 270 (and everything else, snapped to the nearest quarter-turn)
                0.0, 1.0, 0.0,   // image right = device +y
                1.0, 0.0, 0.0,   // image down  = device +x
                0.0, 0.0, -1.0,
            )
        }
}
