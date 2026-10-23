package io.github.daddymean.agentickeyboard.twin.capsule

import android.content.Context
import android.util.Base64
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManSwitch
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * When a capsule may be opened: on a [date] (for example Kid 3's 18th birthday), or at an
 * [event] that the key holders confirm (graduation, wedding, …).
 */
data class Unlock(val date: LocalDate? = null, val event: String? = null) {
    init { require((date == null) != (event == null)) { "A capsule opens on a date or at an event, not both." } }
    fun describe(): String = date?.let { "on or after $it" } ?: "when two key holders confirm: $event"
    fun toJson(): JSONObject = if (date != null) JSONObject().put("type", "date").put("date", date.toString())
        else JSONObject().put("type", "event").put("event", event)
    companion object {
        val EVENTS = listOf("High-school graduation", "College graduation", "Wedding", "First child", "Leaving home")
        fun fromJson(o: JSONObject) = if (o.getString("type") == "date") Unlock(date = LocalDate.parse(o.getString("date"))) else Unlock(event = o.getString("event"))
        fun eighteenth(birth: LocalDate) = Unlock(date = DeadManSwitch.adultFrom(birth))
    }
}

data class CapsuleAudio(val name: String, val ms: Long, val flac: ByteArray)

/** A sealed message Keith records now for one kid. */
data class Capsule(
    val id: String, val kid: String, val title: String, val unlock: Unlock, val text: String,
    val audio: List<CapsuleAudio>, val createdAtMillis: Long
) {
    /** What anyone may see before it opens: who it's for, its title and when it opens. */
    fun publicJson(): JSONObject = JSONObject().put("id", id).put("kid", kid).put("title", title)
        .put("unlock", unlock.toJson()).put("created_at", createdAtMillis)

    /** The sealed part: the message and the recordings. */
    fun contentJson(): JSONObject = JSONObject().put("id", id).put("kid", kid).put("title", title).put("text", text)
        .put("audio", JSONArray(audio.map { JSONObject().put("name", it.name).put("ms", it.ms).put("flac", Base64.encodeToString(it.flac, Base64.NO_WRAP)) }))

    fun toJson(): JSONObject = publicJson().put("text", text)
        .put("audio", contentJson().getJSONArray("audio"))

    companion object {
        fun fromJson(o: JSONObject): Capsule {
            val a = o.optJSONArray("audio") ?: JSONArray()
            return Capsule(o.getString("id"), o.getString("kid"), o.getString("title"), Unlock.fromJson(o.getJSONObject("unlock")),
                o.optString("text"), (0 until a.length()).map { i -> a.getJSONObject(i).let { CapsuleAudio(it.getString("name"), it.optLong("ms"), Base64.decode(it.getString("flac"), Base64.NO_WRAP)) } },
                o.optLong("created_at"))
        }
    }
}

/**
 * KEYBOARD-036: milestone time capsules.
 *
 * - **On the phone**, each capsule is one file `noBackupFilesDir/twin/capsules/<id>.sealed`,
 *   sealed with the twin's Keystore key (AAD `capsule-v1|<id>`). Delete-all removes them.
 * - **In the release archive**, each capsule is `capsules/<id>.json`: the public part (kid,
 *   title, unlock rule) in the clear, and the message and recordings sealed with
 *   AES-256-GCM under a **capsule key**. The capsule key is
 *   `HKDF-SHA256(ikm = DEK, salt = archive id, info = "akdm-capsule-v1|<archive id>|<capsule id>")`,
 *   where the DEK is the release data key.
 * - So the existing 2-of-3 share cards are the only way in. Two holders combine cards,
 *   `open_time_capsule.py` derives that one capsule's key and checks the unlock rule (the
 *   date has come, or two holders name themselves as confirming the event), and it opens
 *   only that capsule. Opening the release archive leaves every capsule sealed.
 * - Adding or opening a capsule never re-encrypts the archive, and replacing a holder
 *   doesn't touch capsules, because the DEK doesn't change.
 * - **Limit:** the date or event rule is enforced by the tool and the holders' procedure,
 *   not by cryptography. Holders who already have two cards could open a capsule early,
 *   just as they could open the release early.
 */
object TimeCapsules {
    const val MAX_TEXT = 20_000
    const val MAX_TITLE = 80
    val KIDS: List<String> = DeadManSwitch.KIDS
    private const val INFO = "akdm-capsule-v1"

    fun create(kid: String, title: String, unlock: Unlock, text: String, audio: List<CapsuleAudio>, now: Long, random: SecureRandom = SecureRandom()): Capsule {
        require(kid in KIDS) { "Pick which kid it's for." }
        require(title.isNotBlank() && title.length <= MAX_TITLE) { "Give it a short title (up to $MAX_TITLE characters). It shows before it opens." }
        require(text.isNotBlank() || audio.isNotEmpty()) { "Write or record something first." }
        require(text.length <= MAX_TEXT) { "Please keep the message under $MAX_TEXT characters." }
        require(audio.all { it.name.matches(Regex("[a-z0-9-]{1,32}")) } && audio.map { it.name }.toSet().size == audio.size) { "bad recording name" }
        val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        return Capsule(id, kid, title.trim(), unlock, text.trim(), audio, now)
    }

    fun capsuleKey(dek: ByteArray, archiveId: ByteArray, capsuleId: String): ByteArray =
        hkdf(dek, archiveId, "$INFO|${hex(archiveId)}|$capsuleId".toByteArray(Charsets.US_ASCII), 32)

    fun aad(archiveId: ByteArray, c: Capsule) = "$INFO|${hex(archiveId)}|${c.id}|${c.kid}".toByteArray(Charsets.UTF_8)

    /** The release archive entry for [c]. */
    fun sealForRelease(c: Capsule, dek: ByteArray, archiveId: ByteArray, random: SecureRandom = SecureRandom()): JSONObject {
        val key = capsuleKey(dek, archiveId, c.id)
        val plain = c.contentJson().toString().toByteArray(Charsets.UTF_8)
        try {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(archiveId, c))
            val ct = cipher.doFinal(plain)
            return c.publicJson().put("sealed", JSONObject().put("alg", "AES-256-GCM").put("kdf", "HKDF-SHA256").put("iv", hex(iv)).put("ciphertext", hex(ct)))
        } finally { key.fill(0); plain.fill(0) }
    }

    /** What the off-device tool does, for tests. */
    fun openReleased(entry: JSONObject, dek: ByteArray, archiveId: ByteArray): JSONObject {
        val pub = Capsule(entry.getString("id"), entry.getString("kid"), entry.getString("title"), Unlock.fromJson(entry.getJSONObject("unlock")), "", emptyList(), 0)
        val key = capsuleKey(dek, archiveId, pub.id)
        try {
            val s = entry.getJSONObject("sealed")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unhex(s.getString("iv"))))
            cipher.updateAAD(aad(archiveId, pub))
            return JSONObject(String(cipher.doFinal(unhex(s.getString("ciphertext"))), Charsets.UTF_8))
        } finally { key.fill(0) }
    }

    internal fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; i++
        }
        prk.fill(0)
        return out
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** The capsules on the phone, one sealed file each. */
class CapsuleStore(private val dir: File, private val cipher: TwinCipher) {
    @Synchronized
    fun all(): List<Capsule> = (dir.listFiles { f -> f.name.endsWith(SUFFIX) }?.toList() ?: emptyList())
        .mapNotNull { f -> runCatching { load(f.name.removeSuffix(SUFFIX)) }.getOrNull() }
        .sortedBy { it.createdAtMillis }

