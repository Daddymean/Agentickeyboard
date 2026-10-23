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
 * ([DeadManStore]). It holds the current set's key-encryption key (KEK) and the data key
 * (DEK) of the latest release archive, but never a share. The phone already holds the
 * plaintext twin, so keeping the KEK here exposes nothing new; it lets every archive get
 * a fresh DEK under the current cards (KEYBOARD-039).
 */
data class DeadManState(
    val dataKey: ByteArray,
    val archiveId: ByteArray,
    val epoch: Int,
    val setId: String,
    val holders: List<Holder>,
    val lastCheckIn: LocalDate,
    val keyFile: JSONObject,
    /** When Keith last confirmed his Google Inactive Account Manager plan; null if never or holders changed since. */
    val iamConfirmedOn: LocalDate? = null,
    /**
     * The current set's KEK. Null only for state saved before KEYBOARD-039, which kept no
     * KEK: such a set must be reissued before another archive can be saved.
     */
    val kek: ByteArray? = null
) {
    /** True when the cards must be reissued before a new archive can be saved. */
    val needsNewCards: Boolean get() = kek == null

    fun toJson(): JSONObject = JSONObject()
        .put("v", 2)
        .put("dek", hex(dataKey))
        .put("archive_id", hex(archiveId))
        .put("epoch", epoch)
        .put("set_id", setId)
        .put("holders", JSONArray(holders.map { JSONObject().put("x", it.x).put("label", it.label) }))
        .put("last_check_in", lastCheckIn.toString())
        .put("key_file", keyFile)
        .also { o -> iamConfirmedOn?.let { o.put("iam_confirmed_on", it.toString()) } }
        .also { o -> kek?.let { o.put("kek", hex(it)) } }

    override fun equals(other: Any?) = other is DeadManState && toJson().toString() == other.toJson().toString()
    override fun hashCode() = toJson().toString().hashCode()

    companion object {
        fun fromJson(o: JSONObject) = DeadManState(
            unhex(o.getString("dek")), unhex(o.getString("archive_id")), o.getInt("epoch"), o.getString("set_id"),
            o.getJSONArray("holders").let { a -> (0 until a.length()).map { Holder(a.getJSONObject(it).getInt("x"), a.getJSONObject(it).getString("label")) } },
            LocalDate.parse(o.getString("last_check_in")), o.getJSONObject("key_file"),
            o.optString("iam_confirmed_on", "").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
            o.optString("kek", "").takeIf { it.isNotEmpty() }?.let(::unhex)
        )
    }
}

/**
 * The result of creating or re-keying holders. Nothing is committed yet: the screen shows
 * the cards, and [state] is saved only after [DeadManSwitch.cardsConfirmed] (KEYBOARD-039).
 */
class Rekey(val state: DeadManState, val cards: List<Pair<Holder, ShareCard>>)

/** The fallback ("no contact for 6 months") date as of the last in-app check-in. */
enum class CheckInStage { OK, DUE_SOON, OVERDUE }

/** Whether Keith has confirmed the Google Inactive Account Manager plan recently enough. */
enum class IamStage { NOT_CONFIRMED, OK, REVIEW_DUE }

data class CheckInStatus(
    val lastCheckIn: LocalDate,
    /** The fallback date: the holders' rule never opens the archive before it. */
    val releaseFrom: LocalDate,
    val daysLeft: Long,
    val stage: CheckInStage,
    val iamConfirmedOn: LocalDate? = null,
    val iamReviewDue: LocalDate? = null,
    val iamStage: IamStage = IamStage.NOT_CONFIRMED
)

/**
 * KEYBOARD-027: the dead-man switch for access to the twin (Keith, relayed 2026-10-17
 * 20:00 PT).
 *
 * - **Keys.** Each release archive is encrypted with its own random 256-bit data key
 *   (DEK), made fresh by [newArchive] (KEYBOARD-039). For each key-holder set ("epoch") a
 *   key-encryption key (KEK) wraps the DEK (AES-256-GCM, bound to the archive id, epoch
 *   and set id) and is split **2-of-3** with [Shamir] into one [ShareCard] per holder.
 *   The wrapped DEK and the policy go in the **release key file**. The phone keeps the KEK
 *   sealed; the shares are never stored.
 * - **Holder changes** make a new KEK and cards, and re-wrap the latest archive's DEK, so
 *   that archive is never re-encrypted. Old cards can't open the new key file, and they
 *   can't open any archive saved after the change, because each has a new DEK. (An old
 *   key file still opens its own archive with old cards; see tools/twin-export/RELEASE.md.)
 * - **Commit.** A new set takes effect only once Keith shows the cards were copied
 *   ([cardsConfirmed]), so losing them on screen never leaves a set nobody holds.
 * - **Holders** are Guardian 1–3 at first. Kid 1–6 can replace a guardian once 18.
 * - **Recipients** are Kid 1–6.
 * - **Trigger (Keith, relayed 2026-10-18 08:17 PT).** Google's Inactive Account Manager
 *   (IAM) emails the key holders after 6 months with no activity on Keith's Google account.
 *   That email is only a **signal** to start: it carries no key, share or passphrase, and
 *   nothing secret goes into Google. The holders verify it and cross-check with each other,
 *   then any 2 of 3 combine their cards. Nothing runs on a server of Keith's.
 * - **Fallback.** If the email can't come (Google drops or changes IAM, or the account is
 *   suspended or deleted), the holders apply the 6-month rule themselves. The phone's
 *   check-in moves the "not before" date that is written in the key file.
 */
