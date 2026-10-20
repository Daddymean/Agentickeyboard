package io.github.daddymean.agentickeyboard.twin.export

import io.github.daddymean.agentickeyboard.twin.TwinExportScrubber
import io.github.daddymean.agentickeyboard.twin.TwinStore
import io.github.daddymean.agentickeyboard.twin.CredentialMentions
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import io.github.daddymean.agentickeyboard.twin.memory.MemoryStore
import io.github.daddymean.agentickeyboard.twin.memory.VaultState
import io.github.daddymean.agentickeyboard.util.SecretTokenPatterns
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What one export wrote (counts only; never any content). */
data class ExportSummary(
    val messages: Int,
    val messagesDropped: Int,
    val journalRecords: Int,
    val fieldsRedacted: Int,
    val recordings: Int,
    val recordingsUnreadable: Int,
    val memories: Int = 0,
    val memoriesDropped: Int = 0,
    val coreBlocks: Int = 0,
    val coreBlocksWithheld: Int = 0
)

/**
 * KEYBOARD-026: writes the legacy twin's data into one passphrase-encrypted archive
 * ([PassphraseArchive]) for Keith to copy to his training machine by hand.
 *
 * Inside the encryption is a ZIP with:
 * - `twin/messages.jsonl`: typed messages, each `{"at", "package", "text"}`; text is
 *   passed through [TwinExportScrubber] and a message it rejects is left out;
 * - `journal/records.jsonl`: journal records `{"id", "at", "kind", "body"}`; every
 *   free-text string in the body is scrubbed, and a field the scrubber rejects is removed
 *   and named in `body._redacted`;
 * - `audio/<id>.flac`: each saved recording, bit-exact (voice is the point of the
 *   archive, so it is not altered);
 * - KEYBOARD-032: `memory/memories.jsonl`: every **approved** vault memory (never pending
 *   or rejected candidates), each with its citation `m:<id>` and its source link (journal
 *   record id and field, question, date), so the cited record can be found in
 *   `journal/records.jsonl`. Its text, original answer, question and child label are
 *   scrubbed like the journal; a memory the scrubber rejects is left out;
 * - KEYBOARD-032: `memory/core.json`: the core memories ("Who I am", "About my kids"),
 *   citation `core:<label>`. Keith writes them deliberately for the kids, so identifiers
 *   (names, years, places) are **not** blanked. They are still checked for secrets: a
 *   block holding a token, private key or credential mention is withheld and counted;
 * - `manifest.json` (last): format, creation time, counts, and the SHA-256 and size of
 *   every other file, so the decrypt script can verify the contents.
 * Nothing is written to disk in plaintext: the ZIP streams straight into the encryption.
 * Takes that were never saved to an answer are not exported.
 */
class TwinExporter(private val twin: TwinStore?, private val journal: JournalStore?, private val memory: MemoryStore? = null) {

    fun export(out: OutputStream, passphrase: CharArray, nowMillis: Long, iterations: Int = PassphraseArchive.ITERATIONS): ExportSummary {
        val normalized = normalize(passphrase)
        try {
            return write(PassphraseArchive.Writer(out, normalized, iterations), nowMillis)
        } finally {
            normalized.fill('\u0000')
        }
    }

    /** KEYBOARD-027: the same contents under a data key (the dead-man release archive). */
    fun exportRelease(out: OutputStream, params: PassphraseArchive.Params, nowMillis: Long): ExportSummary =
        write(PassphraseArchive.Writer(out, params), nowMillis)

