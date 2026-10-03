package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.network.GeminiManager
import io.github.daddymean.agentickeyboard.util.GeminiKeyStore

/**
 * Lets the user paste their own Gemini API key. It is encrypted on the device
 * (GeminiKeyStore) and applied to GeminiManager immediately, so cloud AI works
 * in CI-built APKs without a key ever being compiled in or committed.
 */
@Composable
fun GeminiApiKeyCard() {
    val context = LocalContext.current
    var savedKey by remember { mutableStateOf(GeminiManager.userApiKey) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🔑 Gemini API key",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when {
                    savedKey != null -> "Online AI uses your key ${GeminiKeyStore.mask(savedKey!!)}."
                    GeminiManager.hasBuildTimeKey() -> "Using the key built into this APK. Paste your own to override it."
                    else -> "No key set: online AI is off. Get a free key at aistudio.google.com → Get API key."
                },
                color = if (savedKey != null || GeminiManager.hasBuildTimeKey()) Color(0xFF1B6B3A) else Color(0xFF79747E),
                fontSize = 12.sp,
                modifier = Modifier.testTag("gemini_key_status")
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    message = null
                },
                label = { Text(if (savedKey != null) "Replace key" else "Paste API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("gemini_key_input")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val key = GeminiKeyStore.normalize(input)
                        if (key == null) {
                            isError = true
                            message = "That doesn't look like an API key."
                        } else if (GeminiKeyStore.save(context, key)) {
                            GeminiManager.userApiKey = key
                            savedKey = key
                            input = ""
                            isError = false
                            message = "Saved. Online AI is ready in the keyboard."
                        } else {
                            isError = true
                            message = "Couldn't store the key securely on this device."
                        }
                    },
                    enabled = input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    modifier = Modifier.testTag("gemini_key_save")
                ) {
                    Text("Save key")
                }
                if (savedKey != null) {
                    OutlinedButton(
                        onClick = {
                            GeminiKeyStore.clear(context)
                            GeminiManager.userApiKey = null
                            savedKey = null
                            isError = false
                            message = "Key removed from this device."
                        },
                        modifier = Modifier.testTag("gemini_key_remove")
                    ) {
                        Text("Remove")
                    }
                }
            }
            message?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(it, color = if (isError) Color(0xFFB3261E) else Color(0xFF1B6B3A), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Stored encrypted on this phone only (Android Keystore); never synced or backed up. " +
                    "Cloud AI also needs Offline Privacy mode off.",
                color = Color(0xFF79747E),
                fontSize = 11.sp
            )
        }
    }
}
