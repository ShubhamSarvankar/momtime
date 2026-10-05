package com.momtime.android.data

import android.database.sqlite.SQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.data.AppSettingsRepository
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.NutritionTag
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
 * shared with them, because those tests construct a JDBC driver directly. The five tests of schema
 * version 6 (ADR 0079, ADR 0086) are the twins of `SchemaV6Test`, under the names they have there.
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
        localDate = LocalDate.fromEpochDays(20_000 + n),
        scheduledInstant = scheduled,
        timeZoneId = testZone,
        state = state,
        alarmSlot = n,
        criticality = Criticality.CRITICAL,
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
        if (row.isTerminal) {
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

    // Every column of a terminal row, under the Android driver (ADR 0068, schema version 5): the instant and zone
    // through the repository's one sanctioned path, the rest as raw SQL below it. An open row accepts all of them.
    @Test
    fun `the trigger refuses an update of any column of a terminal row`() {
        val graph = graph()
        graph.seedTemplate()
        val occurrences = graph.get<OccurrenceRepository>()
        val driver = graph.factory.createDriver()
        val updates =
            listOf(
                "state, unchanged" to "state = state",
                "scheduled_instant" to "scheduled_instant = scheduled_instant + 1",
                "time_zone_id" to "time_zone_id = 'Asia/Tokyo'",
                "local_date" to "local_date = date(local_date, '+1000 days')",
                "alarm_slot" to "alarm_slot = alarm_slot + 1000",
                "criticality" to "criticality = 'GENTLE'",
            )
        var n = 100
        for (state in OccurrenceState.entries) {
            for ((column, set) in updates) {
                n++
                val row = distinct(n, state)
                occurrences.insert(row)
                val context = "$state, $column"
                if (row.isTerminal) {
                    assertRejected(
                        context,
                    ) { driver.execute(null, "UPDATE occurrence SET $set WHERE id = '${row.id}'", 0) }
                    assertEquals("$context: the row changed anyway", row, occurrences.findById(row.id))
                } else {
                    driver.execute(null, "UPDATE occurrence SET $set WHERE id = '${row.id}'", 0)
                }
            }
            val moved = distinct(n + 5000, state)
            occurrences.insert(moved)
            if (moved.isTerminal) {
                assertRejected("$state, reschedule") {
                    occurrences.reschedule(moved.id, scheduled + kotlin.time.Duration.parse("1h"), testZone)
                }
                assertEquals("$state: reschedule changed the row", moved, occurrences.findById(moved.id))
            } else {
                occurrences.reschedule(moved.id, scheduled + kotlin.time.Duration.parse("1h"), testZone)
            }
        }
    }

    /** A row with its own id, date and slot, so many rows can share the template. */
    private fun distinct(
        n: Int,
        state: OccurrenceState,
    ) = occurrence(n, state)

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
        // A snooze recorded before version 4 has no end; the migration gives it ten minutes (3.sqm).
        db.execSQL(
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source, snooze_number) " +
                "VALUES ('snooze', 'occ-1', 'SNOOZED', 2000, 'USER', 1)",
        )
        // Equal bounds cannot satisfy the version 2 CHECK; the migration clears both.
        db.execSQL(
            "INSERT INTO app_settings(id, quiet_hours_start, quiet_hours_end, ring_grade_daily_budget) " +
                "VALUES (0, '22:00', '22:00', 8)",
        )
    }

    // The twin of SchemaV6Test's test of the same name. A database at each of versions 1 to 5, seeded with a template
    // of each criticality, an occurrence in every state and an event of each type that has a payload, is migrated
    // by the framework, with foreign keys on, when the graph opens it: no old row changes in a column it had.
    // Then the version 1 file this test has always migrated, with the rows only earlier migrations change.
    @Test
    fun `migrates from every prior version`() {
        for (version in 1..5) {
            val name = "m6-v$version.db"
            lateinit var columns: Map<String, List<String>>
            lateinit var before: Map<String, List<List<String?>>>
            databaseAt(name, version) { db ->
                seedAt(db, version)
                columns =
                    copiedTables.associateWith { table ->
                        db.rows("PRAGMA table_info($table)", 2).map { checkNotNull(it[1]) }
                    }
                before = columns.mapValues { (table, names) -> db.rows(selectAll(table, names), names.size) }
            }
            assertEquals("v$version: the seed is in place", 15, before.getValue("occurrence").size)
            assertEquals("v$version: the seed is in place", 10, before.getValue("event").size)

            val driver = graph(name).factory.createDriver()
            val after = columns.mapValues { (table, names) -> driver.rows(selectAll(table, names), names.size) }

            assertEquals("v$version", "6", driver.pragma("user_version"))
            assertEquals("v$version", "1", driver.pragma("foreign_keys"))
            assertEquals("v$version", emptyList<String>(), driver.foreignKeyViolations())
            assertEquals("v$version: an old row changed in a column it had", before, after)
        }

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

        assertEquals(
            "a snooze from before the column ends ten minutes after it was taken",
            EventPayload.Snooze(1, Instant.fromEpochMilliseconds(2000 + 600_000)),
            graph.get<EventRepository>().findById("snooze")?.payload,
        )

        val driver = graph.factory.createDriver()
        assertEquals("6", driver.pragma("user_version"))
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

    // Schema version 5 (ADR 0068) reaches a database migrated from version 1 under the Android driver: its terminal
    // row refuses an update of its instant and zone, not only a change of state.
    @Test
    fun `the migrated database refuses an update of a terminal row's instant`() {
        val name = "m-v1d.db"
        v1Database(name) { seedV1Rows(it) }

        val graph = graph(name)
        val occurrences = graph.get<OccurrenceRepository>()
        val before = occurrences.findById("occ-1")
        assertRejected("rescheduling a MISSED occurrence on a migrated database") {
            occurrences.reschedule("occ-1", scheduled + kotlin.time.Duration.parse("1h"), testZone)
        }
        assertEquals(before, occurrences.findById("occ-1"))
    }

    // ---- Schema version 6 (ADR 0079, ADR 0086): what the twins of SchemaV6Test share. ----

    private val states = listOf("PENDING", "SNOOZED", "COMPLETED", "SKIPPED", "MISSED")
    private val criticalities = listOf("CRITICAL", "STANDARD", "GENTLE")
    private val copiedTables =
        listOf("pregnancy", "schedule_template", "schedule_template_nutrition_tag", "occurrence", "event")

    private fun selectAll(
        table: String,
        names: List<String>,
    ) = "SELECT ${names.joinToString()} FROM $table ORDER BY 1, 2"

    private fun SQLiteDatabase.rows(
        sql: String,
        columns: Int,
    ): List<List<String?>> =
        rawQuery(sql, null).use { cursor ->
            val out = mutableListOf<List<String?>>()
            while (cursor.moveToNext()) out += List(columns) { cursor.getString(it) }
            out
        }

    private fun SqlDriver.rows(
        sql: String,
        columns: Int,
    ): List<List<String?>> =
        executeQuery(
            null,
            sql,
            { cursor ->
                val out = mutableListOf<List<String?>>()
                while (cursor.next().value) out += List(columns) { cursor.getString(it) }
                QueryResult.Value(out.toList())
            },
            0,
        ).value

    /**
     * The statements of a migration file, as written. A trigger's body holds semicolons of its own, so a statement
     * that starts `CREATE TRIGGER` ends at its `END;` line and not before.
     */
    private fun migrationStatements(from: Int): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inTrigger = false
        File("../shared/src/commonMain/sqldelight/migrations/$from.sqm").readLines().forEach { line ->
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("--")) return@forEach
            if (current.isEmpty() && text.startsWith("CREATE TRIGGER")) inTrigger = true
            current.append(line).append('\n')
            val ends = if (inTrigger) text == "END;" else text.endsWith(";")
            if (ends) {
                statements += current.toString().trim().removeSuffix(";")
                current.clear()
                inTrigger = false
            }
        }
        check(current.isBlank()) { "$from.sqm ends inside a statement" }
        return statements
    }

    /**
     * A database file the app at [version] (1 to 5) left behind: the committed version 1 baseline with the
     * migrations before [version] applied as written, carrying that `user_version`. [seed] runs on it at that
     * version. The framework migrates it from there when a graph opens it.
     */
    private fun databaseAt(
        name: String,
        version: Int,
        seed: (SQLiteDatabase) -> Unit,
    ) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        File("../shared/src/commonMain/sqldelight/databases/1.db").copyTo(file, overwrite = true)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            for (from in 1 until version) migrationStatements(from).forEach(db::execSQL)
            db.version = version
            seed(db)
        } finally {
            db.close()
        }
    }

    private fun SQLiteDatabase.event(
        id: String,
        occurrenceId: String?,
        type: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        val occurrence = occurrenceId?.let { "'$it'" } ?: "NULL"
        val names = extra.keys.joinToString("") { ", $it" }
        val values = extra.values.joinToString("") { ", $it" }
        execSQL(
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source$names) " +
                "VALUES ('$id', $occurrence, '$type', 2000, 'USER'$values)",
        )
    }

    /**
     * The seed of SchemaV6Test: a template of each criticality (the CRITICAL one with two tags, the STANDARD one
     * with none, the GENTLE one with one), an occurrence of each in every state the version knows, and one event of
     * each type that has a payload, with the columns the schema at [version] has. Two completions of the tagged
     * template and one of the untagged.
     */
    private fun seedAt(
        db: SQLiteDatabase,
        version: Int,
    ) {
        db.execSQL("INSERT INTO pregnancy(id, phase, created_at, phase_changed_at) VALUES ('preg-1', 'PRENATAL', 0, 0)")
        for ((t, criticality) in criticalities.withIndex()) {
            db.execSQL(
                "INSERT INTO schedule_template(id, pregnancy_id, title, task_type, criticality, time_of_day, " +
                    "time_zone_id, recurrence_type) VALUES ('t-$criticality', 'preg-1', 'Iron tablet', " +
                    "'SUPPLEMENT', '$criticality', '08:00', 'Asia/Kolkata', 'DAILY')",
            )
            for ((s, state) in states.withIndex()) {
                db.execSQL(
                    "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, time_zone_id, state, " +
                        "alarm_slot) VALUES ('o-$criticality-$state', 't-$criticality', '2026-01-0${s + 1}', " +
                        "${1000 + s}, 'Asia/Kolkata', '$state', ${t * 10 + s + 1})",
                )
            }
        }
        for ((template, tag) in listOf("t-CRITICAL" to "IRON", "t-CRITICAL" to "DAIRY", "t-GENTLE" to "FRUIT")) {
            db.execSQL("INSERT INTO schedule_template_nutrition_tag(template_id, tag) VALUES ('$template', '$tag')")
        }
        db.event("e-missed", "o-CRITICAL-MISSED", "MISSED", mapOf("effective_at" to "1500"))
        val snooze =
            mapOf("snooze_number" to "1") + if (version >= 4) mapOf("snoozed_until" to "602000") else emptyMap()
        db.event("e-snoozed", "o-CRITICAL-SNOOZED", "SNOOZED", snooze)
        db.event("e-mission", "o-CRITICAL-COMPLETED", "MISSION_VERIFIED", mapOf("mission_result_type" to "'BARCODE'"))
        db.event("e-water", null, "WATER_LOGGED", mapOf("water_ml" to "250"))
        db.event("e-weight", null, "WEIGHT_LOGGED", mapOf("weight_grams" to "68000"))
        db.event("e-caregiver", null, "CAREGIVER_LINKED", mapOf("caregiver_link_id" to "'link-1'"))
        val canary =
            if (version >= 3) mapOf("canary_scheduled_at" to "100", "canary_actual_at" to "150") else emptyMap()
        db.event("e-canary", null, "CANARY_RESULT", canary)
        db.event("e-completed-tagged", "o-CRITICAL-COMPLETED", "COMPLETED")
        db.event("e-backfilled-tagged", "o-CRITICAL-MISSED", "COMPLETED_BACKFILLED")
        db.event("e-completed-untagged", "o-STANDARD-COMPLETED", "COMPLETED")
    }

    /** A graph over a seeded database at [from] (1 to 5), which the framework migrates to version 6 on opening. */
    private fun migratedGraph(
        name: String,
        from: Int,
    ): TestGraph {
        databaseAt(name, from) { seedAt(it, from) }
        return graph(name)
    }

    /** A graph over a new database, created at version 6, holding the same templates as the seed. */
    private fun freshGraph(): TestGraph {
        val graph = graph()
        val driver = graph.factory.createDriver()
        driver.execute(
            null,
            "INSERT INTO pregnancy(id, phase, created_at, phase_changed_at) VALUES ('preg-1', 'PRENATAL', 0, 0)",
            0,
        )
        for (criticality in criticalities) {
            driver.execute(
                null,
                "INSERT INTO schedule_template(id, pregnancy_id, title, task_type, criticality, time_of_day, " +
                    "time_zone_id, recurrence_type) VALUES ('t-$criticality', 'preg-1', 'Iron tablet', " +
                    "'SUPPLEMENT', '$criticality', '08:00', 'Asia/Kolkata', 'DAILY')",
                0,
            )
        }
        return graph
    }

    private val v6Updates =
        listOf(
            "state" to "state = 'PENDING'",
            "state, unchanged" to "state = state",
            "scheduled_instant" to "scheduled_instant = scheduled_instant + 1",
            "time_zone_id" to "time_zone_id = 'Asia/Tokyo'",
            "local_date" to "local_date = date(local_date, '+1000 days')",
            "alarm_slot" to "alarm_slot = alarm_slot + 1000",
            "criticality" to "criticality = 'GENTLE'",
            "template_id, unchanged" to "template_id = template_id",
            "id" to "id = id || 'x'",
        )

    /** Every update of [id] aborts with the trigger's message and leaves the row as it was. */
    private fun assertImmutable(
        driver: SqlDriver,
        id: String,
        label: String,
    ) {
        val select = "SELECT * FROM occurrence WHERE id = '$id'"
        val before = driver.rows(select, 8).single()
        for ((column, set) in v6Updates) {
            try {
                driver.execute(null, "UPDATE occurrence SET $set WHERE id = '$id'", 0)
            } catch (expected: Exception) {
                assertTrue(
                    "$label, $column: not the trigger's message: ${expected.message}",
                    expected.message.orEmpty().contains("terminal occurrence is immutable"),
                )
                assertEquals("$label, $column: the row changed anyway", before, driver.rows(select, 8).single())
                continue
            }
            fail("$label, $column: the update went through")
        }
    }

    private fun v6Occurrence(
        id: String,
        state: OccurrenceState,
        slot: Int,
    ) = Occurrence(
        id = id,
        templateId = "t-GENTLE",
        localDate = LocalDate.fromEpochDays(21_000 + slot),
        scheduledInstant = scheduled,
        timeZoneId = testZone,
        state = state,
        alarmSlot = slot,
        criticality = Criticality.GENTLE,
    )

    @Test
    fun `the migration fills each new column from version 5 data`() {
        val graph = migratedGraph("m6-fill.db", from = 5)
        val occurrences = graph.get<OccurrenceRepository>()
        val events = graph.get<EventRepository>()
        val driver = graph.factory.createDriver()

        // Open and terminal alike: the fill runs with the terminal trigger dropped.
        for (criticality in criticalities) {
            for (state in states) {
                assertEquals(
                    "o-$criticality-$state takes its template's criticality",
                    Criticality.valueOf(criticality),
                    occurrences.findById("o-$criticality-$state")?.criticality,
                )
            }
        }

        // As sets: the order in which group_concat joined the names is unspecified (ADR 0079).
        val two = EventPayload.Completion(setOf(NutritionTag.IRON, NutritionTag.DAIRY))
        assertEquals(two, events.findById("e-completed-tagged")?.payload)
        assertEquals(two, events.findById("e-backfilled-tagged")?.payload)
        assertEquals(EventPayload.Completion(emptySet()), events.findById("e-completed-untagged")?.payload)
        assertEquals(
            "no tags is null and nothing else",
            listOf(listOf<String?>(null)),
            driver.rows("SELECT nutrition_tags FROM event WHERE id = 'e-completed-untagged'", 1),
        )

        assertEquals(listOf(listOf<String?>(null)), driver.rows("SELECT zone_id FROM event WHERE id = 'e-water'", 1))
        assertEquals(EventPayload.Water(250, zone = null), events.findById("e-water")?.payload)

        assertEquals(
            "no other event has tags",
            listOf(listOf<String?>("e-backfilled-tagged"), listOf<String?>("e-completed-tagged")),
            driver.rows("SELECT id FROM event WHERE nutrition_tags IS NOT NULL ORDER BY id", 1),
        )
        assertEquals(
            "no event has a zone",
            emptyList<List<String?>>(),
            driver.rows("SELECT id FROM event WHERE zone_id IS NOT NULL", 1),
        )
        // Every migrated event still decodes: no column landed on a type that may not carry it.
        for (id in driver.rows("SELECT id FROM event", 1).map { checkNotNull(it[0]) }) {
            assertEquals(id, events.findById(id)?.id)
        }
    }

    @Test
    fun `a withdrawn row is immutable in full`() {
        for ((label, graph) in listOf("fresh" to freshGraph(), "migrated from 5" to migratedGraph("m6-w.db", 5))) {
            val occurrences = graph.get<OccurrenceRepository>()
            val driver = graph.factory.createDriver()
            occurrences.insert(v6Occurrence("w", OccurrenceState.WITHDRAWN, slot = 110))
            assertImmutable(driver, "w", "$label, WITHDRAWN")

            // The control: the same updates go through on an open row, so the aborts above are the trigger's.
            for ((index, update) in v6Updates.withIndex()) {
                occurrences.insert(v6Occurrence("open-$index", OccurrenceState.PENDING, slot = 111 + index))
                driver.execute(null, "UPDATE occurrence SET ${update.second} WHERE id = 'open-$index'", 0)
            }
        }
    }

    @Test
    fun `the other terminal states are still immutable after the migration`() {
        for (from in listOf(1, 5)) {
            val driver = migratedGraph("m6-t$from.db", from).factory.createDriver()
            for (state in listOf("COMPLETED", "SKIPPED", "MISSED")) {
                assertImmutable(driver, "o-CRITICAL-$state", "from $from, $state")
            }
        }
    }

    @Test
    fun `a date can be materialised again after a withdrawal and not otherwise`() {
        for ((label, graph) in listOf("fresh" to freshGraph(), "migrated from 5" to migratedGraph("m6-d.db", 5))) {
            val occurrences = graph.get<OccurrenceRepository>()
            val first = v6Occurrence("first", OccurrenceState.PENDING, slot = 201)
            occurrences.insert(first)
            occurrences.transition(
                "first",
                OccurrenceState.WITHDRAWN,
                Event(
                    "e-withdrawn",
                    "first",
                    EventType.WITHDRAWN,
                    scheduled,
                    null,
                    EventSource.USER,
                    EventPayload.None,
                ),
            )
            assertEquals(label, OccurrenceState.WITHDRAWN, occurrences.findById("first")?.state)

            // The same template and date, a new id and a new slot: the withdrawn row does not stand in its way.
            occurrences.insert(first.copy(id = "second", alarmSlot = 202))
            assertEquals(label, OccurrenceState.PENDING, occurrences.findById("second")?.state)

            // A third for the date is a duplicate of the second, and the index refuses it.
            try {
                occurrences.insert(first.copy(id = "third", alarmSlot = 203))
                fail("$label: a second open occurrence for one date was stored")
            } catch (expected: Exception) {
                assertTrue(
                    "$label: not the date index: ${expected.message}",
                    expected.message.orEmpty().contains("occurrence.local_date"),
                )
            }
            assertNull(label, occurrences.findById("third"))
        }
    }
}
