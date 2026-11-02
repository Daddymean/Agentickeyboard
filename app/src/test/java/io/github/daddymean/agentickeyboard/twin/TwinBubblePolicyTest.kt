package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinBubblePolicyTest {
    private fun state(
        enabled: Boolean = true,
        overlayGranted: Boolean = true,
        keyboardShown: Boolean = true,
        sensitiveField: Boolean = false,
        twinFieldBlocked: Boolean = false
    ) = TwinBubblePolicy.State(enabled, overlayGranted, keyboardShown, sensitiveField, twinFieldBlocked)

    @Test fun `shown only when everything allows it`() = assertTrue(TwinBubblePolicy.visible(state()))
    @Test fun `off by default`() = assertFalse(TwinBubblePolicy.DEFAULT_ENABLED)
    @Test fun `hidden when turned off`() = assertFalse(TwinBubblePolicy.visible(state(enabled = false)))
    @Test fun `hidden without the overlay grant`() = assertFalse(TwinBubblePolicy.visible(state(overlayGranted = false)))
    @Test fun `hidden when the keyboard is not showing, so its field is always known`() =
        assertFalse(TwinBubblePolicy.visible(state(keyboardShown = false)))
    @Test fun `hidden over password and incognito fields`() = assertFalse(TwinBubblePolicy.visible(state(sensitiveField = true)))
    @Test fun `hidden over secure apps and sensitive hints`() = assertFalse(TwinBubblePolicy.visible(state(twinFieldBlocked = true)))
}
