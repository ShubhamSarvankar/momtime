package com.momtime.android.data

import com.momtime.android.di.CorruptionMarker
import com.momtime.android.di.DatabaseFiles
import com.momtime.android.store.ArmedAlarm
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.domain.DeliveryCapability
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
import kotlin.time.Instant

/**
 * The android store (ADR 0048): what the shared schema must not hold. It is a separate database, on
 * the same driver configuration as the shared one, and its rows are keyed by the shared event id
 * with no foreign key across the two files.
 *
 * Its repositories never throw (ADR 0051): the store is diagnostics and an armed record the watchdog
 * recomputes, so a failure in it must not stop an alarm. And unlike the shared database it is
 * reopened, not left closed, when corruption found in the middle of a query has closed its driver.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class AndroidStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        assertNativeSqliteMode()
        graph = TestGraph(context)
    }

    @After
    fun tearDown() = graph.close()

    private fun telemetry(
        id: String,
        tier: DeliveryCapability = DeliveryCapability.TIER_2,
    ) = FireTelemetry(
        eventId = id,
        resolvedTier = tier,
        screenOn = true,
        audioFocusObtained = false,
        batteryPct = 42,
        dozeState = "IDLE",
        watchdogRepair = true,
        bootCount = 17,
    )

    @Test
    fun `fire telemetry round trips every field, and every optional field absent`() {
        val repo = graph.get<FireTelemetryRepository>()
        val full = telemetry("e-full")
        // Booleans keep their value, not just their presence: false is not null.
        val flipped =
            full.copy(eventId = "e-flipped", screenOn = false, audioFocusObtained = true, watchdogRepair = false)
        val bare =
            FireTelemetry(
                "e-bare",
                DeliveryCapability.TIER_1,
                null,
                null,
                null,
                null,
                watchdogRepair = false,
                bootCount = null,
            )
        for (row in listOf(full, flipped, bare)) {
            assertTrue("the insert of ${row.eventId} failed", repo.insert(row))
            assertEquals(row, repo.findForEvent(row.eventId))
        }
        assertEquals(3L, repo.count())
        assertNull(repo.findForEvent("missing"))
    }

    @Test
    fun `every delivery tier is stored and read back as itself`() {
        val repo = graph.get<FireTelemetryRepository>()
        for ((index, tier) in DeliveryCapability.entries.withIndex()) {
            val row = telemetry("e-$index", tier)
            assertTrue(repo.insert(row))
            assertEquals(tier, repo.findForEvent(row.eventId)?.resolvedTier)
        }
    }

    // The schema's CHECKs and primary key still reject these rows. The repository does not throw, so the
    // rejection shows as false and as no row stored.
    @Test
    fun `the schema rejects an unknown tier, a battery over 100 and a second row for one event`() {
        val repo = graph.get<FireTelemetryRepository>()
        assertTrue(repo.insert(telemetry("e-1")))

        assertFalse("a second row for the same event", repo.insert(telemetry("e-1")))
        assertFalse("a battery of 101", repo.insert(telemetry("e-2").copy(batteryPct = 101)))
        assertFalse("a negative battery", repo.insert(telemetry("e-3").copy(batteryPct = -1)))
        assertEquals(1L, repo.count())
    }

    private fun armed(slot: Int) =
        ArmedAlarm(
            alarmSlot = slot,
            rungInstant = Instant.parse("2026-01-01T08:00:00Z"),
            armedAt = Instant.parse("2026-01-01T07:00:00Z"),
            bootCount = 9,
            exactAllowed = true,
        )

    @Test
    fun `the armed alarm is one record, replaced and cleared`() {
        val repo = graph.get<ArmedAlarmRepository>()
        assertNull(repo.current())

        assertTrue(repo.replace(armed(3)))
        assertEquals(armed(3), repo.current())

        // At most one alarm is armed at a time: a second replace overwrites, never adds.
        val second = armed(8).copy(exactAllowed = false, bootCount = 10)
        assertTrue(repo.replace(second))
        assertEquals(second, repo.current())

        assertTrue(repo.clear())
        assertNull(repo.current())
    }

    // The store is a different file: nothing here touches the shared database, and the shared schema
    // has no table for it.
    @Test
    fun `the store file is separate from the shared database`() {
        graph.get<ArmedAlarmRepository>().replace(armed(1))
        val shared = graph.factory.createDriver()
        val store = graph.storeFactory.createDriver {}

        assertEquals(1L, store.scalar("SELECT COUNT(*) FROM armed_alarm"))
        assertEquals(0L, shared.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name = 'armed_alarm'"))
        assertEquals(0L, shared.scalar("SELECT COUNT(*) FROM sqlite_master WHERE name = 'fire_telemetry'"))
    }

    // A store that fails does not stop the call that asked: a failed read of the armed record is "record
    // missing", which sends the watchdog to its repair path, and a failed write returns false (ADR 0051).
    @Test
    fun `a store failure on the alarm path is never fatal`() {
        val armedRepo = graph.get<ArmedAlarmRepository>()
        val fires = graph.get<FireTelemetryRepository>()
        assertTrue(armedRepo.replace(armed(1)))
        // The store's driver is closed underneath the repositories, so every call from here on fails.
        graph.storeFactory.closeAll()

        val outcome =
            runCatching {
                listOf(
                    armedRepo.current(),
                    armedRepo.replace(armed(2)),
                    armedRepo.clear(),
                    fires.insert(telemetry("e-1")),
                    fires.findForEvent("e-1"),
                    fires.count(),
                )
            }

        assertTrue("a store failure reached the caller: ${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals(listOf(null, false, false, false, null, 0L), outcome.getOrThrow())
    }

    private val storeFile get() = context.getDatabasePath(graph.storeName)
    private val storeCorruptFile get() = File(storeFile.parentFile, graph.storeName + ".corrupt")
    private val storeMarker get() = File(context.noBackupFilesDir, DatabaseFiles.STORE_CORRUPTION_MARKER_NAME)

    /** Overwrites the second half of the store file and the file change counter. Returns the file as it now is. */
    private fun damageStore(): ByteArray {
        val bytes = storeFile.readBytes()
        for (i in bytes.size / 2 until bytes.size) bytes[i] = 0xFF.toByte()
        // Bytes 24 to 39 of the header are the file change counter and the version-valid-for number.
        for (i in 24 until 40) bytes[i] = (i * 7).toByte()
        storeFile.writeBytes(bytes)
        return bytes
    }

    // Corruption found in the middle of a query closes the store's driver. The next call replaces it:
    // exactly one live driver at any time, the process is not ended, the damaged file is kept aside, and
    // the call that hit the corruption did not throw (ADR 0051).
    @Test
    fun `the store is reopened after corruption found during a query`() {
        val fires = graph.get<FireTelemetryRepository>()
        val armedRepo = graph.get<ArmedAlarmRepository>()
        for (i in 0 until 1500) assertTrue(fires.insert(telemetry("e-$i")))
        assertEquals(1500L, fires.count())
        assertEquals(1, graph.storeFactory.live)
        val damaged = damageStore()

        val hit = runCatching { fires.count() }

        assertTrue("the read that hit the corruption threw: ${hit.exceptionOrNull()}", hit.isSuccess)
        assertEquals("a read that hit corruption is treated as nothing read", 0L, hit.getOrThrow())
        assertTrue("the damaged store must be kept aside", storeCorruptFile.isFile)
        assertArrayEquals("the kept file must be the damaged one", damaged, storeCorruptFile.readBytes())
        assertNotNull("the store's marker must be written", CorruptionMarker.read(storeMarker))
        assertEquals("the process must not be ended for the store", 0, graph.processEnd.calls)

        // The next call reopens: it works, on a fresh store, and the old driver is closed.
        assertTrue("the next write must succeed on a reopened store", armedRepo.replace(armed(5)))
        assertEquals(armed(5), armedRepo.current())
        assertEquals("a second live driver", 1, graph.storeFactory.live)
        assertEquals("the store was not reopened exactly once", 2, graph.storeFactory.created)
        assertEquals("the fresh store holds nothing from before", 0L, fires.count())
    }
}

private fun app.cash.sqldelight.db.SqlDriver.scalar(sql: String): Long =
    executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            check(cursor.next().value)
            app.cash.sqldelight.db.QueryResult
                .Value(checkNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value
