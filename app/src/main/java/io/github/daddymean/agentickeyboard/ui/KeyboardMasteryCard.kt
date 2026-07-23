package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.util.mastery.MasteryAchievements
import io.github.daddymean.agentickeyboard.util.mastery.MasteryPath

/** Compact, local-only progression surface for the Style Hub. */
@Composable
fun KeyboardMasteryCard(viewModel: KeyboardViewModel) {
    val state by viewModel.masteryState.collectAsState()
    val enabled by viewModel.isMasteryEnabled.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mastery_card"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "✨ KEYBOARD MASTERY",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp
                    )
                    Text(
                        "Useful habits, measured privately on this device.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 10.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = viewModel::setMasteryEnabled,
                    modifier = Modifier.testTag("mastery_toggle")
                )
            }

            if (!enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Progress is paused. Keyboard features remain fully available.",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MasteryMetric(
                    label = "Total XP",
                    value = state.totalXp.toString(),
                    modifier = Modifier.weight(1f)
                )
                MasteryMetric(
                    label = "Rhythm",
                    value = "${state.currentStreak} days",
                    modifier = Modifier.weight(1f)
                )
                MasteryMetric(
                    label = "Best",
                    value = "${state.bestStreak} days",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            MasteryPath.values().forEach { path ->
                MasteryPathProgress(
                    path = path,
                    level = state.levelFor(path),
                    xp = state.xpFor(path),
                    progress = state.levelProgress(path)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            val unlocked = MasteryAchievements.catalog.filter { it.id in state.achievements }
            Text(
                "${unlocked.size}/${MasteryAchievements.catalog.size} achievements · ${state.graceDays} grace day${if (state.graceDays == 1) "" else "s"}",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
            if (unlocked.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    unlocked.takeLast(3).joinToString("   ") { "${it.symbol} ${it.title}" },
                    color = Color(0xFFFDE68A),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = { confirmReset = true },
                modifier = Modifier.testTag("mastery_reset")
            ) {
                Text("Reset mastery progress", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset Keyboard Mastery?") },
            text = {
                Text("This clears XP, streaks, and achievements. It does not change keyboard settings, learned vocabulary, or core features.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetMasteryProgress()
                        confirmReset = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("Keep progress")
                }
            }
        )
    }
}

@Composable
private fun MasteryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
    }
}

@Composable
private fun MasteryPathProgress(
    path: MasteryPath,
    level: Int,
    xp: Int,
    progress: Float
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${path.symbol} ${path.label}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Lv $level · $xp XP",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 10.sp
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(6.dp)),
            color = Color(0xFF8B5CF6),
            trackColor = Color.White.copy(alpha = 0.12f)
        )
    }
}
