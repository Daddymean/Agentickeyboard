package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-line AI result (label + text) that expands on tap so the full output —
 * and, when available, the original text it will replace — can be read before
 * committing with Apply.
 */
@Composable
internal fun ExpandableResult(
    label: String,
    labelColor: Color,
    result: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    original: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalKeyboardColors.current
    Column(modifier = modifier.clickable { onToggle() }) {
        Text(
            text = "$label ${if (expanded) "▲" else "▼"}",
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = result,
            color = colors.text,
            fontSize = 13.sp,
            maxLines = if (expanded) 10 else 1,
            overflow = TextOverflow.Ellipsis
        )
        if (expanded && !original.isNullOrBlank() && original != result) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Was: $original",
                color = colors.textMuted,
                fontSize = 11.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                textDecoration = TextDecoration.LineThrough
            )
        }
    }
}

@Composable
internal fun ClipActionChip(label: String, onClick: () -> Unit) {
    val colors = LocalKeyboardColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.chip)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = colors.onChip, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AiActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    val colors = LocalKeyboardColors.current
    val containerColor = if (highlighted) colors.chipAlt else colors.key
    val borderColor = if (highlighted) colors.keyActive else colors.border
    val contentColor = if (highlighted) colors.onChip else colors.text

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val interactionModifier = if (onLongPress != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { currentOnClick() },
                onLongPress = { currentOnLongPress?.invoke() }
            )
        }
    } else {
        Modifier.clickable { currentOnClick() }
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .then(interactionModifier),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(icon, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = contentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun KeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSpecial: Boolean = false,
    isHighlighted: Boolean = false,
    repeatOnHold: Boolean = false,
    longPressVariants: List<String> = emptyList(),
    onVariant: (String) -> Unit = {},
    onHorizontalDrag: ((Int) -> Unit)? = null,
    onTapPosition: ((Offset) -> Unit)? = null
) {
    val colors = LocalKeyboardColors.current
    val keyMetrics = LocalKeyboardMetrics.current
    val isEnter = text == "Enter"
    val containerColor = when {
        isEnter -> colors.accent
        isHighlighted -> colors.keyActive
        isSpecial -> colors.keySpecial
        else -> colors.key
    }
    val contentColor = if (isEnter) colors.onAccent else colors.text

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnVariant by rememberUpdatedState(onVariant)
    val currentOnTapPosition by rememberUpdatedState(onTapPosition)
    val scope = rememberCoroutineScope()
    var showVariants by remember { mutableStateOf(false) }
    var selectedVariant by remember { mutableStateOf<Int?>(null) }
    val variantGeometry = remember { VariantPopupGeometry() }
    val currentVariants by rememberUpdatedState(longPressVariants)
    val pressOrder = LocalKeyPressOrder.current
    val density = LocalDensity.current

    val currentOnHorizontalDrag by rememberUpdatedState(onHorizontalDrag)

    val interactionModifier = when {
        // KEYBOARD-017: the space bar types a space unless the thumb clearly slides.
        onHorizontalDrag != null -> Modifier.pointerInput(pressOrder) {
            detectTapOrHorizontalSlide(
                order = pressOrder,
                onTap = { currentOnClick() },
                onSlide = { steps -> currentOnHorizontalDrag?.invoke(steps) }
            )
        }
        repeatOnHold -> Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    currentOnClick()
                    val repeatJob = scope.launch {
                        delay(450)
                        while (true) {
                            currentOnClick()
                            delay(60)
                        }
                    }
                    tryAwaitRelease()
                    repeatJob.cancel()
                }
            )
        }
        longPressVariants.isNotEmpty() || onTapPosition != null -> Modifier.pointerInput(pressOrder) {
            // KEYBOARD-016: holding opens the accents; releasing types the accent
            // slid onto, or the base letter if none was chosen.
            detectKeyPress(
                order = pressOrder,
                longPressEnabled = { currentVariants.isNotEmpty() },
                onTap = { point -> currentOnTapPosition?.invoke(point) ?: currentOnClick() },
                onLongPress = {
                    selectedVariant = null
                    showVariants = true
                },
                onHoldMove = { point -> selectedVariant = variantGeometry.variantAt(point, currentVariants.size) },
                onHoldEnd = { downPoint, lastPoint ->
                    val variants = currentVariants
                    val index = variantGeometry.variantAt(lastPoint, variants.size)
                    showVariants = false
                    selectedVariant = null
                    if (index != null) {
                        currentOnVariant(variants[index])
                    } else {
                        currentOnTapPosition?.invoke(downPoint) ?: currentOnClick()
                    }
                }
            )
        }
        else -> Modifier.clickable { currentOnClick() }
    }

    Box(
        modifier = modifier
            .size(width = keyMetrics.keyWidth, height = keyMetrics.keyHeight)
            .shadow(1.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .then(
                // KEYBOARD-018: keys that do not order themselves still type any held letter first.
                if (longPressVariants.isNotEmpty() || onTapPosition != null || onHorizontalDrag != null) Modifier
                else Modifier.flushHeldKeysOnPress(pressOrder)
            )
            .then(interactionModifier)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { currentOnClick(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            // Letters and digits grow with the key (about 20dp on a 44dp key) but stay
            // inside its width, and are sized in dp so the system font scale cannot
            // push a wide glyph like "W" past a fixed-size key. Word labels (?123,
            // Space, Enter) stay compact so they never wrap.
            fontSize = if (text.length == 1) {
                with(density) {
                    minOf(keyMetrics.keyHeight.value * 0.45f, keyMetrics.keyWidth.value * 0.6f)
                        .coerceIn(14f, 28f).dp.toSp()
                }
            } else {
                14.sp
            },
            fontWeight = FontWeight.Medium
        )

        if (showVariants && longPressVariants.isNotEmpty()) {
            KeyVariantPopup(
                variants = longPressVariants,
                selected = selectedVariant,
                geometry = variantGeometry,
                onVariant = { variant ->
                    currentOnVariant(variant)
                    showVariants = false
                },
                onDismiss = { showVariants = false }
            )
        }
    }
}

val RowDefaultsButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 12.dp,
    vertical = 0.dp
)

@Composable
internal fun VoiceMatchBadge(match: VoiceMatchState) {
    val keyboardColors = LocalKeyboardColors.current
    val deltaText = when {
        match.delta == null -> ""
        match.delta > 0 -> " · ↑ ${match.delta} after refine"
        match.delta < 0 -> " · ${-match.delta} point shift"
        else -> " · steady after refine"
    }
    val confidenceText = if (match.confidence < 35) " · early estimate" else ""
    val signalText = match.signals.firstOrNull()?.let { " · $it" }.orEmpty()

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(keyboardColors.accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("voice_match_badge"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🎙 ${match.percent}% your voice$deltaText$confidenceText$signalText",
            color = keyboardColors.accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
