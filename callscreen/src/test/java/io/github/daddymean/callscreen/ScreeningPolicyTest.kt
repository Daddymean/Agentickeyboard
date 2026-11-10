package io.github.daddymean.callscreen

import org.junit.Assert.*
import org.junit.Test

class ScreeningPolicyTest {
    @Test fun normalizesExactInternationalAndUsNumbers() {
        assertEquals("+14155550100", normalize("(415) 555-0100"))
        assertEquals("+14155550100", normalize("1 415 555 0100"))
        assertEquals("+442079460123", normalize("+44 20 7946 0123"))
    }
    @Test fun rejectsAmbiguousOrExecutableInputs() {
        for (s in listOf<String?>(null, "", "911", "*67 4155550100", "4155550100,123", "tel:+14155550100", "+01234567890", "１２３４５６７８９０", "++14155550100")) {
            assertNull(s, normalize(s))
        }
    }
    @Test fun observationAlwaysAllows() {
        assertEquals(Action.ALLOW, decide(Input(false, true, true, "+14155550100", false, emptySet(), Action.REJECT)))
    }
    @Test fun contactsAndExactAllowlistOverrideUnknownAction() {
        assertEquals(Action.ALLOW, decide(input(known = true)))
        assertEquals(Action.ALLOW, decide(input(allowlist = setOf("+14155550100"))))
        assertEquals(Action.REJECT, decide(input(allowlist = setOf("+4414155550100"))))
        assertEquals(Action.SILENCE, decide(input(action = Action.SILENCE)))
    }
    @Test fun permissionRoleAndInvalidNumberFailOpen() {
        assertEquals(Action.ALLOW, decide(input().copy(roleHeld = false)))
        assertEquals(Action.ALLOW, decide(input().copy(contactsGranted = false)))
        assertEquals(Action.ALLOW, decide(input().copy(number = null)))
    }
    @Test fun deadlineCannotRejectAndLateResultsAreIgnored() {
        var now = 0L
        val results = mutableListOf<Action>()
        val gate = ResponseGate(2500L, { now }, { results.add(it) })
        now = 2500L
        gate.complete(Action.REJECT)
        gate.complete(Action.SILENCE)
        assertEquals(listOf(Action.ALLOW), results)
    }
    @Test fun watchdogAllowsExactlyOnce() {
        val results = mutableListOf<Action>()
        val gate = ResponseGate(2500L, { 0L }, { results.add(it) })
        gate.failOpen()
        gate.complete(Action.REJECT)
        assertEquals(listOf(Action.ALLOW), results)
    }
    @Test fun timelyResponseKeepsChosenAction() {
        val results = mutableListOf<Action>()
        ResponseGate(2500L, { 2499L }, { results.add(it) }).complete(Action.SILENCE)
        assertEquals(listOf(Action.SILENCE), results)
    }
    @Test fun decisionExceptionFailsOpen() {
        val results = mutableListOf<Action>()
        ResponseGate(2500L, { 0L }, { results.add(it) }).evaluate { throw SecurityException() }
        assertEquals(listOf(Action.ALLOW), results)
    }
    @Test fun concurrentWorkerAndWatchdogRespondOnce() {
        val results = java.util.Collections.synchronizedList(mutableListOf<Action>())
        val gate = ResponseGate(2500L, { 2501L }, { results.add(it) })
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..20).map {
            Thread { start.await(); gate.complete(Action.REJECT) }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(1000L) }
        assertTrue(threads.none { it.isAlive })
        assertEquals(listOf(Action.ALLOW), results)
    }
    private fun input(known: Boolean = false, allowlist: Set<String> = emptySet(), action: Action = Action.REJECT) =
        Input(true, true, true, "+14155550100", known, allowlist, action)
}
