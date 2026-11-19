package io.github.daddymean.agentickeyboard.twin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.daddymean.agentickeyboard.twin.memory.MemoryIndex
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** Gives a ready HMAC-SHA256 [Mac] for never-learn phrases. The key must not leave the device. */
fun interface PhraseKey {
    fun mac(): Mac
}

/**
 * HMAC-SHA256 with a key generated in the Android Keystore: non-exportable, never backed up,
 * so a dump of the settings file can't be dictionary-attacked off the phone.
 */
class KeystorePhraseKey(private val alias: String = KEY_ALIAS) : PhraseKey {
    override fun mac(): Mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).apply { init(key()) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE)
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "agentic_keyboard_twin_never_learn_hmac_v1"
        private const val KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * "Forget and never learn this again" for a phrase (twin-memory viewer).
 *
 * A phrase is its folded words (lower case, no accents, letters and digits only, like the
 * memory search). It matches a text when those words appear in a row, so "art" does not
 * match "start". The list stores only `v2:<n>:<HMAC-SHA256(words)>` per phrase, keyed by
 * [PhraseKey] (Android Keystore in the app), never the phrase and never a bare hash.
 * Entries in any older format can't be checked or turned back into phrases; [migrate]
 * drops them. Pure logic apart from the key.
 */
object NeverLearnPhrases {
    const val MAX_WORDS = 8
    private const val PREFIX = "v2:"

    /** The folded words of [phrase], or empty when it has none or more than [MAX_WORDS]. */
    fun words(phrase: String): List<String> =
        MemoryIndex.tokens(phrase).takeIf { it.size in 1..MAX_WORDS } ?: emptyList()

    /** The stored form of [phrase], or null when it can't be used. */
    fun entry(phrase: String, key: PhraseKey): String? =
        words(phrase).takeIf { it.isNotEmpty() }?.let { "$PREFIX${it.size}:${mac(key.mac(), it)}" }

    /** Keeps only current-format entries. */
    fun migrate(entries: Set<String>): Set<String> = entries.filterTo(LinkedHashSet()) { isCurrent(it) }

    fun isCurrent(entry: String): Boolean =
        entry.startsWith(PREFIX) && entry.removePrefix(PREFIX).substringBefore(':').toIntOrNull() in 1..MAX_WORDS

    /** True when [text] contains any phrase in [entries]. */
    fun matches(text: String, entries: Set<String>, key: PhraseKey): Boolean {
        val current = entries.filter(::isCurrent).toSet()
        if (current.isEmpty()) return false
        val tokens = MemoryIndex.tokens(text)
        val sizes = current.map { it.removePrefix(PREFIX).substringBefore(':').toInt() }.toSet()
        val m = key.mac()
        for (n in sizes) {
            for (i in 0..tokens.size - n) {
                if ("$PREFIX$n:${mac(m, tokens.subList(i, i + n))}" in current) return true
            }
        }
        return false
    }

    /** True when [text] holds the words of [phrase] in a row. */
    fun contains(text: String, phrase: String): Boolean {
        val p = words(phrase)
        if (p.isEmpty()) return false
        val t = MemoryIndex.tokens(text)
        if (t.size < p.size) return false
        for (i in 0..t.size - p.size) if (t.subList(i, i + p.size) == p) return true
        return false
    }

    private fun mac(m: Mac, words: List<String>): String =
        m.doFinal(words.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
