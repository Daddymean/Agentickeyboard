package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPassportTest {

    private val input = KeyboardPassportInput(
        personaPreference = "Casual",
        vocabulary = listOf(UserVocabulary(word = "fantastic", count = 4, lastUsed = 123L)),
        corrections = listOf(LearnedCorrection(typo = "teh", correction = "the", count = 3)),
        shortcuts = listOf(ShortcutTemplate(shortcut = "omw", template = "On my way")),
        customCommands = listOf(CustomCommand(token = "/warm", instruction = "Make this warmer")),
        appPersonas = listOf(AppPersona(packageName = "com.example.chat", persona = "Joyful", appLabel = "Chat")),
        writingLogs = listOf(
            WritingLog(
                originalText = "A private writing sample",
                sentiment = "Casual",
                toneScore = 0.8f,
                wordCount = 4,
                timestamp = 456L
            )
        )
    )

    @Test
    fun defaultPassportIncludesPortableCategoriesButExcludesRawWritingLogs() {
        val serialized = KeyboardPassport.create(
            input = input,
            options = KeyboardPassportOptions(redactSensitiveText = false),
            createdAt = 1_000L
        )

        val preview = KeyboardPassport.inspect(serialized)
        assertNotNull(preview)
        assertEquals(1_000L, preview!!.createdAt)
        assertFalse(preview.encrypted)
        assertFalse(preview.legacy)
        assertEquals(1, preview.counts.vocabulary)
        assertEquals(1, preview.counts.corrections)
        assertEquals(1, preview.counts.shortcuts)
        assertEquals(1, preview.counts.customCommands)
        assertEquals(1, preview.counts.appPersonas)
        assertEquals(0, preview.counts.writingLogs)
        assertFalse(PassportCategory.WRITING_LOGS in preview.categories)

        val opened = KeyboardPassport.open(serialized)
        assertTrue(opened is KeyboardPassportOpenResult.Success)
        val payload = (opened as KeyboardPassportOpenResult.Success).payload
        assertEquals("Casual", payload.personaPreference)
        assertEquals("fantastic", payload.vocabulary.single().word)
        assertEquals("omw", payload.shortcuts.single().shortcut)
        assertEquals("/warm", payload.customCommands.single().token)
        assertEquals("com.example.chat", payload.appPersonas.single().packageName)
        assertTrue(payload.writingLogs.isEmpty())
    }

    @Test
    fun encryptedPassportRequiresTheCorrectPassphrase() {
        val serialized = KeyboardPassport.create(
            input = input,
            options = KeyboardPassportOptions(
                passphrase = "correct horse battery staple",
                redactSensitiveText = false
            ),
            createdAt = 2_000L
        )

        val preview = KeyboardPassport.inspect(serialized)
        assertNotNull(preview)
        assertTrue(preview!!.encrypted)
        assertTrue(preview.requiresPassphrase)
        assertEquals(5, preview.counts.total)

        assertTrue(KeyboardPassport.open(serialized) is KeyboardPassportOpenResult.PassphraseRequired)
        assertTrue(
            KeyboardPassport.open(serialized, "wrong passphrase") is KeyboardPassportOpenResult.Invalid
        )

        val opened = KeyboardPassport.open(serialized, "correct horse battery staple")
        assertTrue(opened is KeyboardPassportOpenResult.Success)
        assertEquals("fantastic", (opened as KeyboardPassportOpenResult.Success).payload.vocabulary.single().word)
    }

    @Test
    fun encryptionUsesFreshSaltAndNonce() {
        val options = KeyboardPassportOptions(passphrase = "portable-secret", redactSensitiveText = false)
        val first = KeyboardPassport.create(input, options, createdAt = 3_000L)
        val second = KeyboardPassport.create(input, options, createdAt = 3_000L)

        assertNotEquals(first, second)
        assertTrue(KeyboardPassport.open(first, "portable-secret") is KeyboardPassportOpenResult.Success)
        assertTrue(KeyboardPassport.open(second, "portable-secret") is KeyboardPassportOpenResult.Success)
    }

    @Test
    fun categoryFilteringIsExplicitAndDeterministic() {
        val serialized = KeyboardPassport.create(
            input = input,
            options = KeyboardPassportOptions(
                includedCategories = setOf(PassportCategory.VOCABULARY),
                redactSensitiveText = false
            ),
            createdAt = 4_000L
        )

        val opened = KeyboardPassport.open(serialized) as KeyboardPassportOpenResult.Success
        assertNull(opened.payload.personaPreference)
        assertEquals(1, opened.payload.vocabulary.size)
        assertTrue(opened.payload.corrections.isEmpty())
        assertTrue(opened.payload.shortcuts.isEmpty())
        assertTrue(opened.payload.customCommands.isEmpty())
        assertTrue(opened.payload.appPersonas.isEmpty())
        assertTrue(opened.payload.writingLogs.isEmpty())
        assertEquals(setOf(PassportCategory.VOCABULARY), opened.preview.categories)
    }

    @Test
    fun writingLogsRequireExplicitInclusion() {
        val serialized = KeyboardPassport.create(
            input = input,
            options = KeyboardPassportOptions(
                includedCategories = DEFAULT_PASSPORT_CATEGORIES + PassportCategory.WRITING_LOGS,
                redactSensitiveText = false
            )
        )

        val opened = KeyboardPassport.open(serialized) as KeyboardPassportOpenResult.Success
        assertEquals("A private writing sample", opened.payload.writingLogs.single().text)
        assertEquals(1, opened.preview.counts.writingLogs)
    }

    @Test
    fun tamperingWithPlainPayloadFailsChecksumValidation() {
        val serialized = KeyboardPassport.create(
            input = input,
            options = KeyboardPassportOptions(redactSensitiveText = false)
        )
        val marker = "\"payloadBase64\": \""
        val index = serialized.indexOf(marker) + marker.length
        assertTrue(index >= marker.length)
        val original = serialized[index]
        val replacement = if (original == 'A') 'B' else 'A'
        val tampered = serialized.replaceRange(index, index + 1, replacement.toString())

        val result = KeyboardPassport.open(tampered)
        assertTrue(result is KeyboardPassportOpenResult.Invalid)
    }

    @Test
    fun legacyJsonExportRemainsReadable() {
        val legacy = PersonalModelSerializer.serialize(
            vocabulary = input.vocabulary,
            corrections = input.corrections,
            logs = input.writingLogs,
            personaPreference = input.personaPreference,
            stripSensitive = false,
            exportFormat = "JSON Structure"
        ).serializedContent

        val preview = KeyboardPassport.inspect(legacy)
        assertNotNull(preview)
        assertTrue(preview!!.legacy)
        assertEquals(0, preview.version)

        val opened = KeyboardPassport.open(legacy)
        assertTrue(opened is KeyboardPassportOpenResult.Success)
        opened as KeyboardPassportOpenResult.Success
        assertTrue(opened.preview.legacy)
        assertEquals("fantastic", opened.payload.vocabulary.single().word)
        assertEquals("A private writing sample", opened.payload.writingLogs.single().text)
    }

    @Test
    fun unsupportedFutureEnvelopeIsInspectableButNotOpened() {
        val serialized = KeyboardPassport.create(input, createdAt = 5_000L)
        val future = serialized.replaceFirst("\"version\": 1", "\"version\": 99")

        val preview = KeyboardPassport.inspect(future)
        assertNotNull(preview)
        assertFalse(preview!!.compatible)
        assertEquals(99, preview.version)
        assertTrue(KeyboardPassport.open(future) is KeyboardPassportOpenResult.Invalid)
    }

    @Test
    fun emptyAndArbitraryContentAreRejected() {
        assertNull(KeyboardPassport.inspect(""))
        assertNull(KeyboardPassport.inspect("{}"))
        assertTrue(KeyboardPassport.open("not a passport") is KeyboardPassportOpenResult.Invalid)
    }
}
