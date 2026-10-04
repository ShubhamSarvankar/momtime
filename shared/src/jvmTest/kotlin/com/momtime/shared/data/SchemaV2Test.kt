package com.momtime.shared.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.MissionConfig
import com.momtime.shared.domain.NutritionTag
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.QuietHours
import com.momtime.shared.domain.Recurrence
import com.momtime.shared.domain.ScheduleTemplate
import com.momtime.shared.domain.TaskType
import com.momtime.shared.engine.Reconcile
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Schema version 2 (ADR 0037): the terminal-state trigger and the quiet_hours_valid CHECK.
 *
 * Every constraint test runs twice, against a fresh database at the current version and against a database
 * migrated from the committed version 1 baseline (`databases/1.db`) up to the current version, because a migration that
 * silently omitted a constraint would pass the fresh-database half alone. Invariant 2: a forward
 * migration test from every prior version.
 */
class SchemaV2Test {
    private val tempFiles = mutableListOf<Path>()
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private lateinit var lastV1File: Path
    private val zone = TimeZone.of("Asia/Kolkata")
    private val scheduled = Instant.parse("2026-01-01T02:30:00Z")

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    private fun freshV2(): MomTimeDatabase {
        val driver = openJvmSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { drivers.add(it) }
        MomTimeDatabase.Schema.create(driver)
        return MomTimeDatabase(driver)
    }

    /**
     * A real version 1 file, copied from the committed baseline; seeded by [seed], then migrated. Ends with
     * PRAGMA foreign_key_check and fails on any orphan, so a migration that leaves one behind cannot pass.
     * [checkForeignKeys] is false only for the test that proves the check can fail.
     */
    private fun migratedFromV1(
        checkForeignKeys: Boolean = true,
        seed: (JdbcSqliteDriver) -> Unit = {},
    ): MomTimeDatabase {
        val file = Files.createTempFile("momtime-v1", ".db").also { tempFiles.add(it) }
        lastV1File = file
        Files.copy(Path.of("src/commonMain/sqldelight/databases/1.db"), file, StandardCopyOption.REPLACE_EXISTING)
        val driver = openJvmSqliteDriver("jdbc:sqlite:$file").also { drivers.add(it) }
        seed(driver)
        MomTimeDatabase.Schema.migrate(driver, 1, MomTimeDatabase.Schema.version)
        if (checkForeignKeys) {
            assertEquals(emptyList(), driver.foreignKeyViolations(), "migration left foreign key violations")
        }
        return MomTimeDatabase(driver)
    }

    private fun bothDatabases(): List<Pair<String, MomTimeDatabase>> =
        listOf("fresh" to freshV2(), "migrated from v1" to migratedFromV1())

    private fun occurrence(
        state: OccurrenceState,
        slot: Int = 1,
    ) = Occurrence(
        id = "occ-$state",
        templateId = "tmpl-1",
        localDate = LocalDate(2026, 1, 1),
        scheduledInstant = scheduled,
        timeZoneId = zone,
        state = state,
        alarmSlot = slot,
    )

    private fun seedTemplate(db: MomTimeDatabase) {
        SqlDelightPregnancyRepository(db).insert(
            Pregnancy(
                "preg-1",
                PregnancyPhase.PRENATAL,
                Instant.fromEpochMilliseconds(0),
                Instant.fromEpochMilliseconds(0),
            ),
        )
        SqlDelightScheduleTemplateRepository(db).insert(
            ScheduleTemplate(
                id = "tmpl-1",
                pregnancyId = "preg-1",
                title = "Iron tablet",
                notes = null,
                taskType = TaskType.SUPPLEMENT,
                criticality = Criticality.CRITICAL,
                timeOfDay = LocalTime(8, 0),
                timeZoneId = zone,
                recurrence = Recurrence.Daily,
                mission = MissionConfig.None,
                nutritionTags = setOf(NutritionTag.IRON),
                dosage = null,
                doctorInstructions = null,
                inventoryCount = null,
                refillThresholdDays = null,
                active = true,
            ),
        )
    }

    private fun event(
        id: String,
        occurrenceId: String,
        type: EventType,
    ) = Event(
        id = id,
        occurrenceId = occurrenceId,
        eventType = type,
        deviceTimestamp = scheduled,
        effectiveAt = null,
        source = EventSource.SYSTEM,
        payload = EventPayload.None,
    )

