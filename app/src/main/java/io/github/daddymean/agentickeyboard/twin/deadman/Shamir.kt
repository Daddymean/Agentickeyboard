package io.github.daddymean.agentickeyboard.twin.deadman

import java.security.SecureRandom

/**
 * KEYBOARD-027: Shamir's secret sharing over GF(2^8) (the AES field, polynomial 0x11B),
 * byte by byte. With threshold k, any k shares recover the secret and k − 1 shares
 * reveal nothing about it. Share x-coordinates are 1..n. The same arithmetic is in
 * `tools/twin-export/open_twin_release.py`.
 */
object Shamir {
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x xor (x shl 1) // multiply by the generator 3
            if (x and 0x100 != 0) x = x xor 0x11B
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    internal fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    internal fun div(a: Int, b: Int): Int {
        require(b != 0) { "division by zero" }
        return if (a == 0) 0 else exp[log[a] + 255 - log[b]]
    }

    data class Share(val x: Int, val y: ByteArray) {
        override fun equals(other: Any?) = other is Share && other.x == x && other.y.contentEquals(y)
        override fun hashCode() = 31 * x + y.contentHashCode()
    }

    fun split(secret: ByteArray, n: Int, k: Int, random: SecureRandom = SecureRandom()): List<Share> {
        require(k in 2..n && n <= 255) { "need 2 <= k <= n <= 255" }
        val ys = List(n) { ByteArray(secret.size) }
        val coeff = IntArray(k)
        for (i in secret.indices) {
            coeff[0] = secret[i].toInt() and 0xFF
            for (j in 1 until k) coeff[j] = random.nextInt(256)
            for (s in 0 until n) {
                val x = s + 1
                var y = 0
                for (j in k - 1 downTo 0) y = mul(y, x) xor coeff[j] // Horner
                ys[s][i] = y.toByte()
            }
        }
        coeff.fill(0)
        return ys.mapIndexed { s, y -> Share(s + 1, y) }
    }

    /** Lagrange interpolation at x = 0. Pass exactly the threshold number of shares (or more). */
    fun combine(shares: List<Share>): ByteArray {
        require(shares.size >= 2) { "need at least two shares" }
        require(shares.map { it.x }.toSet().size == shares.size && shares.all { it.x in 1..255 }) { "duplicate or bad share" }
        val len = shares[0].y.size
        require(shares.all { it.y.size == len }) { "shares differ in length" }
        val out = ByteArray(len)
        for (i in 0 until len) {
            var acc = 0
            for ((a, sa) in shares.withIndex()) {
                var num = 1
                var den = 1
                for ((b, sb) in shares.withIndex()) if (a != b) {
                    num = mul(num, sb.x)
                    den = mul(den, sa.x xor sb.x)
                }
                acc = acc xor mul(sa.y[i].toInt() and 0xFF, div(num, den))
            }
            out[i] = acc.toByte()
        }
        return out
    }
}
