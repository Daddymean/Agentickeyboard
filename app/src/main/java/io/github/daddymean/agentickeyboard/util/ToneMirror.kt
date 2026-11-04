package io.github.daddymean.agentickeyboard.util

/**
 * Tone mirror, on-device and rule-based (no model, no network).
 *
 * - [outgoingHint]: how Keith's own draft will probably land (curt, warm, pushy), shown before he sends.
 * - [pressureFlags]: influence patterns in an *incoming* message: false choices, presupposed yes,
 *   manufactured urgency, yes-ladders, "don't X until" embedded commands. It only flags them; it never
 *   rewrites, replies or sends.
 *
 * Both features are off by default ([KeyboardSettings.isToneMirrorOutgoing] /
 * [KeyboardSettings.isToneMirrorIncoming]) and gated by [ToneMirror.allowed], which refuses sensitive editors.
 * Nothing it sees is stored or learned.
 */
object ToneMirror {

    enum class Tone(val label: String) { CURT("May read as curt"), WARM("Reads warm"), PUSHY("May read as pushy"), NEUTRAL("Neutral") }

    data class Hint(val tone: Tone, val reason: String)

    enum class Pressure(val label: String, val tip: String) {
        FALSE_CHOICE("Two options, no \"no\"", "You can pick neither."),
        PRESUPPOSED_YES("Assumes you've agreed", "Restate it: \"I haven't decided yet.\""),
        URGENCY("Time pressure", "Real deals survive a night's sleep."),
        YES_LADDER("String of easy yeses", "Answer the real ask on its own."),
        EMBEDDED_COMMAND("Hidden instruction", "Say the command plainly and decide if you want it."),
    }

    /** Both features require their toggle and a non-sensitive editor. */
    fun allowed(toggleOn: Boolean, inputType: Int, imeOptions: Int): Boolean =
        toggleOn && !EditorPrivacy.isSensitiveEditor(inputType, imeOptions)

    private val WARM_WORDS = setOf("thanks", "thank", "appreciate", "please", "glad", "love", "great", "hope", "sorry", "cheers", "happy")
    private val PUSHY = listOf(
        Regex("\\b(asap|right now|immediately|urgent(ly)?)\\b", RegexOption.IGNORE_CASE),
        Regex("\\byou (need|have) to\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(final offer|take it or leave it|last chance)\\b", RegexOption.IGNORE_CASE),
        Regex("!{2,}"),
    )
    private val CURT_ONLY = Regex("^(k|ok|okay|fine|no|yes|sure|whatever|noted|got it)[.!]?$", RegexOption.IGNORE_CASE)

    fun outgoingHint(draft: String): Hint? {
        val text = draft.trim()
        if (text.length < 2) return null
        val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        PUSHY.firstOrNull { it.containsMatchIn(text) }?.let { return Hint(Tone.PUSHY, "\"${it.find(text)!!.value}\" can feel like pressure") }
        val letters = text.filter { it.isLetter() }
        if (letters.length >= 6 && letters.all { it.isUpperCase() }) return Hint(Tone.PUSHY, "all caps reads as shouting")
        if (words.any { it in WARM_WORDS }) return Hint(Tone.WARM, "has a thank-you or softener")
        if (CURT_ONLY.matches(text) || (words.size <= 3 && text.endsWith("."))) return Hint(Tone.CURT, "very short with a full stop")
        return Hint(Tone.NEUTRAL, "")
    }

    private val PATTERNS: List<Pair<Pressure, Regex>> = listOf(
        Pressure.FALSE_CHOICE to Regex("\\b(would you (rather|prefer)|do you want)\\b[^?]{1,60}\\bor\\b[^?]{1,60}\\?", RegexOption.IGNORE_CASE),
        Pressure.PRESUPPOSED_YES to Regex("\\b(when|once|after) you('ve| have)? (decided|signed|bought|started|joined|committed|paid)\\b", RegexOption.IGNORE_CASE),
        Pressure.PRESUPPOSED_YES to Regex("\\b(when you're enjoying|after you get your|as soon as you own)\\b", RegexOption.IGNORE_CASE),
        Pressure.URGENCY to Regex("\\b(only \\d+ left|today only|ends (tonight|today|soon)|expires|before it'?s gone|act now|last chance|won't last)\\b", RegexOption.IGNORE_CASE),
        Pressure.EMBEDDED_COMMAND to Regex("\\bdon'?t (buy|decide|sign|say yes|commit)\\b[^.]{0,40}\\b(until|before)\\b", RegexOption.IGNORE_CASE),
    )
    private val TAG_QUESTION = Regex("\\b(right|isn't it|don't you|wouldn't you|correct)\\s*\\?", RegexOption.IGNORE_CASE)

    fun pressureFlags(incoming: String): List<Pressure> {
        if (incoming.isBlank()) return emptyList()
        val text = incoming.take(2_000)
        val found = PATTERNS.filter { (_, rx) -> rx.containsMatchIn(text) }.map { it.first }.toMutableSet()
        if (TAG_QUESTION.findAll(text).count() >= 2) found += Pressure.YES_LADDER
        return Pressure.values().filter { it in found }
    }
}
