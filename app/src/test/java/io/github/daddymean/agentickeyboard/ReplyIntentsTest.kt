package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.ReplyIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyIntentsTest {

    @Test
    fun everyIntentHasADirectiveAndThreeOfflineReplies() {
        for (intent in ReplyIntents.ALL) {
            assertTrue("directive missing for $intent", ReplyIntents.promptDirective(intent).isNotBlank())
            val replies = ReplyIntents.offlineReplies(intent)
            assertNotNull("offline replies missing for $intent", replies)
            assertEquals("expected 3 offline replies for $intent", 3, replies!!.size)
        }
    }

    @Test
    fun salesIntentsAreAvailable() {
        assertTrue(ReplyIntents.ALL.contains("Counteroffer"))
        assertTrue(ReplyIntents.ALL.contains("Close sale"))
        assertTrue(ReplyIntents.promptDirective("Counteroffer").contains("counteroffer", ignoreCase = true))
        assertTrue(ReplyIntents.promptDirective("Close sale").contains("close", ignoreCase = true))
    }

    @Test
    fun legacyCloseIntentStillWorksAsAlias() {
        assertTrue(ReplyIntents.promptDirective("Close").isNotBlank())
        assertEquals(3, ReplyIntents.offlineReplies("Close")!!.size)
    }

    @Test
    fun unknownIntentYieldsNoDirectiveAndNoCannedReplies() {
        assertEquals("", ReplyIntents.promptDirective("Escalate"))
        assertEquals("", ReplyIntents.promptDirective(""))
        assertNull(ReplyIntents.offlineReplies("Escalate"))
        assertNull(ReplyIntents.offlineReplies(""))
    }
}
