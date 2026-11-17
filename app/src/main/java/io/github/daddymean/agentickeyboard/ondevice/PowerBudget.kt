package io.github.daddymean.agentickeyboard.ondevice

import android.content.Context
import android.os.PowerManager
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus

/**
 * LAB-006: battery-aware local AI. While Battery Saver is on, or the phone
 * reports severe thermal throttling, local Gemma 4 E2B (a 2.59 GB model, the
 * heaviest thing the keyboard can run) is paused: requests go to Gemini Nano
 * (system-managed AICore) or the keyboard's existing local helpers instead.
 * Nothing goes to the cloud as a result. Neither signal needs a permission.
 */
object PowerBudget {

    /** PowerManager.THERMAL_STATUS_SEVERE. */
    const val THERMAL_SEVERE = 3

    data class Snapshot(val powerSave: Boolean, val thermalStatus: Int) {
        val pausesLocalModel: Boolean get() = powerSave || thermalStatus >= THERMAL_SEVERE
    }

    val UNCONSTRAINED = Snapshot(powerSave = false, thermalStatus = 0)

    fun read(context: Context): Snapshot {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return UNCONSTRAINED
        return runCatching { Snapshot(pm.isPowerSaveMode, pm.currentThermalStatus) }.getOrDefault(UNCONSTRAINED)
    }
}

/**
 * Builds the router context, checking the cheap conditions first. Gemma's
 * readiness check SHA-256s the whole model file, so it runs only when the model
 * could actually be used (opted in, device supported, not paused by [PowerBudget]).
 * [OnDeviceEngineRouter] and [CascadingOnDeviceAi] read these three fields only
 * through `localEligible`, so the routing decision is the same as evaluating all
 * three every time; only the power pause changes it.
 */
object OnDeviceContextFactory {
    fun build(
        privacyBlocked: Boolean,
        nanoStatus: OnDeviceAiStatus,
        power: PowerBudget.Snapshot,
        isEnabled: () -> Boolean,
        isSupported: () -> Boolean,
        isReady: () -> Boolean
    ): OnDeviceEngineRouter.Context {
        val enabled = !power.pausesLocalModel && isEnabled()
        val supported = enabled && isSupported()
        val ready = supported && isReady()
        return OnDeviceEngineRouter.Context(
            privacyBlocked = privacyBlocked,
            nanoStatus = nanoStatus,
            localEnabled = enabled,
            localReady = ready,
            localSupported = supported
        )
    }
}
