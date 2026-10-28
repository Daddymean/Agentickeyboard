package io.github.daddymean.agentickeyboard.util

import org.junit.Test
import org.junit.Assert.assertEquals

class StringExtensionsTest {
    @Test
    fun testWordCount() {
        assertEquals(2, "Hello world".wordCount())
        assertEquals(2, "Hello world  ".wordCount())
        assertEquals(0, "".wordCount())
        assertEquals(0, "  ".wordCount())
        assertEquals(1, "  hello".wordCount())
        assertEquals(1, "hello".wordCount())
        assertEquals(1, "hello  ".wordCount())
        assertEquals(1, "  hello  ".wordCount())
        assertEquals(7, "a b c d e f g".wordCount())
        assertEquals(4, "This\nis\ta\n\ttest".wordCount())
    }
}