    // The trigger. The terminal set comes from Occurrence.isTerminal, the single Kotlin definition,
    // so a terminal state added to the enum without updating the trigger fails here. The update is
    // issued through the generated query, below the repository, because the trigger exists to
    // protect against any writer. Since schema version 5 (ADR 0068) any update to a terminal row aborts,
    // including one that keeps its state, so every update to a terminal row is rejected here; the columns
    // other than the state are in `trigger rejects an update of any column of a terminal row`.
    @Test
    fun `trigger rejects every change out of a terminal state and allows the rest`() {
        for ((label, db) in bothDatabases()) {
            seedTemplate(db)
            val repo = SqlDelightOccurrenceRepository(db)
            var n = 0
            for (from in OccurrenceState.entries) {
                for (to in OccurrenceState.entries) {
                    // A fresh row per pair: a row that legitimately reached a terminal state can
                    // never be moved back, so it cannot be reused for the next target.
                    n++
                    val row =
                        occurrence(from, slot = n).copy(id = "occ-$n", localDate = LocalDate(2026, 1, n))
                    repo.insert(row)
                    val context = "$label: $from to $to"
                    if (row.isTerminal) {
                        assertFailsWith<Exception>(
                            context,
                        ) { db.occurrenceQueries.updateOccurrenceState(to.name, row.id) }
                        assertEquals(from, repo.findById(row.id)?.state, "$context changed the row anyway")
                    } else {
                        db.occurrenceQueries.updateOccurrenceState(to.name, row.id)
                        assertEquals(to, repo.findById(row.id)?.state, context)
                    }
                }
            }
        }
    }

    // Every updatable column, on every state (ADR 0068, schema version 5). A terminal row refuses any update: its
    // state, its instant, its zone, its date, its slot, its template and its id, even an update that changes
    // nothing. An open row accepts every one of them. The updates are raw SQL, below the repository and the generated
    // queries, because the trigger exists to protect against any writer.
    @Test
    fun `trigger rejects an update of any column of a terminal row`() {
        val updates =
            listOf(
                "state" to "state = 'PENDING'",
                "state, unchanged" to "state = state",
                "scheduled_instant" to "scheduled_instant = scheduled_instant + 1",
                "time_zone_id" to "time_zone_id = 'Asia/Tokyo'",
                "local_date" to "local_date = date(local_date, '+1000 days')",
                "alarm_slot" to "alarm_slot = alarm_slot + 1000",
                "template_id, unchanged" to "template_id = template_id",
                "id" to "id = id || 'x'",
            )
        for ((label, db, driver) in bothWithDrivers()) {
            seedTemplate(db)
            val repo = SqlDelightOccurrenceRepository(db)
            var n = 0
            for (state in OccurrenceState.entries) {
                for ((column, set) in updates) {
                    n++
                    val row =
                        occurrence(
                            state,
                            slot = n,
                        ).copy(id = "occ-$n", localDate = LocalDate.fromEpochDays(20_000 + n))
                    repo.insert(row)
                    val context = "$label: $state, $column"
                    if (row.isTerminal) {
                        assertFailsWith<Exception>(context) {
                            driver.execute(null, "UPDATE occurrence SET $set WHERE id = '${row.id}'", 0)
                        }
                        assertEquals(row, repo.findById(row.id), "$context: the row changed anyway")
                    } else {
                        driver.execute(null, "UPDATE occurrence SET $set WHERE id = '${row.id}'", 0)
                    }
                }
            }
        }
    }

    private fun bothWithDrivers(): List<Triple<String, MomTimeDatabase, JdbcSqliteDriver>> {
        val fresh = freshV2()
        val freshDriver = drivers.last()
        val migrated = migratedFromV1()
        return listOf(
            Triple("fresh", fresh, freshDriver),
            Triple("migrated from v1", migrated, drivers.last()),
        )
    }

