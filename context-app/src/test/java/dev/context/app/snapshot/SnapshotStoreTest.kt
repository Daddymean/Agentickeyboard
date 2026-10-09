package dev.context.app.snapshot

import dev.context.core.json.ContextJson
import dev.context.core.model.Sensitivity
import dev.context.core.model.Snapshot
import dev.context.core.model.Today
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SnapshotStoreTest {
  @get:Rule val tmp = TemporaryFolder()

  private val local = Snapshot(generatedAtMs = 10, today = Today(steps = 1200, places = listOf("Home")))
  private val syncable = Snapshot(generatedAtMs = 10, maxSensitivity = Sensitivity.PERSONAL)

  @Test
  fun servesAnEmptySnapshotBeforeTheFirstPublish() {
    val store = SnapshotStore(tmp.root)
    assertEquals(Snapshot.empty(store.local().generatedAtMs), store.local())
    assertNull(store.sync())
  }

  @Test
  fun publishedSnapshotsSurviveANewProcess() {
    SnapshotStore(tmp.root).publish(local, syncable)
    val reopened = SnapshotStore(tmp.root)
    assertEquals(ContextJson.encodeSnapshot(local), reopened.localJson())
    assertEquals(syncable, reopened.sync())
  }

  @Test
  fun neverStoresASnapshotThatMayNotSync() {
    val store = SnapshotStore(tmp.root)
    store.publish(local, syncable)
    // An unmarked (DEVICE_ONLY) sync snapshot replaces the old one with nothing.
    store.publish(local, Snapshot(generatedAtMs = 11))
    assertNull(store.sync())
    assertNull(SnapshotStore(tmp.root).sync())
  }

  @Test
  fun ignoresACorruptLocalFile() {
    tmp.root.resolve("local.json").writeText("{not json")
    assertEquals(emptyList<String>(), SnapshotStore(tmp.root).local().recentNotes)
  }
}
