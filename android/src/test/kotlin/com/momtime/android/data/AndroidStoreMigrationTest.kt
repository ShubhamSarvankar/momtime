package com.momtime.android.data

import android.database.sqlite.SQLiteDatabase
import com.momtime.android.store.ArmedAlarm
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.domain.DeliveryCapability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * The android store's forward migrations (ADR 0048, ADR 0058, ADR 0060, ADR 0065): version 1 to 2 gives the armed
 * record the app's version code, version 2 to 3 gives the device telemetry row the delivery path and whether the
 * ringer started, and version 3 to 4 gives it whether the alarm stream was muted. The starting point is the
 * committed baseline snapshot (`databases/1.db`), the schema a version 1 install holds, never a regenerated one
 * (ADR 0035); a later version is that baseline with the migrations before it applied as written. The framework
 * migrates from `user_version`.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class AndroidStoreMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val graphs = mutableListOf<TestGraph>()

    @Before
    fun setUp() = assertNativeSqliteMode()

    @After
    fun tearDown() = graphs.forEach { it.close() }

    /** A store file at [version], seeded with rows through [seed]. A later version applies `N.sqm` itself. */
    private fun store(
        name: String,
        version: Int,
        seed: (SQLiteDatabase) -> Unit,
    ) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        File("src/main/sqldelight/databases/1.db").copyTo(file, overwrite = true)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.version = 1
            for (from in 1 until version) {
                File("src/main/sqldelight/migrations/$from.sqm")
                    .readText()
                    .lines()
                    .filterNot { it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach(db::execSQL)
                db.version = from + 1
            }
            seed(db)
        } finally {
            db.close()
        }
    }

    private fun seedRows(
        db: SQLiteDatabase,
        withVersion: Boolean,
    ) {
        if (withVersion) {
            db.execSQL(
                "INSERT INTO armed_alarm(id, alarm_slot, rung_instant, armed_at, boot_count, exact_allowed, " +
                    "version_code) VALUES (0, 7, 1000, 900, 3, 1, 77)",
            )
        } else {
            db.execSQL(
                "INSERT INTO armed_alarm(id, alarm_slot, rung_instant, armed_at, boot_count, exact_allowed) " +
                    "VALUES (0, 7, 1000, 900, 3, 1)",
            )
        }
        db.execSQL(
            "INSERT INTO fire_telemetry(event_id, resolved_tier, screen_on, audio_focus_obtained, battery_pct, " +
                "doze_state, watchdog_repair, boot_count) VALUES ('old', 'TIER_2', 1, 0, 40, 'IDLE', 0, 3)",
        )
    }

    private fun graphOf(name: String) = TestGraph(context, storeName = name).also { graphs.add(it) }

    @Test
    fun `a version 1 store migrates to 4 and keeps its rows`() {
        val name = "s-v1.db"
        store(name, 1) { seedRows(it, withVersion = false) }

        val graph = graphOf(name)
        val kept = checkNotNull(graph.get<ArmedAlarmRepository>().current())
        assertEquals(7, kept.alarmSlot)
        assertEquals(Instant.fromEpochMilliseconds(1000), kept.rungInstant)
        assertEquals(Instant.fromEpochMilliseconds(900), kept.armedAt)
        assertEquals(3L, kept.bootCount)
        assertEquals(true, kept.exactAllowed)
        assertEquals("a record from before the version was kept reads as version 0", 0L, kept.versionCode)

        val old = checkNotNull(graph.get<FireTelemetryRepository>().findForEvent("old"))
        assertEquals(DeliveryCapability.TIER_2, old.resolvedTier)
        assertEquals(40, old.batteryPct)
        assertNull("a row from before the path was kept has none", old.deliveryPath)
        assertNull(old.ringerStarted)
        assertNull("a row from before the muted flag was kept has none", old.alarmStreamMuted)

        val driver = graph.storeFactory.createDriver {}
        assertEquals("4", driver.pragma("user_version"))
        assertEquals("1", driver.pragma("foreign_keys"))
    }

    @Test
    fun `a version 2 store migrates to 4 and keeps its rows and its version code`() {
        val name = "s-v2.db"
        store(name, 2) { seedRows(it, withVersion = true) }

        val graph = graphOf(name)
        assertEquals(77L, graph.get<ArmedAlarmRepository>().current()?.versionCode)
        val old = checkNotNull(graph.get<FireTelemetryRepository>().findForEvent("old"))
        assertEquals(DeliveryCapability.TIER_2, old.resolvedTier)
        assertNull(old.deliveryPath)
        assertNull(old.ringerStarted)
        assertNull(old.alarmStreamMuted)
        assertEquals("4", graph.storeFactory.createDriver {}.pragma("user_version"))
    }

    @Test
    fun `a version 3 store migrates to 4 and keeps its rows and its path`() {
        val name = "s-v3.db"
        store(name, 3) { db ->
            seedRows(db, withVersion = true)
            db.execSQL(
                "INSERT INTO fire_telemetry(event_id, resolved_tier, watchdog_repair, delivery_path, " +
                    "ringer_started) VALUES ('v3', 'TIER_3', 0, 'RING', 1)",
            )
        }

        val graph = graphOf(name)
        assertEquals(77L, graph.get<ArmedAlarmRepository>().current()?.versionCode)
        val kept = checkNotNull(graph.get<FireTelemetryRepository>().findForEvent("v3"))
        assertEquals("RING", kept.deliveryPath)
        assertEquals(true, kept.ringerStarted)
        assertNull("a row from before the muted flag was kept has none", kept.alarmStreamMuted)
        assertEquals("4", graph.storeFactory.createDriver {}.pragma("user_version"))
    }

    @Test
    fun `the migrated table stores whether the alarm stream was muted`() {
        val name = "s-v3b.db"
        store(name, 3) { seedRows(it, withVersion = true) }

        val telemetry = graphOf(name).get<FireTelemetryRepository>()
        val base = checkNotNull(telemetry.findForEvent("old"))
        assertEquals(true, telemetry.insert(base.copy(eventId = "muted", alarmStreamMuted = true)))
        assertEquals(true, telemetry.insert(base.copy(eventId = "heard", alarmStreamMuted = false)))
        assertEquals(true, telemetry.findForEvent("muted")?.alarmStreamMuted)
        assertEquals(false, telemetry.findForEvent("heard")?.alarmStreamMuted)
    }

    @Test
    fun `the migrated tables take the new columns`() {
        val name = "s-v2b.db"
        store(name, 2) { seedRows(it, withVersion = true) }

        val graph = graphOf(name)
        val armed = graph.get<ArmedAlarmRepository>()
        val kept = checkNotNull(armed.current())
        assertEquals(true, armed.replace(kept.copy(versionCode = 123)))
        assertEquals(123L, armed.current()?.versionCode)

        val telemetry = graph.get<FireTelemetryRepository>()
        val row =
            checkNotNull(
                telemetry.findForEvent("old"),
            ).copy(eventId = "new", deliveryPath = "RING", ringerStarted = true)
        assertEquals(true, telemetry.insert(row))
        assertEquals(row, telemetry.findForEvent("new"))
        assertEquals(true, telemetry.recordRinger("new", started = false, audioFocus = true))
        assertEquals(false, telemetry.findForEvent("new")?.ringerStarted)
        assertEquals(true, telemetry.findForEvent("new")?.audioFocusObtained)
    }

    @Test
    fun `a new store is created at version 4`() {
        val graph = TestGraph(context, storeName = "s-new.db").also { graphs.add(it) }
        val repo = graph.get<ArmedAlarmRepository>()
        val record =
            ArmedAlarm(
                alarmSlot = 1,
                rungInstant = Instant.fromEpochMilliseconds(5),
                armedAt = Instant.fromEpochMilliseconds(4),
                bootCount = 1,
                exactAllowed = false,
                versionCode = 9,
            )
        assertEquals(true, repo.replace(record))
        assertEquals(record, repo.current())
        assertEquals("4", graph.storeFactory.createDriver {}.pragma("user_version"))
    }
}
