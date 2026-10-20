package io.github.daddymean.agentickeyboard.twin.deadman

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import kotlin.random.Random

/** KEYBOARD-027 row 1. */
class ShamirTest {
    private val rnd = SecureRandom()

    @Test
    fun fieldArithmeticIsAField() {
        for (a in 1..255) {
            assertEquals(1, Shamir.mul(a, Shamir.div(1, a)))
            for (b in listOf(1, 2, 3, 0x53, 0xCA, 255)) assertEquals(a, Shamir.div(Shamir.mul(a, b), b))
        }
        assertEquals(0xC1, Shamir.mul(0x57, 0x83)) // FIPS-197 example
    }

    @Test
    fun anyTwoOfThreeRecoverTheSecret() {
        repeat(50) {
            val secret = ByteArray(32).also(rnd::nextBytes)
            val shares = Shamir.split(secret, 3, 2, rnd)
            assertEquals(listOf(1, 2, 3), shares.map { it.x })
            for (i in 0..2) for (j in 0..2) if (i != j) assertArrayEquals(secret, Shamir.combine(listOf(shares[i], shares[j])))
            assertArrayEquals(secret, Shamir.combine(shares))
        }
    }

    @Test
    fun oneShareRevealsNothing() {
        // For a 2-of-3 split, share 1 = s + a (per byte, a uniform): every secret is equally
        // consistent with it. Empirically, share-1 bytes of a constant secret are uniform.
        val counts = IntArray(256)
        val secret = ByteArray(1) { 0x42 }
        repeat(256 * 200) { counts[Shamir.split(secret, 3, 2, rnd)[0].y[0].toInt() and 0xFF]++ }
        assertTrue(counts.min() > 120 && counts.max() < 300)
        // And pairing one real share with a forged one gives a different secret.
        val s = ByteArray(32).also(rnd::nextBytes)
        val sh = Shamir.split(s, 3, 2, rnd)
        val forged = Shamir.Share(2, ByteArray(32).also(rnd::nextBytes))
        assertFalse(s.contentEquals(Shamir.combine(listOf(sh[0], forged))))
    }

    @Test
    fun badInputsAreRejected() {
        val sh = Shamir.split(ByteArray(32) { 1 }, 3, 2, rnd)
        assertTrue(runCatching { Shamir.combine(listOf(sh[0])) }.isFailure)
        assertTrue(runCatching { Shamir.combine(listOf(sh[0], sh[0])) }.isFailure)
        assertTrue(runCatching { Shamir.combine(listOf(sh[0], Shamir.Share(2, ByteArray(31)))) }.isFailure)
        assertTrue(runCatching { Shamir.split(ByteArray(4), 3, 1) }.isFailure)
    }

    @Test
    fun cardsRoundTripAndCatchTypos() {
        val sh = Shamir.split(ByteArray(32).also(rnd::nextBytes), 3, 2, rnd)
        val card = ShareCard("0123456789abcdef", 4, 2, 3, sh[1])
        val text = card.encode()
        assertTrue(text.startsWith("AKDM1-0123456789ABCDEF-4-2OF3-2-"))
        assertEquals(card, ShareCard.parse(text))
        // Spaces, line breaks and lower case are fine.
        assertEquals(card, ShareCard.parse(text.lowercase().chunked(7).joinToString(" \n")))
        // Any single changed character is caught.
        val r = Random(1)
        repeat(200) {
            val i = r.nextInt(text.length)
            if (text[i] == '-') return@repeat
            val c = if (text[i] == 'A') 'B' else 'A'
            val typo = text.substring(0, i) + c + text.substring(i + 1)
            assertTrue(typo, runCatching { ShareCard.parse(typo) }.isFailure)
        }
        assertTrue(runCatching { ShareCard.parse("hello") }.isFailure)
    }
}
