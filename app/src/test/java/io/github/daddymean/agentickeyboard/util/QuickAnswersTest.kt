package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.QuickAnswers.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class QuickAnswersTest {

    private fun math(text: String) = QuickAnswers.answer(text)?.takeIf { it.kind == Kind.MATH }?.replacement
    private fun unit(text: String) = QuickAnswers.answer(text)?.takeIf { it.kind == Kind.UNIT }?.label

    @Test
    fun arithmetic() {
        assertEquals("12*7=84", math("12*7="))
        assertEquals("so (3+4)x2=14", "so " + math("so (3+4)x2=")!!)
        assertEquals("15%*80=12", math("15%*80="))
        assertEquals("2^10=1024", math("2^10="))
        assertEquals("10/4=2.5", math("10/4="))
        assertEquals("1/3=0.3333333333", math("1/3="))
        assertEquals("0.1+0.2=0.3", math("0.1+0.2="))
        assertEquals("-5+2=-3", math("-5+2="))
        assertEquals("1,200*3=3600", math("1,200*3="))
        assertEquals("= 84", QuickAnswers.answer("12×7=")!!.label)
    }

    @Test
    fun notMath() {
        for (t in listOf("2026=", "12*7", "a=b", "x=5", "1/0=", "(1+2=", "1++=", "2^99=", "==", "5*=", "hello=", "12*7= "))
            assertNull(t, math(t))
    }

    @Test
    fun units() {
        assertEquals("≈3.11 mi", unit("about 5 km"))
        assertEquals("≈8.05 km", unit("5 miles"))
        assertEquals("≈22.2 °C", unit("it's 72f"))
        assertEquals("≈68 °F", unit("20c"))
        assertEquals("≈1.59 kg", unit("3.5 lb"))
        assertEquals("≈96.6 km/h", unit("60mph"))
        assertNull(unit("1,83 m")) // "m" alone is never a unit
        assertEquals("km (≈3.11 mi)", QuickAnswers.answer("5 km")!!.replacement)
    }

    @Test
    fun notUnits() {
        for (t in listOf("5 m", "5m", "5g", "back in 5 min", "km", "five km", "5 km ", "5 mi", "I'm 5 c", "page 5 f", "5 kmx"))
            assertNull(t, unit(t))
    }

    /** No chip at any keystroke of ordinary messages (the real false-positive risk). */
    @Test
    fun ordinaryTyping_neverTriggers() {
        val messages = listOf(
            "On my way, be there in 5 min", "Running late, 10 mins out", "Can you grab 2 gallons of milk at the store?",
            "Meet at 7:30 at the cafe on 5th", "My number is 555-1234", "Score was 3-2 in the 2nd half!",
            "Lol I can't even", "Its 5 o'clock somewhere", "Got a 4.0 this semester", "Room 12b, floor 3",
            "Call me at 8 or 9 tonight", "I need 3 of those and 2 of these", "See you at 10am tmrw",
            "We're at 1/2 price today", "The 49ers won 24-21", "Love you x", "50/50 chance tbh"
        )
        // Real dictionary words too: every prefix of the first 2,000.
        val dictionary = listOf(File("src/main/res/raw/wordlist.txt"), File("app/src/main/res/raw/wordlist.txt"))
            .first { it.exists() }.readLines().take(2_000)
        val hits = mutableListOf<String>()
        for (m in messages + dictionary) for (n in 1..m.length) QuickAnswers.answer(m.take(n))?.let { hits += m.take(n) }
        // "2 gallons" legitimately converts while it is being typed.
        assertEquals(listOf("Can you grab 2 gal", "Can you grab 2 gallon", "Can you grab 2 gallons"), hits)
    }

    /**
     * LAB-007 measurement, box JVM: cost per keystroke (the answer() call runs for
     * every text change) and taps saved on sample tasks versus switching to a
     * calculator/converter app (counted as 6 taps: home, app, clear, the answer is
     * read and retyped digit by digit, back to the chat).
     */
    @Test
    fun benchmarkAndTapsSaved() {
        val inputs = (1..2_000).map { i ->
            when (i % 4) {
                0 -> "the quick brown fox ${i}"
                1 -> "running ${i % 9} km"
                2 -> "${i}*${i % 13}="
                else -> "see you at ${i % 12} tonight"
            }
        }.flatMap { s -> (1..s.length).map { s.take(it) } }
        repeat(3) { inputs.forEach { QuickAnswers.answer(it) } }
        val ns = LongArray(inputs.size)
        inputs.forEachIndexed { i, s -> val t = System.nanoTime(); QuickAnswers.answer(s); ns[i] = System.nanoTime() - t }
        ns.sort()
        fun us(p: Double) = "%.1f".format(ns[((ns.size - 1) * p).toInt()] / 1000.0)
        val tasks = listOf("37*12=" to "444", "1250/4=" to "312.5", "72f" to "22.2", "5 km" to "3.11", "3.5 lb" to "1.59")
        val manual = tasks.sumOf { (_, answer) -> 6 + answer.length }
        val chip = tasks.size // one chip tap each
        println("LAB-007 answer() per keystroke over ${inputs.size} prefixes (box JVM): p50 ${us(0.5)} µs, p99 ${us(0.99)} µs, max ${us(1.0)} µs")
        println("LAB-007 taps for ${tasks.size} sample answers: app switch + retype = $manual, chip = $chip")
        tasks.forEach { (input, answer) -> assert(QuickAnswers.answer(input)!!.label.contains(answer)) { input } }
    }

    @Test
    fun relativeDatesResolveDeterministically() {
        // Wednesday, Nov 25, 2026
        val wed = java.time.LocalDate.of(2026, 11, 25)
        fun date(text: String) = QuickAnswers.answer(text, wed)?.takeIf { it.kind == Kind.DATE }

        assertEquals("📅 Nov 27", date("see you this friday")!!.label)
        assertEquals("friday (Nov 27)", date("see you this friday")!!.replacement)
        assertEquals("Friday (Nov 27)", date("see you this Friday")!!.replacement)

        // Calendar-week semantics: on Wed Nov 25, "next monday" is Nov 30 and "next friday" is Dec 4.
        assertEquals("📅 Nov 30", date("let's meet next monday")!!.label)
        assertEquals("📅 Dec 2", date("next wednesday")!!.label)
        assertEquals("📅 Dec 4", date("next friday")!!.label)

        // Past weekdays
        assertEquals("📅 Nov 23", date("last monday")!!.label)
        assertEquals("📅 Nov 20", date("last friday")!!.label)
        assertEquals("📅 Nov 18", date("last wednesday")!!.label)

        // Forward and backward offsets
        assertEquals("📅 Sat, Nov 28", date("back in 3 days")!!.label)
        assertEquals("days (Sat, Nov 28)", date("back in 3 days")!!.replacement)
        assertEquals("📅 Wed, Dec 9", date("due in 2 weeks")!!.label)
        assertEquals("📅 Fri, Dec 25", date("in 1 month")!!.label)
        assertEquals("📅 Sun, Nov 22", date("sent 3 days ago")!!.label)
        assertEquals("ago (Sun, Nov 22)", date("sent 3 days ago")!!.replacement)
        assertEquals("📅 Wed, Nov 11", date("2 weeks ago")!!.label)

        // Year boundary includes year
        val dec28 = java.time.LocalDate.of(2026, 12, 28)
        assertEquals("📅 Jan 8, 2027", QuickAnswers.answer("next friday", dec28)!!.label)
        assertEquals("📅 Mon, Jan 4, 2027", QuickAnswers.answer("in 7 days", dec28)!!.label)

        // Rejects standalone words, trailing whitespace, and out-of-range offsets
        for (nonDate in listOf("friday", "today", "tomorrow", "next friday ", "in 0 days", "in 999 days", "in 5 min", "some days ago")) {
            assertNull(nonDate, date(nonDate))
        }
    }
}
