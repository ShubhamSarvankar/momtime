package com.momtime.android.backup

import com.momtime.android.di.DatabaseFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Auto Backup rules (ADR 0034, ADR 0044). The shared database goes with the backup, whole and with its
 * rollback journal. The quarantined `.corrupt` copy never does. API 31 and above read
 * `dataExtractionRules`, API 29 and 30 read `fullBackupContent`, so both must say the same thing and
 * the manifest must point at both. The file names come from [DatabaseFiles], so renaming the database
 * without renaming it here fails.
 *
 * This reads the source XML. It shows what the rules say, not what a device's backup agent does with
 * them (MANUAL_CHECKS, backup and restore on a device).
 */
class BackupRulesTest {
    private fun xml(path: String) =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File(path))
            .documentElement

    private fun children(
        parent: Element,
        tag: String,
    ): List<Pair<String, String>> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { i ->
            val node = nodes.item(i) as Element
            node.getAttribute("domain") to node.getAttribute("path")
        }
    }

    private fun assertRules(
        section: Element,
        label: String,
    ) {
        val database = "database"
        assertEquals(
            "$label: the includes must be exactly the database and its journal",
            setOf(database to DatabaseFiles.NAME, database to DatabaseFiles.JOURNAL_NAME),
            children(section, "include").toSet(),
        )
        assertTrue(
            "$label: ${DatabaseFiles.CORRUPT_NAME} must be excluded",
            (database to DatabaseFiles.CORRUPT_NAME) in children(section, "exclude"),
        )
    }

    @Test
    fun `data extraction rules include the database and exclude the corrupt copy`() {
        val root = xml("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", root.tagName)
        for (section in listOf("cloud-backup", "device-transfer")) {
            assertRules(root.getElementsByTagName(section).item(0) as Element, section)
        }
    }

    @Test
    fun `full backup content says the same for API 29 and 30`() {
        val root = xml("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", root.tagName)
        assertRules(root, "full-backup-content")
    }

    @Test
    fun `the manifest allows backup and references both rule files`() {
        val application = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("true", application.getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(android, "dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttributeNS(android, "fullBackupContent"))
    }

    @Test
    fun `the file names are the ones the callback derives`() {
        assertEquals("momtime.db", DatabaseFiles.NAME)
        assertEquals(DatabaseFiles.NAME + "-journal", DatabaseFiles.JOURNAL_NAME)
        assertEquals(DatabaseFiles.NAME + ".corrupt", DatabaseFiles.CORRUPT_NAME)
    }
}
