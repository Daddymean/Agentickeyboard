package io.github.daddymean.agentickeyboard.util

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * LAB-007: offline "quick answers" for the suggestion strip.
 *
 * - **Math:** the word being typed is an arithmetic expression ending in "=",
 *   e.g. `12*7=`, `(3+4)x2=`, `15%*80=`, `2^10=`. The chip completes it in place: `12*7=84`.
 * - **Units:** the last two words are a number and a unit, e.g. `5 km`, `72 F`,
 *   `3.5 lb`, `60mph`. The chip appends the conversion: `km (≈3.11 mi)`.
 * - **Dates:** explicit relative date phrases like `this friday`, `next monday`,
 *   `last thursday`, `in 3 days`, `in 2 weeks`, or `3 days ago`. The chip appends
 *   the resolved calendar date: `friday (Dec 4)` or `days (Mon, Nov 30)`.
 *
 * Pure and offline: nothing is stored, sent or learned. The keyboard shows the
 * strip only outside sensitive fields, and a pick is committed as inserted text
 * (an unbound chip), so the twin never counts an answer as typed.
 */
object QuickAnswers {

    /** [label] is what the chip shows; [replacement] replaces the word being typed. */
    data class Answer(val label: String, val replacement: String, val kind: Kind)

    enum class Kind { MATH, UNIT, DATE }

    private const val MAX_CHARS = 64

    fun answer(text: String): Answer? = answer(text, LocalDate.now())

