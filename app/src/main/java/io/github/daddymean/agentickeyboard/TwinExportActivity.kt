package io.github.daddymean.agentickeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.daddymean.agentickeyboard.twin.TwinLearning
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.twin.export.PassphraseArchive
import io.github.daddymean.agentickeyboard.twin.export.TwinExporter
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KEYBOARD-026: user-initiated encrypted export of the legacy twin (Keith's decision,
 * relayed 2026-10-12 17:12 PT: the backup stays encrypted on this device until he copies
 * it to his training machine by hand; no upload, no automatic sharing).
 *
 * Keith types a passphrase twice (never stored), picks where to save on this phone with
 * the system file picker, and the archive is written and then verified by reading it
 * back. Only on-device document providers are accepted; cloud providers are refused and
 * the file they created is deleted. There is no network or share path.
 */
class TwinExportActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var first: EditText
    private lateinit var second: EditText
    private lateinit var status: TextView
    private lateinit var export: Button
    private var pending: CharArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        layout.addView(TextView(this).apply { textSize = 22f; text = "Encrypted export" })
        layout.addView(TextView(this).apply { textSize = 15f; text = NOTICE })
        first = passphraseField("Passphrase (at least ${PassphraseArchive.MIN_PASSPHRASE} characters)")
        second = passphraseField("Type it again")
        layout.addView(first)
        layout.addView(second)
        export = Button(this).apply { text = "Choose where to save on this phone…"; setOnClickListener { start() } }
        layout.addView(export)
        status = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, 0) }
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onDestroy() {
        pending?.fill('\u0000')
        pending = null
        scope.cancel()
        super.onDestroy()
    }

    private fun passphraseField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
    }

    private fun takePassphrase(field: EditText): CharArray {
        val e = field.text
        val out = CharArray(e.length).also { e.getChars(0, e.length, it, 0) }
        e.clear()
        return out
    }

    private fun start() {
        val a = takePassphrase(first)
        val b = takePassphrase(second)
        val problem = problem(a, b)
        b.fill('\u0000')
        if (problem != null) { a.fill('\u0000'); status.text = problem; return }
        pending?.fill('\u0000')
        pending = a
        val name = "twin-export-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + TwinExporter.FILE_EXTENSION
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, name)
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CREATE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE) return
        val passphrase = pending
        pending = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || passphrase == null) {
            passphrase?.fill('\u0000'); status.text = "Export cancelled."; return
        }
        if (!isLocalProvider(uri)) {
            passphrase.fill('\u0000')
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
            status.text = "That location isn't on this phone. Choose a folder on the phone (for example Downloads); " +
                "cloud locations are refused so nothing leaves the device."
            return
        }
        run(uri, passphrase)
    }

    private fun run(uri: Uri, passphrase: CharArray) {
        export.isEnabled = false
        status.text = "Encrypting… this takes a little while."
        scope.launch {
            val result = runCatching {
                TwinLearning.onWorker {
                    val exporter = TwinExporter(TwinLearning.store(this@TwinExportActivity), TwinVault.journal(this@TwinExportActivity))
                    val summary = contentResolver.openOutputStream(uri, "wt")!!.use { exporter.export(it, passphrase, System.currentTimeMillis()) }
                    contentResolver.openInputStream(uri)!!.use { TwinExporter.verify(it, passphrase) }
                    summary
                }
            }
            passphrase.fill('\u0000')
            export.isEnabled = true
            result.onSuccess { s ->
                status.text = buildString {
                    append("Saved and verified.\n")
                    append("${s.messages} messages (${s.messagesDropped} left out by the export scrubber), ")
                    append("${s.journalRecords} journal records (${s.fieldsRedacted} fields redacted), ")
                    append("${s.recordings} recordings")
                    if (s.recordingsUnreadable > 0) append(" (${s.recordingsUnreadable} could not be opened and were skipped)")
                    append(".\n\nCopy the file to your training computer yourself, then decrypt it there with ")
                    append("tools/twin-export/decrypt_twin_export.py. Without the passphrase it can't be opened.")
                }
            }.onFailure {
                SafeLog.w(TAG, "Twin export failed", it)
                runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
                status.text = "Export failed and the partial file was removed. Nothing was sent anywhere."
            }
        }
    }

    companion object {
        private const val TAG = "TwinExport"
        private const val REQUEST_CREATE = 4026

        /** Document providers that store on this phone. Anything else (Drive, OneDrive, …) is refused. */
        val LOCAL_AUTHORITIES = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents"
        )

        fun isLocalAuthority(authority: String?) = authority in LOCAL_AUTHORITIES

        private fun isLocalProvider(uri: Uri) = uri.scheme == "content" && isLocalAuthority(uri.authority)

        /** Null when the two entries are usable; otherwise what to tell Keith. */
        fun problem(a: CharArray, b: CharArray): String? = when {
            !a.contentEquals(b) -> "The two passphrases don't match."
            a.size < PassphraseArchive.MIN_PASSPHRASE -> "Use at least ${PassphraseArchive.MIN_PASSPHRASE} characters; four or five random words work well."
            a.all { it == a[0] } -> "Choose a less predictable passphrase."
            else -> null
        }

        const val NOTICE =
            "Exports your twin data (typed messages, journal answers and transcripts, instrument answers, settings " +
                "and saved recordings) into one encrypted file on this phone, for you to copy to your training " +
                "computer yourself.\n\n" +
                "• Nothing is uploaded or shared: the app has no upload path, and cloud locations are refused.\n" +
                "• All text goes through the export scrubber: messages with credentials are left out, and numbers and " +
                "identifiers become [ID]/[NUM]. Recordings are exported as they are (your voice).\n" +
                "• The file is protected only by your passphrase (AES-256-GCM, PBKDF2-SHA256 600,000 rounds). It is " +
                "not tied to this phone, so anyone with the file and the passphrase can open it. Keep the passphrase " +
                "somewhere safe; it is never stored and can't be recovered.\n" +
                "• The export is a copy. Deleting the copy is up to you; delete-all here doesn't reach it."
    }
}
