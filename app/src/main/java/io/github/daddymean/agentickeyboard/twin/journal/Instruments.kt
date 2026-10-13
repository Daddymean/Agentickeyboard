package io.github.daddymean.agentickeyboard.twin.journal

import org.json.JSONObject

/**
 * KEYBOARD-025: the personality and values instruments in `assets/twin/instruments.json`,
 * and their scoring.
 *
 * Keys and their sources:
 * - **IPIP-NEO-120** (Johnson, 2014): signs, domains and facets verified 2026-10-11 against
 *   Johnson's official key, OSF osf.io/tbmh5, component "Scoring Key for the IPIP-NEO-300
 *   and IPIP-NEO-120" (osf.io/ycvdk), file IPIP-NEO-ItemKey.xls
 *   (https://osf.io/download/yk3fd/). Items 28, 29 and 62 reworded to the official text.
 * - **MFQ-2** (Atari et al., 2023, JPSP 125(5), doi:10.1037/pspp0000470; CC BY 4.0):
 *   foundations verified against the official English questionnaire in OSF osf.io/srtxn
 *   (https://osf.io/download/6bfe9/); item 27 reworded to the official text. Mean of six
 *   items per foundation, no reverse keys.
 * - IPIP-50, IPIP-HEXACO and IPIP-VIA follow the IPIP keys cited in each instrument's
 *   `source`; ECR-RS follows Fraley et al. (2011): avoidance = mean of items 1–6 with
 *   1–4 reverse keyed; anxiety = mean of items 7–9.
 * `'+'` items score the response; `'-'` items score (points + 1 − response).
 */
data class InstrumentItem(val n: Int, val text: String, val scales: List<String>, val reverse: Boolean)

data class Instrument(
    val id: String,
    val name: String,
    val source: String,
    val license: String,
    val labels: List<String>,
    val items: List<InstrumentItem>
) {
    val points: Int get() = labels.size
}

data class ScaleScore(val scale: String, val mean: Double, val answered: Int, val total: Int) {
    val complete: Boolean get() = answered == total
}

/** The Values Card Sort is a sort, not a questionnaire. */
data class ValueCard(val value: String, val text: String)

object Instruments {
    private val domainNames = mapOf(
        "N" to "Neuroticism", "E" to "Extraversion", "O" to "Openness", "A" to "Agreeableness",
        "C" to "Conscientiousness", "ES" to "Emotional Stability", "H" to "Honesty-Humility",
        "X" to "Extraversion (HEXACO)"
    )
    const val ECR_POINTS = 7
    private val ecrLabels = listOf(
        "Strongly disagree", "Disagree", "Slightly disagree", "Neutral", "Slightly agree", "Agree", "Strongly agree"
    )

