package io.github.daddymean.agentickeyboard.twin.deadman

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A key holder: who holds share x. Labels only, no names (Keith, 2026-10-17). */
data class Holder(val x: Int, val label: String)

/**
 * The phone's dead-man state, kept only sealed under the twin's Keystore key
 * ([DeadManStore]). It holds the archive's data key (DEK) but never a KEK or a share.
 */
data class DeadManState(
    val dataKey: ByteArray,
    val archiveId: ByteArray,
    val epoch: Int,
    val setId: String,
    val holders: List<Holder>,
    val lastCheckIn: LocalDate,
    val keyFile: JSONObject
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("dek", hex(dataKey))
        .put("archive_id", hex(archiveId))
        .put("epoch", epoch)
        .put("set_id", setId)
        .put("holders", JSONArray(holders.map { JSONObject().put("x", it.x).put("label", it.label) }))
        .put("last_check_in", lastCheckIn.toString())
        .put("key_file", keyFile)

    override fun equals(other: Any?) = other is DeadManState && toJson().toString() == other.toJson().toString()
    override fun hashCode() = toJson().toString().hashCode()

    companion object {
        fun fromJson(o: JSONObject) = DeadManState(
            unhex(o.getString("dek")), unhex(o.getString("archive_id")), o.getInt("epoch"), o.getString("set_id"),
            o.getJSONArray("holders").let { a -> (0 until a.length()).map { Holder(a.getJSONObject(it).getInt("x"), a.getJSONObject(it).getString("label")) } },
            LocalDate.parse(o.getString("last_check_in")), o.getJSONObject("key_file")
        )
    }
}

/** The result of creating or re-keying holders: show the cards once, save the key file. */
class Rekey(val state: DeadManState, val cards: List<Pair<Holder, ShareCard>>)

enum class CheckInStage { OK, DUE_SOON, OVERDUE }

data class CheckInStatus(val lastCheckIn: LocalDate, val releaseFrom: LocalDate, val daysLeft: Long, val stage: CheckInStage)

/**
 * KEYBOARD-027: the dead-man switch for access to the twin (Keith, relayed 2026-10-17
 * 20:00 PT).
 *
 * - **Keys.** The release archive is encrypted with a random 256-bit data key (DEK). For
 *   each key-holder set ("epoch") a fresh key-encryption key (KEK) wraps the DEK
 *   (AES-256-GCM, bound to the archive id, epoch and set id) and is split **2-of-3** with
 *   [Shamir] into one [ShareCard] per holder. The wrapped DEK and the policy go in the
 *   **release key file**; the KEK and the shares are never stored.
 * - **Holder changes** make a new KEK, wrap and cards, so the archive is never
 *   re-encrypted. Old cards can't open the new key file. (An old key file still opens
 *   with old cards, so old key files must be destroyed; see tools/twin-export/RELEASE.md.)
 * - **Holders** are Guardian 1–3 at first. Kid 1–6 can replace a guardian once 18.
 * - **Recipients** are Kid 1–6.
 * - **Check-in.** Release is due 6 months after the last check-in. There is no server: the
 *   phone shows the status, and the holders apply the rule. Two of them must agree, so
 *   no single person can open it early.
 */
object DeadManSwitch {
    const val THRESHOLD = 2
    const val HOLDERS = 3
    const val RELEASE_MONTHS = 6L
    const val WARNING_DAYS = 30L
    const val ADULT_AGE = 18L
    const val KEY_FILE_FORMAT = "agentickeyboard-twin-release-key-v1"
    val GUARDIANS = (1..3).map { "Guardian $it" }
    val KIDS = (1..6).map { "Kid $it" }
    val LABELS = GUARDIANS + KIDS

    fun isKid(label: String) = label in KIDS

    /** First set-up: three holders, normally Guardian 1–3. */
    fun create(labels: List<String>, today: LocalDate, random: SecureRandom = SecureRandom()): Rekey {
        val dek = ByteArray(32).also(random::nextBytes)
        val archiveId = ByteArray(16).also(random::nextBytes)
        return rekey(dek, archiveId, 0, labels, today, random)
    }

    /**
     * Replaces one holder. A kid may take a slot only on or after their 18th birthday;
     * the birth date is checked here and not stored.
     */
    fun replaceHolder(
        state: DeadManState, x: Int, newLabel: String, kidBirthDate: LocalDate?, today: LocalDate,
        random: SecureRandom = SecureRandom()
    ): Rekey {
        require(state.holders.any { it.x == x }) { "no such holder" }
        if (isKid(newLabel)) {
            requireNotNull(kidBirthDate) { "$newLabel's date of birth is needed to confirm they are 18" }
            require(isAdult(kidBirthDate, today)) { "$newLabel can hold a key from ${adultFrom(kidBirthDate)}" }
        }
        val labels = state.holders.sortedBy { it.x }.map { if (it.x == x) newLabel else it.label }
        return rekey(state.dataKey, state.archiveId, state.epoch, labels, today, random, state.lastCheckIn)
    }

