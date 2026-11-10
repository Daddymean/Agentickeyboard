package io.github.daddymean.agentickeyboard.ondevice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.AgenticKeyboardApplication
import io.github.daddymean.agentickeyboard.SettingSwitchRow

@Composable
fun GemmaSettingsSection() {
    val context = LocalContext.current
    val app = context.applicationContext as AgenticKeyboardApplication
    val controller = app.localGemma
    val settings = app.settings
    val artifact = controller.artifact
    var enabled by remember { mutableStateOf(controller.isEnabled()) }
    var allowCellular by remember { mutableStateOf(settings.isLocalGemmaCellularAllowed) }
    val storeState by controller.storeState.collectAsState()
    val ready = controller.isReady()
    val sizeLabel = LocalGemmaController.formattedSize(artifact)

    val statusText = when {
        !enabled -> "Off — Gemini Nano is used when available, otherwise the keyboard's existing local helpers. Nothing is downloaded."
        storeState is GemmaStoreState.Downloading -> {
            val d = storeState as GemmaStoreState.Downloading
            val pct = if (d.total > 0) (d.bytes * 100 / d.total) else 0
            "Downloading Gemma 4 E2B… $pct% (${d.bytes} of $sizeLabel)."
        }
        storeState is GemmaStoreState.Verifying -> "Verifying the SHA-256 hash…"
        storeState is GemmaStoreState.Failed -> "Download failed: ${(storeState as GemmaStoreState.Failed).message}"
        storeState is GemmaStoreState.Refused -> when ((storeState as GemmaStoreState.Refused).reason) {
            ModelDownloadPolicy.Reason.WIFI_REQUIRED -> "Waiting for Wi-Fi, or allow a cellular download below."
            ModelDownloadPolicy.Reason.OPT_IN_REQUIRED -> "Turn the toggle on to allow a download."
            ModelDownloadPolicy.Reason.SIZE_NOT_ACKNOWLEDGED -> "Confirm the size below to start the download."
        }
        ready -> "Ready — rewrite, proofread and tone can use Gemma 4 E2B on this phone when Gemini Nano is unavailable."
        else -> "The model is $sizeLabel, Apache-2.0, stored only in this app's private storage. It is not in the install. Download uses Wi-Fi unless you allow cellular."
    }

    Column {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Local Gemma 4 E2B",
            color = Color(0xFF1C1B1F),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
        Text(
            statusText,
            color = Color(0xFF5F5D6B),
            fontSize = 10.sp
        )
        SettingSwitchRow(
            title = "Use Gemma 4 E2B on this device",
            description = "Optional on-device backup for rewrite, proofread and tone. Off by default. Never used in password, payment, one-time-code or incognito fields, and never falls back to the cloud on its own. Turning this off does not delete a download.",
            checked = enabled,
            onCheckedChange = {
                enabled = it
                controller.setEnabled(it)
                (app.onDeviceAi as? CascadingOnDeviceAi)?.refreshStatus()
            }
        )
        if (enabled && !ready) {
            Text(
                "Download size: $sizeLabel. SHA-256 starts ${artifact.sha256.take(12)}… License: ${artifact.license}.",
                color = Color(0xFF5F5D6B),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    settings.isLocalGemmaCellularAllowed = false
                    allowCellular = false
                    controller.requestDownload(context, allowMetered = false)
                }) {
                    Text("Download on Wi-Fi", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    settings.isLocalGemmaCellularAllowed = true
                    allowCellular = true
                    controller.requestDownload(context, allowMetered = true)
                }) {
                    Text("Allow cellular", fontSize = 12.sp)
                }
            }
        }
        if (enabled && ready) {
            TextButton(onClick = {
                controller.delete()
                enabled = controller.isEnabled()
                (app.onDeviceAi as? CascadingOnDeviceAi)?.refreshStatus()
            }) {
                Text("Delete downloaded model", fontSize = 12.sp)
            }
        }
        if (enabled) {
            SettingSwitchRow(
                title = "Allow cellular downloads",
                description = "If off, the model only downloads on unmetered Wi-Fi.",
                checked = allowCellular,
                onCheckedChange = {
                    allowCellular = it
                    settings.isLocalGemmaCellularAllowed = it
                }
            )
        }
    }
}