    /** All Likert instruments; ECR-RS becomes one instrument per relationship target. */
    fun parse(json: String): List<Instrument> {
        val root = JSONObject(json)
        val scales = root.getJSONObject("response_scales")
        val ipip = scales.getJSONArray("ipip").strings()
        val mfq = scales.getJSONArray("mfq2").strings()
        val list = root.getJSONArray("instruments")
        val out = ArrayList<Instrument>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val id = o.getString("id")
            val name = o.getString("name")
            val source = o.optString("source")
            val license = o.optString("license")
            when (id) {
                "ipip_bfm_50" -> out += Instrument(id, name, source, license, ipip, o.getJSONArray("items").let { a ->
                    (0 until a.length()).map { k ->
                        val it = a.getJSONObject(k)
                        InstrumentItem(it.getInt("n"), it.getString("text"), listOf(domain(it.getString("scale"))), it.getString("key") == "-")
                    }
                })
                "ipip_neo_120" -> out += Instrument(id, name, source, license, ipip, o.getJSONArray("items").let { a ->
                    (0 until a.length()).map { k ->
                        val it = a.getJSONObject(k)
                        InstrumentItem(
                            it.getInt("n"), it.getString("text"),
                            listOf(domain(it.getString("domain")), "${it.getString("domain")}: ${it.getString("facet")}"),
                            it.getString("key") == "-"
                        )
                    }
                })
                "ipip_hexaco" -> {
                    var n = 0
                    val items = ArrayList<InstrumentItem>()
                    val facets = o.getJSONArray("facets")
                    for (f in 0 until facets.length()) {
                        val facet = facets.getJSONObject(f)
                        val its = facet.getJSONArray("items")
                        for (k in 0 until its.length()) {
                            val it = its.getJSONObject(k)
                            items += InstrumentItem(
                                ++n, it.getString("text"),
                                listOf("HEXACO ${facet.getString("domain")}", "${facet.getString("domain")}: ${facet.getString("facet")}"),
                                it.getString("key") == "-"
                            )
                        }
                    }
                    out += Instrument(id, name, source, license, ipip, items)
                }
                "ipip_via" -> {
                    var n = 0
                    val items = ArrayList<InstrumentItem>()
                    val sc = o.getJSONArray("scales")
                    for (s in 0 until sc.length()) {
                        val scale = sc.getJSONObject(s)
                        val its = scale.getJSONArray("items")
                        for (k in 0 until its.length()) {
                            val it = its.getJSONObject(k)
                            items += InstrumentItem(++n, it.getString("text"), listOf(scale.getString("scale")), it.getString("key") == "-")
                        }
                    }
                    out += Instrument(id, name, source, license, ipip, items)
                }
                "mfq2" -> out += Instrument(id, name, source, license, mfq, o.getJSONArray("items").let { a ->
                    (0 until a.length()).map { k ->
                        val it = a.getJSONObject(k)
                        InstrumentItem(it.getInt("n"), it.getString("text"), listOf(it.getString("foundation")), false)
                    }
                })
                "ecr_rs" -> {
                    val targets = o.getJSONArray("targets").strings()
                    val specific = o.getJSONArray("items_relationship_specific").strings()
                    val global = o.getJSONArray("items_global").strings()
                    targets.forEachIndexed { t, target ->
                        val texts = if (t == 0) global else specific
                        val key = target.substringBefore(" (").lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                        out += Instrument("ecr_rs:$key", "$name: $target", source, license, ecrLabels,
                            texts.mapIndexed { k, text ->
                                val n = k + 1
                                InstrumentItem(n, text, listOf(if (n <= 6) "Avoidance" else "Anxiety"), n <= 4)
                            })
                    }
                }
            }
        }
        return out
    }

    fun valueCards(json: String): List<ValueCard> {
        val list = JSONObject(json).getJSONArray("instruments")
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.getString("id") == "values_card_sort") {
                val cards = o.getJSONArray("cards")
                return (0 until cards.length()).map { ValueCard(cards.getJSONObject(it).getString("value"), cards.getJSONObject(it).getString("text")) }
            }
        }
        return emptyList()
    }

    private fun domain(code: String) = domainNames[code]?.let { "$it ($code)" } ?: code

    /** Item score: the response for '+' items, (points + 1 − response) for '-' items. */
    fun itemScore(item: InstrumentItem, response: Int, points: Int): Int {
        require(response in 1..points) { "response out of range" }
        return if (item.reverse) points + 1 - response else response
    }

    /** Mean item score per scale, over the items answered so far (in scale order). */
    fun score(instrument: Instrument, responses: Map<Int, Int>): List<ScaleScore> {
        val sums = LinkedHashMap<String, Pair<Int, Int>>()
        val totals = LinkedHashMap<String, Int>()
        for (item in instrument.items) for (scale in item.scales) {
            totals[scale] = (totals[scale] ?: 0) + 1
            val r = responses[item.n] ?: continue
            val (s, c) = sums[scale] ?: (0 to 0)
            sums[scale] = (s + itemScore(item, r, instrument.points)) to (c + 1)
        }
        return totals.map { (scale, total) ->
            val (s, c) = sums[scale] ?: (0 to 0)
            ScaleScore(scale, if (c == 0) Double.NaN else s.toDouble() / c, c, total)
        }
    }
}
