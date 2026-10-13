package io.github.daddymean.agentickeyboard

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.daddymean.agentickeyboard.twin.TwinEntry
import io.github.daddymean.agentickeyboard.twin.TwinLearning
import io.github.daddymean.agentickeyboard.twin.TwinPreferences
import io.github.daddymean.agentickeyboard.twin.TwinStoragePolicy
import io.github.daddymean.agentickeyboard.twin.TwinStyleStats
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/**
 * KEYBOARD-024: the legacy twin's settings, viewer and delete controls.
 *
 * Shows stored entries, so the window is FLAG_SECURE (no screenshots, blank in
 * Recents). Every store call runs on the twin worker, never on the main thread.
 */
class TwinActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: TwinPreferences
    private lateinit var status: TextView
    private lateinit var style: TextView
    private lateinit var entries: LinearLayout
    private lateinit var more: Button
    private var shown = 0

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        prefs = TwinPreferences(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply { textSize = 22f; text = "Legacy twin" })
        layout.addView(TextView(this).apply { textSize = 15f; text = PRIVACY_NOTICE })
        layout.addView(Button(this).apply {
            text = "Journal & interview"
            setOnClickListener { startActivity(android.content.Intent(this@TwinActivity, JournalActivity::class.java)) }
        })
        layout.addView(Button(this).apply {
            text = "Encrypted export (stays on this phone)"
            setOnClickListener { startActivity(android.content.Intent(this@TwinActivity, TwinExportActivity::class.java)) }
        })
        layout.addView(switch("Learn for my twin", prefs.isEnabled) { _, on -> prefs.isEnabled = on; refresh() })
        layout.addView(switch("Pause twin learning", prefs.isPaused) { _, on -> prefs.isPaused = on; refresh() })
        status = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad / 2) }
        layout.addView(status)
        layout.addView(TextView(this).apply { textSize = 18f; text = "Your writing style" })
        style = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, pad / 2) }
        layout.addView(style)
        layout.addView(TextView(this).apply { textSize = 18f; text = "Recent entries" })
        entries = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(entries)
        more = Button(this).apply { text = "Show more"; setOnClickListener { loadPage() } }
        layout.addView(more)
        layout.addView(Button(this).apply {
            text = "Delete all twin data"
            setOnClickListener { confirmDeleteAll() }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun switch(label: String, checked: Boolean, onChange: CompoundButton.OnCheckedChangeListener) =
        Switch(this).apply {
            textSize = 16f
            text = label
            isChecked = checked
            setOnCheckedChangeListener(onChange)
        }

    private fun refresh() {
        entries.removeAllViews()
        shown = 0
        scope.launch {
            val (count, bytes, stats) = runCatching {
                TwinLearning.onWorker {
                    val store = TwinLearning.store(this@TwinActivity)
                    val builder = TwinStyleStats.Builder(top = 10)
                    store.forEach { builder.add(it.text) }
                    Triple(store.count(), store.fileBytes(), builder.build())
                }
            }.onFailure { SafeLog.w(TAG, "Twin store unavailable", it) }.getOrNull() ?: Triple(0, 0L, null)
            val learningPaused = KeyboardSettings(this@TwinActivity).isLearningPaused
            val tier = TwinStoragePolicy.tier(bytes)
            status.text = buildString {
                append(
                    when {
                        !prefs.isEnabled -> "Off: nothing new is learned."
                        prefs.isPaused -> "Paused: nothing new is learned until you resume."
                        learningPaused -> "Paused by \"Pause learning\" in keyboard settings."
                        prefs.isLowStoragePaused -> "Paused: " + TwinStoragePolicy.LOW_STORAGE_NOTICE
                        else -> "Learning from messages you type and send."
                    }
                )
                append("\n$count messages stored, ${"%.1f".format(Locale.US, bytes / (1024.0 * 1024.0))} MB encrypted on this phone. ")
                append("Nothing is ever deleted automatically; only you can delete entries.")
                if (tier >= 1) append("\n\n⚠ " + TwinStoragePolicy.sizeNotice(tier))
            }
            style.text = stats?.let(::describe) ?: "Nothing learned yet."
            loadPage()
        }
    }

    private fun loadPage() {
        val offset = shown
        scope.launch {
            val page = runCatching {
                TwinLearning.onWorker { TwinLearning.store(this@TwinActivity).recent(PAGE, offset) }
            }.getOrDefault(emptyList())
            page.forEach { entries.addView(row(it)) }
            shown += page.size
            more.isEnabled = page.size == PAGE
        }
    }

    private fun row(entry: TwinEntry): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 12, 0, 12)
        val whenText = DateFormat.format("yyyy-MM-dd HH:mm", Date(entry.atMillis))
        addView(TextView(context).apply {
            textSize = 12f
            text = "$whenText · ${entry.packageName ?: "unknown app"}"
        })
        addView(TextView(context).apply { textSize = 15f; text = entry.text })
        addView(Button(context).apply {
            text = "Delete"
            setOnClickListener {
                scope.launch {
                    TwinLearning.onWorker { TwinLearning.store(this@TwinActivity).delete(entry.id) }
                    refresh()
                }
            }
        })
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this)
            .setTitle("Delete all twin data?")
            .setMessage(
                "This deletes every stored message, every journal answer, recording and result, and destroys " +
                    "the encryption key on this phone. " +
                    "It cannot be undone. Learning stays on unless you turn it off."
            )
            .setPositiveButton("Delete everything") { _, _ ->
                scope.launch {
                    runCatching { TwinLearning.onWorker { TwinVault.deleteAll(this@TwinActivity) } }
                        .onFailure { SafeLog.w(TAG, "Twin delete-all failed", it) }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun describe(s: TwinStyleStats): String {
        if (s.messages == 0) return "Nothing learned yet."
        fun list(items: List<Pair<String, Int>>) =
            items.joinToString(", ") { "${it.first} (${it.second})" }.ifEmpty { "-" }
        val punct = s.punctuationPer100Messages.entries.filter { it.value > 0 }
            .joinToString(", ") { "${it.key} ${"%.0f".format(Locale.US, it.value)}" }.ifEmpty { "-" }
        return """
            |${s.messages} messages, ${s.words} words, ${"%.1f".format(Locale.US, s.avgWordsPerSentence)} words per sentence.
            |Top words: ${list(s.topWords)}
            |Phrases: ${list(s.topBigrams + s.topTrigrams)}
            |Emoji: ${list(s.topEmoji)}
            |Punctuation per 100 messages: $punct
            |Formality: ${"%.0f".format(Locale.US, s.formality * 100)}/100 (contractions ${"%.1f".format(Locale.US, s.contractionsPer100Words)} per 100 words, lower-case starts ${"%.0f".format(Locale.US, s.lowercaseStartShare * 100)}%)
        """.trimMargin()
    }

    companion object {
        private const val TAG = "TwinActivity"
        private const val PAGE = 50

        const val PRIVACY_NOTICE =
            "Your legacy twin learns how you write so that, one day, it can talk with your kids in your own words.\n\n" +
                "What it keeps: messages you type on this keyboard and send, in any app, with the app's name and the time. " +
                "A message counts as sent when you press the keyboard's Send or Go key, or when a chat app clears the box " +
                "after you typed it. When the keyboard is unsure, it does not save.\n\n" +
                "Never saved: passwords, PINs, codes and other secrets in common forms. Any message that mentions a " +
                "password, PIN, code, login, token, key, secret or similar (also joined, like accessToken or " +
                "securitycode), or contains a token or key, is skipped whole. Code- or config-like text (JSON, " +
                "key=value lines, settings, code blocks, links carrying a key or token) is never saved either. Rare " +
                "formats are best effort, and a secret typed on its own with no word like \"password\" around it " +
                "can't be recognised, so don't type secrets bare. Also never saved: anything typed " +
                "in password, private or incognito fields; anything while learning or the twin is paused; other " +
                "people's messages (it never reads the screen for this); any message that contains pasted, pre-filled, " +
                "inserted or AI-written text (the whole message is skipped); text you select and cut or clear.\n\n" +
                "Blanked, best effort: other personal identifiers, such as ID, passport, account, member, booking or " +
                "reference numbers, card numbers, e-mail addresses, phone numbers and links, are replaced with a marker " +
                "like [ID] before saving, and the rest of the message is kept. This is automatic and some identifiers " +
                "may be missed, so check the viewer below.\n\n" +
                "Where it lives: only on this phone, encrypted with a key kept in Android's secure key store. It is not " +
                "backed up, not transferred to a new phone and never sent to the cloud. You can view every saved entry " +
                "here, delete single entries, or delete everything, which also destroys the key. You can also turn it " +
                "off or pause it.\n\n" +
                "Encrypted export: only when you start it. It writes one passphrase-encrypted file to a folder on this " +
                "phone, scrubbed again more strictly first; nothing is uploaded. Copying it off the phone is up to you, " +
                "and delete-all doesn't reach copies you made. (The dead-man hand-off is a later version.)"
    }
}
