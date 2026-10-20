package io.github.daddymean.agentickeyboard.twin.consent

import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** KEYBOARD-033 rows 1–3: per-kid opt-in, pause and retirement; the sealed record; the use rules. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinConsentTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 86_400_000L

    @Test
    fun nobodyIsOptedInByDefaultAndEachChoiceIsPerKid() {
        var s = ConsentState()
        assertTrue(TwinConsent.KIDS.none { TwinConsent.mayAsk(s, it) })
        s = TwinConsent.optIn(s, "Kid 1", adult = true, guardianPresent = false, now = 1)
        s = TwinConsent.optIn(s, "Kid 2", adult = false, guardianPresent = true, now = 2)
        assertThrows(IllegalArgumentException::class.java) { TwinConsent.optIn(s, "Kid 3", adult = false, guardianPresent = false, now = 3) }
        assertTrue(TwinConsent.mayAsk(s, "Kid 1"))
        // KEYBOARD-036: Kid 2 is supervised, so a guardian must be present for each session.
        assertFalse(TwinConsent.mayAsk(s, "Kid 2"))
        assertTrue(TwinConsent.mayAsk(s, "Kid 2", "Guardian 3"))
        assertFalse(TwinConsent.mayAsk(s, "Kid 2", "Kid 1"))
        assertFalse(TwinConsent.mayAsk(s, "Kid 3"))
        assertFalse(TwinConsent.mayAsk(s, "Guardian 1"))
        assertTrue(s.of("Kid 2").guardianPresent)

        s = TwinConsent.pause(s, "Kid 1", 10)
        assertFalse(TwinConsent.mayAsk(s, "Kid 1"))
        assertTrue(TwinConsent.mayAsk(s, "Kid 2", "Guardian 1")) // only Kid 1 changed
        s = TwinConsent.resume(s, "Kid 1", 11)
        assertTrue(TwinConsent.mayAsk(s, "Kid 1"))

        s = TwinConsent.decline(s, "Kid 4", 12)
        assertFalse(TwinConsent.mayAsk(s, "Kid 4"))
        assertEquals(listOf("Kid 1", "Kid 2", "Kid 1", "Kid 1", "Kid 4"), s.log.map { it.kid })
    }

    @Test
    fun supervisedModeForMinors() {
        var s = TwinConsent.optIn(ConsentState(), "Kid 4", adult = false, guardianPresent = true, now = 1)
        assertTrue(TwinConsent.needsGuardian(s, "Kid 4"))
        // A guardian can pause it; then only a guardian can resume it.
        assertThrows(IllegalArgumentException::class.java) { TwinConsent.guardianPause(s, "Kid 4", "Kid 1", 2) }
        s = TwinConsent.guardianPause(s, "Kid 4", "Guardian 2", 2)
        assertFalse(TwinConsent.mayAsk(s, "Kid 4", "Guardian 2"))
        assertEquals("Guardian 2", s.of("Kid 4").pausedBy)
        assertThrows(IllegalArgumentException::class.java) { TwinConsent.resume(s, "Kid 4", 3) }
        s = TwinConsent.resume(s, "Kid 4", 3, "Guardian 1")
        assertTrue(TwinConsent.mayAsk(s, "Kid 4", "Guardian 1"))
        // The kid's own pause keeps supervision (it used to drop it).
        s = TwinConsent.resume(TwinConsent.pause(s, "Kid 4", 4), "Kid 4", 5)
        assertTrue(s.of("Kid 4").supervised)
        // The kid can still retire it themselves.
        assertEquals(Status.RETIRED, TwinConsent.retire(s, "Kid 4", 6).of("Kid 4").status)
        // A guardian can't pause an adult's twin.
        val adult = TwinConsent.optIn(ConsentState(), "Kid 1", adult = true, guardianPresent = false, now = 1)
        assertThrows(IllegalArgumentException::class.java) { TwinConsent.guardianPause(adult, "Kid 1", "Guardian 1", 2) }
        // At 18, the kid ends supervision; the birth date is checked, not stored.
        val today = java.time.LocalDate.of(2026, 10, 19)
        assertThrows(IllegalArgumentException::class.java) { TwinConsent.becomeAdult(s, "Kid 4", java.time.LocalDate.of(2010, 1, 1), today, 7) }
        s = TwinConsent.becomeAdult(s, "Kid 4", java.time.LocalDate.of(2008, 10, 19), today, 7)
        assertTrue(TwinConsent.mayAsk(s, "Kid 4"))
        assertEquals("turned 18: supervision ended", s.log.last().note)
        val back = TwinConsent.fromJson(org.json.JSONObject(TwinConsent.toJson(s).toString()))
        assertEquals(s, back)
        // The guardian is named in the log.
        assertTrue(back.log.any { it.by == "Guardian 2" && it.status == Status.PAUSED })
    }

    @Test
    fun retirementIsDignifiedAndHardToUndo() {
        var s = TwinConsent.optIn(ConsentState(), "Kid 5", adult = true, guardianPresent = false, now = 0)
        s = TwinConsent.retire(s, "Kid 5", day)
        assertFalse(TwinConsent.mayAsk(s, "Kid 5"))
        assertThrows(IllegalArgumentException::class.java) { TwinConsent.optIn(s, "Kid 5", adult = true, guardianPresent = false, now = 2 * day) }
        assertFalse(TwinConsent.canBringBack(s, "Kid 5", 10 * day))
        assertThrows(IllegalArgumentException::class.java) { TwinConsent.bringBack(s, "Kid 5", TwinConsent.BRING_BACK, 10 * day) }
        val later = day + TwinConsent.RETIRE_COOLING_OFF_DAYS * day
        assertThrows(IllegalArgumentException::class.java) { TwinConsent.bringBack(s, "Kid 5", "yes", later) }
        s = TwinConsent.bringBack(s, "Kid 5", "BRING BACK", later)
        assertFalse(TwinConsent.mayAsk(s, "Kid 5")) // back to "not asked": they opt in again themselves
        assertEquals(Status.NOT_ASKED, s.of("Kid 5").status)
        // A kid can retire it without ever having turned it on.
        assertEquals(Status.RETIRED, TwinConsent.retire(ConsentState(), "Kid 6", 1).of("Kid 6").status)
    }

    @Test
    fun theRecordIsSealedAndRoundTrips() {
        val f = File(tmp.root, "twin/consent.sealed")
        val store = ConsentStore(f, SoftwareTwinCipher())
        store.update { TwinConsent.optIn(it, "Kid 3", adult = true, guardianPresent = false, now = 7) }
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("Kid 3"))
        val back = store.load()
        assertTrue(TwinConsent.mayAsk(back, "Kid 3"))
        assertEquals(1, back.log.size)
        store.delete()
        assertFalse(f.exists())
        assertEquals(ConsentState(), store.load())
    }

    @Test
    fun useRulesCoverLabelPublicUseAndDecisions() {
        val all = TwinUseRules.RULES.joinToString(" ").lowercase()
        assertTrue(all.contains("public platform"))
        assertTrue(all.contains("always an ai"))
        assertTrue(all.contains("pause or retire"))
        assertTrue(all.contains("inheritance"))
        assertTrue(TwinUseRules.LABEL.contains("AI") && TwinUseRules.LABEL.contains("not Dad"))
    }
}
