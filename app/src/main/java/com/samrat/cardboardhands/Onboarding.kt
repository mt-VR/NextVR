package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

/**
 * First start of the headset: short greetings in the supported locales, a language picker, account,
 * hand scan, the user's name, room scan (6DoF), reach calibration, then "Welcome" before the home screen.
 */
class Onboarding(private val context: Context, private val host: Host) {
    interface Host {
        val sixDof: Boolean
        /** The room scan has found a table (for the keyboard to lie on). */
        fun tableFound(): Boolean
        fun finish()
    }

    enum class Step { GREETING, HELLO, ACCOUNT, HANDS, NAME, ROOM, REACH, WELCOME }

    var step = Step.GREETING
        private set
    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val script: Typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/Borel-Regular.ttf") }.getOrDefault(Typeface.DEFAULT)
    private var stepStart = SystemClock.elapsedRealtime()
    private val buttons = ArrayList<Pair<RectF, () -> Unit>>()
    private val keyboard = KeyboardPanel()
    private var name = Settings.userName(context)
    private var hover: String? = null

    // Hands scan and reach calibration.
    private var bothHandsSince = 0L
    private var pushes = 0
    private var armed = true
    private var peak = 0f

    private fun go(next: Step) {
        step = next
        stepStart = SystemClock.elapsedRealtime()
        bothHandsSince = 0L
        if (next == Step.NAME) keyboard.setLanguage(L10n.current)
    }

    private fun elapsed() = (SystemClock.elapsedRealtime() - stepStart) / 1000f

    /** Hands from the camera: both open hands for the scan. */
    @Synchronized
    fun onHands(hands: List<Pair<Boolean, HandGestures.Shape>>, points: List<FloatArray>) {
        val now = SystemClock.elapsedRealtime()
        when (step) {
            Step.HANDS -> {
                val ready = hands.size >= 2 && hands.none { it.second.fist }
                if (!ready) { bothHandsSince = 0L; return }
                if (bothHandsSince == 0L) bothHandsSince = now
                if (now - bothHandsSince > SCAN_MS) {
                    HandProfile.save(context, points)
                    go(Step.NAME)
                }
            }
            else -> Unit
        }
    }

    /** The pointing fingertip's distance (see [HandGestures.tipDepth]): three pushes to the full reach. */
    @Synchronized
    fun onReach(depth: Float, pointing: Boolean) {
        if (step != Step.REACH || !pointing || depth <= 0f) return
        peak = max(peak, depth)
        if (armed && depth > peak * .92f && elapsed() > 1f) {
            armed = false
            pushes++
            if (pushes >= 3) {
                HandProfile.saveReach(context, peak)
                go(Step.WELCOME)
            }
        } else if (depth < peak * .7f) armed = true
    }

    /** A touch on the panel at (u, v). */
    @Synchronized
    fun press(u: Float, v: Float) {
        val x = u * WIDTH; val y = v * HEIGHT
        if (step == Step.ACCOUNT && accountMode != 0 && keyboardRect.contains(x, y)) {
            val key = keyboard.press((x - keyboardRect.left) / keyboardRect.width(), (y - keyboardRect.top) / keyboardRect.height())
            when (key) {
                null, KeyboardPanel.HIDE -> Unit
                KeyboardPanel.BACKSPACE -> type("backspace")
                KeyboardPanel.ENTER -> type("enter")
                else -> type(key)
            }
            return
        }
        if (step == Step.NAME && keyboardRect.contains(x, y)) {
            when (val key = keyboard.press((x - keyboardRect.left) / keyboardRect.width(), (y - keyboardRect.top) / keyboardRect.height())) {
                null -> Unit
                KeyboardPanel.BACKSPACE -> name = name.dropLast(1)
                KeyboardPanel.ENTER, KeyboardPanel.HIDE -> confirmName()
                else -> if (name.length < 24) name += key
            }
            return
        }
        buttons.firstOrNull { it.first.contains(x, y) }?.second?.invoke()
    }

