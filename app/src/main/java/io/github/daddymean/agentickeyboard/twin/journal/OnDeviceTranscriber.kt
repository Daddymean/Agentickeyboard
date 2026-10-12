package io.github.daddymean.agentickeyboard.twin.journal

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * KEYBOARD-025: dictation for the transcript field using **only** Android's on-device
 * recognizer ([SpeechRecognizer.createOnDeviceSpeechRecognizer]). Nothing is sent to a
 * cloud service: when on-device recognition isn't available, [isAvailable] is false and
 * the screen offers a typed transcript instead. (Transcribing the saved recording itself,
 * e.g. with Whisper.cpp, is a later step.)
 */
class OnDeviceTranscriber(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Must be called on the main thread. */
    fun start(onText: (String, Boolean) -> Unit, onError: (String) -> Unit) {
        if (!isAvailable) { onError("On-device speech recognition isn't available on this phone."); return }
        stop()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onText(it, true) }
                stop()
            }
            override fun onPartialResults(partial: Bundle) {
                partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onText(it, false) }
            }
            override fun onError(error: Int) { onError("Dictation stopped (code $error)."); stop() }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
