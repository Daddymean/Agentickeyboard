package io.github.daddymean.agentickeyboard.ondevice

import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAB-006: power-aware local Gemma and the lazy readiness (full-model hash) check. */
class PowerAwareLocalAiTest {

    private class Probe(val enabled: Boolean, val supported: Boolean = true, val ready: Boolean = true) {
        var hashes = 0
        fun build(power: PowerBudget.Snapshot, nano: OnDeviceAiStatus = OnDeviceAiStatus.UNSUPPORTED) =
            OnDeviceContextFactory.build(false, nano, power, { enabled }, { supported }, { hashes++; ready })

        /** What the Application built before LAB-006: every field, every time. */
        fun legacy(nano: OnDeviceAiStatus = OnDeviceAiStatus.UNSUPPORTED) =
            OnDeviceEngineRouter.Context(false, nano, enabled, run { hashes++; ready }, supported)
    }

    private val normal = PowerBudget.UNCONSTRAINED
    private val saver = PowerBudget.Snapshot(powerSave = true, thermalStatus = 0)
    private val hot = PowerBudget.Snapshot(powerSave = false, thermalStatus = PowerBudget.THERMAL_SEVERE)

    @Test
    fun pauseSignals() {
        assertFalse(normal.pausesLocalModel)
        assertTrue(saver.pausesLocalModel)
        assertTrue(hot.pausesLocalModel)
        assertFalse(PowerBudget.Snapshot(false, PowerBudget.THERMAL_SEVERE - 1).pausesLocalModel)
    }

    @Test
    fun routingIsUnchangedWithoutAPowerPause() {
        for (enabled in listOf(true, false)) for (supported in listOf(true, false)) for (ready in listOf(true, false))
            for (nano in OnDeviceAiStatus.values()) {
                val p = Probe(enabled, supported, ready)
                val new = p.build(normal, nano)
                val old = p.legacy(nano)
                assertEquals(old.localEligible, new.localEligible)
                assertEquals(OnDeviceEngineRouter.decide(old), OnDeviceEngineRouter.decide(new))
            }
    }

    @Test
    fun saverOrHeat_neverRoutesToLocalGemma() {
        for (power in listOf(saver, hot)) {
            val ctx = Probe(enabled = true).build(power)
            assertFalse(ctx.localEligible)
            assertEquals(OnDeviceEngineRouter.Engine.EXISTING, OnDeviceEngineRouter.decide(ctx))
            assertEquals(OnDeviceEngineRouter.Engine.NANO, OnDeviceEngineRouter.decide(ctx.copy(nanoStatus = OnDeviceAiStatus.AVAILABLE)))
        }
    }

    /**
     * LAB-006 measurement: full-model SHA-256 checks caused by the router context
     * over 10 AI requests. CascadingOnDeviceAi.cascade() builds the context twice
     * per request (refreshStatus + route). Counts, not time; one check is ~1.4 s
     * of CPU on the box JVM (LAB-002) for the 2.59 GB file.
     */
    @Test
    fun hashesPerTenRequests() {
        val requests = 10
        fun count(p: Probe, build: () -> Unit): Int { p.hashes = 0; repeat(requests * 2) { build() }; return p.hashes }
        val rows = listOf(
            "gemma off, file kept" to Probe(enabled = false),
            "gemma on, normal" to Probe(enabled = true),
            "gemma on, battery saver" to Probe(enabled = true)
        ).map { (name, p) ->
            val power = if (name.endsWith("saver")) saver else normal
            Triple(name, count(p) { p.legacy() }, count(p) { p.build(power) })
        }
        rows.forEach { (name, before, after) -> println("LAB-006 hashes per $requests requests, $name: before=$before after=$after") }
        assertEquals(listOf(20, 20, 20), rows.map { it.second })
        assertEquals(listOf(0, 20, 0), rows.map { it.third })
    }
}
