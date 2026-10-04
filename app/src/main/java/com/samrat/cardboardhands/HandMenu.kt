package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint

/**
 * The system menu that opens on the hand: palm held toward the face. A small glass bar with
 * round buttons in the Next VR language ([NextDesign]) that floats just above the hand which called
 * it and moves with it; the other hand touches an item with a fingertip to choose.
 */
class HandMenu(val holderLeft: Boolean, private val items: List<Item>) {
    class Item(val id: String, val label: String, val icon: Drawable?)

    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NextDesign.shadow; maskFilter = BlurMaskFilter(24f, BlurMaskFilter.Blur.NORMAL) }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    /** Where the menu sits, head space (tangent units): centre x, y. Follows the holder's hand. */
    @Volatile var x = Float.NaN
    @Volatile var y = Float.NaN
    @Volatile var hovered = -1
        private set
    @Volatile var dirty = true
    /** Where it was opened, as a world direction: it stays there while the head turns. */
    var world: FloatArray? = null
    var lastSeen = System.currentTimeMillis()

    /** Moves toward the holder's palm, smoothly so the menu does not shake with the hand. */
    fun follow(palmX: Float, palmY: Float, palmSize: Float) {
        val targetX = palmX
        val targetY = palmY + palmSize * 1.9f + HALF_H
        if (x.isNaN()) { x = targetX; y = targetY } else { x += (targetX - x) * .35f; y += (targetY - y) * .35f }
        lastSeen = System.currentTimeMillis()
    }

    /** The item under a head-space point, or -1. */
    fun itemAt(px: Float, py: Float): Int {
        if (x.isNaN()) return -1
        val u = (px - (x - HALF_W)) / (HALF_W * 2)
        val v = ((y + HALF_H) - py) / (HALF_H * 2)
        if (u !in 0f..1f || v !in 0f..1f) return -1
        val column = ((u * WIDTH - PAD) / tileW).toInt()
        val row = ((v * HEIGHT - PAD) / tileH).toInt()
        if (column !in 0 until COLUMNS || row < 0) return -1
        return (row * COLUMNS + column).takeIf { it in items.indices } ?: -1
    }

    fun contains(px: Float, py: Float) = !x.isNaN() && kotlin.math.abs(px - x) <= HALF_W && kotlin.math.abs(py - y) <= HALF_H

    fun hover(index: Int) {
        if (index != hovered) { hovered = index; dirty = true }
    }

    fun item(index: Int) = items.getOrNull(index)

    fun draw() {
        bitmap.eraseColor(Color.TRANSPARENT)
        val card = RectF(12f, 12f, WIDTH - 12f, HEIGHT - 12f)
        canvas.drawRoundRect(card, 90f, 90f, shadow)
        // The Next VR glass: the reference's panel with its hairline.
        val dark = Ui.dark
        val ink = if (dark) NextDesign.ink else Color.rgb(39, 39, 39)
        val tile = if (dark) NextDesign.tile else Color.argb(26, 39, 39, 39)
        val tileHover = if (dark) NextDesign.tileHover else Color.argb(46, 39, 39, 39)
        val accent = if (dark) NextDesign.accent else Color.rgb(70, 110, 200)
        paint.color = Color.WHITE
        paint.shader = android.graphics.LinearGradient(0f, card.top, 0f, card.bottom,
            if (dark) NextDesign.glassTopVeil else Color.argb(247, 255, 255, 255),
            if (dark) NextDesign.glassBottomVeil else Color.argb(247, 242, 242, 242), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, 90f, 90f, paint)
        paint.shader = null
        label.color = ink
        paint.color = if (dark) NextDesign.stroke else Color.argb(20, 39, 39, 39)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(card, 90f, 90f, paint)
        paint.style = Paint.Style.FILL
        // Tiles in rows of three, an icon over a label; the one under the pointer brightens and its
        // label takes the accent — the reference's selected state, not a filled accent tile.
        items.forEachIndexed { i, item ->
            val left = PAD + (i % COLUMNS) * tileW
            val top = PAD + (i / COLUMNS) * tileH
            val tileRect = RectF(left + 8f, top + 8f, left + tileW - 8f, top + tileH - 8f)
            paint.color = if (i == hovered) tileHover else tile
            canvas.drawRoundRect(tileRect, 34f, 34f, paint)
            if (i == hovered) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                paint.color = if (dark) NextDesign.accentLine else Color.argb(120, 70, 110, 200)
                canvas.drawRoundRect(tileRect, 34f, 34f, paint)
                paint.style = Paint.Style.FILL
            }
            val r = 34f
            val circle = RectF(tileRect.centerX() - r, tileRect.top + 22f, tileRect.centerX() + r, tileRect.top + 22f + 2 * r)
            item.icon?.let { it.setBounds(circle.left.toInt(), circle.top.toInt(), circle.right.toInt(), circle.bottom.toInt()); it.draw(canvas) }
            label.color = if (i == hovered) accent else ink
            val shown = android.text.TextUtils.ellipsize(item.label, label, tileRect.width() - 20f, android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, tileRect.centerX(), tileRect.bottom - 26f, label)
        }
        dirty = false
    }

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 470
        private const val COLUMNS = 3
        private const val PAD = 30f
        /** Size in head space (tangent units, about metres at arm's length). */
        const val HALF_W = .24f
        const val HALF_H = HALF_W * HEIGHT / WIDTH
    }

    private val tileW get() = (WIDTH - 2 * PAD) / COLUMNS
    private val tileH get() = (HEIGHT - 2 * PAD) / ((items.size + COLUMNS - 1) / COLUMNS).coerceAtLeast(1)
}
