package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.network.CloudPrivacyPolicy
import io.github.daddymean.agentickeyboard.util.TrustPrism
import io.github.daddymean.agentickeyboard.util.TrustPrismMode

/** Compact, always-visible explanation of the active AI privacy path. */
@Composable
fun TrustPrismBanner(viewModel: KeyboardViewModel) {
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val isSensitiveField by viewModel.isSensitiveField.collectAsState()
    val isLearningPaused by viewModel.isLearningPaused.collectAsState()

    val status = TrustPrism.resolve(
        isOfflineMode = isOfflineMode,
        isSensitiveField = isSensitiveField,
        cloudRedactionEnabled = CloudPrivacyPolicy.redactionEnabled
    )

    val background = when (status.mode) {
        TrustPrismMode.SECURE_FIELD -> Color(0xFF12372A)
        TrustPrismMode.OFFLINE_LOCAL -> Color(0xFF173B57)
        TrustPrismMode.CLOUD_REDACTED -> Color(0xFF2D2458)
        TrustPrismMode.CLOUD_UNPROTECTED -> Color(0xFF6B1E1E)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("trust_prism_banner"),
        color = background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Text(status.icon, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = status.label,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = status.detail,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isLearningPaused && !isSensitiveField) {
                Text(
                    text = "LEARNING PAUSED",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}
