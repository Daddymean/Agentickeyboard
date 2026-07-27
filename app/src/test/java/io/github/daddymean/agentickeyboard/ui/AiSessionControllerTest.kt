package io.github.daddymean.agentickeyboard.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSessionControllerTest {

    @Test
    fun actionThatPublishesNothingReturnsToIdle() = runTest {
        val controller = AiSessionController(this)

        controller.launch { }
        advanceUntilIdle()

        assertEquals(AiPanelState.Idle, controller.currentState)
    }

    @Test
    fun completedPanelSurvivesLoadingCleanup() = runTest {
        val controller = AiSessionController(this)
        val result = AiPanelState.Compose("Finished draft")

        controller.launch { publish(result) }
        advanceUntilIdle()

        assertEquals(result, controller.currentState)
    }

    @Test
    fun unexpectedFailureClearsLoadingState() = runTest {
        val controller = AiSessionController(this)

        controller.launch { error("boom") }
        advanceUntilIdle()

        assertEquals(AiPanelState.Idle, controller.currentState)
    }

    @Test
    fun newerActionCancelsOlderActionAndOwnsPanel() = runTest {
        val controller = AiSessionController(this)
        var firstCancelled = false

        controller.launch {
            try {
                delay(Long.MAX_VALUE)
            } finally {
                firstCancelled = true
            }
        }
        runCurrent()
        assertEquals(AiPanelState.Loading, controller.currentState)

        val newest = AiPanelState.Explanation("Newest result")
        controller.launch { publish(newest) }
        advanceUntilIdle()

        assertTrue(firstCancelled)
        assertEquals(newest, controller.currentState)
    }

    @Test
    fun regenerateUsesLatestRegisteredAction() {
        val controller = AiSessionController(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        var firstRan = false
        var secondRan = false

        controller.setRegenerateAction { firstRan = true }
        controller.setRegenerateAction { secondRan = true }
        controller.regenerate()

        assertFalse(firstRan)
        assertTrue(secondRan)
    }

    @Test
    fun clearDismissesCurrentPanel() = runTest {
        val controller = AiSessionController(this)
        controller.publish(AiPanelState.Summary("summary", "source"))

        controller.clear()

        assertEquals(AiPanelState.Idle, controller.currentState)
    }
}
