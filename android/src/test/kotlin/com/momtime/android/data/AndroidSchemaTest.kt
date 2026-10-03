package com.momtime.android.data

import android.database.sqlite.SQLiteDatabase
import com.momtime.shared.data.AppSettingsRepository
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * The schema on the Android driver: the terminal trigger (ADR 0037) and the v1 to v2 forward
 * migration with foreign keys on, ending in `foreign_key_check` (ADR 0043). These are the Phase 1
 * checks, rerun on the driver the app ships. They are duplicated from the `shared` tests and not
 * shared with them, because those tests construct a JDBC driver directly.
 *
 * Everything goes through repositories: android sources cannot name a generated query type.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class AndroidSchemaTest {
    private val context = RuntimeEnvironment.getApplication()
    private val graphs = mutableListOf<TestGraph>()
    private val scheduled = Instant.parse("2026-01-01T02:30:00Z")

    @Before
    fun setUp() = assertNativeSqliteMode()

    @After
    fun tearDown() = graphs.forEach { it.close() }

    private fun graph(name: String? = null) =
        (if (name == null) TestGraph(context) else TestGraph(context, name)).also { graphs.add(it) }

    private fun occurrence(
        n: Int,
        state: OccurrenceState,
    ) = Occurrence(
        id = "occ-$n",
        templateId = "tmpl-1",
        localDate = LocalDate(2026, 1, n),
        scheduledInstant = scheduled,
        timeZoneId = testZone,
        state = state,
        alarmSlot = n,
    )

    private fun event(
        n: Int,
        occurrenceId: String,
    ) = Event("e-$n", occurrenceId, EventType.ALARM_FIRED, scheduled, null, EventSource.SYSTEM, EventPayload.None)

    private fun assertRejected(
        context: String,
        change: () -> Unit,
    ) {
        try {
            change()
        } catch (expected: Exception) {
            return
        }
        fail("$context: the trigger let a terminal occurrence change")
    }

    private fun checkTransition(
        graph: TestGraph,
        n: Int,
        from: OccurrenceState,
        to: OccurrenceState,
    ) {
        val occurrences = graph.get<OccurrenceRepository>()
        val row = occurrence(n, from)
        occurrences.insert(row)
        val label = "$from to $to"
        if (row.isTerminal && to != from) {
            assertRejected(label) { occurrences.transition(row.id, to, event(n, row.id)) }
            assertEquals("$label changed the row anyway", from, occurrences.findById(row.id)?.state)
            assertTrue(
                "$label: an event survived the rejected transition",
                graph.get<EventRepository>().findForOccurrence(row.id).isEmpty(),
            )
        } else {
            occurrences.transition(row.id, to, event(n, row.id))
            assertEquals(label, to, occurrences.findById(row.id)?.state)
        }
    }

    // The terminal set comes from Occurrence.isTerminal, the single Kotlin definition, so a terminal
    // state added to the enum without updating the trigger fails here. The change goes through
    // OccurrenceRepository.transition, which appends the event and sets the state in one transaction,
    // so a rejected change also leaves no event behind.
    @Test
    fun `the trigger rejects every change out of a terminal state`() {
        val graph = graph()
        graph.seedTemplate()
        var n = 0
        for (from in OccurrenceState.entries) {
            for (to in OccurrenceState.entries) {
                n++
                checkTransition(graph, n, from, to)
            }
        }
    }

    private fun insertOccurrenceSql(
        id: String,
        templateId: String,
        date: String,
        state: String,
        slot: Int,
    ) = "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, time_zone_id, state, alarm_slot) " +
        "VALUES ('$id', '$templateId', '$date', ${scheduled.toEpochMilliseconds()}, 'Asia/Kolkata', '$state', $slot)"

    private fun v1Database(
        name: String,
        seed: (SQLiteDatabase) -> Unit,
    ) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        File("../shared/src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            // A database the version 1 app left behind carries user_version 1; the committed baseline is
            // a schema snapshot. The framework migrates from this number.
            db.version = 1
            seed(db)
        } finally {
            db.close()
        }
    }

    private fun seedV1Rows(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO pregnancy(id, phase, created_at, phase_changed_at) VALUES ('preg-1', 'PRENATAL', 0, 0)")
        db.execSQL(
            "INSERT INTO schedule_template(id, pregnancy_id, title, task_type, criticality, time_of_day, " +
                "time_zone_id, recurrence_type) VALUES ('tmpl-1', 'preg-1', 'Iron tablet', 'SUPPLEMENT', " +
                "'CRITICAL', '08:00', 'Asia/Kolkata', 'DAILY')",
        )
        db.execSQL(insertOccurrenceSql("occ-1", "tmpl-1", "2026-01-01", "MISSED", 7))
        // A canary recorded by the version 2 schema: its instants sit in the telemetry table and move onto
        // the event in version 3 (ADR 0048).
        db.execSQL(
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, effective_at, source) " +
                "VALUES ('canary', NULL, 'CANARY_RESULT', 0, NULL, 'SYSTEM')",
        )
        db.execSQL(
            "INSERT INTO alarm_delivery_telemetry(event_id, canary_scheduled_at, canary_actual_at, resolved_tier) " +
                "VALUES ('canary', 100, 150, 'TIER_2')",
        )
        // Equal bounds cannot satisfy the version 2 CHECK; the migration clears both.
        db.execSQL(
            "INSERT INTO app_settings(id, quiet_hours_start, quiet_hours_end, ring_grade_daily_budget) " +
                "VALUES (0, '22:00', '22:00', 8)",
        )
    }

    @Test
    fun `a v1 database migrates to v3 with foreign keys on and keeps its rows`() {
        val name = "m-v1a.db"
        v1Database(name) { seedV1Rows(it) }

        val graph = graph(name)
        val occurrence = graph.get<OccurrenceRepository>().findById("occ-1")
        assertEquals(OccurrenceState.MISSED, occurrence?.state)
        assertEquals(7, occurrence?.alarmSlot)
        val settings = graph.get<AppSettingsRepository>().current()
        assertNull("the unsatisfiable quiet hours must be cleared", settings.quietHours)
        assertEquals("other settings must survive the rebuild", 8, settings.ringGradeDailyBudget)
        assertEquals(
            "the canary instants must move onto their event",
            EventPayload.Canary(Instant.fromEpochMilliseconds(100), Instant.fromEpochMilliseconds(150)),
            graph.get<EventRepository>().findById("canary")?.payload,
        )

        val driver = graph.factory.createDriver()
        assertEquals("3", driver.pragma("user_version"))
        assertEquals("1", driver.pragma("foreign_keys"))
        assertEquals(emptyList<String>(), driver.foreignKeyViolations())
    }

    // The migration test ends with foreign_key_check because foreign keys cannot be switched off inside
    // the transaction the framework runs onUpgrade in, so a table rebuild could leave orphans. This
    // plants one the v1 to v2 migration does not touch and shows only the check reports it.
    @Test
    fun `foreign_key_check reports an orphan the migration let through`() {
        val name = "m-v1b.db"
        v1Database(name) { db ->
            seedV1Rows(db)
            db.execSQL(insertOccurrenceSql("orphan", "no-such-template", "2026-01-02", "PENDING", 9))
        }

        val graph = graph(name)
        graph.get<OccurrenceRepository>().findById("orphan")
        val violations = graph.factory.createDriver().foreignKeyViolations()
        assertEquals("expected exactly the planted orphan: $violations", 1, violations.size)
        assertTrue(violations.single(), violations.single().startsWith("occurrence "))
    }

    @Test
    fun `the migrated database has the trigger`() {
        val name = "m-v1c.db"
        v1Database(name) { seedV1Rows(it) }

        val graph = graph(name)
        val occurrences = graph.get<OccurrenceRepository>()
        assertRejected("MISSED to PENDING on a migrated database") {
            occurrences.transition("occ-1", OccurrenceState.PENDING, event(1, "occ-1"))
        }
        assertEquals(OccurrenceState.MISSED, occurrences.findById("occ-1")?.state)
    }
}
