package io.github.daddymean.agentickeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Typing data (writing logs, vocabulary, clipboard history, snippets, databases,
 * preferences, files) must never leave the device through Android backup or
 * device-to-device transfer. On Android 12+ `allowBackup="false"` does not stop
 * device transfer, so the backup rule files have to exclude every domain.
 */
class AppDataRulesTest {

    private val allDomains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref"
    )

    @Test
    fun manifestDisablesBackupAndReferencesBothRuleFiles() {
        val application = parse("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element

        assertEquals("false", application.getAttributeNS(ANDROID_NS, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(ANDROID_NS, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(ANDROID_NS, "fullBackupContent"))
    }

    @Test
    fun cloudBackupExcludesEveryDomain() {
        assertExcludesEverything(section(parse("res/xml/data_extraction_rules.xml"), "cloud-backup"))
    }

    @Test
    fun deviceTransferExcludesEveryDomain() {
        assertExcludesEverything(section(parse("res/xml/data_extraction_rules.xml"), "device-transfer"))
    }

    @Test
    fun legacyFullBackupContentExcludesEveryDomain() {
        val root = parse("res/xml/backup_rules.xml").documentElement
        assertEquals("full-backup-content", root.tagName)
        assertExcludesEverything(root)
    }

    private fun assertExcludesEverything(rules: Element) {
        val label = rules.tagName
        assertTrue("<$label> must not <include> anything", children(rules, "include").isEmpty())
        val excludedWholeDomains = children(rules, "exclude")
            .filter { it.getAttribute("path") in setOf(".", "./") }
            .map { it.getAttribute("domain") }
            .toSet()
        assertEquals("<$label> must exclude every domain", allDomains, excludedWholeDomains)
    }

    private fun section(doc: Document, name: String): Element {
        val matches = children(doc.documentElement, name)
        assertEquals("expected exactly one <$name> section", 1, matches.size)
        return matches.single()
    }

    private fun children(parent: Element, tag: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).map { nodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }
    }

    private fun parse(pathInMain: String): Document {
        // Gradle runs unit tests from the module directory; IDEs sometimes use the repo root.
        val file = listOf(File("src/main/$pathInMain"), File("app/src/main/$pathInMain")).firstOrNull { it.isFile }
            ?: throw AssertionError("cannot find src/main/$pathInMain from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
