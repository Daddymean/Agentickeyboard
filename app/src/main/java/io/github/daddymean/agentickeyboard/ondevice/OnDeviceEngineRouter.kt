package io.github.daddymean.agentickeyboard.ondevice

import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import kotlinx.coroutines.CancellationException

/**
 * Routing for rewrite / proofread / tone:
 * 1. Refuse entirely on a privacy-blocked field (password, OTP, payment, incognito).
 * 2. Gemini Nano when it is AVAILABLE and allowed.
 * 3. Local Gemma 4 E2B when the user opted in and the model is downloaded.
 * 4. Otherwise the existing (heuristic) behavior.
 *
 * Never a silent cloud fallback. Pure JVM so unit tests can use a fake engine.
 */
object OnDeviceEngineRouter {

    enum class Engine { NANO, LOCAL_GEMMA, EXISTING }

    data class Context(
        val privacyBlocked: Boolean,
        val nanoStatus: OnDeviceAiStatus,
        val localEnabled: Boolean,
        val localReady: Boolean
    )

    fun decide(context: Context, nanoBlockedOrBusy: Boolean = false): Engine {
        if (context.privacyBlocked) return Engine.EXISTING
        val nanoAllowed = context.nanoStatus == OnDeviceAiStatus.AVAILABLE && !nanoBlockedOrBusy
        if (nanoAllowed) return Engine.NANO
        if (context.localEnabled && context.localReady) return Engine.LOCAL_GEMMA
        return Engine.EXISTING
    }

    /**
     * Runs [onNano] then [onLocal] according to [decide], then [fallback].
     * A blocked/busy Nano error, a null result, or any other non-cancellation
     * Nano failure falls through to local Gemma when it is ready, then to
     * [fallback]. Cloud is never invoked here.
     */
    suspend fun <T> route(
        context: Context,
        onNano: suspend () -> T?,
        onLocal: suspend () -> T?,
        fallback: () -> T
    ): T {
        if (context.privacyBlocked) return fallback()
        val first = decide(context)
        if (first == Engine.NANO) {
            try {
                onNano()?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val blocked = NanoErrorCodes.isBlockedOrBusy(e)
                val after = decide(context, nanoBlockedOrBusy = blocked || true)
                if (after == Engine.LOCAL_GEMMA) {
                    try {
                        onLocal()?.let { return it }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        return fallback()
                    }
                }
                return fallback()
            }
            if (context.localEnabled && context.localReady) {
                try {
                    onLocal()?.let { return it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return fallback()
                }
            }
            return fallback()
        }
        if (first == Engine.LOCAL_GEMMA) {
            try {
                onLocal()?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return fallback()
            }
        }
        return fallback()
    }
}
