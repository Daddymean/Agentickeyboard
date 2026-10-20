package io.github.daddymean.agentickeyboard.twin.consent

import android.content.Context
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManSwitch
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * KEYBOARD-033: who may talk to the twin. This follows Hollanek & Nowaczyk-Basińska,
 * "Griefbots, Deadbots, Postmortem Avatars" (Philosophy & Technology, 2024): mutual consent,
 * meaningful transparency, and a dignified way to retire the bot.
 *
 * - Each of Kid 1–6 starts at [Status.NOT_ASKED]. Only that kid can opt in, by their own
 *   choice on this screen. Nothing nudges them: the twin sends no notifications.
 * - An opted-in kid can [Status.PAUSED] pause and resume, or [Status.RETIRED] retire the twin.
 *   Each of these changes only that kid's access.
 * - Retiring is meant to be final. A kid can bring the twin back only by typing [BRING_BACK],
 *   and only after [RETIRE_COOLING_OFF_DAYS] days.
 * - Every change is kept in [ConsentState.log], so there is a record of who chose what and when.
 * - Keith can preview the twin (to check what it says) without any of this.
 */
enum class Status { NOT_ASKED, OPTED_IN, DECLINED, PAUSED, RETIRED }

data class ConsentEvent(val kid: String, val status: Status, val atMillis: Long, val guardianPresent: Boolean = false)

data class KidConsent(val kid: String, val status: Status = Status.NOT_ASKED, val sinceMillis: Long = 0, val guardianPresent: Boolean = false)

data class ConsentState(val kids: Map<String, KidConsent> = emptyMap(), val log: List<ConsentEvent> = emptyList()) {
    fun of(kid: String): KidConsent = kids[kid] ?: KidConsent(kid)
}

object TwinConsent {
    val KIDS: List<String> = DeadManSwitch.KIDS
    const val BRING_BACK = "BRING BACK"
    const val RETIRE_COOLING_OFF_DAYS = 30L
    private const val DAY_MS = 86_400_000L

    /** Only an opted-in kid gets answers. */
    fun mayAsk(state: ConsentState, kid: String): Boolean = kid in KIDS && state.of(kid).status == Status.OPTED_IN

    /**
     * The kid's own decision. [adult] false needs [guardianPresent] true: until Keith decides
     * how minors use the twin (follow-up item 3), a kid under 18 opts in only with a guardian.
     */
    fun optIn(state: ConsentState, kid: String, adult: Boolean, guardianPresent: Boolean, now: Long): ConsentState {
        val cur = state.of(kid)
        require(kid in KIDS) { "Unknown kid" }
        require(adult || guardianPresent) { "Under 18: a guardian needs to be with you to turn this on." }
        require(cur.status == Status.NOT_ASKED || cur.status == Status.DECLINED) {
            if (cur.status == Status.RETIRED) "Retired. Use \"Bring back\" instead." else "Already on."
        }
        return set(state, kid, Status.OPTED_IN, now, guardianPresent && !adult)
    }

    fun decline(state: ConsentState, kid: String, now: Long): ConsentState {
        require(state.of(kid).status == Status.NOT_ASKED || state.of(kid).status == Status.OPTED_IN) { "Nothing to decline." }
        return set(state, kid, Status.DECLINED, now)
    }

    fun pause(state: ConsentState, kid: String, now: Long): ConsentState {
        require(state.of(kid).status == Status.OPTED_IN) { "Only an active twin can be paused." }
        return set(state, kid, Status.PAUSED, now)
    }

    fun resume(state: ConsentState, kid: String, now: Long): ConsentState {
        require(state.of(kid).status == Status.PAUSED) { "Not paused." }
        return set(state, kid, Status.OPTED_IN, now, state.of(kid).guardianPresent)
    }

    /** Retire the twin for [kid] only. Allowed from any state, including never having opted in. */
    fun retire(state: ConsentState, kid: String, now: Long): ConsentState {
        require(kid in KIDS) { "Unknown kid" }
        require(state.of(kid).status != Status.RETIRED) { "Already retired." }
        return set(state, kid, Status.RETIRED, now)
    }

    fun canBringBack(state: ConsentState, kid: String, now: Long): Boolean {
        val c = state.of(kid)
        return c.status == Status.RETIRED && now - c.sinceMillis >= RETIRE_COOLING_OFF_DAYS * DAY_MS
    }

