package io.github.daddymean.agentickeyboard.twin.deadman

import java.security.MessageDigest

/**
 * KEYBOARD-027: one holder's key share as text, to print or write down.
 *
 * `AKDM1-<set id, 16 hex>-<epoch>-<k>OF<n>-<x>-<share, base32>-<check, 8 hex>` (upper case)
 *
 * The set id and epoch name the key-holder set it belongs to, so shares from different
 * sets (before and after a holder change) can't be mixed by mistake. The check is the
 * first 4 bytes of SHA-256 over everything before it, which catches typos.
 */
data class ShareCard(val setId: String, val epoch: Int, val k: Int, val n: Int, val share: Shamir.Share) {
    fun encode(): String {
        val body = "$PREFIX-${setId.uppercase()}-$epoch-${k}OF$n-${share.x}-${Base32.encode(share.y)}"
        return "$body-${check(body)}"
    }

    companion object {
        const val PREFIX = "AKDM1"

        private fun check(body: String): String =
            MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.US_ASCII)).copyOf(4)
                .joinToString("") { "%02X".format(it) }

        /** Parses a card, ignoring spaces, line breaks and case. */
        fun parse(text: String): ShareCard {
            val t = text.filterNot { it.isWhitespace() }.uppercase()
            val parts = t.split('-')
            require(parts.size == 7 && parts[0] == PREFIX) { "not a twin key share" }
            val body = parts.dropLast(1).joinToString("-")
            require(check(body) == parts[6]) { "share card check failed (typo?)" }
            val (k, n) = parts[3].split("OF").map(String::toInt)
            return ShareCard(parts[1].lowercase(), parts[2].toInt(), k, n, Shamir.Share(parts[4].toInt(), Base32.decode(parts[5])))
        }
    }
}

internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(b: ByteArray): String {
        val sb = StringBuilder()
        var buf = 0
        var bits = 0
        for (byte in b) {
            buf = (buf shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "bad character in share" }
            buf = (buf shl 5) or v
            bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray()
    }
}
