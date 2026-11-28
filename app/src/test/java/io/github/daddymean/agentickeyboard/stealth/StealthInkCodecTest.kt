package io.github.daddymean.agentickeyboard.stealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class StealthInkCodecTest {

    // Low iteration count for fast unit tests; production uses DEFAULT_PBKDF2_ITERATIONS (210_000).
    private val testIter = 1_000
    private val t0 = 1_764_300_000L

    @Test
    fun envelopeRoundtripAndSurroundingQuoteExtraction() {
        val secret = "Meet at pier 7 — passcode 8492 🌊\nBring the blue folder."
        val sealed = StealthInkCodec.sealEnvelope(secret, "correct-horse-99", StealthTtl.NONE, t0, testIter)

        assertTrue(sealed.startsWith(StealthInkCodec.ENVELOPE_PREFIX))
        assertFalse(sealed.contains("pier 7"))
        assertEquals(StealthMode.ENVELOPE, StealthInkCodec.detect(sealed))

        // Embedded inside a quoted reply still extracts and opens cleanly.
        val quoted = "> Alice wrote:\n$sealed\nSent from SMS"
        assertEquals(StealthMode.ENVELOPE, StealthInkCodec.detect(quoted))

        val opened = StealthInkCodec.open(quoted, "correct-horse-99", t0 + 10, testIter)
        assertTrue("Expected Success, got $opened", opened is StealthOpenResult.Success)
        opened as StealthOpenResult.Success
        assertEquals(secret, opened.plaintext)
        assertEquals(StealthMode.ENVELOPE, opened.mode)
        assertNull(opened.expiresAtEpochSec)
        assertNull(opened.remainingSeconds)
    }

    @Test
    fun invisibleInkPreservesVisibleCoverAndRoundtripsThroughQuotes() {
        val cover = "Sounds good, talk soon!"
        val secret = "Gate code is 4419# — don't share."
        val hidden = StealthInkCodec.sealInvisibleInk(secret, "velvet-42", cover, StealthTtl.ONE_HOUR, t0, testIter)

        assertEquals(cover, StealthInkCodec.stripInvisibleInk(hidden))
        assertFalse(hidden.contains("4419"))
        assertEquals(StealthMode.INVISIBLE_INK, StealthInkCodec.detect(hidden))

        val wrappedInChat = "Re: dinner\n$hidden\n👍"
        assertEquals(StealthMode.INVISIBLE_INK, StealthInkCodec.detect(wrappedInChat))

        val opened = StealthInkCodec.open(wrappedInChat, "velvet-42", t0 + 600, testIter)
        assertTrue("Expected Success, got $opened", opened is StealthOpenResult.Success)
        opened as StealthOpenResult.Success
        assertEquals(secret, opened.plaintext)
        assertEquals(StealthMode.INVISIBLE_INK, opened.mode)
        assertEquals(t0 + 3_600L, opened.expiresAtEpochSec)
        assertEquals(3_000L, opened.remainingSeconds)
    }

    @Test
    fun sealingSamePlaintextTwiceProducesDistinctCiphertexts() {
        val a = StealthInkCodec.sealEnvelope("same secret", "passphrase1", StealthTtl.NONE, t0, testIter)
        val b = StealthInkCodec.sealEnvelope("same secret", "passphrase1", StealthTtl.NONE, t0, testIter)
        assertNotEquals(a, b)
    }

    @Test
    fun rejectsWrongPassphraseAndOrdinaryText() {
        val sealed = StealthInkCodec.sealEnvelope("top secret", "right-key", StealthTtl.NONE, t0, testIter)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(sealed, "wrong-key", t0, testIter)
        )
        assertNull(StealthInkCodec.detect("Just an ordinary chat message!"))
        assertEquals(
            StealthOpenResult.Malformed,
            StealthInkCodec.open("Just an ordinary chat message!", "right-key", t0, testIter)
        )
    }

    @Test
    fun authenticatedTtlExpiresAndBlocksHeaderTampering() {
        val sealed = StealthInkCodec.sealEnvelope("burn after 5m", "ttl-pass", StealthTtl.FIVE_MIN, t0, testIter)

        // Valid right up to the expiry boundary.
        val valid = StealthInkCodec.open(sealed, "ttl-pass", t0 + 300L, testIter)
        assertTrue(valid is StealthOpenResult.Success)
        assertEquals(0L, (valid as StealthOpenResult.Success).remainingSeconds)

        // Rejected 1 second past expiry without revealing plaintext.
        val expired = StealthInkCodec.open(sealed, "ttl-pass", t0 + 301L, testIter)
        assertEquals(StealthOpenResult.Expired(t0 + 300L), expired)

        // Tampering with the expiry timestamp bytes in the header MUST fail GCM AAD verification.
        val raw = Base64.getUrlDecoder().decode(sealed.removePrefix(StealthInkCodec.ENVELOPE_PREFIX))
        raw[10] = (raw[10].toInt() xor 0xFF).toByte() // flip low byte of expiresAtEpochSec
        val tampered = StealthInkCodec.ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(tampered, "ttl-pass", t0, testIter)
        )
    }

    @Test
    fun bitFlipsInCiphertextTagModeOrVersionAreRejected() {
        val sealed = StealthInkCodec.sealEnvelope("integrity check", "key-1234", StealthTtl.NONE, t0, testIter)
        val original = Base64.getUrlDecoder().decode(sealed.removePrefix(StealthInkCodec.ENVELOPE_PREFIX))

        // Flip version byte -> UnsupportedVersion
        val badVersion = original.clone().also { it[0] = 0x09 }
        val badVerStr = StealthInkCodec.ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(badVersion)
        assertEquals(
            StealthOpenResult.UnsupportedVersion(9),
            StealthInkCodec.open(badVerStr, "key-1234", t0, testIter)
        )

        // Flip mode byte (ENVELOPE -> INVISIBLE_INK) -> rejected
        val badMode = original.clone().also { it[1] = StealthMode.INVISIBLE_INK.code }
        val badModeStr = StealthInkCodec.ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(badMode)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(badModeStr, "key-1234", t0, testIter)
        )

        // Flip last byte (GCM auth tag) -> rejected
        val badTag = original.clone().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }
        val badTagStr = StealthInkCodec.ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(badTag)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(badTagStr, "key-1234", t0, testIter)
        )
    }

    @Test
    fun compressesLongRepetitivePayloadsCleanly() {
        val longSecret = "Confidential schedule update for Q4 release. ".repeat(20)
        val sealed = StealthInkCodec.sealEnvelope(longSecret, "compress-key", StealthTtl.NONE, t0, testIter)
        // Compressed envelope should be dramatically smaller than raw plaintext.
        assertTrue("Expected compressed envelope < 250 chars, got ${sealed.length}", sealed.length < 250)

        val opened = StealthInkCodec.open(sealed, "compress-key", t0, testIter)
        assertTrue(opened is StealthOpenResult.Success)
        assertEquals(longSecret, (opened as StealthOpenResult.Success).plaintext)
    }

    @Test
    fun strengthAndRemainingFormatters() {
        assertEquals(PassphraseStrength.TOO_SHORT, StealthInkCodec.passphraseStrength("abc"))
        assertEquals(PassphraseStrength.FAIR, StealthInkCodec.passphraseStrength("word"))
        assertEquals(PassphraseStrength.GOOD, StealthInkCodec.passphraseStrength("blue2026"))
        assertEquals(PassphraseStrength.STRONG, StealthInkCodec.passphraseStrength("Correct-Horse-99!"))

        assertEquals("expired", StealthInkCodec.formatRemaining(0))
        assertEquals("45s", StealthInkCodec.formatRemaining(45))
        assertEquals("4m 12s", StealthInkCodec.formatRemaining(252))
        assertEquals("2h 5m", StealthInkCodec.formatRemaining(7_500))
    }

    @Test
    fun stealthInkHasZeroNetworkDiskOrTwinImports() {
        val roots = listOf(File("app/src/main/java"), File("src/main/java"))
        val srcRoot = roots.first { it.exists() }
        val files = listOf(
            File(srcRoot, "io/github/daddymean/agentickeyboard/stealth/StealthInkCodec.kt"),
            File(srcRoot, "io/github/daddymean/agentickeyboard/ui/StealthInkPanel.kt")
        )
        val forbidden = listOf(
            "okhttp3", "retrofit2", "java.net.", "SharedPreferences", "FileOutputStream",
            "RoomDatabase", "agentickeyboard.twin.", "agentickeyboard.network."
        )
        for (file in files) {
            assertTrue("Expected source file: ${file.path}", file.exists())
            val source = file.readText()
            for (bad in forbidden) {
                assertFalse("${file.name} must not reference $bad", source.contains(bad))
            }
        }
    }
}
