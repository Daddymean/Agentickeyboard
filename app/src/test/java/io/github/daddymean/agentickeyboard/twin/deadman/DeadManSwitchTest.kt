package io.github.daddymean.agentickeyboard.twin.deadman

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** KEYBOARD-027 rows 1–3 and 5 (org.json needs Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeadManSwitchTest {
    private val today = LocalDate.of(2026, 10, 17)

    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test
    fun createdSetIsTwoOfThreeGuardiansForKidsOneToSix() {
        val r = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
        val k = r.state.keyFile
        assertEquals(DeadManSwitch.KEY_FILE_FORMAT, k.getString("format"))
        assertEquals(2, k.getInt("threshold"))
        assertEquals(1, k.getInt("epoch"))
        assertEquals(listOf("Guardian 1", "Guardian 2", "Guardian 3"), r.cards.map { it.first.label })
        assertEquals((1..6).map { "Kid $it" }, (0 until 6).map { k.getJSONArray("recipients").getString(it) })
        assertEquals("2027-04-17", k.getJSONObject("policy").getString("not_before"))
        // The key file holds no usable secret: no DEK, no KEK, no share bytes.
        val text = k.toString()
        assertFalse(text.contains(hex(r.state.dataKey)))
        for ((_, c) in r.cards) assertFalse(text.contains(Base32.encode(c.share.y)))
        // Any two cards unwrap the data key; one doesn't.
        val cs = r.cards.map { it.second }
        for (i in 0..2) for (j in i + 1..2) assertArrayEquals(r.state.dataKey, DeadManSwitch.unwrap(k, listOf(cs[i], cs[j])))
        fails { DeadManSwitch.unwrap(k, listOf(cs[0])) }
    }

    @Test
    fun rekeyKeepsTheArchiveKeyAndRevokesOldCards() {
        val first = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
        val kidBirth = LocalDate.of(2008, 10, 17) // 18 today
        val second = DeadManSwitch.replaceHolder(first.state, 2, "Kid 1", kidBirth, today)
        assertArrayEquals(first.state.dataKey, second.state.dataKey)
        assertArrayEquals(first.state.archiveId, second.state.archiveId)
        assertEquals(2, second.state.epoch)
        assertNotEquals(first.state.setId, second.state.setId)
        assertEquals(listOf("Guardian 1", "Kid 1", "Guardian 3"), second.state.holders.map { it.label })
        val newCards = second.cards.map { it.second }
        val oldCards = first.cards.map { it.second }
        assertArrayEquals(second.state.dataKey, DeadManSwitch.unwrap(second.state.keyFile, listOf(newCards[0], newCards[1])))
        // Every pair of old cards fails against the new key file, as do mixed pairs.
        for (i in 0..2) for (j in i + 1..2) fails { DeadManSwitch.unwrap(second.state.keyFile, listOf(oldCards[i], oldCards[j])) }
        for (o in oldCards) for (n in newCards) fails { DeadManSwitch.unwrap(second.state.keyFile, listOf(o, n)) }
        // Old cards relabelled as the new set still fail (the KEK changed, not just the label).
        val forged = oldCards.take(2).map { it.copy(setId = second.state.setId, epoch = 2) }
        fails { DeadManSwitch.unwrap(second.state.keyFile, forged) }
        // A tampered key file fails.
        val t = JSONObject(second.state.keyFile.toString()).put("archive_id", "00".repeat(16))
        fails { DeadManSwitch.unwrap(t, listOf(newCards[0], newCards[2])) }
    }

    @Test
    fun kidsHoldKeysOnlyFromTheir18thBirthday() {
        val s = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today).state
        fails { DeadManSwitch.replaceHolder(s, 1, "Kid 1", LocalDate.of(2008, 10, 18), today) } // 18 tomorrow
        fails { DeadManSwitch.replaceHolder(s, 1, "Kid 1", null, today) }
        DeadManSwitch.replaceHolder(s, 1, "Kid 1", LocalDate.of(2008, 10, 18), today.plusDays(1))
        // Born 29 Feb: 18 on 28 Feb in a non-leap year (java.time plusYears).
        assertEquals(LocalDate.of(2026, 2, 28), DeadManSwitch.adultFrom(LocalDate.of(2008, 2, 29)))
        assertFalse(DeadManSwitch.isAdult(LocalDate.of(2008, 2, 29), LocalDate.of(2026, 2, 27)))
        // Guardians need no birth date; labels are fixed and unique.
        DeadManSwitch.replaceHolder(s, 3, "Guardian 3", null, today)
        fails { DeadManSwitch.replaceHolder(s, 3, "Guardian 1", null, today) }
        fails { DeadManSwitch.replaceHolder(s, 3, "Uncle Bob", null, today) }
        fails { DeadManSwitch.replaceHolder(s, 9, "Guardian 3", null, today) }
        fails { DeadManSwitch.create(listOf("Guardian 1", "Guardian 2"), today) }
        // Day one: the oldest (17 now) can take over as soon as they turn 18, through every slot.
        var st = s
        for ((x, kid) in listOf(1 to "Kid 1", 2 to "Kid 2", 3 to "Kid 3")) {
            st = DeadManSwitch.replaceHolder(st, x, kid, LocalDate.of(2000, 1, 1), today).state
        }
        assertEquals(listOf("Kid 1", "Kid 2", "Kid 3"), st.holders.map { it.label })
        assertEquals(4, st.epoch)
    }

    @Test
    fun checkInStatusAndDueDates() {
        val s = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today).state
        assertEquals(CheckInStage.OK, DeadManSwitch.status(s, today).stage)
        assertEquals(LocalDate.of(2027, 4, 17), DeadManSwitch.status(s, today).releaseFrom)
        assertEquals(CheckInStage.DUE_SOON, DeadManSwitch.status(s, LocalDate.of(2027, 3, 18)).stage)
        assertEquals(CheckInStage.OVERDUE, DeadManSwitch.status(s, LocalDate.of(2027, 4, 17)).stage)
        val later = DeadManSwitch.checkIn(s, LocalDate.of(2027, 3, 20))
        assertEquals(CheckInStage.OK, DeadManSwitch.status(later, LocalDate.of(2027, 4, 17)).stage)
        assertEquals("2027-09-20", later.keyFile.getJSONObject("policy").getString("not_before"))
        // Month ends: 31 Aug + 6 months = 28/29 Feb.
        assertEquals(LocalDate.of(2027, 2, 28), DeadManSwitch.releaseFrom(LocalDate.of(2026, 8, 31)))
        // Check-in keeps the keys.
        assertEquals(s.setId, later.setId)
        assertArrayEquals(s.dataKey, later.dataKey)
    }

    @Test
    fun googleTriggerIsASignalAndHolderChangesNeedItConfirmedAgain() {
        val s = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today).state
        assertEquals(IamStage.NOT_CONFIRMED, DeadManSwitch.status(s, today).iamStage)
        val p0 = s.keyFile.getJSONObject("policy")
        assertEquals("google-inactive-account-manager", p0.getJSONObject("trigger").getString("primary"))
        assertEquals(6, p0.getJSONObject("trigger").getInt("inactive_months"))
        assertTrue(p0.getJSONObject("trigger").getBoolean("signal_only"))
        assertTrue(p0.getJSONObject("trigger").isNull("keith_confirmed_plan_on"))
        assertTrue(p0.getString("fallback").contains("suspended"))

        val c = DeadManSwitch.confirmIam(s, today)
        val st = DeadManSwitch.status(c, today)
        assertEquals(IamStage.OK, st.iamStage)
        assertEquals(LocalDate.of(2027, 10, 17), st.iamReviewDue)
        assertEquals(IamStage.REVIEW_DUE, DeadManSwitch.status(c, LocalDate.of(2027, 10, 17)).iamStage)
        assertEquals(today.toString(), c.keyFile.getJSONObject("policy").getJSONObject("trigger").getString("keith_confirmed_plan_on"))
        // Confirming touches no key material, and check-in keeps the confirmation.
        assertEquals(s.setId, c.setId)
        assertArrayEquals(s.dataKey, c.dataKey)
        assertEquals(today, DeadManSwitch.checkIn(c, today.plusDays(3)).iamConfirmedOn)

        // Reissuing with the same holders keeps it; a new holder needs adding as a Google contact.
        assertEquals(today, DeadManSwitch.replaceHolder(c, 1, "Guardian 1", null, today).state.iamConfirmedOn)
        val kid = DeadManSwitch.replaceHolder(c, 1, "Kid 1", LocalDate.of(2000, 1, 1), today).state
        assertEquals(null, kid.iamConfirmedOn)
        assertEquals(IamStage.NOT_CONFIRMED, DeadManSwitch.status(kid, today).iamStage)
        assertTrue(kid.keyFile.getJSONObject("policy").getJSONObject("trigger").isNull("keith_confirmed_plan_on"))

        // Older saved state (before the Google trigger) still loads.
        val old = s.toJson().also { it.remove("iam_confirmed_on") }
        assertEquals(null, DeadManState.fromJson(old).iamConfirmedOn)
        assertEquals(c, DeadManState.fromJson(JSONObject(c.toJson().toString())))
    }

    @Test
    fun googleMessageAndCardsCarryNoSecret() {
        val r = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
        val msg = DeadManSwitch.IAM_SUBJECT + "\n" + DeadManSwitch.IAM_MESSAGE
        assertFalse(msg.contains("AKDM1"))
        assertFalse(Regex("[0-9a-fA-F]{16,}").containsMatchIn(msg))
        assertFalse(Regex("https?://").containsMatchIn(msg))
        assertFalse(msg.contains(r.state.setId, ignoreCase = true))
        for (word in listOf("passphrase:", ".aktx", ".json")) assertFalse(msg.contains(word))
        assertTrue(msg.contains("not a key"))
        // The guardian card text explains the signal, the checks and the cross-check.
        val card = io.github.daddymean.agentickeyboard.DeadManActivity.cardText("Guardian 1", r.cards[0].second.encode(), 1)
        for (k in listOf("google.com", "signed-by", "other holders", "only a signal", "not_before")) assertTrue(k, card.contains(k))
    }

    @Test
    fun stateRoundTrips() {
        val s = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today).state
        assertEquals(s, DeadManState.fromJson(JSONObject(s.toJson().toString())))
        assertFalse(s.toJson().toString().contains("AKDM1"))
    }
}
