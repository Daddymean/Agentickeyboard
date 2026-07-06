package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog
import io.github.daddymean.agentickeyboard.util.PersonalModelSerializer
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testEmailAnonymization() {
        val input = "Please contact me at bob.smith@example.co.uk ASAP."
        val (sanitized, stats) = PersonalModelSerializer.sanitizeText(input)
        assertEquals("Please contact me at [REDACTED_EMAIL] ASAP.", sanitized)
        assertEquals(1, stats.emailsRedacted)
        assertEquals(1, stats.totalRedactions)
    }

    @Test
    fun testPhoneNumberAnonymization() {
        val inputs = listOf(
            "Call me at +1-555-0199",
            "Call me at 555-555-0199",
            "Call me at (555) 555-0199"
        )
        for (input in inputs) {
            val (sanitized, stats) = PersonalModelSerializer.sanitizeText(input)
            assertTrue(sanitized.contains("[REDACTED_PHONE]"))
            assertEquals(1, stats.phonesRedacted)
        }
    }

    @Test
    fun testFinancialTokenAnonymization() {
        val ssnInput = "My code is 123-45-6789 here."
        val (ssnSanitized, ssnStats) = PersonalModelSerializer.sanitizeText(ssnInput)
        assertEquals("My code is [REDACTED_FINANCIAL] here.", ssnSanitized)
        assertEquals(1, ssnStats.cardsRedacted)

        val ccInput = "My card is 1234-5678-9012-3456."
        val (ccSanitized, ccStats) = PersonalModelSerializer.sanitizeText(ccInput)
        assertEquals("My card is [REDACTED_FINANCIAL].", ccSanitized)
        assertEquals(1, ccStats.cardsRedacted)
    }

    @Test
    fun testUrlAnonymization() {
        val input = "Go to http://subdomain.test.org/some/path?param=1 for info."
        val (sanitized, stats) = PersonalModelSerializer.sanitizeText(input)
        assertEquals("Go to [REDACTED_URL] for info.", sanitized)
        assertEquals(1, stats.urlsRedacted)
    }

    @Test
    fun testNumericIdAnonymization() {
        val input = "Your account number is 83749281."
        val (sanitized, stats) = PersonalModelSerializer.sanitizeText(input)
        assertEquals("Your account number is [REDACTED_NUMERIC_ID].", sanitized)
        assertEquals(1, stats.numericIdsRedacted)
    }

    @Test
    fun testSerializationModuleWithStripping() {
        val vocab = listOf(UserVocabulary(word = "test@example.com", count = 3))
        val corrections = listOf(LearnedCorrection(typo = "teh", correction = "the", count = 5))
        val logs = listOf(WritingLog(originalText = "Hey! Call me at 123-456-7890", sentiment = "Casual", toneScore = 0.8f, wordCount = 6))

        val result = PersonalModelSerializer.serialize(
            vocabulary = vocab,
            corrections = corrections,
            logs = logs,
            personaPreference = "Casual",
            stripSensitive = true,
            exportFormat = "JSON Structure"
        )

        // Verify anonymized logs contain redactions
        assertTrue(result.serializedContent.contains("[REDACTED_EMAIL]"))
        assertTrue(result.serializedContent.contains("[REDACTED_PHONE]"))
        assertEquals(2, result.stats.totalRedactions)
        assertEquals(1, result.stats.emailsRedacted)
        assertEquals(1, result.stats.phonesRedacted)
    }

    @Test
    fun testSerializationModuleWithoutStripping() {
        val vocab = listOf(UserVocabulary(word = "secret", count = 3))
        val corrections = listOf(LearnedCorrection(typo = "teh", correction = "the", count = 5))
        val logs = listOf(WritingLog(originalText = "Hey! Call me at 123-456-7890", sentiment = "Casual", toneScore = 0.8f, wordCount = 6))

        val result = PersonalModelSerializer.serialize(
            vocabulary = vocab,
            corrections = corrections,
            logs = logs,
            personaPreference = "Casual",
            stripSensitive = false,
            exportFormat = "JSON Structure"
        )

        // No redactions when stripSensitive = false
        assertFalse(result.serializedContent.contains("[REDACTED_PHONE]"))
        assertTrue(result.serializedContent.contains("123-456-7890"))
        assertEquals(0, result.stats.totalRedactions)
    }

    @Test
    fun testJsonEscapingOfControlAndSpecialCharacters() {
        val logs = listOf(
            WritingLog(
                originalText = "Line one\nLine \"two\" with a \\ backslash\tand tab",
                sentiment = "Casual",
                toneScore = 0.5f,
                wordCount = 9
            )
        )

        val result = PersonalModelSerializer.serialize(
            vocabulary = emptyList(),
            corrections = emptyList(),
            logs = logs,
            personaPreference = "Casual",
            stripSensitive = false,
            exportFormat = "JSON Structure"
        )

        // The payload must remain valid, parseable JSON despite the special characters.
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val parsed = moshi.adapter(Any::class.java).fromJson(result.serializedContent)
        assertNotNull(parsed)

        @Suppress("UNCHECKED_CAST")
        val root = parsed as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val writingLogs = root["writingLogs"] as List<Map<String, Any?>>
        assertEquals("Line one\nLine \"two\" with a \\ backslash\tand tab", writingLogs[0]["text"])
    }

    @Test
    fun testExportImportRoundtrip() {
        val vocab = listOf(UserVocabulary(word = "fantastic", count = 3, lastUsed = 123L))
        val corrections = listOf(LearnedCorrection(typo = "teh", correction = "the", count = 5))
        val logs = listOf(WritingLog(originalText = "Hello there, world!", sentiment = "Joyful", toneScore = 0.9f, wordCount = 3, timestamp = 456L))

        val result = PersonalModelSerializer.serialize(
            vocabulary = vocab,
            corrections = corrections,
            logs = logs,
            personaPreference = "Casual",
            stripSensitive = false,
            exportFormat = "JSON Structure"
        )

        val imported = PersonalModelSerializer.parseImport(result.serializedContent)
        assertNotNull(imported)
        assertEquals("Casual", imported!!.exportMetadata?.userPersonaPreference)
        assertEquals(1, imported.typingPatterns?.vocabulary?.size)
        assertEquals("fantastic", imported.typingPatterns?.vocabulary?.first()?.word)
        assertEquals(3, imported.typingPatterns?.vocabulary?.first()?.count)
        assertEquals("teh", imported.correctionHistory.first().typo)
        assertEquals("the", imported.correctionHistory.first().correction)
        assertEquals("Hello there, world!", imported.writingLogs.first().text)
        assertEquals(456L, imported.writingLogs.first().timestamp)
    }

    @Test
    fun testParseImportRejectsInvalidContent() {
        assertNull(PersonalModelSerializer.parseImport(""))
        assertNull(PersonalModelSerializer.parseImport("{\"broken\": "))
        assertNull(PersonalModelSerializer.parseImport("!!! definitely not an export !!!"))
    }

    @Test
    fun testSwipeDictionaryLoadAndReset() {
        try {
            io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine.loadDictionary(listOf("hello", "help", "hero"))
            val path = listOf(
                io.github.daddymean.agentickeyboard.util.SwipePoint(6.0f, 1.5f),
                io.github.daddymean.agentickeyboard.util.SwipePoint(2.5f, 0.5f),
                io.github.daddymean.agentickeyboard.util.SwipePoint(9.0f, 1.5f),
                io.github.daddymean.agentickeyboard.util.SwipePoint(8.5f, 0.5f)
            )
            val matches = io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine.getSwipeWordMatches(path)
            assertEquals("hello", matches.firstOrNull())
        } finally {
            // Restore the built-in fallback so other tests stay hermetic
            io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine.loadDictionary(emptyList())
        }
    }

    @Test
    fun testSwipeToTypeHello() {
        val hCoord = io.github.daddymean.agentickeyboard.util.SwipePoint(6.0f, 1.5f)
        val eCoord = io.github.daddymean.agentickeyboard.util.SwipePoint(2.5f, 0.5f)
        val lCoord = io.github.daddymean.agentickeyboard.util.SwipePoint(9.0f, 1.5f)
        val oCoord = io.github.daddymean.agentickeyboard.util.SwipePoint(8.5f, 0.5f)

        val path = listOf(hCoord, eCoord, lCoord, oCoord)
        val matches = io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine.getSwipeWordMatches(path)

        assertTrue("Should identify 'hello' as a top candidate", matches.contains("hello"))
        assertEquals("hello", matches.firstOrNull())
    }

    @Test
    fun testSwipeToTypeShortWords() {
        // swipe 'to'
        // T is at (4.5, 0.5), O is at (8.5, 0.5)
        val path = listOf(
            io.github.daddymean.agentickeyboard.util.SwipePoint(4.5f, 0.5f),
            io.github.daddymean.agentickeyboard.util.SwipePoint(8.5f, 0.5f)
        )
        val matches = io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine.getSwipeWordMatches(path)
        assertTrue(matches.contains("to"))
    }
}

