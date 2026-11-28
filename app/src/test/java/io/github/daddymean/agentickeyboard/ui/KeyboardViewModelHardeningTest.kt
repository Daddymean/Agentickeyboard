package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.network.GeminiManager
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardViewModelHardeningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: KeyboardRepository
    private lateinit var settings: KeyboardSettings
    private val created = mutableListOf<KeyboardViewModel>()
    private var previousApiKey: String? = null

    @Before
    fun setUp() {
        previousApiKey = GeminiManager.userApiKey
        GeminiManager.userApiKey = null
        context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = KeyboardRepository(db)
        settings = KeyboardSettings(context)
    }

    @After
    fun tearDown() {
        GeminiManager.userApiKey = previousApiKey
        val clear = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").apply {
            isAccessible = true
        }
        created.forEach { runCatching { clear.invoke(it) } }
        created.clear()
        db.close()
    }

    @Test
    fun rewriteWithStylePreservesOriginalSourceAcrossRefinements() = runBlocking {
        settings.isOfflineMode = true
        val viewModel = KeyboardViewModel(repo, settings).also { created += it }
        shadowOf(Looper.getMainLooper()).idle()

        val originalDraft = "Please let me know if this works for you"
        val firstRewrite = "Let me know if that works"
        viewModel.rewriteWithStyle(
            text = firstRewrite,
            styleInstruction = "Shorter",
            source = originalDraft
        )

        val panel = withTimeout(5_000) {
            viewModel.aiPanelState.first { state ->
                shadowOf(Looper.getMainLooper()).idle()
                state is AiPanelState.Rewrite
            }
        } as AiPanelState.Rewrite

        assertEquals(originalDraft, panel.original)
        assertEquals(originalDraft, panel.sourceText)
        assertEquals("Shorter", panel.styleLabel)
    }

    @Test
    fun recordWordsUsageBatchesAndIncrementsAtomically() = runBlocking {
        repo.recordWordsUsage(listOf("keyboard", "refactor", "keyboard", "compose"))

        assertEquals(2, repo.getWord("keyboard")?.count)
        assertEquals(1, repo.getWord("refactor")?.count)
        assertEquals(1, repo.getWord("compose")?.count)
    }

    @Test
    fun geminiManagerPropagatesCancellationInsteadOfFallingBack() = runBlocking {
        val cancelled = Job().apply { cancel() }
        try {
            withContext(cancelled) {
                GeminiManager.rewriteWithTone("hello world", "Professional")
            }
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }
}