    // State and event are one operation. A rejected transition leaves no event behind.
    @Test
    fun `a rejected transition rolls back its event and leaves the state`() {
        for ((label, db) in bothDatabases()) {
            seedTemplate(db)
            val repo = SqlDelightOccurrenceRepository(db)
            val events = SqlDelightEventRepository(db)
            repo.insert(occurrence(OccurrenceState.PENDING))
            val id = "occ-PENDING"

            repo.transition(id, OccurrenceState.COMPLETED, event("e1", id, EventType.COMPLETED))
            assertEquals(OccurrenceState.COMPLETED, repo.findById(id)?.state, label)
            assertEquals(listOf("e1"), events.findForOccurrence(id).map { it.id }, label)

            assertFailsWith<Exception>(label) {
                repo.transition(id, OccurrenceState.PENDING, event("e2", id, EventType.SNOOZED))
            }
            assertEquals(OccurrenceState.COMPLETED, repo.findById(id)?.state, "$label: terminal state changed")
            assertEquals(
                listOf("e1"),
                events.findForOccurrence(id).map { it.id },
                "$label: event survived a rejected transition",
            )
        }
    }

    @Test
    fun `a transition naming another occurrences event is refused`() {
        val db = freshV2()
        seedTemplate(db)
        val repo = SqlDelightOccurrenceRepository(db)
        repo.insert(occurrence(OccurrenceState.PENDING))
        assertFailsWith<IllegalArgumentException> {
            repo.transition(
                "occ-PENDING",
                OccurrenceState.COMPLETED,
                event("e1", "some-other-occurrence", EventType.COMPLETED),
            )
        }
        assertEquals(OccurrenceState.PENDING, repo.findById("occ-PENDING")?.state)
    }

    // The CHECK. Raw updates through the generated query, below the repository and below the
    // QuietHours value object, because the constraint exists to protect against any writer.
    @Test
    fun `quiet hours CHECK rejects half-set and equal bounds and accepts null or distinct`() {
        for ((label, db) in bothDatabases()) {
            db.appSettingsQueries.seedAppSettings()
            val q = db.appSettingsQueries
            assertFailsWith<Exception>("$label: start only") { q.updateQuietHours("22:00", null) }
            assertFailsWith<Exception>("$label: end only") { q.updateQuietHours(null, "06:00") }
            assertFailsWith<Exception>("$label: equal") { q.updateQuietHours("22:00", "22:00") }

            q.updateQuietHours("22:00", "06:00")
            assertEquals("22:00", q.selectAppSettings().executeAsOne().quiet_hours_start, label)
            q.updateQuietHours(null, null)
            assertNull(q.selectAppSettings().executeAsOne().quiet_hours_start, label)
        }
    }

    @Test
    fun `the repository round-trips quiet hours and null`() {
        val repo = SqlDelightAppSettingsRepository(freshV2())
        assertNull(repo.current().quietHours)
        repo.updateQuietHours(QuietHours(LocalTime(13, 0), LocalTime(15, 30)))
        assertEquals(QuietHours(LocalTime(13, 0), LocalTime(15, 30)), repo.current().quietHours)
        repo.updateQuietHours(null)
        assertNull(repo.current().quietHours)
    }

    // The migration itself, with data. A version 1 row that cannot satisfy the new CHECK is
    // copied with both bounds cleared; everything else survives untouched.
    @Test
    fun `migration preserves valid settings and other columns`() {
        val db =
            migratedFromV1 { driver ->
                driver.execute(
                    null,
                    "INSERT INTO app_settings(id, quiet_hours_start, quiet_hours_end, ring_grade_daily_budget, " +
                        "snooze_duration_minutes, locale_override, telemetry_opt_in) VALUES (0, '22:00', '06:00', 8, 15, 'hi', 1)",
                    0,
                )
            }
        val settings = SqlDelightAppSettingsRepository(db).current()
        assertEquals(QuietHours(LocalTime(22, 0), LocalTime(6, 0)), settings.quietHours)
        assertEquals(8, settings.ringGradeDailyBudget)
        assertEquals(15, settings.snoozeDurationMinutes)
        assertEquals("hi", settings.localeOverride)
        assertTrue(settings.telemetryOptIn)
    }

