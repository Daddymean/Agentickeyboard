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

    // Low iteration count for fast unit tests; production uses DEFAULT_PBKDF2_ITERATIONS (600_000).
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
        val hidden = StealthInkCodec.sealInvisibleInk(secret, "velvet-moon-42", cover, StealthTtl.ONE_HOUR, t0, testIter)

        assertEquals(cover, StealthInkCodec.stripInvisibleInk(hidden))
        assertFalse(hidden.contains("4419"))
        assertEquals(StealthMode.INVISIBLE_INK, StealthInkCodec.detect(hidden))

        val wrappedInChat = "Re: dinner\n$hidden\n👍"
        assertEquals(StealthMode.INVISIBLE_INK, StealthInkCodec.detect(wrappedInChat))

        val opened = StealthInkCodec.open(wrappedInChat, "velvet-moon-42", t0 + 600, testIter)
        assertTrue("Expected Success, got $opened", opened is StealthOpenResult.Success)
        opened as StealthOpenResult.Success
        assertEquals(secret, opened.plaintext)
        assertEquals(StealthMode.INVISIBLE_INK, opened.mode)
        assertEquals(t0 + 3_600L, opened.expiresAtEpochSec)
        assertEquals(3_000L, opened.remainingSeconds)
    }

    @Test
    fun sealingSamePlaintextTwiceProducesDistinctCiphertexts() {
        val a = StealthInkCodec.sealEnvelope("same secret", "marble-teacup-31", StealthTtl.NONE, t0, testIter)
        val b = StealthInkCodec.sealEnvelope("same secret", "marble-teacup-31", StealthTtl.NONE, t0, testIter)
        assertNotEquals(a, b)
    }

    @Test
    fun rejectsWrongPassphraseAndOrdinaryText() {
        val sealed = StealthInkCodec.sealEnvelope("top secret", "right-key-opens-it", StealthTtl.NONE, t0, testIter)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(sealed, "wrong-key-opens-it", t0, testIter)
        )
        assertNull(StealthInkCodec.detect("Just an ordinary chat message!"))
        assertEquals(
            StealthOpenResult.Malformed,
            StealthInkCodec.open("Just an ordinary chat message!", "right-key-opens-it", t0, testIter)
        )
    }

    @Test
    fun authenticatedTtlExpiresAndBlocksHeaderTampering() {
        val sealed = StealthInkCodec.sealEnvelope("burn after 5m", "ttl-pass-ticking-5", StealthTtl.FIVE_MIN, t0, testIter)

        // Valid right up to the expiry boundary.
        val valid = StealthInkCodec.open(sealed, "ttl-pass-ticking-5", t0 + 300L, testIter)
        assertTrue(valid is StealthOpenResult.Success)
        assertEquals(0L, (valid as StealthOpenResult.Success).remainingSeconds)

        // Rejected 1 second past expiry without revealing plaintext.
        val expired = StealthInkCodec.open(sealed, "ttl-pass-ticking-5", t0 + 301L, testIter)
        assertEquals(StealthOpenResult.Expired(t0 + 300L), expired)

        // Tampering with the expiry timestamp bytes in the header MUST fail GCM AAD verification.
        val raw = Base64.getUrlDecoder().decode(sealed.removePrefix(StealthInkCodec.ENVELOPE_PREFIX))
        raw[10] = (raw[10].toInt() xor 0xFF).toByte() // flip low byte of expiresAtEpochSec
        val tampered = StealthInkCodec.ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(tampered, "ttl-pass-ticking-5", t0, testIter)
        )
    }

    @Test
    fun bitFlipsInCiphertextTagModeOrVersionAreRejected() {
        val sealed = StealthInkCodec.sealEnvelope("integrity check", "integrity-key-1234", StealthTtl.NONE, t0, testIter)
        val original = Base64.getUrlDecoder().decode(sealed.removePrefix(StealthInkCodec.ENVELOPE_PREFIX))

        // Flip version byte -> UnsupportedVersion
        val badVersion = original.clone().also { it[0] = 0x09 }
        val badVerStr = StealthInkCodec.ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(badVersion)
        assertEquals(
            StealthOpenResult.UnsupportedVersion(9),
            StealthInkCodec.open(badVerStr, "integrity-key-1234", t0, testIter)
        )

        // Flip mode byte (ENVELOPE -> INVISIBLE_INK) -> rejected
        val badMode = original.clone().also { it[1] = StealthMode.INVISIBLE_INK.code }
        val badModeStr = StealthInkCodec.ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(badMode)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(badModeStr, "integrity-key-1234", t0, testIter)
        )

        // Flip last byte (GCM auth tag) -> rejected
        val badTag = original.clone().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte() }
        val badTagStr = StealthInkCodec.ENVELOPE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(badTag)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(badTagStr, "integrity-key-1234", t0, testIter)
        )
    }

    @Test
    fun compressesLongRepetitivePayloadsCleanly() {
        val longSecret = "Confidential schedule update for Q4 release. ".repeat(20)
        val sealed = StealthInkCodec.sealEnvelope(longSecret, "compress-key-9x!", StealthTtl.NONE, t0, testIter)
        // Compressed envelope should be dramatically smaller than raw plaintext.
        assertTrue("Expected compressed envelope < 250 chars, got ${sealed.length}", sealed.length < 250)

        val opened = StealthInkCodec.open(sealed, "compress-key-9x!", t0, testIter)
        assertTrue(opened is StealthOpenResult.Success)
        assertEquals(longSecret, (opened as StealthOpenResult.Success).plaintext)
    }

    @Test
    fun passphraseMinimumIsTwelveCharacters() {
        assertEquals(12, StealthInkCodec.MIN_PASSPHRASE_CHARS)
        assertEquals(PassphraseStrength.TOO_SHORT, StealthInkCodec.passphraseStrength(""))
        assertEquals(PassphraseStrength.TOO_SHORT, StealthInkCodec.passphraseStrength("abc"))
        assertEquals(PassphraseStrength.TOO_SHORT, StealthInkCodec.passphraseStrength("Tr0ub4dor&3")) // 11 chars
        assertTrue(StealthInkCodec.isAcceptablePassphrase("Tr0ub4dor&3x")) // 12 chars
        assertFalse(StealthInkCodec.isAcceptablePassphrase("a1B!c2D@e3F")) // 11 chars, varied but short
    }

    @Test
    fun weakPassphrasesAreRejectedEvenWhenLongEnough() {
        val weak = listOf(
            "aaaaaaaaaaaa",          // one repeated character
            "abababababab",          // two distinct characters
            "abcabcabcabc",          // repeated unit
            "hellohellohello",       // repeated word
            "123456789012",          // ascending digits
            "abcdefghijkl",          // ascending letters
            "zyxwvutsrqpo",          // descending letters
            "            ",          // spaces only
            "passwordpassword",      // very common
            "Password123456",        // very common with digits
            "p@ssw0rd2026",          // leet-speak common word plus year
            "qwertyuiop12",          // keyboard walk
            "1q2w3e4r5t6y",          // keyboard walk
            "iloveyou123456",
            "agentickeyboard",
            "a".repeat(129)          // over the maximum
        )
        for (p in weak) {
            assertFalse("Expected '$p' to be rejected", StealthInkCodec.isAcceptablePassphrase(p))
        }
    }

    @Test
    fun reasonablePassphrasesAreAcceptedAndRatedByLengthAndVariety() {
        assertEquals(PassphraseStrength.FAIR, StealthInkCodec.passphraseStrength("blueberryjamx"))
        assertEquals(PassphraseStrength.GOOD, StealthInkCodec.passphraseStrength("Blue-berry-42"))
        assertEquals(PassphraseStrength.STRONG, StealthInkCodec.passphraseStrength("Correct-Horse-99!"))
        assertEquals(PassphraseStrength.STRONG, StealthInkCodec.passphraseStrength("tiny purple rocket lamp"))
        assertTrue(StealthInkCodec.isAcceptablePassphrase("tiny purple rocket lamp"))
    }

    @Test
    fun sealRefusesShortOrWeakPassphrases() {
        for (bad in listOf("short-key", "aaaaaaaaaaaa", "passwordpassword", "123456789012")) {
            try {
                StealthInkCodec.sealEnvelope("secret", bad, StealthTtl.NONE, t0, testIter)
                org.junit.Assert.fail("sealEnvelope accepted weak passphrase '$bad'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
            try {
                StealthInkCodec.sealInvisibleInk("secret", bad, "hi", StealthTtl.NONE, t0, testIter)
                org.junit.Assert.fail("sealInvisibleInk accepted weak passphrase '$bad'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun openRejectsPassphrasesShorterThanTheMinimum() {
        val sealed = StealthInkCodec.sealEnvelope("secret", "marble-teacup-31", StealthTtl.NONE, t0, testIter)
        assertEquals(
            StealthOpenResult.WrongPassphraseOrTampered,
            StealthInkCodec.open(sealed, "marble", t0, testIter)
        )
    }

    @Test
    fun cryptoParametersAreSound() {
        assertTrue(StealthInkCodec.DEFAULT_PBKDF2_ITERATIONS >= 600_000) // OWASP PBKDF2-HMAC-SHA256 guidance
        val sealed = StealthInkCodec.sealEnvelope("layout check", "marble-teacup-31", StealthTtl.NONE, t0, testIter)
        val blob = Base64.getUrlDecoder().decode(sealed.removePrefix(StealthInkCodec.ENVELOPE_PREFIX))
        // header(11) + salt(16) + iv(12) + ciphertext(12) + GCM tag(16)
        assertEquals(11 + 16 + 12 + "layout check".length + 16, blob.size)
        // Fresh random salt and IV every time.
        val other = StealthInkCodec.sealEnvelope("layout check", "marble-teacup-31", StealthTtl.NONE, t0, testIter)
        val blob2 = Base64.getUrlDecoder().decode(other.removePrefix(StealthInkCodec.ENVELOPE_PREFIX))
        assertNotEquals(blob.copyOfRange(11, 27).toList(), blob2.copyOfRange(11, 27).toList()) // salt
        assertNotEquals(blob.copyOfRange(27, 39).toList(), blob2.copyOfRange(27, 39).toList()) // IV
    }

    @Test
    fun passphraseIsNormalisedSoComposedAndDecomposedAccentsMatch() {
        val composed = "caf\u00e9-au-lait-matin" // é as one code point
        val decomposed = "cafe\u0301-au-lait-matin" // e + combining acute
        val sealed = StealthInkCodec.sealEnvelope("bonjour", composed, StealthTtl.NONE, t0, testIter)
        val opened = StealthInkCodec.open(sealed, decomposed, t0, testIter)
        assertTrue("Expected Success, got $opened", opened is StealthOpenResult.Success)
    }

    @Test
    fun noticesStateTheLimitationsPlainly() {
        assertTrue(StealthInkNotices.INVISIBLE_INK.contains("detected and stripped by many apps"))
        assertTrue(StealthInkNotices.EXPIRY.contains("not deletion"))
        assertTrue(StealthInkNotices.EXPIRY.contains("copies"))
        assertTrue(StealthInkNotices.SHARED_KEY.contains("passphrase"))
    }

    @Test
    fun noZeroKnowledgeClaimsAnywhereInStealthInkSources() {
        val roots = listOf(File("app/src/main/java"), File("src/main/java"))
        val srcRoot = roots.first { it.exists() }
        val banned = listOf("zero" + "-knowledge", "zero" + " knowledge")
        val files = listOf(
            File(srcRoot, "io/github/daddymean/agentickeyboard/stealth/StealthInkCodec.kt"),
            File(srcRoot, "io/github/daddymean/agentickeyboard/ui/StealthInkPanel.kt"),
            File(srcRoot, "io/github/daddymean/agentickeyboard/ui/AgenticKeyboardLayout.kt")
        )
        for (file in files) {
            assertTrue("Expected source file: ${file.path}", file.exists())
            val source = file.readText().lowercase()
            for (claim in banned) assertFalse("${file.name} must not claim '$claim'", source.contains(claim))
        }
    }

    @Test
    fun drawerShowsLimitationsAndAvoidsDeletionLanguage() {
        val roots = listOf(File("app/src/main/java"), File("src/main/java"))
        val srcRoot = roots.first { it.exists() }
        val panel = File(srcRoot, "io/github/daddymean/agentickeyboard/ui/StealthInkPanel.kt").readText()
        assertTrue(panel.contains("StealthInkNotices.INVISIBLE_INK"))
        assertTrue(panel.contains("StealthInkNotices.EXPIRY"))
        assertTrue(panel.contains("StealthInkNotices.SHARED_KEY"))
        val lower = panel.lowercase()
        for (word in listOf("burns in", "burn ttl", "self-destruct", "self destruct", "unbreakable", "untraceable", "undetectable")) {
            assertFalse("Panel must not say '$word'", lower.contains(word))
        }
        assertTrue(panel.contains("(not deletion)"))
    }

    @Test
    fun strengthAndRemainingFormatters() {
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
