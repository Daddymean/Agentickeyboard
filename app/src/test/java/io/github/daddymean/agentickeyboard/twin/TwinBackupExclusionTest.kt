package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * KEYBOARD-024: the twin store is in `no_backup/` under the app's data root, and its
 * Keystore key cannot be exported. The rules must also exclude that root (and files
 * and databases, in case the store is ever moved) from cloud backup, device-to-device
 * transfer and legacy full backup; the manifest must keep backup off.
 */
class TwinBackupExclusionTest {
    private val required = setOf("root", "file", "database", "device_root", "device_file", "device_database")

    @Test
    fun cloudBackupAndDeviceTransferExcludeTheTwinStore() {
        val doc = parse("res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val el = doc.getElementsByTagName(section).item(0) as Element
            assertTrue("$section excludes the twin store", excluded(el).containsAll(required))
        }
    }

    @Test
    fun legacyBackupExcludesTheTwinStore() {
        assertTrue(excluded(parse("res/xml/backup_rules.xml").documentElement).containsAll(required))
    }

    @Test
    fun manifestKeepsBackupOffAndTheTwinScreenPrivate() {
        val manifest = File(base(), "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        val twin = Regex("<activity\\s+android:name=\"\\.TwinActivity\"[^>]*>").find(manifest)!!.value
        assertTrue(twin.contains("android:exported=\"false\""))
    }

    private fun excluded(rules: Element): Set<String> {
        val nodes = rules.getElementsByTagName("exclude")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("path") in setOf(".", "./") }
            .map { it.getAttribute("domain") }.toSet()
    }

    private fun base(): File = listOf(File("src/main"), File("app/src/main")).first { it.exists() }

    private fun parse(path: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(base(), path))
}
