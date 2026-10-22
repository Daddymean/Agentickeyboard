package io.github.daddymean.agentickeyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.daddymean.agentickeyboard.twin.TwinLearning
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.twin.capsule.Capsule
import io.github.daddymean.agentickeyboard.twin.capsule.CapsuleAudio
import io.github.daddymean.agentickeyboard.twin.capsule.TimeCapsules
import io.github.daddymean.agentickeyboard.twin.capsule.Unlock
import io.github.daddymean.agentickeyboard.twin.journal.JournalRecorder
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Date

/**
 * KEYBOARD-036: Keith records sealed messages for milestones, such as Kid N at 18,
 * graduation or a wedding. Each one is encrypted on this phone. In the release archive each
 * is sealed again under its own capsule key, so it opens only when two key holders use their
 * cards with `open_time_capsule.py`. FLAG_SECURE; recordings are FLAC and the takes are
 * moved into the capsule, so no copy stays in the journal.
 */
class TwinCapsuleActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: LinearLayout
    private lateinit var recorder: JournalRecorder
    private val pad by lazy { (20 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        recorder = JournalRecorder(this, TwinVault.journal(this))
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
        home()
    }

    override fun onStop() {
        if (recorder.isRecording) runCatching { val (id, _) = recorder.stop(); TwinVault.journal(this).deleteAudio(id) }
        super.onStop()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun home() {
        scope.launch {
            val all = runCatching { TwinLearning.onWorker { TwinVault.capsules(this@TwinCapsuleActivity).all() } }
                .onFailure { SafeLog.w(TAG, "Capsules unreadable", it) }.getOrDefault(emptyList())
            root.removeAllViews()
            text("Time capsules", 22f)
            text(NOTICE, 14f)
            button("New time capsule") { edit(null, LinkedHashMap()) }
            for (c in all) {
                text("${c.kid} · ${c.title}\nOpens ${c.unlock.describe()} · ${c.audio.size} recordings · made ${date(c.createdAtMillis)}", 15f)
                button("Delete") {
                    AlertDialog.Builder(this@TwinCapsuleActivity).setMessage("Delete \"${c.title}\" for ${c.kid}? It can't be recovered.")
                        .setPositiveButton("Delete") { _, _ -> scope.launch { TwinLearning.onWorker { TwinVault.capsules(this@TwinCapsuleActivity).delete(c.id) }; home() } }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }
    }

    /** [takes]: recording name to (journal audio id, ms) while the capsule is being written. */
    private fun edit(kidChoice: String?, takes: LinkedHashMap<String, Pair<String, Long>>) {
        root.removeAllViews()
        text("New time capsule", 22f)
        var kid = kidChoice
        val who = text(kid?.let { "For $it" } ?: "Who is it for?", 16f)
        row(*TimeCapsules.KIDS.map { k -> k to { kid = k; who.text = "For $k" } }.toTypedArray())
        val title = field("Title (shown before it opens, e.g. \"For your wedding day\")", 1)
        text("Opens on a date (YYYY-MM-DD, e.g. their 18th birthday) or at an event two key holders confirm:", 14f)
        val date = field("Date, YYYY-MM-DD", 1).apply { inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE }
        val birth = field("Or their date of birth, for their 18th birthday", 1).apply { inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE }
        var event: String? = null
        val ev = text("Event: none", 14f)
        row(*Unlock.EVENTS.map { e -> e to { event = e; ev.text = "Event: $e" } }.toTypedArray())
        val body = field("Your message", 8)
        val status = text("${takes.size} recordings", 13f)
        val rec = Button(this).apply { text = "Record" }
        rec.setOnClickListener {
            if (recorder.isRecording) {
                runCatching { recorder.stop() }.onSuccess { (id, ms) -> takes["take-${takes.size + 1}"] = id to ms; status.text = "${takes.size} recordings" }
                    .onFailure { toast("Recording failed: ${it.message}") }
                rec.text = "Record"
            } else withMic {
                runCatching { recorder.start() }.onSuccess { rec.text = "Stop"; status.text = "Recording…" }.onFailure { toast("Couldn't record: ${it.message}") }
            }
        }
        root.addView(rec)
        button("Seal it") {
            if (recorder.isRecording) { toast("Stop the recording first."); return@button }
            val unlock = runCatching {
                when {
                    date.text.isNotBlank() -> Unlock(date = LocalDate.parse(date.text.toString().trim()))
                    birth.text.isNotBlank() -> Unlock.eighteenth(LocalDate.parse(birth.text.toString().trim()))
                    event != null -> Unlock(event = event)
                    else -> error("Pick a date, a date of birth or an event.")
                }
            }.getOrElse { toast(it.message ?: "Check the date (YYYY-MM-DD)."); return@button }
            scope.launch {
                runCatching {
                    TwinLearning.onWorker {
                        val journal = TwinVault.journal(this@TwinCapsuleActivity)
                        val audio = takes.map { (name, t) -> CapsuleAudio(name, t.second, journal.readRecording(t.first)) }
                        val c: Capsule = TimeCapsules.create(kid ?: "", title.text.toString(), unlock, body.text.toString(), audio, System.currentTimeMillis())
                        TwinVault.capsules(this@TwinCapsuleActivity).save(c)
                        takes.values.forEach { journal.deleteAudio(it.first) } // moved into the capsule
                    }
                }.onSuccess { toast("Sealed."); home() }.onFailure { toast(it.message ?: "Couldn't save.") }
            }
        }
        button("Cancel") {
            scope.launch { TwinLearning.onWorker { takes.values.forEach { TwinVault.journal(this@TwinCapsuleActivity).deleteAudio(it.first) } }; home() }
        }
    }

    /** The microphone is only ever requested from the Journal, after its rationale (KEYBOARD-025 guard). */
    private fun withMic(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { action(); return }
        AlertDialog.Builder(this).setMessage("Voice takes use the microphone permission you grant in the Journal. Open the Journal, record once there, then come back. Text capsules work without it.")
            .setPositiveButton("OK", null).show()
    }

    private fun text(t: String, size: Float) = TextView(this).apply { textSize = size; text = t; setPadding(0, pad / 4, 0, pad / 4) }.also(root::addView)
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }.also(root::addView)
    private fun row(vararg buttons: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { (label, f) -> r.addView(Button(this).apply { text = label; setOnClickListener { f() } }) }
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(r) })
    }
    /** Twin text: never learned by any keyboard (KEYBOARD-028). */
    private fun field(hint: String, lines: Int) = EditText(this).apply {
        this.hint = hint
        minLines = lines
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        root.addView(this)
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun date(ms: Long) = DateFormat.format("yyyy-MM-dd", Date(ms)).toString()

    companion object {
        private const val TAG = "TwinCapsules"
        const val NOTICE =
            "Messages you record now for one of the kids, to open at a milestone: a date such as their 18th birthday, or an " +
                "event such as graduation or a wedding. They're encrypted on this phone. In the release archive each one is " +
                "sealed separately and opens only when two key holders use their cards and the date has come or they confirm " +
                "the event. Only the kid, the title and the opening rule show before then."
    }
}
