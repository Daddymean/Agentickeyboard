package io.github.daddymean.agentickeyboard.util

import com.squareup.moshi.Moshi
import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
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

    // --- Legacy sanity checks (issue #101) ---

    private fun legacyJson(
        exportVersion: String? = "1.0.0",
        totalRecords: Int? = 3,
        vocabulary: String = """{ "word": "fantastic", "count": 4, "lastUsed": 1000 }""",
        corrections: String = """{ "typo": "teh", "correction": "the", "count": 2 }""",
        logs: String = """{ "text": "hello", "sentiment": "Joyful", "toneScore": 0.9, "timestamp": 5 }"""
    ): String {
        val metadata = buildList {
            add("\"userPersonaPreference\": \"Casual\"")
            exportVersion?.let { add("\"exportVersion\": \"$it\"") }
            totalRecords?.let { add("\"totalRecordsExported\": $it") }
        }.joinToString(", ")
        return """{ "exportMetadata": { $metadata },
            "typingPatterns": { "vocabulary": [ $vocabulary ] },
            "correctionHistory": [ $corrections ],
            "writingLogs": [ $logs ] }"""
    }

    private fun assertLegacyRejected(content: String, reasonFragment: String) {
        val result = KeyboardPassport.open(content)
        assertTrue("expected Invalid, got $result", result is KeyboardPassportOpenResult.Invalid)
        val reason = (result as KeyboardPassportOpenResult.Invalid).reason
        assertTrue(reason, reason.contains(reasonFragment))
    }

    @Test
    fun wellFormedLegacyExportOpensButIsMarkedUnverified() {
        val result = KeyboardPassport.open(legacyJson())
        assertTrue(result is KeyboardPassportOpenResult.Success)
        result as KeyboardPassportOpenResult.Success
        assertTrue(result.preview.legacy)
        assertFalse(result.preview.verified)
        assertEquals(PassportRecordCounts(vocabulary = 1, corrections = 1, writingLogs = 1), result.preview.counts)
    }

    @Test
    fun envelopePassportsAreVerified() {
        val opened = KeyboardPassport.open(KeyboardPassport.create(input, KeyboardPassportOptions()))
        assertTrue((opened as KeyboardPassportOpenResult.Success).preview.verified)
    }

    @Test
    fun legacyExportWithoutOptionalMetadataStillOpens() {
        // Hand-made or very old files may omit exportVersion/totalRecordsExported.
        assertTrue(KeyboardPassport.open(legacyJson(exportVersion = null, totalRecords = null)) is KeyboardPassportOpenResult.Success)
        assertTrue(KeyboardPassport.open(legacyJson(exportVersion = "1.4")) is KeyboardPassportOpenResult.Success)
    }

    @Test
    fun legacyExportWithUnknownVersionIsRejected() {
        assertLegacyRejected(legacyJson(exportVersion = "2.0.0"), "version 2.0.0")
        assertLegacyRejected(legacyJson(exportVersion = "garbage"), "not supported")
    }

    @Test
    fun legacyExportWhoseRecordTotalDisagreesWithItsContentsIsRejected() {
        // e.g. a truncated or hand-damaged file that still parses.
        assertLegacyRejected(legacyJson(totalRecords = 40), "says it has 40 record(s) but contains 3")
        assertLegacyRejected(legacyJson(totalRecords = 3, logs = ""), "contains 2")
    }

    @Test
    fun legacyExportWithMalformedRecordsIsRejected() {
        assertLegacyRejected(legacyJson(vocabulary = """{ "word": "  ", "count": 1 }"""), "vocabulary")
        assertLegacyRejected(legacyJson(vocabulary = """{ "word": "ok", "count": -3 }"""), "vocabulary")
        assertLegacyRejected(legacyJson(vocabulary = """{ "word": "ok", "lastUsed": -1 }"""), "vocabulary")
        assertLegacyRejected(legacyJson(corrections = """{ "typo": "", "correction": "the" }"""), "corrections")
        assertLegacyRejected(legacyJson(corrections = """{ "typo": "teh", "correction": " ", "count": 1 }"""), "corrections")
        assertLegacyRejected(legacyJson(logs = """{ "text": "hi", "timestamp": -5 }"""), "writing logs")
    }

    @Test
    fun legacyChecksAcceptWhatTheLegacySerializerWrites() {
        // Guard against the sanity checks rejecting genuine exports.
        val legacy = PersonalModelSerializer.serialize(
            vocabulary = input.vocabulary,
            corrections = input.corrections,
            logs = input.writingLogs,
            personaPreference = input.personaPreference,
            stripSensitive = true,
            exportFormat = "JSON Structure"
        ).serializedContent
        assertTrue(KeyboardPassport.open(legacy) is KeyboardPassportOpenResult.Success)
    }

    @Test
    fun unsupportedFutureEnvelopeIsInspectableButNotOpened() {
        val serialized = KeyboardPassport.create(input, createdAt = 5_000L)
        val future = serialized.replaceFirst("\"version\": 2", "\"version\": 99")
        assertNotEquals(serialized, future)

        val preview = KeyboardPassport.inspect(future)
        assertNotNull(preview)
        assertFalse(preview!!.compatible)
        assertEquals(99, preview.version)
        assertTrue(KeyboardPassport.open(future) is KeyboardPassportOpenResult.Invalid)
    }

    @Test
    fun editedEnvelopeCategoriesCannotWidenImportScope() {
        val serialized = KeyboardPassport.create(
            input = input,
            options = KeyboardPassportOptions(
                includedCategories = setOf(PassportCategory.VOCABULARY),
                passphrase = "portable-secret",
                redactSensitiveText = false
            ),
            createdAt = 6_000L
        )

        // Ciphertext, counts and checksum all stay valid; only the unauthenticated
        // envelope metadata is edited to claim categories the payload does not carry.
        val tampered = serialized.replaceFirst(
            "\"categories\": [\n    \"vocabulary\"\n  ]",
            "\"categories\": [\n    \"appPersonas\",\n    \"corrections\",\n    \"customCommands\",\n" +
                "    \"shortcuts\",\n    \"vocabulary\",\n    \"writingLogs\"\n  ]"
        )
        assertNotEquals(serialized, tampered)

        val opened = KeyboardPassport.open(tampered, "portable-secret")
        assertTrue(opened is KeyboardPassportOpenResult.Success)
        opened as KeyboardPassportOpenResult.Success
        assertEquals(setOf(PassportCategory.VOCABULARY), opened.preview.categories)

        val plan = KeyboardPassportImportPlanner.plan(
            current = KeyboardPassportSnapshot(
                personaPreference = "Professional",
                shortcuts = listOf(ShortcutTemplate(shortcut = "brb", template = "Be right back")),
                customCommands = listOf(CustomCommand(token = "/tight", instruction = "Shorten this")),
                appPersonas = listOf(AppPersona(packageName = "com.example.mail", persona = "Formal")),
                corrections = listOf(LearnedCorrection(typo = "adn", correction = "and", count = 2))
            ),
            incoming = opened.payload,
            categories = opened.preview.categories,
            mode = KeyboardPassportImportMode.REPLACE
        )

        assertEquals(setOf(PassportCategory.VOCABULARY), plan.affectedCategories)
        assertEquals("brb", plan.snapshot.shortcuts.single().shortcut)
        assertEquals("/tight", plan.snapshot.customCommands.single().token)
        assertEquals("com.example.mail", plan.snapshot.appPersonas.single().packageName)
        assertEquals("adn", plan.snapshot.corrections.single().typo)
        assertEquals("Professional", plan.snapshot.personaPreference)
    }

    @Test
    fun encryptedPassportChecksumCoversCiphertextNotPlaintext() {
        val serialized = KeyboardPassport.create(
            input = input,
            options = KeyboardPassportOptions(passphrase = "portable-secret", redactSensitiveText = false),
            createdAt = 7_000L
        )

        val ciphertext = Base64.getDecoder().decode(jsonField(serialized, "payloadBase64"))
        assertEquals(sha256Hex(ciphertext), jsonField(serialized, "checksumSha256"))

        // The digest of the decrypted payload must not appear anywhere in the file.
        val opened = KeyboardPassport.open(serialized, "portable-secret")
        assertTrue(opened is KeyboardPassportOpenResult.Success)
        assertFalse(serialized.contains(sha256Hex(canonicalPayloadBytes(opened as KeyboardPassportOpenResult.Success))))
    }

    @Test
    fun version1EncryptedPassportsRemainReadable() {
        val plaintext = """{"schemaVersion":1,"vocabulary":[{"word":"fantastic","count":4,"lastUsed":123}]}"""
            .toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { (it + 7).toByte() }
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("legacy-secret".toCharArray(), salt, 210_000, 256))
                .encoded,
            "AES"
        )
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD("lumina-keyboard-passport:1".toByteArray(StandardCharsets.UTF_8))
            doFinal(plaintext)
        }
        val encoder = Base64.getEncoder()
        val envelope = { checksum: String ->
            """
            {
              "format": "lumina-keyboard-passport",
              "version": 1,
              "createdAt": 1000,
              "categories": ["vocabulary"],
              "counts": { "vocabulary": 1 },
              "checksumSha256": "$checksum",
              "payloadEncoding": "base64-aes-gcm",
              "encryption": {
                "algorithm": "AES-256-GCM",
                "keyDerivation": "PBKDF2-HMAC-SHA256",
                "iterations": 210000,
                "saltBase64": "${encoder.encodeToString(salt)}",
                "ivBase64": "${encoder.encodeToString(iv)}"
              },
              "payloadBase64": "${encoder.encodeToString(ciphertext)}"
            }
            """.trimIndent()
        }

        val authentic = envelope(sha256Hex(plaintext))
        assertTrue(KeyboardPassport.open(authentic) is KeyboardPassportOpenResult.PassphraseRequired)

        val opened = KeyboardPassport.open(authentic, "legacy-secret")
        assertTrue(opened is KeyboardPassportOpenResult.Success)
        opened as KeyboardPassportOpenResult.Success
        assertEquals(1, opened.preview.version)
        assertTrue(opened.preview.compatible)
        assertEquals("fantastic", opened.payload.vocabulary.single().word)

        // A version 1 plaintext digest is no longer consulted; GCM authenticates it.
        assertTrue(
            KeyboardPassport.open(envelope("00".repeat(32)), "legacy-secret")
                is KeyboardPassportOpenResult.Success
        )
        assertTrue(
            KeyboardPassport.open(authentic, "wrong-secret") is KeyboardPassportOpenResult.Invalid
        )
    }

    private fun canonicalPayloadBytes(opened: KeyboardPassportOpenResult.Success): ByteArray =
        Moshi.Builder().build().adapter(PassportPayload::class.java)
            .toJson(opened.payload)
            .toByteArray(StandardCharsets.UTF_8)

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun jsonField(json: String, name: String): String {
        val marker = "\"$name\": \""
        val start = json.indexOf(marker)
        assertTrue("missing field $name", start >= 0)
        val from = start + marker.length
        return json.substring(from, json.indexOf('"', from))
    }

    @Test
    fun emptyAndArbitraryContentAreRejected() {
        assertNull(KeyboardPassport.inspect(""))
        assertNull(KeyboardPassport.inspect("{}"))
        assertTrue(KeyboardPassport.open("not a passport") is KeyboardPassportOpenResult.Invalid)
    }
}
