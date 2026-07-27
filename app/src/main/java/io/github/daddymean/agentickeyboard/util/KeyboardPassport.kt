package io.github.daddymean.agentickeyboard.util

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable data categories that can be included independently in a passport. */
enum class PassportCategory(val wireName: String) {
    PERSONA_PREFERENCE("personaPreference"),
    VOCABULARY("vocabulary"),
    CORRECTIONS("corrections"),
    SHORTCUTS("shortcuts"),
    CUSTOM_COMMANDS("customCommands"),
    APP_PERSONAS("appPersonas"),
    WRITING_LOGS("writingLogs")
}

@JsonClass(generateAdapter = true)
data class PassportShortcut(val shortcut: String, val template: String)

@JsonClass(generateAdapter = true)
data class PassportCustomCommand(val token: String, val instruction: String)

@JsonClass(generateAdapter = true)
data class PassportAppPersona(
    val packageName: String,
    val persona: String,
    val appLabel: String = ""
)

@JsonClass(generateAdapter = true)
data class PassportPayload(
    val schemaVersion: Int = 1,
    val personaPreference: String? = null,
    val vocabulary: List<ImportedVocabulary> = emptyList(),
    val corrections: List<ImportedCorrection> = emptyList(),
    val shortcuts: List<PassportShortcut> = emptyList(),
    val customCommands: List<PassportCustomCommand> = emptyList(),
    val appPersonas: List<PassportAppPersona> = emptyList(),
    val writingLogs: List<ImportedLog> = emptyList()
)

@JsonClass(generateAdapter = true)
data class PassportRecordCounts(
    val vocabulary: Int = 0,
    val corrections: Int = 0,
    val shortcuts: Int = 0,
    val customCommands: Int = 0,
    val appPersonas: Int = 0,
    val writingLogs: Int = 0
) {
    val total: Int
        get() = vocabulary + corrections + shortcuts + customCommands + appPersonas + writingLogs
}

@JsonClass(generateAdapter = true)
data class PassportEncryption(
    val algorithm: String,
    val keyDerivation: String,
    val iterations: Int,
    val saltBase64: String,
    val ivBase64: String
)

@JsonClass(generateAdapter = true)
data class KeyboardPassportEnvelope(
    val format: String,
    val version: Int,
    val createdAt: Long,
    val categories: List<String>,
    val counts: PassportRecordCounts,
    val checksumSha256: String,
    val payloadEncoding: String,
    val encryption: PassportEncryption? = null,
    val payloadBase64: String
)

data class KeyboardPassportInput(
    val personaPreference: String,
    val vocabulary: List<UserVocabulary> = emptyList(),
    val corrections: List<LearnedCorrection> = emptyList(),
    val shortcuts: List<ShortcutTemplate> = emptyList(),
    val customCommands: List<CustomCommand> = emptyList(),
    val appPersonas: List<AppPersona> = emptyList(),
    val writingLogs: List<WritingLog> = emptyList()
)

data class KeyboardPassportOptions(
    val includedCategories: Set<PassportCategory> = DEFAULT_PASSPORT_CATEGORIES,
    val passphrase: String? = null,
    val redactSensitiveText: Boolean = true
)

val DEFAULT_PASSPORT_CATEGORIES: Set<PassportCategory> = setOf(
    PassportCategory.PERSONA_PREFERENCE,
    PassportCategory.VOCABULARY,
    PassportCategory.CORRECTIONS,
    PassportCategory.SHORTCUTS,
    PassportCategory.CUSTOM_COMMANDS,
    PassportCategory.APP_PERSONAS
)

data class KeyboardPassportPreview(
    val version: Int,
    val createdAt: Long?,
    val categories: Set<PassportCategory>,
    val counts: PassportRecordCounts,
    val encrypted: Boolean,
    val requiresPassphrase: Boolean,
    val compatible: Boolean,
    val legacy: Boolean
)

sealed interface KeyboardPassportOpenResult {
    data class Success(
        val payload: PassportPayload,
        val preview: KeyboardPassportPreview
    ) : KeyboardPassportOpenResult

    data class PassphraseRequired(
        val preview: KeyboardPassportPreview
    ) : KeyboardPassportOpenResult

