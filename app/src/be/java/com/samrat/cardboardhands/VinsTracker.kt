package com.samrat.cardboardhands

import android.app.Activity

/**
 * BE has no camera at all, and a visual-inertial tracker without a camera has nothing to look at.
 * This stub is here so the modes the other editions offer stay describable in one place.
 */
class VinsTracker private constructor() {
    companion object {
        /** Never: this edition has no camera to track the room with. */
        val available get() = false

        /** Nothing to start, and the home only ever asks for a [SixDof] anyway. */
        @Suppress("UNUSED_PARAMETER")
        fun create(activity: Activity): SixDof? = null
    }
}