    private fun write(writer: PassphraseArchive.Writer, nowMillis: Long): ExportSummary {
        val files = JSONObject()
        var messages = 0
        var dropped = 0
        var records = 0
        var redacted = 0
        var recordings = 0
        var unreadable = 0
        var memories = 0
        var memoriesDropped = 0
        var coreBlocks = 0
        var coreWithheld = 0
        val vault: VaultState? = memory?.load()
        run {
            writer.use { enc ->
                val zip = ZipOutputStream(NonClosing(enc))
                fun entry(name: String, write: (OutputStream) -> Unit) {
                    zip.putNextEntry(ZipEntry(name).apply { time = nowMillis })
                    val digest = MessageDigest.getInstance("SHA-256")
                    val counting = Hashing(zip, digest)
                    write(counting)
                    zip.closeEntry()
                    files.put(name, JSONObject().put("sha256", hex(digest.digest())).put("bytes", counting.count))
                }

                entry(MESSAGES) { o ->
                    twin?.forEach { e ->
                        val text = TwinExportScrubber.scrub(e.text)
                        if (text == null) { dropped++; return@forEach }
                        o.write((JSONObject().put("at", e.atMillis).put("package", e.packageName ?: JSONObject.NULL)
                            .put("text", text).toString() + "\n").toByteArray(Charsets.UTF_8))
                        messages++
                    }
                }

                val audioIds = ArrayList<String>()
                entry(RECORDS) { o ->
                    for (r in journal?.all().orEmpty()) {
                        val (body, gone) = scrubBody(r.body)
                        redacted += gone
                        o.write((JSONObject().put("id", r.id).put("at", r.atMillis).put("kind", r.kind).put("body", body)
                            .toString() + "\n").toByteArray(Charsets.UTF_8))
                        records++
                        audioIds += JournalStore.audioIds(r.body)
                    }
                }

                for (id in audioIds.distinct()) {
                    val bytes = runCatching { journal!!.readRecording(id) }.getOrNull()
                    if (bytes == null) { unreadable++; continue }
                    entry("$AUDIO_DIR$id.flac") { it.write(bytes) }
                    bytes.fill(0)
                    recordings++
                }

                if (vault != null) {
                    entry(MEMORIES) { o ->
                        for (m in vault.memories) {
                            val text = TwinExportScrubber.scrub(m.text)
                            if (text == null) { memoriesDropped++; continue }
                            val src = JSONObject()
                                .put("journal_record_id", m.source.recordId)
                                .put("field", m.source.field)
                                .put("prompt", m.prompt?.let(TwinExportScrubber::scrub) ?: JSONObject.NULL)
                                .put("child", m.child?.let(TwinExportScrubber::scrub) ?: JSONObject.NULL)
                                .put("at", m.atMillis)
                                .put("original_text", TwinExportScrubber.scrub(m.sourceText) ?: JSONObject.NULL)
                            o.write((JSONObject().put("cite", "m:${m.id}").put("id", m.id).put("text", text).put("edited", m.edited)
                                .put("approved_at", m.approvedAtMillis).put("source", src).toString() + "\n").toByteArray(Charsets.UTF_8))
                            memories++
                        }
                    }
                    entry(CORE) { o ->
                        val blocks = JSONArray()
                        for (b in vault.core) {
                            if (b.value.isBlank()) continue
                            if (!coreIsSafe(b.value)) { coreWithheld++; continue }
                            blocks.put(JSONObject().put("cite", "core:${b.label}").put("label", b.label).put("title", b.title)
                                .put("value", b.value).put("updated_at", b.updatedAtMillis))
                            coreBlocks++
                        }
                        o.write(JSONObject().put("scrubbed", false).put("checked_for_secrets", true).put("blocks", blocks)
                            .toString(2).toByteArray(Charsets.UTF_8))
                    }
                }

                val manifest = JSONObject()
                    .put("format", FORMAT)
                    .put("created_at_ms", nowMillis)
                    .put("scrubber", "TwinExportScrubber (KEYBOARD-024)")
                    .put("counts", JSONObject().put("messages", messages).put("messages_dropped", dropped)
                        .put("journal_records", records).put("fields_redacted", redacted)
                        .put("recordings", recordings).put("recordings_unreadable", unreadable)
                        .put("memories", memories).put("memories_dropped", memoriesDropped)
                        .put("core_blocks", coreBlocks).put("core_blocks_withheld", coreWithheld))
                    .put("files", files)
                zip.putNextEntry(ZipEntry(MANIFEST).apply { time = nowMillis })
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.finish()
            }
        }
        return ExportSummary(messages, dropped, records, redacted, recordings, unreadable, memories, memoriesDropped, coreBlocks, coreWithheld)
    }

