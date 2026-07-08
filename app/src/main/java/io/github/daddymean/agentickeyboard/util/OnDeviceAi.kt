package io.github.daddymean.agentickeyboard.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle of the on-device (Gemini Nano via AICore) feature set. */
enum class OnDeviceAiStatus {
    /** Feature status check has not completed yet. */
    CHECKING,

    /** The model is being downloaded by AICore; not usable yet. */
    DOWNLOADING,

    /** Ready for inference. */
    AVAILABLE,

    /** Device has no AICore support (or the download failed). */
    UNSUPPORTED
}

/**
 * Preset rewrite tones supported by the on-device Rewriting feature. Deliberately
 * our own enum (not ML Kit's int constants) so tone mapping stays pure JVM and
 * unit-testable.
 */
enum class OnDeviceTone { SHORTEN, ELABORATE, FRIENDLY, PROFESSIONAL }

/**
 * Minimal on-device text-AI surface for the offline path. Implementations must
 * return null (or throw) on any failure; callers treat both as "fall back to the
 * heuristics" — an unavailable model is never surfaced as an error.
 */
interface OnDeviceAi {

    /**
     * Availability of the task-specific features (proofread/rewrite/summarize),
     * driven by feature-status checks and model downloads. Phase 1 surface.
     */
    val status: StateFlow<OnDeviceAiStatus>

    /**
     * Availability of the freeform prompt feature backing [generate]. Tracked
     * separately from [status] so a device that lacks (or is still downloading)
     * the prompt model keeps routing the Phase 1 task features on-device, and
     * vice-versa.
     */
    val promptStatus: StateFlow<OnDeviceAiStatus>

    /** Grammar/spelling correction of [text]; null if no usable suggestion. */
    suspend fun proofread(text: String): String?

    /** Rewrite [text] in a preset [tone]; null if no usable suggestion. */
    suspend fun rewrite(text: String, tone: OnDeviceTone): String?

    /** Short summary of [text]; null if no usable summary. */
    suspend fun summarize(text: String): String?

    /**
     * Freeform text generation for the offline replies/compose/continue/tone
     * paths. Returns the model's text, or null on empty/failed output.
     */
    suspend fun generate(prompt: String): String?

    companion object {
        /**
         * Maps the free-form tone/instruction strings the app already uses
         * (personas like "Professional", iterate-chip instructions like "the same
         * message, noticeably shorter and tighter") onto the preset tones the
         * on-device Rewriting feature supports. Returns null when nothing fits —
         * e.g. "Firmer" — so the caller keeps the heuristic fallback instead of
         * mis-toning the text.
         */
        fun toneFor(instruction: String): OnDeviceTone? {
            val t = instruction.lowercase()
            return when {
                "shorter" in t || "shorten" in t || "tighter" in t || "concise" in t -> OnDeviceTone.SHORTEN
                "longer" in t || "expand" in t || "more detail" in t || "elaborate" in t -> OnDeviceTone.ELABORATE
                "formal" in t || "professional" in t -> OnDeviceTone.PROFESSIONAL
                "warmer" in t || "friendl" in t || "joyful" in t || "casual" in t || "empathetic" in t -> OnDeviceTone.FRIENDLY
                else -> null
            }
        }
    }
}

/**
 * The offline routing decision: use the on-device model only when the relevant
 * feature is actually AVAILABLE and it produced a result; anything else — no
 * provider, still downloading, unsupported, null result, or a thrown error —
 * degrades silently to the heuristic [fallback]. Pure JVM so the decision is
 * unit-testable.
 *
 * [statusOf] selects which availability gate applies: the task features
 * ([OnDeviceAi.status], the default) or the freeform prompt feature
 * ([OnDeviceAi.promptStatus]).
 */
object OnDeviceAiRouter {
    suspend fun <T> route(
        ai: OnDeviceAi?,
        onDevice: suspend (OnDeviceAi) -> T?,
        fallback: () -> T,
        statusOf: (OnDeviceAi) -> OnDeviceAiStatus = { it.status.value }
    ): T {
        if (ai == null || statusOf(ai) != OnDeviceAiStatus.AVAILABLE) return fallback()
        return try {
            onDevice(ai) ?: fallback()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fallback()
        }
    }
}
