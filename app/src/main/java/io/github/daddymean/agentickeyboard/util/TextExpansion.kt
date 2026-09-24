package io.github.daddymean.agentickeyboard.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Expands `{token}` placeholders inside shortcut templates and saved snippets.
 *
 * Snippets and shortcuts used to be dead strings: "On my way!" could only ever be
 * that. Tokens turn them into small templates — a standup note that always carries
 * today's date, a reply that pulls in whatever was just copied, a letter that drops
 * the caret where you actually start writing.
 *
 * Everything here is pure: the clock, clipboard, locale and time zone all arrive in
 * [ExpansionContext], so expansion is deterministic and exercised by JVM tests.
 *
 * Grammar:
 * ```
 *   {date}            {time}            {datetime}
 *   {date:EEEE}       {time:HH:mm}      pattern is a SimpleDateFormat pattern
 *   {date+1d}         {date-2w}         offset, optionally with a pattern:
 *   {date+1d:EEEE}                      "tomorrow's weekday name"
 *   {clipboard}                         current clipboard text, empty when unset
 *   {cursor}                            where the caret lands; emits nothing
 *   {{ and }}                           literal braces
 * ```
 * Offset units are `min`, `h`, `d`, `w`, `mo`, `y`. Month is `mo` and minute is
 * `min` so neither has to guess at a bare `m`.
 *
 * Anything unrecognised is left exactly as typed. A user who writes `{foo}`, or a
 * JSON snippet full of braces, gets their own text back rather than silent loss.
 */
object TextExpansion {

    data class ExpansionContext(
        val now: Long = System.currentTimeMillis(),
        val clipboard: String? = null,
        val locale: Locale = Locale.getDefault(),
        val timeZone: TimeZone = TimeZone.getDefault()
    )

    /**
     * @param text the expanded text.
     * @param cursorOffset index within [text] where the caret should land, or null
     *   when the template declared no `{cursor}`.
     */
    data class ExpandedText(val text: String, val cursorOffset: Int? = null)

    /** One supported token, for the hint shown in the snippet and shortcut editors. */
    data class TokenHelp(val token: String, val description: String)

    val SUPPORTED_TOKENS: List<TokenHelp> = listOf(
        TokenHelp("{date}", "Today, e.g. Mar 4, 2026"),
        TokenHelp("{time}", "Now, e.g. 9:30 AM"),
        TokenHelp("{datetime}", "Today and now"),
        TokenHelp("{date+1d}", "Offset by min/h/d/w/mo/y"),
        TokenHelp("{date:EEEE}", "Your own date format"),
        TokenHelp("{clipboard}", "What you last copied"),
        TokenHelp("{cursor}", "Where the caret lands")
    )

    /**
     * Supporting text for a template editor: a live preview once the template uses
     * tokens, and a short prompt advertising them when it does not. Tokens are worth
     * nothing if no one discovers them, and a preview teaches the grammar faster than
     * documentation nobody opens.
     */
    fun editorHint(template: String, context: ExpansionContext = ExpansionContext()): String {
        if (!hasTokens(template)) {
            return "Tokens: {date} {time} {clipboard} {cursor} — try {date+1d:EEEE}"
        }
        val preview = expand(template, context).text.replace('\n', ' ').trim()
        if (preview.isEmpty()) return "Inserts nothing yet"
        val shown = if (preview.length <= 80) preview else preview.take(79) + "…"
        return "Inserts: $shown"
    }

    private const val DEFAULT_DATE_PATTERN = "MMM d, yyyy"
    private const val DEFAULT_TIME_PATTERN = "h:mm a"
    private const val DEFAULT_DATETIME_PATTERN = "MMM d, yyyy h:mm a"

    /** `name` plus an optional signed offset, e.g. "date", "date+1d", "time-30min". */
    private val SPEC_REGEX = Regex("^([a-zA-Z]+)(?:([+-])(\\d+)(min|mo|[hdwy]))?$")

    /** True when [template] contains anything this object would rewrite. */
    fun hasTokens(template: String): Boolean =
        template.contains('{') || template.contains('}')

