package com.example

import com.example.util.ReplyIntents
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
    fun unknownIntentYieldsNoDirectiveAndNoCannedReplies() {
        assertEquals("", ReplyIntents.promptDirective("Escalate"))
        assertEquals("", ReplyIntents.promptDirective(""))
        assertNull(ReplyIntents.offlineReplies("Escalate"))
        assertNull(ReplyIntents.offlineReplies(""))
    }
}
