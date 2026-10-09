package dev.context.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import dev.context.app.collect.health.HealthCollector
import dev.context.app.graph
import dev.context.app.work.Schedules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

/**
 * The whole app UI: one scrollable page with sources, cloud sync, actions and a
 * snapshot preview. Everything on disk is reloaded off the main thread whenever
 * [resumeCount] changes (the activity bumps it in `onResume`), after a
 * permission result, and on "Refresh".
 */
@Composable
fun OnboardingScreen(resumeCount: Int, showHealthRationale: Boolean) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<ScreenState?>(null) }
  var reloads by remember { mutableIntStateOf(0) }
  var message by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(resumeCount, reloads) {
    state = withContext(Dispatchers.IO) {
      val graph = context.graph
      loadScreenState(graph.collectors, graph.settings, graph.snapshots, System.currentTimeMillis())
    }
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reloads++ }
  val healthLauncher =
    rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { reloads++ }

  fun grant(step: GrantStep) {
    message = null
    when (step) {
      GrantStep.Health -> when (HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PACKAGE)) {
        HealthConnectClient.SDK_AVAILABLE ->
          runCatching { healthLauncher.launch(HealthCollector.PERMISSIONS) }
            .onFailure { message = "Couldn't open Health Connect." }
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> openHealthConnectInStore(context)
        else -> message = "Health Connect isn't available on this device."
      }
      is GrantStep.Runtime -> permissionLauncher.launch(step.permissions.toTypedArray())
      GrantStep.BackgroundLocation -> permissionLauncher.launch(arrayOf(BACKGROUND_LOCATION))
      is GrantStep.OpenSettings ->
        if (!startSafely(context, Intent(step.action))) message = "Couldn't open ${describeMissing(step.action)}."
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .safeDrawingPadding()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Context", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
      TextButton(onClick = { reloads++ }) { Text("Refresh") }
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    if (showHealthRationale) HealthRationaleCard()

    val current = state
    if (current == null) {
      Text("Loading…")
    } else {
      SourcesCard(current.sources, onGrant = ::grant, onAppSettings = {
        startSafely(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
      })
      SyncCard(
        current,
        onSave = { url, token ->
          scope.launch {
            withContext(Dispatchers.IO) {
              val settings = context.graph.settings
              settings.supabaseUrl = url
              settings.syncToken = token
            }
            message = "Sync settings saved."
            reloads++
          }
        },
        onSyncNow = {
          Schedules.syncNow(context)
          message = "Sync queued; it runs once the network is available. Tap Refresh for its status."
        },
      )
      SectionCard("Actions") {
        Text("Collect from every ready source and rebuild the snapshot now, instead of waiting for the hourly run.")
        Button(onClick = {
          Schedules.runPipelineNow(context)
          message = "Collect & distill queued. Tap Refresh in a moment to see the result."
        }) { Text("Collect & distill now") }
      }
      SectionCard("Snapshot preview") {
        Text(
          "What the keyboard and other same-signed apps receive. It stays on this device.",
          style = MaterialTheme.typography.bodySmall,
        )
        SelectionContainer {
          Text(current.snapshotJson, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleLarge)
      content()
    }
  }
}

@Composable
private fun HealthRationaleCard() {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("How Context uses health data", style = MaterialTheme.typography.titleLarge)
      Text(
        "Context reads sleep sessions, heart rate and steps from Health Connect, including in the " +
          "background on its hourly run, to summarise your day (for example \"slept 7 h\" or " +
          "\"4,200 steps\") for your keyboard. It never writes health data."
      )
      Text(
        "Health data is stored only on this device. It is marked as sensitive (level 2), and cloud " +
          "sync only ever uploads level 0–1 data, so health data never leaves the phone, even when " +
          "sync is turned on."
      )
      Text("You can revoke access at any time in Health Connect; Context then stops reading it.")
    }
  }
}

