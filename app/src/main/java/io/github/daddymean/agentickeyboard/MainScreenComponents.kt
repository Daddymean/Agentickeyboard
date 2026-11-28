package io.github.daddymean.agentickeyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.TextUnit
import io.github.daddymean.agentickeyboard.ui.theme.LocalCompanionColors
import io.github.daddymean.agentickeyboard.util.LatencyMeter

@Composable
fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalCompanionColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                description,
                color = colors.textSecondary,
                fontSize = 10.sp,
                lineHeight = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent
            )
        )
    }
}

/** Selectable pill shared by theme, retention, persona, and export-format rows. */
@Composable
fun ChoiceOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp
) {
    val colors = LocalCompanionColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.cardAccent else colors.surfaceMuted)
            .clickable(onClick = onClick)
            .border(1.dp, if (selected) colors.accent else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) colors.onAccentContainer else colors.textSecondary,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Median / 95th-percentile milliseconds for one keyboard operation, or a dash before any sample. */
@Composable
fun LatencyStat(label: String, summary: LatencyMeter.Summary?) {
    val colors = LocalCompanionColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (summary == null) "–" else "%.1f / %.1f".format(java.util.Locale.US, summary.medianMs, summary.p95Ms),
            color = colors.onCardDarkAccent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = colors.onCardDark.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
    }
}

@Composable
fun DashboardStat(label: String, value: Int) {
    val colors = LocalCompanionColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value",
            color = colors.onCardDarkAccent,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = colors.onCardDark.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
    }
}

@Composable
fun SetupStepCard(
    stepNumber: String,
    title: String,
    description: String,
    actionLabel: String,
    done: Boolean = false,
    onAction: () -> Unit
) {
    val colors = LocalCompanionColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(28.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (done) colors.success else colors.cardAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (done) "✓" else stepNumber,
                        color = if (done) colors.onAccent else colors.onAccentContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    title,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                description,
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceMuted),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, colors.borderStrong)
            ) {
                Text(actionLabel, color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
