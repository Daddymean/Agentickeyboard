package dev.context.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The app's only activity: onboarding, settings and status ([OnboardingScreen]).
 * Also the target of Health Connect's permissions-rationale and
 * permission-usage intents, which open it with the privacy explanation on top.
 */
class MainActivity : ComponentActivity() {
  /** Bumped on every resume so the screen re-checks permissions granted elsewhere. */
  private var resumeCount by mutableIntStateOf(0)
  private var launchAction by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    launchAction = intent?.action
    setContent {
      MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
          OnboardingScreen(resumeCount, showHealthRationale = isHealthRationaleAction(launchAction))
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    launchAction = intent.action
  }

  override fun onResume() {
    super.onResume()
    resumeCount++
  }
}
