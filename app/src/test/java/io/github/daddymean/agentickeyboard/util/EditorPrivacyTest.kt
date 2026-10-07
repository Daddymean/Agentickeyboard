package io.github.daddymean.agentickeyboard.util

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPrivacyTest {

    private val plainText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    @Test
    fun `incognito flag makes an ordinary text field sensitive`() {
        val incognito = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        assertTrue(EditorPrivacy.requestsNoPersonalizedLearning(incognito))
        assertTrue(EditorPrivacy.isSensitiveEditor(plainText, incognito))
    }

    @Test
    fun `ordinary field without the flag stays normal`() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertFalse(EditorPrivacy.requestsNoPersonalizedLearning(options))
        assertFalse(EditorPrivacy.isSensitiveEditor(plainText, options))
        assertFalse(EditorPrivacy.isSensitiveEditor(plainText, EditorInfo.IME_NULL))
    }

    @Test
    fun `password variations remain sensitive regardless of options`() {
        assertTrue(EditorPrivacy.isSensitiveEditor(password, EditorInfo.IME_NULL))
        assertTrue(
            EditorPrivacy.isSensitiveEditor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, 0
            )
        )
        assertTrue(
            EditorPrivacy.isSensitiveEditor(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 0
            )
        )
        assertTrue(
            EditorPrivacy.isSensitiveEditor(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0
            )
        )
    }

    @Test
    fun `number field without password variation is not sensitive`() {
        assertFalse(EditorPrivacy.isPasswordInputType(InputType.TYPE_CLASS_NUMBER))
        assertFalse(EditorPrivacy.isPasswordInputType(InputType.TYPE_CLASS_PHONE))
    }

    @Test
    fun `flag constant is the documented platform bit`() {
        assertEquals(0x1000000, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }
}
