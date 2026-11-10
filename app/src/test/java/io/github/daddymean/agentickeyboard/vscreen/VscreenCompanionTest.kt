package io.github.daddymean.agentickeyboard.vscreen

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VscreenCompanionTest {

    private val token = "vscreen-test-token-abcdef"
    private val settingsPkg = "com.android.settings"
    private val bankPkg = "com.example.bank"

    private fun action(
        enabled: Boolean = true,
        token: String? = this.token,
        host: String = "127.0.0.1",
        port: Int = 18765,
        targetPackage: String? = settingsPkg,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        hints: List<String?> = emptyList(),
        secureWindow: Boolean = false,
        secureApps: Set<String> = emptySet(),
        args: List<String> = listOf("launch", "Settings"),
    ) = VscreenAction(
        enabled = enabled,
        token = token,
        host = host,
        port = port,
        targetPackage = targetPackage,
        inputType = inputType,
        imeOptions = imeOptions,
        hints = hints,
        secureWindow = secureWindow,
        ownPackage = "io.github.daddymean.agentickeyboard",
        secureApps = secureApps,
        args = args,
    )

    @Test
    fun `default-off action is refused and builds no request`() {
        val decided = VscreenCompanion.plan(VscreenAction())
        val refuse = decided as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.DISABLED, refuse.reason)
    }

    @Test
    fun `enabled loopback launch is allowed with token only in Authorization`() {
        val decided = VscreenCompanion.plan(action())
        val allow = decided as VscreenDecision.Allow
        assertEquals("POST", allow.request.method)
        assertEquals("http://127.0.0.1:18765/v1/run", allow.request.url)
        assertFalse(allow.request.url.contains("?"))
        assertFalse(allow.request.url.contains("token"))
        assertFalse(allow.request.url.contains(token))
        assertFalse(allow.request.body.contains(token))
        assertEquals(setOf("Authorization"), allow.request.headers.keys)
        assertEquals("Bearer $token", allow.request.headers["Authorization"])
        assertEquals("{\"args\":[\"launch\",\"Settings\"]}", allow.request.body)
    }

    @Test
    fun `localhost alias is accepted`() {
        val allow = VscreenCompanion.plan(action(host = "localhost")) as VscreenDecision.Allow
        assertEquals("http://localhost:18765/v1/run", allow.request.url)
    }

    @Test
    fun `non-loopback host is refused`() {
        val refuse = VscreenCompanion.plan(action(host = "8.8.8.8")) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.BAD_HOST, refuse.reason)
    }

    @Test
    fun `blank token is refused`() {
        val refuse = VscreenCompanion.plan(action(token = "  ")) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.MISSING_TOKEN, refuse.reason)
    }

    @Test
    fun `password field is refused`() {
        val refuse = VscreenCompanion.plan(
            action(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        ) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.PRIVACY, refuse.reason)
        assertTrue(refuse.detail.contains("PASSWORD"))
    }

    @Test
    fun `incognito field is refused`() {
        val refuse = VscreenCompanion.plan(
            action(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        ) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.PRIVACY, refuse.reason)
        assertTrue(refuse.detail.contains("INCOGNITO"))
    }

    @Test
    fun `otp hint is refused`() {
        val refuse = VscreenCompanion.plan(
            action(hints = listOf("smsOTPCode"))
        ) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.PRIVACY, refuse.reason)
        assertTrue(refuse.detail.contains("SENSITIVE_HINT") || refuse.detail.contains("ONE_TIME_CODE"))
    }

    @Test
    fun `one-time number code is refused`() {
        val refuse = VscreenCompanion.plan(
            action(
                inputType = InputType.TYPE_CLASS_NUMBER,
                hints = listOf("verification code"),
            )
        ) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.PRIVACY, refuse.reason)
        assertTrue(refuse.detail.contains("ONE_TIME_CODE") || refuse.detail.contains("SENSITIVE_HINT"))
    }

    @Test
    fun `payment checkout hint is refused`() {
        val refuse = VscreenCompanion.plan(action(hints = listOf("Pay now"))) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.PRIVACY, refuse.reason)
        assertTrue(refuse.detail.contains("PAYMENT"))
    }

    @Test
    fun `credit card hint is refused`() {
        val refuse = VscreenCompanion.plan(action(hints = listOf("creditCardNumber"))) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.PRIVACY, refuse.reason)
        assertTrue(refuse.detail.contains("SENSITIVE_HINT"))
    }

    @Test
    fun `secure banking package is refused`() {
        val refuse = VscreenCompanion.plan(
            action(targetPackage = bankPkg, secureApps = setOf(bankPkg))
        ) as VscreenDecision.Refuse
        assertEquals(VscreenDecision.Reason.PRIVACY, refuse.reason)
        assertTrue(refuse.detail.contains("SECURE_WINDOW"))
    }

    @Test
    fun `token normalize rejects inner spaces and blanks`() {
        assertNull(VscreenToken.normalize(null))
        assertNull(VscreenToken.normalize("  "))
        assertNull(VscreenToken.normalize("abc def"))
        assertEquals(token, VscreenToken.normalize("  $token  "))
        assertEquals("vscr…cdef", VscreenToken.mask(token))
    }
}
