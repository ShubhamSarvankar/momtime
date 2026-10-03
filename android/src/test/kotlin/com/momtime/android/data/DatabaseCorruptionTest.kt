package com.momtime.android.data

import com.momtime.android.di.CorruptionMarker
import com.momtime.android.di.DatabaseFiles
import com.momtime.shared.data.ScheduleTemplateRepository
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * A corrupt database is moved aside, not deleted (ADR 0044). The framework's default `onCorruption`
 * deletes the file, and this database is the device's record of authority. Reminders must keep
 * working, so a fresh database is opened in its place, and a marker records what happened for the
 * reliability view to read later.
 *
 * Corruption here is a file whose content is not a database, which SQLite reports as
 * SQLITE_NOTADB and the framework raises as a corruption exception on open.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class DatabaseCorruptionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val graphs = mutableListOf<TestGraph>()
    private val databaseFile get() = context.getDatabasePath(DatabaseFiles.NAME)
    private val corruptFile get() = File(databaseFile.parentFile, DatabaseFiles.CORRUPT_NAME)
    private val markerFile get() = File(context.noBackupFilesDir, DatabaseFiles.CORRUPTION_MARKER_NAME)

    @Before
    fun setUp() = assertNativeSqliteMode()

    @After
    fun tearDown() = graphs.forEach { it.close() }

    private fun graph() = TestGraph(context, DatabaseFiles.NAME).also { graphs.add(it) }

    private fun garbage(seed: Int) = ByteArray(8192) { (it * 31 + seed).toByte() }

    private fun corrupt(bytes: ByteArray) = databaseFile.writeBytes(bytes)

    @Test
    fun `a corrupt database is kept aside and a fresh one opens`() {
        val first = graph()
        first.seedTemplate()
        first.close()
        graphs.remove(first)
        val bytes = garbage(1)
        corrupt(bytes)

        val second = graph()
        // The fresh database is empty, and it works.
        assertNull(second.get<ScheduleTemplateRepository>().findById("tmpl-1"))
        second.seedTemplate()
        assertEquals("tmpl-1", second.get<ScheduleTemplateRepository>().findById("tmpl-1")?.id)

        assertTrue("the corrupt file must be kept as ${DatabaseFiles.CORRUPT_NAME}", corruptFile.isFile)
        assertArrayEquals("the kept file must be the corrupt one, unchanged", bytes, corruptFile.readBytes())
        assertEquals(DatabaseFiles.CORRUPT_NAME, corruptFile.name)
    }

    @Test
    fun `corruption leaves a marker with the time and whether the file was kept`() {
        corrupt(garbage(2))
        graph().get<ScheduleTemplateRepository>().findById("x")

        val marker = CorruptionMarker.read(markerFile)
        assertNotNull("no corruption marker was written to the no-backup directory", marker)
        assertEquals(testClock.now(), marker!!.corruptedAt)
        assertTrue(marker.preserved)
        assertFalse(
            "the marker must not be in the database directory, which is backed up",
            File(databaseFile.parentFile, markerFile.name).exists(),
        )
    }

    @Test
    fun `only the most recent corrupt copy is kept`() {
        // An older corrupt copy from an earlier incident is already there.
        databaseFile.parentFile!!.mkdirs()
        corruptFile.writeBytes(garbage(3))
        val latest = garbage(4)
        corrupt(latest)

        graph().get<ScheduleTemplateRepository>().findById("x")

        val copies = databaseFile.parentFile!!.listFiles { f -> f.name.contains("corrupt") }.orEmpty()
        assertEquals("exactly one corrupt copy must remain", 1, copies.size)
        assertArrayEquals("the older copy must be replaced by the latest", latest, corruptFile.readBytes())
    }

    @Test
    fun `a healthy database writes no marker and no copy`() {
        graph().seedTemplate()

        assertFalse(corruptFile.exists())
        assertNull(CorruptionMarker.read(markerFile))
    }
}
