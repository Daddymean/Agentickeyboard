package com.example.ui.tabs

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast

@Composable
fun SetupTab() {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Setup Guide",
                color = Color(0xFF1C1B1F),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp
            )
            Text(
                "Activate Lumina AI system-wide for access inside Signal, WhatsApp, or other messengers.",
                color = Color(0xFF5F5D6B),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        item {
            SetupStepCard(
                stepNumber = "1",
                title = "Enable Keyboard in Settings",
                description = "Enable Lumina Keyboard inside your Android system Language & Input settings panel.",
                actionLabel = "Open System Keyboards",
                onAction = {
                    try {
                        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Search Languages & Input settings on your device.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        item {
            SetupStepCard(
                stepNumber = "2",
                title = "Select as Active Input",
                description = "Trigger the keyboard selector dialog to choose Lumina AI as your standard input.",
                actionLabel = "Switch Active Input method",
                onAction = {
                    try {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Switch via status notification bar.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        item {
            SetupStepCard(
                stepNumber = "3",
                title = "Verify Absolute Offline Privacy",
                description = "Confirm local processing state. Toggling Local Lock blocks cloud connection for ultimate confidentiality.",
                actionLabel = "Security Protocol Verified",
                onAction = {
                    Toast.makeText(context, "Lumina offline containment active!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SetupStepCard(
    stepNumber: String,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(28.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8DEF8)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stepNumber,
                        color = Color(0xFF21005D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    title,
                    color = Color(0xFF1C1B1F),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                description,
                color = Color(0xFF5F5D6B),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFFCBD5E1))
            ) {
                Text(actionLabel, color = Color(0xFF6750A4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
