package io.github.daddymean.agentickeyboard.bench

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.daddymean.agentickeyboard.BuildConfig
import io.github.daddymean.agentickeyboard.models.ModelCatalog
import io.github.daddymean.agentickeyboard.models.ModelManager
import io.github.daddymean.agentickeyboard.twin.memory.LiteRtTextEmbedder
import java.io.File
import java.util.Locale

/**
 * Debug-only "Model benchmark" screen for the on-device model bake-off.
 *
 * Off by default: it exists only in debug builds (src/debug), and its manifest
 * entry is disabled unless the build passes `-PmodelBenchmark=true`. It never
 * downloads anything, never touches twin data, and asks for no permission.
 * Model files are pushed by hand into this app's own external files dir
 * (`Android/data/<package>/files/bench/`); Gemma 4 E2B and EmbeddingGemma 2
 * 270M also work from the copies the normal opt-in download already verified.
 *
 * All prompts are the 20 synthetic cases in [BakeoffCases]. For a clean peak-RAM
 * number, force-stop the app between models: VmHWM is per process.
 */
class ModelBenchmarkActivity : Activity() {
    private lateinit var log: TextView
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        log = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true) }
        if (!BuildConfig.MODEL_BENCHMARK) {
            log.text = "Model benchmark is off. Build the debug APK with -PmodelBenchmark=true to turn it on."
            root.addView(log)
            setContentView(root)
            return
        }
        root.addView(TextView(this).apply {
            text = "Model benchmark (debug). Synthetic prompts only. Push model files to:\n${benchDir().absolutePath}"
        })
        for (model in BakeoffModels.ALL) {
            val backends = if (model.kind == BakeoffModels.Kind.REWRITE) listOf("cpu", "gpu") else listOf("cpu")
            for (backend in backends) {
                root.addView(Button(this).apply {
                    text = "${model.label} · ${backend.uppercase(Locale.US)}"
                    isAllCaps = false
                    setOnClickListener { start(model, backend) }
                })
            }
        }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })

        // adb: am start -n <pkg>/io.github.daddymean.agentickeyboard.bench.ModelBenchmarkActivity --es model lfm3-int4 --es backend cpu
        BakeoffModels.byKey(intent.getStringExtra("model"))?.let { start(it, intent.getStringExtra("backend") ?: "cpu") }
    }

    private fun benchDir(): File = File(getExternalFilesDir(null), "bench").apply { mkdirs() }

    private fun locate(model: BakeoffModels.BenchModel): File? {
        val pushed = File(benchDir(), model.fileName)
        if (pushed.isFile && pushed.length() == model.sizeBytes) return pushed
        val manager = ModelManager.forApp(this)
        return when (model.key) {
            BakeoffModels.GEMMA_4_E2B.key -> manager.verifiedFile(ModelCatalog.GEMMA_4_E2B)
            BakeoffModels.EMBEDDING_270M.key -> manager.verifiedFile(ModelCatalog.EMBEDDING_GEMMA_2)
            else -> null
        }
    }

    private fun append(line: String) = runOnUiThread { log.append(line + "\n") }

    private fun start(model: BakeoffModels.BenchModel, backend: String) {
        if (running) { append("Busy; wait for the current run."); return }
        val file = locate(model)
        if (file == null) {
            append("${model.label}: file missing. Push ${model.fileName} (${model.sizeBytes} bytes) to ${benchDir().absolutePath}")
            return
        }
        running = true
        append("== ${model.label} on $backend, ${file.name}")
        Thread({
            try {
                val report = if (model.kind == BakeoffModels.Kind.REWRITE) rewrite(model, file, backend) else embed(model, file)
                val out = File(benchDir(), "result-${model.key}-$backend-${System.currentTimeMillis()}.md")
                out.writeText(report)
                append(report)
                append("Saved ${out.absolutePath}")
            } catch (t: Throwable) {
                append("Failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running = false
            }
        }, "model-bench").start()
    }

    private fun peakRssMib(): Double? =
        BenchStats.parseVmHwmKb(File("/proc/self/status").readText())?.let { it / 1024.0 }

    private fun rewrite(model: BakeoffModels.BenchModel, file: File, backend: String): String {
        val cases = BakeoffCases.CASES
        val runner = LiteRtBenchRunner(file, File(cacheDir, "bench-litert"), backend == "gpu", model.disableThinking)
        val results = runner.run(cases.map { it.id }, cases.map { BakeoffCases.prompt(it) }, 256) { i, n, r ->
            append(String.format(Locale.US, "%d/%d %s ttft=%s ms decode=%s tok/s %s", i + 1, n, r.id,
                r.ttftMs?.let { "%.0f".format(Locale.US, it) } ?: "-",
                r.decodeTokensPerSec?.let { "%.1f".format(Locale.US, it) } ?: "-",
                r.error ?: ""))
        }
        val summary = BenchStats.summarize(results)
        val row = BenchStats.markdownRow(model.label, backend.uppercase(Locale.US), summary, peakRssMib(), model.sizeBytes)
        Log.i(TAG, row)
        val sb = StringBuilder()
        sb.append("| Model | Where | TTFT (median) | Prefill tok/s | Decode tok/s | Peak RSS (VmHWM) | Download | OK |\n")
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|\n").append(row).append("\n\n")
        sb.append("Median engine init: ${summary.medianInitMs?.let { "%.0f".format(Locale.US, it) }} ms\n\n")
        for ((case, r) in cases.zip(results)) {
            val shown = if (model.disableThinking) BenchStats.stripThinking(r.output) else r.output
            sb.append("- ${case.id} (${case.tone}): ")
                .append(r.error ?: shown?.replace('\n', ' '))
                .append(if (BenchStats.factsKept(shown, case.facts)) "" else "  [facts check: missing]")
                .append("\n")
        }
        return sb.toString()
    }

    private fun embed(model: BakeoffModels.BenchModel, file: File): String {
        val embedder = LiteRtTextEmbedder(file, File(cacheDir, "bench-litert"))
        try {
            val t0 = System.nanoTime()
            val dim = embedder.embedDocument("warm up").size
            val firstMs = (System.nanoTime() - t0) / 1e6
            val times = BakeoffCases.EMBED_TEXTS.map { text ->
                val s = System.nanoTime()
                embedder.embedDocument(text)
                (System.nanoTime() - s) / 1e6
            }
            val median = BenchStats.median(times)
            val row = String.format(Locale.US, "| %s | phone CPU | %.1f ms | %d | %.0f ms | %s MiB | %.0f MB |",
                model.label, median ?: Double.NaN, dim, firstMs,
                peakRssMib()?.let { "%.0f".format(Locale.US, it) } ?: "not measured", model.sizeBytes / 1e6)
            Log.i(TAG, row)
            return "| Model | Where | Embed latency (median of 10) | Dim | First call incl. load | Peak RSS (VmHWM) | Download |\n" +
                "|---|---|---:|---:|---:|---:|---:|\n$row\n"
        } finally {
            embedder.close()
        }
    }

    private companion object {
        const val TAG = "ModelBench"
    }
}
