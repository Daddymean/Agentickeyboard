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
import android.view.inputmethod.EditorInfo
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
import io.github.daddymean.agentickeyboard.twin.deadman.IamStage
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
 * KEYBOARD-027: the dead-man switch screen (Keith, relayed 2026-10-17 20:00 PT; Google
 * Inactive Account Manager trigger 2026-10-18 08:17 PT): confirm the Google plan, check in
 * (moves the fallback date), set up or change the three key holders, and write the
 * release archive and its key file to a folder on this phone. Nothing is uploaded or
 * shared; Keith moves the files himself. Share cards are shown once and never stored.
 */
class DeadManActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var root: LinearLayout
    private var state: DeadManState? = null
    /**
     * A new holder set waiting for Keith to confirm its cards; only in memory. Nothing is
     * saved until he does, so if the screen closes first the current set stays in force
     * (KEYBOARD-036).
     */
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
                    cardsScreen(r)
                }
            }
            return
        }
        val st = DeadManSwitch.status(s, LocalDate.now())
        if (s.needsNewCards) {
            text("Action needed: this app now gives every release archive its own key, so that people who held a key " +
                "before a holder change can't open later archives. Reissue the share cards once to switch over; saving " +
                "a new archive is off until then. Your existing archive and cards keep working.", 16f)
        }
        text("Trigger: Google Inactive Account Manager. " + when (st.iamStage) {
            IamStage.NOT_CONFIRMED -> "Not confirmed for the current key holders. Set it up so Google emails them after " +
                "${DeadManSwitch.IAM_INACTIVE_MONTHS} months of inactivity."
            IamStage.OK -> "Confirmed on ${st.iamConfirmedOn}; review it by ${st.iamReviewDue}. Using your Google account " +
                "resets Google's timer; this phone can't see it."
            IamStage.REVIEW_DUE -> "Last confirmed on ${st.iamConfirmedOn}. Review it now: Google may have changed it, " +
                "or your contacts may be out of date."
        }, 16f)
        button(if (st.iamStage == IamStage.NOT_CONFIRMED) "Set up the Google trigger…" else "Review the Google trigger…") { iamScreen(s) }
        text("Fallback if Google can't send the email: the holders wait until Keith is out of contact for " +
            "${DeadManSwitch.RELEASE_MONTHS} months, never before ${st.releaseFrom} (last check-in here ${st.lastCheckIn}" +
            when (st.stage) {
                CheckInStage.OK -> ", ${st.daysLeft} days left)."
                CheckInStage.DUE_SOON -> ", only ${st.daysLeft} days left: check in)."
                CheckInStage.OVERDUE -> ", passed: check in now)."
            } + " Save the key file again after checking in so the date it carries is current.", 15f)
        button("I'm here: check in (moves the fallback date)") {
            scope.launch { save(DeadManSwitch.checkIn(s, LocalDate.now())); toast("Checked in."); home() }
        }
        text("Key holders (set ${s.epoch}, any ${DeadManSwitch.THRESHOLD} of ${DeadManSwitch.HOLDERS} can open): " +
            s.holders.joinToString { it.label } + ". Recipients: Kid 1–6.", 15f)
        button("Replace a key holder") { replaceScreen(s) }
        button("Reissue all share cards (a card was lost)") {
            confirm("This makes new cards for all three holders. Old cards and old key files stop matching; " +
                "give everyone their new card and replace the key file next to the archive.") {
                scope.launch {
                    runCatching { TwinLearning.onWorker { DeadManSwitch.reissue(s, LocalDate.now()) } }
                        .onSuccess { cardsScreen(it) }.onFailure { toast(it.message ?: "Couldn't reissue the cards.") }
                }
            }
        }
        if (!s.needsNewCards) button("Save a new release archive and its key file to this phone…") { pickFolder(REQUEST_ARCHIVE) }
    }

    private fun iamScreen(s: DeadManState) {
        clear()
        title("Google trigger")
        text(IAM_STEPS, 14f)
        text("Subject:", 15f)
        selectable(DeadManSwitch.IAM_SUBJECT)
        text("Message:", 15f)
        selectable(DeadManSwitch.IAM_MESSAGE)
        text("Never put a share card, passphrase, key file or archive in this message, in Google Drive or in any data " +
            "you share through the plan.", 14f)
        button("I've set it up this way for ${s.holders.joinToString { it.label }}") {
            scope.launch { save(DeadManSwitch.confirmIam(s, LocalDate.now())); toast("Recorded. Review it by " +
                DeadManSwitch.iamReviewDue(LocalDate.now()) + "."); home() }
        }
        button("Back") { home() }
    }

    private fun cardsScreen(r: Rekey) {
        pendingCards = r
        clear()
        title("Share cards (set ${r.state.epoch})")
        text("Give each holder only their own card: print it or write it down. Any ${DeadManSwitch.THRESHOLD} cards plus the " +
            "key file open the archive, so never keep two cards together. These cards are shown once and are not stored " +
            "on this phone. Nothing changes until you confirm below; if you leave this screen first, the current cards " +
            "stay in force.", 14f)
        for ((h, card) in r.cards) {
            text(h.label, 16f)
            root.addView(TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE; text = card.encode(); setTextIsSelectable(false) })
        }
        if (r.state.iamConfirmedOn == null) {
            text("The key holders changed: add the new holder as a contact in Google Inactive Account Manager (and remove " +
                "the old one), then confirm the Google trigger again.", 14f)
        }
        button("Save the cards and the key file to a folder on this phone…") { pickFolder(REQUEST_CARDS) }
        text("To confirm you have copied every card, type the last 8 characters of each one (after its last dash):", 14f)
        val checks = r.cards.map { (h, _) ->
            EditText(this).apply {
                hint = "${h.label}: last 8 characters"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING // KEYBOARD-028: twin screen, never learned or sent
            }.also(root::addView)
        }
        button("Confirm: I have every card (start using this set)") {
            if (!DeadManSwitch.cardsConfirmed(r, checks.map { it.text.toString() })) {
                toast("A check doesn't match its card. Copy the card again and retype it.")
                return@button
            }
            scope.launch {
                runCatching { save(r.state) }
                    .onSuccess { pendingCards = null; toast("Set ${r.state.epoch} is now in use."); home() }
                    .onFailure { SafeLog.w(TAG, "Dead-man save failed", it); toast("Couldn't save the new set; the current cards stay in force.") }
            }
        }
        button("Cancel (keep the current cards)") {
            pendingCards = null
            toast("Nothing changed. Delete any card or key files you saved for this set.")
            home()
        }
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
        val field = EditText(this).apply {
            hint = "YYYY-MM-DD"; inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING // KEYBOARD-028: twin screen, never learned or sent
        }
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
            r.onSuccess { cardsScreen(it) }.onFailure { toast(it.message ?: "Couldn't change holders.") }
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
        val s = state
        if (requestCode == REQUEST_ARCHIVE && (s == null || s.needsNewCards)) return
        scope.launch {
            val result = runCatching {
                TwinLearning.onWorker<Pair<String, DeadManState?>> {
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
                            "Saved the key file and ${r.cards.size} cards. Hand each card to its holder, then delete the card files." to null
                        }
                        else -> {
                            // KEYBOARD-036: every archive gets its own data key, wrapped under the current set's KEK.
                            val next = DeadManSwitch.newArchive(s!!)
                            val archive = write(dir, "twin-release-$stamp${TwinExporter.FILE_EXTENSION}", "application/octet-stream") {
                                TwinExporter(TwinLearning.store(this@DeadManActivity), TwinVault.journal(this@DeadManActivity), TwinVault.memory(this@DeadManActivity))
                                    .exportRelease(it, PassphraseArchive.dataKeyParams(next.dataKey, next.archiveId), System.currentTimeMillis())
                            }
                            contentResolver.openInputStream(archive)!!.use { TwinExporter.verifyRelease(it, next.dataKey) }
                            write(dir, "twin-release-$stamp-key-set${next.epoch}.json", "application/json") {
                                it.write(next.keyFile.toString(2).toByteArray())
                            }
                            ("Saved and verified the release archive and its key file (set ${next.epoch}). Replace older " +
                                "archives and their key files with these.") to next
                        }
                    }
                }
            }
            result.mapCatching { (message, next) -> next?.let { save(it) }; message }
                .onSuccess { toast(it) }.onFailure { SafeLog.w(TAG, "Dead-man save failed", it); toast("Saving failed; nothing was sent anywhere.") }
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
    private fun selectable(t: String) = TextView(this).apply {
        textSize = 14f; typeface = Typeface.MONOSPACE; text = t; setTextIsSelectable(true); setPadding(0, pad / 4, 0, pad / 4)
    }.also(root::addView)
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
                "Keep this card private and separate from the other cards. Never type it into an email, a website or " +
                "Google. One card alone reveals nothing.\n\n" +
                "When to use it: Google's Inactive Account Manager emails the key holders after Keith's Google account " +
                "has been inactive for ${DeadManSwitch.IAM_INACTIVE_MONTHS} months. That email is only a signal, not a key.\n" +
                "1. Check it is genuine: the sender is a google.com address; in Gmail, the message details show " +
                "mailed-by and signed-by google.com (or Show original shows SPF, DKIM and DMARC PASS for google.com); " +
                "the footer names Keith's own address; the text matches what Keith showed you. Don't click links in it.\n" +
                "2. Phone the other holders on numbers you already know, and try to reach Keith and the family. A real " +
                "email reaches every holder; if only you got one, stop.\n" +
                "3. Only if ${DeadManSwitch.THRESHOLD} of you agree, combine two cards with the key file and the archive " +
                "using tools/twin-export/open_twin_release.py, for Kid 1–6 (see tools/twin-export/RELEASE.md).\n" +
                "If Google can't send the email (the service changed or the account was closed), the same steps apply " +
                "once Keith has been out of contact for ${DeadManSwitch.RELEASE_MONTHS} months, never before the key " +
                "file's not_before date.\n"

        const val IAM_STEPS =
            "Set this up in your Google Account at ${DeadManSwitch.IAM_PAGE} (Google's Inactive Account Manager page):\n" +
                "1. Wait period: ${DeadManSwitch.IAM_INACTIVE_MONTHS} months. Google offers 3, 6, 12 or 18 and alerts you by " +
                "text and email about a month before. Keep your recovery phone and email current.\n" +
                "2. Contacts: each current key holder's email (up to 10 contacts are allowed). Choose notify only: don't " +
                "share data with them.\n" +
                "3. Subject and message: the text below. Google adds a footer naming your address.\n" +
                "4. Leave account deletion off, or understand that it deletes the account 3 months after the trigger.\n" +
                "Signing in, using Gmail, My Activity or an Android phone signed in to the account resets Google's timer. " +
                "This app can't check the plan; it only records that you confirmed it."

        const val NOTICE =
            "When your Google account has been inactive for ${DeadManSwitch.IAM_INACTIVE_MONTHS} months, Google emails the " +
                "key holders, and your twin is meant to be opened for Kid 1–6.\n\n" +
                "• The release archive is encrypted with a random key. That key is locked by three share cards (Guardian 1–3 " +
                "for now); any ${DeadManSwitch.THRESHOLD} of them together can open it, one alone can't.\n" +
                "• A kid can take over a guardian's card from their 18th birthday. Changing a holder issues new cards; the " +
                "archive isn't re-encrypted.\n" +
                "• Google's email is only a signal: it holds no key, and nothing secret goes into Google. The holders check " +
                "it, confirm with each other, and only then combine 2 cards.\n" +
                "• If Google can't send it, the holders fall back to ${DeadManSwitch.RELEASE_MONTHS} months without contact.\n" +
                "• This app sends nothing and there is no server. You move the archive and key file off the phone yourself."
    }
}