    /** A real keyboard: the name is typed on it too. */
    @Synchronized
    fun type(key: String) {
        if (step == Step.ACCOUNT && accountMode != 0) {
            if (busy) return
            val value = when (field) { 0 -> email; 1 -> password; else -> name }
            val next = when (key) {
                "backspace" -> value.dropLast(1)
                "enter" -> {
                    // Enter goes to the next field, and from the last one signs in.
                    val last = if (accountMode == 2) 2 else 1
                    if (field < last) selectAccountField(field + 1) else submitAccount()
                    return
                }
                else -> if (value.length < 64) value + key else value
            }
            when (field) { 0 -> email = next; 1 -> password = next; else -> name = next }
            message = null
            return
        }
        if (step != Step.NAME) { if (key == "enter") buttons.lastOrNull()?.second?.invoke(); return }
        when (key) {
            "backspace" -> name = name.dropLast(1)
            "enter" -> confirmName()
            else -> if (name.length < 24) name += key
        }
    }

    @Synchronized
    fun hover(u: Float, v: Float) {
        hover = if (step == Step.NAME || step == Step.ACCOUNT && accountMode != 0) {
            val x = u * WIDTH; val y = v * HEIGHT
            if (keyboardRect.contains(x, y)) keyboard.hovered((x - keyboardRect.left) / keyboardRect.width(), (y - keyboardRect.top) / keyboardRect.height()) else null
        } else null
    }

    // ------------------------------------------------------------------ the account, in VR

    /** 0: sign in, create or later; 1: signing in; 2: creating an account. */
    private var accountMode = 0
    /** The field being typed: 0 e-mail, 1 password, 2 name. */
    private var field = 0
    private var email = ""
    private var password = ""
    @Volatile private var busy = false
    @Volatile private var message: String? = null

