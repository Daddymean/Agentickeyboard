package io.github.daddymean.agentickeyboard.util

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Decides whether an editor must be handled as sensitive: no learning, no
 * history, no clipboard capture and no AI. Password-type fields are always
 * sensitive. So is any editor that sets [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING],
 * the flag browsers' incognito tabs and privacy-conscious apps use to ask the
 * keyboard not to learn from or remember what is typed (KEYBOARD-004).
 *
 * Pure integer logic so it can be unit-tested without an Android runtime.
 */
object EditorPrivacy {

    fun isSensitiveEditor(inputType: Int, imeOptions: Int): Boolean =
        isPasswordInputType(inputType) || requestsNoPersonalizedLearning(imeOptions)

    fun requestsNoPersonalizedLearning(imeOptions: Int): Boolean =
        (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
