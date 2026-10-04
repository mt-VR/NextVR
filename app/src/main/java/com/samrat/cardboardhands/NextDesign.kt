package com.samrat.cardboardhands

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The Next VR design language.
 *
 * PhoneXR's screens, its VR home and its window chrome all read their colours, radii and motion
 * from here, so the phone app and the headset stay one design. The numbers come from the reference
 * UI: dark glass panels with a hairline stroke, white 7% tiles, a periwinkle accent for whatever is
 * selected or on, one teal filled action per screen, gradient app tiles and a world-locked dock.
 *
 * Two of the reference's choices were adapted deliberately for PhoneXR:
 *  - the glass is a little denser than on the web (0.90 instead of 0.72): in the headset a window
 *    usually hangs over the live camera picture, and thin glass there costs legibility;
 *  - the warm black backdrop is only used where PhoneXR has a backdrop of its own (the phone app,
 *    the setup), never behind passthrough or a chosen world.
 *
 * Everything is ARGB here so the Compose surfaces and the canvas-drawn VR chrome (window pill,
 * keyboard, setup) can share the same numbers.
 */
object NextDesign {

    // ---------------------------------------------------------------- colours

    /** The reference's warm black, and the darker end it fades to. */
    val backdrop: Int = 0xFF120904.toInt()
    val backdropDeep: Int = 0xFF0B0705.toInt()
    val backdropTop: Int = 0xFF1E1409.toInt()

    /** Glass: the two ends of the panel fall, the hairline that outlines it and the shadow under it. */
    val glassTop: Int = 0xFF1C1F26.toInt()
    val glassBottom: Int = 0xFF16181E.toInt()
    val glassSolid: Int = 0xFF20242B.toInt()

    /**
     * The same glass at 90% — dense enough that a window over the live camera stays readable, thin
     * enough that the backdrop still breathes through it, as it does in the reference.
     */
    val glassTopVeil: Int = 0xE61C1F26.toInt()
    val glassBottomVeil: Int = 0xE616181E.toInt()
    val glassSolidVeil: Int = 0xEB20242B.toInt()
    val stroke: Int = 0x1AFFFFFF
    val strokeStrong: Int = 0x33FFFFFF
    val shadow: Int = 0x73000000

    /** Tiles: what sits on the glass at rest and under the pointer (or the gaze). */
    val tile: Int = 0x12FFFFFF
    val tileHover: Int = 0x21FFFFFF
    val edge: Int = 0x0DFFFFFF

    /** Text and icons. */
    val ink: Int = 0xFFF4F6FA.toInt()
    val inkSoft: Int = 0xB8F4F6FA.toInt()
    val inkFaint: Int = 0x80F4F6FA.toInt()

    /** The accent: selected, on, or under the pointer. The reference's `--mr-accent`. */
    val accent: Int = 0xFF9BBAFF.toInt()
    val accentSoft: Int = 0x339BBAFF
    val accentLine: Int = 0x809BBAFF.toInt()

    /** The one filled action of a screen, and the teal the reference uses for it. */
    val primary: Int = 0xFF3E7E90.toInt()
    val primaryPressed: Int = 0xFF356C7C.toInt()

    val good: Int = 0xFF8DE6C8.toInt()
    val warn: Int = 0xFFF2BD70.toInt()
    val danger: Int = 0xFFFF8C8C.toInt()
    val dangerSurface: Int = 0x2EFD5B64
    val online: Int = 0xFF8DE6C8.toInt()
    val alert: Int = 0xFFE5484D.toInt()

    /** The dock's world-locked capsule: a slate-blue fall with a lit top edge. */
    val dockTop: Int = 0xFF384155.toInt()
    val dockBottom: Int = 0xFF1D2535.toInt()
    val dockStroke: Int = 0x33C0DCFF
    val dockHighlightFrom: Int = 0x00B9DCFF
    val dockHighlight: Int = 0xB0B9DCFF.toInt()
    val dockHighlightTo: Int = 0x80D5C2FF
    val divider: Int = 0x26CEDAFF

    /** The profile circle of the dock. */
    val profileFrom: Int = 0xFFBAA7F7.toInt()
    val profileMid: Int = 0xFF6962BC.toInt()
    val profileDeep: Int = 0xFF404176.toInt()

    /** The battery in the dock's status strip. */
    val batteryInk: Int = 0xFFBED4E4.toInt()

    /** The floating keyboard: the reference's blue-black panel and its translucent keys. */
    val keyboardPanel: Int = 0xFF232B3D.toInt()
    val keyboardPanelStroke: Int = 0x35C2D7F4
    val key: Int = 0x1AC0D5F3
    val keyStroke: Int = 0x15D3E5FF
    val keyHover: Int = 0x34C5DCFC
    val keyPressed: Int = 0xFFA5C6F8.toInt()
    val keyPressedInk: Int = 0xFF1A3552.toInt()
    val keyEnter: Int = 0x60729BC7
    val keyInk: Int = 0xFFD9E9FE.toInt()

    // ---------------------------------------------------------------- Compose

