package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Icons for the VR home. PhoneXR's own apps wear the Next VR gradient tiles ([NextDesign]); an
 * Android app brings its own themed icon where the system draws one, and keeps its real icon
 * otherwise. Light or dark is the choice made with a long touch on the home.
 */
object MaterialYouIcons {
    /** [tile] behind the glyph, [glyph] the glyph itself; both ARGB. */
    data class Palette(val tile: Int, val glyph: Int)

    fun palette(context: Context, dark: Boolean): Palette =
        // The Next VR default: a glyph on the glass the rest of the interface is made of.
        if (dark) Palette(NextDesign.glassSolid, NextDesign.ink)
        else Palette(Color.rgb(255, 255, 255), Color.rgb(39, 39, 39))

    /** Hues of PhoneXR's own apps, so each tile has its own colour. */
    private val HUES = mapOf(
        "own:browser" to 212f, "own:photos" to 338f, "own:settings" to 262f, "own:store" to 28f,
        "own:calls" to 138f, "desktop" to 190f, "own:games" to 2f,
        "own:avatar" to 292f, "own:elix" to 168f, "own:android" to 96f,
    )

    /**
     * A tonal pair in a hue of its own for [key] (an app id or package name), used where an app has
     * no gradient of its own: a deep container with a light glyph.
     */
    fun palette(key: String, dark: Boolean): Palette {
        val hue = HUES[key] ?: ((key.hashCode() and 0x7fffffff) % 360).toFloat()
        // The reference's tiles: a deep tone of the app's own colour with a light glyph on it.
        return if (dark) Palette(Color.HSVToColor(floatArrayOf(hue, .38f, .26f)), Color.HSVToColor(floatArrayOf(hue, .30f, 1f)))
        else Palette(Color.HSVToColor(floatArrayOf(hue, .20f, 1f)), Color.HSVToColor(floatArrayOf(hue, .85f, .48f)))
    }

    /**
     * Gradient tiles for PhoneXR's own apps: the reference's colour → deep diagonal with a white
     * glyph on it ([NextDesign.gradients] holds the table).
     */
    val GRADIENTS: Map<String, Pair<Int, Int>> = NextDesign.gradients

    /** PhoneXR is dark only. */
    @Suppress("UNUSED_PARAMETER")
    fun dark(context: Context) = true

    /** The themed icon of [packageName], a full square (the home rounds its corners), or null. */
    fun themed(context: Context, packageName: String, dark: Boolean = dark(context)): Drawable? {
        if (Build.VERSION.SDK_INT < 33) return null
        val icon = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() as? AdaptiveIconDrawable ?: return null
        val mono = icon.monochrome ?: return null
        val colors = palette(packageName, dark)
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.tile)
        // Adaptive layers reach a quarter past the icon on every side.
        val extra = size / 4
        mono.mutate().colorFilter = PorterDuffColorFilter(colors.glyph, PorterDuff.Mode.SRC_IN)
        mono.setBounds(-extra, -extra, size + extra, size + extra)
        mono.draw(canvas)
        return BitmapDrawable(context.resources, bitmap)
    }
}
