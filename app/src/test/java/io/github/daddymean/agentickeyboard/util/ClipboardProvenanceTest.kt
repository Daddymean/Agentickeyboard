package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardProvenanceTest {
    private val eth1 = "0x52908400098527886E0F7030069857D2E4169EE7"
    private val eth2 = "0x8617E340B3D01FA5F11F306F4090FD50E238070D"
    private val btc = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val iban = "DE89370400440532013000"

    @Test fun detectsAddressKinds() {
        assertEquals(AddressKind.ETH, ClipboardProvenance.detect(eth1))
        assertEquals(AddressKind.BTC, ClipboardProvenance.detect(" $btc\n"))
        assertEquals(AddressKind.IBAN, ClipboardProvenance.detect(iban))
        assertNull(ClipboardProvenance.detect("see you at 5"))
    }

    @Test fun samePasteIsOk() {
        val p = ClipboardProvenance()
        p.observe(eth1, "com.wallet", 0, sensitive = false)
        assertEquals(ClipboardProvenance.Verdict.Ok, p.checkPaste(eth1, 60_000))
    }

    @Test fun swappedAddressWarns() {
        val p = ClipboardProvenance()
        p.observe(eth1, "com.wallet", 0, sensitive = false)
        val v = p.checkPaste(eth2, 5 * 60_000)
        assertTrue(v is ClipboardProvenance.Verdict.Changed)
        v as ClipboardProvenance.Verdict.Changed
        assertEquals(5, v.minutesAgo)
        assertEquals("0x86…070D", v.preview)
        assertTrue(v.message.contains("wallet"))
    }

    @Test fun differentKindOrNoHistoryIsOk() {
        val p = ClipboardProvenance()
        assertEquals(ClipboardProvenance.Verdict.Ok, p.checkPaste(eth2, 0))
        p.observe(btc, null, 0, sensitive = false)
        assertEquals(ClipboardProvenance.Verdict.Ok, p.checkPaste(eth2, 1000))
    }

    @Test fun oldObservationExpires() {
        val p = ClipboardProvenance(windowMillis = 60_000)
        p.observe(eth1, null, 0, sensitive = false)
        assertEquals(ClipboardProvenance.Verdict.Ok, p.checkPaste(eth2, 120_000))
    }

    @Test fun sensitiveClipsAreNeverRecorded() {
        val p = ClipboardProvenance()
        p.observe(eth1, "com.vault", 0, sensitive = true)
        assertEquals(ClipboardProvenance.Verdict.Ok, p.checkPaste(eth2, 1000))
    }

    @Test fun storesFingerprintNotText() {
        val fp = ClipboardProvenance.fingerprint(eth1)
        assertEquals(64, fp.length)
        assertFalse(fp.contains("52908400"))
    }
}