object DeadManSwitch {
    const val THRESHOLD = 2
    const val HOLDERS = 3
    const val RELEASE_MONTHS = 6L
    /** Inactivity period Keith sets in IAM (Google offers 3, 6, 12 or 18 months). */
    const val IAM_INACTIVE_MONTHS = 6L
    /** Re-check the IAM plan at least this often: Google may change it, and contacts change. */
    const val IAM_REVIEW_MONTHS = 12L
    const val IAM_PAGE = "myaccount.google.com/inactive"
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
     * New cards for the same holders, for a lost card or to finish the KEYBOARD-039 update.
     * No age check: the holders were already approved. The IAM confirmation is kept.
     */
    fun reissue(state: DeadManState, today: LocalDate, random: SecureRandom = SecureRandom()): Rekey =
        rekey(state.dataKey, state.archiveId, state.epoch, state.holders.sortedBy { it.x }.map { it.label }, today, random,
            state.lastCheckIn, state.iamConfirmedOn)

    /**
     * A fresh DEK and archive id for the next release archive, wrapped under the current
     * set's KEK, so cards from an earlier set can never open it. Save the returned state
     * only after the archive is written and verified.
     */
    fun newArchive(state: DeadManState, random: SecureRandom = SecureRandom()): DeadManState {
        val kek = requireNotNull(state.kek) { "reissue the share cards first" }
        val dek = ByteArray(32).also(random::nextBytes)
        val archiveId = ByteArray(16).also(random::nextBytes)
        val k = JSONObject(state.keyFile.toString())
            .put("archive_id", hex(archiveId))
            .put("wrap", wrap(kek, dek, archiveId, state.epoch, state.setId, random))
        return state.copy(dataKey = dek, archiveId = archiveId, keyFile = k)
    }

    /**
     * True when [typed] holds each card's check (the 8 characters after its last dash), in
     * card order. Typing them back shows the cards were copied before the set is saved.
     */
    fun cardsConfirmed(r: Rekey, typed: List<String>): Boolean =
        typed.size == r.cards.size && r.cards.zip(typed).all { (c, t) ->
            t.filterNot { it.isWhitespace() }.equals(c.second.encode().substringAfterLast('-'), ignoreCase = true)
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
        // A new holder must become an IAM contact, so the plan needs confirming again.
        val iam = state.iamConfirmedOn.takeIf { labels == state.holders.sortedBy { it.x }.map { it.label } }
        return rekey(state.dataKey, state.archiveId, state.epoch, labels, today, random, state.lastCheckIn, iam)
    }

    fun adultFrom(birth: LocalDate): LocalDate = birth.plusYears(ADULT_AGE)
    fun isAdult(birth: LocalDate, today: LocalDate) = !today.isBefore(adultFrom(birth))

    fun checkIn(state: DeadManState, today: LocalDate): DeadManState {
        val k = JSONObject(state.keyFile.toString())
        k.put("policy", policy(today, state.iamConfirmedOn))
        return state.copy(lastCheckIn = today, keyFile = k)
    }

    /**
     * Keith confirms that his IAM plan is on: 6 months, the current holders as contacts, and
     * a message with no secrets. The app can't check this; it only records the date.
     */
    fun confirmIam(state: DeadManState, today: LocalDate): DeadManState {
        val k = JSONObject(state.keyFile.toString())
        k.put("policy", policy(state.lastCheckIn, today))
        return state.copy(iamConfirmedOn = today, keyFile = k)
    }

    fun iamReviewDue(confirmedOn: LocalDate): LocalDate = confirmedOn.plusMonths(IAM_REVIEW_MONTHS)

    fun releaseFrom(lastCheckIn: LocalDate): LocalDate = lastCheckIn.plusMonths(RELEASE_MONTHS)

    fun status(state: DeadManState, today: LocalDate): CheckInStatus {
        val from = releaseFrom(state.lastCheckIn)
        val left = java.time.temporal.ChronoUnit.DAYS.between(today, from)
        val stage = when {
            left <= 0 -> CheckInStage.OVERDUE
            left <= WARNING_DAYS -> CheckInStage.DUE_SOON
            else -> CheckInStage.OK
        }
        val iamDue = state.iamConfirmedOn?.let(::iamReviewDue)
        val iamStage = when {
            iamDue == null -> IamStage.NOT_CONFIRMED
            !today.isBefore(iamDue) -> IamStage.REVIEW_DUE
            else -> IamStage.OK
        }
        return CheckInStatus(state.lastCheckIn, from, left, stage, state.iamConfirmedOn, iamDue, iamStage)
    }

