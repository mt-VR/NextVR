package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Interests
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Vrpano
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.io.File
import java.util.WeakHashMap

/**
 * The VR home in the Next VR language ([NextDesign]): the Library card (title, search, sections, a
 * grid of gradient app tiles, sorting) over the world-locked dock — the reference's slate capsule
 * with its lit top edge — with Material You icons where an Android app brings its own. Everything
 * sits exactly where [HomePanel] says its targets are, so pointing keeps working.
 */
class HomePanelContent(private val panel: HomePanel) :
    ComposeContent(pixelWidth = HomePanel.WIDTH, pixelHeight = HomePanel.HEIGHT) {
    override val transparent = true

    /**
     * The Next VR colours ([NextDesign]): dark glass with a hairline, white 7% tiles, ink for the
     * text and the periwinkle accent for whatever is selected, on or under the pointer. The light
     * pair is kept for a light room; NextVR itself is dark only.
     */
    private class Look(dark: Boolean) {
        val top = if (dark) Color(NextDesign.glassTopVeil) else Color(0xF7FFFFFF)
        val bottom = if (dark) Color(NextDesign.glassBottomVeil) else Color(0xF7F2F2F2)
        val ink = if (dark) Color(NextDesign.ink) else Color(0xFF272727)
        val soft = if (dark) Color(NextDesign.inkSoft) else ink.copy(alpha = .7f)
        val faint = if (dark) Color(NextDesign.tile) else ink.copy(alpha = .1f)
        val hover = if (dark) Color(NextDesign.tileHover) else ink.copy(alpha = .2f)
        val edge = if (dark) Color(NextDesign.stroke) else Color(0x14272727)
        /** The chosen row and the enter key: the accent with ink of its own. */
        val chosen = if (dark) Color(NextDesign.accent) else ink
        val onChosen = if (dark) Color(0xFF1B2338) else Color.White
        /** A selected row or tile: the accent as a wash, with its hairline. */
        val chosenSoft = if (dark) Color(NextDesign.accentSoft) else ink.copy(alpha = .12f)
        val chosenLine = if (dark) Color(NextDesign.accentLine) else ink.copy(alpha = .35f)
        val online = Color(NextDesign.online)
        /** Text floating in the room without a card: white with a soft shadow, as on Quest. */
        val air = Color.White
        val airSoft = Color(0xDDFFFFFF)
        val tile = if (dark) Color(NextDesign.tile) else Color(0x14272727)
        /** A quick tile at rest and under the pointer: the same glass as a window. */
        val card = if (dark) Color(NextDesign.glassTopVeil) else Color(0xF7FFFFFF)
        val cardHover = if (dark) Color(0xFF2B3139) else Color.White
        val danger = Color(NextDesign.alert)
        val batteryInk = if (dark) Color(NextDesign.batteryInk) else ink
    }

    @Composable
    override fun Content() {
        val state = panel.snapshot ?: return
        val look = remember(state.dark) { Look(state.dark) }
        Box(Modifier.fillMaxSize()) {
            if (state.library) Library(state, look)
            if (state.mode == HomePanel.Mode.QUICK) Quick(state, look)
            Dock(state, look)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A box of [w]×[h] panel pixels whose centre is at ([cx], [cy]). */
    @Composable
    private fun At(cx: Float, cy: Float, w: Float, h: Float, content: @Composable BoxScope.() -> Unit) {
        with(LocalDensity.current) {
            Box(
                Modifier.offset((cx - w / 2).toDp(), (cy - h / 2).toDp()).size(w.toDp(), h.toDp()),
                contentAlignment = Alignment.Center,
                content = content
            )
        }
    }

    @Composable
    private fun In(rect: RectF, content: @Composable BoxScope.() -> Unit) =
        At(rect.centerX(), rect.centerY(), rect.width(), rect.height(), content)

    @Composable
    private fun px(value: Float): Dp = with(LocalDensity.current) { value.toDp() }

    @Composable
    private fun Text(
        text: String, color: Color, size: Float, modifier: Modifier = Modifier,
        weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start, lines: Int = 1,
        shadow: Boolean = false,
    ) {
        val sp = with(LocalDensity.current) { size.toSp() }
        BasicText(
            text, modifier,
            style = TextStyle(
                color = color, fontSize = sp, fontWeight = weight, textAlign = align, lineHeight = sp * 1.2f,
                shadow = if (shadow) androidx.compose.ui.graphics.Shadow(Color(0xB3000000), Offset(0f, 3f), 12f) else null,
            ),
            maxLines = lines, overflow = TextOverflow.Ellipsis,
        )
    }

    /** Centred text of [width] panel pixels; [top] is the top of the line. */
    @Composable
    private fun Label(
        text: String, cx: Float, top: Float, size: Float, color: Color, width: Float,
        weight: FontWeight = FontWeight.Normal, lines: Int = 1, shadow: Boolean = false,
    ) {
        if (text.isEmpty()) return
        with(LocalDensity.current) {
            Text(text, color, size, Modifier.offset((cx - width / 2).toDp(), top.toDp()).width(width.toDp()), weight, TextAlign.Center, lines, shadow)
        }
    }

    @Composable
    private fun Glyph(icon: ImageVector, color: Color, size: Float, modifier: Modifier = Modifier) =
        Icon(icon, null, tint = color, modifier = modifier.size(px(size)))

    /** Glass: the dark fall with its hairline edge and a soft shadow under it (the reference's window). */
    private fun Modifier.glass(look: Look, shape: Shape, elevation: Dp) = this
        .shadow(elevation, shape, ambientColor = Color(NextDesign.shadow), spotColor = Color(NextDesign.shadow))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(look.top, look.bottom)))
        .border(Dp.Hairline, look.edge, shape)

    private val images = WeakHashMap<Drawable, ImageBitmap>()

    private fun image(drawable: Drawable?): ImageBitmap? {
        drawable ?: return null
        return images.getOrPut(drawable) { runCatching { drawable.toBitmap(256, 256).asImageBitmap() }.getOrNull() }
    }

    /**
     * An app tile, in the reference's language: the app's own colour falling into its deep end, a
     * white glyph, a lit rim and a corner radius that keeps the icon's proportions ([NextDesign]).
     * An Android app keeps its real icon; anything without one gets a quiet tile instead.
     */
    @Composable
    private fun Tile(entry: HomePanel.Entry, size: Float, dark: Boolean) {
        // The reference rounds an app icon by about 28% of its side.
        val shape = RoundedCornerShape(px(size * .28f))
        val palette = remember(dark, entry.id) { MaterialYouIcons.palette(entry.tint ?: entry.id, dark) }
        val glyph = entry.glyph
        if (glyph != null) {
            val gradient = NextDesign.gradientFor(entry.tint ?: entry.id)
            Box(
                Modifier.size(px(size)).clip(shape)
                    .background(Brush.linearGradient(listOf(Color(gradient.first), Color(gradient.second))))
                    // The reference's tiles carry a lit rim and a soft shadow underneath.
                    .border(Dp.Hairline, Color(0x33FFFFFF), shape)
                    .shadow(px(10f), shape, ambientColor = Color(NextDesign.shadow), spotColor = Color(NextDesign.shadow)),
                contentAlignment = Alignment.Center
            ) { Glyph(glyph, Color.White, size * .48f) }
            return
        }
        val picture = image(entry.icon)
        if (picture != null) Image(picture, null, contentScale = ContentScale.Crop, modifier = Modifier.size(px(size)).clip(shape))
        else Box(
            Modifier.size(px(size)).clip(shape).background(Color(palette.tile))
                .border(Dp.Hairline, Color(NextDesign.stroke), shape),
            contentAlignment = Alignment.Center
        ) {
            Text(entry.label.take(1).uppercase(), Color(palette.glyph), size * .42f, weight = FontWeight.SemiBold)
        }
    }

    // ------------------------------------------------------------------ the library

    /**
     * The app menu floats in the room without a card behind it, as on Quest: the tiles and their
     * names (white, with a shadow) straight over the passthrough or the world; only the controls
     * (search, sections, sorting) sit on small glass pieces.
     */
    @Composable
    private fun Library(state: HomePanel.Snapshot, look: Look) {
        // Windows arrive with a short fade in the reference; the library is NextVR's biggest one.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(NextDesign.Motion.windowMs)) }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 36f
            }
        ) {
            val title = when (state.mode) {
                HomePanel.Mode.STORE -> tr("Store")
                HomePanel.Mode.MENU -> tr("Menu")
                else -> tr("Library")
            }
            Label(title, HomePanel.WIDTH / 2f, HomePanel.TITLE_TOP, 62f, look.air, 900f, FontWeight.SemiBold, shadow = true)
            SearchField(state, look)
            Rail(state, look)
            if (state.showSliders) Sliders(state, look)
            else if (!state.searching) SortButton(state, look)
            if (state.entries.isEmpty() && !state.searching) {
                val empty = when {
                    state.mode == HomePanel.Mode.STORE -> tr("Loading the store…")
                    state.tab == HomePanel.Tab.PEOPLE -> tr("Your friends will be here — sign in in the NextVR app")
                    state.tab == HomePanel.Tab.GAMES -> tr("VR games prepared in the NextVR app will be here")
                    state.tab == HomePanel.Tab.WEB -> tr("Add web apps from the store")
                    else -> ""
                }
                Label(empty, HomePanel.GRID_CENTER_X, 560f, 38f, look.airSoft, 900f, lines = 2, shadow = true)
            }
            state.entries.forEachIndexed { index, entry ->
                val (x, y) = HomePanel.cell(index)
                val hover = state.hovered == HomePanel.Target.App(entry)
                // The reference's tile: a white 13% wash, a hairline ring and a hair of lift under the pointer.
                if (hover) At(x, y + 30f, HomePanel.CELL_W - 10f, HomePanel.ICON + 150f) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(px(HomePanel.ICON * .34f)))
                            .background(look.hover)
                            .border(Dp.Hairline, Color(0x38FFFFFF), RoundedCornerShape(px(HomePanel.ICON * .34f)))
                    )
                }
                // The reference eases a tile towards the pointer, and a press sinks it back: 180 ms.
                val scale by animateFloatAsState(
                    if (hover) (if (state.pressed) .96f else 1.08f) else 1f,
                    tween(NextDesign.Motion.hoverMs), label = "app"
                )
                val size = HomePanel.ICON * scale
                At(x, y, size, size) { Box(Modifier.shadow(px(14f), RoundedCornerShape(px(size * .28f)))) { Tile(entry, size, state.dark) } }
                entry.badge?.let { badge ->
                    At(x + HomePanel.ICON * .42f, y - HomePanel.ICON * .42f, 50f, 50f) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(look.chosen), contentAlignment = Alignment.Center) {
                            Text(badge, look.onChosen, 30f, weight = FontWeight.Bold)
                        }
                    }
                }
                Label(entry.label, x, y + HomePanel.ICON / 2 + 18f, 29f, look.air, HomePanel.CELL_W - 16f, FontWeight.Medium, lines = 2, shadow = true)
        }
        if (state.searching) Keyboard(state, look)
        else if (state.pages > 1) PageDots(state, look)
        }
    }

    @Composable
    private fun SearchField(state: HomePanel.Snapshot, look: Look) {
        val rect = HomePanel.searchField(state.searching)
        val hover = state.hovered == HomePanel.Target.Control(HomePanel.Control.SEARCH)
        In(rect) {
            Box(
                Modifier.fillMaxSize().glass(look, RoundedCornerShape(50), px(12f))
                    .background(if (hover) look.hover else Color.Transparent)
                    .padding(start = px(28f), end = px(28f)),
                contentAlignment = if (state.searching) Alignment.CenterStart else Alignment.Center
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Icons.Rounded.Search, look.soft, 40f)
                    Box(Modifier.width(px(14f)))
                    val text = if (state.searching && state.query.isNotEmpty()) state.query + "▏" else tr("Search")
                    Text(text, if (state.query.isNotEmpty()) look.ink else look.soft, 34f, weight = FontWeight.Medium)
                }
            }
        }
    }

    private fun tabIcon(tab: HomePanel.Tab): ImageVector = when (tab) {
        HomePanel.Tab.ALL -> Icons.Rounded.Apps
        HomePanel.Tab.APPS -> Icons.Rounded.GridView
        HomePanel.Tab.GAMES -> Icons.Rounded.SportsEsports
        HomePanel.Tab.WEB -> Icons.Rounded.Public
        HomePanel.Tab.PEOPLE -> Icons.Rounded.People
        HomePanel.Tab.ENVIRONMENTS -> Icons.Rounded.Landscape
    }

    /** The section list: the chosen section is the accent wash with its hairline, the rest quiet. */
    @Composable
    private fun Rail(state: HomePanel.Snapshot, look: Look) {
        @Composable
        fun Row(rect: RectF, icon: ImageVector, title: String, chosen: Boolean, hover: Boolean) {
            In(rect) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(50))
                        .background(if (chosen) look.chosenSoft else if (hover) look.hover else Color.Transparent)
                        .then(if (chosen) Modifier.border(Dp.Hairline, look.chosenLine, RoundedCornerShape(50)) else Modifier)
                        .padding(start = px(26f)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(icon, if (chosen) look.chosen else look.ink, 38f)
                        Box(Modifier.width(px(20f)))
                        Text(title, if (chosen) look.ink else look.soft, 32f, weight = if (chosen) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
        // The sections sit on their own glass panel.
        val first = HomePanel.railRow(0)
        val last = HomePanel.railRow(HomePanel.Tab.entries.size, store = true)
        In(RectF(first.left - 18f, first.top - 18f, first.right + 18f, last.bottom + 18f)) {
            Box(Modifier.fillMaxSize().glass(look, RoundedCornerShape(px(34f)), px(18f)))
        }
        val library = state.mode == HomePanel.Mode.HOME || state.mode == HomePanel.Mode.LIBRARY
        HomePanel.Tab.entries.forEachIndexed { index, tab ->
            Row(HomePanel.railRow(index), tabIcon(tab), tr(tab.title), library && !state.searching && tab == state.tab,
                state.hovered == HomePanel.Target.Rail(tab))
        }
        val y = HomePanel.railSeparatorY()
        At((HomePanel.RAIL_LEFT + HomePanel.RAIL_RIGHT) / 2, y + 10f, HomePanel.RAIL_RIGHT - HomePanel.RAIL_LEFT - 40f, 2f) {
            Box(Modifier.fillMaxSize().background(look.tile))
        }
        Row(HomePanel.railRow(HomePanel.Tab.entries.size, store = true), Icons.Rounded.ShoppingBag, tr("Store"),
            state.mode == HomePanel.Mode.STORE, state.hovered == HomePanel.Target.Store)
    }

    /**
     * Room controls under the worlds: tall pills filled from the bottom with the reference's white
     * slider fall, the icon riding on top of the fill in the ink its knobs use.
     */
    @Composable
    private fun Sliders(state: HomePanel.Snapshot, look: Look) {
        for (slider in HomePanel.Slider.entries) {
            val rect = HomePanel.sliderRect(slider.ordinal)
            val value = state.sliders[slider.ordinal]
            val hover = state.hovered == HomePanel.Target.Slider(slider)
            Label(tr(slider.title), rect.centerX(), rect.top - 62f, 30f, look.air, 200f, FontWeight.SemiBold, shadow = true)
            In(rect) {
                Box(Modifier.fillMaxSize().glass(look, RoundedCornerShape(50), px(10f)).background(if (hover) look.hover else Color.Transparent))
            }
            // The fill never gets shorter than its round end, so the icon always has a place.
            val fill = maxOf(rect.width(), rect.height() * value)
            // The reference's slider: a white track filled in white, its knob round and light.
            val level = Brush.verticalGradient(listOf(Color.White, Color(0xFFE3E9F2)))
            At(rect.centerX(), rect.bottom - fill / 2, rect.width(), fill) {
                Box(
                    Modifier.fillMaxSize().shadow(px(8f), RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(level),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val icon = when (slider) {
                        HomePanel.Slider.VOLUME -> Icons.AutoMirrored.Rounded.VolumeUp
                        HomePanel.Slider.BRIGHTNESS -> Icons.Rounded.LightMode
                        HomePanel.Slider.RAIN -> Icons.Rounded.WaterDrop
                        HomePanel.Slider.FOG -> Icons.Rounded.Cloud
                    }
                    Glyph(icon, Color(NextDesign.keyPressedInk), 48f, Modifier.padding(top = px(38f)))
                }
            }
        }
    }

    @Composable
    private fun SortButton(state: HomePanel.Snapshot, look: Look) {
        if (state.mode == HomePanel.Mode.STORE || state.mode == HomePanel.Mode.MENU) return
        val hover = state.hovered == HomePanel.Target.Sort
        In(HomePanel.sortButton()) {
            Box(
                Modifier.fillMaxSize().glass(look, RoundedCornerShape(50), px(12f)).background(if (hover) look.hover else Color.Transparent).padding(start = px(20f)),
                contentAlignment = Alignment.CenterStart
            ) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Icons.Rounded.SwapVert, look.ink, 36f)
                    Box(Modifier.width(px(12f)))
                    Text(tr(state.sort.title), look.ink, 30f, weight = FontWeight.Medium)
                }
            }
        }
    }

    @Composable
    private fun PageDots(state: HomePanel.Snapshot, look: Look) {
        for (i in 0 until state.pages) {
            val hover = state.hovered == HomePanel.Target.Page(i)
            val current = i == state.page
            // The current page is a short bar, the others dots — the reference's carousel.
            At(HomePanel.pageDotX(i, state.pages), HomePanel.PAGE_DOTS_Y, if (current) 36f else 16f, 16f) {
                Box(Modifier.fillMaxSize().shadow(px(4f), CircleShape).clip(CircleShape)
                    .background(if (current) Color.White else if (hover) Color(0xCCFFFFFF) else Color(0x80FFFFFF)))
            }
        }
    }

    /** The search keyboard: glass keys, an accent enter key, app names as suggestions. */
    @Composable
    private fun Keyboard(state: HomePanel.Snapshot, look: Look) {
        In(HomePanel.KEYBOARD) { Box(Modifier.fillMaxSize().glass(look, RoundedCornerShape(px(40f)), px(20f))) }
        for (i in 0 until 3) {
            val rect = HomePanel.suggestion(i)
            val entry = state.suggestions.getOrNull(i)
            if (entry != null) {
                val hover = state.hovered == HomePanel.Target.App(entry)
                In(rect) {
                    Box(Modifier.fillMaxSize().padding(px(10f)).clip(RoundedCornerShape(px(20f))).background(if (hover) look.hover else Color.Transparent))
                }
                Label(entry.label, rect.centerX(), rect.centerY() - 22f, 34f, look.ink, rect.width() - 40f)
            }
            if (i > 0) At(rect.left, rect.centerY(), 2f, rect.height() * .6f) { Box(Modifier.fillMaxSize().background(look.tile)) }
        }
        val panelKeyboard = panel.keyboard()
        for (key in panelKeyboard) {
            val hover = state.hovered == HomePanel.Target.Key(key.id)
            val enter = key.id == HomePanel.KEY_ENTER
            In(key.rect) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(px(16f)))
                        .background(if (enter) (if (hover) Color(NextDesign.primaryPressed) else Color(NextDesign.primary)) else if (hover) look.hover else look.tile),
                    contentAlignment = Alignment.Center
                ) {
                    val ink = if (enter) Color.White else look.ink
                    when (key.id) {
                        HomePanel.KEY_BACKSPACE -> Glyph(Icons.AutoMirrored.Rounded.Backspace, ink, 40f)
                        HomePanel.KEY_ENTER -> Glyph(Icons.AutoMirrored.Rounded.ArrowForward, ink, 44f)
                        HomePanel.KEY_LANGUAGE -> Glyph(Icons.Rounded.Language, ink, 40f)
                        HomePanel.KEY_HIDE -> Glyph(Icons.Rounded.KeyboardHide, ink, 40f)
                        HomePanel.KEY_SPACE -> Text(key.label, look.soft, 24f)
                        else -> Text(key.label, ink, if (key.label.length > 1) 30f else 40f, weight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ quick settings

    /**
     * The control centre: separate glass tiles — the status (battery, date) and "Settings", volume
     * and brightness, big tiles (Wi‑Fi, Bluetooth, car mode, computer) and a row of round-cornered
     * buttons. What is on wears the accent wash with the accent hairline.
     */
    @Composable
    private fun Quick(state: HomePanel.Snapshot, look: Look) {
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(NextDesign.Motion.windowMs)) }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 36f
            }
        ) {
            val context = LocalContext.current
            var battery by remember { mutableStateOf(battery(context)) }
            var wifi by remember { mutableStateOf(wifi(context)) }
            var bluetooth by remember { mutableStateOf(bluetooth(context)) }
            LaunchedEffect(Unit) { while (true) { delay(5_000); battery = battery(context); wifi = wifi(context); bluetooth = bluetooth(context) } }
            @Composable
            fun Tile(rect: RectF, hover: Boolean = false, active: Boolean = false, content: @Composable BoxScope.() -> Unit) {
                In(rect) {
                    Box(
                        Modifier.fillMaxSize()
                            .shadow(px(12f), RoundedCornerShape(px(30f)), ambientColor = Color(NextDesign.shadow), spotColor = Color(NextDesign.shadow))
                            .clip(RoundedCornerShape(px(30f)))
                            .background(if (active) look.chosenSoft else if (hover) look.cardHover else look.card)
                            .border(Dp.Hairline, if (active) look.chosenLine else look.edge, RoundedCornerShape(px(30f))),
                        content = content
                    )
                }
            }
            // Status: battery and the date.
            Tile(HomePanel.QUICK_STATUS) {
                val date = java.text.SimpleDateFormat("EEE, d MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(start = px(36f)), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔋 ${battery.first}%" + if (battery.second) " ⚡" else "", look.ink, 30f, weight = FontWeight.Medium)
                    Box(Modifier.width(px(36f)))
                    Text(date.replaceFirstChar { it.uppercase() }, look.soft, 30f)
                }
            }
            QuickTile(HomePanel.Quick.SETTINGS, state, look) { _ ->
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Icons.Rounded.Settings, look.ink, 38f)
                    Box(Modifier.width(px(12f)))
                    Text(tr("Settings"), look.ink, 30f, weight = FontWeight.Medium)
                }
            }
            // Volume and brightness: white sliders with a round knob, as on Quest.
            for ((slider, icon) in listOf(HomePanel.Slider.VOLUME to Icons.AutoMirrored.Rounded.VolumeUp, HomePanel.Slider.BRIGHTNESS to Icons.Rounded.LightMode)) {
                val hover = state.hovered == HomePanel.Target.Slider(slider)
                Tile(HomePanel.quickSliderTile(slider), hover) {}
                val track = HomePanel.quickSlider(slider)
                val value = state.sliders[slider.ordinal]
                In(track) { Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(look.tile)) }
                val fill = track.width() * value
                At(track.left + fill / 2, track.centerY(), fill.coerceAtLeast(1f), track.height()) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(look.chosen))
                }
                // The reference's sliders are white with a round knob, whatever the accent is.
                At(track.left + fill, track.centerY(), 64f, 64f) {
                    Box(Modifier.fillMaxSize().shadow(px(6f), CircleShape).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Glyph(icon, Color(0xFF1B2338), 36f)
                    }
                }
            }
            // Big tiles.
            val big = listOf(
                Triple(HomePanel.Quick.WIFI, Icons.Rounded.Wifi, if (wifi) tr("Connected") else tr("Not connected")),
                Triple(HomePanel.Quick.BLUETOOTH, Icons.Rounded.Bluetooth, if (bluetooth) tr("On") else tr("Off")),
                Triple(HomePanel.Quick.CAR, Icons.Rounded.DirectionsCar, if (state.car) tr("On") else tr("Off")),
                Triple(HomePanel.Quick.DESKTOP, Icons.Rounded.Computer, tr("Stream from the computer")),
            )
            for ((item, icon, detail) in big) {
                QuickTile(item, state, look, active = item == HomePanel.Quick.CAR && state.car) { _ ->
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(start = px(28f)), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(icon, look.ink, 40f)
                        Box(Modifier.width(px(20f)))
                        androidx.compose.foundation.layout.Column {
                            Text(tr(item.title), look.ink, 34f, weight = FontWeight.SemiBold)
                            Text(detail, look.soft, 24f)
                        }
                    }
                }
        }
        // Buttons: icons only; the accent when on.
        val on = mapOf(HomePanel.Quick.PASSTHROUGH to state.passthrough, HomePanel.Quick.DND to state.dnd)
        val small = listOf(
            HomePanel.Quick.PASSTHROUGH to Icons.Rounded.Vrpano, HomePanel.Quick.RECENTER to Icons.Rounded.CenterFocusStrong,
            HomePanel.Quick.DND to Icons.Rounded.DoNotDisturbOn, HomePanel.Quick.PHOTO to Icons.Rounded.PhotoCamera,
        )
        for ((item, icon) in small) {
            val active = on[item] == true
            QuickTile(item, state, look, active = active) { _ -> Glyph(icon, if (active) look.chosen else look.ink, 44f) }
        }
        }
    }

    /** A tile of the control centre: lighter under the pointer, the accent wash when it is on. */
    @Composable
    private fun QuickTile(item: HomePanel.Quick, state: HomePanel.Snapshot, look: Look, active: Boolean = false, content: @Composable (Boolean) -> Unit) {
        val hover = state.hovered == HomePanel.Target.Quick(item)
        In(HomePanel.quickRect(item)) {
            Box(
                Modifier.fillMaxSize()
                        .shadow(px(12f), RoundedCornerShape(px(30f)), ambientColor = Color(NextDesign.shadow), spotColor = Color(NextDesign.shadow))
                        .clip(RoundedCornerShape(px(30f)))
                        .background(if (active) look.chosenSoft else if (hover) look.cardHover else look.card)
                        .border(Dp.Hairline, if (active) look.chosenLine else look.edge, RoundedCornerShape(px(30f))),
                contentAlignment = Alignment.Center
            ) { content(hover) }
        }
    }

    private fun bluetooth(context: Context): Boolean = runCatching {
        context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled == true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ the dock

    @Composable
    private fun Dock(state: HomePanel.Snapshot, look: Look) {
        // The reference's dock rises into place once, when the home arrives.
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            appear.animateTo(1f, tween(NextDesign.Motion.dockMs, easing = CubicBezierEasing(.2f, .75f, .2f, 1f)))
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 60f
            }
        ) {
            val s = HomePanel.DOCK_SCALE
            val cy = HomePanel.DOCK_CY
            // The reference's dock: a slate-blue capsule with a lit top edge, not a plain glass bar.
            val bar = RoundedCornerShape(px(HomePanel.DOCK_H * .38f))
            At(HomePanel.WIDTH / 2f, cy, HomePanel.DOCK_W, HomePanel.DOCK_H) {
                Box(
                    Modifier.fillMaxSize()
                        .shadow(px(26f), bar, ambientColor = Color(NextDesign.shadow), spotColor = Color(NextDesign.shadow))
                        .clip(bar)
                        .background(if (state.dark) NextDesign.dockBrush else Brush.verticalGradient(listOf(look.top, look.bottom)))
                        .border(Dp.Hairline, Color(NextDesign.dockStroke), bar)
                )
            }
            // The hairline of light that runs along the top of the reference's dock.
            At(HomePanel.WIDTH / 2f, HomePanel.DOCK_TOP + 2f, HomePanel.DOCK_W - 48f * s, 3f) {
                Box(Modifier.fillMaxSize().background(NextDesign.dockHighlightBrush))
            }
            fun x(design: Float) = HomePanel.dockX(design)

            @Composable
            fun Round(at: Float, target: HomePanel.Target, selected: Boolean = false, content: @Composable () -> Unit) {
                val hover = state.hovered == target
                At(x(at), cy, 46f * s, 46f * s) {
                    Box(
                        Modifier.fillMaxSize().clip(CircleShape)
                            .background(if (selected) look.chosenSoft else if (hover) look.hover else Color.Transparent)
                            .then(if (selected) Modifier.border(Dp.Hairline, look.chosenLine, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center
                    ) { content() }
                }
            }

            // Profile, with the green "online" dot.
            Round(HomePanel.AVATAR_AT, HomePanel.Target.Control(HomePanel.Control.PROFILE)) {
                Avatar(44f * s, look)
            }
            At(x(HomePanel.AVATAR_AT + 15f), cy + 15f * s, 13f * s, 13f * s) {
                // The reference rings the online dot in the colour of the dock behind it.
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(NextDesign.dockBottom)).padding(px(2f * s)).clip(CircleShape).background(look.online))
            }

            // Status: time, Wi‑Fi, battery (opens quick settings).
            val statusHover = state.hovered == HomePanel.Target.Control(HomePanel.Control.STATUS)
            if (statusHover || state.mode == HomePanel.Mode.QUICK) At((x(HomePanel.STATUS_FROM) + x(HomePanel.STATUS_TO)) / 2, cy, x(HomePanel.STATUS_TO) - x(HomePanel.STATUS_FROM), 46f * s) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(look.hover))
            }
            Status(look)

            Round(HomePanel.BELL_AT, HomePanel.Target.Control(HomePanel.Control.NOTIFICATIONS)) {
                Glyph(Icons.Rounded.Notifications, look.soft, 24f * s)
            }
            if (state.alerts > 0) At(x(HomePanel.BELL_AT + 9f), cy - 9f * s, 16f * s, 16f * s) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(look.danger), contentAlignment = Alignment.Center) {
                    Text(state.alerts.coerceAtMost(9).toString(), Color.White, 11f * s, weight = FontWeight.Bold)
                }
            }
            Round(HomePanel.SEARCH_AT, HomePanel.Target.Control(HomePanel.Control.SEARCH), selected = state.searching) {
                Glyph(Icons.Rounded.Search, if (state.searching) look.chosen else look.soft, 25f * s)
            }
            // Passthrough: the room through the camera or the chosen world.
            Round(HomePanel.PASSTHROUGH_AT, HomePanel.Target.Control(HomePanel.Control.PASSTHROUGH), selected = !state.passthrough) {
                Glyph(Icons.Rounded.Vrpano, if (!state.passthrough) look.chosen else look.soft, 25f * s)
            }

            // The reference separates the dock's groups with a hairline that fades at both ends.
            val dividerBrush = Brush.verticalGradient(
                listOf(NextDesign.dividerColor.copy(alpha = 0f), NextDesign.dividerColor, NextDesign.dividerColor.copy(alpha = 0f))
            )
            for (divider in HomePanel.DIVIDERS) At(x(divider), cy, 2f * s / 1.5f, 38f * s) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(dividerBrush))
            }

            @Composable
            fun App(at: Float, entry: HomePanel.Entry) {
                val hover = state.hovered == HomePanel.Target.App(entry)
                val size = 44f * s * (if (hover) (if (state.pressed) .95f else 1.1f) else 1f)
                At(x(at), cy, size, size) {
                    // The reference lifts a dock tile towards the user under the pointer; in VR that
                    // reads as a slightly larger icon, which is what the size above already does.
                    Box(Modifier.shadow(px(8f), RoundedCornerShape(px(size * .28f)))) { Tile(entry, size, state.dark) }
                }
            }
            state.pinned.forEachIndexed { i, entry -> App(HomePanel.PINNED_AT + i * HomePanel.SLOT, entry) }
            state.recent.forEachIndexed { i, entry -> App(HomePanel.RECENT_AT + i * HomePanel.SLOT, entry) }

            val libraryOpen = state.library && !state.searching && state.mode != HomePanel.Mode.STORE && state.mode != HomePanel.Mode.MENU
            Round(HomePanel.LIBRARY_AT, HomePanel.Target.Library, selected = libraryOpen) {
                Glyph(Icons.Rounded.Interests, if (libraryOpen) look.chosen else look.soft, 28f * s)
            }
        }
    }

    /** The profile: the reference's periwinkle-to-indigo circle with the person on it. */
    @Composable
    private fun Avatar(size: Float, look: Look) {
        Box(
            Modifier.size(px(size)).clip(CircleShape).background(NextDesign.profileBrush)
                .border(Dp.Hairline, Color(0x66FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Glyph(Icons.Rounded.People, Color.White, size * .55f)
        }
    }

    /** Time, Wi‑Fi and the battery, read from the phone every few seconds. */
    @Composable
    private fun Status(look: Look) {
        val context = LocalContext.current
        var now by remember { mutableStateOf(clock()) }
        var battery by remember { mutableStateOf(battery(context)) }
        var wifi by remember { mutableStateOf(wifi(context)) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(5_000)
                now = clock(); battery = battery(context); wifi = wifi(context)
            }
        }
        val s = HomePanel.DOCK_SCALE
        val cy = HomePanel.DOCK_CY
        with(LocalDensity.current) {
            Text(now, look.ink, 19f * s,
                Modifier.offset(HomePanel.dockX(HomePanel.CLOCK_AT).toDp(), (cy - 12.5f * s).toDp()), FontWeight.SemiBold)
        }
        At(HomePanel.dockX(HomePanel.WIFI_AT), cy, 24f * s, 24f * s) {
            Glyph(if (wifi) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, look.soft, 22f * s)
        }
        // The battery as the reference's status strip draws it: an outline with the charge filled in.
        At(HomePanel.dockX(HomePanel.BATTERY_AT), cy, 24f * s, 14f * s) {
            val ink = look.batteryInk
            val low = battery.first in 0..15 && !battery.second
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.height * .12f
                val bodyW = size.width * .88f
                drawRoundRect(ink, Offset(stroke / 2, stroke / 2), Size(bodyW - stroke, size.height - stroke),
                    CornerRadius(size.height * .22f), style = Stroke(stroke))
                drawRoundRect(ink, Offset(bodyW + stroke * .3f, size.height * .32f), Size(size.width - bodyW - stroke * .3f, size.height * .36f), CornerRadius(stroke))
                val level = (battery.first.coerceIn(0, 100)) / 100f
                val inner = stroke * 2
                drawRoundRect(if (low) Color(NextDesign.alert) else if (battery.second) Color(NextDesign.online) else ink,
                    Offset(inner, inner), Size((bodyW - inner * 2) * level, size.height - inner * 2), CornerRadius(stroke * .6f))
            }
        }
    }

    private fun clock() = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    /** Percent and whether it is charging. */
    private fun battery(context: Context): Pair<Int, Boolean> = runCatching {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        Pair(if (level < 0) 100 else level * 100 / scale.coerceAtLeast(1),
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }.getOrDefault(Pair(100, false))

    private fun wifi(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)
}
