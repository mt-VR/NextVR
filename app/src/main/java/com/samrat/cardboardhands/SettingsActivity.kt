package com.samrat.cardboardhands

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    private var state by mutableStateOf(Settings.State())
    private var live by mutableStateOf(JoyConBridge.Snapshot())
    /** Key code waiting for an action, or "learning" while the user presses a button. */
    private var pickedKey by mutableStateOf<Int?>(null)
    private var learning by mutableStateOf(false)
    private var receiver: android.content.BroadcastReceiver? = null
    /** Re-read on resume: the user comes back from accessibility settings. */
    private var interceptEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = Settings.load(this)
        rootJoyCons = Settings.rootJoyCons(this)
        setContent { PhoneXRTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        // The camera Joy-Con screen may have changed settings.
        state = Settings.load(this)
        interceptEnabled = JoyConInputService.isEnabled(this)
    }

    override fun onStart() {
        super.onStart()
        receiver = JoyConBridge.listen(this) { snapshot ->
            live = snapshot
            val learned = snapshot.learnedKey
            if (learning && learned != null) {
                learning = false
                pickedKey = learned
                JoyConBridge.watch(this, watching = true, learning = false)
            }
        }
        JoyConBridge.watch(this, watching = true, learning = false)
    }

    override fun onStop() {
        super.onStop()
        JoyConBridge.watch(this, watching = false, learning = false)
        receiver?.let { unregisterReceiver(it) }
        receiver = null
    }

    private var rootJoyCons by androidx.compose.runtime.mutableStateOf(false)
    private var rootStatus by androidx.compose.runtime.mutableStateOf<String?>(null)

    private fun update(next: Settings.State) {
        state = next
        Settings.save(this, next)
    }

    @Composable
    private fun Screen() {
        HigPage(title = "Controls", onBack = ::finish) {
            HigSection(
                title = "Tracking",
                footer = "3DoF works on any phone. 6DoF adds movement through the room when ARCore is " +
                    "available; otherwise the headset keeps rotation tracking only."
            ) {
                Settings.SixDofMode.entries.forEach { mode ->
                    val reason = SixDofSupport.unavailableReason(this@SettingsActivity, mode)
                    if (reason == null) {
                        HigChoice(mode.title, mode.description, mode == state.sixDofMode) { update(state.copy(sixDofMode = mode)) }
                    } else {
                        // Greyed out, with the reason: nothing here hides a mode the phone cannot do.
                        HigRow(mode.title, reason, detailColor = HigColors.secondary)
                    }
                }
            }

            HigSection(title = "Hands") {
                HigChoice(
                    "Gestures press",
                    "A pinch and a fist work as controller buttons",
                    state.handMode == Settings.HandMode.CONTROLLERS
                ) { update(state.copy(handMode = Settings.HandMode.CONTROLLERS)) }
                HigChoice(
                    "Hands only",
                    "The game gets hands without presses",
                    state.handMode == Settings.HandMode.HANDS
                ) { update(state.copy(handMode = Settings.HandMode.HANDS)) }
            }

            HigSection(
                title = "Joy‑Con",
                footer = if (interceptEnabled) "Joy‑Con are tracked by the hand holding them: position and rotation come from the hand, " +
                    "buttons and the stick from the Joy‑Con. Nothing to set up."
                else "Without interception, Joy‑Con buttons go to the game as a gamepad, not as VR controllers. " +
                    "Turn on “NextVR Joy‑Con” in Accessibility."
            ) {
                HigRow(
                    "Button interception",
                    if (interceptEnabled) "On" else "Off",
                    detailColor = if (interceptEnabled) HigColors.good else HigColors.bad
                )
                if (!interceptEnabled) {
                    HigLink("Turn on interception") { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
            HigSection(
                title = "Controllers",
                footer = "NextVR accepts any gamepad: Joy‑Con, DualShock, Xbox and no-name ones. " +
                    "A single gamepad works as both hands: the left stick and X/Y/L are the left hand, the right stick and A/B/R the right one."
            ) {
                val found = JoyConButtons.names()
                if (found.isEmpty()) HigRow("Nothing connected", "Pair a gamepad over Bluetooth", detailColor = HigColors.secondary)
                else found.forEach { name -> HigRow(name, "Connected", detailColor = HigColors.good) }
            }

            HigSection(
                title = "It's",
                footer = if (!JoyConInputService.sticksSupported) "The stick is only read on Android 14 and newer." else null
            ) {
                HigRow("Left stick", stick(live.left))
                HigRow("Right stick", stick(live.right))
                HigRow("Left hand", pressed(live.left.buttons))
                HigRow("Right hand", pressed(live.right.buttons))
            }

            HigSection {
                HigLink("Assign by pressing a button") {
                    learning = true
                    JoyConBridge.watch(this@SettingsActivity, watching = true, learning = true)
                }
                HigLink("Reset the layout") { update(state.copy(bindings = Settings.defaults().bindings)) }
            }
        }

        if (learning) LearningDialog()
        pickedKey?.let { ActionDialog(it) }
    }

    /** Joy-Con rotation needs a gyroscope, and not every Android kernel exposes one. */
    @Composable
    private fun gyroStatus(): Pair<String, androidx.compose.ui.graphics.Color> {
        val secondary = HigColors.secondary
        if (android.os.Build.VERSION.SDK_INT < 31) return "Needs Android 12 or newer" to HigColors.bad
        val devices = android.view.InputDevice.getDeviceIds().toList()
            .mapNotNull { id -> android.view.InputDevice.getDevice(id) }
            .filter { device -> JoyConButtons.isJoyCon(device) }
        if (devices.isEmpty()) return "No Joy‑Con found — pair them over Bluetooth" to secondary
        val withGyro = devices.count { device ->
            device.sensorManager.getSensorList(android.hardware.Sensor.TYPE_ALL).any { sensor ->
                sensor.type == android.hardware.Sensor.TYPE_GYROSCOPE ||
                    sensor.type == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR ||
                    sensor.type == android.hardware.Sensor.TYPE_ROTATION_VECTOR
            }
        }
        return if (withGyro > 0) "$withGyro of ${devices.size} have one: hand rotation works without the camera" to HigColors.good
        else "Unavailable — hand rotation only comes from the camera" to HigColors.bad
    }

    private fun stick(live: JoyConButtons.Live) =
        "forward ${(live.stickY * 100).roundToInt()}%, sideways ${(live.stickX * 100).roundToInt()}%"

    private fun pressed(mask: Int) =
        Settings.Action.entries.filter { it.bit != 0 && mask and it.bit != 0 }
            .joinToString(", ") { it.title }
            .ifEmpty { "nothing pressed" }

    @Composable
    private fun LearningDialog() {
        val stop = {
            learning = false
            JoyConBridge.watch(this, watching = true, learning = false)
        }
        HigAlert(
            title = "Press a button on the Joy‑Con",
            message = "NextVR is waiting for a press. Then choose what that button does in VR.",
            actions = listOf(HigAction(tr("Cancel"), HigActionStyle.CANCEL, stop)),
            onDismiss = stop
        )
    }

    @Composable
    private fun ActionDialog(keyCode: Int) {
        val actions = Settings.Action.entries.map { action ->
            HigAction(action.title + if (state.bindings[keyCode] == action) " ✓" else "") {
                update(state.copy(bindings = state.bindings + (keyCode to action)))
                pickedKey = null
            }
        } + HigAction(tr("Cancel"), HigActionStyle.CANCEL) { pickedKey = null }
        HigAlert(
            title = "Button ${Settings.keyName(keyCode)}",
            message = "What it does in VR:",
            actions = actions,
            onDismiss = { pickedKey = null }
        )
    }
}