    fun answer(text: String, today: LocalDate): Answer? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val word = text.takeLastWhile { !it.isWhitespace() }
        return math(word) ?: unit(text, word) ?: relativeDate(text, word, today)
    }

    // --- math ------------------------------------------------------------------------

    private fun math(word: String): Answer? {
        if (word.length !in 4..MAX_CHARS || !word.endsWith("=")) return null
        val expr = word.dropLast(1)
        if (expr.none { it in OPERATORS } || expr.none { it.isDigit() }) return null
        if (expr.any { !(it.isDigit() || it in OPERATORS || it in "().,") }) return null
        val value = Parser(expr.replace(",", "")).parse() ?: return null
        val shown = format(value) ?: return null
        return Answer(label = "= $shown", replacement = "$word$shown", kind = Kind.MATH)
    }

    private const val OPERATORS = "+-*/x×÷^%"

    /** Recursive descent over + - * / ^ %, unary minus and parentheses, in BigDecimal. */
    private class Parser(val s: String) {
        var i = 0
        val mc = MathContext(34, RoundingMode.HALF_EVEN)

        fun parse(): BigDecimal? = runCatching {
            val v = sum()
            if (i != s.length) null else v
        }.getOrNull()

        fun sum(): BigDecimal {
            var v = product()
            while (i < s.length && s[i] in "+-") {
                val op = s[i++]
                val r = product()
                v = if (op == '+') v.add(r, mc) else v.subtract(r, mc)
            }
            return v
        }

        fun product(): BigDecimal {
            var v = power()
            while (i < s.length && s[i] in "*/x×÷") {
                val op = s[i++]
                val r = power()
                v = if (op == '/' || op == '÷') {
                    require(r.signum() != 0)
                    v.divide(r, mc)
                } else v.multiply(r, mc)
            }
            return v
        }

        fun power(): BigDecimal {
            val base = unary()
            if (i < s.length && s[i] == '^') {
                i++
                val exp = power()
                val n = exp.toInt()
                require(exp.compareTo(BigDecimal(n)) == 0 && n in 0..64)
                return base.pow(n, mc)
            }
            return base
        }

        fun unary(): BigDecimal {
            if (i < s.length && s[i] == '-') { i++; return unary().negate() }
            return percent()
        }

        fun percent(): BigDecimal {
            var v = atom()
            while (i < s.length && s[i] == '%') { i++; v = v.divide(BigDecimal(100), mc) }
            return v
        }

        fun atom(): BigDecimal {
            require(i < s.length)
            if (s[i] == '(') {
                i++
                val v = sum()
                require(i < s.length && s[i] == ')')
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start)
            return BigDecimal(s.substring(start, i))
        }
    }

    // --- units -----------------------------------------------------------------------

    private data class Conversion(val target: String, val convert: (Double) -> Double)

    private val UNITS: Map<String, Conversion> = buildMap {
        fun linear(names: List<String>, target: String, factor: Double) =
            names.forEach { put(it, Conversion(target) { v -> v * factor }) }
        linear(listOf("km", "kms", "kilometer", "kilometers", "kilometre", "kilometres"), "mi", 0.621371)
        // Not "mi": it flashes a chip on the way to "5 min".
        linear(listOf("mile", "miles"), "km", 1.609344)
        linear(listOf("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms"), "lb", 2.20462)
        linear(listOf("lb", "lbs", "pound", "pounds"), "kg", 0.453592)
        linear(listOf("cm", "centimeter", "centimeters"), "in", 0.393701)
        linear(listOf("in", "inch", "inches"), "cm", 2.54)
        linear(listOf("m", "meter", "meters", "metre", "metres"), "ft", 3.28084)
        linear(listOf("ft", "foot", "feet"), "m", 0.3048)
        linear(listOf("l", "liter", "liters", "litre", "litres"), "gal", 0.264172)
        linear(listOf("gal", "gallon", "gallons"), "L", 3.78541)
        linear(listOf("mph"), "km/h", 1.609344)
        linear(listOf("kph", "km/h", "kmh"), "mph", 0.621371)
        linear(listOf("oz", "ounce", "ounces"), "g", 28.3495)
        linear(listOf("g", "gram", "grams"), "oz", 0.035274)
        put("f", Conversion("°C") { v -> (v - 32) * 5 / 9 })
        put("°f", Conversion("°C") { v -> (v - 32) * 5 / 9 })
        put("fahrenheit", Conversion("°C") { v -> (v - 32) * 5 / 9 })
        put("c", Conversion("°F") { v -> v * 9 / 5 + 32 })
        put("°c", Conversion("°F") { v -> v * 9 / 5 + 32 })
        put("celsius", Conversion("°F") { v -> v * 9 / 5 + 32 })
    }

    private val NUMBER_THEN_UNIT = Regex("""^(-?\d+(?:[.,]\d+)?)([a-zA-Z°/]+)$""")
    private val TEMPERATURE_LETTERS = setOf("f", "c")
    private val NUMBER = Regex("""^-?\d+(?:[.,]\d+)?$""")

    private fun unit(text: String, word: String): Answer? {
        if (word.length > MAX_CHARS) return null
        // "60mph" (one word) or "5 km" (number word, then unit word).
        val glued = NUMBER_THEN_UNIT.find(word)
        val (numberText, unitText) = if (glued != null) {
            glued.groupValues[1] to glued.groupValues[2]
        } else {
            val before = text.dropLast(word.length).trimEnd()
            if (before.length == text.length - word.length) return null // no space before the unit
            val previous = before.takeLastWhile { !it.isWhitespace() }
            if (!NUMBER.matches(previous)) return null
            previous to word
        }
        val key = unitText.lowercase()
        // One-letter units: only temperatures, and only glued to the number ("72f",
        // "20c"). "5m"/"5g" in texts usually mean minutes, millions or a network.
        if (key.length == 1 && (glued == null || key !in TEMPERATURE_LETTERS)) return null
        val conversion = UNITS[key] ?: return null
        val value = numberText.replace(',', '.').toDoubleOrNull() ?: return null
        val shown = format(BigDecimal(conversion.convert(value)).round(MathContext(3, RoundingMode.HALF_EVEN))) ?: return null
        val approx = "≈$shown ${conversion.target}"
        return Answer(label = approx, replacement = "$word ($approx)", kind = Kind.UNIT)
    }

    // --- relative dates --------------------------------------------------------------

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )
    private val DATE_UNITS = setOf("day", "days", "week", "weeks", "month", "months")
    private val DATE_TRIGGERS = WEEKDAYS.keys + DATE_UNITS + "ago"

    private val FMT_SAME_YEAR = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val FMT_OTHER_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val FMT_DOW_SAME_YEAR = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val FMT_DOW_OTHER_YEAR = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

    private fun relativeDate(text: String, word: String, today: LocalDate): Answer? {
        if (word.length > MAX_CHARS) return null
        val lower = word.lowercase(Locale.ROOT)
        if (lower !in DATE_TRIGGERS) return null

        val beforeWord = text.dropLast(word.length).trimEnd()
        if (beforeWord.length == text.length - word.length) return null // requires preceding word
        val prev1Raw = beforeWord.takeLastWhile { !it.isWhitespace() }
        val prev1 = prev1Raw.lowercase(Locale.ROOT)

        // 1. "this <weekday>", "next <weekday>", "last <weekday>"
        val targetDow = WEEKDAYS[lower]
        if (targetDow != null) {
            val todayDow = today.dayOfWeek.value
            val dow = targetDow.value
            val resolved = when (prev1) {
                "this" -> {
                    val diff = (dow - todayDow + 7) % 7
                    today.plusDays(diff.toLong())
                }
                "next" -> {
                    // Calendar-week semantics: "next <weekday>" resolves to that weekday
                    // in the following Mon–Sun week (so on Wed, "next monday" is +5d and
                    // "next friday" is +9d).
                    val offset = if (dow > todayDow) (dow - todayDow + 7) else {
                        val until = (dow - todayDow + 7) % 7
                        if (until == 0) 7 else until
                    }
                    today.plusDays(offset.toLong())
                }
                "last" -> {
                    val back = (todayDow - dow + 7) % 7
                    today.minusDays((if (back == 0) 7 else back).toLong())
                }
                else -> return null
            }
            val formatted = formatDate(resolved, today, includeWeekday = false)
            return Answer(label = "📅 $formatted", replacement = "$word ($formatted)", kind = Kind.DATE)
        }

        // Look back one more token for 3-word phrases: "in <N> <unit>" or "<N> <unit> ago".
        val beforePrev1 = beforeWord.dropLast(prev1Raw.length).trimEnd()
        if (beforePrev1.length == beforeWord.length - prev1Raw.length) return null
        val prev2 = beforePrev1.takeLastWhile { !it.isWhitespace() }.lowercase(Locale.ROOT)

        // 2. "in <N> day(s)/week(s)/month(s)"
        if (lower in DATE_UNITS && prev2 == "in") {
            val resolved = offsetDate(today, prev1, lower, forward = true) ?: return null
            val formatted = formatDate(resolved, today, includeWeekday = true)
            return Answer(label = "📅 $formatted", replacement = "$word ($formatted)", kind = Kind.DATE)
        }

        // 3. "<N> day(s)/week(s)/month(s) ago"
        if (lower == "ago" && prev1 in DATE_UNITS) {
            val resolved = offsetDate(today, prev2, prev1, forward = false) ?: return null
            val formatted = formatDate(resolved, today, includeWeekday = true)
            return Answer(label = "📅 $formatted", replacement = "$word ($formatted)", kind = Kind.DATE)
        }

        return null
    }

    private fun offsetDate(today: LocalDate, countText: String, unit: String, forward: Boolean): LocalDate? {
        if (countText.isEmpty() || countText.any { !it.isDigit() }) return null
        val n = countText.toLongOrNull() ?: return null
        return when (unit) {
            "day", "days" -> if (n in 1..365) (if (forward) today.plusDays(n) else today.minusDays(n)) else null
            "week", "weeks" -> if (n in 1..52) (if (forward) today.plusWeeks(n) else today.minusWeeks(n)) else null
            "month", "months" -> if (n in 1..24) (if (forward) today.plusMonths(n) else today.minusMonths(n)) else null
            else -> null
        }
    }

    private fun formatDate(date: LocalDate, today: LocalDate, includeWeekday: Boolean): String {
        val sameYear = date.year == today.year
        val fmt = when {
            includeWeekday && sameYear -> FMT_DOW_SAME_YEAR
            includeWeekday -> FMT_DOW_OTHER_YEAR
            sameYear -> FMT_SAME_YEAR
            else -> FMT_OTHER_YEAR
        }
        return fmt.format(date)
    }

    // --- formatting --------------------------------------------------------------------

    /** Up to 10 significant digits, no exponent, no trailing zeros; null when absurd. */
    private fun format(value: BigDecimal): String? {
        val rounded = value.round(MathContext(10, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        if (rounded.abs() >= BigDecimal("1e15") || (rounded.signum() != 0 && rounded.abs() < BigDecimal("1e-6"))) return null
        val plain = rounded.toPlainString()
        return if (plain == "-0") "0" else plain
    }
}