    @Test
    fun `migration clears version 1 quiet hours that cannot satisfy the CHECK`() {
        for ((start, end) in listOf("'22:00'" to "'22:00'", "'22:00'" to "NULL", "NULL" to "'06:00'")) {
            val db =
                migratedFromV1 { driver ->
                    driver.execute(
                        null,
                        "INSERT INTO app_settings(id, quiet_hours_start, quiet_hours_end) VALUES (0, $start, $end)",
                        0,
                    )
                }
            val settings = SqlDelightAppSettingsRepository(db).current()
            assertNull(settings.quietHours, "start=$start end=$end should have been cleared")
            assertEquals(12, settings.ringGradeDailyBudget, "defaults must survive the rebuild")
        }
    }

    @Test
    fun `migration preserves existing occurrence rows`() {
        val db =
            migratedFromV1 { driver ->
                seedTemplate(MomTimeDatabase(driver))
                driver.execute(
                    null,
                    "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, " +
                        "time_zone_id, state, alarm_slot) VALUES ('occ-1', 'tmpl-1', '2026-01-01', " +
                        "${scheduled.toEpochMilliseconds()}, 'Asia/Kolkata', 'MISSED', 7)",
                    0,
                )
            }
        val row = SqlDelightOccurrenceRepository(db).findById("occ-1")
        assertEquals(OccurrenceState.MISSED, row?.state)
        assertEquals(7, row?.alarmSlot)
    }

    // Reconcile never rewrites a terminal occurrence, even when the row and the event log have
    // diverged (terminal state, no terminal event). The divergent row is built directly.
    @Test
    fun `reconcile appends nothing for a terminal occurrence that has no terminal event`() {
        val db = freshV2()
        seedTemplate(db)
        val repo = SqlDelightOccurrenceRepository(db)
        val events = SqlDelightEventRepository(db)
        val terminal = OccurrenceState.entries.map { occurrence(it, slot = it.ordinal + 1) }.filter { it.isTerminal }
        assertTrue(terminal.isNotEmpty())
        terminal.forEachIndexed { index, row ->
            repo.insert(row.copy(localDate = LocalDate(2026, 1, 1 + index)))
        }

        for (row in terminal) {
            val stored = checkNotNull(repo.findById(row.id))
            val result =
                Reconcile.evaluate(
                    occurrence = stored,
                    criticality = Criticality.CRITICAL,
                    now = stored.scheduledInstant + 48.hours,
                    hasTerminalEvent = events.findForOccurrence(stored.id).isEmpty().not(),
                    generateId = { "should-not-be-used" },
                )
            assertNull(result, "${stored.state}: Reconcile appended an event for a terminal occurrence")
        }
        assertTrue(terminal.all { events.findForOccurrence(it.id).isEmpty() })
    }

    // The end-of-migration foreign_key_check can fail (ADR 0043). The orphan is planted with
    // enforcement switched off, standing in for a migration that rebuilds a table and leaves a
    // child row without its parent. The v1 to v2 migration does not touch the occurrence table, so
    // it does not notice; only PRAGMA foreign_key_check does.
    @Suppress("UNUSED_PARAMETER")
    private fun plantOrphanOccurrence(enforcing: JdbcSqliteDriver) {
        // A plain JdbcSqliteDriver on the same file: SQLite's default is enforcement off, which is
        // exactly what lets a bad migration leave an orphan behind.
        JdbcSqliteDriver("jdbc:sqlite:$lastV1File").use { plain ->
            check(plain.pragma("foreign_keys") == "0") { "the planting connection must not enforce" }
            plain.execute(
                null,
                "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, " +
                    "time_zone_id, state, alarm_slot) VALUES ('orphan', 'no-such-template', '2026-01-01', " +
                    "${scheduled.toEpochMilliseconds()}, 'Asia/Kolkata', 'PENDING', 1)",
                0,
            )
        }
    }

    @Test
    fun `foreign_key_check reports an orphan the migration let through`() {
        migratedFromV1(checkForeignKeys = false) { plantOrphanOccurrence(it) }
        val violations = drivers.last().foreignKeyViolations()
        assertEquals(1, violations.size, "expected exactly the planted orphan: $violations")
        assertTrue(violations.single().startsWith("occurrence "), violations.single())
    }

    @Test
    fun `a migration test with an orphan fails on the foreign key check`() {
        val failure = assertFailsWith<AssertionError> { migratedFromV1 { plantOrphanOccurrence(it) } }
        assertTrue(failure.message.orEmpty().contains("foreign key violations"), failure.message)
    }
}