    fun adultFrom(birth: LocalDate): LocalDate = birth.plusYears(ADULT_AGE)
    fun isAdult(birth: LocalDate, today: LocalDate) = !today.isBefore(adultFrom(birth))

    fun checkIn(state: DeadManState, today: LocalDate): DeadManState {
        val k = JSONObject(state.keyFile.toString())
        k.put("policy", policy(today))
        return state.copy(lastCheckIn = today, keyFile = k)
    }

    fun releaseFrom(lastCheckIn: LocalDate): LocalDate = lastCheckIn.plusMonths(RELEASE_MONTHS)

    fun status(state: DeadManState, today: LocalDate): CheckInStatus {
        val from = releaseFrom(state.lastCheckIn)
        val left = java.time.temporal.ChronoUnit.DAYS.between(today, from)
        val stage = when {
            left <= 0 -> CheckInStage.OVERDUE
            left <= WARNING_DAYS -> CheckInStage.DUE_SOON
            else -> CheckInStage.OK
        }
        return CheckInStatus(state.lastCheckIn, from, left, stage)
    }

    private fun rekey(
        dek: ByteArray, archiveId: ByteArray, previousEpoch: Int, labels: List<String>, today: LocalDate,
        random: SecureRandom, lastCheckIn: LocalDate = today
    ): Rekey {
        require(labels.size == HOLDERS) { "exactly $HOLDERS key holders" }
        require(labels.all { it in LABELS }) { "holders are Guardian 1–3 or Kid 1–6" }
        require(labels.toSet().size == HOLDERS) { "each holder can hold only one share" }
        val epoch = previousEpoch + 1
        val setId = hex(ByteArray(8).also(random::nextBytes))
        val kek = ByteArray(32).also(random::nextBytes)
        try {
            val iv = ByteArray(12).also(random::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(wrapAad(hex(archiveId), epoch, setId))
            val wrapped = c.doFinal(dek)
            val holders = labels.mapIndexed { i, l -> Holder(i + 1, l) }
            val keyFile = JSONObject()
                .put("format", KEY_FILE_FORMAT)
                .put("archive_id", hex(archiveId))
                .put("epoch", epoch)
                .put("set_id", setId)
                .put("threshold", THRESHOLD)
                .put("holders", JSONArray(holders.map { JSONObject().put("x", it.x).put("label", it.label) }))
                .put("recipients", JSONArray(KIDS))
                .put("policy", policy(lastCheckIn))
                .put("wrap", JSONObject().put("alg", "AES-256-GCM").put("iv", hex(iv)).put("ciphertext", hex(wrapped)))
                .put("created", today.toString())
            val shares = Shamir.split(kek, HOLDERS, THRESHOLD, random)
            val cards = holders.map { h -> h to ShareCard(setId, epoch, THRESHOLD, HOLDERS, shares.single { it.x == h.x }) }
            return Rekey(DeadManState(dek.copyOf(), archiveId.copyOf(), epoch, setId, holders, lastCheckIn, keyFile), cards)
        } finally {
            kek.fill(0)
        }
    }

    private fun policy(lastCheckIn: LocalDate) = JSONObject()
        .put("rule", "Release when Keith has not checked in for $RELEASE_MONTHS months. $THRESHOLD of the $HOLDERS key holders " +
            "must agree and combine their share cards; no single holder can open it.")
        .put("check_in_months", RELEASE_MONTHS)
        .put("last_check_in", lastCheckIn.toString())
        .put("release_from", releaseFrom(lastCheckIn).toString())

    internal fun wrapAad(archiveIdHex: String, epoch: Int, setId: String) =
        "akdm-wrap-v1|$archiveIdHex|$epoch|$setId".toByteArray(Charsets.US_ASCII)

    /** What the off-device tool does: recombine cards and unwrap the DEK from a key file. */
    fun unwrap(keyFile: JSONObject, cards: List<ShareCard>): ByteArray {
        require(keyFile.getString("format") == KEY_FILE_FORMAT) { "not a twin release key file" }
        val epoch = keyFile.getInt("epoch")
        val setId = keyFile.getString("set_id")
        require(cards.size >= keyFile.getInt("threshold")) { "need ${keyFile.getInt("threshold")} share cards" }
        require(cards.all { it.epoch == epoch && it.setId == setId }) { "a share card is from a different key-holder set" }
        val kek = Shamir.combine(cards.map { it.share })
        try {
            val w = keyFile.getJSONObject("wrap")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, unhex(w.getString("iv"))))
            c.updateAAD(wrapAad(keyFile.getString("archive_id"), epoch, setId))
            return c.doFinal(unhex(w.getString("ciphertext")))
        } finally {
            kek.fill(0)
        }
    }
}

internal fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
internal fun unhex(s: String): ByteArray {
    require(s.length % 2 == 0) { "bad hex" }
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
