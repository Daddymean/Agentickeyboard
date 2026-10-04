package io.github.daddymean.agentickeyboard.util

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the user's Gemini API key on the device, encrypted with an AES-GCM key
 * that lives in the Android Keystore (never exported, never backed up). The key
 * is entered in Keyboard Settings, so it never has to be baked into an APK.
 */
object GeminiKeyStore {
    private const val TAG = "GeminiKeyStore"
    private const val PREFS = "gemini_key_store"
    private const val PREF_CIPHERTEXT = "ciphertext"
    private const val PREF_IV = "iv"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "agentic_keyboard_gemini_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    /** Trims pasted input; null when it cannot be an API key (too short, inner spaces). */
    fun normalize(raw: String): String? {
        val key = raw.trim()
        if (key.length < 20 || key.any { it.isWhitespace() }) return null
        return key
    }

    /** "AIza…wxyz": enough to recognise which key is saved without revealing it. */
    fun mask(key: String): String =
        if (key.length <= 8) "••••" else key.take(4) + "…" + key.takeLast(4)

    /** The saved key, or null if none is saved or it can no longer be decrypted. */
    fun load(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ciphertext = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(PREF_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "Saved Gemini key could not be decrypted") }.getOrNull()
    }

    /** Encrypts and stores [key]; false if the Keystore refused. */
    fun save(context: Context, key: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        true
    }.onFailure { Log.w(TAG, "Unable to save Gemini key", it) }.getOrDefault(false)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
