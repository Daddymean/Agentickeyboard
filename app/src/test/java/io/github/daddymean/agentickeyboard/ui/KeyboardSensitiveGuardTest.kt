package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.text.InputType
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mika-inspired takeover guard: card / one-time-code hints block the keyboard AI
 * path (not only the twin), and the opt-in AI-apply confirm arms and clears.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardSensitiveGuardTest {

    private val text = InputType.TYPE_CLASS_TEXT
    private lateinit var db: AppDatabase
    private lateinit var vm: KeyboardViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        vm = KeyboardViewModel(KeyboardRepository(db))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `card hint blocks keyboard AI`() {
        vm.onEditorStarted("shop.app", "Shop", text, 0, hints = listOf("Card number"))
        assertTrue(vm.isSensitiveField.value)
    }

    @Test
    fun `otp autofill hint and number code field block keyboard AI`() {
        vm.onEditorStarted("bank.app", "Bank", text, 0, hints = listOf(null, "smsOTPCode"))
        assertTrue(vm.isSensitiveField.value)
        vm.onEditorStarted("bank.app", "Bank", InputType.TYPE_CLASS_NUMBER, 0, hints = listOf("Enter code"))
        assertTrue(vm.isSensitiveField.value)
    }

    @Test
    fun `ordinary hint stays normal`() {
        vm.onEditorStarted("chat.app", "Chat", text, 0, hints = listOf("Type a message"))
        assertFalse(vm.isSensitiveField.value)
    }

    @Test
    fun `apply confirm is off by default and applies directly`() {
        assertFalse(vm.isAiApplyConfirmEnabled.value)
        assertFalse(vm.requestAiApply("Hello there"))
        assertNull(vm.pendingAiApply.value)
    }

    @Test
    fun `apply confirm arms with where and what, then confirm clears`() {
        vm.onEditorStarted("chat.app", "Chat", text, 0)
        vm.setAiApplyConfirmEnabled(true)
        assertTrue(vm.requestAiApply("Hello there"))
        val pending = vm.pendingAiApply.value!!
        assertEquals("Chat", pending.where)
        assertEquals("Hello there", pending.preview)
        assertTrue(vm.confirmAiApply())
        assertNull(vm.pendingAiApply.value)
        assertFalse(vm.confirmAiApply())
    }

    @Test
    fun `apply confirm clears on cancel, dismiss, new editor and disable`() {
        vm.onEditorStarted("chat.app", "Chat", text, 0)
        vm.setAiApplyConfirmEnabled(true)
        vm.requestAiApply("a"); vm.cancelAiApply(); assertNull(vm.pendingAiApply.value)
        vm.requestAiApply("a"); vm.dismissResults(); assertNull(vm.pendingAiApply.value)
        vm.requestAiApply("a"); vm.onEditorStarted("x", "X", text, 0); assertNull(vm.pendingAiApply.value)
        vm.requestAiApply("a"); vm.setAiApplyConfirmEnabled(false); assertNull(vm.pendingAiApply.value)
    }

    @Test
    fun `long preview is shortened and missing app label falls back`() {
        vm.onEditorStarted(null, "", text, 0)
        vm.setAiApplyConfirmEnabled(true)
        vm.requestAiApply("x".repeat(200))
        val pending = vm.pendingAiApply.value!!
        assertEquals("this app", pending.where)
        assertTrue(pending.preview.length <= KeyboardViewModel.AI_APPLY_PREVIEW_CHARS + 1)
        assertTrue(pending.preview.endsWith("…"))
    }
}
