package io.github.daddymean.agentickeyboard.twin.judgment

/**
 * Round 2: weekly offline drift report. Compares recent twin replies with Keith's writing
 * ([DriftCheck]), then also checks whether replies contradict an approved [ValuesCard] or
 * Keith's own decision-journal "why" phrases. Rule-based only: no model, no network.
 */
object WeeklyDriftReport {
    const val INTERVAL_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val MIN_SAMPLES = DriftCheck.MIN_SAMPLES

    data class Finding(val kind: Kind, val detail: String) {
        enum class Kind { STYLE, VALUES, DECISION }
    }

    data class Report(
        val style: DriftCheck.Result?,
        val findings: List<Finding>,
        val ranAtMillis: Long,
        val twinSamples: Int,
        val keithSamples: Int,
    ) {
        val drifting: Boolean get() = findings.isNotEmpty() || (style?.drifting == true)
        fun summary(): String = buildString {
            append("Weekly twin drift report\n")
            append("Samples: $twinSamples twin replies, $keithSamples of Keith's writing.\n")
            if (!drifting) {
                append("No drift flagged this week.")
                return@buildString
            }
            style?.reasons?.forEach { append("- Style: $it\n") }
            findings.forEach { append("- ${it.kind.name.lowercase().replaceFirstChar(Char::titlecase)}: ${it.detail}\n") }
        }.trimEnd()
    }

    fun due(lastRunMillis: Long?, now: Long) =
        lastRunMillis == null || now - lastRunMillis >= INTERVAL_MILLIS

    /**
     * Builds a report. [style] is null when there aren't enough samples for [DriftCheck].
     * Values/decision findings only run when an approved card / journal whys exist.
     */
    fun run(
        twinReplies: List<String>,
        keithWriting: List<String>,
        values: ValuesCard?,
        journal: DecisionJournal,
        now: Long,
    ): Report {
        val style = DriftCheck.compare(twinReplies, keithWriting)
        val findings = ArrayList<Finding>()
        val low = twinReplies.joinToString("\n").lowercase()

        values?.anchor()?.let { anchor ->
            // "Don't say …" / Lines I won't cross: flag if twin used a forbidden phrase.
            for (section in values.sections) {
                if (section.title == ValuesCard.TITLES[3] || section.title == ValuesCard.TITLES[2]) {
                    for (line in section.lines) {
                        val phrase = extractAvoided(line) ?: continue
                        if (phrase.length >= 3 && low.contains(phrase.lowercase())) {
                            findings += Finding(Finding.Kind.VALUES, "Used avoided phrase from values card: \"$phrase\"")
                        }
                    }
                }
            }
            // Soft check: if approved "What matters most" lines exist and twin never echoes any stem.
            val matters = values.sections.firstOrNull { it.title == ValuesCard.TITLES[0] }?.lines.orEmpty()
            if (matters.isNotEmpty() && twinReplies.size >= MIN_SAMPLES) {
                val stems = matters.map { stem(it) }.filter { it.length >= 4 }
                if (stems.isNotEmpty() && stems.none { s -> low.contains(s) }) {
                    findings += Finding(
                        Finding.Kind.VALUES,
                        "Recent replies never touch approved \"What matters most\" themes.",
                    )
                }
            }
            // silence unused
            anchor.length
        }

        val whys = journal.whys(12)
        if (whys.isNotEmpty() && twinReplies.size >= MIN_SAMPLES) {
            // Flag twin giving advice that contradicts a recent why when it uses an opposing cue.
            for (why in whys) {
                val cue = opposingCue(why) ?: continue
                if (low.contains(cue)) {
                    findings += Finding(
                        Finding.Kind.DECISION,
                        "Reply language conflicts with Keith's recorded why: \"${why.take(80)}\"",
                    )
                }
            }
        }

        val allFindings = buildList {
            style?.reasons?.forEach { add(Finding(Finding.Kind.STYLE, it)) }
            addAll(findings)
        }
        return Report(style, allFindings, now, twinReplies.size, keithWriting.size)
    }

    /** Pulls the quoted phrase from a "Don't say \"…\"" values-card line. */
    internal fun extractAvoided(line: String): String? {
        val m = Regex("""Don't say\s+[\"“](.+?)[\"”]""", RegexOption.IGNORE_CASE).find(line.trim())
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun stem(line: String): String =
        line.lowercase().split(Regex("\\W+")).filter { it.length >= 4 }.take(2).joinToString(" ")

    /**
     * Very small opposing-cue table: if Keith's why says he won't / can't / never X,
     * flag twin replies that urge doing X. Returns the cue substring to look for, or null.
     */
    internal fun opposingCue(why: String): String? {
        val w = why.lowercase()
        return when {
            w.contains("won't sell") || w.contains("not selling") -> "should sell"
            w.contains("won't borrow") || w.contains("no debt") -> "take out a loan"
            w.contains("kids come first") -> "skip the kids"
            else -> null
        }
    }
}
