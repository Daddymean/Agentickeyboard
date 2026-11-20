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
import io.github.daddymean.agentickeyboard.learned.KeyboardEntry
import io.github.daddymean.agentickeyboard.learned.KeyboardLearnedData
import io.github.daddymean.agentickeyboard.learned.KeyboardStore
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
        "Delete this from ${e.store.title.lowercase()}?" + if (e.store == KeyboardStore.WORDS) " Its next-word pairs go too." else ""
    ) { it.delete(e) }

    private fun confirmClear(store: KeyboardStore) = confirm(
        "Delete all ${counts[store] ?: 0} from ${store.title.lowercase()}?" +
            if (store == KeyboardStore.CLIPBOARD) " Pinned clips go too." else ""
    ) { it.clear(store) }

    private fun confirmClearAll() {
        val parts = KeyboardStore.values().filter { (counts[it] ?: 0) > 0 }.map { "${counts[it]} ${it.title.lowercase()}" }
        if (parts.isEmpty()) { toast("Nothing to delete."); return }
        confirm("Delete everything the keyboard has learned: ${parts.joinToString(", ")}? The twin's data isn't touched; it has its own screen.") {
            it.clearAll().values.sum()
        }
    }

    /** Shows what goes and waits for "Delete". Only then runs [run] off the main thread and reloads. */
    private fun confirm(message: String, run: suspend (KeyboardLearnedData) -> Int) {
        AlertDialog.Builder(this).setTitle("Delete for good?")
            .setMessage("$message\n\n$AFTER")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { run(data()) } }
                        .onSuccess { toast(if (it == 0) "Nothing was left to delete." else "Deleted $it.") }
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
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
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