    /**
     * The NextVR account, like on Quest: sign in or create one right here with the VR keyboard,
     * or leave it for later (the phone app asks again).
     */
    private fun account(t: Float) {
        card()
        if (accountMode == 0) {
            title(tr("NextVR account"))
            body(tr("Sign in to call friends, see them in VR and keep your settings. You can also do it later in the phone app."))
            button(RectF(WIDTH / 2f - 420f, 620f, WIDTH / 2f - 20f, 710f), tr("Sign in")) { openForm(1) }
            button(RectF(WIDTH / 2f + 20f, 620f, WIDTH / 2f + 420f, 710f), tr("Create account"), FAINT, INK) { openForm(2) }
            button(RectF(WIDTH / 2f - 150f, 760f, WIDTH / 2f + 150f, 830f), tr("Later"), FAINT, INK) { go(Step.HANDS) }
            return
        }
        text(if (accountMode == 1) tr("Sign in") else tr("Create account"), WIDTH / 2f, 105f, 56f, INK, bold = true)
        val rows = if (accountMode == 2) listOf(tr("E-mail"), tr("Password"), tr("Name")) else listOf(tr("E-mail"), tr("Password"))
        val fieldH = 64f
        val gap = 12f
        rows.forEachIndexed { i, label ->
            val top = 135f + i * (fieldH + gap)
            val rect = RectF(300f, top, WIDTH - 300f, top + fieldH)
            paint.color = if (i == field) NextDesign.tileHover else FAINT
            canvas.drawRoundRect(rect, FIELD_R, FIELD_R, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = if (i == field) NextDesign.accentLine else NextDesign.stroke
            canvas.drawRoundRect(rect, FIELD_R, FIELD_R, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            val value = when (i) { 0 -> email; 1 -> "•".repeat(password.length); else -> name }
            val cursor = if (i == field && (t * 2).toInt() % 2 == 0) "|" else ""
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 34f
            paint.typeface = Typeface.DEFAULT
            paint.color = if (value.isEmpty()) SOFT else INK
            canvas.drawText(if (value.isEmpty() && i != field) label else value + cursor, rect.left + 36f, rect.centerY() + 12f, paint)
            paint.textAlign = Paint.Align.LEFT
            buttons += rect to { selectAccountField(i) }
        }
        val actions = 135f + rows.size * (fieldH + gap) + 4f
        button(RectF(300f, actions, 560f, actions + 58f), tr("Back"), FAINT, INK) { accountMode = 0; message = null }
        val go = if (busy) "…" else if (accountMode == 1) tr("Sign in") else tr("Create")
        button(RectF(WIDTH - 560f, actions, WIDTH - 300f, actions + 58f), go) { submitAccount() }
        message?.let { text(it, WIDTH / 2f, actions + 40f, 28f, NextDesign.danger) }
        keyboard.draw(hover)
        canvas.drawBitmap(keyboard.bitmap, null, keyboardRect, paint)
    }

    private fun openForm(mode: Int) {
        accountMode = mode
        selectAccountField(0)
        message = null
    }

    /** E-mail and password stay Latin; the optional account name follows the selected UI language. */
    private fun selectAccountField(index: Int) {
        field = index
        val followsUiLanguage = accountMode == 2 && index == 2
        keyboard.setLanguage(if (followsUiLanguage) L10n.current else L10n.Lang.EN, lock = !followsUiLanguage)
    }

    /** Signs in or creates the account on a background thread; on success setup goes on. */
    private fun submitAccount() {
        if (busy) return
        if (!email.contains('@') || password.length < 6) {
            message = tr("Enter your e-mail and a password (6+ characters)")
            return
        }
        if (accountMode == 2 && name.isBlank()) { message = tr("Enter your name"); selectAccountField(2); return }
        busy = true
        message = null
        val signUp = accountMode == 2
        Thread {
            val error = if (signUp) Account.signUp(context, email.trim(), password, name.trim()) else Account.signIn(context, email.trim(), password)
            synchronized(this) {
                busy = false
                if (error == null) {
                    password = ""
                    name = Settings.userName(context)
                    go(Step.HANDS)
                } else message = error
            }
        }.start()
    }

    private fun confirmName() {
        if (name.isBlank()) return
        Settings.setUserName(context, name.trim())
        if (host.sixDof) go(Step.ROOM) else go(Step.WELCOME)
    }

    /** Redraws the panel for this moment; returns false once setup is over. */
    @Synchronized
    fun draw(): Boolean {
        buttons.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        val t = elapsed()
        when (step) {
            Step.GREETING -> {
                greeting(t)
                if (t > GREETINGS.size * GREETING_SECONDS) go(Step.HELLO)
            }
            Step.HELLO -> hello(t)
            Step.ACCOUNT -> account(t)
            Step.HANDS -> {
                card()
                title(tr("Show your hands"))
                body(tr("Hold both hands in front of you with fingers open. Keep still for a few seconds."))
                val progress = if (bothHandsSince == 0L) 0f else ((SystemClock.elapsedRealtime() - bothHandsSince) / SCAN_MS.toFloat()).coerceIn(0f, 1f)
                bar(progress)
                button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), tr("Skip"), FAINT, INK) { go(Step.NAME) }
            }
            Step.NAME -> {
                card()
                title(tr("What's your name?"))
                paint.color = FAINT
                canvas.drawRoundRect(RectF(300f, 220f, WIDTH - 300f, 330f), 30f, 30f, paint)
                text(if (name.isEmpty()) tr("Username") else name + if ((t * 2).toInt() % 2 == 0) "|" else "",
                    WIDTH / 2f, 295f, 60f, if (name.isEmpty()) SOFT else INK)
                button(RectF(WIDTH / 2f - 200f, 360f, WIDTH / 2f + 200f, 440f), tr("Done")) { confirmName() }
                keyboard.draw(hover)
                canvas.drawBitmap(keyboard.bitmap, null, keyboardRect, paint)
            }
            Step.ROOM -> {
                card()
                title(tr("Room scan"))
                body(tr("Look at the floor, the walls and the table — NextVR covers them with a grid and remembers where the table is: the keyboard will lie on it."))
                text(if (host.tableFound()) tr("Table found ✓") else tr("Looking for a table…"), WIDTH / 2f, 640f, 44f, if (host.tableFound()) NextDesign.good else SOFT, bold = true)
                button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), if (host.tableFound()) tr("Done") else tr("Skip"), if (host.tableFound()) BLUE else FAINT, if (host.tableFound()) Color.WHITE else INK) { go(Step.WELCOME) }
            }
            Step.REACH -> {
                card()
                title(tr("Touch"))
                body(tr("Windows are pressed with a finger: point your index finger, curl the others, and push your hand forward briefly. Three times."))
                button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), tr("Skip"), FAINT, INK) { go(Step.WELCOME) }
                for (i in 0 until 3) {
                    paint.color = if (i < pushes) NextDesign.good else FAINT
                    canvas.drawCircle(WIDTH / 2f + (i - 1) * 90f, 600f, 30f, paint)
                }
            }
            Step.WELCOME -> {
                val hi = Settings.userName(context).takeIf { it.isNotBlank() }
                card()
                written(tr("Welcome"), t, 150f, HEIGHT / 2f + 20f)
                if (hi != null && t > 1f) text(hi, WIDTH / 2f, HEIGHT / 2f + 150f, 64f, Color.argb(((t - 1f).coerceIn(0f, 1f) * 179).toInt(), 245, 246, 247))
                if (t > 3f) {
                    Settings.setSetupDone(context)
                    host.finish()
                    return false
                }
            }
        }
        return true
    }

    /**
     * The first seconds: an app card in front of the user, light with "Hi", dark with "Hello",
     * light again with "你好" — each for three seconds, the change a quick cross-fade.
     */
    private fun greeting(t: Float) {
        val index = (t / GREETING_SECONDS).toInt().coerceAtMost(GREETINGS.size - 1)
        val local = t - index * GREETING_SECONDS
        val dark = true
        // The card grows in at the very start.
        val appear = (t / .6f).coerceIn(0f, 1f)
        val inset = (1f - appear) * 120f
        val rect = RectF(60f + inset, 40f + inset, WIDTH - 60f - inset, HEIGHT - 40f - inset)
        glass(rect, dark)
        val fadeIn = (local / .4f).coerceIn(0f, 1f)
        val fadeOut = ((GREETING_SECONDS - local) / .4f).coerceIn(0f, 1f)
        val alpha = (minOf(fadeIn, fadeOut) * appear * 255).toInt()
        val ink = INK
        val (word, code) = GREETINGS[index]
        text(word, WIDTH / 2f, HEIGHT / 2f + 60f, 190f, Color.argb(alpha, Color.red(ink), Color.green(ink), Color.blue(ink)), bold = true)
        text(code, WIDTH / 2f, HEIGHT / 2f + 170f, 40f, Color.argb(alpha * 5 / 10, Color.red(ink), Color.green(ink), Color.blue(ink)))
        // The language is chosen on this very first card: the row appears with the card itself.
        if (t > .6f) {
            text(tr("Choose your language"), WIDTH / 2f, 700f, 34f, SOFT)
            languageRow()
        }
    }

    /** The supported-language picker, drawn inside the existing NextVR setup window. */
    private fun hello(t: Float) {
        glass(RectF(60f, 40f, WIDTH - 60f, HEIGHT - 40f), true)

        // Reference-style header and selected-language chip, adapted to the dark NextVR window.
        paint.color = NextDesign.profileMid
        canvas.drawRoundRect(RectF(138f, 94f, 204f, 160f), 20f, 20f, paint)
        text("N", 171f, 140f, 42f, Color.WHITE, bold = true)
        text("NEXT VR", 295f, 138f, 23f, SOFT, bold = true)
        text(tr("Choose your language"), 550f, 222f, 56f, INK, bold = true)
        text(tr("Choose from these supported languages."), 550f, 268f, 24f, SOFT)

        val current = L10n.current
        paint.color = FAINT
        canvas.drawRoundRect(RectF(138f, 300f, 962f, 366f), 20f, 20f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = NextDesign.stroke
        canvas.drawRoundRect(RectF(138f, 300f, 962f, 366f), 20f, 20f, paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        text(current.badge, 195f, 343f, 23f, NextDesign.accent, bold = true)
        text(tr("Current language"), 370f, 343f, 22f, SOFT)
        text(current.title, 720f, 343f, 28f, INK, bold = true)

        L10n.Lang.entries.forEachIndexed { index, lang ->
            val top = 382f + index * 102f
            languageOption(lang, RectF(138f, top, 962f, top + 90f))
        }
        languageArtwork(t)
        if (t > .6f) {
            button(RectF(600f, 852f, 1000f, 932f), tr("Continue")) {
                // Setup does not stop at an account. One is needed for friends, calls and the
                // store, and can be created at any time from Settings -> Account; making it a step
                // here is what sent people who said "Later" straight back to a sign-in screen.
                go(Step.HANDS)
            }
        }
    }

    /** A selectable row: exactly one row for each language [L10n] translates and types. */
    private fun languageOption(lang: L10n.Lang, rect: RectF) {
        val selected = lang == L10n.current
        paint.color = if (selected) NextDesign.accentSoft else FAINT
        canvas.drawRoundRect(rect, BUTTON_R, BUTTON_R, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (selected) NextDesign.accentLine else NextDesign.stroke
        canvas.drawRoundRect(rect, BUTTON_R, BUTTON_R, paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f

        val badge = RectF(rect.left + 18f, rect.top + 19f, rect.left + 86f, rect.bottom - 19f)
        paint.color = if (selected) NextDesign.accentSoft else NextDesign.tileHover
        canvas.drawRoundRect(badge, 14f, 14f, paint)
        text(lang.badge, badge.centerX(), badge.centerY() + 9f, 22f, if (selected) NextDesign.accent else INK, bold = true)
        text(lang.nativeName, rect.left + 310f, rect.top + 39f, 30f, INK, bold = true)
        text(lang.detail, rect.left + 545f, rect.top + 68f, 22f, SOFT)
        if (selected) text("✓", rect.right - 48f, rect.centerY() + 13f, 32f, NextDesign.accent, bold = true)
        buttons += rect to {
            L10n.set(context, lang)
            keyboard.setLanguage(lang)
        }
    }

    /** The reference's soft violet artwork, kept inside the same glass setup window. */
    private fun languageArtwork(t: Float) {
        val rect = RectF(1000f, 78f, 1490f, 918f)
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xFF32344A.toInt(), 0xFF3E3154.toInt(), 0xFF28374C.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 42f, 42f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 28f
        paint.color = 0x24FFFFFF
        canvas.drawCircle(1310f, 360f, 225f, paint)
        paint.strokeWidth = 18f
        paint.color = 0x18FFFFFF
        canvas.drawCircle(1180f, 690f, 185f, paint)
        paint.style = Paint.Style.FILL

        paint.shader = LinearGradient(1160f, 250f, 1360f, 470f, 0xFF8396CE.toInt(), 0xFF172034.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(1270f, 355f, 122f, paint)
        paint.shader = null
        paint.color = 0xFF76DDF2.toInt()
        canvas.drawCircle(1342f, 305f, 9f, paint)
        paint.color = 0xFFA995FF.toInt()
        canvas.drawCircle(1320f, 345f, 6f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(1203f, 410f, 5f, paint)

        val alpha = ((t / .5f).coerceIn(0f, 1f) * 255).toInt()
        val greeting = L10n.current.greeting
        text(greeting, rect.centerX(), 636f, 76f, Color.argb(alpha, 255, 255, 255), bold = true)
        text(L10n.current.detail, rect.centerX(), 686f, 25f, Color.argb(alpha * 3 / 4, 244, 246, 250))
        paint.color = Color.argb(alpha / 2, 255, 255, 255)
        canvas.drawRoundRect(RectF(1120f, 760f, 1420f, 766f), 3f, 3f, paint)
    }

    /** The card: the reference's glass panel with its hairline and a soft shadow under it. */
    private fun glass(rect: RectF, dark: Boolean) {
        paint.color = NextDesign.shadow
        paint.maskFilter = android.graphics.BlurMaskFilter(30f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(RectF(rect.left, rect.top + 12f, rect.right, rect.bottom + 12f), CARD_R, CARD_R, paint)
        paint.maskFilter = null
        // A shader still takes the paint's alpha: full, or the card turns see-through.
        paint.color = Color.WHITE
        paint.shader = if (dark) LinearGradient(0f, rect.top, 0f, rect.bottom, NextDesign.glassTopVeil, NextDesign.glassBottomVeil, Shader.TileMode.CLAMP)
        else LinearGradient(0f, rect.top, 0f, rect.bottom, Color.WHITE, Color.rgb(242, 242, 242), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, CARD_R, CARD_R, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (dark) NextDesign.stroke else Color.argb(20, 39, 39, 39)
        canvas.drawRoundRect(rect, CARD_R, CARD_R, paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
    }

    /**
     * Script text drawn as if written: revealed from left to right, then held; white, round and a
     * little lit from above like the visionOS lettering.
     */
    private fun written(value: String, t: Float, size: Float, baseline: Float, fadeAt: Float = Float.MAX_VALUE) {
        paint.typeface = script
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        val width = paint.measureText(value)
        val reveal = (t / 1.3f).coerceIn(0f, 1f)
        val eased = 1f - (1f - reveal) * (1f - reveal)
        val alpha = if (t > fadeAt) (1f - (t - fadeAt) / .45f).coerceIn(0f, 1f) else 1f
        val left = WIDTH / 2f - width / 2 - 20f
        canvas.save()
        canvas.clipRect(left, 0f, left + (width + 40f) * eased, HEIGHT.toFloat())
        paint.shader = null
        paint.color = Color.argb((alpha * 25).toInt(), 0, 0, 0)
        canvas.drawText(value, WIDTH / 2f + 4f, baseline + 6f, paint)
        paint.shader = LinearGradient(0f, baseline - size, 0f, baseline, Color.WHITE, Color.rgb(205, 210, 218), Shader.TileMode.CLAMP)
        paint.alpha = (alpha * 255).toInt()
        canvas.drawText(value, WIDTH / 2f, baseline, paint)
        paint.shader = null
        canvas.restore()
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT
        paint.alpha = 255
    }

    private fun card() = glass(RectF(60f, 40f, WIDTH - 60f, HEIGHT - 40f), true)

    private fun title(value: String) = text(value, WIDTH / 2f, 170f, 76f, INK, bold = true)

    private fun body(value: String) {
        paint.textSize = 44f
        var line = ""
        var row = 0
        for (word in value.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) > WIDTH - 360f) {
                text(line, WIDTH / 2f, 300f + row * 62f, 44f, SOFT); row++; line = word
            } else line = next
        }
        text(line, WIDTH / 2f, 300f + row * 62f, 44f, SOFT)
    }

    private fun bar(progress: Float) {
        paint.color = FAINT
        canvas.drawRoundRect(RectF(400f, 560f, WIDTH - 400f, 590f), 15f, 15f, paint)
        paint.color = NextDesign.accent
        canvas.drawRoundRect(RectF(400f, 560f, 400f + (WIDTH - 800f) * progress, 590f), 15f, 15f, paint)
    }

    /**
     * A button of the setup: the filled teal action, or — with [color] left at the tile colour — the
     * reference's tinted button, a white 7% tile with a hairline and ink text.
     */
    private fun button(rect: RectF, label: String, color: Int = NextDesign.primary, ink: Int = Color.WHITE, size: Float = 44f, action: () -> Unit) {
        val tinted = color == FAINT
        paint.color = color
        canvas.drawRoundRect(rect, BUTTON_R, BUTTON_R, paint)
        if (tinted) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = NextDesign.stroke
            canvas.drawRoundRect(rect, BUTTON_R, BUTTON_R, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        }
        text(label, rect.centerX(), rect.centerY() + size * .36f, size, ink, bold = true)
        buttons += rect to action
    }

    /** Choose among the supported interface languages from the opening greeting card. */
    private fun languageRow() {
        val languages = L10n.Lang.entries
        val width = 350f
        val gap = 18f
        val left = (WIDTH - (languages.size * width + (languages.size - 1) * gap)) / 2f
        languages.forEachIndexed { i, lang ->
            val rect = RectF(
                left + i * (width + gap), LANGUAGE_ROW_TOP,
                left + i * (width + gap) + width, LANGUAGE_ROW_TOP + LANGUAGE_ROW_HEIGHT
            )
            val chosen = lang == L10n.current
            // The accent wash marks the chosen language; the hairline is what draws it.
            button(
                rect, lang.title,
                if (chosen) NextDesign.accentSoft else FAINT,
                if (chosen) NextDesign.accent else INK,
                size = 32f
            ) {
                L10n.set(context, lang)
                keyboard.setLanguage(lang)
            }
            if (chosen) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = NextDesign.accentLine
                canvas.drawRoundRect(rect, BUTTON_R, BUTTON_R, paint)
                paint.style = Paint.Style.FILL
                paint.strokeWidth = 0f
            }
        }
    }

    private fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(value, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private val keyboardRect = RectF(40f, 470f, WIDTH - 40f, 470f + (WIDTH - 80f) * KeyboardPanel.HEIGHT / KeyboardPanel.WIDTH)

    companion object {
        const val WIDTH = 1600
        const val HEIGHT = 1000
        private const val SCAN_MS = 2000L
        /** The language row under the first cards (see [languageRow]). */
        private const val LANGUAGE_ROW_TOP = 748f
        private const val LANGUAGE_ROW_HEIGHT = 84f
        /** NextVR is dark only: the design language's ink on its glass, its teal for the action. */
        private val INK = NextDesign.ink
        private val SOFT = NextDesign.inkSoft
        private val FAINT = NextDesign.tile
        private val BLUE = NextDesign.primary
        /** The card's radius (the reference's window), a field's and a button's. */
        private const val CARD_R = 52f
        private const val FIELD_R = 20f
        private const val BUTTON_R = 28f
        private const val GREETING_SECONDS = 3f
        /** The opening greetings correspond only to the four locales the app actually supports. */
        private val GREETINGS = L10n.Lang.entries.map { it.greeting to it.badge }
    }
}

/**
 * The user's hands as scanned in setup: bone lengths relative to the palm, used to keep the
 * see-through hands steady; and the pinch distances measured in calibration.
 */
object HandProfile {
    private const val PREFS = "hand_profile"

    fun save(context: Context, hands: List<FloatArray>) {
        val hand = hands.firstOrNull() ?: return
        fun d(a: Int, b: Int) = kotlin.math.hypot(hand[a * 3] - hand[b * 3], hand[a * 3 + 1] - hand[b * 3 + 1])
        val palm = d(5, 17).coerceAtLeast(1e-4f)
        val ratios = GhostHand.BONES.joinToString(",") { (a, b) -> "%.4f".format(java.util.Locale.US, d(a, b) / palm) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("bones", ratios).apply()
    }

    /** Bone length / palm width per bone of [GhostHand.BONES], or null before the scan. */
    fun bones(context: Context): FloatArray? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("bones", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
        ?.takeIf { it.size == GhostHand.BONES.size }

    fun savePinch(context: Context, closed: Float, open: Float) {
        val close = (closed + (open - closed) * .3f).coerceIn(.2f, .45f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("pinch_close", close).putFloat("pinch_open", (close + .16f).coerceAtMost(.7f)).apply()
    }

    /**
     * Where hands cut through the VR content: how much wider than the fingers the cut is and how
     * far it is shifted (head-space tangent units), set in Settings → Hand calibration.
     */
    data class Mask(val grow: Float, val dx: Float, val dy: Float)

    fun mask(context: Context): Mask {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Mask(prefs.getFloat("mask_grow", 1.1f), prefs.getFloat("mask_dx", 0f), prefs.getFloat("mask_dy", 0f))
    }

    fun saveMask(context: Context, mask: Mask) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("mask_grow", mask.grow.coerceIn(.7f, 2f))
            .putFloat("mask_dx", mask.dx.coerceIn(-.3f, .3f))
            .putFloat("mask_dy", mask.dy.coerceIn(-.3f, .3f))
            .apply()
    }

    /** Furthest the pointing fingertip went in the setup (see [HandGestures.tipDepth]); a guess before it. */
    fun reach(context: Context): Float = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("reach", .22f)

    fun saveReach(context: Context, reach: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("reach", reach.coerceAtLeast(.05f)).apply()
    }

    /** How far forward (a share of the hand's distance, 0.06..0.30) a finger pushes to press; VR Settings → Hands and touch. */
    fun touchShare(context: Context): Float = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("touch_push", .14f)

    fun setTouchShare(context: Context, share: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("touch_push", share.coerceIn(.06f, .3f)).apply()
    }

    /** A pinch latch tuned to this user's fingers. */
    fun latch(context: Context): HandGestures.PinchLatch {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HandGestures.PinchLatch(
            prefs.getFloat("pinch_close", HandGestures.PINCH_CLOSE_GAP),
            prefs.getFloat("pinch_open", HandGestures.PINCH_OPEN_GAP),
        )
    }
}
