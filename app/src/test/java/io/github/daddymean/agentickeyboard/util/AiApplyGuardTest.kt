package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApplyGuardTest {

    @Test
    fun `unchanged draft can be applied`() {
        assertFalse(AiApplyGuard.isStale("meet at noon", "meet at noon"))
    }

    @Test
    fun `a trailing space typed after the request is not a change`() {
        assertFalse(AiApplyGuard.isStale("meet at noon", "meet at noon "))
    }

    @Test
    fun `text typed after the request makes the result stale`() {
        // Rewrite requested on draft A; user keeps typing to B before Apply.
        assertTrue(AiApplyGuard.isStale("meet at noon", "meet at noon, bring the slides"))
    }

    @Test
    fun `deleted or replaced text makes the result stale`() {
        assertTrue(AiApplyGuard.isStale("meet at noon", "meet at"))
        assertTrue(AiApplyGuard.isStale("meet at noon", "lunch tomorrow?"))
    }

    @Test
    fun `a different selection makes the result stale`() {
        assertTrue(AiApplyGuard.isStale("first paragraph", "second paragraph"))
    }

    @Test
    fun `results not bound to a draft are never refused`() {
        assertFalse(AiApplyGuard.isStale(null, "anything"))
    }
}
