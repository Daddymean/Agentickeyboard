package io.github.daddymean.agentickeyboard.util

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ML Kit GenAI-backed [OnDeviceAi]: Gemini Nano through AICore. Inference runs
 * out-of-process in AICore, so nothing heavy is loaded into the keyboard
 * process; the clients here are thin service bindings created lazily.
 *
 * English-first by design (the beta APIs support a handful of languages; the
 * app's offline features are English-only anyway). All failures return null or
 * flip status to UNSUPPORTED — never a user-facing error.
 */
class MlKitOnDeviceAi(context: Context, scope: CoroutineScope) : OnDeviceAi {

    private val appContext = context.applicationContext

    private val _status = MutableStateFlow(OnDeviceAiStatus.CHECKING)
    override val status = _status.asStateFlow()

    private val proofreader: Proofreader by lazy {
        Proofreading.getClient(
            ProofreaderOptions.builder(appContext)
                .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(ProofreaderOptions.Language.ENGLISH)
                .build()
        )
    }

    private val summarizer: Summarizer by lazy {
        Summarization.getClient(
            SummarizerOptions.builder(appContext)
                .setInputType(SummarizerOptions.InputType.CONVERSATION)
                .setOutputType(SummarizerOptions.OutputType.ONE_BULLET)
                .setLanguage(SummarizerOptions.Language.ENGLISH)
                .setLongInputAutoTruncationEnabled(true)
                .build()
        )
    }

    // One Rewriter per preset tone (the output type is fixed per client).
    private val rewriters = ConcurrentHashMap<OnDeviceTone, Rewriter>()

    private fun rewriterFor(tone: OnDeviceTone): Rewriter = rewriters.getOrPut(tone) {
        val outputType = when (tone) {
            OnDeviceTone.SHORTEN -> RewriterOptions.OutputType.SHORTEN
            OnDeviceTone.ELABORATE -> RewriterOptions.OutputType.ELABORATE
            OnDeviceTone.FRIENDLY -> RewriterOptions.OutputType.FRIENDLY
            OnDeviceTone.PROFESSIONAL -> RewriterOptions.OutputType.PROFESSIONAL
        }
        Rewriting.getClient(
            RewriterOptions.builder(appContext)
                .setOutputType(outputType)
                .setLanguage(RewriterOptions.Language.ENGLISH)
                .build()
        )
    }

    init {
        scope.launch { refresh() }
    }

    /**
     * Checks feature status for all three features, kicking off model downloads
     * where the device supports them, and resolves [status] to a single value:
     * AVAILABLE only when every feature is ready.
     */
    private suspend fun refresh() {
        try {
            var statuses = checkAll()
            if (statuses.any { it == FeatureStatus.DOWNLOADABLE || it == FeatureStatus.DOWNLOADING }) {
                _status.value = OnDeviceAiStatus.DOWNLOADING
                downloadWhereNeeded(statuses)
                statuses = checkAll()
            }
            _status.value = if (statuses.all { it == FeatureStatus.AVAILABLE }) {
                OnDeviceAiStatus.AVAILABLE
            } else {
                OnDeviceAiStatus.UNSUPPORTED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Deliberately broad (incl. Errors from missing GMS/AICore classes):
            // a device without on-device AI must never crash or surface errors.
            Log.i(TAG, "On-device AI unavailable: ${t.message}")
            _status.value = OnDeviceAiStatus.UNSUPPORTED
        }
    }

    private suspend fun checkAll(): List<Int> = listOf(
        proofreader.checkFeatureStatus().await(),
        rewriterFor(OnDeviceTone.PROFESSIONAL).checkFeatureStatus().await(),
        summarizer.checkFeatureStatus().await()
    )

    private suspend fun downloadWhereNeeded(statuses: List<Int>) {
        if (statuses[0] == FeatureStatus.DOWNLOADABLE) proofreader.downloadFeature(loggingCallback("proofreading")).await()
        if (statuses[1] == FeatureStatus.DOWNLOADABLE) rewriterFor(OnDeviceTone.PROFESSIONAL).downloadFeature(loggingCallback("rewriting")).await()
        if (statuses[2] == FeatureStatus.DOWNLOADABLE) summarizer.downloadFeature(loggingCallback("summarization")).await()
    }

    private fun loggingCallback(feature: String) = object : DownloadCallback {
        override fun onDownloadStarted(bytesToDownload: Long) {
            Log.i(TAG, "$feature model download started ($bytesToDownload bytes)")
        }

        override fun onDownloadProgress(totalBytesDownloaded: Long) {}

        override fun onDownloadCompleted() {
            Log.i(TAG, "$feature model download completed")
        }

        override fun onDownloadFailed(e: GenAiException) {
            Log.i(TAG, "$feature model download failed: ${e.message}")
        }
    }

    override suspend fun proofread(text: String): String? {
        if (text.isBlank()) return null
        val result = proofreader.runInference(ProofreadingRequest.builder(text).build()).await()
        return cleanOutput(result.results.firstOrNull()?.text)
    }

    override suspend fun rewrite(text: String, tone: OnDeviceTone): String? {
        if (text.isBlank()) return null
        val result = rewriterFor(tone).runInference(RewritingRequest.builder(text).build()).await()
        return cleanOutput(result.results.firstOrNull()?.text)
    }

    override suspend fun summarize(text: String): String? {
        if (text.isBlank()) return null
        val result = summarizer.runInference(SummarizationRequest.builder(text).build()).await()
        // The summarizer emits bullet-formatted output; strip the markers so the
        // shelf shows plain prose like the cloud summary does.
        val summary = cleanOutput(result.summary) ?: return null
        return summary.lines().joinToString(" ") { it.replace(BULLET_PREFIX, "").trim() }
            .trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Same output hygiene as the cloud path: drop stray code fences and treat
     * empty output as "no result".
     */
    private fun cleanOutput(raw: String?): String? {
        var text = raw?.trim() ?: return null
        if (text.startsWith("```")) {
            text = text.removePrefix("```").trim().removeSuffix("```").trim()
        }
        return text.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "MlKitOnDeviceAi"
        private val BULLET_PREFIX = "^\\s*[-*•]\\s+".toRegex()
    }
}
