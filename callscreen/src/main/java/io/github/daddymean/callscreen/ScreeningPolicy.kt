package io.github.daddymean.callscreen

/** Conservative exact matching; never infer an international country code. */
fun normalize(raw: String?): String? {
    if (raw == null || raw.length > 80 || !raw.matches(Regex("[+0-9 ()\\.-]+"))) return null
    val compact = raw.filterNot { it in " ()-." }
    if (compact.startsWith("+")) {
        return compact.takeIf { it.matches(Regex("\\+[1-9][0-9]{7,14}")) }
    }
    return when {
        compact.matches(Regex("[2-9][0-9]{2}[2-9][0-9]{6}")) -> "+1$compact"
        compact.matches(Regex("1[2-9][0-9]{2}[2-9][0-9]{6}")) -> "+$compact"
        else -> null
    }
}

enum class Action { ALLOW, SILENCE, REJECT }
data class Input(
    val active: Boolean,
    val roleHeld: Boolean,
    val contactsGranted: Boolean,
    val number: String?,
    val knownContact: Boolean,
    val allowlist: Set<String>,
    val unknownAction: Action,
)

fun decide(input: Input): Action = with(input) {
    if (!active || !roleHeld || !contactsGranted || number == null ||
        knownContact || number in allowlist) Action.ALLOW else unknownAction
}
