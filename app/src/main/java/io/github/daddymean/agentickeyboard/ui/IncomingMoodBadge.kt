package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ui.theme.KeyboardTheme
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors
import io.github.daddymean.agentickeyboard.util.IncomingMoodSession

/** KEYBOARD-022: a small strip with the estimated mood of their latest message. */
@Composable
fun IncomingMoodBadge(
    badge: IncomingMoodSession.Badge,
    themeOverride: String,
    onDismiss: () -> Unit
) {
    KeyboardTheme(darkTheme = when (themeOverride) {
        "Light" -> false
        "Dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }) {
        val colors = LocalKeyboardColors.current
        Surface(color = colors.shelf, modifier = Modifier.fillMaxWidth().testTag("incoming_mood_badge")) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    moodBadgeText(badge),
                    color = colors.text, fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("×", color = colors.textMuted) }
            }
        }
    }
}

internal fun moodBadgeText(badge: IncomingMoodSession.Badge): String =
    "Their last message: ${badge.mood.emoji} ${badge.mood.label} · estimated on this phone"
