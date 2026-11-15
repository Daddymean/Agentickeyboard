package io.github.daddymean.agentickeyboard.bench

import java.util.Locale

/** One timed rewrite. Times are from LiteRT-LM's own benchmark info where it reports them. */
data class PromptResult(
    val id: String,
    val ttftMs: Double?,
    val prefillTokensPerSec: Double?,
    val decodeTokensPerSec: Double?,
    val decodeTokens: Int?,
    val wallMs: Double,
    val initMs: Double,
    val output: String?,
    val error: String? = null,
)

data class BenchSummary(
    val total: Int,
    val ok: Int,
    val medianTtftMs: Double?,
    val medianDecodeTps: Double?,
    val medianPrefillTps: Double?,
    val medianInitMs: Double?,
    val medianWallMs: Double?,
)

object BenchStats {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    fun summarize(results: List<PromptResult>): BenchSummary {
        val ok = results.filter { it.error == null }
        return BenchSummary(
            total = results.size,
            ok = ok.size,
            medianTtftMs = median(ok.mapNotNull { it.ttftMs }),
            medianDecodeTps = median(ok.mapNotNull { it.decodeTokensPerSec }),
            medianPrefillTps = median(ok.mapNotNull { it.prefillTokensPerSec }),
            medianInitMs = median(ok.map { it.initMs }),
            medianWallMs = median(ok.map { it.wallMs }),
        )
    }

    /** Peak resident set (VmHWM) in kB from the text of /proc/self/status. */
    fun parseVmHwmKb(procStatus: String): Long? =
        procStatus.lineSequence()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.removePrefix("VmHWM:")
            ?.trim()
            ?.removeSuffix("kB")
            ?.trim()
            ?.toLongOrNull()

    /**
     * What a rewrite integration would show for LFM3: drop any `<think>…</think>`
     * block and a stray leading `…</think>` (the model card documents both with
     * thinking off). The raw text is still kept in the results file.
     */
    fun stripThinking(raw: String?): String? {
        if (raw == null) return null
        var text = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        val stray = text.lastIndexOf("</think>")
        if (stray >= 0) text = text.substring(stray + "</think>".length)
        return text.trim()
    }

    fun factsKept(output: String?, facts: List<String>): Boolean =
        output != null && facts.all { output.contains(it, ignoreCase = true) }

    fun markdownRow(label: String, backend: String, summary: BenchSummary, peakRssMib: Double?, fileBytes: Long): String {
        fun f(v: Double?, digits: Int) = v?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "not measured"
        return "| $label | phone $backend | ${f(summary.medianTtftMs, 0)} ms | ${f(summary.medianPrefillTps, 1)} | " +
            "${f(summary.medianDecodeTps, 1)} | ${f(peakRssMib, 0)} MiB | " +
            String.format(Locale.US, "%.2f GB", fileBytes / 1e9) + " | ${summary.ok}/${summary.total} |"
    }
}
