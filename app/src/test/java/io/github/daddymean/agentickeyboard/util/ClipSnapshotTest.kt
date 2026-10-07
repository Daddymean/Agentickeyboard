package io.github.daddymean.agentickeyboard.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KEYBOARD-004: the clip's text and its sensitivity flag must come from the same
 * ClipData read, so a clipboard change between reads cannot pair them wrongly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipSnapshotTest {

    private fun clip(text: String, sensitive: Boolean?): ClipData =
        ClipData.newPlainText("test", text).apply {
            if (sensitive != null) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipboardSensitivity.EXTRA_IS_SENSITIVE, sensitive)
                }
            }
        }

    @Test
    fun `flag and text are read from the same clip`() {
        val flagged = ClipboardSensitivity.snapshotOf(clip("hunter2", sensitive = true))
        assertEquals("hunter2", flagged.text)
        assertTrue(flagged.flaggedSensitive)

        val plain = ClipboardSensitivity.snapshotOf(clip("see you at noon", sensitive = null))
        assertEquals("see you at noon", plain.text)
        assertFalse(plain.flaggedSensitive)

        assertFalse(ClipboardSensitivity.snapshotOf(clip("x", sensitive = false)).flaggedSensitive)
    }

    @Test
    fun `primary clip replaced after a read keeps the earlier snapshot consistent`() {
        val manager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        manager.setPrimaryClip(clip("otp 123456", sensitive = true))
        val first = ClipboardSensitivity.readPrimaryClip(manager)
        manager.setPrimaryClip(clip("ordinary text", sensitive = null))
        val second = ClipboardSensitivity.readPrimaryClip(manager)

        assertEquals("otp 123456", first?.text)
        assertTrue(first!!.flaggedSensitive)
        assertEquals("ordinary text", second?.text)
        assertFalse(second!!.flaggedSensitive)
    }

    @Test
    fun `no primary clip yields no snapshot`() {
        val manager = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.clearPrimaryClip()
        assertNull(ClipboardSensitivity.readPrimaryClip(manager))
    }
}
