package io.github.daddymean.agentickeyboard.twin.journal

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea

/**
 * KEYBOARD-037: the only code that touches ML Kit Digital Ink. Callers must check
 * [InkModelGate.approved] first. [download] is the one network step: a one-time fetch of
 * the model from Google, on Wi-Fi only. [recognize] runs on the device.
 */
class InkRecognizer(private val approved: Boolean) {
    private val model: DigitalInkRecognitionModel by lazy {
        DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.fromLanguageTag(InkModelGate.LANGUAGE)!!).build()
    }

    fun isDownloaded(done: (Boolean) -> Unit) {
        if (!approved) { done(false); return }
        RemoteModelManager.getInstance().isModelDownloaded(model).addOnSuccessListener { done(it) }.addOnFailureListener { done(false) }
    }

    fun download(done: (Throwable?) -> Unit) {
        check(approved) { "Keith hasn't approved the handwriting model download" }
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().requireWifi().build())
            .addOnSuccessListener { done(null) }.addOnFailureListener { done(it) }
    }

    fun delete(done: () -> Unit) {
        RemoteModelManager.getInstance().deleteDownloadedModel(model).addOnCompleteListener { done() }
    }

    fun recognize(ink: InkStrokes, done: (String?, Throwable?) -> Unit) {
        check(approved) { "Keith hasn't approved handwriting recognition" }
        val b = Ink.builder()
        for (s in ink.strokes) {
            val sb = Ink.Stroke.builder()
            var i = 0
            while (i + 2 < s.size) { sb.addPoint(Ink.Point.create(s[i], s[i + 1], s[i + 2].toLong())); i += 3 }
            b.addStroke(sb.build())
        }
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        val ctx = RecognitionContext.builder().setPreContext("").setWritingArea(WritingArea(ink.width.toFloat(), ink.height.toFloat())).build()
        recognizer.recognize(b.build(), ctx)
            .addOnSuccessListener { r -> done(r.candidates.firstOrNull()?.text, null) }
            .addOnFailureListener { done(null, it) }
            .addOnCompleteListener { recognizer.close() }
    }

    companion object { const val NAME = "ML Kit Digital Ink (on device)" }
}
