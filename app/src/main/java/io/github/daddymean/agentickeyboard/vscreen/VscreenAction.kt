package io.github.daddymean.agentickeyboard.vscreen

/**
 * One proposed call from Agentic Keyboard to Agent-VScreen. Pure data so
 * [VscreenCompanion.plan] can be unit-tested without a network or Activity.
 */
data class VscreenAction(
    /** Master switch; must stay false until Keith signs this off and the user opts in. */
    val enabled: Boolean = false,
    /** User-pasted Agent-VScreen token; never logged or put in a URL. */
    val token: String? = null,
    val host: String = VscreenEndpoint.DEFAULT_HOST,
    val port: Int = VscreenEndpoint.DEFAULT_PORT,
    /** Package the companion would launch or type into, if known. */
    val targetPackage: String? = null,
    val inputType: Int = 0,
    val imeOptions: Int = 0,
    /** Autofill hints, hint text, label, field name, private IME options. */
    val hints: List<String?> = emptyList(),
    val secureWindow: Boolean = false,
    val ownPackage: String? = null,
    /** Keith's "never learn here" / banking list, same set as the twin. */
    val secureApps: Set<String> = emptySet(),
    /** CLI args for `POST /v1/run`, e.g. `["launch", "Settings"]`. No secrets. */
    val args: List<String> = emptyList(),
)

sealed class VscreenDecision {
    data class Allow(val request: VscreenHttpRequest) : VscreenDecision()
    data class Refuse(val reason: Reason, val detail: String) : VscreenDecision()

    enum class Reason {
        DISABLED,
        MISSING_TOKEN,
        BAD_HOST,
        BAD_PORT,
        PRIVACY,
    }
}
