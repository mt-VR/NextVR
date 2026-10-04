package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * The floating VR keyboard in the Next VR language: the reference's blue-black panel with a
 * hairline, translucent keys that light up under the pointer, a pressed key going light periwinkle
 * with navy text, and a steel-blue enter. Russian and English letters, digits, shift, backspace,
 * space, enter.
 */
class KeyboardPanel {
    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keys = ArrayList<Pair<RectF, String>>()
    private var russian = true
    private var shift = false

    private var symbols = false

    /** What a touch at 0..1 panel coordinates types: a character, or "backspace", "enter", "hide". */
    fun press(u: Float, v: Float): String? {
        val key = keys.firstOrNull { it.first.contains(u * WIDTH, v * HEIGHT) }?.second ?: return null
        return when (key) {
            SHIFT -> { shift = !shift; null }
            LANGUAGE -> { russian = !russian; symbols = false; null }
            SYMBOLS -> { symbols = !symbols; null }
            SPACE -> " "
            BACKSPACE, ENTER, HIDE -> key
            else -> (if (shift) key.uppercase() else key).also { shift = false }
        }
    }

    /** Latin letters (e-mail, passwords) or Russian. */
    fun setRussian(value: Boolean) { russian = value }

    fun hovered(u: Float, v: Float): String? = keys.firstOrNull { it.first.contains(u * WIDTH, v * HEIGHT) }?.second

    /** Four rows of keys on the reference's keyboard panel, numbers as small hints. */
    fun draw(hover: String?) {
        keys.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        // The panel: the reference's blue-black, its hairline, and the keys floating on it.
        val shape = RectF(2f, 2f, WIDTH - 2f, HEIGHT - 2f)
        paint.color = NextDesign.keyboardPanel
        canvas.drawRoundRect(shape, PANEL_R, PANEL_R, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = NextDesign.keyboardPanelStroke
        canvas.drawRoundRect(shape, PANEL_R, PANEL_R, paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        val rows = when {
            symbols -> SYMBOL_ROWS
            russian -> RUSSIAN
            else -> ENGLISH
        }
        val pad = 28f
        val gap = 12f
        val rowH = (HEIGHT - 2 * pad - 3 * gap) / 4
        val unit = (WIDTH - 2 * pad - 11 * gap) / 12
        fun top(row: Int) = pad + row * (rowH + gap)
        // Row 1: letters, then ⌫.
        var x = pad
        rows[0].forEachIndexed { i, c ->
            key(RectF(x, top(0), x + unit, top(0) + rowH), c.toString(), label(c), hover, hint = if (!symbols && i < 10) "1234567890"[i].toString() else null)
            x += unit + gap
        }
        key(RectF(x, top(0), WIDTH - pad, top(0) + rowH), BACKSPACE, "⌫", hover)
        // Row 2: letters, then the blue →.
        x = pad + unit * .35f
        rows[1].forEach { c ->
            key(RectF(x, top(1), x + unit, top(1) + rowH), c.toString(), label(c), hover)
            x += unit + gap
        }
        key(RectF(x, top(1), WIDTH - pad, top(1) + rowH), ENTER, "→", hover)
        // Row 3: ⇧, letters, ⇧.
        x = pad
        key(RectF(x, top(2), x + unit * 1.4f, top(2) + rowH), SHIFT, "⇧", hover, lit = shift)
        x += unit * 1.4f + gap
        rows[2].forEach { c ->
            key(RectF(x, top(2), x + unit, top(2) + rowH), c.toString(), label(c), hover)
            x += unit + gap
        }
        key(RectF(x, top(2), WIDTH - pad, top(2) + rowH), SHIFT, "⇧", hover, lit = shift)
        // Row 4: !123, 🌐, space, ",", ".", hide.
        x = pad
        val y = top(3)
        key(RectF(x, y, x + unit * 1.6f, y + rowH), SYMBOLS, if (symbols) "ABC" else "!123", hover); x += unit * 1.6f + gap
        key(RectF(x, y, x + unit, y + rowH), LANGUAGE, "🌐", hover); x += unit + gap
        val spaceEnd = WIDTH - pad - 3 * (unit + gap)
        key(RectF(x, y, spaceEnd, y + rowH), SPACE, if (russian) "пробел" else "space", hover); x = spaceEnd + gap
        key(RectF(x, y, x + unit, y + rowH), ",", ",", hover); x += unit + gap
        key(RectF(x, y, x + unit, y + rowH), ".", ".", hover); x += unit + gap
        key(RectF(x, y, WIDTH - pad, y + rowH), HIDE, "⌨", hover)
    }

    private fun label(c: Char) = if (shift) c.uppercase() else c.toString()

    private fun key(rect: RectF, id: String, text: String, hover: String?, hint: String? = null, lit: Boolean = false) {
        val enter = id == ENTER
        val hovered = id == hover
        // On: a light periwinkle key with navy text. Under the pointer: the key brightens. Enter is
        // the reference's steel blue, quieter than the accent so it does not shout on every screen.
        val pressed = lit
        paint.color = when {
            enter -> NextDesign.keyEnter
            hovered && !pressed -> NextDesign.keyHover
            pressed -> NextDesign.keyPressed
            else -> NextDesign.key
        }
        canvas.drawRoundRect(rect, KEY_R, KEY_R, paint)
        if (pressed) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = NextDesign.keyStroke
            canvas.drawRoundRect(rect, KEY_R, KEY_R, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        }
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        if (hint != null) {
            paint.color = NextDesign.inkFaint
            paint.textSize = 26f
            canvas.drawText(hint, rect.right - 22f, rect.top + 32f, paint)
        }
        paint.color = if (pressed) NextDesign.keyPressedInk else NextDesign.keyInk
        paint.textSize = if (text.length > 2) 40f else 52f
        canvas.drawText(text, rect.centerX(), rect.centerY() + paint.textSize * .35f, paint)
        keys += rect to id
    }

    companion object {
        /** The panel's and the keys' corner radii, kept in proportion to the reference's 24 / 8. */
        private const val PANEL_R = 56f
        private const val KEY_R = 20f
        const val WIDTH = 1560
        const val HEIGHT = 560
        const val SHIFT = "#shift"
        const val LANGUAGE = "#lang"
        const val SPACE = "#space"
        const val SYMBOLS = "#symbols"
        const val BACKSPACE = "backspace"
        const val ENTER = "enter"
        const val HIDE = "hide"
        private val RUSSIAN = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        private val ENGLISH = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm@")
        private val SYMBOL_ROWS = listOf("1234567890-", "@#_&+()/*\"", "!?:;'%=")
    }
}
