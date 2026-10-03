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
 * The order the corruption handler works in (ADR 0044, ADR 0049): the database is closed before its file
 * is moved aside. On Linux a file can be renamed while it is open, so a handler that moved it first
 * would work there and fail on a platform that refuses, and nothing observable on a JVM file system
 * would tell. This records what is true of the file at the instant `close` is called instead.
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
    ): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPath" -> path
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

        CorruptionHandler(marker, clock).handle(recording(null, observed))

        assertEquals(1, observed.closed)
        assertFalse(corrupt.exists())
        assertFalse("no marker for a database with no file", marker.exists())
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
}