    fun expand(template: String, context: ExpansionContext = ExpansionContext()): ExpandedText {
        if (!template.contains('{') && !template.contains('}')) {
            return ExpandedText(template)
        }

        val out = StringBuilder(template.length)
        var cursorOffset: Int? = null
        var i = 0

        while (i < template.length) {
            val c = template[i]
            when {
                // Doubled braces are literals, so JSON and code snippets survive.
                c == '{' && i + 1 < template.length && template[i + 1] == '{' -> {
                    out.append('{')
                    i += 2
                }
                c == '}' && i + 1 < template.length && template[i + 1] == '}' -> {
                    out.append('}')
                    i += 2
                }
                c == '{' -> {
                    val close = template.indexOf('}', i + 1)
                    val raw = if (close == -1) null else template.substring(i + 1, close)
                    val resolved = raw?.let { resolve(it, context) }
                    when {
                        // Unclosed or unknown: emit verbatim, never drop the user's text.
                        raw == null || resolved == null -> {
                            out.append(c)
                            i++
                        }
                        resolved === CURSOR -> {
                            if (cursorOffset == null) cursorOffset = out.length
                            i = close + 1
                        }
                        else -> {
                            out.append(resolved)
                            i = close + 1
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }

        return ExpandedText(out.toString(), cursorOffset)
    }

    /** Sentinel distinguishing "caret marker" from "expanded to empty string". */
    private val CURSOR = String("\u0000cursor".toCharArray())

    /** Returns the replacement, [CURSOR] for a caret marker, or null when unknown. */
    private fun resolve(inner: String, context: ExpansionContext): String? {
        if (inner.isEmpty()) return null
        val separator = inner.indexOf(':')
        val spec = if (separator == -1) inner else inner.substring(0, separator)
        val pattern = if (separator == -1) null else inner.substring(separator + 1)

        val match = SPEC_REGEX.matchEntire(spec) ?: return null
        val name = match.groupValues[1].lowercase()
        val sign = match.groupValues[2]
        val amount = match.groupValues[3]
        val unit = match.groupValues[4]

        return when (name) {
            "cursor" -> if (pattern == null && sign.isEmpty()) CURSOR else null
            "clipboard" -> if (pattern == null && sign.isEmpty()) context.clipboard.orEmpty() else null
            "date" -> formatMoment(DEFAULT_DATE_PATTERN, pattern, sign, amount, unit, context)
            "time" -> formatMoment(DEFAULT_TIME_PATTERN, pattern, sign, amount, unit, context)
            "datetime" -> formatMoment(DEFAULT_DATETIME_PATTERN, pattern, sign, amount, unit, context)
            else -> null
        }
    }

    private fun formatMoment(
        defaultPattern: String,
        requestedPattern: String?,
        sign: String,
        amount: String,
        unit: String,
        context: ExpansionContext
    ): String? {
        // An empty pattern ("{date:}") is a typo, not a request for no output.
        if (requestedPattern != null && requestedPattern.isEmpty()) return null

        val calendar = Calendar.getInstance(context.timeZone, context.locale)
        calendar.timeInMillis = context.now
        if (sign.isNotEmpty()) {
            val magnitude = amount.toIntOrNull() ?: return null
            val delta = if (sign == "-") -magnitude else magnitude
            val field = when (unit) {
                "min" -> Calendar.MINUTE
                "h" -> Calendar.HOUR_OF_DAY
                "d" -> Calendar.DAY_OF_MONTH
                "w" -> Calendar.WEEK_OF_YEAR
                "mo" -> Calendar.MONTH
                "y" -> Calendar.YEAR
                else -> return null
            }
            calendar.add(field, delta)
        }

        // An invalid pattern must not take the whole snippet down with it.
        return runCatching {
            SimpleDateFormat(requestedPattern ?: defaultPattern, context.locale)
                .apply { timeZone = context.timeZone }
                .format(Date(calendar.timeInMillis))
        }.getOrNull()
    }
}
