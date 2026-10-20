package io.github.daddymean.agentickeyboard.twin.journal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * KEYBOARD-037: a handwritten answer. The strokes are kept exactly as written (x, y in view
 * pixels, t in ms since the first point), so the kids get Dad's actual handwriting. A PNG
 * of it is kept too. Both go into the journal answer, which is encrypted like every other.
 */
class InkStrokes(val width: Int, val height: Int) {
    /** Each stroke is x0, y0, t0, x1, y1, t1, … */
    val strokes = ArrayList<FloatArray>()
    private var current: ArrayList<Float>? = null
    private var t0 = -1L

    fun down(x: Float, y: Float, t: Long) { if (t0 < 0) t0 = t; current = arrayListOf(x, y, (t - t0).toFloat()) }
    fun move(x: Float, y: Float, t: Long) { current?.addAll(listOf(x, y, (t - t0).toFloat())) }
    fun up(x: Float, y: Float, t: Long) { move(x, y, t); current?.let { strokes += it.toFloatArray() }; current = null }
    fun clear() { strokes.clear(); current = null; t0 = -1 }
    val isEmpty: Boolean get() = strokes.isEmpty()
    fun pointCount() = strokes.sumOf { it.size / 3 }

    /** The live stroke too, for drawing while the finger is down. */
    fun drawable(): List<FloatArray> = strokes + listOfNotNull(current?.toFloatArray())

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("w", width).put("h", height)
        .put("strokes", JSONArray(strokes.map { s -> JSONArray(s.map { (Math.round(it * 10) / 10.0) }) }))

    /** A white PNG with the strokes in dark ink, at most [maxSide] px on the long side. */
    fun png(maxSide: Int = 1600): ByteArray {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height, 1))
        val bmp = Bitmap.createBitmap(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()), Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            draw(c, drawable(), scale)
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bmp.recycle() }
    }

    companion object {
        const val MAX_POINTS = 50_000

        fun fromJson(o: JSONObject): InkStrokes {
            val ink = InkStrokes(o.getInt("w"), o.getInt("h"))
            val a = o.getJSONArray("strokes")
            for (i in 0 until a.length()) {
                val s = a.getJSONArray(i)
                ink.strokes += FloatArray(s.length()) { s.getDouble(it).toFloat() }
            }
            return ink
        }

        fun paint(scale: Float = 1f) = Paint().apply {
            color = Color.rgb(20, 24, 60); style = Paint.Style.STROKE; strokeWidth = 5f * maxOf(scale, 0.4f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
        }

        fun draw(c: Canvas, strokes: List<FloatArray>, scale: Float = 1f) {
            val p = paint(scale)
            for (s in strokes) {
                if (s.size < 3) continue
                val path = Path().apply { moveTo(s[0] * scale, s[1] * scale) }
                var i = 3
                while (i + 1 < s.size) { path.lineTo(s[i] * scale, s[i + 1] * scale); i += 3 }
                if (s.size == 3) c.drawPoint(s[0] * scale, s[1] * scale, p) else c.drawPath(path, p)
            }
        }

        /** The journal answer body for a handwritten answer. [text] is what Keith confirmed it says. */
        fun answerBody(promptId: String, child: String?, prompt: String, category: String, ink: InkStrokes, text: String, recognizedBy: String?): JSONObject {
            require(!ink.isEmpty) { "Write something first." }
            require(ink.pointCount() <= MAX_POINTS) { "That's a lot of ink. Please save it as two answers." }
            return JSONObject()
                .put("promptId", promptId).put("child", child ?: "").put("prompt", prompt).put("category", category)
                .put("followUp", false).put("revisit", false)
                .put("text", text.trim()).put("transcript", "")
                .put("audio", JSONArray())
                .put("answer_mode", "handwriting")
                .put("ink", ink.toJson())
                .put("ink_png", Base64.encodeToString(ink.png(), Base64.NO_WRAP))
                .put("ink_text_source", recognizedBy ?: "typed by Keith")
        }
    }
}

/**
 * KEYBOARD-037: ML Kit Digital Ink recognises handwriting **on the device**, but it needs a
 * language model (~20 MB) **downloaded from Google once**. That download, and ML Kit's
 * diagnostics (device model, app version, configured language, input and output sizes,
 * never the ink or the text), are network traffic. So recognition stays off until Keith
 * approves the one-time download. The approval is a journal event, so it is on record and
 * in the export. Without it, handwriting is still captured and Keith types what it says.
 */
object InkModelGate {
    const val EVENT = "ink_model"
    const val LANGUAGE = "en-US"
    const val DISCLOSURE =
        "To read your handwriting, the phone needs Google's handwriting model for English (about 20 MB). It is " +
            "downloaded once from Google over Wi-Fi; after that, recognition runs entirely on this phone. Your " +
            "handwriting and the recognised text are never sent. ML Kit does send Google basic diagnostics (phone model, " +
            "app version, the language, input sizes). Without this, your handwriting is still saved and you type what it says."

    fun approved(records: List<JournalRecord>): Boolean =
        records.lastOrNull { it.kind == JournalKinds.EVENT && it.body.optString("type") == EVENT }?.body?.optBoolean("approved") == true

    fun approval(approved: Boolean): JSONObject = JSONObject().put("type", EVENT).put("approved", approved).put("language", LANGUAGE)
}
