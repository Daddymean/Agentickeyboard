package io.github.daddymean.agentickeyboard.twin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals one twin record. */
interface TwinCipher {
    class Sealed(val iv: ByteArray, val ciphertext: ByteArray)

    fun seal(plaintext: ByteArray, aad: ByteArray): Sealed

    /** Throws when the key is gone or the record was tampered with. */
    fun open(sealed: Sealed, aad: ByteArray): ByteArray

    /** Destroys the key, so every record sealed with it is unreadable for good. */
    fun destroyKey()
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore (the GeminiKeyStore
 * pattern): it is generated on the device, never exported and never backed up.
 * Every record gets a fresh 96-bit IV from the Keystore and a 128-bit tag.
 * Deleting the alias crypto-shreds the whole store.
 */
class KeystoreTwinCipher(private val alias: String = KEY_ALIAS) : TwinCipher {

    override fun seal(plaintext: ByteArray, aad: ByteArray): TwinCipher.Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad)
        return TwinCipher.Sealed(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun open(sealed: TwinCipher.Sealed, aad: ByteArray): ByteArray {
        val key = key(create = false) ?: error("Twin key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    override fun destroyKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun key(create: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "agentic_keyboard_twin_store_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
