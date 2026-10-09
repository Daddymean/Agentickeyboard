package io.github.daddymean.agentickeyboard.util

import kotlin.math.abs

data class TouchBounds(val left: Float, val top: Float, val width: Float, val height: Float)

/** Guided, intentional samples only. Offsets scale with the current key geometry. */
class TouchCalibration(encoded: String = "") {
    private data class Bias(val x: Float, val y: Float, val count: Int)
    private val biases = mutableMapOf<Char, Bias>()
    init {
        encoded.split(';').forEach { row ->
            val fields = row.split(',')
            if (fields.size == 4) {
                val key = fields[0].singleOrNull()
                val x = fields[1].toFloatOrNull()
                val y = fields[2].toFloatOrNull()
                val count = fields[3].toIntOrNull()
                if (key != null && key in 'a'..'z' && x != null && y != null &&
                    x.isFinite() && y.isFinite() && abs(x) <= .35f && abs(y) <= .35f && count != null && count in 1..100) {
                    biases[key] = Bias(x, y, count)
                }
            }
        }
    }
    fun learn(key: Char, x: Float, y: Float, bounds: TouchBounds): Boolean {
        if (key !in 'a'..'z' || bounds.width <= 0 || bounds.height <= 0 || !x.isFinite() || !y.isFinite()) return false
        val dx = (x - bounds.left) / bounds.width - .5f
        val dy = (y - bounds.top) / bounds.height - .5f
        // Ignore accidental taps far from the requested letter.
        if (abs(dx) > .85f || abs(dy) > .85f) return false
        val old = biases[key] ?: Bias(0f, 0f, 0)
        val n = old.count.coerceAtMost(19)
        biases[key] = Bias((old.x * n + dx.coerceIn(-.35f, .35f)) / (n + 1),
            (old.y * n + dy.coerceIn(-.35f, .35f)) / (n + 1), (old.count + 1).coerceAtMost(100))
        return true
    }
    fun resolve(original: Char, x: Float, y: Float, keys: Map<Char, TouchBounds>): Char {
        if (biases.values.none { it.count >= 3 }) return original
        val origin = keys[original] ?: return original
        if (origin.width <= 0 || origin.height <= 0) return original
        // Keep central taps stable; only reconsider taps near an edge.
        if (abs((x - origin.left) / origin.width - .5f) < .25f &&
            abs((y - origin.top) / origin.height - .5f) < .25f) return original
        return keys.entries.filter { (_, b) ->
            abs(b.left - origin.left) <= origin.width * 1.6f && abs(b.top - origin.top) <= origin.height * 1.6f
        }.minByOrNull { (key, b) ->
            val bias = biases[key]?.takeIf { it.count >= 3 }
            val dx = (x - b.left) / b.width - .5f - (bias?.x ?: 0f)
            val dy = (y - b.top) / b.height - .5f - (bias?.y ?: 0f)
            dx * dx + dy * dy
        }?.key ?: original
    }
    fun restore(encoded: String) {
        val restored = TouchCalibration(encoded)
        biases.clear()
        biases.putAll(restored.biases)
    }
    fun clear() = biases.clear()
    fun encode(): String = biases.entries.sortedBy { it.key }.joinToString(";") { (key, b) -> "$key,${b.x},${b.y},${b.count}" }
    companion object { val TRAINING_KEYS = "qwertyuiopasdfghjklzxcvbnm".repeat(3) }
}
