package io.github.daddymean.agentickeyboard.twin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.journal.InkModelGate
import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.JournalRecord
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KEYBOARD-046: defaults after upgrading a phone that already has twin settings
 * from before #190–#192. The bubble must stay off and handwriting recognition must
 * stay off until Keith turns each one on himself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinUpgradeDefaultsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // A pre-#192 settings file: twin on, an older tier announced, no bubble key.
        context.getSharedPreferences(TwinPreferences.PREFS, Context.MODE_PRIVATE).edit().clear()
            .putBoolean(TwinPreferences.KEY_ENABLED, true)
            .putInt(TwinPreferences.KEY_SIZE_TIER, 2)
            .commit()
    }

    @Test fun bubbleIsOffAfterUpgrade() {
        val prefs = TwinPreferences(context)
        assertFalse(prefs.isBubbleEnabled)
        assertTrue("existing settings survive", prefs.isEnabled)
    }

    @Test fun bubbleNeverShowsWhenOffEvenIfEverythingElseAllows() {
        val prefs = TwinPreferences(context)
        assertFalse(TwinBubblePolicy.visible(TwinBubblePolicy.State(prefs.isBubbleEnabled, overlayGranted = true, keyboardShown = true, sensitiveField = false, twinFieldBlocked = false)))
    }

    @Test fun bubbleTurnsOnOnlyWhenSetAndBackOff() {
        val prefs = TwinPreferences(context)
        prefs.isBubbleEnabled = true
        assertTrue(TwinPreferences(context).isBubbleEnabled)
        prefs.isBubbleEnabled = false
        assertFalse(TwinPreferences(context).isBubbleEnabled)
    }

    @Test fun neverLearnListIsEmptyAfterUpgradeAndActionsNotRemembered() {
        assertTrue(TwinPreferences(context).secureApps.isEmpty())
        assertTrue(TwinActionPolicy.of(context).remembered().isEmpty())
    }

    private fun ev(body: JSONObject, id: Long) = JournalRecord(id, id, JournalKinds.EVENT, body)

    @Test fun handwritingStaysOffUntilEnabled() {
        assertFalse(InkModelGate.approved(emptyList()))
        // Unrelated journal events (older builds) never count as approval.
        val other = listOf(ev(JSONObject().put("type", "reminder"), 1), ev(JSONObject().put("type", "ink_model_shown"), 2))
        assertFalse(InkModelGate.approved(other))
        assertFalse(InkModelGate.approved(other + ev(JSONObject().put("type", InkModelGate.EVENT), 3)))
        assertTrue(InkModelGate.approved(other + ev(InkModelGate.approval(true), 4)))
        assertFalse("turning it off wins", InkModelGate.approved(other + ev(InkModelGate.approval(true), 4) + ev(InkModelGate.approval(false), 5)))
    }
}