    private fun rekey(
        dek: ByteArray, archiveId: ByteArray, previousEpoch: Int, labels: List<String>, today: LocalDate,
        random: SecureRandom, lastCheckIn: LocalDate = today, iamConfirmedOn: LocalDate? = null
    ): Rekey {
        require(labels.size == HOLDERS) { "exactly $HOLDERS key holders" }
        require(labels.all { it in LABELS }) { "holders are Guardian 1–3 or Kid 1–6" }
        require(labels.toSet().size == HOLDERS) { "each holder can hold only one share" }
        val epoch = previousEpoch + 1
        val setId = hex(ByteArray(8).also(random::nextBytes))
        val kek = ByteArray(32).also(random::nextBytes)
        try {
            val holders = labels.mapIndexed { i, l -> Holder(i + 1, l) }
            val keyFile = JSONObject()
                .put("format", KEY_FILE_FORMAT)
                .put("archive_id", hex(archiveId))
                .put("epoch", epoch)
                .put("set_id", setId)
                .put("threshold", THRESHOLD)
                .put("holders", JSONArray(holders.map { JSONObject().put("x", it.x).put("label", it.label) }))
                .put("recipients", JSONArray(KIDS))
                .put("policy", policy(lastCheckIn, iamConfirmedOn))
                .put("wrap", wrap(kek, dek, archiveId, epoch, setId, random))
                .put("created", today.toString())
            val shares = Shamir.split(kek, HOLDERS, THRESHOLD, random)
            val cards = holders.map { h -> h to ShareCard(setId, epoch, THRESHOLD, HOLDERS, shares.single { it.x == h.x }) }
            return Rekey(DeadManState(dek.copyOf(), archiveId.copyOf(), epoch, setId, holders, lastCheckIn, keyFile, iamConfirmedOn, kek.copyOf()), cards)
        } finally {
            kek.fill(0)
        }
    }

    private fun wrap(kek: ByteArray, dek: ByteArray, archiveId: ByteArray, epoch: Int, setId: String, random: SecureRandom): JSONObject {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(wrapAad(hex(archiveId), epoch, setId))
        return JSONObject().put("alg", "AES-256-GCM").put("iv", hex(iv)).put("ciphertext", hex(c.doFinal(dek)))
    }

    private fun policy(lastCheckIn: LocalDate, iamConfirmedOn: LocalDate?) = JSONObject()
        .put("use_rules", JSONArray(io.github.daddymean.agentickeyboard.twin.consent.TwinUseRules.RULES))
        .put("rule", "Start when Google's Inactive Account Manager emails the key holders that Keith's Google account " +
            "has been inactive for $IAM_INACTIVE_MONTHS months. The email is only a signal, never a key. Each holder checks " +
            "it is genuine, then the holders confirm with each other by phone or in person and try to reach Keith. " +
            "$THRESHOLD of the $HOLDERS holders then combine their share cards; no single holder can open it.")
        .put("trigger", JSONObject()
            .put("primary", "google-inactive-account-manager")
            .put("inactive_months", IAM_INACTIVE_MONTHS)
            .put("signal_only", true)
            .put("keith_confirmed_plan_on", iamConfirmedOn?.toString() ?: JSONObject.NULL))
        .put("fallback", "If no Google email can come (Inactive Account Manager was changed or dropped, or the account " +
            "was suspended or deleted), the holders apply the rule themselves: Keith out of contact for $RELEASE_MONTHS " +
            "months, or known to have died, and never before not_before.")
        .put("check_in_months", RELEASE_MONTHS)
        .put("last_check_in", lastCheckIn.toString())
        .put("not_before", releaseFrom(lastCheckIn).toString())

    /**
     * Suggested IAM subject and message. It names the plan and what to do, and contains no
     * key, share, passphrase, file location or link: Google and anyone reading the email
     * learn only that the plan exists.
     */
    const val IAM_SUBJECT = "Keith's legacy-twin plan: signal to start"
    const val IAM_MESSAGE =
        "This is the automatic signal Keith set up for his legacy-twin plan. It is not a key and it asks you to do " +
            "nothing online: don't click links or sign in anywhere because of this email.\n\n" +
            "1. Check it is genuine: it came from a google.com address, Gmail's details show mailed-by and signed-by " +
            "google.com, and Google's footer names Keith's own address.\n" +
            "2. Phone the other key holders on numbers you already know, and try to reach Keith and the family.\n" +
            "3. Only if two of you agree, follow the instructions that came with your share card.\n\n" +
            "Nothing secret is in this email or in Google."

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
