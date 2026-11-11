package io.github.daddymean.agentickeyboard.ondevice

import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Routing for rewrite / proofread / tone:
 * 1. Refuse entirely on a privacy-blocked field (password, OTP, payment, incognito).
 * 2. Gemini Nano (ML Kit) when it is AVAILABLE and allowed, unless a rewrite
 *    prefers local Gemma when that engine is eligible.
 * 3. Local Gemma 4 E2B when the user opted in, the model is ready, and the
 *    device is supported (ABI + RAM).
 * 4. If the first engine fails (null, timeout, init/inference exception), try
 *    the other on-device engine. Never a silent cloud fallback.
 *
 * Pure JVM so unit tests can use a fake engine. Failure reasons passed to
 * [onFailure] are enum or class names only — never user text.
 */
object OnDeviceEngineRouter {

    enum class Engine { NANO, LOCAL_GEMMA, EXISTING }

    data class Context(
        val privacyBlocked: Boolean,
        val nanoStatus: OnDeviceAiStatus,
        val localEnabled: Boolean,
        val localReady: Boolean,
        val localSupported: Boolean = true
    ) {
        val localEligible: Boolean
            get() = localEnabled && localReady && localSupported
    }

    fun decide(context: Context, nanoBlockedOrBusy: Boolean = false): Engine {
        if (context.privacyBlocked) return Engine.EXISTING
        val nanoAllowed = context.nanoStatus == OnDeviceAiStatus.AVAILABLE && !nanoBlockedOrBusy
        if (nanoAllowed) return Engine.NANO
        if (context.localEligible) return Engine.LOCAL_GEMMA
        return Engine.EXISTING
    }

    /**
     * Runs [onNano] then [onLocal] according to [decide] (or local-first when
     * [preferLocalIfReady] and the model is eligible), then the other engine
     * if the first fails, then [fallback].
     *
     * A timeout or any non-cancellation failure is a failed attempt, not an
     * abort of the cascade. When [surfaceUnavailable] is set and at least one
     * engine was attempted, both failing throws
     * [OnDeviceRewriteUnavailableException] instead of a silent fallback.
     */
    suspend fun <T> route(
        context: Context,
        onNano: suspend () -> T?,
        onLocal: suspend () -> T?,
        fallback: () -> T,
        preferLocalIfReady: Boolean = false,
        onFailure: (Engine, String) -> Unit = { _, _ -> },
        surfaceUnavailable: Boolean = false
    ): T {
        if (context.privacyBlocked) return fallback()

        val engines = LinkedHashSet<Engine>()
        if (preferLocalIfReady && context.localEligible) {
            engines += Engine.LOCAL_GEMMA
            if (context.nanoStatus == OnDeviceAiStatus.AVAILABLE) engines += Engine.NANO
        } else {
            val first = decide(context)
            if (first != Engine.EXISTING) engines += first
            if (first != Engine.LOCAL_GEMMA && context.localEligible) engines += Engine.LOCAL_GEMMA
            if (first != Engine.NANO && context.nanoStatus == OnDeviceAiStatus.AVAILABLE) {
                engines += Engine.NANO
            }
        }

        var attempted = false
        for (engine in engines) {
            attempted = true
            try {
                val value = if (engine == Engine.NANO) onNano() else onLocal()
                if (value != null) return value
                onFailure(engine, GemmaFailureReason.EMPTY_OUTPUT.name)
            } catch (e: TimeoutCancellationException) {
                onFailure(engine, GemmaFailureReason.TIMEOUT.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GemmaInferenceException) {
                onFailure(engine, e.reason.name)
            } catch (e: Exception) {
                onFailure(engine, e.javaClass.simpleName)
            }
        }
        if (surfaceUnavailable && attempted) {
            throw OnDeviceRewriteUnavailableException()
        }
        return fallback()
    }
}
