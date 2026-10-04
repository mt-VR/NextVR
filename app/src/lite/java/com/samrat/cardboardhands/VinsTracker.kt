package com.samrat.cardboardhands

import android.app.Activity

/**
 * VINS-Mono is not in Lite: the estimator's front end is OpenCV work on every frame, and Lite is the
 * edition that leaves the heavy per-frame vision out (no ArUco markers, no depth network, no ARCore).
 * The name is here so the home and the settings screens can talk about the mode either way — see the
 * full and be editions of this file for the shape of it.
 */
class VinsTracker private constructor() {
    companion object {
        /** Never: nothing to start. */
        val available get() = false

        /** Nothing to start, and the home only ever asks for a [SixDof] anyway. */
        @Suppress("UNUSED_PARAMETER")
        fun create(activity: Activity): SixDof? = null
    }
}
