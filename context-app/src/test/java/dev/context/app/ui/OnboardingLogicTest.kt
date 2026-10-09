package dev.context.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {
  private val fine = "android.permission.ACCESS_FINE_LOCATION"
  private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
  private val activity = "android.permission.ACTIVITY_RECOGNITION"
  private val usage = "android.settings.USAGE_ACCESS_SETTINGS"

  @Test
  fun healthPermissionsGoThroughHealthConnect() {
    assertEquals(GrantStep.Health, nextGrantStep(listOf("android.permission.health.READ_STEPS", fine)))
  }

  @Test
  fun foregroundLocationComesBeforeBackground() {
    assertEquals(
      GrantStep.Runtime(listOf(fine, coarse, activity)),
      nextGrantStep(listOf(fine, coarse, activity, BACKGROUND_LOCATION)),
    )
    assertEquals(GrantStep.BackgroundLocation, nextGrantStep(listOf(BACKGROUND_LOCATION)))
  }

  @Test
  fun settingsActionsOpenTheirScreen() {
    assertEquals(GrantStep.OpenSettings(usage), nextGrantStep(listOf("needs a reason", usage)))
  }

  @Test
  fun plainReasonsHaveNoGrantStep() {
    assertNull(nextGrantStep(listOf("collector not implemented yet")))
    assertNull(nextGrantStep(emptyList()))
    assertFalse(isActionable("collector not implemented yet"))
    assertTrue(isActionable(usage))
    assertTrue(isActionable("android.permission.READ_CALENDAR"))
  }

  @Test
  fun describesMissingItems() {
    assertEquals("READ_CALENDAR", describeMissing("android.permission.READ_CALENDAR"))
    assertEquals("Health: READ_STEPS", describeMissing("android.permission.health.READ_STEPS"))
    assertEquals("Usage access", describeMissing(usage))
    assertEquals("Settings: WIFI_SETTINGS", describeMissing("android.settings.WIFI_SETTINGS"))
    assertEquals("free text", describeMissing("free text"))
  }

  @Test
  fun recognisesHealthRationaleActions() {
    assertTrue(isHealthRationaleAction("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"))
    assertTrue(isHealthRationaleAction("android.intent.action.VIEW_PERMISSION_USAGE"))
    assertFalse(isHealthRationaleAction("android.intent.action.MAIN"))
    assertFalse(isHealthRationaleAction(null))
  }

  @Test
  fun formatsStatuses() {
    val now = 10L * 24 * 60 * 60 * 1000
    assertEquals("never run", formatStatus(null, 0, now))
    assertEquals("never run", formatStatus(" ", now, now))
    assertEquals("ok", formatStatus("ok", 0, now))
    assertEquals("ok · just now", formatStatus("ok", now - 30_000, now))
    assertEquals("ok · 5 min ago", formatStatus("ok", now - 5 * 60_000, now))
    assertEquals("ok · 3 h ago", formatStatus("ok", now - 3 * 60 * 60_000, now))
    assertEquals("ok · 4 d ago", formatStatus("ok", now - 4L * 24 * 60 * 60_000, now))
    assertEquals("ok · just now", formatStatus("ok", now + 60_000, now))
  }

  @Test
  fun prettyPrintsJsonAndKeepsInvalidTextAsIs() {
    assertEquals("{\n    \"a\": 1\n}", prettyJson("{\"a\":1}"))
    assertEquals("not json {", prettyJson("not json {"))
  }
}
