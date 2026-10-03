package com.momtime.android.data

import com.momtime.android.di.CorruptionMarker
import com.momtime.android.di.DatabaseFiles
import com.momtime.android.store.ArmedAlarm
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.shared.data.OccurrenceRepository
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

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

    private val storeFile get() = context.getDatabasePath(DatabaseFiles.STORE_NAME)
    private val storeCorruptFile get() = File(storeFile.parentFile, DatabaseFiles.STORE_CORRUPT_NAME)
    private val storeMarkerFile get() = File(context.noBackupFilesDir, DatabaseFiles.STORE_CORRUPTION_MARKER_NAME)

    // The store is configured like the shared database, so a corrupt store is also kept aside and a fresh
    // one opens. It writes its own marker and leaves the shared database and its marker alone: losing
    // the store loses diagnostics and the armed record, which the watchdog arms again (ADR 0048).
    @Test
    fun `a corrupt android store is kept aside and the shared database is untouched`() {
        val first = TestGraph(context, DatabaseFiles.NAME, storeName = DatabaseFiles.STORE_NAME).also { graphs.add(it) }
        first.seedTemplate()
        first.get<ArmedAlarmRepository>().replace(
            ArmedAlarm(1, testClock.now(), testClock.now(), bootCount = 1, exactAllowed = true),
        )
        first.close()
        graphs.remove(first)
        val bytes = garbage(5)
        storeFile.writeBytes(bytes)

        val second =
            TestGraph(
                context,
                DatabaseFiles.NAME,
                storeName = DatabaseFiles.STORE_NAME,
            ).also { graphs.add(it) }

        assertNull("the fresh store is empty", second.get<ArmedAlarmRepository>().current())
        assertEquals(
            "the shared database must be untouched",
            "tmpl-1",
            second.get<ScheduleTemplateRepository>().findById("tmpl-1")?.id,
        )
        assertArrayEquals(bytes, storeCorruptFile.readBytes())
        assertNotNull(CorruptionMarker.read(storeMarkerFile))
        assertFalse("the shared database must not be quarantined", corruptFile.exists())
        assertNull("the shared marker is for the shared database only", CorruptionMarker.read(markerFile))
    }

    // Corruption found in the middle of a query goes through the same handler as corruption found on open:
    // the framework reports it from SQLiteQuery.fillWindow and from the statement paths (ADR 0049). The
    // exception still reaches the caller once, and the file is kept aside. The damage leaves the schema
    // and the first page alone and overwrites later pages, so the database opens and the first read of an
    // overwritten page fails; the file change counter is overwritten too, so the open connection does not
    // trust the pages it has cached.
    @Test
    fun `corruption found during a query is handled like corruption on open`() {
        val graph = graph()
        val template = graph.seedTemplate()
        val occurrences = graph.get<OccurrenceRepository>()
        var n = 0
        occurrences.materialiseWindow(template, windowStart, windowStart + 400.days) { "occ-${n++}" }
        assertEquals(400, occurrences.findForTemplate(template.id).size)

        val damaged = damageLaterPages()
        var failure: Throwable? = null
        try {
            occurrences.findForTemplate(template.id)
        } catch (expected: Exception) {
            failure = expected
        }

        assertNotNull("a query over a damaged page must fail", failure)
        println(
            "MIDQUERY-CORRUPTION ${generateSequence<Throwable>(
                failure,
            ) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName + ": " + it.message }}",
        )
        assertTrue("the handler must keep the damaged file aside", corruptFile.isFile)
        assertArrayEquals("the kept file must be the damaged one", damaged, corruptFile.readBytes())
        val marker = CorruptionMarker.read(markerFile)
        assertNotNull("the handler must write the marker", marker)
        assertTrue(marker!!.preserved)
    }

    private val windowStart = Instant.parse("2026-01-01T00:00:00Z")

    /** Overwrites the second half of the file and the change counter. Returns the file as it now is. */
    private fun damageLaterPages(): ByteArray {
        val bytes = databaseFile.readBytes()
        for (i in bytes.size / 2 until bytes.size) bytes[i] = 0xFF.toByte()
        // Bytes 24 to 39 of the header are the file change counter and the version-valid-for number.
        for (i in 24 until 40) bytes[i] = (i * 7).toByte()
        databaseFile.writeBytes(bytes)
        return bytes
    }
}
