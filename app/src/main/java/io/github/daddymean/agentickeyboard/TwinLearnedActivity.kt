package io.github.daddymean.agentickeyboard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.daddymean.agentickeyboard.twin.ForgetPlan
import io.github.daddymean.agentickeyboard.twin.ForgetResult
import io.github.daddymean.agentickeyboard.twin.LearnedGroup
import io.github.daddymean.agentickeyboard.twin.LearnedItem
import io.github.daddymean.agentickeyboard.twin.NeverLearnLists
import io.github.daddymean.agentickeyboard.twin.TwinLearning
import io.github.daddymean.agentickeyboard.twin.TwinLedger
import io.github.daddymean.agentickeyboard.twin.TwinPreferences
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Twin-memory viewer: everything the legacy twin has learned, grouped (approved memories,
 * core memories, phrases, typed messages, journal answers), searchable, each with its
 * source, date and why it was learned. Keith deletes one item, a selection, everything
 * from one app or a date range, or a phrase; every delete asks first and reaches every
 * store ([TwinLedger]). "Never learn again" adds the app or phrase to the never-learn lists.
 *
 * On this phone only: no network, no new permission, nothing logged but static messages.
 * FLAG_SECURE like the other twin screens; every text field opts out of keyboard learning.
 */
class TwinLearnedActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: LinearLayout
    private var items: List<LearnedItem> = emptyList()
    private var query = ""
    private val selected = LinkedHashSet<String>()
    private val shownPerGroup = HashMap<LearnedGroup, Int>()
    private var loadedGeneration = -1L
    private var deleteSelected: Button? = null
    private val pad by lazy { (20 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        if (loadedGeneration != TwinLedger.generation) reload()
    }

    override fun onDestroy() {
        scope.cancel()
        items = emptyList()
        super.onDestroy()
    }

    private fun ledger() = TwinLedger(
        TwinLearning.store(this), TwinVault.memory(this), TwinVault.journal(this), TwinVault.vectors(this),
        NeverLearnLists.of(TwinPreferences(this))
    )

    private fun reload() {
        scope.launch {
            val gen = TwinLedger.generation
            items = runCatching { TwinLearning.onWorker { ledger().list() } }
                .onFailure { SafeLog.w(TAG, "Twin data unreadable", it) }.getOrDefault(emptyList())
            loadedGeneration = gen
            selected.retainAll(items.map { it.key }.toSet())
            render()
        }
    }

    // ---- screen ---------------------------------------------------------------------------

    private fun render() {
        root.removeAllViews()
        text("What the twin has learned", 22f)
        text(NOTICE, 14f)
        val search = field("Search everything the twin keeps", 1).apply { setText(query); root.addView(this) }
        row("Search" to { query = search.text.toString(); shownPerGroup.clear(); render() },
            "Clear" to { query = ""; shownPerGroup.clear(); render() })
        row("Delete from an app…" to { pickApp() }, "Delete a date range…" to { pickRange() }, "Forget a phrase…" to { askPhrase() })
        deleteSelected = button("") { confirmPlan(selected.toList()) }
        updateSelection()
        val shown = TwinLedger.search(items, query)
        if (shown.isEmpty()) text(if (items.isEmpty()) "The twin hasn't learned anything yet." else "No matches.", 15f)
        for (g in LearnedGroup.values()) {
            val inGroup = shown.filter { it.group == g }
            if (inGroup.isEmpty()) continue
            text("${g.title} (${inGroup.size})", 18f)
            val limit = shownPerGroup[g] ?: PAGE
            inGroup.take(limit).forEach(::itemView)
            if (inGroup.size > limit) button("Show more ${g.title.lowercase()}") { shownPerGroup[g] = limit + PAGE; render() }
        }
        button("Back") { finish() }
    }

    private fun updateSelection() {
        deleteSelected?.apply {
            text = "Delete selected (${selected.size})"
            visibility = if (selected.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun itemView(item: LearnedItem) {
        val meta = listOfNotNull(
            item.atMillis.takeIf { it > 0 }?.let { DateFormat.format("yyyy-MM-dd HH:mm", Date(it)).toString() },
            if (item.sourcePackage != null && item.sourcePackage != item.source) "${item.source} (${item.sourcePackage})" else item.source
        ).joinToString(" · ")
        root.addView(CheckBox(this).apply {
            text = meta
            textSize = 12f
            isChecked = item.key in selected
            setOnCheckedChangeListener { _, on -> if (on) selected += item.key else selected -= item.key; updateSelection() }
        })
        root.addView(TextView(this).apply {
            textSize = 15f; text = item.text
            // Nothing to copy out of a twin screen (KEYBOARD-033).
            setTextIsSelectable(false); isLongClickable = false
        })
        item.why?.let { text("Why: $it", 12f) }
        if (item.group == LearnedGroup.PHRASES) {
            row("Delete messages with it" to { confirmPlan(listOf(item.key)) }, "Forget, never learn again" to { confirmPhrase(item.text, true) })
        } else {
            button("Delete") { confirmPlan(listOf(item.key)) }
        }
    }

    // ---- deletes, each confirmed ---------------------------------------------------------

    private fun confirmPlan(keys: List<String>) = planThenConfirm({ it.planKeys(keys) }, null) { l, _ -> l.forgetKeys(keys) }

    private fun pickApp() {
        val apps = TwinLedger.apps(items)
        if (apps.isEmpty()) { toast("No typed messages are stored."); return }
        val labels = apps.map { (pkg, n) -> "$pkg ($n)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Delete everything learned from which app?")
            .setItems(labels) { _, i -> val pkg = apps[i].first
                planThenConfirm({ it.planApp(pkg) }, "Never learn in $pkg again") { l, never -> l.forgetApp(pkg, never) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickRange() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val from = field("From, YYYY-MM-DD", 1).also(box::addView)
        val to = field("To (included), YYYY-MM-DD", 1).also(box::addView)
        AlertDialog.Builder(this).setTitle("Delete what the twin learned in a date range")
            .setMessage("Typed messages stored and memories approved in these days. Core memories and journal answers stay.")
            .setView(box)
            .setPositiveButton("Continue") { _, _ ->
                val zone = ZoneId.systemDefault()
                val a = runCatching { LocalDate.parse(from.text.toString().trim()) }.getOrNull()
                val b = runCatching { LocalDate.parse(to.text.toString().trim()) }.getOrNull()
                if (a == null || b == null || b.isBefore(a)) { toast("Use YYYY-MM-DD, with the start on or before the end."); return@setPositiveButton }
                val start = a.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = b.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                planThenConfirm({ it.planRange(start, end) }, null) { l, _ -> l.forgetRange(start, end) }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun askPhrase() {
        val f = field("A word or phrase (up to 8 words)", 1)
        AlertDialog.Builder(this).setTitle("Forget a phrase").setView(f)
            .setPositiveButton("Continue") { _, _ -> confirmPhrase(f.text.toString(), false) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmPhrase(phrase: String, neverAgainDefault: Boolean) {
        if (io.github.daddymean.agentickeyboard.twin.NeverLearnPhrases.words(phrase).isEmpty()) { toast("Type 1 to 8 words."); return }
        planThenConfirm({ it.planPhrase(phrase) }, "Never learn messages with this phrase again", neverAgainDefault, allowEmpty = true) { l, never ->
            l.forgetPhrase(phrase, never)
        }
    }

    /**
     * Works out what a delete reaches, shows the counts and waits for Keith. [neverLabel] adds a
     * "never learn again" box. Only then deletes, on the twin worker, and reloads.
     */
    private fun planThenConfirm(
        plan: (TwinLedger) -> ForgetPlan,
        neverLabel: String?,
        neverDefault: Boolean = false,
        allowEmpty: Boolean = false,
        run: (TwinLedger, Boolean) -> ForgetResult
    ) {
        scope.launch {
            val p = runCatching { TwinLearning.onWorker { plan(ledger()) } }.getOrNull() ?: return@launch toast("Couldn't read the twin's data.")
            if (p.isEmpty && !allowEmpty) return@launch toast("Nothing to delete.")
            val never = neverLabel?.let { CheckBox(this@TwinLearnedActivity).apply { text = it; isChecked = neverDefault } }
            AlertDialog.Builder(this@TwinLearnedActivity).setTitle("Delete for good?")
                .setMessage("This deletes ${p.describe()} from this phone, with their search index entries and on-device embedding " +
                    "vectors, and any memories made from deleted journal answers. Exports and dead-man archives you save from now on " +
                    "won't include them; archives you already saved elsewhere aren't changed. This can't be undone.")
                .apply { never?.let { setView(LinearLayout(this@TwinLearnedActivity).apply { setPadding(pad, 0, pad, 0); addView(it) }) } }
                .setPositiveButton("Delete") { _, _ ->
                    val n = never?.isChecked == true
                    scope.launch {
                        runCatching { TwinLearning.onWorker { run(ledger(), n) } }
                            .onSuccess { toast("Deleted. " + summary(it, n)) }
                            .onFailure { SafeLog.w(TAG, "Twin delete failed", it); toast("Couldn't delete. Nothing was changed for items that failed.") }
                        selected.clear()
                        reload()
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun summary(r: ForgetResult, never: Boolean): String = listOfNotNull(
        "${r.messages} messages", "${r.memories + r.derivedMemories} memories", "${r.vectors} vectors",
        r.coreBlocks.takeIf { it > 0 }?.let { "$it core memories" }, r.journalRecords.takeIf { it > 0 }?.let { "$it journal answers" }
    ).joinToString(", ") + if (never) ". It won't be learned again." else "."

    // ---- ui helpers ------------------------------------------------------------------------

    private fun text(t: String, size: Float) = TextView(this).apply { textSize = size; text = t; setPadding(0, pad / 4, 0, pad / 4) }.also(root::addView)
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }.also(root::addView)
    private fun row(vararg buttons: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEach { (label, f) -> r.addView(Button(this).apply { text = label; setOnClickListener { f() } }) }
        root.addView(android.widget.HorizontalScrollView(this).apply { addView(r) })
    }
    /** Twin text: never learned by any keyboard, no cloud AI (KEYBOARD-028). */
    private fun field(hint: String, lines: Int) = EditText(this).apply {
        this.hint = hint
        minLines = lines
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "TwinLearned"
        private const val PAGE = 50
        const val NOTICE =
            "Everything your twin keeps on this phone, with where and when it came from and why. Tick items to delete several, " +
                "or delete everything from one app, from a date range, or every message with a phrase. Each delete asks first and " +
                "removes the item from every place the twin keeps it: the encrypted stores, the search index and the embedding " +
                "vectors. Nothing here leaves the phone."
    }
}
