package io.github.daddymean.agentickeyboard

import android.app.Activity
import android.app.AlertDialog
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
import io.github.daddymean.agentickeyboard.twin.memory.Candidate
import io.github.daddymean.agentickeyboard.twin.memory.CitedReply
import io.github.daddymean.agentickeyboard.twin.memory.LegacyVault
import io.github.daddymean.agentickeyboard.twin.memory.MemoryIndex
import io.github.daddymean.agentickeyboard.twin.memory.TwinAnswerer
import io.github.daddymean.agentickeyboard.twin.memory.VaultState
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date

/**
 * KEYBOARD-029–031: the twin's memories (Keith, relayed 2026-10-19 14:25 PT).
 * - **Review**: journal answers become memories only when Keith approves (or edits and
 *   approves) them. Reject keeps one out for good.
 * - **Core blocks**: "Who I am" and "About my kids", always in the twin's context.
 * - **Vault**: the approved memories, searchable on the device (FTS4, in memory).
 * - **Ask the twin**: answers that quote approved memories, each with its source.
 * Everything is on this phone; there is no network code. Every text field opts out of
 * keyboard learning.
 */
class TwinMemoryActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: LinearLayout
    private var state = VaultState()
    private var index: MemoryIndex? = null
    private val pad by lazy { (20 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
        scope.launch { reload(); home() }
    }

    override fun onDestroy() {
        index?.close()
        index = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun reload() {
        val loaded = runCatching { TwinLearning.onWorker { TwinVault.memory(this).load() } }
            .onFailure { SafeLog.w(TAG, "Memory vault unreadable", it) }.getOrNull() ?: VaultState()
        setState(loaded)
    }

    private suspend fun setState(s: VaultState) {
        state = s
        val old = index
        index = TwinLearning.onWorker { MemoryIndex.build(this, s) }
        TwinLearning.onWorker { old?.close() }
    }

    private suspend fun change(f: (VaultState) -> VaultState) {
        setState(TwinLearning.onWorker { TwinVault.memory(this).update(f) })
    }

    private suspend fun candidates(): List<Candidate> =
        TwinLearning.onWorker { LegacyVault.candidates(TwinVault.journal(this).all(), state) }

    // ---- screens ----------------------------------------------------------------------

    private fun home() {
        scope.launch {
            val pending = candidates()
            clear()
            title("Twin memories")
            text(NOTICE, 14f)
            button("Review journal answers (${pending.size} waiting)") { review() }
            button("Core memories: Who I am, About my kids") { core() }
            button("Approved memories (${state.memories.size})") { vault("") }
            button("Ask the twin") { ask() }
        }
    }

    private fun review() {
        scope.launch {
            val pending = candidates()
            clear()
            title("Review")
            text("Nothing enters the twin's memory until you approve it. Rejected answers are never offered again. " +
                "Identifiers and numbers are already removed.", 14f)
            if (pending.isEmpty()) text("Nothing waiting.", 15f)
            for (c in pending.take(50)) {
                text(date(c.atMillis) + (c.child?.let { " · for $it" } ?: "") + (c.prompt?.let { "\n$it" } ?: ""), 13f)
                text(c.text, 15f)
                row(
                    "Approve" to { scope.launch { decide { LegacyVault.approve(it, c, System.currentTimeMillis()) }; review() } },
                    "Edit…" to { editThenApprove(c) },
                    "Reject" to { scope.launch { decide { LegacyVault.reject(it, c) }; review() } }
                )
            }
            button("Back") { home() }
        }
    }

    private suspend fun decide(f: (VaultState) -> VaultState) {
        runCatching { change(f) }.onFailure { toast(it.message ?: "Couldn't save.") }
    }

    private fun editThenApprove(c: Candidate) {
        val field = field("Memory", 4).apply { setText(c.text) }
        AlertDialog.Builder(this).setTitle("Edit, then approve").setView(field)
            .setPositiveButton("Approve") { _, _ ->
                val edited = field.text.toString()
                scope.launch { decide { LegacyVault.approve(it, c, System.currentTimeMillis(), edited) }; review() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun core() {
        clear()
        title("Core memories")
        text("These are always in the twin's context. Only you write them (up to ${LegacyVault.CORE_LIMIT} characters each).", 14f)
        val fields = state.core.map { b ->
            text(b.title, 17f)
            b.label to field(b.title, 5).apply { setText(b.value); root.addView(this) }
        }
        button("Save") {
            val now = System.currentTimeMillis()
            scope.launch {
                runCatching { change { s -> fields.fold(s) { acc, (label, f) -> LegacyVault.setCore(acc, label, f.text.toString(), now) } } }
                    .onSuccess { toast("Saved."); home() }.onFailure { toast(it.message ?: "Couldn't save.") }
            }
        }
        button("Back") { home() }
    }

    private fun vault(query: String) {
        clear()
        title("Approved memories")
        val search = field("Search memories", 1).apply { setText(query); root.addView(this) }
        button("Search") { vault(search.text.toString()) }
        val shown = if (query.isBlank()) state.memories.sortedByDescending { it.approvedAtMillis }.take(100)
        else index?.search(query, 50)?.map { it.memory } ?: emptyList()
        if (shown.isEmpty()) text(if (query.isBlank()) "No memories approved yet." else "No matches.", 15f)
        for (m in shown) {
            text(date(m.atMillis) + (m.prompt?.let { " · $it" } ?: "") + if (m.edited) " · edited" else "", 13f)
            text(m.text, 15f)
            button("Remove from the vault") {
                confirm("Remove this memory? The journal answer stays; it won't be offered for review again.") {
                    scope.launch { change { LegacyVault.remove(it, m.id) }; vault(query) }
                }
            }
        }
        button("Back") { home() }
    }

    private fun ask() {
        clear()
        title("Ask the twin")
        text("Answers come only from memories you approved and your core memories, quoted with their source. " +
            "If nothing matches, the twin says so.", 14f)
        val q = field("Ask a question", 2).apply { root.addView(this) }
        val out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        button("Ask") {
            val idx = index ?: return@button
            scope.launch {
                val reply = TwinLearning.onWorker { TwinAnswerer.answer(q.text.toString(), state, idx) }
                show(out, reply)
            }
        }
        root.addView(out)
        button("Back") { home() }
    }

    private fun show(out: LinearLayout, reply: CitedReply) {
        out.removeAllViews()
        out.addView(TextView(this).apply { textSize = 16f; text = reply.render(); setPadding(0, pad / 2, 0, pad / 2) })
        reply.citations.forEachIndexed { i, c ->
            out.addView(Button(this).apply {
                text = "[${i + 1}] ${c.label}"
                setOnClickListener { source(c.ref) }
            })
        }
    }

    private fun source(ref: String) {
        val msg = when {
            ref.startsWith("m:") -> state.memory(ref.removePrefix("m:"))?.let { m ->
                "Journal entry #${m.source.recordId} (${m.source.field}), ${date(m.atMillis)}" + (m.prompt?.let { "\nQuestion: $it" } ?: "") +
                    "\n\nMemory: ${m.text}" + if (m.edited) "\n\nOriginal answer: ${m.sourceText}" else ""
            }
            ref.startsWith("core:") -> state.coreBlock(ref.removePrefix("core:"))?.let { "Core memory: ${it.title}\n\n${it.value}" }
            else -> null
        } ?: "This source no longer exists."
        AlertDialog.Builder(this).setTitle("Source").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---- ui helpers -------------------------------------------------------------------

    private fun clear() = root.removeAllViews()
    private fun title(t: String) = text(t, 22f)
    private fun text(t: String, size: Float) = TextView(this).apply { textSize = size; text = t; setPadding(0, pad / 4, 0, pad / 4) }.also(root::addView)
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }.also(root::addView)
    private fun row(vararg buttons: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { (label, f) -> r.addView(Button(this).apply { text = label; setOnClickListener { f() } }) }
        root.addView(r)
    }
    /** Twin text: never learned by any keyboard, no cloud AI, no "Save note" (KEYBOARD-028). */
    private fun field(hint: String, lines: Int) = EditText(this).apply {
        this.hint = hint
        minLines = lines
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun confirm(message: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("Continue") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }
    private fun date(ms: Long) = DateFormat.format("yyyy-MM-dd", Date(ms)).toString()

    companion object {
        private const val TAG = "TwinMemory"
        const val NOTICE =
            "Your twin answers only from memories you approve, plus your two core memories. Every answer shows where it " +
                "came from, and the twin won't claim you said something without a source. Memories are encrypted on this " +
                "phone and never leave it. Deleting a journal answer also deletes the memories made from it."
    }
}
