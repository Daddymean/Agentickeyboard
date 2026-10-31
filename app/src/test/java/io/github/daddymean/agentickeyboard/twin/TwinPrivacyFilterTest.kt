package io.github.daddymean.agentickeyboard.twin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.github.daddymean.agentickeyboard.twin.TwinPrivacyFilter.Field
import io.github.daddymean.agentickeyboard.twin.TwinPrivacyFilter.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinPrivacyFilterTest {
    private val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    private val number = InputType.TYPE_CLASS_NUMBER
    private fun field(
        inputType: Int = text,
        imeOptions: Int = EditorInfo.IME_ACTION_SEND,
        hints: List<String?> = emptyList(),
        pkg: String? = "com.whatsapp",
        secure: Boolean = false
    ) = Field(inputType, imeOptions, hints, pkg, secure)

    @Test fun `ordinary chat field allows capture`() {
        assertTrue(TwinPrivacyFilter.reasons(field(hints = listOf("Message"))).isEmpty())
        assertFalse(TwinPrivacyFilter.blocksCapture(field(hints = listOf("Type a message", "Shipping notes"))))
    }

    @Test fun `password and incognito still block`() {
        assertEquals(setOf(Reason.PASSWORD), TwinPrivacyFilter.reasons(field(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertEquals(setOf(Reason.PASSWORD), TwinPrivacyFilter.reasons(field(inputType = number or InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
        assertEquals(setOf(Reason.INCOGNITO), TwinPrivacyFilter.reasons(field(imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)))
    }

    @Test fun `secure window and our own screens block`() {
        assertEquals(setOf(Reason.SECURE_WINDOW), TwinPrivacyFilter.reasons(field(secure = true)))
        assertTrue(TwinPrivacyFilter.blocksCapture(field(pkg = "com.chase.sig.android"), secureApps = setOf("com.chase.sig.android")))
        assertTrue(TwinPrivacyFilter.blocksCapture(field(pkg = "me.app"), ownPackage = "me.app"))
        assertFalse(TwinPrivacyFilter.blocksCapture(field(pkg = null), secureApps = setOf("com.chase.sig.android")))
    }

    @Test fun `autofill hints for passwords cards and codes block`() {
        for (h in listOf("password", "newPassword", "creditCardNumber", "creditCardSecurityCode", "creditCardExpirationDate",
            "smsOTPCode", "one-time-code", "postalCode_cc-number", "Enter PIN", "CVV", "Verification code", "SSN", "Passcode")) {
            assertTrue(h, TwinPrivacyFilter.reasons(field(hints = listOf(h))).contains(Reason.SENSITIVE_HINT))
        }
    }

    @Test fun `words that merely contain a token do not block`() {
        for (h in listOf("Shipping address", "Spinach recipe", "Topic", "Pinned note", "Discover more")) {
            assertFalse(h, TwinPrivacyFilter.blocksCapture(field(hints = listOf(h))))
        }
    }

    @Test fun `short numeric code fields block as one-time codes`() {
        assertTrue(TwinPrivacyFilter.reasons(field(inputType = number, hints = listOf("Code"))).contains(Reason.ONE_TIME_CODE))
        assertTrue(TwinPrivacyFilter.reasons(field(inputType = number, hints = listOf("6-digit code"))).contains(Reason.ONE_TIME_CODE))
        assertFalse(TwinPrivacyFilter.blocksCapture(field(inputType = number, hints = listOf("Quantity"))))
        assertFalse(TwinPrivacyFilter.blocksCapture(field(hints = listOf("Promo code idea for dinner"))))
    }

    @Test fun `null and blank hints are ignored`() {
        assertFalse(TwinPrivacyFilter.blocksCapture(field(hints = listOf(null, "", "  "))))
    }
}
