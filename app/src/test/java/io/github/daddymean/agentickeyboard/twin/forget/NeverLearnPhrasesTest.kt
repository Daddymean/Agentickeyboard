package io.github.daddymean.agentickeyboard.twin.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.NeverLearnLists
import io.github.daddymean.agentickeyboard.twin.NeverLearnPhrases
import io.github.daddymean.agentickeyboard.twin.TwinPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NeverLearnPhrasesTest {
    private val key = FakePhraseKey()

    @Test
    fun matchesWholeWordsInARowFoldingCaseAndAccents() {
        val e = setOf(NeverLearnPhrases.entry("Café Nero", key)!!)
        assertTrue(NeverLearnPhrases.matches("meet at CAFE nero?", e, key))
        assertFalse(NeverLearnPhrases.matches("nero cafe", e, key))
        assertFalse(NeverLearnPhrases.matches("cafeteria nero", e, key))
        assertFalse(NeverLearnPhrases.matches("anything", emptySet(), key))
    }

    @Test
    fun storesAKeyedMacNotTheWordsOrABareHash() {
        val e = NeverLearnPhrases.entry("my secret diagnosis", key)!!
        assertTrue(e.startsWith("v2:3:"))
        assertFalse(e.contains("secret") || e.contains("diagnosis"))
        assertEquals(e, NeverLearnPhrases.entry("  My SECRET diagnosis! ", key))
        // Not guessable without the key: the old unkeyed SHA-256 and a different key both differ.
        val bare = MessageDigest.getInstance("SHA-256").digest("never-learn-v1\u0000my\u0000secret\u0000diagnosis".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertFalse(e.contains(bare))
        assertNotEquals(e, NeverLearnPhrases.entry("my secret diagnosis", FakePhraseKey()))
        assertFalse(NeverLearnPhrases.matches("my secret diagnosis", setOf(e), FakePhraseKey()))
    }

    @Test
    fun refusesEmptyOrOverlongPhrases() {
        assertNull(NeverLearnPhrases.entry("  ...  ", key))
        assertNull(NeverLearnPhrases.entry((1..9).joinToString(" ") { "w$it" }, key))
        assertTrue(NeverLearnPhrases.contains("one two three", "two three"))
        assertFalse(NeverLearnPhrases.contains("one two three", "three two"))
    }

    @Test
    fun oldUnkeyedEntriesAreDroppedFromSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = context.getSharedPreferences(TwinPreferences.PREFS, Context.MODE_PRIVATE)
        raw.edit().clear().putStringSet(TwinPreferences.KEY_NEVER_LEARN_PHRASES, setOf("2:" + "ab".repeat(32))).commit()
        val prefs = TwinPreferences(context)
        assertTrue(prefs.neverLearnPhrases.isEmpty())
        assertTrue(raw.getStringSet(TwinPreferences.KEY_NEVER_LEARN_PHRASES, null)!!.isEmpty())

        val lists = NeverLearnLists.of(prefs, key)
        assertTrue(lists.addPhrase("Kingfisher crew"))
        assertFalse(lists.addPhrase("   "))
        val stored = raw.getStringSet(TwinPreferences.KEY_NEVER_LEARN_PHRASES, null)!!
        assertEquals(1, stored.size)
        assertFalse(stored.single().contains("kingfisher", ignoreCase = true))
        assertTrue(NeverLearnPhrases.matches("hi Kingfisher crew!", prefs.neverLearnPhrases, key))
        raw.edit().clear().commit()
    }
}
