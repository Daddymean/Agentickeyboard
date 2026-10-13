package io.github.daddymean.agentickeyboard.twin.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KEYBOARD-025: the shipped instruments must match the publishers' keys.
 * - IPIP-NEO-120 fixture: the "Short#" items of IPIP-NEO-ItemKey.xls from
 *   osf.io/tbmh5 (Johnson's IPIP-NEO data repository, https://osf.io/download/yk3fd/).
 * - MFQ-2 fixture: the English questionnaire in osf.io/srtxn
 *   (https://osf.io/download/6bfe9/); foundations repeat Care, Equality,
 *   Proportionality, Loyalty, Authority, Purity in item order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstrumentKeysTest {
    private val json = JournalAssets.asset("instruments.json")
    private val instruments = Instruments.parse(json).associateBy { it.id }
    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").replace('\u2019', '\'').trim()

    @Test
    fun ipipNeo120MatchesTheOfficialKey() {
        val neo = instruments.getValue("ipip_neo_120")
        val official = JournalAssets.fixture("ipip-neo-120-official-key.csv")
        assertEquals(120, official.size)
        assertEquals(120, neo.items.size)
        for (row in official) {
            val (n, sign, key, facet, text) = row
            val item = neo.items.single { it.n == n.toInt() }
            assertEquals("item $n sign", sign == "-", item.reverse)
            assertEquals("item $n domain", "(${key.take(1)})", item.scales[0].substringAfterLast(" "))
            assertEquals("item $n facet", "${key.take(1)}: $facet", item.scales[1])
            assertEquals("item $n text", norm(text), norm(item.text))
        }
        // 30 facets of 4 items, 5 domains of 24.
        val score = Instruments.score(neo, emptyMap())
        assertEquals(35, score.size)
        assertEquals(listOf(24), score.filter { !it.scale.contains(":") }.map { it.total }.distinct())
        assertEquals(listOf(4), score.filter { it.scale.contains(":") }.map { it.total }.distinct())
    }

    @Test
    fun mfq2MatchesTheOfficialQuestionnaire() {
        val mfq = instruments.getValue("mfq2")
        val official = JournalAssets.fixture("mfq2-official.csv")
        assertEquals(36, official.size)
        val order = listOf("Care", "Equality", "Proportionality", "Loyalty", "Authority", "Purity")
        for ((n, foundation, text) in official) {
            val item = mfq.items.single { it.n == n.toInt() }
            assertEquals("item $n", order[(n.toInt() - 1) % 6], foundation)
            assertEquals("item $n foundation", listOf(foundation), item.scales)
            assertFalse("MFQ-2 has no reverse keys", item.reverse)
            assertEquals("item $n text", norm(text), norm(item.text))
        }
        assertEquals(5, mfq.points)
        assertTrue(json.contains("osf.io/srtxn") && json.contains("osf.io/tbmh5"))
    }

    @Test
    fun otherIpipInstrumentsAreComplete() {
        val bfm = instruments.getValue("ipip_bfm_50")
        assertEquals(50, bfm.items.size)
        assertEquals(5, Instruments.score(bfm, emptyMap()).size)
        assertTrue(Instruments.score(bfm, emptyMap()).all { it.total == 10 })
        val hexaco = instruments.getValue("ipip_hexaco")
        assertEquals(6, Instruments.score(hexaco, emptyMap()).count { it.scale.startsWith("HEXACO") })
        assertEquals(24, Instruments.score(hexaco, emptyMap()).count { !it.scale.startsWith("HEXACO") })
        assertEquals(24, Instruments.score(instruments.getValue("ipip_via"), emptyMap()).size)
        assertTrue(listOf(bfm, hexaco).all { it.points == 5 })
        assertEquals(21, Instruments.valueCards(json).size)
    }

    @Test
    fun reverseKeyedItemsAreFlipped() {
        val bfm = instruments.getValue("ipip_bfm_50")
        val plus = bfm.items.first { !it.reverse }
        val minus = bfm.items.first { it.reverse }
        assertEquals(5, Instruments.itemScore(plus, 5, 5))
        assertEquals(1, Instruments.itemScore(minus, 5, 5))
        assertEquals(4, Instruments.itemScore(minus, 2, 5))
        // Agreeing with every item scores 3.0 on a scale with equal + and - items...
        val all5 = bfm.items.associate { it.n to 5 }
        for (s in Instruments.score(bfm, all5)) {
            val items = bfm.items.filter { s.scale in it.scales }
            val expected = items.sumOf { if (it.reverse) 1 else 5 }.toDouble() / items.size
            assertEquals(s.scale, expected, s.mean, 1e-9)
            assertTrue(s.complete)
        }
        // ...and partial answers average only what was answered.
        val partial = Instruments.score(bfm, mapOf(plus.n to 4)).single { it.scale == plus.scales[0] }
        assertEquals(4.0, partial.mean, 1e-9)
        assertEquals(1, partial.answered)
        assertTrue(Instruments.score(bfm, emptyMap()).all { it.mean.isNaN() })
    }

    @Test
    fun ecrRsScoresAvoidanceAndAnxiety() {
        val ecr = instruments.values.filter { it.id.startsWith("ecr_rs:") }
        assertEquals(5, ecr.size)
        assertTrue(ecr.any { it.id == "ecr_rs:general" })
        for (i in ecr) {
            assertEquals(7, i.points)
            assertEquals(9, i.items.size)
            assertEquals(listOf(1, 2, 3, 4), i.items.filter { it.reverse }.map { it.n })
            assertEquals((1..6).toList(), i.items.filter { it.scales == listOf("Avoidance") }.map { it.n })
            assertEquals(listOf(7, 8, 9), i.items.filter { it.scales == listOf("Anxiety") }.map { it.n })
        }
        // Fully secure answers: agree with 1-4, disagree with 5-9 -> avoidance 1, anxiety 1.
        val secure = (1..9).associateWith { if (it <= 4) 7 else 1 }
        val s = Instruments.score(ecr.first(), secure).associate { it.scale to it.mean }
        assertEquals(1.0, s.getValue("Avoidance"), 1e-9)
        assertEquals(1.0, s.getValue("Anxiety"), 1e-9)
        val anxious = (1..9).associateWith { if (it <= 4) 4 else 7 }
        val a = Instruments.score(ecr.first(), anxious).associate { it.scale to it.mean }
        assertEquals((4 * 4 + 2 * 7) / 6.0, a.getValue("Avoidance"), 1e-9)
        assertEquals(7.0, a.getValue("Anxiety"), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangeResponsesAreRejected() {
        val bfm = instruments.getValue("ipip_bfm_50")
        Instruments.itemScore(bfm.items.first(), 6, 5)
    }
}
