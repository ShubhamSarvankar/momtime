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
        // The android store, its journal and its quarantined copy never leave the device (ADR 0048). The
        // rules are an allow-list, so the store is out by not being included. Lint rejects an exclude
        // that is not under an include, so there is none to check. What must hold is that no include
        // names a store file, and that no include is a prefix of one: lint treats an include path as a
        // prefix, and so may a backup agent. Each store file is checked on its own.
        val includedPaths = children(section, "include").map { it.second }
        for (file in listOf(
            DatabaseFiles.STORE_NAME,
            DatabaseFiles.STORE_JOURNAL_NAME,
            DatabaseFiles.STORE_CORRUPT_NAME,
        )) {
            assertTrue("$label: $file must not be included", file !in includedPaths)
            assertTrue(
                "$label: an include is a prefix of $file: ${includedPaths.filter { file.startsWith(it) }}",
                includedPaths.none { file.startsWith(it) },
            )
        }
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
        assertEquals("momtime_android.db", DatabaseFiles.STORE_NAME)
        assertEquals(DatabaseFiles.STORE_NAME + "-journal", DatabaseFiles.STORE_JOURNAL_NAME)
        assertEquals(DatabaseFiles.STORE_NAME + ".corrupt", DatabaseFiles.STORE_CORRUPT_NAME)
    }
}
