package io.github.daddymean.agentickeyboard.twin

import android.content.Context

/** KEYBOARD-043: every twin action the policy gate knows, with its risk level. */
enum class TwinAction(val risk: TwinActionPolicy.Risk, val label: String) {
    ASK_TWIN(TwinActionPolicy.Risk.READ, "Ask the twin"),
    VIEW_MEMORIES(TwinActionPolicy.Risk.READ, "View memories"),
    APPROVE_MEMORY(TwinActionPolicy.Risk.WRITE, "Approve a memory"),
    EDIT_CORE_MEMORIES(TwinActionPolicy.Risk.WRITE, "Edit core memories"),
    SEAL_CAPSULE(TwinActionPolicy.Risk.WRITE, "Seal a time capsule"),
    DELETE_CAPSULE(TwinActionPolicy.Risk.DANGEROUS, "Delete a time capsule"),
    EXPORT(TwinActionPolicy.Risk.SENSITIVE, "Save an encrypted export"),
    SAVE_RELEASE_ARCHIVE(TwinActionPolicy.Risk.SENSITIVE, "Save a release archive"),
    SAVE_SHARE_CARDS(TwinActionPolicy.Risk.SENSITIVE, "Save share cards and the key file"),
    REISSUE_SHARE_CARDS(TwinActionPolicy.Risk.DANGEROUS, "Reissue all share cards"),
    REPLACE_KEY_HOLDER(TwinActionPolicy.Risk.DANGEROUS, "Replace a key holder");

    /** Stored in preferences; never rename. */
    val id: String get() = name.lowercase()
}

/**
 * KEYBOARD-043: the policy gate around twin actions.
 *
 * - [Risk.READ] runs without asking.
 * - [Risk.WRITE] asks once; if Keith ticks "remember", that one action is allowed
 *   from then on until he revokes it in the twin settings.
 * - [Risk.SENSITIVE] and [Risk.DANGEROUS] ask every time and are never remembered,
 *   even if a grant somehow exists in storage.
 *
 * The risk-tier and ask-once pattern is inspired by jackwener/DSH-Android
 * (Apache-2.0); this is a clean re-implementation. Pure logic over [Grants].
 */
class TwinActionPolicy(private val grants: Grants) {
    enum class Risk { READ, WRITE, SENSITIVE, DANGEROUS }
    enum class Decision { ALLOW, ASK_ONCE, ASK_ALWAYS }

    interface Grants {
        fun isGranted(id: String): Boolean
        fun grant(id: String)
        fun revoke(id: String)
        fun granted(): Set<String>
    }

    fun decide(action: TwinAction): Decision = when (action.risk) {
        Risk.READ -> Decision.ALLOW
        Risk.WRITE -> if (grants.isGranted(action.id)) Decision.ALLOW else Decision.ASK_ONCE
        Risk.SENSITIVE, Risk.DANGEROUS -> Decision.ASK_ALWAYS
    }

    /** Keith said yes. Only a WRITE action can be remembered. */
    fun approved(action: TwinAction, remember: Boolean) {
        if (remember && action.risk == Risk.WRITE) grants.grant(action.id)
    }

    fun revoke(action: TwinAction) = grants.revoke(action.id)

    /** Remembered WRITE actions, for the settings screen. */
    fun remembered(): List<TwinAction> =
        TwinAction.entries.filter { it.risk == Risk.WRITE && grants.isGranted(it.id) }

    /** Grants kept in the twin's private preferences file. */
    class PrefsGrants(context: Context) : Grants {
        private val prefs = context.applicationContext.getSharedPreferences(TwinPreferences.PREFS, Context.MODE_PRIVATE)
        override fun granted(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()
        override fun isGranted(id: String) = id in granted()
        override fun grant(id: String) { prefs.edit().putStringSet(KEY, granted() + id).apply() }
        override fun revoke(id: String) { prefs.edit().putStringSet(KEY, granted() - id).apply() }
        private companion object { const val KEY = "twin_action_grants" }
    }

    companion object {
        fun of(context: Context) = TwinActionPolicy(PrefsGrants(context))
    }
}
