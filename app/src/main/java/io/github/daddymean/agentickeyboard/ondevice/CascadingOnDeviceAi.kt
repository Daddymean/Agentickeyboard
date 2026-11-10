package io.github.daddymean.agentickeyboard.ondevice

import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.util.OnDeviceTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [OnDeviceAi] that prefers Gemini Nano, then local Gemma 4 E2B, then
 * leaves a null result so the existing heuristic path runs. Privacy-blocked
 * fields never reach either model.
 */
class CascadingOnDeviceAi(
    private val nano: OnDeviceAi,
    private val local: OnDeviceAi,
    private val context: () -> OnDeviceEngineRouter.Context
) : OnDeviceAi {

    private val _status = MutableStateFlow(resolve(nano.status.value, context()))
    private val _promptStatus = MutableStateFlow(resolve(nano.promptStatus.value, context()))
    override val status = _status.asStateFlow()
    override val promptStatus = _promptStatus.asStateFlow()

    fun refreshStatus() {
        val ctx = context()
        _status.value = resolve(nano.status.value, ctx)
        _promptStatus.value = resolve(nano.promptStatus.value, ctx)
    }

    private fun resolve(nanoStatus: OnDeviceAiStatus, ctx: OnDeviceEngineRouter.Context): OnDeviceAiStatus {
        if (nanoStatus == OnDeviceAiStatus.AVAILABLE) return OnDeviceAiStatus.AVAILABLE
        if (ctx.localEnabled && ctx.localReady) return OnDeviceAiStatus.AVAILABLE
        return nanoStatus
    }

    override suspend fun proofread(text: String): String? = cascade(task = true) { it.proofread(text) }

    override suspend fun rewrite(text: String, tone: OnDeviceTone): String? =
        cascade(task = true) { it.rewrite(text, tone) }

    override suspend fun summarize(text: String): String? = cascade(task = true) { it.summarize(text) }

    override suspend fun generate(prompt: String): String? = cascade(task = false) { it.generate(prompt) }

    private suspend fun cascade(task: Boolean, call: suspend (OnDeviceAi) -> String?): String? {
        refreshStatus()
        val ctx = context()
        if (ctx.privacyBlocked) return null
        val nanoStatus = if (task) nano.status.value else nano.promptStatus.value
        return OnDeviceEngineRouter.route(
            ctx.copy(nanoStatus = nanoStatus),
            onNano = { call(nano) },
            onLocal = { call(local) },
            fallback = { null }
        )
    }
}
