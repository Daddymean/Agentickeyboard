package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardSetupStatusTest {

    private val pkg = "io.github.daddymean.agentickeyboard"

    @Test
    fun `fresh install is neither enabled nor selected`() {
        val status = KeyboardSetupStatus.of(pkg, listOf("com.google.android.inputmethod.latin"),
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")
        assertEquals(KeyboardSetupStatus(enabled = false, selected = false), status)
    }

    @Test
    fun `enabled but another keyboard active`() {
        val status = KeyboardSetupStatus.of(pkg, listOf("com.google.android.inputmethod.latin", pkg),
            "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME")
        assertEquals(KeyboardSetupStatus(enabled = true, selected = false), status)
    }

    @Test
    fun `selected implies enabled even if the enabled list is unreadable`() {
        val status = KeyboardSetupStatus.of(pkg, emptyList(), "$pkg/.service.AgenticKeyboardService")
        assertEquals(KeyboardSetupStatus(enabled = true, selected = true), status)
    }

    @Test
    fun `a package that merely starts with ours does not count`() {
        val status = KeyboardSetupStatus.of(pkg, listOf("$pkg.evil"), "$pkg.evil/.Ime")
        assertEquals(KeyboardSetupStatus(enabled = false, selected = false), status)
    }

    @Test
    fun `unreadable default input method is not selected`() {
        assertEquals(KeyboardSetupStatus(enabled = true, selected = false), KeyboardSetupStatus.of(pkg, listOf(pkg), null))
        assertEquals(KeyboardSetupStatus(enabled = false, selected = false), KeyboardSetupStatus.of(pkg, emptyList(), ""))
    }
}
