package io.github.daddymean.agentickeyboard.stealth

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Wire transport mode for a StealthInk payload. */
enum class StealthMode(val code: Byte) {
    /** Explicit compact Base64URL envelope (`🔐AK1:...`). */
    ENVELOPE(0x01),

    /** Authenticated ciphertext hidden inside Unicode Variation Selectors within innocent cover text. */
    INVISIBLE_INK(0x02);

    companion object {
        fun fromCode(code: Byte): StealthMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Optional expiry bound into GCM AAD. Advisory only: it stops this keyboard from opening the
 * message after the time passes. It does not delete anything, and anyone with the passphrase and
 * the ciphertext can still decrypt it with other software (see [StealthInkNotices.EXPIRY]).
 */
enum class StealthTtl(val seconds: Long, val label: String) {
    NONE(0L, "∞"),
    FIVE_MIN(300L, "5m"),
    ONE_HOUR(3_600L, "1h"),
    ONE_DAY(86_400L, "24h")
}

/** Qualitative passphrase strength indicator shown in the StealthInk drawer. */
enum class PassphraseStrength(val label: String) {
    TOO_SHORT("Min 12 chars"),
    WEAK("Too common or simple"),
    FAIR("Fair"),
    GOOD("Good"),
    STRONG("Strong");

    /** Only FAIR or better may be used to seal. */
    val acceptable: Boolean get() = this == FAIR || this == GOOD || this == STRONG
}

/**
 * User-facing limitation notices. Kept in one place so the UI, tests and docs say the same
 * plain thing: StealthInk is encryption with a shared passphrase, not a vanishing message.
 */
object StealthInkNotices {
    const val INVISIBLE_INK =
        "Invisible ink can be detected and stripped by many apps. It hides text from the eye, not from software."
    const val EXPIRY =
        "Expiry is not deletion: copies of the message can persist. It only stops this keyboard from opening it."
    const val SHARED_KEY =
        "Anyone who has the passphrase can read, and forge, messages. Share it in person, not in the same chat."
}

/** Result of attempting to open a StealthInk payload. */
sealed interface StealthOpenResult {
    data class Success(
        val plaintext: String,
        val mode: StealthMode,
        val expiresAtEpochSec: Long?,
        val remainingSeconds: Long?
    ) : StealthOpenResult

    data object WrongPassphraseOrTampered : StealthOpenResult
    data class Expired(val expiredAtEpochSec: Long) : StealthOpenResult
    data object Malformed : StealthOpenResult
    data class UnsupportedVersion(val version: Int) : StealthOpenResult
}

/**
 * Keyboard-native authenticated encryption and steganography codec.
 *
 * Limitations (see [StealthInkNotices]): invisible ink is detectable and strippable, expiry is
 * advisory and is not deletion, the passphrase is a shared symmetric secret (no sender
 * authentication, no forward secrecy), and ciphertext length reveals plaintext length.
 *
 * Turns any text field in any app into an end-to-end encrypted channel using:
 * - **PBKDF2WithHmacSHA256** (600,000 iterations by default, 16-byte random salt, NFC-normalised passphrase)
 * - **AES-256-GCM** (12-byte random IV, 128-bit authentication tag)
 * - **Authenticated AAD header**: `[version(1B) | mode(1B) | flags(1B) | expiresAtEpochSec(8B)]`
 *   so transport mode, compression flag, and the expiry time cannot be altered without
 *   failing GCM verification.
 * - **Invisible Ink steganography**: bijective 1-codepoint-per-byte encoding via Unicode
 *   Variation Selectors (`U+FE00..U+FE0F` for `0x00..0x0F`, `U+E0100..U+E01EF` for
 *   `0x10..0xFF`), bounded by Invisible Separator sentinels (`U+2063`), embedded inside
 *   ordinary cover text with zero visible glyphs.
 *
 * Pure JVM, zero network, zero disk storage, and safe against decompression bombs.
 */
object StealthInkCodec {

    const val FORMAT_VERSION: Byte = 0x01
    const val ENVELOPE_PREFIX = "🔐AK1:"
    const val DEFAULT_PBKDF2_ITERATIONS = 600_000
    const val MIN_PASSPHRASE_CHARS = 12
    const val MAX_PASSPHRASE_CHARS = 128
    const val MAX_PLAINTEXT_BYTES = 2_048

    private const val HEADER_BYTES = 11 // version(1) + mode(1) + flags(1) + expiresAt(8)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val FLAG_COMPRESSED: Byte = 0x01
    private const val COMPRESSION_THRESHOLD_BYTES = 64

    // Unicode Variation Selector byte alphabet: 16 BMP + 240 Supplementary = exact 256-byte bijection.
    private const val VS_BMP_START = 0xFE00
    private const val VS_BMP_END = 0xFE0F
    private const val VS_SUPP_START = 0xE0100
    private const val VS_SUPP_END = 0xE01EF
    private const val STEGO_SENTINEL = 0x2063 // U+2063 INVISIBLE SEPARATOR

    private val LEET = mapOf('0' to 'o', '1' to 'l', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's')

    /** Very common passphrases and phrases, compared after lower-casing, leet-folding and removing symbols. */
    private val COMMON_PASSPHRASES = setOf(
        "passwordpassword", "password123456", "passwordpassword1", "iloveyouiloveyou", "iloveyou123456",
        "letmeinletmein", "welcomewelcome", "administrator", "adminadminadmin", "qwertyuiopasdf",
        "qwertyuiop123", "qwertyqwerty", "qwertyqwertyqwerty", "1q2w3e4r5t6y", "1qaz2wsx3edc",
        "trustno1trustno1", "changemechangeme", "monkeymonkey", "dragondragon", "footballfootball",
        "baseballbaseball", "supermansuperman", "sunshinesunshine", "princessprincess", "starwarsstarwars",
        "thequickbrownfox", "correcthorsebatterystaple", "opensesame", "openthedoor", "letmeinplease",
        "mypasswordismypassword", "thisismypassword", "myverysecretpassword", "secretsecretsecret",
        "keyboardkeyboard", "stealthinkstealthink", "agentickeyboard", "agentickeyboard1"
    )

    private val COMMON_ROOTS = setOf(
        "password", "letmein", "iloveyou", "welcome", "admin", "qwerty", "qwertyuiop", "monkey",
        "dragon", "football", "baseball", "superman", "sunshine", "princess", "starwars", "secret",
        "changeme", "trustno", "opensesame", "stealthink", "agentickeyboard"
    )

    private val KEYBOARD_WALKS = listOf(
        "qwertyuiopasdfghjklzxcvbnm", "qazwsxedcrfvtgbyhnujmikolp", "1q2w3e4r5t6y7u8i9o0p",
        "1qaz2wsx3edc4rfv5tgb", "zxcvbnmasdfghjklqwertyuiop", "poiuytrewqlkjhgfdsamnbvcxz"
    )

    private val ENVELOPE_REGEX = Regex("""🔐AK1:([A-Za-z0-9_-]{40,})""")
    private val SECURE_RANDOM = SecureRandom()

    val DEFAULT_COVERS = listOf(
        "Sounds good, talk soon!",
        "On my way 👍",
        "Got it, thanks!",
        "Let's catch up later"
    )

    /**
     * Seals [plaintext] under [passphrase] as a compact `🔐AK1:...` authenticated envelope.
     */
    fun sealEnvelope(
        plaintext: String,
        passphrase: String,
        ttl: StealthTtl = StealthTtl.NONE,
        nowEpochSec: Long = System.currentTimeMillis() / 1_000L,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS
    ): String {
        val blob = encryptToBlob(plaintext, passphrase, StealthMode.ENVELOPE, ttl, nowEpochSec, iterations)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(blob)
        return ENVELOPE_PREFIX + encoded
    }

    /**
     * Seals [plaintext] under [passphrase] and hides the authenticated binary blob inside
     * [coverText] using invisible Unicode Variation Selectors (1 codepoint per byte).
     */
    fun sealInvisibleInk(
        plaintext: String,
        passphrase: String,
        coverText: String = DEFAULT_COVERS.first(),
        ttl: StealthTtl = StealthTtl.NONE,
        nowEpochSec: Long = System.currentTimeMillis() / 1_000L,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS
    ): String {
        val cleanCover = stripInvisibleInk(coverText).trim().ifEmpty { DEFAULT_COVERS.first() }
        val blob = encryptToBlob(plaintext, passphrase, StealthMode.INVISIBLE_INK, ttl, nowEpochSec, iterations)
        return cleanCover + encodeInvisibleBytes(blob)
    }

    /**
     * Detects whether [text] contains a StealthInk envelope or Invisible Ink stream without
     * performing any cryptography. Runs in O(length).
     */
    fun detect(text: String): StealthMode? {
        if (text.isEmpty()) return null
        if (ENVELOPE_REGEX.containsMatchIn(text)) return StealthMode.ENVELOPE
        if (extractInvisibleBytes(text) != null) return StealthMode.INVISIBLE_INK
        return null
    }

    /**
     * Removes any StealthInk invisible steganographic payload from [text], leaving only
     * the visible cover text.
     */
    fun stripInvisibleInk(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            val isStego = cp == STEGO_SENTINEL ||
                cp in VS_BMP_START..VS_BMP_END ||
                cp in VS_SUPP_START..VS_SUPP_END
            if (!isStego) {
                out.appendCodePoint(cp)
            }
            i += charCount
        }
        return out.toString()
    }

    /**
     * Extracts and opens a StealthInk payload (either `🔐AK1:...` envelope or Invisible Ink)
     * from anywhere inside [text], authenticating header + TTL before revealing plaintext.
     */
    fun open(
        text: String,
        passphrase: String,
        nowEpochSec: Long = System.currentTimeMillis() / 1_000L,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS
    ): StealthOpenResult {
        if (text.isBlank() || passphrase.length < MIN_PASSPHRASE_CHARS) {
            return if (detect(text) == null) StealthOpenResult.Malformed else StealthOpenResult.WrongPassphraseOrTampered
        }

        val (expectedMode, blob) = extractBlob(text) ?: return StealthOpenResult.Malformed
        return decryptBlob(blob, expectedMode, passphrase, nowEpochSec, iterations)
    }

    /**
     * Rates [passphrase] for sealing. This is a deliberately simple local heuristic, not an
     * entropy estimate: it rejects short, very common, repeated, sequential and low-variety
     * passphrases. Anyone holding a ciphertext can guess passphrases offline, so the
     * passphrase, not the cipher, is the weakest link.
     */
    fun passphraseStrength(passphrase: String): PassphraseStrength {
        val length = passphrase.codePointCount(0, passphrase.length)
        if (length < MIN_PASSPHRASE_CHARS) return PassphraseStrength.TOO_SHORT
        if (length > MAX_PASSPHRASE_CHARS) return PassphraseStrength.WEAK
        if (isLowVariety(passphrase) || isCommonPassphrase(passphrase)) return PassphraseStrength.WEAK

        var classes = 0
        if (passphrase.any { it.isLowerCase() }) classes++
        if (passphrase.any { it.isUpperCase() }) classes++
        if (passphrase.any { it.isDigit() }) classes++
        if (passphrase.any { !it.isLetterOrDigit() }) classes++ // symbols and spaces
        return when {
            length >= 20 || (length >= 16 && classes >= 3) -> PassphraseStrength.STRONG
            length >= 16 || classes >= 3 -> PassphraseStrength.GOOD
            else -> PassphraseStrength.FAIR
        }
    }

    /** True when [passphrase] may be used to seal (length 12..128 and not weak). */
    fun isAcceptablePassphrase(passphrase: String): Boolean =
        passphraseStrength(passphrase).acceptable

    /** Fewer than 6 distinct characters, one repeated unit, or a plain ascending/descending run. */
    private fun isLowVariety(passphrase: String): Boolean {
        val points = passphrase.codePoints().toArray()
        if (points.toSet().size < 6) return true
        // Repeated short unit, e.g. "abcabcabcabc" or "hellohellohello".
        for (unit in 1..points.size / 2) {
            if (points.size % unit != 0) continue
            var repeats = true
            for (i in unit until points.size) {
                if (points[i] != points[i - unit]) { repeats = false; break }
            }
            if (repeats) return true
        }
        // Monotone run, e.g. "123456789012" or "abcdefghijkl" (allow up to 1 break in 12).
        var steps = 0
        for (i in 1 until points.size) {
            val d = points[i] - points[i - 1]
            if (d == 1 || d == -1) steps++
        }
        return steps >= points.size - 2
    }

    private fun isCommonPassphrase(passphrase: String): Boolean {
        val lower = passphrase.lowercase()
        // Also compare with any trailing digits/symbols (years, "123!") removed before folding.
        val bases = listOf(lower, lower.trimEnd { !it.isLetter() })
        val candidates = bases.flatMap { base ->
            val plain = base.filter { it.isLetterOrDigit() }
            val leet = base.map { LEET[it] ?: it }.filter { it.isLetterOrDigit() }.joinToString("")
            listOf(plain, leet)
        }.toSet()
        return candidates.any { c ->
            c in COMMON_PASSPHRASES ||
                COMMON_ROOTS.any { root -> c == root || c == root + root } ||
                (c.length >= 10 && KEYBOARD_WALKS.any { walk -> walk.contains(c) })
        }
    }

    fun formatRemaining(seconds: Long): String {
        if (seconds <= 0L) return "expired"
        val hours = seconds / 3_600L
        val mins = (seconds % 3_600L) / 60L
        val secs = seconds % 60L
        return when {
            hours >= 1L -> "${hours}h ${mins}m"
            mins >= 1L -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }

    // --- internal crypto -------------------------------------------------------------

    private fun encryptToBlob(
        plaintext: String,
        passphrase: String,
        mode: StealthMode,
        ttl: StealthTtl,
        nowEpochSec: Long,
        iterations: Int
    ): ByteArray {
        require(passphrase.codePointCount(0, passphrase.length) in MIN_PASSPHRASE_CHARS..MAX_PASSPHRASE_CHARS) {
            "Passphrase must be $MIN_PASSPHRASE_CHARS..$MAX_PASSPHRASE_CHARS characters"
        }
        require(isAcceptablePassphrase(passphrase)) {
            "Passphrase is too common or too simple; use a longer, less predictable phrase"
        }
        val rawBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
        require(rawBytes.isNotEmpty() && rawBytes.size <= MAX_PLAINTEXT_BYTES) {
            "Plaintext must be 1..$MAX_PLAINTEXT_BYTES UTF-8 bytes"
        }

        var flags: Byte = 0
        val payloadBytes = if (rawBytes.size >= COMPRESSION_THRESHOLD_BYTES) {
            val deflated = deflate(rawBytes)
            if (deflated.size < rawBytes.size) {
                flags = FLAG_COMPRESSED
                deflated
            } else {
                rawBytes
            }
        } else {
            rawBytes
        }

        val expiresAt = if (ttl == StealthTtl.NONE) 0L else nowEpochSec + ttl.seconds
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .put(FORMAT_VERSION)
            .put(mode.code)
            .put(flags)
            .putLong(expiresAt)
            .array()

        val salt = ByteArray(SALT_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        val keyBytes = deriveKey(passphrase, salt, iterations)
        val ciphertext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            cipher.doFinal(payloadBytes)
        } finally {
            keyBytes.fill(0)
        }

        return ByteBuffer.allocate(HEADER_BYTES + SALT_BYTES + IV_BYTES + ciphertext.size)
            .put(header)
            .put(salt)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decryptBlob(
        blob: ByteArray,
        expectedMode: StealthMode,
        passphrase: String,
        nowEpochSec: Long,
        iterations: Int
    ): StealthOpenResult {
        val minSize = HEADER_BYTES + SALT_BYTES + IV_BYTES + GCM_TAG_BYTES + 1
        if (blob.size < minSize) return StealthOpenResult.Malformed

        val buf = ByteBuffer.wrap(blob)
        val version = buf.get()
        if (version != FORMAT_VERSION) return StealthOpenResult.UnsupportedVersion(version.toInt() and 0xFF)
        val modeByte = buf.get()
        val mode = StealthMode.fromCode(modeByte) ?: return StealthOpenResult.Malformed
        if (mode != expectedMode) return StealthOpenResult.WrongPassphraseOrTampered
        val flags = buf.get()
        val expiresAt = buf.getLong()

        val header = blob.copyOfRange(0, HEADER_BYTES)
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val iv = ByteArray(IV_BYTES).also { buf.get(it) }
        val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }

        val keyBytes = deriveKey(passphrase, salt, iterations)
        val decrypted = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            return StealthOpenResult.WrongPassphraseOrTampered
        } catch (_: Exception) {
            return StealthOpenResult.WrongPassphraseOrTampered
        } finally {
            keyBytes.fill(0)
        }

        // Only trust expiresAt AFTER GCM verified the header AAD!
        if (expiresAt > 0L && nowEpochSec > expiresAt) {
            decrypted.fill(0)
            return StealthOpenResult.Expired(expiresAt)
        }

        val plainBytes = if ((flags.toInt() and FLAG_COMPRESSED.toInt()) != 0) {
            inflateBounded(decrypted) ?: return StealthOpenResult.Malformed
        } else {
            if (decrypted.size > MAX_PLAINTEXT_BYTES) return StealthOpenResult.Malformed
            decrypted
        }

        val remaining = if (expiresAt > 0L) (expiresAt - nowEpochSec).coerceAtLeast(0L) else null
        return StealthOpenResult.Success(
            plaintext = String(plainBytes, StandardCharsets.UTF_8),
            mode = mode,
            expiresAtEpochSec = expiresAt.takeIf { it > 0L },
            remainingSeconds = remaining
        )
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        // NFC-normalise so the same visible passphrase typed on different keyboards derives one key.
        val chars = java.text.Normalizer.normalize(passphrase, java.text.Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun extractBlob(text: String): Pair<StealthMode, ByteArray>? {
        val envMatch = ENVELOPE_REGEX.find(text)
        if (envMatch != null) {
            val decoded = runCatching { Base64.getUrlDecoder().decode(envMatch.groupValues[1]) }.getOrNull()
            if (decoded != null) return StealthMode.ENVELOPE to decoded
        }
        val stego = extractInvisibleBytes(text)
        if (stego != null) return StealthMode.INVISIBLE_INK to stego
        return null
    }

    // --- Variation Selector steganography --------------------------------------------

    private fun encodeInvisibleBytes(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2 + 2)
        sb.appendCodePoint(STEGO_SENTINEL)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val cp = if (v < 16) VS_BMP_START + v else VS_SUPP_START + (v - 16)
            sb.appendCodePoint(cp)
        }
        sb.appendCodePoint(STEGO_SENTINEL)
        return sb.toString()
    }

    private fun extractInvisibleBytes(text: String): ByteArray? {
        var inStream = false
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (cp == STEGO_SENTINEL) {
                if (inStream) {
                    val bytes = out.toByteArray()
                    if (bytes.size >= HEADER_BYTES + SALT_BYTES + IV_BYTES + GCM_TAG_BYTES + 1) {
                        return bytes
                    }
                    // Too short: reset and keep scanning in case an earlier stray sentinel appeared.
                    out.reset()
                }
                inStream = true
            } else if (inStream) {
                when (cp) {
                    in VS_BMP_START..VS_BMP_END -> out.write(cp - VS_BMP_START)
                    in VS_SUPP_START..VS_SUPP_END -> out.write(16 + (cp - VS_SUPP_START))
                    else -> {
                        // Stream interrupted by a non-selector character; abandon partial buffer.
                        inStream = false
                        out.reset()
                    }
                }
            }
            i += charCount
        }
        return null
    }

    // --- bounded compression ---------------------------------------------------------

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream(input.size)
            val buf = ByteArray(256)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflateBounded(input: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val out = ByteArrayOutputStream(input.size)
            val buf = ByteArray(256)
            var total = 0
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    return null
                }
                total += n
                if (total > MAX_PLAINTEXT_BYTES) return null // decompression bomb guard
                out.write(buf, 0, n)
            }
            if (!inflater.finished()) null else out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
