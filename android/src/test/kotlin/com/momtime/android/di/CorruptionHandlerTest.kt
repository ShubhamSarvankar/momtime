package com.momtime.android.di

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The order the corruption handler works in (ADR 0044, ADR 0049, ADR 0051): the database is closed before
 * its file is moved aside, the marker is durable before the process ends, and the process ends only for
 * corruption found in the middle of a query. On Linux a file can be renamed while it is open, so a
 * handler that moved it first would work there and fail on a platform that refuses, and nothing
 * observable on a JVM file system would tell. This records what is true at the instant `close` and the
 * process end are called instead.
 */
class CorruptionHandlerTest {
    private val directory = Files.createTempDirectory("momtime-corruption").toFile()
    private val database = File(directory, "momtime.db")
    private val corrupt = File(directory, "momtime.db.corrupt")
    private val marker = File(directory, "marker")
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-01-01T00:00:00Z")
        }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private class Observed {
        var closed = 0
        var databasePresentAtClose: Boolean? = null
        var corruptPresentAtClose: Boolean? = null
    }

    /** A database that records the state of the files when it is closed, and fails on anything else. */
    private fun recording(
        path: String?,
        observed: Observed,
        open: Boolean = true,
    ): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPath" -> path
                "isOpen" -> open
                "close" -> {
                    observed.closed++
                    observed.databasePresentAtClose = database.exists()
                    observed.corruptPresentAtClose = corrupt.exists()
                    null
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SupportSQLiteDatabase

    @Test
    fun `the database is closed before its file is moved`() {
        database.writeBytes(ByteArray(64) { it.toByte() })
        val observed = Observed()

        CorruptionHandler(marker, clock).handle(recording(database.path, observed))

        assertEquals("the database must be closed exactly once", 1, observed.closed)
        assertEquals(
            "the file must still be in place when the database is closed",
            true,
            observed.databasePresentAtClose,
        )
        assertEquals("nothing may have been moved aside yet", false, observed.corruptPresentAtClose)
        assertTrue("the file must be moved aside afterwards", corrupt.isFile)
        assertFalse("the original path must be free for a fresh database", database.exists())
        assertNotNull(CorruptionMarker.read(marker))
    }

    @Test
    fun `an in-memory database has nothing to keep and writes no marker`() {
        val observed = Observed()
        var ended = 0

        CorruptionHandler(marker, clock, processEnd = { ended++ }).handle(recording(null, observed))

        assertEquals(1, observed.closed)
        assertFalse(corrupt.exists())
        assertFalse("no marker for a database with no file", marker.exists())
        assertEquals("nothing to replace, so the process is not ended", 0, ended)
    }

    @Test
    fun `the sidecar files go with the corrupt database, and an older copy is replaced`() {
        database.writeBytes(byteArrayOf(1, 2, 3))
        File(directory, "momtime.db-journal").writeBytes(byteArrayOf(9))
        File(directory, "momtime.db-wal").writeBytes(byteArrayOf(9))
        File(directory, "momtime.db-shm").writeBytes(byteArrayOf(9))
        corrupt.writeBytes(byteArrayOf(7, 7, 7, 7))

        CorruptionHandler(marker, clock).handle(recording(database.path, Observed()))

        assertEquals(listOf<Byte>(1, 2, 3), corrupt.readBytes().toList())
        assertFalse(File(directory, "momtime.db-journal").exists())
        assertFalse(File(directory, "momtime.db-wal").exists())
        assertFalse(File(directory, "momtime.db-shm").exists())
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), CorruptionMarker.read(marker)?.corruptedAt)
    }

    // The process ends after the database is closed, the file is kept aside and the marker is readable, so
    // a process that dies straight afterwards leaves all three behind (ADR 0051).
    @Test
    fun `the process ends only after the marker is durable`() {
        database.writeBytes(ByteArray(64) { it.toByte() })
        val observed = Observed()
        var calls = 0
        var markerAtEnd: CorruptionMarker? = null
        var corruptAtEnd = false
        var closedAtEnd = 0
        val end =
            ProcessEnd {
                calls++
                markerAtEnd = CorruptionMarker.read(marker)
                corruptAtEnd = corrupt.isFile
                closedAtEnd = observed.closed
            }

        CorruptionHandler(marker, clock, processEnd = end).handle(recording(database.path, observed))

        assertEquals("the process must end exactly once", 1, calls)
        assertNotNull("the marker must be readable when the process ends", markerAtEnd)
        assertTrue("the damaged copy must be in place when the process ends", corruptAtEnd)
        assertEquals("the database must be closed when the process ends", 1, closedAtEnd)
    }

    // Corruption found while a database is still being opened is replaced by the framework's own retry, so
    // nothing holds a closed database and nothing needs to end or reopen (ADR 0051).
    @Test
    fun `corruption found on open neither ends the process nor reports a closed database`() {
        database.writeBytes(ByteArray(64) { it.toByte() })
        var ended = 0
        var reported = 0

        CorruptionHandler(marker, clock, { ended++ }, { reported++ })
            .handle(recording(database.path, Observed(), open = false))

        assertEquals(0, ended)
        assertEquals(0, reported)
        assertTrue("the file is still kept aside", corrupt.isFile)
        assertNotNull(CorruptionMarker.read(marker))
    }

    @Test
    fun `a database that can be reopened is told, and its process is left alone`() {
        database.writeBytes(ByteArray(64) { it.toByte() })
        var ended = 0
        var reported = 0

        CorruptionHandler(marker, clock, onClosedByCorruption = { reported++ })
            .handle(recording(database.path, Observed()))

        assertEquals("the owner must be told exactly once", 1, reported)
        assertEquals(0, ended)
    }
}
