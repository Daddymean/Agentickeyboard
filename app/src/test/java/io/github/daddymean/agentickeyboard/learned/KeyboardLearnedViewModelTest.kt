package io.github.daddymean.agentickeyboard.learned

import android.content.Context
import android.os.Looper
import android.text.InputType
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** KEYBOARD-047 review fixes that live in the ViewModel: one-time starter rules, live per-app style delete. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardLearnedViewModelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: KeyboardRepository
    private lateinit var settings: KeyboardSettings
    private val made = mutableListOf<KeyboardViewModel>()

    @Before
    fun setUp() {
        context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.SECURE_DELETE).build()
        repo = KeyboardRepository(db)
        settings = KeyboardSettings(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun data() = KeyboardLearnedData(db, settings, context.getSharedPreferences("kld_vm_test", Context.MODE_PRIVATE))
    private fun newVm() = KeyboardViewModel(repo, settings).also { made += it }

    /** Drives the main looper (viewModelScope) and lets Room's executor finish until [done]. */
    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun corrections() = runBlocking { db.learnedCorrectionDao().count() }

    @Test
    fun clearedCorrectionsStayClearedAfterANewViewModel() {
        newVm()
        waitFor("first-run seed") { corrections() == 3 && settings.isCorrectionsSeeded }
        runBlocking { data().clear(KeyboardStore.CORRECTIONS) }
        assertEquals(0, corrections())

        newVm()
        waitFor("second ViewModel's start-up") { runBlocking { db.shortcutDao().getAllShortcuts().first().isNotEmpty() } }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertEquals(0, corrections())

        runBlocking { data().clearAll() }
        newVm()
        repeat(30) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertEquals(0, corrections())
    }

    @Test
    fun anInstallFromBeforeTheFlagCountsAsSeeded() {
        // Upgrade: shortcuts were seeded on an earlier first run, rules since cleared, no flag yet.
        runBlocking { repo.insertShortcut(ShortcutTemplate(shortcut = "omw", template = "On my way!")) }
        newVm()
        waitFor("flag") { settings.isCorrectionsSeeded }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertEquals(0, corrections())
    }

    @Test
    fun deletedPerAppStyleStopsApplyingToTheCurrentEditor() {
        runBlocking { repo.upsertAppPersonas(listOf(AppPersona("com.slack", "Professional", "Slack"))) }
        val vm = newVm()
        vm.onEditorStarted("com.slack", "Slack", InputType.TYPE_CLASS_TEXT)
        waitFor("stored style applied") { vm.userPersonaPreference.value == "Professional" }

        val entry = runBlocking { data().entries(KeyboardStore.APP_STYLES, 10).single() }
        assertEquals(1, runBlocking { data().delete(entry) })
        waitFor("style dropped") { vm.userPersonaPreference.value == settings.persona }
        assertEquals(KeyboardViewModel.DEFAULT_PERSONA, vm.userPersonaPreference.value)

        // Clear works the same way.
        runBlocking { repo.upsertAppPersonas(listOf(AppPersona("com.slack", "Casual", "Slack"))) }
        vm.onEditorStarted("com.slack", "Slack", InputType.TYPE_CLASS_TEXT)
        waitFor("stored style applied again") { vm.userPersonaPreference.value == "Casual" }
        assertTrue(runBlocking { data().clear(KeyboardStore.APP_STYLES) } == 1)
        waitFor("style dropped after clear") { vm.userPersonaPreference.value == KeyboardViewModel.DEFAULT_PERSONA }
    }
}
