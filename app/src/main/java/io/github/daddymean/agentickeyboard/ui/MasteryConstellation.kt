package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.mastery.KeyboardMasteryCosmetics
import io.github.daddymean.agentickeyboard.util.mastery.MasteryAura
import io.github.daddymean.agentickeyboard.util.mastery.MasteryCompanion
import io.github.daddymean.agentickeyboard.util.mastery.MasteryState

private val STAR_POINTS = listOf(
    Offset(0.12f, 0.62f),
    Offset(0.23f, 0.31f),
    Offset(0.37f, 0.48f),
    Offset(0.49f, 0.20f),
    Offset(0.61f, 0.39f),
    Offset(0.76f, 0.24f),
    Offset(0.88f, 0.52f),
    Offset(0.72f, 0.72f),
    Offset(0.54f, 0.65f),
    Offset(0.39f, 0.81f),
    Offset(0.21f, 0.78f),
    Offset(0.91f, 0.82f)
)

/** Companion-app-only cosmetic surface. It never renders inside the IME. */
@Composable
fun MasteryConstellationSection(
    state: MasteryState,
    settings: KeyboardSettings
) {
    var visible by remember(settings) {
        mutableStateOf(settings.isMasteryCompanionVisible)
    }
    var selectedAuraId by remember(settings) {
        mutableStateOf(settings.masteryAura)
    }
    val companion = remember(state, selectedAuraId) {
        KeyboardMasteryCosmetics.companion(state, selectedAuraId)
    }
    val unlockedIds = remember(state) {
        KeyboardMasteryCosmetics.unlockedAuras(state).map { it.id }.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp)
            .testTag("mastery_constellation")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "LUMINA CONSTELLATION",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    "A companion-app keepsake grown from useful habits.",
                    color = Color.White.copy(alpha = 0.48f),
                    fontSize = 9.sp
                )
            }
            Switch(
                checked = visible,
                onCheckedChange = {
                    visible = it
                    settings.isMasteryCompanionVisible = it
                },
                modifier = Modifier.testTag("mastery_constellation_toggle")
            )
        }

        if (!visible) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Hidden by choice. Progress and keyboard features continue normally.",
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 10.sp
            )
            return@Column
        }

        Spacer(modifier = Modifier.height(10.dp))
        ConstellationCanvas(companion)
        Spacer(modifier = Modifier.height(9.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${companion.aura.symbol} ${companion.stage.label} · ${companion.aura.title}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    companion.aura.description,
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 9.sp
                )
            }
            Text(
                "${companion.visibleStars} stars",
                color = auraColor(companion.aura.id),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(7.dp))
        Text(
            companion.nextStage?.let {
                "Next form: ${it.label} in ${companion.xpUntilNextStage} XP"
            } ?: "All constellation forms discovered",
            color = Color.White.copy(alpha = 0.52f),
            fontSize = 9.sp
        )
        companion.nextAura?.let {
            Text(
                "Next aura: ${it.title} · ${it.requirementLabel()}",
                color = Color.White.copy(alpha = 0.42f),
                fontSize = 8.sp
            )
        }

        Spacer(modifier = Modifier.height(9.dp))
        Text(
            "AURAS",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp
        )
        KeyboardMasteryCosmetics.auras.chunked(2).forEach { rowAuras ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowAuras.forEach { aura ->
                    val unlocked = aura.id in unlockedIds
                    AuraChoice(
                        aura = aura,
                        unlocked = unlocked,
                        selected = companion.aura.id == aura.id,
                        onSelect = {
                            selectedAuraId = aura.id
                            settings.masteryAura = aura.id
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowAuras.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }

        Text(
            "Cosmetics are visual only. They never unlock or alter keyboard features.",
            color = Color.White.copy(alpha = 0.36f),
            fontSize = 8.sp
        )
    }
}

@Composable
private fun ConstellationCanvas(companion: MasteryCompanion) {
    val accent = auraColor(companion.aura.id)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.07f))
            .testTag("mastery_constellation_canvas")
    ) {
        val points = STAR_POINTS.take(companion.visibleStars).map {
            Offset(it.x * size.width, it.y * size.height)
        }
        for (index in 0 until points.lastIndex) {
            drawLine(
                color = accent.copy(alpha = 0.42f),
                start = points[index],
                end = points[index + 1],
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )
        }
        if (points.size >= 8) {
            drawLine(
                color = accent.copy(alpha = 0.24f),
                start = points[1],
                end = points[7],
                strokeWidth = 1.5f
            )
        }
        if (points.size >= 10) {
            drawLine(
                color = accent.copy(alpha = 0.22f),
                start = points[3],
                end = points[9],
                strokeWidth = 1.5f
            )
        }
        points.forEachIndexed { index, point ->
            val radius = if (index == points.lastIndex) 5.5f else 4f
            drawCircle(accent.copy(alpha = 0.13f), radius * 2.8f, point)
            drawCircle(accent, radius, point)
            drawCircle(Color.White.copy(alpha = 0.72f), radius * 0.35f, point)
        }
    }
}

@Composable
private fun AuraChoice(
    aura: MasteryAura,
    unlocked: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onSelect,
        enabled = unlocked,
        modifier = modifier.testTag("mastery_aura_${aura.id}")
    ) {
        Text(
            when {
                selected -> "✓ ${aura.symbol} ${aura.title}"
                unlocked -> "${aura.symbol} ${aura.title}"
                else -> "🔒 ${aura.title}"
            },
            color = when {
                selected -> auraColor(aura.id)
                unlocked -> Color.White.copy(alpha = 0.72f)
                else -> Color.White.copy(alpha = 0.3f)
            },
            fontSize = 9.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun auraColor(id: String): Color = when (id) {
    "ember" -> Color(0xFFF59E0B)
    "prism" -> Color(0xFFA78BFA)
    "aurora" -> Color(0xFF34D399)
    "comet" -> Color(0xFFF472B6)
    else -> Color(0xFF93C5FD)
}