package io.github.daddymean.agentickeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.daddymean.agentickeyboard.twin.TwinLearning
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.twin.deadman.CheckInStage
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManState
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManSwitch
import io.github.daddymean.agentickeyboard.twin.deadman.Rekey
import io.github.daddymean.agentickeyboard.twin.export.PassphraseArchive
import io.github.daddymean.agentickeyboard.twin.export.TwinExporter
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * KEYBOARD-027: the dead-man switch screen (Keith, relayed 2026-10-17 20:00 PT): check
 * in, see when release becomes due, set up or change the three key holders, and write the
 * release archive and its key file to a folder on this phone. Nothing is uploaded or
 * shared; Keith moves the files himself. Share cards are shown once and never stored.
 */
class DeadManActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: LinearLayout
    private var state: DeadManState? = null
    /** Cards waiting to be saved; only in memory, cleared when the screen closes. */
    private var pendingCards: Rekey? = null
    private val pad by lazy { (20 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        setContentView(ScrollView(this).apply { addView(root) })
        scope.launch { load(); home() }
    }

    override fun onDestroy() {
        pendingCards = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun load() {
        state = runCatching { TwinLearning.onWorker { TwinVault.deadMan(this).load() } }
            .onFailure { SafeLog.w(TAG, "Dead-man state unreadable", it) }.getOrNull()
    }

    private suspend fun save(s: DeadManState) {
        TwinLearning.onWorker { TwinVault.deadMan(this).save(s) }
        state = s
    }

    // ---- screens ----------------------------------------------------------------------

    private fun home() {
        clear()
        title("Dead-man switch")
        text(NOTICE, 14f)
        val s = state
        if (s == null) {
            text("Not set up yet.", 16f)
            button("Set up key holders (Guardian 1, 2 and 3)") {
                scope.launch {
                    val r = TwinLearning.onWorker { DeadManSwitch.create(DeadManSwitch.GUARDIANS, LocalDate.now()) }
                    save(r.state)
                    cardsScreen(r)
                }
            }
            return
        }
        val st = DeadManSwitch.status(s, LocalDate.now())
        text("Last check-in: ${st.lastCheckIn}. Release becomes due: ${st.releaseFrom}" + when (st.stage) {
            CheckInStage.OK -> " (${st.daysLeft} days)."
            CheckInStage.DUE_SOON -> ". Only ${st.daysLeft} days left: check in."
            CheckInStage.OVERDUE -> ". Overdue: check in now if you can."
        }, 16f)
        button("I'm here: check in") {
            scope.launch { save(DeadManSwitch.checkIn(s, LocalDate.now())); toast("Checked in."); home() }
        }
        text("Key holders (set ${s.epoch}, any ${DeadManSwitch.THRESHOLD} of ${DeadManSwitch.HOLDERS} can open): " +
            s.holders.joinToString { it.label } + ". Recipients: Kid 1–6.", 15f)
        button("Replace a key holder") { replaceScreen(s) }
        button("Reissue all share cards (a card was lost)") {
            confirm("This makes new cards for all three holders. Old cards and old key files stop matching; " +
                "give everyone their new card and replace the key file next to the archive.") {
                scope.launch {
                    val r = TwinLearning.onWorker {
                        DeadManSwitch.replaceHolder(s, 1, s.holders.first { it.x == 1 }.label, null, LocalDate.now())
                    }
                    save(r.state); cardsScreen(r)
                }
            }
        }
        button("Save the release archive and key file to this phone…") { pickFolder(REQUEST_ARCHIVE) }
    }

    private fun cardsScreen(r: Rekey) {
        pendingCards = r
        clear()
        title("Share cards (set ${r.state.epoch})")
        text("Give each holder only their own card: print it or write it down. Any ${DeadManSwitch.THRESHOLD} cards plus the " +
            "key file open the archive, so never keep two cards together. These cards are shown once and are not stored " +
            "on this phone.", 14f)
        for ((h, card) in r.cards) {
            text(h.label, 16f)
            root.addView(TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; text = card.encode(); setTextIsSelectable(false) })
        }
        button("Save the cards and the key file to a folder on this phone…") { pickFolder(REQUEST_CARDS) }
        button("Done (forget the cards)") { pendingCards = null; home() }
    }

    private fun replaceScreen(s: DeadManState) {
        clear()
        title("Replace a key holder")
        text("Choose who to replace. A kid can hold a key from their 18th birthday. Every holder gets a new card; " +
            "the archive stays as it is.", 14f)
        for (h in s.holders) button("Replace ${h.label}") {
            clear()
            title("Replace ${h.label} with…")
            val used = s.holders.map { it.label }.toSet() - h.label
            for (label in DeadManSwitch.LABELS.filter { it !in used && it != h.label }) button(label) {
                if (DeadManSwitch.isKid(label)) askBirth(s, h.x, label) else rekey(s, h.x, label, null)
            }
            button("Back") { home() }
        }
        button("Back") { home() }
    }

    private fun askBirth(s: DeadManState, x: Int, label: String) {
        val field = EditText(this).apply { hint = "YYYY-MM-DD"; inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE }
        AlertDialog.Builder(this)
            .setTitle("$label's date of birth")
            .setMessage("Used only to check that $label is 18. It isn't stored.")
            .setView(field)
            .setPositiveButton("Continue") { _, _ ->
                val birth = try { LocalDate.parse(field.text.toString().trim()) } catch (e: DateTimeParseException) { null }
                if (birth == null) toast("Use the form YYYY-MM-DD.") else rekey(s, x, label, birth)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun rekey(s: DeadManState, x: Int, label: String, birth: LocalDate?) {
        scope.launch {
            val r = runCatching { TwinLearning.onWorker { DeadManSwitch.replaceHolder(s, x, label, birth, LocalDate.now()) } }
            r.onSuccess { save(it.state); cardsScreen(it) }.onFailure { toast(it.message ?: "Couldn't change holders.") }
        }
    }

    // ---- files ------------------------------------------------------------------------

    private fun pickFolder(request: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(Intent.EXTRA_LOCAL_ONLY, true), request)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (resultCode != RESULT_OK || tree == null) return
        if (!TwinExportActivity.isLocalAuthority(tree.authority)) {
            toast("Choose a folder on this phone; cloud locations are refused.")
            return
        }
        val s = state ?: return
        scope.launch {
            val result = runCatching {
                TwinLearning.onWorker {
                    val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                    val stamp = LocalDate.now().toString()
                    when (requestCode) {
                        REQUEST_CARDS -> {
                            val r = pendingCards ?: error("no cards to save")
                            write(dir, "twin-release-key-set${r.state.epoch}.json", "application/json") {
                                it.write(r.state.keyFile.toString(2).toByteArray())
                            }
                            for ((h, card) in r.cards) {
                                write(dir, "twin-share-card-${h.label.replace(' ', '-').lowercase()}-set${r.state.epoch}.txt", "text/plain") {
                                    it.write(cardText(h.label, card.encode(), r.state.epoch).toByteArray())
                                }
                            }
                            "Saved the key file and ${r.cards.size} cards. Hand each card to its holder, then delete the card files."
                        }
                        else -> {
                            val archive = write(dir, "twin-release-$stamp${TwinExporter.FILE_EXTENSION}", "application/octet-stream") {
                                TwinExporter(TwinLearning.store(this@DeadManActivity), TwinVault.journal(this@DeadManActivity))
                                    .exportRelease(it, PassphraseArchive.dataKeyParams(s.dataKey, s.archiveId), System.currentTimeMillis())
                            }
                            contentResolver.openInputStream(archive)!!.use { TwinExporter.verifyRelease(it, s.dataKey) }
                            write(dir, "twin-release-key-set${s.epoch}.json", "application/json") {
                                it.write(s.keyFile.toString(2).toByteArray())
                            }
                            "Saved and verified the release archive and its key file (set ${s.epoch})."
                        }
                    }
                }
            }
            result.onSuccess { toast(it) }.onFailure { SafeLog.w(TAG, "Dead-man save failed", it); toast("Saving failed; nothing was sent anywhere.") }
        }
    }

    private fun <T> write(dir: Uri, name: String, mime: String, block: (java.io.OutputStream) -> T): Uri {
        val doc = DocumentsContract.createDocument(contentResolver, dir, mime, name) ?: error("could not create $name")
        try {
            contentResolver.openOutputStream(doc, "wt")!!.use { block(it) }
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, doc) }
            throw t
        }
        return doc
    }

    // ---- ui helpers -------------------------------------------------------------------

    private fun clear() = root.removeAllViews()
    private fun title(t: String) = text(t, 22f)
    private fun text(t: String, size: Float) = TextView(this).apply { textSize = size; text = t; setPadding(0, pad / 4, 0, pad / 4) }.also(root::addView)
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }.also(root::addView)
    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()
    private fun confirm(message: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("Continue") { _, _ -> ok() }.setNegativeButton("Cancel", null).show()
    }

    companion object {
        private const val TAG = "DeadMan"
        private const val REQUEST_CARDS = 4027
        private const val REQUEST_ARCHIVE = 4028

        fun cardText(label: String, card: String, epoch: Int) =
            "Agentic Keyboard legacy twin: key share card\n" +
                "Holder: $label (set $epoch)\n\n$card\n\n" +
                "Keep this card private and separate from the other cards. When Keith has not checked in for " +
                "${DeadManSwitch.RELEASE_MONTHS} months, any ${DeadManSwitch.THRESHOLD} of the ${DeadManSwitch.HOLDERS} holders " +
                "together can open the twin for Kid 1–6 with tools/twin-export/open_twin_release.py " +
                "(see tools/twin-export/RELEASE.md). One card alone reveals nothing.\n"

        const val NOTICE =
            "If you stop checking in for ${DeadManSwitch.RELEASE_MONTHS} months, your twin is meant to be opened for Kid 1–6.\n\n" +
                "• The release archive is encrypted with a random key. That key is locked by three share cards (Guardian 1–3 " +
                "for now); any ${DeadManSwitch.THRESHOLD} of them together can open it, one alone can't.\n" +
                "• A kid can take over a guardian's card from their 18th birthday. Changing a holder issues new cards; the " +
                "archive isn't re-encrypted.\n" +
                "• Nothing is sent anywhere and there is no server: this phone only tracks your check-ins. The holders apply " +
                "the ${DeadManSwitch.RELEASE_MONTHS}-month rule. You move the archive and key file off the phone yourself."
    }
}