    val backdropColor = Color(backdrop)
    val backdropDeepColor = Color(backdropDeep)
    val inkColor = Color(ink)
    val inkSoftColor = Color(inkSoft)
    val inkFaintColor = Color(inkFaint)
    val accentColor = Color(accent)
    val accentSoftColor = Color(accentSoft)
    val accentLineColor = Color(accentLine)
    val primaryColor = Color(primary)
    val tileColor = Color(tile)
    val tileHoverColor = Color(tileHover)
    val edgeColor = Color(edge)
    val strokeColor = Color(stroke)
    val strokeStrongColor = Color(strokeStrong)
    val glassTopColor = Color(glassTop)
    val glassBottomColor = Color(glassBottom)
    val glassSolidColor = Color(glassSolid)
    val glassTopVeilColor = Color(glassTopVeil)
    val glassBottomVeilColor = Color(glassBottomVeil)
    val glassSolidVeilColor = Color(glassSolidVeil)
    val dangerColor = Color(danger)
    val warnColor = Color(warn)
    val dangerSurfaceColor = Color(dangerSurface)
    val goodColor = Color(good)
    val dividerColor = Color(divider)
    val dockStrokeColor = Color(dockStroke)
    val keyboardPanelColor = Color(keyboardPanel)
    val keyboardPanelStrokeColor = Color(keyboardPanelStroke)
    val keyColor = Color(key)

    /** The glass fall: what a window or a card is filled with. */
    val glassBrush: Brush
        get() = Brush.verticalGradient(listOf(glassTopColor, glassBottomColor))

    /** The 90% glass fall, for the surfaces the backdrop should still show through. */
    val glassVeilBrush: Brush
        get() = Brush.verticalGradient(listOf(glassTopVeilColor, glassBottomVeilColor))

    /** The page of the phone app: the reference's warm black, lit at the top. */
    val pageBrush: Brush
        get() = Brush.verticalGradient(listOf(Color(backdropTop), backdropColor, backdropDeepColor))

    /** The lit top edge of the dock, exactly as the reference draws it. */
    val dockHighlightBrush: Brush
        get() = Brush.horizontalGradient(
            listOf(
                Color(dockHighlightFrom), Color(dockHighlight), Color(dockHighlightTo), Color(dockHighlightFrom)
            )
        )

    val dockBrush: Brush
        get() = Brush.verticalGradient(listOf(Color(dockTop), Color(dockBottom)))

    val profileBrush: Brush
        get() = Brush.linearGradient(listOf(Color(profileFrom), Color(profileMid), Color(profileDeep)))

    // ---------------------------------------------------------------- shapes

    /** Radii in dp for the phone screens; the VR panels use the same proportions of their size. */
    object Radius {
        /** A window or a big panel: the reference's 28. */
        const val window = 28f

        /** A list card, a quick tile. */
        const val card = 22f

        /** An app icon or a small tile: the reference's `.app-icon`. */
        const val icon = 18f

        /** A chip, a button, a field. */
        const val control = 14f

        /** A window footer button, a stepper button. */
        const val button = 9f

        /** A capsule (pills, switches, the dock). */
        const val capsule = 999f
    }

    /** The reference's rhythm: 4 / 8 / 12 / 16 / 20 / 24. */
    object Space {
        const val hair = 4f
        const val tight = 8f
        const val snug = 12f
        const val normal = 16f
        const val loose = 20f
        const val section = 24f
    }

    // ---------------------------------------------------------------- app tiles

    /**
     * The reference's app tiles: a diagonal gradient from the app's own colour to its deep end,
     * always with a white glyph. PhoneXR's own apps take the reference's palette where it has one
     * (`store`, `people`, `browser`, `photos`, `settings`, `worlds`) and a neighbour of it where
     * PhoneXR has apps the reference does not.
     */
    val gradients: Map<String, Pair<Int, Int>> = mapOf(
        "own:store" to (0xFFF2BD70.toInt() to 0xFFB7772E.toInt()),
        "own:people" to (0xFFBA97E2.toInt() to 0xFF8055AF.toInt()),
        "own:youtube" to (0xFFFF5268.toInt() to 0xFFCF1738.toInt()),
        "own:browser" to (0xFF64DADD.toInt() to 0xFF1A929F.toInt()),
        "own:photos" to (0xFF8FC7AB.toInt() to 0xFF398169.toInt()),
        "own:settings" to (0xFF8CE4C4.toInt() to 0xFF36A187.toInt()),
        "own:worlds" to (0xFFB1A4DF.toInt() to 0xFF7869A6.toInt()),
        "own:games" to (0xFFFF9B87.toInt() to 0xFFC2503A.toInt()),
        "own:calls" to (0xFF86E0B8.toInt() to 0xFF2F8A63.toInt()),
        "own:mirror" to (0xFFC79BE8.toInt() to 0xFF6E3FA8.toInt()),
        "own:elix" to (0xFFA0B8FF.toInt() to 0xFF5A6FD0.toInt()),
        "own:android" to (0xFFA9CF8C.toInt() to 0xFF4A8C3F.toInt()),
        "own:avatar" to (0xFFE4A6D2.toInt() to 0xFF9B4C87.toInt()),
        "own:instagram" to (0xFFFCB03F.toInt() to 0xFFC13584.toInt()),
        "own:discord" to (0xFF7289FF.toInt() to 0xFF4752C4.toInt()),
        "desktop" to (0xFF7FD4E8.toInt() to 0xFF2C7C93.toInt()),
    )

    /** A gradient for anything else, from a hue of its own — still the reference's colour → deep. */
    fun gradientFor(key: String): Pair<Int, Int> = gradients[key] ?: run {
        val hue = ((key.hashCode() and 0x7fffffff) % 360).toFloat()
        android.graphics.Color.HSVToColor(floatArrayOf(hue, .42f, .88f)) to
            android.graphics.Color.HSVToColor(floatArrayOf(hue, .58f, .50f))
    }

    // ---------------------------------------------------------------- motion

    /**
     * The reference's timing: windows arrive with a short fade, the dock rises once when the home
     * appears, and a pointer moves its target in under a fifth of a second.
     */
    object Motion {
        const val windowMs = 220
        const val dockMs = 550
        const val hoverMs = 180
    }
}