    @Synchronized
    fun load(id: String): Capsule {
        require(id.matches(ID)) { "bad id" }
        return DataInputStream(File(dir, id + SUFFIX).inputStream().buffered()).use { inp ->
            val iv = ByteArray(inp.readUnsignedByte()).also(inp::readFully)
            val ct = ByteArray(inp.readInt()).also(inp::readFully)
            val plain = cipher.open(TwinCipher.Sealed(iv, ct), aad(id))
            try { Capsule.fromJson(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
        }
    }

    @Synchronized
    fun save(c: Capsule) {
        require(c.id.matches(ID)) { "bad id" }
        val plain = c.toJson().toString().toByteArray(Charsets.UTF_8)
        val sealed = try { cipher.seal(plain, aad(c.id)) } finally { plain.fill(0) }
        dir.mkdirs()
        val tmp = File(dir, c.id + SUFFIX + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeByte(sealed.iv.size); o.write(sealed.iv); o.writeInt(sealed.ciphertext.size); o.write(sealed.ciphertext)
        }
        check(tmp.renameTo(File(dir, c.id + SUFFIX))) { "could not save" }
    }

    @Synchronized
    fun delete(id: String) { require(id.matches(ID)); File(dir, id + SUFFIX).delete() }

    @Synchronized
    fun deleteAll() { dir.deleteRecursively() }

    companion object {
        private const val SUFFIX = ".sealed"
        private val ID = Regex("[0-9a-f]{16}")
        private fun aad(id: String) = "capsule-v1|$id".toByteArray(Charsets.US_ASCII)
        fun dir(context: Context) = File(context.noBackupFilesDir, "twin/capsules")
    }
}