    fun bringBack(state: ConsentState, kid: String, typed: String, now: Long): ConsentState {
        require(canBringBack(state, kid, now)) { "A retired twin can come back only after $RETIRE_COOLING_OFF_DAYS days." }
        require(typed.trim() == BRING_BACK) { "Type $BRING_BACK to confirm." }
        // Back to "not asked": the kid opts in again, fresh.
        return set(state, kid, Status.NOT_ASKED, now)
    }

    private fun set(state: ConsentState, kid: String, status: Status, now: Long, guardian: Boolean = false) = ConsentState(
        state.kids + (kid to KidConsent(kid, status, now, guardian)),
        state.log + ConsentEvent(kid, status, now, guardian)
    )

    fun toJson(s: ConsentState): JSONObject = JSONObject()
        .put("version", 1)
        .put("kids", JSONArray(s.kids.values.map { JSONObject().put("kid", it.kid).put("status", it.status.name).put("since", it.sinceMillis).put("guardian", it.guardianPresent) }))
        .put("log", JSONArray(s.log.map { JSONObject().put("kid", it.kid).put("status", it.status.name).put("at", it.atMillis).put("guardian", it.guardianPresent) }))

    fun fromJson(o: JSONObject): ConsentState {
        fun <T> arr(name: String, f: (JSONObject) -> T): List<T> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> runCatching { f(a.getJSONObject(i)) }.getOrNull() }
        }
        val kids = arr("kids") { KidConsent(it.getString("kid"), Status.valueOf(it.getString("status")), it.optLong("since"), it.optBoolean("guardian")) }
            .filter { it.kid in KIDS }.associateBy { it.kid }
        val log = arr("log") { ConsentEvent(it.getString("kid"), Status.valueOf(it.getString("status")), it.optLong("at"), it.optBoolean("guardian")) }
        return ConsentState(kids, log)
    }
}

/** `noBackupFilesDir/twin/consent.sealed`, sealed with the twin key (AAD `consent-v1`). Delete-all removes it. */
class ConsentStore(private val file: File, private val cipher: TwinCipher) {
    @Synchronized
    fun load(): ConsentState {
        if (!file.exists()) return ConsentState()
        return DataInputStream(file.inputStream().buffered()).use { inp ->
            val iv = ByteArray(inp.readUnsignedByte()).also(inp::readFully)
            val ct = ByteArray(inp.readInt()).also(inp::readFully)
            val plain = cipher.open(TwinCipher.Sealed(iv, ct), AAD)
            try { TwinConsent.fromJson(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
        }
    }

    @Synchronized
    fun update(change: (ConsentState) -> ConsentState): ConsentState {
        val next = change(load())
        val plain = TwinConsent.toJson(next).toString().toByteArray(Charsets.UTF_8)
        val sealed = try { cipher.seal(plain, AAD) } finally { plain.fill(0) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeByte(sealed.iv.size); o.write(sealed.iv); o.writeInt(sealed.ciphertext.size); o.write(sealed.ciphertext)
        }
        check(tmp.renameTo(file)) { "could not save" }
        return next
    }

    @Synchronized
    fun delete() {
        file.delete()
        File(file.path + ".tmp").delete()
    }

    companion object {
        private val AAD = "consent-v1".toByteArray(Charsets.US_ASCII)
        fun file(context: Context) = File(context.noBackupFilesDir, "twin/consent.sealed")
    }
}

/**
 * KEYBOARD-033: the twin is always labelled as an AI ([LABEL] heads every reply), and it is
 * never for public platforms. These rules travel with the release key file and the export notes.
 */
object TwinUseRules {
    const val LABEL = "AI twin built from Dad's own words. This is not Dad."
    val RULES = listOf(
        "Each kid chooses for themselves whether to use the twin, and can pause or retire it for themselves at any time.",
        "The twin is always an AI and must be presented as one. It is not Keith.",
        "Do not post the twin, its answers, its voice, or the archive on any public platform, and do not upload them to any online AI or 'griefbot' service.",
        "The twin makes no decisions about money, inheritance or legal matters. Those come from Keith's real documents and the guardians.",
        "The twin sends no notifications and never contacts anyone by itself."
    )
}
