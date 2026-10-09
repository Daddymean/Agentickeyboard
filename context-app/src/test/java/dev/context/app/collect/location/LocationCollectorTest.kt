package dev.context.app.collect.location

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.context.app.collect.EventIds
import dev.context.core.model.EventTypes
import dev.context.core.model.EventValidator
import dev.context.core.model.Sensitivity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

// A plain Application: ContextApp.onCreate would schedule WorkManager jobs.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LocationCollectorTest {
  private lateinit var app: Application
  private lateinit var buffer: LocationBuffer
  private val min = 60_000L
  private val day = 24 * 60 * min
  private val now = 10 * day
  private var geocodeCalls = 0

  @Before
  fun setUp() {
    app = ApplicationProvider.getApplicationContext()
    buffer = LocationBuffer(File(app.noBackupFilesDir, "location-test-${System.nanoTime()}"))
    geocodeCalls = 0
  }

  private fun collector(label: String? = "1 Example Street") = LocationCollector(
    app,
    buffer,
    { _, _ -> geocodeCalls++; label },
    { now },
  )

  @Test
  fun reportsEveryMissingPermission() = runTest {
    assertEquals(LocationCollector.PERMISSIONS, collector().missingPermissions())

    // Approximate location alone is not enough for visits.
    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
    assertEquals(
      listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
      ),
      collector().missingPermissions(),
    )

    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

    shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
    assertTrue(collector().missingPermissions().isEmpty())
  }

  @Test
  fun turnsTheBufferIntoClosedEvents() = runTest {
    val t0 = now - 90 * min
    buffer.appendFixes(
      listOf(
        Fix(t0, 52.520_04, 13.405_04, 20f),
        Fix(t0 + 15 * min, 52.520_04, 13.405_04, 20f),
        Fix(t0 + 30 * min, 52.520_04, 13.405_04, 20f),
        Fix(t0 + 45 * min, 52.53, 13.405, 20f), // left: closes the stay, opens another
        Fix(t0 + 60 * min, 52.53, 13.405, 20f),
      ),
    )
    buffer.appendTransitions(
      listOf(
        Transition(t0, Activities.STILL, enter = true),
        Transition(t0 + 40 * min, Activities.STILL, enter = false),
        Transition(t0 + 40 * min, Activities.WALKING, enter = true), // still open
      ),
    )

    val events = collector().collect(now - 2 * 60 * min, now)
    assertTrue(events.all { EventValidator.validate(it) == null })

    val visit = events.single { it.type == EventTypes.LOCATION_VISIT }
    assertEquals(t0, visit.startMs)
    assertEquals(t0 + 30 * min, visit.endMs)
    assertEquals(LocationCollector.SOURCE_LOCATION, visit.source)
    assertEquals(Sensitivity.PRIVATE, visit.sensitivity)
    assertEquals(now, visit.createdMs)
    assertEquals(EventIds.stable(EventTypes.LOCATION_VISIT, t0, 52.52, 13.405), visit.id)
    val payload = Json.parseToJsonElement(visit.payload).jsonObject
    assertEquals(52.52, payload.getValue("lat").jsonPrimitive.double, 0.0)
    assertEquals(13.405, payload.getValue("lng").jsonPrimitive.double, 0.0)
    assertEquals(StayDetector.MIN_RADIUS_M, payload.getValue("radiusM").jsonPrimitive.int)
    assertEquals("1 Example Street", payload.getValue("label").jsonPrimitive.content)

    val activity = events.single { it.type == EventTypes.LOCATION_ACTIVITY }
    assertEquals(t0, activity.startMs)
    assertEquals(t0 + 40 * min, activity.endMs)
    assertEquals(LocationCollector.SOURCE_ACTIVITY, activity.source)
    assertEquals(Sensitivity.PERSONAL, activity.sensitivity)
    assertEquals(EventIds.stable(EventTypes.LOCATION_ACTIVITY, Activities.STILL, t0), activity.id)
    assertEquals(Activities.STILL, Json.parseToJsonElement(activity.payload).jsonObject.getValue("activity").jsonPrimitive.content)
  }

  @Test
  fun reRunningGivesTheSameIdsAndTrimsProcessedData() = runTest {
    val t0 = now - 20 * 60 * min
    buffer.appendFixes(
      listOf(
        Fix(t0, 1.0, 1.0, 20f), Fix(t0 + 20 * min, 1.0, 1.0, 20f), // old stay
        Fix(t0 + 30 * min, 2.0, 2.0, 20f), Fix(t0 + 60 * min, 2.0, 2.0, 20f), // closes at t0 + 18 h
        Fix(t0 + 18 * 60 * min, 1.0, 1.0, 20f), Fix(t0 + 18 * 60 * min + 15 * min, 1.0, 1.0, 20f), // open
      ),
    )
    val first = collector().collect(now - 2 * 60 * min, now)
    assertEquals(listOf(t0 + 30 * min), first.map { it.startMs })
    // The old stay (closed long before the window) is gone; the reported one and the open one remain.
    assertEquals(listOf(t0 + 30 * min, t0 + 60 * min, t0 + 18 * 60 * min, t0 + 18 * 60 * min + 15 * min), buffer.readFixes().map { it.timeMs })

    val second = collector().collect(now - 2 * 60 * min, now)
    assertEquals(first.map { it.id }, second.map { it.id })
  }

  @Test
  fun labelIsNullWhenGeocodingFails() = runTest {
    val t0 = now - 60 * min
    buffer.appendFixes(listOf(Fix(t0, 3.0, 3.0, 20f), Fix(t0 + 20 * min, 3.0, 3.0, 20f), Fix(t0 + 30 * min, 4.0, 4.0, 20f), Fix(t0 + 40 * min, 4.0, 4.0, 20f)))
    val visit = collector(label = null).collect(now - 2 * 60 * min, now).single()
    assertEquals(JsonNull, Json.parseToJsonElement(visit.payload).jsonObject.getValue("label"))
    assertEquals(1, geocodeCalls)
  }
}
