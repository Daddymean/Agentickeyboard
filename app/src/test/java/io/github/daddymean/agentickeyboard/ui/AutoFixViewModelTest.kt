package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.util.LocalSpelling
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** KEYBOARD-011: auto-fix through the view model, with undo and the Settings switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoFixViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var vm: KeyboardViewModel
    private val previous = LocalSpelling.shared

    private fun raw(name: String) = File("src/main/res/raw/$name").readLines().map { it.trim() }.filter { it.isNotEmpty() }

    @Before
    fun setUp() {
        LocalSpelling.shared = LocalSpelling(raw("wordlist.txt").take(10_000), raw("spelling_known_only.txt"), raw("spelling_extra.txt"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        vm = KeyboardViewModel(KeyboardRepository(db))
    }

    @After
    fun tearDown() {
        LocalSpelling.shared = previous
        db.close()
    }

    private fun applyAndUndo(word: String) {
        val fix = vm.resolveWordCommit(word)!!
        assertTrue(fix.fromAutoFix)
        vm.registerAutoCorrection(word, fix.replacement, fix.fromLearnedRule, fromAutoFix = true)
        assertEquals(word, vm.peekPendingUndo()?.original)
        vm.onUndoApplied()
    }

    @Test
    fun `on by default, and the switch turns it off`() {
        assertTrue(vm.isAutoFixEnabled.value)
        assertEquals("really", vm.resolveWordCommit("realy")?.replacement)
        assertEquals("Really", vm.resolveWordCommit("Realy", "")?.replacement)
        assertNull(vm.resolveWordCommit("Realy", "I met "))
        vm.setAutoFixEnabled(false)
        assertNull(vm.resolveWordCommit("realy"))
        vm.setAutoFixEnabled(true)
        assertEquals("really", vm.resolveWordCommit("realy")?.replacement)
    }

    @Test
    fun `undo keeps the word in this field, and a second undo means never`() {
        val text = android.text.InputType.TYPE_CLASS_TEXT
        vm.onEditorStarted("com.example", inputType = text)
        applyAndUndo("realy")
        assertNull(vm.resolveWordCommit("realy"))
        assertEquals("finally", vm.resolveWordCommit("finaly")?.replacement)
        vm.onEditorStarted("com.example", inputType = text)
        vm.onNewInputSession()
        applyAndUndo("realy")
        vm.onEditorStarted("com.example", inputType = text)
        vm.onNewInputSession()
        assertNull(vm.resolveWordCommit("realy"))
        assertEquals("finally", vm.resolveWordCommit("finaly")?.replacement)
    }

    @Test
    fun `first undo survives a restart but clears for a new input session`() {
        val text = android.text.InputType.TYPE_CLASS_TEXT
        vm.onEditorStarted("com.example", inputType = text)
        applyAndUndo("realy")

        vm.onEditorStarted("com.example", inputType = text)
        assertNull(vm.resolveWordCommit("realy"))

        vm.onNewInputSession()
        assertEquals("really", vm.resolveWordCommit("realy")?.replacement)
    }

    @Test
    fun `personal word outside top suggestions is still protected`() = runBlocking {
        db.userVocabularyDao().insertWords(
            (1..151).map { UserVocabulary("word$it", count = 4) } +
                UserVocabulary("realy", count = 3)
        )
        withTimeout(2_000) {
            while (vm.topVocabulary.value.size < 150) yield()
        }

        withTimeout(2_000) {
            while (vm.resolveWordCommit("realy") != null) yield()
        }
        assertNull(vm.resolveWordCommit("realy"))
    }

    @Test
    fun `paused corrections and sensitive fields never auto-fix`() {
        vm.setCorrectionsPaused(true)
        assertNull(vm.resolveWordCommit("realy"))
        vm.setCorrectionsPaused(false)
        vm.onEditorStarted("com.example", inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertNull(vm.resolveWordCommit("realy"))
    }
}
