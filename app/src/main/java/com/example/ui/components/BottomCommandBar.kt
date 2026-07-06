package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KeyboardTheme
import com.example.ui.theme.LocalKeyboardColors

/**
 * One-shot extracted Bottom Command Bar.
 * Contains mode key, privacy toggle, mic, space, period, enter.
 * Replaces the final Row in AgenticKeyboardLayout.kt.
 */
@Composable
fun BottomCommandBar(
    isNumberMode: Boolean,
    isOfflineMode: Boolean,
    onModeClick: () -> Unit,
    onPrivacyToggle: () -> Unit,
    onMicPress: () -> Unit,
    onSpace: () -> Unit,
    onPeriod: () -> Unit,
    onEnter: () -> Unit,
    onCursorMove: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    KeyboardTheme {
        val colors = LocalKeyboardColors.current

        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton(text = if (isNumberMode) "ABC" else "?123", onClick = onModeClick, isSpecial = true, modifier = Modifier.width(56.dp).testTag("key_mode"))
            Spacer(modifier = Modifier.width(4.dp))

            KeyButton(text = if (isOfflineMode) "🔒" else "🚀", onClick = onPrivacyToggle, isSpecial = true, modifier = Modifier.width(40.dp).testTag("key_privacy_toggle"))
            Spacer(modifier = Modifier.width(4.dp))

            KeyButton(text = "🎤", onClick = onMicPress, isSpecial = true, modifier = Modifier.width(40.dp).testTag("key_mic"))
            Spacer(modifier = Modifier.width(4.dp))

            KeyButton(text = "Space", onClick = onSpace, modifier = Modifier.weight(1f).testTag("key_space"))
            Spacer(modifier = Modifier.width(4.dp))

            KeyButton(text = ".", onClick = onPeriod, modifier = Modifier.width(36.dp).testTag("key_period"))
            Spacer(modifier = Modifier.width(4.dp))

            KeyButton(text = "Enter", onClick = onEnter, isSpecial = true, modifier = Modifier.width(56.dp).testTag("key_enter"))
        }
    }
}
