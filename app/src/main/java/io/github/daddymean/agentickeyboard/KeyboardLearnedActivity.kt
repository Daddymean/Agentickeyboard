package io.github.daddymean.agentickeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardDbResetResult
import io.github.daddymean.agentickeyboard.db.KeyboardDbUserStatus
import io.github.daddymean.agentickeyboard.learned.KeyboardEntry
import io.github.daddymean.agentickeyboard.learned.KeyboardLearnedData
import io.github.daddymean.agentickeyboard.learned.KeyboardStore
import io.github.daddymean.agentickeyboard.learned.deletedMessage
import io.github.daddymean.agentickeyboard.twin.secureTwinWindow
import io.github.daddymean.agentickeyboard.twin.showSecure
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.LearnedRuleCleanup
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * KEYBOARD-047: what the keyboard itself has learned (learned words, next-word pairs,
 * auto-correct rules, the writing log, per-app styles, clipboard history, touch calibration
 * and more; see [KeyboardStore]). Per-store counts, entries, delete one, clear a store, clear
 * everything. Every delete asks first. The twin's data has its own screen (TwinLearnedActivity).
 *
 * On this phone only: no network, no new permission, nothing logged but static messages.
 * FLAG_SECURE on the window and every dialog; the search field opts out of keyboard learning.
 */
class KeyboardLearnedActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: LinearLayout
    private var counts: Map<KeyboardStore, Int> = emptyMap()
    private val open = HashMap<KeyboardStore, List<KeyboardEntry>>()
    private val limits = HashMap<KeyboardStore, Int>()
    private var query = ""
    private val pad by lazy { (20 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureTwinWindow()
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        scope.cancel()
        open.clear()
        super.onDestroy()
    }

    private fun data(): KeyboardLearnedData = data(this)

    private fun reload() {
        scope.launch {
            val shown = open.keys.toList()
            runCatching {
                withContext(Dispatchers.IO) {
                    val d = data()
                    d.counts() to shown.associateWith { d.entries(it, limits[it] ?: PAGE) }
                }
            }.onSuccess { (c, e) ->
                counts = c
                open.clear(); open.putAll(e)
            }.onFailure {
                SafeLog.w(TAG, "Keyboard data unreadable", it)
                toast("Couldn't read the keyboard's data.")
            }
            render()
        }
    }

    // ---- screen ---------------------------------------------------------------------------

    private fun render() {
        root.removeAllViews()
        text("What the keyboard has learned", 22f)
        text(NOTICE, 14f)
        KeyboardDbUserStatus.banner(AppDatabase.lastStatus)?.let { text(it, 14f) }
        val kept = AppDatabase.keptOlderCopyCount(this)
        if (kept > 0) {
            text(if (kept == 1) "1 older copy is kept on the phone." else "$kept older copies are kept on the phone.", 14f)
            button("Delete kept older copies") { confirmDeleteKeptCopies() }
        }
        KeyboardDbUserStatus.resetLabel(AppDatabase.lastStatus)?.let { label ->
            button(label) { confirmReset() }
        }
        val search = field("Filter the lists you've opened").apply { setText(query); root.addView(this) }
        row("Filter" to { query = search.text.toString().trim(); render() }, "Clear filter" to { query = ""; render() })
        for (store in KeyboardStore.values()) {
            val n = counts[store] ?: 0
            text("${store.title} ($n)", 18f)
            text(store.what, 12f)
            if (n == 0) continue
            val entries = open[store]
            if (entries == null) {
                row("Show" to { show(store, PAGE) }, "Clear all ${store.title.lowercase()}" to { confirmClear(store) })
                continue
            }
            row("Hide" to { open.remove(store); render() }, "Clear all ${store.title.lowercase()}" to { confirmClear(store) })
            val filtered = if (query.isEmpty()) entries else entries.filter { it.text.contains(query, true) || it.detail.contains(query, true) }
            if (filtered.isEmpty()) text("No matches in what's shown.", 13f)
            filtered.forEach(::entryView)
            if (entries.size < n) button("Show more ${store.title.lowercase()}") { show(store, (limits[store] ?: PAGE) + PAGE) }
        }
        button("Clear everything the keyboard has learned") { confirmClearAll() }
        button("Back") { finish() }
    }

    private fun show(store: KeyboardStore, limit: Int) {
        limits[store] = limit
        open[store] = emptyList()
        reload()
    }

    private fun entryView(e: KeyboardEntry) {
        root.addView(TextView(this).apply {
            textSize = 15f; text = e.text
            // Nothing to copy out of this screen.
            setTextIsSelectable(false); isLongClickable = false
        })
        text(e.detail, 12f)
        button("Delete") { confirmDelete(e) }
    }

    // ---- deletes, each confirmed ---------------------------------------------------------

    private fun confirmDelete(e: KeyboardEntry) = confirm(
        // Name the entry (#231 review 2, note 3); the dialog is FLAG_SECURE via showSecure().
        "Delete \u201C${preview(e.text)}\u201D from ${e.store.title.lowercase()}?" +
            if (e.store == KeyboardStore.WORDS) " Its next-word pairs go too." else ""
    ) { it.deleteCounted(e) }

    private fun confirmClear(store: KeyboardStore) = confirm(
        "Delete all ${counts[store] ?: 0} from ${store.title.lowercase()}?" +
            when (store) {
                KeyboardStore.CLIPBOARD -> " Pinned clips go too."
                KeyboardStore.WORDS -> " All ${counts[KeyboardStore.WORD_PAIRS] ?: 0} next-word pairs go too."
                else -> ""
            }
    ) { it.clearCounted(store) }

    private fun confirmClearAll() {
        val parts = KeyboardStore.values().filter { (counts[it] ?: 0) > 0 }.map { "${counts[it]} ${it.title.lowercase()}" }
        if (parts.isEmpty()) { toast("Nothing to delete."); return }
        confirm("Delete everything the keyboard has learned: ${parts.joinToString(", ")}? This also deletes any older copies kept on the phone. The twin's data isn't touched; it has its own screen.") {
            val result = it.clearAll()
            AppDatabase.deleteKeptOlderCopies(this@KeyboardLearnedActivity)
            result
        }
    }

    private fun confirmDeleteKeptCopies() {
        // Own showSecure dialog: this must work even in the in-memory fallback, and must
        // never go through confirm() or the live-database refusal (those protect the live database).
        AlertDialog.Builder(this).setTitle("Delete older copies?")
            .setMessage(KeyboardDbUserStatus.DELETE_KEPT_EXPLAIN)
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        AppDatabase.deleteKeptOlderCopies(this@KeyboardLearnedActivity)
                    }
                    toast(if (deleted) "Older copies were deleted." else "Nothing to delete.")
                    reload()
                }
            }
            .setNegativeButton("Cancel", null).showSecure()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Start a new encrypted database?")
            .setMessage(KeyboardDbUserStatus.RESET_EXPLAIN)
            .setPositiveButton("Start new") { _, _ ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { AppDatabase.resetBrokenDatabase(this@KeyboardLearnedActivity) }
                    toast(when (result) {
                        KeyboardDbResetResult.CreatedNew -> "Started a new encrypted database. The old file was moved aside, not deleted."
                        KeyboardDbResetResult.KeyWorksAgain -> "The keyboard opened its encrypted data again. Nothing was moved."
                        KeyboardDbResetResult.Failed -> "Couldn't start a new database. Try again later."
                    })
                    reload()
                }
            }
            .setNegativeButton("Cancel", null).showSecure()
    }

    /** Shows what goes and waits for "Delete". Only then runs [run] off the main thread and reloads. */
    private fun confirm(message: String, run: suspend (KeyboardLearnedData) -> Map<KeyboardStore, Int>) {
        KeyboardDbUserStatus.refuseDelete(AppDatabase.lastStatus)?.let { toast(it); return }
        AlertDialog.Builder(this).setTitle("Delete for good?")
            .setMessage("$message\n\n$AFTER")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { run(data()) } }
                        .onSuccess { toast(deletedMessage(it)) }
                        .onFailure { SafeLog.w(TAG, "Keyboard data delete failed", it); toast("Some items may not have been deleted. Check the list and try again.") }
                    reload()
                }
            }
            .setNegativeButton("Cancel", null).showSecure()
    }

    // ---- ui helpers ------------------------------------------------------------------------

    private fun text(t: String, size: Float) = TextView(this).apply { textSize = size; text = t; setPadding(0, pad / 4, 0, pad / 4) }.also(root::addView)
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }.also(root::addView)
    private fun row(vararg buttons: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { (label, f) -> r.addView(Button(this).apply { text = label; setOnClickListener { f() } }) }
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(r) })
    }
    /** Never learned by any keyboard. */
    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    /** One line, at most [PREVIEW_CHARS] characters, for a confirm dialog. */
    private fun preview(text: String): String {
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length <= PREVIEW_CHARS) line else line.take(PREVIEW_CHARS - 1).trimEnd() + "\u2026"
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        private const val PREVIEW_CHARS = 60
        private const val TAG = "KeyboardLearned"
        private const val PAGE = 50
        const val AFTER = "This deletes it from this phone and can't be undone. The keyboard stops using it right away. " +
            "Keyboard Passport or personal-model exports you already saved aren't changed."
        const val NOTICE =
            "Everything the keyboard keeps on this phone from how you type: words, predictions, corrections, clipboard " +
                "history and more. Open a list to see it, delete one entry, clear a list, or clear everything. Each delete " +
                "asks first. To stop new learning, use Pause learning in Settings. The legacy twin keeps its own data; see " +
                "\"What the twin has learned\". Nothing here leaves the phone."

        fun data(context: Context) = KeyboardLearnedData(
            AppDatabase.getDatabase(context),
            KeyboardSettings(context),
            context.getSharedPreferences(LearnedRuleCleanup.PREFS_NAME, Context.MODE_PRIVATE)
        )
    }
}
