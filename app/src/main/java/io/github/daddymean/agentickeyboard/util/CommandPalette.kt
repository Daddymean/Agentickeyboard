package io.github.daddymean.agentickeyboard.util

/**
 * Slash-command palette: a draft that starts with a "/" token (e.g. "/firm can
 * we move the call") exposes quick AI commands that run on the rest of the
 * draft, with the token stripped before the action fires.
 */
object CommandPalette {

    /** What the keyboard should run for a command. */
    enum class Action { REWRITE, PROOFREAD, TRANSLATE }

    /**
     * @param token leading token including the slash, e.g. "/firm"
     * @param label short human-readable name shown in the palette
     * @param instruction style/instruction forwarded with a REWRITE action
     */
    data class Command(
        val token: String,
        val label: String,
        val action: Action,
        val instruction: String = ""
    )

    val COMMANDS = listOf(
        Command("/firm", "Firm & direct", Action.REWRITE, "firm, direct and confident"),
        Command("/kind", "Kind & warm", Action.REWRITE, "kind, warm and considerate"),
        Command("/short", "Shorter", Action.REWRITE, "the same message but noticeably shorter and tighter"),
        Command("/long", "Longer", Action.REWRITE, "the same message expanded with more detail"),
        Command("/sell", "Sales pitch", Action.REWRITE, "a persuasive, benefit-led sales message that highlights the item, offer, or opportunity clearly"),
        Command("/close", "Close sale", Action.REWRITE, "a concise message that confidently closes the sale, confirms the next step, and reduces buyer friction"),
        Command("/counteroffer", "Counteroffer", Action.REWRITE, "a confident counteroffer proposing better terms while keeping the conversation open"),
        Command("/followup", "Follow up", Action.REWRITE, "a polite follow-up that restates the value and asks for the next concrete step"),
        Command("/decline", "Polite decline", Action.REWRITE, "a polite but unambiguous decline"),
        Command("/boundary", "Set boundary", Action.REWRITE, "a calm, firm boundary that protects the user's position without escalating"),
        Command("/apologize", "Apology", Action.REWRITE, "a sincere, accountable apology without overexplaining"),
        Command("/proof", "Proofread", Action.PROOFREAD),
        Command("/extract", "Extract key facts", Action.REWRITE, "a compact bullet list of the key facts, dates, amounts, promises, unanswered questions, and action items"),
        Command("/translate", "Translate", Action.TRANSLATE)
    )

    /**
     * The slash token the draft starts with ("/fi" while still typing it), or
     * null when the draft doesn't lead with one.
     */
    fun activeToken(text: String): String? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return null
        return trimmed.takeWhile { !it.isWhitespace() }
    }

    /** Commands whose token begins with the draft's typed token; "/" alone matches all. */
    fun matches(text: String): List<Command> = matches(text, emptyList())

    /**
     * Like [matches], but also offers [custom] user-defined commands after the
     * built-ins. A custom command whose token collides with a built-in is
     * ignored — built-ins always win.
     */
    fun matches(text: String, custom: List<Command>): List<Command> {
        val token = activeToken(text)?.lowercase() ?: return emptyList()
        val builtIns = COMMANDS.filter { it.token.startsWith(token) }
        val builtInTokens = COMMANDS.map { it.token }.toSet()
        return builtIns + custom.filter { it.token.startsWith(token) && it.token !in builtInTokens }
    }

    /**
     * Normalizes user input ("Firm", " /Firm ") into a palette token
     * ("/firm"), or null when it can't be one (blank, inner whitespace, or
     * extra slashes).
     */
    fun normalizeToken(raw: String): String? {
        val cleaned = raw.trim().removePrefix("/").lowercase()
        if (cleaned.isEmpty() || cleaned.any { it.isWhitespace() } || '/' in cleaned) return null
        return "/$cleaned"
    }

    /** [text] without its leading slash token and the whitespace after it. */
    fun stripToken(text: String): String {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return text
        return trimmed.dropWhile { !it.isWhitespace() }.trimStart()
    }
}
