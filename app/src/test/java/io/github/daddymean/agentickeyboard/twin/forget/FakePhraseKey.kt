package io.github.daddymean.agentickeyboard.twin.forget

import io.github.daddymean.agentickeyboard.twin.PhraseKey
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Robolectric has no Android Keystore: the same HMAC-SHA256 with a random software key. */
class FakePhraseKey(private val key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : PhraseKey {
    var calls = 0
    override fun mac(): Mac { calls++; return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) } }
}
