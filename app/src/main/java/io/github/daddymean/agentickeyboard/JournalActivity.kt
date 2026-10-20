package io.github.daddymean.agentickeyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import android.text.InputType
import android.text.format.DateFormat
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.daddymean.agentickeyboard.twin.TwinLearning
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.twin.journal.DailyPlan
import io.github.daddymean.agentickeyboard.twin.journal.InkModelGate
import io.github.daddymean.agentickeyboard.twin.journal.InkRecognizer
import io.github.daddymean.agentickeyboard.twin.journal.InkStrokes
import io.github.daddymean.agentickeyboard.twin.journal.InkView
import io.github.daddymean.agentickeyboard.twin.journal.Instrument
import io.github.daddymean.agentickeyboard.twin.journal.Instruments
import io.github.daddymean.agentickeyboard.twin.journal.JournalHistory
import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.LegacyInterview
import io.github.daddymean.agentickeyboard.twin.journal.JournalRecord
import io.github.daddymean.agentickeyboard.twin.journal.JournalRecorder
import io.github.daddymean.agentickeyboard.twin.journal.JournalScheduler
import io.github.daddymean.agentickeyboard.twin.journal.JournalSettings
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import io.github.daddymean.agentickeyboard.twin.journal.OnDeviceTranscriber
import io.github.daddymean.agentickeyboard.twin.journal.Prompt
import io.github.daddymean.agentickeyboard.twin.journal.PromptInstance
import io.github.daddymean.agentickeyboard.twin.journal.QuestionBank
import io.github.daddymean.agentickeyboard.twin.journal.ValueCard
import io.github.daddymean.agentickeyboard.twin.journal.VoiceScripts
import io.github.daddymean.agentickeyboard.twin.journal.VoiceSession
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale

/**
 * KEYBOARD-025: the legacy twin's journal and interview mode.
 *
 * Today's prompts (paced by [JournalScheduler]), answers by text or voice (lossless FLAC,
 * encrypted at rest), on-device dictation for transcripts, personality and values
 * instruments with their scores, voice-calibration scripts, and a viewer with delete.
 * FLAG_SECURE; every store call runs on the twin worker. The microphone permission is
 * requested only when Keith first taps Record, after an explanation.
 */
class JournalActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: LinearLayout
    private lateinit var store: JournalStore
    private lateinit var recorder: JournalRecorder
    private lateinit var transcriber: OnDeviceTranscriber
    private var bank: List<Prompt> = emptyList()
    private var interviewBank: List<Prompt> = emptyList()
    private var instruments: List<Instrument> = emptyList()
    private var cards: List<ValueCard> = emptyList()
    private var sessions: List<VoiceSession> = emptyList()
    private var setupAdvice = ""
    private var records: List<JournalRecord> = emptyList()
    private var player: MediaPlayer? = null
    private var pendingRecordStart: (() -> Unit)? = null
    private var onStopRecording: (() -> Unit)? = null
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        store = TwinVault.journal(this)
        recorder = JournalRecorder(this, store)
        transcriber = OnDeviceTranscriber(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        scope.launch {
            runCatching {
                val bankJson = assets.open(ASSET_BANK).bufferedReader().use { it.readText() }
                val instJson = assets.open(ASSET_INSTRUMENTS).bufferedReader().use { it.readText() }
                val scripts = assets.open(ASSET_SCRIPTS).bufferedReader().use { it.readText() }
                bank = QuestionBank.parse(bankJson)
                interviewBank = QuestionBank.parse(assets.open(LegacyInterview.ASSET).bufferedReader().use { it.readText() })
                instruments = Instruments.parse(instJson)
                cards = Instruments.valueCards(instJson)
                sessions = VoiceScripts.parse(scripts)
                setupAdvice = VoiceScripts.setup(scripts)
            }.onFailure { SafeLog.w(TAG, "Journal content failed to load", it) }
            home()
        }
    }

    override fun onStop() {
        // Never record in the background.
        if (recorder.isRecording) onStopRecording?.invoke()
        transcriber.stop()
        player?.release(); player = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Back (button or gesture) returns to the journal home first, stopping any take. */
    private fun onBack() {
        if (root.tag == "home") finish() else {
            if (recorder.isRecording) onStopRecording?.invoke()
            scope.launch { home() }
        }
    }

    // ---- data -------------------------------------------------------------------------

    private suspend fun reload() {
        records = runCatching { TwinLearning.onWorker { store.all() } }
            .onFailure { SafeLog.w(TAG, "Journal store unavailable", it) }.getOrDefault(emptyList())
    }

    private fun add(kind: String, body: JSONObject, then: () -> Unit) {
        scope.launch {
            runCatching { TwinLearning.onWorker { store.add(kind, body, System.currentTimeMillis()) } }
                .onFailure { SafeLog.w(TAG, "Journal entry was not stored", it); toast("Couldn't save. Please try again.") }
            reload()
            then()
        }
    }

    private fun settings() = JournalHistory.settings(records)

    // ---- screens ----------------------------------------------------------------------

    private suspend fun home() {
        reload()
        clear("home")
        title("Journal & interview")
        text(NOTICE, 14f)
        val told = JournalHistory.answerCount(records)
        text(if (told == 0) "Your stories will collect here." else "You've told $told stories so far.", 16f)
        val today = LocalDate.now()
        val plan = JournalScheduler.plan(bank, JournalHistory.state(records, bank, today), today, LocalTime.now().hour)
        showPlan(plan)
        heading("This week")
        val session = LegacyInterview.savedSession(records, LegacyInterview.weekOf(today))
        val done = session?.promptIds?.count { it in LegacyInterview.answeredIds(records) } ?: 0
        button(if (session == null) "Weekly legacy interview" else "Weekly legacy interview ($done of ${session.promptIds.size} answered)") { scope.launch { interviewScreen() } }
        val waiting = LegacyInterview.kidQuestions(records).count { !it.answered }
        button("Kids' questions for you ($waiting waiting)") { kidQuestionsScreen() }
        heading("More")
        button("Personality & values") { instrumentsScreen() }
        button("Voice scripts") { voiceScriptsScreen() }
        button("Your answers") { scope.launch { answersScreen() } }
        button("Journal settings") { settingsScreen() }
    }

    private fun showPlan(plan: DailyPlan) {
        heading("Today")
        plan.note?.let { text(it, 14f) }
        if (plan.prompts.isEmpty()) text("Nothing for today. Enjoy the rest.", 15f)
        for (p in plan.prompts) {
            val label = if (p.slot == PromptInstance.Slot.MORNING) "Morning (light)" else "Evening"
            text("$label · ${p.prompt.categoryLabel} · about ${p.prompt.estMinutes} min" + if (p.isRevisit) " · yearly revisit" else "", 12f)
            text(if (p.isRevisit) "A year ago you answered this. Has anything changed?\n\n${p.text}" else p.text, 17f)
            row(
                "Answer" to { answerScreen(p, null, 0) },
                "Skip for now" to { add(JournalKinds.EVENT, JSONObject().put("type", "skip").put("key", p.key)) { scope.launch { home() } } },
                "Not this one" to { add(JournalKinds.EVENT, JSONObject().put("type", "retire").put("key", p.key)) { scope.launch { home() } } }
            )
        }
    }

    /** Answer a prompt (or a follow-up when [followUpIndex] > 0) by text and/or voice. */
    private fun answerScreen(p: PromptInstance, parentText: String?, followUpIndex: Int, onDone: () -> Unit = { scope.launch { home() } }) {
        clear("answer")
        val question = if (followUpIndex == 0) p.text else p.prompt.followUps[followUpIndex - 1]
        title(if (followUpIndex == 0) "Your answer" else "Tell me more")
        text(question, 17f)
        if (followUpIndex == 0 && p.prompt.followUps.isNotEmpty()) text("If it helps: " + p.prompt.followUps.joinToString(" · "), 13f)
        if (followUpIndex == 0) button("Write it by hand instead") { handwritingScreen(p, question) }
        val answer = edit("Type your answer (or record below)", lines = 6)
        val takes = ArrayList<Pair<String, Long>>()
        val transcript = edit("Transcript of your recording (optional)", lines = 4)
        recordControls(takes, transcript)
        row(
            "Save" to {
                val body = JSONObject()
                    .put("promptId", p.prompt.id)
                    .put("child", p.child ?: "")
                    .put("prompt", question)
                    .put("category", p.prompt.category)
                    .put("followUp", followUpIndex > 0)
                    .put("revisit", p.isRevisit)
                    .put("text", answer.text.toString())
                    .put("transcript", transcript.text.toString())
                    .put("audio", JSONArray(takes.map { JSONObject().put("file", it.first).put("ms", it.second) }))
                if (answer.text.isBlank() && transcript.text.isBlank() && takes.isEmpty()) { toast("Nothing to save yet."); return@to }
                add(JournalKinds.ANSWER, body) {
                    val words = (answer.text.toString() + " " + transcript.text.toString()).split(Regex("\\s+")).count { it.isNotBlank() }
                    val voiceMs = takes.sumOf { it.second }
                    val maxFollowUps = if (words > 150 || voiceMs > 90_000) 2 else 1
                    if (followUpIndex < maxFollowUps && followUpIndex < p.prompt.followUps.size) offerFollowUp(p, followUpIndex + 1, onDone)
                    else onDone()
                }
            },
            "Cancel" to {
                if (recorder.isRecording) onStopRecording?.invoke()
                scope.launch { TwinLearning.onWorker { takes.forEach { store.deleteAudio(it.first) } }; onDone() }
            }
        )
    }

    private fun offerFollowUp(p: PromptInstance, index: Int, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Saved. Tell me more?")
            .setMessage(p.prompt.followUps[index - 1])
            .setPositiveButton("Yes") { _, _ -> answerScreen(p, null, index, onDone) }
            .setNegativeButton("Not now") { _, _ -> onDone() }
            .show()
    }

    /** Record/Stop, the level, and on-device dictation into [transcript]. */
    private fun recordControls(takes: MutableList<Pair<String, Long>>, transcript: EditText) {
        val status = text("Voice: lossless FLAC, 48 kHz mono, encrypted on this phone.", 13f)
        val record = Button(this)
        fun stop() {
            val result = runCatching { recorder.stop() }
            onStopRecording = null
            record.text = "Record"
            result.onSuccess { takes += it; status.text = "Saved take ${takes.size} (${it.second / 1000} s), encrypted." }
                .onFailure { status.text = "The recording failed and was discarded." }
        }
        record.text = "Record"
        record.setOnClickListener {
            if (recorder.isRecording) { stop(); return@setOnClickListener }
            withMicPermission {
                runCatching {
                    recorder.onLevel = { db -> runOnUiThread { status.text = "Recording… level ${"%.0f".format(Locale.US, db)} dBFS (aim for −12 to −6)" } }
                    // Dictation and recording can't share the microphone; the archival take wins.
                    transcriber.stop()
                    recorder.start()
                }.onSuccess {
                    record.text = "Stop"
                    onStopRecording = { stop() }
                }.onFailure { status.text = "Couldn't start recording: ${it.message}" }
            }
        }
        root.addView(record)
        if (transcriber.isAvailable) {
            button("Dictate transcript (on this phone only)") {
                if (recorder.isRecording) { toast("Stop recording first."); return@button }
                withMicPermission {
                    val before = transcript.text.toString().trimEnd()
                    transcriber.start(
                        onText = { t, _ -> transcript.setText(if (before.isEmpty()) t else "$before $t") },
                        onError = { toast(it) }
                    )
                }
            }
        } else {
            text("On-device dictation isn't available on this phone; type a transcript if you like.", 12f)
        }
    }

    /** Asks for the microphone only when needed, after explaining why. */
    private fun withMicPermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { action(); return }
        AlertDialog.Builder(this)
            .setTitle("Use the microphone?")
            .setMessage(MIC_RATIONALE)
            .setPositiveButton("Continue") { _, _ ->
                pendingRecordStart = action
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val action = pendingRecordStart
        pendingRecordStart = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
        else toast("Without the microphone you can still answer by typing.")
    }

    private fun instrumentsScreen() {
        clear("instruments")
        title("Personality & values")
        text("Short blocks, about 10 minutes at a time. Answer how you are now, not how you'd like to be. Results stay encrypted on this phone.", 14f)
        for (inst in instruments) {
            val done = JournalHistory.responses(records, inst.id).size
            heading(inst.name)
            text("$done of ${inst.items.size} answered · ${inst.license}", 12f)
            row(
                (if (done < inst.items.size) "Next block" else "Retake") to { blockScreen(inst, retake = done >= inst.items.size) },
                "Results" to { resultsScreen(inst) }
            )
        }
        if (cards.isNotEmpty()) {
            heading("Values Card Sort")
            button("Sort the cards") { cardSortScreen() }
        }
        button("Back") { scope.launch { home() } }
    }

    private fun blockScreen(inst: Instrument, retake: Boolean) {
        clear("block")
        val answered = if (retake) emptyMap() else JournalHistory.responses(records, inst.id)
        val items = inst.items.filter { it.n !in answered }.take(BLOCK)
        title(inst.name)
        text("${items.size} statements. ${inst.labels.first()} (1) to ${inst.labels.last()} (${inst.points}).", 13f)
        val groups = items.map { item ->
            text("${item.n}. ${item.text}", 16f)
            RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                inst.labels.forEachIndexed { i, label -> addView(RadioButton(context).apply { id = View.generateViewId(); text = "${i + 1} · $label"; tag = i + 1 }) }
                root.addView(this)
            }
        }
        row(
            "Save block" to {
                val responses = JSONObject()
                items.zip(groups).forEach { (item, g) ->
                    val checked = g.findViewById<RadioButton?>(g.checkedRadioButtonId)
                    (checked?.tag as? Int)?.let { responses.put(item.n.toString(), it) }
                }
                if (responses.length() == 0) { toast("Nothing answered yet."); return@to }
                add(JournalKinds.INSTRUMENT, JSONObject().put("instrumentId", inst.id).put("responses", responses)) { instrumentsScreen() }
            },
            "Back" to { instrumentsScreen() }
        )
    }

    private fun resultsScreen(inst: Instrument) {
        clear("results")
        title(inst.name)
        val scores = Instruments.score(inst, JournalHistory.responses(records, inst.id))
        text("Mean item score per scale (1–${inst.points}). Reverse-keyed items are flipped before averaging. Source: ${inst.source}", 12f)
        text(SCORE_DISCLAIMER, 12f)
        for (s in scores) {
            val v = if (s.mean.isNaN()) "–" else "%.2f".format(Locale.US, s.mean)
            text("${s.scale}: $v" + if (!s.complete) "  (${s.answered}/${s.total} answered)" else "", 15f)
        }
        button("Back") { instrumentsScreen() }
    }

    private fun cardSortScreen() {
        clear("cards")
        title("Values Card Sort")
        text("Put each card in a pile, then pick your top 5 in order. Afterwards, record a story for each top card from Today's prompts or the answers screen.", 14f)
        val piles = listOf("Core to me", "Matters", "Less important")
        val groups = cards.map { card ->
            text(card.text, 16f)
            RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
                piles.forEach { p -> addView(RadioButton(context).apply { id = View.generateViewId(); text = p; tag = p }) }
                root.addView(this)
            }
        }
        heading("Top 5")
        val choices = listOf("—") + cards.map { it.text }
        val spinners = (1..5).map { i ->
            text("$i.", 14f)
            Spinner(this).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, choices); root.addView(this) }
        }
        row(
            "Save" to {
                val pile = JSONObject()
                cards.zip(groups).forEach { (c, g) ->
                    (g.findViewById<RadioButton?>(g.checkedRadioButtonId)?.tag as? String)?.let { pile.put(c.value, it) }
                }
                val top = JSONArray(spinners.mapNotNull { s -> s.selectedItemPosition.takeIf { it > 0 }?.let { cards[it - 1].value } }.distinct())
                add(JournalKinds.CARD_SORT, JSONObject().put("piles", pile).put("top5", top)) { instrumentsScreen() }
            },
            "Back" to { instrumentsScreen() }
        )
    }

    private fun voiceScriptsScreen() {
        clear("scripts")
        title("Voice scripts")
        text(setupAdvice, 13f)
        for (s in sessions) {
            val takes = records.count { it.kind == JournalKinds.VOICE_SCRIPT && it.body.optInt("session") == s.number }
            button(s.title + if (takes > 0) "  ($takes saved)" else "") { sessionScreen(s) }
        }
        button("Back") { scope.launch { home() } }
    }

    private fun sessionScreen(s: VoiceSession) {
        clear("session")
        title(s.title)
        text(s.text, 17f)
        val takes = ArrayList<Pair<String, Long>>()
        val transcript = edit("Notes or changed words (optional)", lines = 3)
        recordControls(takes, transcript)
        row(
            "Save" to {
                if (takes.isEmpty()) { toast("Record first."); return@to }
                add(JournalKinds.VOICE_SCRIPT, JSONObject().put("session", s.number).put("title", s.title)
                    .put("transcript", transcript.text.toString())
                    .put("audio", JSONArray(takes.map { JSONObject().put("file", it.first).put("ms", it.second) }))) { voiceScriptsScreen() }
            },
            "Cancel" to {
                if (recorder.isRecording) onStopRecording?.invoke()
                scope.launch { TwinLearning.onWorker { takes.forEach { store.deleteAudio(it.first) } }; voiceScriptsScreen() }
            }
        )
    }

    private suspend fun answersScreen() {
        reload()
        clear("answers")
        title("Your answers")
        val shown = records.filter { it.kind in setOf(JournalKinds.ANSWER, JournalKinds.VOICE_SCRIPT, JournalKinds.CARD_SORT) }.asReversed()
        if (shown.isEmpty()) text("Nothing saved yet.", 15f)
        for (r in shown.take(200)) {
            val b = r.body
            text(DateFormat.format("yyyy-MM-dd HH:mm", Date(r.atMillis)).toString() +
                (b.optString("child").takeIf { it.isNotEmpty() }?.let { " · for $it" } ?: ""), 12f)
            text(b.optString("prompt").ifEmpty { b.optString("title").ifEmpty { "Values Card Sort" } }, 15f)
            b.optString("text").takeIf { it.isNotBlank() }?.let { text(it, 15f) }
            b.optString("transcript").takeIf { it.isNotBlank() }?.let { text("Transcript: $it", 14f) }
            if (r.kind == JournalKinds.CARD_SORT) text("Top 5: " + b.optJSONArray("top5")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }, 14f)
            val audio = JournalStore.audioIds(b)
            val actions = ArrayList<Pair<String, () -> Unit>>()
            audio.forEachIndexed { i, id -> actions += "Play ${i + 1}" to { play(id) } }
            actions += "Delete" to {
                AlertDialog.Builder(this).setMessage("Delete this answer and its recordings?")
                    .setPositiveButton("Delete") { _, _ -> scope.launch { TwinLearning.onWorker { TwinVault.deleteJournalRecord(this@JournalActivity, r.id) }; answersScreen() } }
                    .setNegativeButton("Cancel", null).show()
            }
            row(*actions.toTypedArray())
        }
        val unsaved = runCatching { TwinLearning.onWorker { store.unsavedRecordings(records) } }.getOrDefault(emptyList())
        if (unsaved.isNotEmpty()) {
            text("Unsaved recordings (takes from answers that were closed without saving). They stay until you delete them.", 13f)
            unsaved.forEachIndexed { i, id ->
                row("Play take ${i + 1}" to { play(id) }, "Delete" to {
                    scope.launch { TwinLearning.onWorker { store.deleteAudio(id) }; answersScreen() }
                })
            }
        }
        button("Back") { scope.launch { home() } }
    }

    private fun play(fileId: String) {
        scope.launch {
            val bytes = runCatching { TwinLearning.onWorker { store.readRecording(fileId) } }.getOrNull()
            if (bytes == null) { toast("This recording can't be opened."); return@launch }
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(object : MediaDataSource() {
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (position >= bytes.size) return -1
                        val n = minOf(size.toLong(), bytes.size - position).toInt()
                        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                        return n
                    }
                    override fun getSize(): Long = bytes.size.toLong()
                    override fun close() = Unit
                })
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    private fun settingsScreen() {
        clear("settings")
        title("Journal settings")
        val s = settings()
        text("Children's names (comma-separated). Prompts written for each child are asked once per name. Stored encrypted.", 13f)
        val kids = edit("e.g. Ada, Ben", lines = 1).apply { setText(s.children.joinToString(", ")) }
        text("Partner or co-parent's name (optional)", 13f)
        val partner = edit("Name", lines = 1).apply { setText(s.partner ?: "") }
        val nudge = Switch(this).apply { text = "Morning nudge (one light prompt)"; isChecked = s.morningNudge; root.addView(this) }
        val today = LocalDate.now()
        val heavyPaused = s.heavyPausedUntil?.let { it > today } == true
        val heavy = Switch(this).apply { text = "Life is heavy right now (no heavy prompts for 30 days)"; isChecked = heavyPaused; root.addView(this) }
        val retired = JournalHistory.state(records, bank, today).retired
        if (retired.isNotEmpty()) {
            heading("Prompts you set aside")
            for (key in retired.sorted()) {
                val p = bank.firstOrNull { it.id == key.substringBefore('#') }
                text(p?.text ?: key, 14f)
                button("Bring back") { add(JournalKinds.EVENT, JSONObject().put("type", "unretire").put("key", key)) { settingsScreen() } }
            }
        }
        row(
            "Save" to {
                val updated = JournalSettings(
                    children = JournalSettings.parseNames(kids.text.toString()),
                    partner = partner.text.toString().trim().ifEmpty { null },
                    morningNudge = nudge.isChecked,
                    heavyPausedUntil = when {
                        heavy.isChecked && !heavyPaused -> today.plusDays(30)
                        heavy.isChecked -> s.heavyPausedUntil
                        else -> null
                    },
                    startDate = s.startDate ?: records.firstOrNull()?.let { JournalHistory.date(it.atMillis) } ?: today
                )
                add(JournalKinds.SETTINGS, updated.toJson()) { scope.launch { home() } }
            },
            "Back" to { scope.launch { home() } }
        )
        text("Delete everything from the Legacy twin screen: it removes the journal, every recording and the typed-message store, and destroys the key.", 12f)
        if (InkModelGate.approved(records)) {
            button("Handwriting recognition: remove the model and turn it off") {
                InkRecognizer(true).delete { add(JournalKinds.EVENT, InkModelGate.approval(false)) { toast("Removed. Handwriting is still saved; you type what it says.") } }
            }
        }
    }

    // ---- KEYBOARD-034: weekly legacy interview and the kids' questions -------------------

    private suspend fun interviewScreen() {
        reload()
        val week = LegacyInterview.weekOf(LocalDate.now())
        var session = LegacyInterview.savedSession(records, week)
        if (session == null) {
            val planned = LegacyInterview.plan(interviewBank, records, LocalDate.now())
            runCatching { TwinLearning.onWorker { store.add(JournalKinds.EVENT, LegacyInterview.sessionEvent(planned), System.currentTimeMillis()) } }
                .onFailure { SafeLog.w(TAG, "Interview session was not stored", it) }
            reload()
            session = planned
        }
        clear("interview")
        title("Weekly legacy interview")
        text("Week of $week. ${session.promptIds.size} questions; answer any of them, in any order, by typing or recording. " +
            "Your kids' own questions come first. Questions written for this app, inspired by StoryCorps Great Questions " +
            "(storycorps.org).", 14f)
        val answered = LegacyInterview.answeredIds(records)
        val kidQs = LegacyInterview.kidQuestions(records).associateBy { it.promptId }
        for (id in session.promptIds) {
            val prompt = kidQs[id]?.let(LegacyInterview::prompt) ?: interviewBank.firstOrNull { it.id == id }
            if (prompt == null) { text("(A question that was withdrawn.)", 13f); continue }
            val child = kidQs[id]?.kid
            text(prompt.categoryLabel + if (id in answered) " · answered" else "", 12f)
            text(prompt.text, 17f)
            if (id !in answered) button("Answer") {
                answerScreen(PromptInstance(prompt, child, prompt.text, false, PromptInstance.Slot.EVENING), null, 0) { scope.launch { interviewScreen() } }
            }
        }
        button("Back") { scope.launch { home() } }
    }

    private fun kidQuestionsScreen() {
        clear("kidq")
        title("Kids' questions")
        text("Kids: ask Dad anything. Your question stays encrypted on this phone, goes into his next weekly interview, " +
            "and you can withdraw it until he answers. His answer joins his journal like any other.", 14f)
        var kid: String? = null
        val picker = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val who = TextView(this).apply { text = "Who's asking?" }
        root.addView(who)
        val grid = android.widget.HorizontalScrollView(this).apply { addView(picker) }
        for (k in LegacyInterview.KIDS) picker.addView(Button(this).apply { text = k; setOnClickListener { kid = k; who.text = "Asking as $k" } })
        root.addView(grid)
        val q = edit("Your question for Dad", 3)
        button("Add to Dad's interview") {
            val body = runCatching { LegacyInterview.newKidQuestion(records, kid ?: "", q.text.toString()) }
                .getOrElse { toast(it.message ?: "Couldn't add."); return@button }
            add(JournalKinds.EVENT, body) { toast("Added. Dad will see it in his next weekly interview."); kidQuestionsScreen() }
        }
        heading("Waiting")
        val all = LegacyInterview.kidQuestions(records)
        if (all.none { !it.answered }) text("No questions waiting.", 14f)
        for (kq in all.filter { !it.answered }) {
            text("${kq.kid} · ${LegacyInterview.date(kq.askedAtMillis)}", 12f)
            text(kq.text, 15f)
            button("Withdraw") {
                AlertDialog.Builder(this).setMessage("Withdraw this question? It is deleted from the phone.")
                    .setPositiveButton("Withdraw") { _, _ ->
                        scope.launch { TwinLearning.onWorker { TwinVault.deleteJournalRecord(this@JournalActivity, kq.recordId) }; reload(); kidQuestionsScreen() }
                    }.setNegativeButton("Cancel", null).show()
            }
        }
        val done = all.count { it.answered }
        if (done > 0) text("$done answered. Dad's answers are under \"Your answers\".", 13f)
        button("Back") { scope.launch { home() } }
    }

    // ---- KEYBOARD-037: handwriting -----------------------------------------------------

    private fun handwritingScreen(p: PromptInstance, question: String) {
        clear("handwriting")
        title("Write it by hand")
        text(question, 17f)
        text("Write with your finger or a stylus. Your handwriting itself is saved, encrypted on this phone, along with " +
            "what it says.", 13f)
        val view = InkView(this, (320 * resources.displayMetrics.density).toInt())
        root.addView(view)
        val said = edit("What it says (you can correct it)", lines = 3)
        var source: String? = null
        val approved = InkModelGate.approved(records)
        val recognizer = InkRecognizer(approved)
        row(
            "Clear" to { view.clear() },
            "Read my handwriting" to {
                if (view.ink.isEmpty) { toast("Write something first."); return@to }
                if (!approved) { approveInkModel { handwritingScreen(p, question) }; return@to }
                recognizer.isDownloaded { ready ->
                    if (!ready) {
                        toast("Downloading the handwriting model once (Wi-Fi)…")
                        recognizer.download { err -> if (err != null) toast("Download didn't finish: ${err.message}") else toast("Ready. Tap again.") }
                    } else recognizer.recognize(view.ink) { t, err ->
                        if (t != null) { said.setText(t); source = InkRecognizer.NAME } else toast("Couldn't read it: ${err?.message}")
                    }
                }
            }
        )
        row(
            "Save" to {
                val body = runCatching {
                    InkStrokes.answerBody(p.prompt.id, p.child, question, p.prompt.category, view.ink, said.text.toString(),
                        source?.takeIf { said.text.isNotBlank() }?.let { "$it, checked by Keith" })
                }.getOrElse { toast(it.message ?: "Couldn't save."); return@to }
                add(JournalKinds.ANSWER, body) { scope.launch { home() } }
            },
            "Cancel" to { scope.launch { home() } }
        )
    }

    /** The one-time, Keith-approved model download (KEYBOARD-037). */
    private fun approveInkModel(then: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Handwriting recognition")
            .setMessage(InkModelGate.DISCLOSURE)
            .setPositiveButton("Allow the one-time download") { _, _ -> add(JournalKinds.EVENT, InkModelGate.approval(true)) { then() } }
            .setNegativeButton("Not now", null).show()
    }

    // ---- view helpers -----------------------------------------------------------------

    private fun clear(tag: String) { root.removeAllViews(); root.tag = tag }
    private fun title(t: String) = text(t, 22f)
    private fun heading(t: String) = text(t, 18f).apply { setPadding(0, pad, 0, pad / 4) }
    private fun text(t: String, size: Float): TextView =
        TextView(this).apply { textSize = size; text = t; setPadding(0, pad / 4, 0, pad / 4); root.addView(this) }
    private fun edit(hint: String, lines: Int): EditText = EditText(this).apply {
        this.hint = hint
        minLines = lines
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        // KEYBOARD-028: journal text is twin data. This flag makes any keyboard (ours included) treat the
        // field as sensitive: no learning or history, no cloud AI, and no "Save note" to the context service
        // (whose opt-in sync leaves the phone).
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        root.addView(this)
    }
    private fun button(label: String, onClick: () -> Unit) =
        root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
    private fun row(vararg buttons: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { (label, f) -> r.addView(Button(this).apply { text = label; setOnClickListener { f() } }) }
        root.addView(r)
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        const val SCORE_DISCLAIMER = "These scores describe how you answered, for your twin and your kids. They are not a diagnosis or a clinical assessment."
        private const val TAG = "JournalActivity"
        private const val REQ_MIC = 25
        private const val BLOCK = 30
        const val ASSET_BANK = "twin/question-bank.json"
        const val ASSET_INSTRUMENTS = "twin/instruments.json"
        const val ASSET_SCRIPTS = "twin/voice-scripts.md"

        const val NOTICE =
            "Answers, recordings and results are encrypted on this phone with the twin's key, never backed up or " +
                "sent anywhere, and never deleted automatically. You can view and delete them under \"Your answers\"; " +
                "\"Delete all twin data\" on the Legacy twin screen removes everything and destroys the key."

        const val MIC_RATIONALE =
            "To record your voice answers, the keyboard app needs the microphone. It only listens while you're on " +
                "this screen and have pressed Record (or Dictate). Recordings are saved lossless, encrypted on this " +
                "phone, never uploaded and never backed up. You can always answer by typing instead."
    }
}
