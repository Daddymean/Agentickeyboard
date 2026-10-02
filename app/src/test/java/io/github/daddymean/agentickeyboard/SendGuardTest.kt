package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.SendGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendGuardTest {

    @Test
    fun blankAndOrdinaryDraftsPassThrough() {
        assertFalse(SendGuard.shouldWarn(""))
        assertFalse(SendGuard.shouldWarn("   "))
        assertFalse(SendGuard.shouldWarn("\n"))
        assertFalse(SendGuard.shouldWarn("\t\t"))
        assertFalse(SendGuard.shouldWarn("Running 10 minutes late, sorry!"))
        assertFalse(SendGuard.shouldWarn("Thanks, that works for me."))
        assertFalse(SendGuard.shouldWarn("Can you resend the invoice?"))
    }

    @Test
    fun hostileDraftsAreHeldBack() {
        assertTrue(SendGuard.shouldWarn("I am sick of this, you are useless"))
        assertTrue(SendGuard.shouldWarn("This is a stupid, ridiculous plan"))
        assertTrue(SendGuard.shouldWarn("WHY WOULD YOU EVER DO SOMETHING LIKE THIS"))
    }

    @Test
    fun mildFrustrationDoesNotTrip() {
        // A single sharp word or excited punctuation alone should not block sends.
        assertFalse(SendGuard.shouldWarn("That deadline is ridiculous but fine"))
        assertFalse(SendGuard.shouldWarn("So excited!! See you tonight"))
    }

    @Test
    fun onlyHighRiskIsHeldBack() {
        // One hostile word scores 2 (medium): not enough on its own.
        assertFalse(SendGuard.shouldWarn("This is completely stupid"))
        // A doubled "!!" or "??" adds 1 and tips it to high.
        assertTrue(SendGuard.shouldWarn("This is completely stupid!!"))
        assertTrue(SendGuard.shouldWarn("Are you an idiot??"))
    }
}
