package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors
import io.github.daddymean.agentickeyboard.util.EditClipboardAction
import io.github.daddymean.agentickeyboard.util.SelectionCommand

/**
 * On-keyboard text selection and editing controls.
 *
 * The AI actions above already prefer the editor's selection over the draft, but
 * until now the only way to make a selection was to reach into the host app's
 * drag handles. This bar closes that loop: move the caret, extend a selection
 * word by word, grab the current word or line, then hand it straight to Rewrite,
 * Translate or any other action without leaving the keyboard.
 *
 * Every command is resolved by `SelectionPlanner` in the IME service; this
 * composable only reports which one the user asked for.
 */
@Composable
fun TextEditBar(
    visible: Boolean,
    hasSelection: Boolean,
    onCommand: (SelectionCommand) -> Unit,
    onClipboardAction: (EditClipboardAction) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val colors = LocalKeyboardColors.current

    // "Extend" turns the four arrows from caret moves into selection growth,
    // the way holding shift does on a hardware keyboard. It latches on so a
    // multi-word selection does not need a modifier held down on a touchscreen.
    var extend by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.shelf)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("text_edit_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        EditChip(
            label = "Extend",
            icon = "⇧",
            active = extend,
            onClick = { extend = !extend },
            testTag = "edit_extend"
        )

        EditChip(
            label = "",
            icon = "⇤",
            onClick = {
                onCommand(
                    if (extend) SelectionCommand.ExtendWordLeft else SelectionCommand.MoveWordLeft
                )
            },
            testTag = "edit_word_left"
        )
        EditChip(
            label = "",
            icon = "◀",
            onClick = {
                onCommand(if (extend) SelectionCommand.ExtendLeft else SelectionCommand.MoveLeft)
            },
            testTag = "edit_left"
        )
        EditChip(
            label = "",
            icon = "▶",
            onClick = {
                onCommand(if (extend) SelectionCommand.ExtendRight else SelectionCommand.MoveRight)
            },
            testTag = "edit_right"
        )
        EditChip(
            label = "",
            icon = "⇥",
            onClick = {
                onCommand(
                    if (extend) SelectionCommand.ExtendWordRight else SelectionCommand.MoveWordRight
                )
            },
            testTag = "edit_word_right"
        )

        EditChip(
            label = "Word",
            icon = "⌗",
            onClick = { onCommand(SelectionCommand.SelectWord) },
            testTag = "edit_select_word"
        )
        EditChip(
            label = "Line",
            icon = "☰",
            onClick = { onCommand(SelectionCommand.SelectLine) },
            testTag = "edit_select_line"
        )
        EditChip(
            label = "All",
            icon = "▦",
            onClick = { onCommand(SelectionCommand.SelectAll) },
            testTag = "edit_select_all"
        )

        // Clipboard actions only make sense against a live selection, except
        // paste, which needs a caret and nothing else.
        if (hasSelection) {
            EditChip(
                label = "Cut",
                icon = "✂",
                onClick = { onClipboardAction(EditClipboardAction.Cut) },
                testTag = "edit_cut"
            )
            EditChip(
                label = "Copy",
                icon = "⧉",
                onClick = { onClipboardAction(EditClipboardAction.Copy) },
                testTag = "edit_copy"
            )
            EditChip(
                label = "Clear",
                icon = "⊘",
                onClick = { onCommand(SelectionCommand.Collapse) },
                testTag = "edit_collapse"
            )
        }
        EditChip(
            label = "Paste",
            icon = "📋",
            onClick = { onClipboardAction(EditClipboardAction.Paste) },
            testTag = "edit_paste"
        )

        EditChip(
            label = "Done",
            icon = "✓",
            highlighted = true,
            onClick = onDismiss,
            testTag = "edit_done"
        )
    }
}

/** Compact chip used by [TextEditBar]; a label of "" renders the icon alone. */
@Composable
private fun EditChip(
    label: String,
    icon: String,
    onClick: () -> Unit,
    testTag: String,
    active: Boolean = false,
    highlighted: Boolean = false
) {
    val colors = LocalKeyboardColors.current
    val container = when {
        active -> colors.keyActive
        highlighted -> colors.chipAlt
        else -> colors.key
    }
    val contentColor = when {
        active -> colors.onAccent
        highlighted -> colors.onChip
        else -> colors.text
    }
    val borderColor = if (active) colors.accent else colors.border

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, color = contentColor, fontSize = 13.sp)
        if (label.isNotEmpty()) {
            Text(
                text = " $label",
                color = contentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
