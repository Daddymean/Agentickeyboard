package io.github.daddymean.agentickeyboard.twin.deadman

import io.github.daddymean.agentickeyboard.twin.TwinInsertionPathGuardTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class DeadManDrillTest {
    private val rng = SecureRandom(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    @Test fun anyTwoOfThreeDrillCardsPass() {
        val s = DeadManDrill.start(rng)
        assertTrue(s.cards.all { it.encode().startsWith(DeadManDrill.DRILL_PREFIX) })
        val pairs = listOf(0 to 1, 0 to 2, 1 to 2)
        for ((a, b) in pairs) {
            val r = DeadManDrill.reconstruct(s, listOf(s.cards[a].encode(), s.cards[b].encode()))
            assertTrue("pair $a,$b: ${r.message}", r.ok)
        }
    }

    @Test fun refusesLiveCardWithoutDrillPrefix() {
        val s = DeadManDrill.start(rng)
        val liveLooking = s.cards[0].card.encode() // no DRILL-ONLY prefix
        val r = DeadManDrill.reconstruct(s, listOf(liveLooking, s.cards[1].encode()))
        assertFalse(r.ok)
        assertTrue(r.message.contains("DRILL-ONLY"))
    }

    @Test fun oneCardIsNotEnough() {
        val s = DeadManDrill.start(rng)
        val r = DeadManDrill.reconstruct(s, listOf(s.cards[0].encode()))
        assertFalse(r.ok)
    }

    @Test fun neverTouchesStoreInSource() {
        val file = TwinInsertionPathGuardTest.mainRoot()
            .resolve("io/github/daddymean/agentickeyboard/twin/deadman/DeadManDrill.kt")
        val text = file.readText()
        assertFalse(text.contains("DeadManStore"))
        assertFalse(text.contains("TwinVault"))
        assertFalse(text.contains("DeadManController"))
        assertTrue(text.contains("synthetic") || text.contains("fake"))
    }
}
