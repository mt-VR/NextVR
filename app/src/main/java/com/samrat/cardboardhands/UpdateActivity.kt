package com.samrat.cardboardhands

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zone.ien.hig.CupertinoActivityIndicator
import zone.ien.hig.CupertinoSwitch
import zone.ien.hig.CupertinoText
import zone.ien.hig.ExperimentalCupertinoApi
import zone.ien.hig.section.SectionItem
import zone.ien.hig.theme.CupertinoTheme
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/** Native "Software Update" screen for NextVR. */
class UpdateActivity : ComponentActivity() {
    private var release by mutableStateOf<Updates.Release?>(null)
    private var checking by mutableStateOf(true)
    private var progress by mutableStateOf<Float?>(null)
    private var error by mutableStateOf<String?>(null)
    private var auto by mutableStateOf(true)
    private var beta by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auto = Updates.autoUpdate(this)
        beta = Updates.beta(this)
        check()
        setContent { PhoneXRTheme { Screen() } }
    }

    private fun check() {
        checking = true
        error = null
        thread {
            val found = runCatching { Updates.check(this) }
            runOnUiThread {
                checking = false
                release = found.getOrNull()
                error = found.exceptionOrNull()?.let { "Couldn't check for updates" }
            }
        }
    }

    private fun update(target: Updates.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName")))
            return
        }
        progress = 0f
        thread {
            val file = runCatching { Updates.download(this, target) { value -> runOnUiThread { progress = value } } }
            runOnUiThread {
                progress = null
                file.onSuccess { Updates.install(this, it) }.onFailure { error = "The update didn't download" }
            }
        }
    }

    @OptIn(ExperimentalCupertinoApi::class)
    @Composable
    private fun Screen() {
        HigPage(title = tr("Software Update"), onBack = ::finish) {
            HigSection {
                HigSwitchRow(tr("Automatic Updates"), auto) { auto = it; Updates.setAutoUpdate(this@UpdateActivity, it) }
                HigSwitchRow(tr("Beta Updates"), beta) { beta = it; Updates.setBeta(this@UpdateActivity, it); check() }
            }
            val found = release
            when {
                checking -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { HigSpinner() }
                found != null -> UpdateCard(found)
                else -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CupertinoText("NextVR ${Updates.currentVersion(this@UpdateActivity)}", fontWeight = FontWeight.SemiBold)
                    CupertinoText(error ?: tr("NextVR is up to date"), color = CupertinoTheme.colorScheme.secondaryLabel)
                }
            }
        }
    }

    @Composable
    private fun UpdateCard(found: Updates.Release) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(CupertinoTheme.colorScheme.secondarySystemGroupedBackground).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = remember { packageManager.getApplicationIcon(packageName) }
                Canvas(Modifier.size(56.dp).clip(RoundedCornerShape(13.dp))) {
                    drawIntoCanvas { canvas ->
                        icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                        icon.draw(canvas.nativeCanvas)
                    }
                }
                Column(Modifier.padding(start = 14.dp)) {
                    CupertinoText("NextVR ${found.version}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    CupertinoText(Updates.formatSize(found.size), color = CupertinoTheme.colorScheme.secondaryLabel)
                }
            }
            val value = progress
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(NextDesign.Radius.control.dp))
                    .background(NextDesign.primaryColor)
                    .clickable(enabled = value == null) { update(found) },
                contentAlignment = Alignment.Center
            ) {
                CupertinoText(
                    when {
                        value == null -> tr("Update Now")
                        value < 0f -> tr("Loading…")
                        else -> "Downloading ${(value * 100).roundToInt()}%"
                    },
                    color = Color.White, fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
