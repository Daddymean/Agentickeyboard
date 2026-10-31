package io.github.daddymean.agentickeyboard.twin

import io.github.daddymean.agentickeyboard.twin.TwinActionPolicy.Decision
import io.github.daddymean.agentickeyboard.twin.TwinActionPolicy.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinActionPolicyTest {
    private class MemGrants : TwinActionPolicy.Grants {
        val set = mutableSetOf<String>()
        override fun isGranted(id: String) = id in set
        override fun grant(id: String) { set += id }
        override fun revoke(id: String) { set -= id }
        override fun granted() = set.toSet()
    }

    private val grants = MemGrants()
    private val policy = TwinActionPolicy(grants)

    @Test fun `read is always allowed`() {
        for (a in TwinAction.entries.filter { it.risk == Risk.READ }) assertEquals(Decision.ALLOW, policy.decide(a))
    }

    @Test fun `write asks once then is remembered per action`() {
        assertEquals(Decision.ASK_ONCE, policy.decide(TwinAction.SEAL_CAPSULE))
        policy.approved(TwinAction.SEAL_CAPSULE, remember = true)
        assertEquals(Decision.ALLOW, policy.decide(TwinAction.SEAL_CAPSULE))
        assertEquals(Decision.ASK_ONCE, policy.decide(TwinAction.APPROVE_MEMORY))
    }

    @Test fun `write approved without remembering asks again`() {
        policy.approved(TwinAction.EDIT_CORE_MEMORIES, remember = false)
        assertEquals(Decision.ASK_ONCE, policy.decide(TwinAction.EDIT_CORE_MEMORIES))
    }

    @Test fun `revoking a write grant asks again`() {
        policy.approved(TwinAction.SEAL_CAPSULE, remember = true)
        policy.revoke(TwinAction.SEAL_CAPSULE)
        assertEquals(Decision.ASK_ONCE, policy.decide(TwinAction.SEAL_CAPSULE))
        assertTrue(policy.remembered().isEmpty())
    }

    @Test fun `sensitive and dangerous always ask and are never remembered`() {
        for (a in TwinAction.entries.filter { it.risk >= Risk.SENSITIVE }) {
            policy.approved(a, remember = true)
            assertEquals(a.name, Decision.ASK_ALWAYS, policy.decide(a))
        }
        assertTrue(grants.set.isEmpty())
    }

    @Test fun `a stale grant for a sensitive action is ignored`() {
        grants.grant(TwinAction.EXPORT.id)
        assertEquals(Decision.ASK_ALWAYS, policy.decide(TwinAction.EXPORT))
        assertFalse(policy.remembered().contains(TwinAction.EXPORT))
    }

    @Test fun `key actions carry the expected risk`() {
        assertEquals(Risk.SENSITIVE, TwinAction.EXPORT.risk)
        assertEquals(Risk.SENSITIVE, TwinAction.SAVE_RELEASE_ARCHIVE.risk)
        assertEquals(Risk.SENSITIVE, TwinAction.SAVE_SHARE_CARDS.risk)
        assertEquals(Risk.DANGEROUS, TwinAction.REISSUE_SHARE_CARDS.risk)
        assertEquals(Risk.DANGEROUS, TwinAction.REPLACE_KEY_HOLDER.risk)
        assertEquals(Risk.DANGEROUS, TwinAction.DELETE_CAPSULE.risk)
        assertEquals(Risk.WRITE, TwinAction.SEAL_CAPSULE.risk)
        assertEquals(Risk.READ, TwinAction.ASK_TWIN.risk)
    }

    @Test fun `action ids are unique and stable`() {
        assertEquals(TwinAction.entries.size, TwinAction.entries.map { it.id }.toSet().size)
        assertEquals("seal_capsule", TwinAction.SEAL_CAPSULE.id)
    }
}
