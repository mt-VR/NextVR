package com.samrat.cardboardhands

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import zone.ien.hig.theme.CupertinoColors
import zone.ien.hig.theme.CupertinoTheme
import zone.ien.hig.theme.systemGreen
import zone.ien.hig.theme.systemRed
import kotlin.math.min
import kotlin.math.roundToInt

/** Checks the Joy-Con motion sensors: whether Android exposes them and whether data really arrives. */
class GyroTestActivity : ComponentActivity() {
    private var tracker: JoyConTracker? = null
    private var left by mutableStateOf(JoyConTracker.Pose())
    private var right by mutableStateOf(JoyConTracker.Pose())
    private var leftMotion by mutableStateOf(JoyConTracker.Motion())
    private var rightMotion by mutableStateOf(JoyConTracker.Motion())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhoneXRTheme { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        tracker = JoyConTracker(this)
    }

    override fun onStop() {
        super.onStop()
        tracker?.close()
        tracker = null
    }

    @Composable
    private fun Screen() {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { }
                tracker?.let {
                    left = it.pose(left = true)
                    right = it.pose(left = false)
                    leftMotion = it.motion(left = true)
                    rightMotion = it.motion(left = false)
                }
            }
        }
        HigPage(
            title = "Joy‑Con gyro",
            onBack = ::finish,
            subtitle = "Turn the Joy‑Con — the cube repeats the rotation, and the rotation speed shows the data is coming in."
        ) {
            if (Build.VERSION.SDK_INT < 31) {
                HigSection(footer = "Android only hands Joy‑Con sensors to apps from Android 12 on.") {
                    HigRow("Needs Android 12 or newer", detailColor = HigColors.bad)
                }
                return@HigPage
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Cube(left, leftMotion, Modifier.weight(1f))
                Cube(right, rightMotion, Modifier.weight(1f))
            }
            JoyConSection("Left Joy‑Con", leftMotion)
            JoyConSection("Right Joy‑Con", rightMotion)
            HigButton("Recenter", filled = false) { tracker?.recenter() }
            HigSection(
                footer = "Hold both Joy‑Con straight and tap “Recenter”. If no sensors are found, the phone kernel " +
                    "does not expose the Joy‑Con gyro (that happens, for example, on Samsung with kernel 5.10) — " +
                    "then hand rotation comes from the camera only."
            ) {}
        }
    }

    @Composable
    private fun JoyConSection(title: String, motion: JoyConTracker.Motion) {
        val verdict = verdict(motion)
        HigSection(title = title) {
            HigRow("State", verdict.first, detailColor = verdict.second)
            HigRow("Sensors", motion.sensors.ifEmpty { listOf("none") }.joinToString(", "))
            HigRow("Rate", "${motion.rateHz.roundToInt()} Hz · ${motion.events} events")
            HigRow("Rotation speed", "${motion.degreesPerSecond.roundToInt()}°/s")
        }
    }

    @Composable
    private fun verdict(motion: JoyConTracker.Motion): Pair<String, Color> = when {
        !motion.connected -> "Not connected — pair it over Bluetooth" to HigColors.secondary
        motion.sensors.none { it == "gyroscope" || it == "orientation" } ->
            "Connected, but there is no gyro" to HigColors.bad
        motion.rateHz < 1f -> "There is a gyro, but no data arrives" to HigColors.bad
        else -> "The gyro works" to HigColors.good
    }

    @Composable
    private fun Cube(pose: JoyConTracker.Pose, motion: JoyConTracker.Motion, modifier: Modifier) {
        val working = motion.rateHz >= 1f
        val color = if (working) HigColors.accent else CupertinoTheme.colorScheme.tertiaryLabel
        val accent = if (working) HigColors.good else CupertinoTheme.colorScheme.quaternaryLabel
        Canvas(modifier = modifier.aspectRatio(1f)) {
            val scale = min(size.width, size.height) * .22f
            val center = Offset(size.width / 2f, size.height / 2f)
            val projected = corners.map { corner ->
                val (x, y, z) = rotate(pose, corner[0], corner[1], corner[2] * .55f)
                // Simple perspective: points further away are drawn closer to the centre.
                val depth = 4.5f / (4.5f - z)
                Offset(center.x + x * scale * depth, center.y - y * scale * depth)
            }
            edges.forEach { edge ->
                drawLine(
                    color = if (edge in front) accent else color,
                    start = projected[edge.first],
                    end = projected[edge.second],
                    strokeWidth = if (edge in front) 6f else 4f
                )
            }
            drawCircle(color.copy(alpha = .15f), radius = scale * 2.2f, center = center, style = Stroke(2f))
        }
    }
}

private val corners = listOf(
    floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
    floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f)
)
private val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
/** The front face is drawn in the accent colour so the direction the Joy-Con points is visible. */
private val front = setOf(4 to 5, 5 to 6, 6 to 7, 7 to 4)

/** Rotates a point by the Joy-Con quaternion. */
private fun rotate(pose: JoyConTracker.Pose, px: Float, py: Float, pz: Float): Triple<Float, Float, Float> {
    val (qx, qy, qz, qw) = listOf(pose.x, pose.y, pose.z, pose.w)
    val tx = 2f * (qy * pz - qz * py)
    val ty = 2f * (qz * px - qx * pz)
    val tz = 2f * (qx * py - qy * px)
    return Triple(
        px + qw * tx + (qy * tz - qz * ty),
        py + qw * ty + (qz * tx - qx * tz),
        pz + qw * tz + (qx * ty - qy * tx)
    )
}
