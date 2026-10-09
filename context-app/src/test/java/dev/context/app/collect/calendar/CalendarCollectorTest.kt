package dev.context.app.collect.calendar

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// A plain Application: ContextApp.onCreate would schedule WorkManager jobs.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CalendarCollectorTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun needsReadCalendarUntilGranted() = runTest {
    val collector = CalendarCollector(app)
    assertEquals(listOf(Manifest.permission.READ_CALENDAR), collector.missingPermissions())
    shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
    assertTrue(collector.missingPermissions().isEmpty())
  }

  @Test
  fun noProviderMeansNoEvents() = runTest {
    shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
    assertTrue(CalendarCollector(app).collect(0, 1_000).isEmpty())
  }
}