@Composable
private fun SourcesCard(sources: List<SourceRow>, onGrant: (GrantStep) -> Unit, onAppSettings: () -> Unit) {
  SectionCard("Sources") {
    sources.forEachIndexed { index, row ->
      if (index > 0) HorizontalDivider()
      SourceItem(row, onGrant)
    }
    TextButton(onClick = onAppSettings) { Text("App permissions in Settings") }
    Text(
      "If a Grant button no longer shows a dialog, Android has stopped asking; allow it in App permissions instead.",
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun SourceItem(row: SourceRow, onGrant: (GrantStep) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(row.key, style = MaterialTheme.typography.titleMedium)
    Text("Last run: ${row.status}", style = MaterialTheme.typography.bodySmall)
    if (row.ready) {
      Text("Ready", color = MaterialTheme.colorScheme.primary)
    } else {
      Text("Needs: " + row.missing.joinToString { describeMissing(it) }, style = MaterialTheme.typography.bodyMedium)
      row.grantStep?.let { step ->
        stepHint(step)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = { onGrant(step) }) { Text(grantLabel(step)) }
      }
    }
  }
}

/** Extra explanation for steps Android makes awkward. */
private fun stepHint(step: GrantStep): String? = when (step) {
  is GrantStep.Runtime ->
    if (step.permissions.any { "LOCATION" in it }) {
      "Location is granted in two steps: first while-in-use location and activity recognition, then " +
        "\"Allow all the time\", which Android only offers separately afterwards. Background access lets " +
        "the hourly run notice the places you visit without the app open."
    } else {
      null
    }
  GrantStep.BackgroundLocation ->
    "Step 2 of 2: Android shows background location on its own settings page. Choose \"Allow all the time\", then come back."
  is GrantStep.OpenSettings -> "Find Context in the list, turn access on, then come back."
  GrantStep.Health -> null
}

@Composable
private fun SyncCard(state: ScreenState, onSave: (String, String) -> Unit, onSyncNow: () -> Unit) {
  var url by rememberSaveable { mutableStateOf(state.supabaseUrl) }
  var token by rememberSaveable { mutableStateOf(state.syncToken) }
  SectionCard("Cloud sync") {
    Text(
      "Optional. Uploads a small daily summary to your own Supabase project. Only data marked sensitivity " +
        "0–1 is ever synced; health data and anything more sensitive stays on this device.",
      style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
      value = url,
      onValueChange = { url = it },
      label = { Text("Supabase URL") },
      placeholder = { Text("https://abcd.supabase.co") },
      singleLine = true,
      isError = url.isNotBlank() && !url.trim().startsWith("https://"),
      supportingText = { Text("Must start with https://") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
      modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
      value = token,
      onValueChange = { token = it },
      label = { Text("Sync token") },
      singleLine = true,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { onSave(url, token) }) { Text("Save") }
      OutlinedButton(onClick = onSyncNow, enabled = state.syncConfigured) { Text("Sync now") }
    }
    Text(if (state.syncConfigured) "Sync is configured." else "Sync is off: set an https URL and a token.")
    Text("Last sync: ${state.syncStatus}", style = MaterialTheme.typography.bodySmall)
  }
}

/** Starts [intent], returning false instead of crashing when nothing handles it. */
private fun startSafely(context: Context, intent: Intent): Boolean =
  try {
    context.startActivity(intent)
    true
  } catch (e: ActivityNotFoundException) {
    false
  } catch (e: SecurityException) {
    false
  }

/** Opens the Play Store listing so the user can install or update Health Connect. */
private fun openHealthConnectInStore(context: Context) {
  val market = Intent(
    Intent.ACTION_VIEW,
    "market://details?id=$HEALTH_CONNECT_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri(),
  ).setPackage("com.android.vending").putExtra("overlay", true).putExtra("callerId", context.packageName)
  if (!startSafely(context, market)) {
    startSafely(context, Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE".toUri()))
  }
}