    data class Invalid(val reason: String) : KeyboardPassportOpenResult
}

/**
 * Pure-JVM versioned container for moving Lumina's local personal model.
 *
 * The envelope exposes only category/count metadata for import preview. The
 * payload is Base64-encoded JSON when unencrypted, or AES-256-GCM ciphertext
 * derived from a passphrase with PBKDF2-HMAC-SHA256. No network or storage API
 * is used here; file selection and repository application belong to later UI
 * slices.
 */
object KeyboardPassport {

    const val FORMAT = "lumina-keyboard-passport"
    const val CURRENT_VERSION = 1
    const val PAYLOAD_SCHEMA_VERSION = 1

    private const val ALGORITHM = "AES-256-GCM"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_DERIVATION = "PBKDF2-HMAC-SHA256"
    private const val KEY_FACTORY = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 210_000
    private const val MIN_ACCEPTED_ITERATIONS = 50_000
    private const val MAX_ACCEPTED_ITERATIONS = 1_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PLAIN_ENCODING = "base64-json"
    private const val ENCRYPTED_ENCODING = "base64-aes-gcm"

    private val moshi = Moshi.Builder().build()
    private val envelopeAdapter = moshi.adapter(KeyboardPassportEnvelope::class.java).indent("  ")
    private val payloadAdapter = moshi.adapter(PassportPayload::class.java)
    private val aad = "$FORMAT:$CURRENT_VERSION".toByteArray(StandardCharsets.UTF_8)

    fun create(
        input: KeyboardPassportInput,
        options: KeyboardPassportOptions = KeyboardPassportOptions(),
        createdAt: Long = System.currentTimeMillis(),
        secureRandom: SecureRandom = SecureRandom()
    ): String {
        val categories = options.includedCategories
        val payload = PassportPayload(
            personaPreference = input.personaPreference.takeIf {
                PassportCategory.PERSONA_PREFERENCE in categories
            },
            vocabulary = input.vocabulary.takeIf {
                PassportCategory.VOCABULARY in categories
            }.orEmpty().map {
                ImportedVocabulary(
                    word = sanitize(it.word, options.redactSensitiveText),
                    count = it.count.coerceAtLeast(1),
                    lastUsed = it.lastUsed.coerceAtLeast(0L)
                )
            },
            corrections = input.corrections.takeIf {
                PassportCategory.CORRECTIONS in categories
            }.orEmpty().map {
                ImportedCorrection(
                    typo = sanitize(it.typo, options.redactSensitiveText),
                    correction = sanitize(it.correction, options.redactSensitiveText),
                    count = it.count.coerceAtLeast(1)
                )
            },
            shortcuts = input.shortcuts.takeIf {
                PassportCategory.SHORTCUTS in categories
            }.orEmpty().map {
                PassportShortcut(
                    shortcut = sanitize(it.shortcut, options.redactSensitiveText),
                    template = sanitize(it.template, options.redactSensitiveText)
                )
            },
            customCommands = input.customCommands.takeIf {
                PassportCategory.CUSTOM_COMMANDS in categories
            }.orEmpty().map {
                PassportCustomCommand(
                    token = sanitize(it.token, options.redactSensitiveText),
                    instruction = sanitize(it.instruction, options.redactSensitiveText)
                )
            },
            appPersonas = input.appPersonas.takeIf {
                PassportCategory.APP_PERSONAS in categories
            }.orEmpty().map {
                PassportAppPersona(
                    packageName = it.packageName,
                    persona = it.persona,
                    appLabel = sanitize(it.appLabel, options.redactSensitiveText)
                )
            },
            writingLogs = input.writingLogs.takeIf {
                PassportCategory.WRITING_LOGS in categories
            }.orEmpty().map {
                ImportedLog(
                    text = sanitize(it.originalText, options.redactSensitiveText),
                    sentiment = sanitize(it.sentiment, options.redactSensitiveText),
                    toneScore = it.toneScore,
                    timestamp = it.timestamp.coerceAtLeast(0L)
                )
            }
        )

        val plaintext = payloadAdapter.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        val passphrase = options.passphrase?.takeIf { it.isNotBlank() }
        val encrypted = passphrase != null

        val encryption: PassportEncryption?
        val encodedPayload: String
        if (encrypted) {
            val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
            val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
            val ciphertext = encrypt(plaintext, passphrase!!, salt, iv, PBKDF2_ITERATIONS)
            encryption = PassportEncryption(
                algorithm = ALGORITHM,
                keyDerivation = KEY_DERIVATION,
                iterations = PBKDF2_ITERATIONS,
                saltBase64 = encodeBase64(salt),
                ivBase64 = encodeBase64(iv)
            )
            encodedPayload = encodeBase64(ciphertext)
        } else {
            encryption = null
            encodedPayload = encodeBase64(plaintext)
        }

        val envelope = KeyboardPassportEnvelope(
            format = FORMAT,
            version = CURRENT_VERSION,
            createdAt = createdAt,
            categories = categories.map { it.wireName }.sorted(),
            counts = countsOf(payload),
            checksumSha256 = sha256(plaintext),
            payloadEncoding = if (encrypted) ENCRYPTED_ENCODING else PLAIN_ENCODING,
            encryption = encryption,
            payloadBase64 = encodedPayload
        )
        return envelopeAdapter.toJson(envelope)
    }

