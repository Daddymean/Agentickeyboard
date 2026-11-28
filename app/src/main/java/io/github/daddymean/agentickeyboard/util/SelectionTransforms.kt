package io.github.daddymean.agentickeyboard.util

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * LAB-013: flick up to transform a selection, fully on the device.
 *
 * After a smart-select hold (LAB-011), flicking the thumb up offers chips that
 * rewrite the selection in place: units (12.5 km -> 7.8 mi), time zones
 * (3pm PT -> 6:00 PM ET in the phone's zone), arithmetic (12*4.5+3 -> 57),
 * comma lists to bullets, sorted lines, wrapping in quotes or brackets, and case.
 * Deterministic, no model, no network, no storage; the chips are computed once,
 * when the flick happens, so typing pays nothing.
 */

data class SelectionTransform(val label: String, val output: String)

object SelectionTransforms {

    const val MAX_CHARS = 2_000
    const val MAX_CHIPS = 6

    fun suggest(
        selected: String,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = Locale.getDefault()
    ): List<SelectionTransform> {
        val text = selected.trim()
        if (text.isEmpty() || selected.length > MAX_CHARS) return emptyList()
        val out = ArrayList<SelectionTransform>()
        val redacted = CloudTextSanitizer.sanitize(text)
        if (redacted.changed) out += SelectionTransform("🛡️ Redact", redacted.text)
        convertUnit(text)?.let { out += it }
        out += convertTime(text, zone, today)
        evaluate(text)?.let { out += SelectionTransform("= $it", it) }
        bulletList(text)?.let { out += SelectionTransform("• List", it) }
        sortLines(text)?.let { out += SelectionTransform("Sort A–Z", it) }
        out += SelectionTransform("“Quote”", "“$text”")
        out += SelectionTransform("(Wrap)", "($text)")
        caseVariants(text, locale).forEach { out += it }
        return out.distinctBy { it.output }.filter { it.output != text }.take(MAX_CHIPS)
    }

    // ---- units ---------------------------------------------------------------

    private data class Conversion(val to: String, val f: (Double) -> Double)

    private val units: Map<String, Conversion> = mapOf(
        "km" to Conversion("mi") { it * 0.621371 },
        "mi" to Conversion("km") { it / 0.621371 },
        "mile" to Conversion("km") { it / 0.621371 },
        "miles" to Conversion("km") { it / 0.621371 },
        "m" to Conversion("ft") { it * 3.28084 },
        "ft" to Conversion("m") { it / 3.28084 },
        "cm" to Conversion("in") { it / 2.54 },
        "in" to Conversion("cm") { it * 2.54 },
        "kg" to Conversion("lb") { it * 2.20462 },
        "lb" to Conversion("kg") { it / 2.20462 },
        "lbs" to Conversion("kg") { it / 2.20462 },
        "g" to Conversion("oz") { it / 28.3495 },
        "oz" to Conversion("g") { it * 28.3495 },
        "l" to Conversion("gal") { it / 3.78541 },
        "gal" to Conversion("L") { it * 3.78541 },
        "ml" to Conversion("fl oz") { it / 29.5735 },
        "km/h" to Conversion("mph") { it * 0.621371 },
        "mph" to Conversion("km/h") { it / 0.621371 },
        "°c" to Conversion("°F") { it * 9 / 5 + 32 },
        "°f" to Conversion("°C") { (it - 32) * 5 / 9 },
    )

    private val quantity = Regex("""^(-?\d[\d,]*(?:\.\d+)?)\s?(km/h|°[cCfF]|[A-Za-z]+)$""")

    fun convertUnit(text: String): SelectionTransform? {
        val m = quantity.matchEntire(text) ?: return null
        val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val unit = m.groupValues[2].lowercase(Locale.ROOT)
        val conv = units[unit] ?: return null
        val result = "${format(conv.f(value))} ${conv.to}".replace(" °", "°")
        return SelectionTransform("→ $result", result)
    }

    // ---- time zones ----------------------------------------------------------

    private val zoneAliases: Map<String, String> = mapOf(
        "PT" to "America/Los_Angeles", "PST" to "America/Los_Angeles", "PDT" to "America/Los_Angeles",
        "MT" to "America/Denver", "MST" to "America/Denver", "MDT" to "America/Denver",
        "CT" to "America/Chicago", "CST" to "America/Chicago", "CDT" to "America/Chicago",
        "ET" to "America/New_York", "EST" to "America/New_York", "EDT" to "America/New_York",
        "UTC" to "UTC", "GMT" to "UTC", "BST" to "Europe/London", "CET" to "Europe/Paris",
        "IST" to "Asia/Kolkata", "JST" to "Asia/Tokyo", "AEST" to "Australia/Sydney"
    )

    private val timeWithZone = Regex("""(?i)^(\d{1,2})(?::(\d{2}))?\s?(am|pm)?\s?([A-Z]{2,4})$""")
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** "3pm PT" -> the same instant in the phone's zone (and UTC when the phone is already there). */
    fun convertTime(text: String, zone: ZoneId, today: LocalDate): List<SelectionTransform> {
        val m = timeWithZone.matchEntire(text) ?: return emptyList()
        val abbr = m.groupValues[4].uppercase(Locale.ROOT)
        val source = zoneAliases[abbr]?.let { ZoneId.of(it) } ?: return emptyList()
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3].lowercase(Locale.ROOT)
        if (ampm.isEmpty() && m.groupValues[2].isEmpty()) return emptyList() // "12 CT" is too ambiguous
        if (hour > 23 || minute > 59 || (ampm.isNotEmpty() && hour !in 1..12)) return emptyList()
        if (ampm == "pm" && hour != 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        val at = ZonedDateTime.of(today, LocalTime.of(hour, minute), source)
        val targets = if (source.rules == zone.rules || zone.normalized() == source.normalized())
            listOf(ZoneId.of("America/New_York"), ZoneId.of("UTC")).filter { it.rules != source.rules }
        else listOf(zone)
        return targets.map { target ->
            val local = at.withZoneSameInstant(target)
            val dayShift = local.toLocalDate().compareTo(today).let { if (it > 0) " (+1d)" else if (it < 0) " (−1d)" else "" }
            val result = "${local.format(clock)} ${shortName(target, local)}$dayShift"
            SelectionTransform("→ $result", result)
        }
    }

    private fun shortName(zone: ZoneId, at: ZonedDateTime): String {
        val id = zone.id
        return when (id) {
            "America/Los_Angeles" -> "PT"; "America/Denver" -> "MT"; "America/Chicago" -> "CT"
            "America/New_York" -> "ET"; "UTC", "Etc/UTC", "Z" -> "UTC"
            else -> at.format(DateTimeFormatter.ofPattern("zzz", Locale.US))
        }
    }

    // ---- arithmetic ----------------------------------------------------------

    private val mathChars = Regex("""^[\d\s.,+\-*/×÷x()%^]+$""")

    /** Evaluates + − × ÷ ^ and parentheses when the selection is clearly a sum. */
    fun evaluate(text: String): String? {
        if (!mathChars.matches(text) || text.none { it in "+-*/×÷x^%" } || text.count { it.isDigit() } < 2) return null
        val expr = text.replace("×", "*").replace("÷", "/").replace(Regex("""(?<=\d)\s*x\s*(?=\d)"""), "*").replace(",", "")
        if (Regex("""^-?\d+(?:\.\d+)?$""").matches(expr.trim())) return null
        return try {
            val value = Parser(expr).parse()
            if (value.isNaN() || value.isInfinite()) null else format(value)
        } catch (_: IllegalArgumentException) { null }
    }

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = sum(); skip(); require(i == s.length) { "trailing" }; return v }
        private fun skip() { while (i < s.length && s[i] == ' ') i++ }
        private fun sum(): Double {
            var v = product()
            while (true) { skip(); if (i >= s.length) return v
                when (s[i]) { '+' -> { i++; v += product() }; '-' -> { i++; v -= product() }; else -> return v } }
        }
        private fun product(): Double {
            var v = power()
            while (true) { skip(); if (i >= s.length) return v
                when (s[i]) { '*' -> { i++; v *= power() }; '/' -> { i++; v /= power() }
                    '%' -> { i++; v /= 100 }; else -> return v } }
        }
        private fun power(): Double {
            val b = unary(); skip()
            return if (i < s.length && s[i] == '^') { i++; Math.pow(b, power()) } else b
        }
        private fun unary(): Double { skip(); if (i < s.length && s[i] == '-') { i++; return -unary() }; return atom() }
        private fun atom(): Double {
            skip()
            require(i < s.length) { "end" }
            if (s[i] == '(') { i++; val v = sum(); skip(); require(i < s.length && s[i] == ')') { ")" }; i++; return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            require(i > start) { "number" }
            return s.substring(start, i).toDouble()
        }
    }

    // ---- lists, lines, wrapping, case -----------------------------------------

    fun bulletList(text: String): String? {
        if ('\n' in text) return null
        val parts = text.split(Regex("""\s*,\s*(?:and\s+|or\s+)?|\s+and\s+(?=[^,]*$)""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3 || parts.any { it.length > 60 }) return null
        return parts.joinToString("\n") { "• $it" }
    }

    fun sortLines(text: String): String? {
        val lines = text.lines()
        if (lines.size < 2) return null
        val sorted = lines.sortedWith(String.CASE_INSENSITIVE_ORDER)
        return if (sorted == lines) null else sorted.joinToString("\n")
    }

    fun caseVariants(text: String, locale: Locale): List<SelectionTransform> {
        if (text.none { it.isLetter() }) return emptyList()
        val title = text.split(' ').joinToString(" ") { w -> w.lowercase(locale).replaceFirstChar { it.titlecase(locale) } }
        return listOf(
            SelectionTransform("Title Case", title),
            SelectionTransform("UPPER", text.uppercase(locale)),
            SelectionTransform("lower", text.lowercase(locale))
        )
    }

    private fun format(v: Double): String {
        val bd = BigDecimal(v).round(MathContext(6, RoundingMode.HALF_UP))
        val scaled = if (kotlin.math.abs(v) >= 100) bd.setScale(0, RoundingMode.HALF_UP)
        else if (kotlin.math.abs(v) >= 10) bd.setScale(1, RoundingMode.HALF_UP) else bd.setScale(2, RoundingMode.HALF_UP)
        return scaled.stripTrailingZeros().toPlainString()
    }
}
