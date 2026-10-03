package com.samrat.xrbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zone.ien.hig.CupertinoButton
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.theme.CupertinoTheme
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "XR Bridge"
    ) {
        CupertinoTheme { InstallerScreen() }
    }
}

@OptIn(ExperimentalCupertinoApi::class)
@Composable
private fun InstallerScreen() {
    val controller = remember { AdbController() }
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<Device?>(null) }
    var selectedApk by remember { mutableStateOf<File?>(null) }
    var status by remember { mutableStateOf("Connect an Android phone over USB and turn on USB debugging.") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            busy = true
            val found = withContext(Dispatchers.IO) { controller.devices() }
            devices = found
            selectedDevice = found.firstOrNull()
            status = when {
                found.isEmpty() -> "No phone found. Check the cable and the USB debugging prompt."
                controller.hasOpenXrRuntime(found.first().serial) -> "The device is ready. An OpenXR runtime was found."
                else -> "The phone is connected, but no OpenXR runtime was found. The APK can be installed, but the XR game may not start."
            }
            busy = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF11131A), Color(0xFF252136), Color(0xFF171922)))
        ).padding(28.dp)
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            GlassCard(Modifier.width(280.dp).fillMaxSize()) {
                TextLabel("XR Bridge", 30, Color.White)
                TextLabel("Installing Android XR games", 14, Color(0xFFB8B8C8))
                Spacer(Modifier.height(26.dp))
                TextLabel("DEVICE", 12, Color(0xFF9898A8))
                Spacer(Modifier.height(8.dp))
                TextLabel(selectedDevice?.model ?: "Not connected", 20, Color.White)
                TextLabel(selectedDevice?.serial ?: "—", 12, Color(0xFFB8B8C8))
                Spacer(Modifier.height(18.dp))
                CupertinoButton(onClick = { refresh() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.material3.Text(if (busy) "Checking…" else "Refresh devices")
                }
                Spacer(Modifier.height(20.dp))
                TextLabel("Important", 15, Color.White)
                TextLabel(
                    "Only Android APKs are installed. PCVR EXE and SteamVR games do not run on a phone.",
                    13,
                    Color(0xFFFFC66D)
                )
            }

            Column(
                Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                GlassCard(Modifier.fillMaxWidth()) {
                    TextLabel("Install an OpenXR APK", 22, Color.White)
                    Spacer(Modifier.height(8.dp))
                    TextLabel(
                        selectedApk?.absolutePath ?: "Choose a game APK built for Android and for the phone's architecture.",
                        13,
                        Color(0xFFCACAD7)
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CupertinoButton(onClick = {
                            chooseApk()?.let { selectedApk = it; status = "Chosen ${it.name}" }
                        }) { androidx.compose.material3.Text("Choose an APK") }
                        CupertinoButton(
                            onClick = {
                                val device = selectedDevice ?: return@CupertinoButton
                                val apk = selectedApk ?: return@CupertinoButton
                                scope.launch {
                                    busy = true
                                    status = withContext(Dispatchers.IO) { controller.install(device.serial, apk) }
                                    busy = false
                                }
                            },
                            enabled = !busy && selectedDevice != null && selectedApk != null
                        ) { androidx.compose.material3.Text("Install over USB") }
                    }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    TextLabel("Hand tracking for Cardboard", 22, Color.White)
                    Spacer(Modifier.height(8.dp))
                    TextLabel(
                        "Installs the camera and hand tracking app. It cannot inject hands into someone else's OpenXR game — the game has to support hand tracking itself.",
                        13,
                        Color(0xFFCACAD7)
                    )
                    Spacer(Modifier.height(16.dp))
                    CupertinoButton(
                        onClick = {
                            val device = selectedDevice ?: return@CupertinoButton
                            scope.launch {
                                busy = true
                                status = withContext(Dispatchers.IO) { controller.installCompanion(device.serial) }
                                busy = false
                            }
                        },
                        enabled = !busy && selectedDevice != null
                    ) { androidx.compose.material3.Text("Install Cardboard Hands") }
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    TextLabel("State", 16, Color.White)
                    Spacer(Modifier.height(8.dp))
                    TextLabel(status, 14, Color(0xFFD5D5E0))
                }

                GlassCard(Modifier.fillMaxWidth()) {
                    TextLabel("Instructions", 18, Color.White)
                    Spacer(Modifier.height(10.dp))
                    TextLabel("1. On the phone: Developer options → USB debugging.", 13, Color(0xFFD5D5E0))
                    TextLabel("2. Plug in the cable and confirm the RSA key.", 13, Color(0xFFD5D5E0))
                    TextLabel("3. Choose an Android APK and tap “Install over USB”.", 13, Color(0xFFD5D5E0))
                    TextLabel("4. Launch the game on the phone without the Mac if it has a compatible OpenXR runtime.", 13, Color(0xFFD5D5E0))
                }
            }
        }
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(Color(0xB82A2B36), RoundedCornerShape(24.dp)).padding(22.dp),
        verticalArrangement = Arrangement.Top,
        content = content
    )
}

@Composable
private fun TextLabel(text: String, size: Int, color: Color) {
    androidx.compose.material3.Text(text, color = color, fontSize = size.sp)
}

private fun chooseApk(): File? {
    val dialog = FileDialog(null as Frame?, "Choose an Android APK", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.endsWith(".apk", ignoreCase = true) }
        isVisible = true
    }
    return dialog.file?.let { File(dialog.directory, it) }
}

private data class Device(val serial: String, val model: String)

private class AdbController {
    private val adb: String? by lazy { findAdb() }

    fun devices(): List<Device> {
        val path = adb ?: return emptyList()
        val output = run(listOf(path, "devices", "-l"))
        return output.lineSequence().drop(1).mapNotNull { line ->
            if (!line.contains("\tdevice")) return@mapNotNull null
            val serial = line.substringBefore('\t').trim()
            val model = Regex("model:([^ ]+)").find(line)?.groupValues?.get(1) ?: "Android"
            Device(serial, model.replace('_', ' '))
        }.toList()
    }

    fun hasOpenXrRuntime(serial: String): Boolean {
        val path = adb ?: return false
        val packages = run(listOf(path, "-s", serial, "shell", "pm", "list", "packages")).lowercase()
        return listOf("openxr", "monado", "spaces", "oculus", "pico").any(packages::contains)
    }

    fun install(serial: String, apk: File): String {
        val path = adb ?: return "ADB not found. Install the Android Platform Tools."
        if (!apk.isFile || apk.extension.lowercase() != "apk") return "The chosen APK is not valid."
        val output = run(listOf(path, "-s", serial, "install", "-r", apk.absolutePath))
        return if (output.contains("Success")) "${apk.name} installed successfully." else output.takeLast(900)
    }

    fun installCompanion(serial: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("cardboard-hands.apk")
            ?: return "The build has no Cardboard Hands APK. Rebuild the installer."
        val temp = kotlin.io.path.createTempFile("cardboard-hands-", ".apk").toFile()
        return try {
            stream.use { input -> temp.outputStream().use(input::copyTo) }
            install(serial, temp)
        } finally {
            temp.delete()
        }
    }

    private fun findAdb(): String? {
        val candidates = buildList {
            System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
            System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
            add("${System.getProperty("user.home")}/Library/Android/sdk/platform-tools/adb")
            add("/opt/homebrew/bin/adb")
            add("/usr/local/bin/adb")
        }
        return candidates.firstOrNull { File(it).canExecute() }
    }

    private fun run(command: List<String>): String = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.trim()
    } catch (error: Exception) {
        error.localizedMessage ?: "Failed to run the command"
    }
}
