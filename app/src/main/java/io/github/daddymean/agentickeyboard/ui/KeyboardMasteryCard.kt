package io.github.daddymean.agentickeyboard.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.mastery.KeyboardMasteryMissions
import io.github.daddymean.agentickeyboard.util.mastery.KeyboardMasteryReports
import io.github.daddymean.agentickeyboard.util.mastery.MasteryAchievements
import io.github.daddymean.agentickeyboard.util.mastery.MasteryMission
import io.github.daddymean.agentickeyboard.util.mastery.MasteryPath
import io.github.daddymean.agentickeyboard.util.mastery.MasteryStateCodec
import io.github.daddymean.agentickeyboard.util.mastery.WeeklyMasteryReport

private const val DAY_MS = 86_400_000L

/** Compact, local-only progression surface for the Style Hub. */
@Composable
fun KeyboardMasteryCard(viewModel: KeyboardViewModel) {
    val state by viewModel.masteryState.collectAsState()
    val enabled by viewModel.isMasteryEnabled.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val masterySettings = remember(context) { KeyboardSettings(context) }
    val epochDay = System.currentTimeMillis() / DAY_MS
    val missions = remember(state, epochDay) {
        KeyboardMasteryMissions.forDay(state, epochDay)
    }
    val report = remember(state, epochDay) {
        KeyboardMasteryReports.rollingWeek(state, epochDay)
    }

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

            Text(
                "TODAY'S MISSIONS",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(7.dp))
            missions.forEach { mission ->
                MasteryMissionRow(
                    mission = mission,
                    onDismiss = {
                        val updated = KeyboardMasteryMissions.dismiss(
                            state = state,
                            missionId = mission.definition.id,
                            epochDay = epochDay
                        )
                        masterySettings.masteryState = MasteryStateCodec.encode(updated)
                    }
                )
                Spacer(modifier = Modifier.height(7.dp))
            }

            Spacer(modifier = Modifier.height(5.dp))
            WeeklyReportSummary(report)

            Spacer(modifier = Modifier.height(12.dp))
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
                Text(
                    "Reset mastery progress",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset Keyboard Mastery?") },
            text = {
                Text("This clears XP, missions, weekly history, streaks, and achievements. It does not change keyboard settings, learned vocabulary, or core features.")
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

@Composable
private fun MasteryMissionRow(
    mission: MasteryMission,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 11.dp, vertical = 9.dp)
            .testTag("mastery_mission_${mission.definition.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${mission.definition.path.symbol} ${mission.definition.title}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    mission.definition.description,
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 9.sp
                )
            }
            if (!mission.completed) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("dismiss_mission_${mission.definition.id}")
                ) {
                    Text("Dismiss", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = mission.progressFraction,
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = if (mission.completed) Color(0xFF34D399) else Color(0xFF60A5FA),
            trackColor = Color.White.copy(alpha = 0.1f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${mission.clampedProgress}/${mission.definition.target}",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp
            )
            AnimatedVisibility(visible = mission.completed) {
                Text(
                    "✓ Complete",
                    color = Color(0xFF6EE7B7),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun WeeklyReportSummary(report: WeeklyMasteryReport) {
    val comparison = when {
        report.actionDelta > 0 -> "+${report.actionDelta} useful actions vs prior week"
        report.actionDelta < 0 -> "${-report.actionDelta} fewer actions than prior week"
        else -> "Even with the prior week"
    }
    val timeLabel = when {
        report.estimatedSecondsSaved <= 0 -> "0s"
        report.estimatedSecondsSaved < 60 -> "${report.estimatedSecondsSaved}s"
        else -> "${(report.estimatedSecondsSaved + 30) / 60}m"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1F2937))
            .padding(12.dp)
            .testTag("mastery_weekly_report")
    ) {
        Text(
            "YOUR LAST 7 DAYS",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            MasteryMetric(
                label = "Keys saved",
                value = report.estimatedKeystrokesSaved.toString(),
                modifier = Modifier.weight(1f)
            )
            MasteryMetric(
                label = "Time back",
                value = timeLabel,
                modifier = Modifier.weight(1f)
            )
            MasteryMetric(
                label = "Active days",
                value = "${report.activeDays}/7",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            comparison,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 10.sp
        )
        Text(
            "Top path: ${report.dominantPath?.label ?: "Still exploring"} · Personal best: ${report.bestDayActions} useful actions in one day",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 9.sp
        )
        Text(
            "Time saved is a conservative estimate from aggregate actions only.",
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 8.sp
        )
    }
}
