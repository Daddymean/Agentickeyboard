package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.text.InputType
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TemplateClipboardTest {
    @Test fun `view model never reads clipboard for escaped tokens or secure fields`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
        var reads = 0
        vm.setClipboardProvider { reads++; "sample" }
        assertEquals("{clipboard}", vm.expandTemplate("{{clipboard}}").text)
        assertEquals("ordinary", vm.expandTemplate("ordinary").text)
        assertEquals(0, reads)
        assertEquals("sample sample", vm.expandTemplate("{CLIPBOARD} {clipboard}").text)
        assertEquals(1, reads)
        for (inputType in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )) {
            vm.onEditorStarted(null, inputType = inputType)
            assertEquals("", vm.expandTemplate("{clipboard}").text)
        }
        assertEquals(1, reads)
        vm.onEditorStarted(null, inputType = InputType.TYPE_CLASS_TEXT)
        vm.setClipboardProvider(null)
        assertEquals("", vm.expandTemplate("{clipboard}").text)
        assertEquals(1, reads)
    }
}
