package com.samrat.cardboardhands

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import zone.ien.hig.CupertinoSwitch
import zone.ien.hig.CupertinoText
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.section.SectionItem
import zone.ien.hig.theme.CupertinoColors
import zone.ien.hig.theme.systemGreen
import zone.ien.hig.theme.systemRed
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.acos
import kotlin.math.roundToInt

/** Teaches PhoneXR the colour and shape of the Joy-Con and shows how the camera sees them. */
class JoyConCameraActivity : ComponentActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    /** One thread owns the vision buffers: frame processing and the "remember" buttons alike. */
    private val visionExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val vision = JoyConVision()
    @Volatile private var latest: Bitmap? = null

    private var state by mutableStateOf(Settings.State())
    private var preview by mutableStateOf<Bitmap?>(null)
    private var left by mutableStateOf(JoyConVision.Detection())
    private var right by mutableStateOf(JoyConVision.Detection())
    private var message by mutableStateOf<String?>(null)

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) bindCamera() else message = "Camera access is needed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = Settings.load(this)
        // The tracking service holds the camera; this screen needs it for itself.
        stopService(Intent(this, HandTrackingService::class.java))
        setContent { PhoneXRTheme { Screen() } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraExecutor.shutdownNow()
        visionExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun update(next: Settings.State) {
        state = next
        Settings.save(this, next)
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (busy.compareAndSet(false, true)) {
                        val upright = image.toBitmap().rotate(image.imageInfo.rotationDegrees)
                        val frame = Bitmap.createScaledBitmap(upright, 320, 320 * upright.height / upright.width, true)
                        if (frame !== upright) upright.recycle()
                        visionExecutor.execute {
                            try {
                                val current = state
                                val found = vision.process(frame, current.leftColor, current.rightColor)
                                latest = frame
                                runOnUiThread {
                                    preview = frame
                                    left = found.first
                                    right = found.second
                                }
                            } finally {
                                busy.set(false)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    busy.set(false)
                } finally {
                    image.close()
                }
            }
            val provider = future.get()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rememberColor(isLeft: Boolean) {
        visionExecutor.execute {
            val frame = latest ?: return@execute
            val target = vision.sample(frame)
            runOnUiThread {
                if (target == null) {
                    message = "No bright color found inside the square. Gray and black Joy‑Con cannot be tracked by color."
                } else {
                    val shaped = target.copy(sideRatio = (if (isLeft) state.leftColor else state.rightColor).sideRatio)
                    update(if (isLeft) state.copy(leftColor = shaped) else state.copy(rightColor = shaped))
                    message = "The color of the ${if (isLeft) "left" else "right"} Joy‑Con is remembered"
                }
            }
        }
    }

    private fun rememberShape(isLeft: Boolean) {
        visionExecutor.execute {
            val frame = latest ?: return@execute
            val target = if (isLeft) state.leftColor else state.rightColor
            val ratio = vision.measureSideRatio(frame, target)
            runOnUiThread {
                if (ratio == null) {
                    message = "The Joy‑Con is not visible from the side. Keep all of it in frame, long side towards the camera."
                } else {
                    val shaped = target.copy(sideRatio = ratio)
                    update(if (isLeft) state.copy(leftColor = shaped) else state.copy(rightColor = shaped))
                    message = "Shape remembered: the length is ${"%.1f".format(ratio)} times the thickness"
                }
            }
        }
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun Screen() {
        HigPage(
            title = "Joy‑Con via camera",
            onBack = ::finish,
            subtitle = "The camera finds the Joy‑Con by color: where it is and where it points. Buttons and sticks come from the Joy‑Con itself."
        ) {
            CameraPreview()

            HigSection(footer = message) {
                HigSwitchRow("Track Joy‑Con with the camera", state.cameraJoyCons) { update(state.copy(cameraJoyCons = it)) }
                HigRow("Left", describe(left), detailColor = if (left.found) HigColors.good else HigColors.bad)
                HigRow("Right", describe(right), detailColor = if (right.found) HigColors.good else HigColors.bad)
            }

            HigSection(
                title = "Color",
                footer = "Hold the Joy‑Con so that it covers the square in the center of the frame, then tap. " +
                    "If both Joy‑Con are the same color, the one further left in the frame counts as the left one."
            ) {
                HigLink("Remember the left color") { rememberColor(isLeft = true) }
                HigLink("Remember the right color") { rememberColor(isLeft = false) }
                HigLink("Neon blue and red") {
                    update(state.copy(leftColor = JoyConVision.Target.NEON_BLUE, rightColor = JoyConVision.Target.NEON_RED))
                    message = "Default colors restored"
                }
            }

            HigSection(
                title = "Left Joy‑Con color",
                footer = "Pick the color of your Joy‑Con — then there is nothing to teach the camera. Gray and white Joy‑Con " +
                    "are not in the list: the camera finds them by color and they have none — for those use “remember the color”."
            ) {
                JoyConVision.Target.PRESETS.forEach { (title, colour) ->
                    HigChoice(title, null, sameColour(state.leftColor, colour)) {
                        update(state.copy(leftColor = colour))
                        message = "Left Joy‑Con: $title"
                    }
                }
            }

            HigSection(title = "Right Joy‑Con color") {
                JoyConVision.Target.PRESETS.forEach { (title, colour) ->
                    HigChoice(title, null, sameColour(state.rightColor, colour)) {
                        update(state.copy(rightColor = colour))
                        message = "Right Joy‑Con: $title"
                    }
                }
            }

            HigSection(
                title = "Shape",
                footer = "Needed for an accurate tilt. Keep the whole Joy‑Con in frame, long side towards the camera, and tap."
            ) {
                HigLink("Remember the left shape") { rememberShape(isLeft = true) }
                HigLink("Remember the right shape") { rememberShape(isLeft = false) }
            }

            HigSection(
                footer = "Limits: light and colored Joy‑Con are required, the camera does not see rotation around the Joy‑Con's own axis, " +
                    "and outside the frame a Joy‑Con stays at its last place. If the Joy‑Con gyro starts working, " +
                    "rotation is taken from it automatically."
            ) {}
        }
    }

    /** The chosen colour, when it is one of the ready-made ones rather than a taught one. */
    private fun sameColour(target: JoyConVision.Target, preset: JoyConVision.Target) =
        JoyConVision.Target.nameOf(target) != null && JoyConVision.Target.nameOf(target) == JoyConVision.Target.nameOf(preset)

    @Composable
    private fun CameraPreview() {
        val frame = preview
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .aspectRatio(if (frame != null) frame.width.toFloat() / frame.height else 4f / 3f)
                .clip(RoundedCornerShape(14.dp))
        ) {
            if (frame != null) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val square = minOf(size.width, size.height) / 6
                drawRect(
                    Color.White,
                    topLeft = Offset(size.width / 2 - square / 2, size.height / 2 - square / 2),
                    size = Size(square, square),
                    style = Stroke(3f)
                )
                listOf(left to state.leftColor, right to state.rightColor).forEach { (detection, target) ->
                    if (!detection.found) return@forEach
                    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(target.hue, 1f, 1f)))
                    val center = Offset(detection.x * size.width, detection.y * size.height)
                    drawCircle(Color.White, radius = 14f, center = center)
                    drawCircle(color, radius = 10f, center = center)
                    // The arrow is the pointing direction seen from the camera: long when tilted, short when aimed away.
                    val reach = size.minDimension * .35f
                    drawLine(
                        color,
                        start = center,
                        end = Offset(center.x + detection.dirX * reach, center.y - detection.dirY * reach),
                        strokeWidth = 8f
                    )
                }
            }
        }
    }

    private fun describe(detection: JoyConVision.Detection): String {
        if (!detection.found) return "not visible"
        val fromForward = Math.toDegrees(acos((-detection.dirZ).coerceIn(-1f, 1f).toDouble())).roundToInt()
        val distance = ((0.08f - detection.thickness) / 0.06f).coerceIn(0f, 1f)
        return "visible · ${fromForward}° off “forward” · distance ${(distance * 100).roundToInt()}%"
    }
}