    /** Scrubs free text recursively; structural fields (ids, keys, numbers) are kept as they are. */
    private fun scrubBody(body: JSONObject): Pair<JSONObject, Int> {
        var gone = 0
        fun walk(v: Any?, key: String?): Any? = when (v) {
            is JSONObject -> JSONObject().also { o ->
                val removed = JSONArray()
                for (k in v.keys()) {
                    val w = walk(v.get(k), k)
                    if (w == null) { removed.put(k); gone++ } else o.put(k, w)
                }
                if (removed.length() > 0) o.put("_redacted", removed)
            }
            is JSONArray -> JSONArray().also { a ->
                for (i in 0 until v.length()) { val w = walk(v.get(i), key); if (w == null) gone++ else a.put(w) }
            }
            is String -> if (key in STRUCTURAL || v.isBlank()) v else TwinExportScrubber.scrub(v)
            else -> v
        }
        return (walk(body, null) as JSONObject) to gone
    }

    private class Hashing(out: OutputStream, private val digest: MessageDigest) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); digest.update(b.toByte()); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); digest.update(b, off, len); count += len }
        override fun close() = Unit
    }

    private class NonClosing(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    companion object {
        const val FORMAT = "agentickeyboard-twin-export-v1"
        const val MESSAGES = "twin/messages.jsonl"
        const val RECORDS = "journal/records.jsonl"
        const val AUDIO_DIR = "audio/"
        const val MANIFEST = "manifest.json"
        const val MEMORIES = "memory/memories.jsonl"
        const val CORE = "memory/core.json"

        /** Core memories aren't identifier-scrubbed, but never carry a secret out (KEYBOARD-032). */
        fun coreIsSafe(text: String): Boolean =
            !SecretTokenPatterns.containsToken(text) && !SecretTokenPatterns.privateKeyHeader.containsMatchIn(text) &&
                !CredentialMentions.matches(text)
        const val FILE_EXTENSION = ".aktx"

        /** Body keys that hold identifiers, not prose: never scrubbed (and never user text). */
        val STRUCTURAL = setOf("kind", "promptId", "key", "file", "instrumentId", "type", "category", "slot", "v", "value", "startDate", "heavyPausedUntil", "ink_png")

        /** The passphrase as NFC, so the same words typed on another keyboard derive the same key. */
        fun normalize(p: CharArray): CharArray = Normalizer.normalize(String(p), Normalizer.Form.NFC).toCharArray()

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        /**
         * Reads an archive back and checks every manifest hash: the integrity check run right
         * after writing. Returns the manifest; throws if anything doesn't match.
         */
        fun verify(input: InputStream, passphrase: CharArray): JSONObject {
            val p = normalize(passphrase)
            try {
                return verifyStream(PassphraseArchive.Reader(input, p))
            } finally {
                p.fill('\u0000')
            }
        }

        /** KEYBOARD-027: verifies a release archive with its data key. */
        fun verifyRelease(input: InputStream, dataKey: ByteArray): JSONObject =
            verifyStream(PassphraseArchive.Reader(input, dataKey))

        private fun verifyStream(reader: PassphraseArchive.Reader): JSONObject {
            run {
                val seen = HashMap<String, Pair<String, Long>>()
                var manifest: JSONObject? = null
                ZipInputStream(reader).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (e.name == MANIFEST) {
                            manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        } else {
                            val d = MessageDigest.getInstance("SHA-256")
                            val s = DigestInputStream(zip, d) // not closed: that would close the ZIP
                            var n = 0L
                            val buf = ByteArray(65536)
                            while (true) { val r = s.read(buf); if (r < 0) break; n += r }
                            seen[e.name] = hex(d.digest()) to n
                        }
                    }
                    // Decrypt to the very end (the ZIP directory and the final chunk), so a
                    // truncated or tampered tail is detected too.
                    val tail = ByteArray(65536)
                    while (reader.read(tail) >= 0) Unit
                }
                val m = manifest ?: error("manifest missing")
                require(m.getString("format") == FORMAT) { "unknown format" }
                val files = m.getJSONObject("files")
                require(files.keys().asSequence().toSet() == seen.keys) { "file list does not match the manifest" }
                for ((name, v) in seen) {
                    val f = files.getJSONObject(name)
                    require(f.getString("sha256") == v.first && f.getLong("bytes") == v.second) { "hash mismatch: $name" }
                }
                return m
            }
        }
    }
}