    /** Reads category/count metadata without decrypting the payload. */
    fun inspect(content: String): KeyboardPassportPreview? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        parseEnvelope(trimmed)?.let { envelope ->
            return previewOf(envelope)
        }

        val legacy = parseLegacy(trimmed) ?: return null
        return previewOfLegacy(legacy)
    }

    /** Opens a passport or a legacy JSON/Base64 export without mutating storage. */
    fun open(content: String, passphrase: String? = null): KeyboardPassportOpenResult {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return KeyboardPassportOpenResult.Invalid("The file is empty.")

        val envelope = parseEnvelope(trimmed)
        if (envelope == null) {
            val legacy = parseLegacy(trimmed)
                ?: return KeyboardPassportOpenResult.Invalid("This is not a recognized Lumina export.")
            return KeyboardPassportOpenResult.Success(legacy, previewOfLegacy(legacy))
        }

        val preview = previewOf(envelope)
        if (envelope.format != FORMAT) {
            return KeyboardPassportOpenResult.Invalid("Unknown passport format.")
        }
        if (envelope.version != CURRENT_VERSION) {
            return KeyboardPassportOpenResult.Invalid(
                "Passport version ${envelope.version} is not supported by this app version."
            )
        }

        val encrypted = envelope.encryption != null
        if (encrypted && passphrase.isNullOrBlank()) {
            return KeyboardPassportOpenResult.PassphraseRequired(preview)
        }

        val encodedBytes = decodeBase64(envelope.payloadBase64)
            ?: return KeyboardPassportOpenResult.Invalid("The passport payload is damaged.")

        val plaintext = if (encrypted) {
            val metadata = envelope.encryption!!
            if (metadata.algorithm != ALGORITHM || metadata.keyDerivation != KEY_DERIVATION) {
                return KeyboardPassportOpenResult.Invalid("The passport uses unsupported encryption.")
            }
            if (metadata.iterations !in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) {
                return KeyboardPassportOpenResult.Invalid("The passport has an unsafe key-derivation cost.")
            }
            val salt = decodeBase64(metadata.saltBase64)
                ?: return KeyboardPassportOpenResult.Invalid("The encryption salt is damaged.")
            val iv = decodeBase64(metadata.ivBase64)
                ?: return KeyboardPassportOpenResult.Invalid("The encryption nonce is damaged.")
            if (salt.size < 12 || iv.size != IV_BYTES) {
                return KeyboardPassportOpenResult.Invalid("The encryption metadata is malformed.")
            }
            try {
                decrypt(encodedBytes, passphrase!!, salt, iv, metadata.iterations)
            } catch (_: Exception) {
                return KeyboardPassportOpenResult.Invalid("Wrong passphrase or damaged passport.")
            }
        } else {
            if (envelope.payloadEncoding != PLAIN_ENCODING) {
                return KeyboardPassportOpenResult.Invalid("The passport payload encoding is unsupported.")
            }
            encodedBytes
        }

        if (!sha256(plaintext).equals(envelope.checksumSha256, ignoreCase = true)) {
            return KeyboardPassportOpenResult.Invalid("The passport checksum does not match.")
        }

        val payload = try {
            payloadAdapter.fromJson(String(plaintext, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            null
        } ?: return KeyboardPassportOpenResult.Invalid("The passport payload cannot be read.")

        if (payload.schemaVersion != PAYLOAD_SCHEMA_VERSION) {
            return KeyboardPassportOpenResult.Invalid(
                "Payload schema ${payload.schemaVersion} is not supported."
            )
        }
        if (countsOf(payload) != envelope.counts) {
            return KeyboardPassportOpenResult.Invalid("The passport record summary does not match its payload.")
        }

        return KeyboardPassportOpenResult.Success(payload, preview)
    }

    private fun parseEnvelope(content: String): KeyboardPassportEnvelope? {
        if (!content.startsWith("{")) return null
        return try {
            envelopeAdapter.fromJson(content)?.takeIf {
                it.format == FORMAT || content.contains("\"payloadBase64\"")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLegacy(content: String): PassportPayload? {
        val legacy = PersonalModelSerializer.parseImport(content) ?: return null
        val hasContent = legacy.exportMetadata != null ||
            legacy.typingPatterns?.vocabulary?.isNotEmpty() == true ||
            legacy.correctionHistory.isNotEmpty() ||
            legacy.writingLogs.isNotEmpty()
        if (!hasContent) return null
        return PassportPayload(
            personaPreference = legacy.exportMetadata?.userPersonaPreference,
            vocabulary = legacy.typingPatterns?.vocabulary.orEmpty(),
            corrections = legacy.correctionHistory,
            writingLogs = legacy.writingLogs
        )
    }

    private fun previewOf(envelope: KeyboardPassportEnvelope): KeyboardPassportPreview {
        val categories = envelope.categories.mapNotNull { wire ->
            PassportCategory.entries.firstOrNull { it.wireName == wire }
        }.toSet()
        return KeyboardPassportPreview(
            version = envelope.version,
            createdAt = envelope.createdAt,
            categories = categories,
            counts = envelope.counts,
            encrypted = envelope.encryption != null,
            requiresPassphrase = envelope.encryption != null,
            compatible = envelope.format == FORMAT && envelope.version == CURRENT_VERSION,
            legacy = false
        )
    }

    private fun previewOfLegacy(payload: PassportPayload): KeyboardPassportPreview {
        val categories = buildSet {
            if (payload.personaPreference != null) add(PassportCategory.PERSONA_PREFERENCE)
            if (payload.vocabulary.isNotEmpty()) add(PassportCategory.VOCABULARY)
            if (payload.corrections.isNotEmpty()) add(PassportCategory.CORRECTIONS)
            if (payload.writingLogs.isNotEmpty()) add(PassportCategory.WRITING_LOGS)
        }
        return KeyboardPassportPreview(
            version = 0,
            createdAt = null,
            categories = categories,
            counts = countsOf(payload),
            encrypted = false,
            requiresPassphrase = false,
            compatible = true,
            legacy = true
        )
    }

    private fun countsOf(payload: PassportPayload): PassportRecordCounts = PassportRecordCounts(
        vocabulary = payload.vocabulary.size,
        corrections = payload.corrections.size,
        shortcuts = payload.shortcuts.size,
        customCommands = payload.customCommands.size,
        appPersonas = payload.appPersonas.size,
        writingLogs = payload.writingLogs.size
    )

    private fun sanitize(text: String, enabled: Boolean): String =
        if (enabled) PersonalModelSerializer.sanitizeText(text).first else text

    private fun encrypt(
        plaintext: ByteArray,
        passphrase: String,
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int
    ): ByteArray {
        val key = deriveKey(passphrase, salt, iterations)
        return Cipher.getInstance(CIPHER_TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad)
            doFinal(plaintext)
        }
    }

    private fun decrypt(
        ciphertext: ByteArray,
        passphrase: String,
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int
    ): ByteArray {
        val key = deriveKey(passphrase, salt, iterations)
        return Cipher.getInstance(CIPHER_TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(aad)
            doFinal(ciphertext)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val encoded = SecretKeyFactory.getInstance(KEY_FACTORY).generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decodeBase64(value: String): ByteArray? = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}
