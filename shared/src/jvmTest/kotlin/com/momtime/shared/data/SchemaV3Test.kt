package com.momtime.shared.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.momtime.shared.domain.EventPayload
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Schema version 3 (ADR 0048): the delivery tier and device state leave the shared schema, and the
 * canary's two instants become the payload of CANARY_RESULT. Forward migration tests from every
 * prior version (invariant 2), with foreign keys enforced and ending in `foreign_key_check`
 * (ADR 0043). Version 2 has no committed snapshot, so it is made the way it was made: the committed
 * version 1 baseline, migrated by `1.sqm`.
 */
class SchemaV3Test {
    private val tempFiles = mutableListOf<Path>()
    private val drivers = mutableListOf<SqlDriver>()
    private lateinit var lastFile: Path

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    private fun baselineCopy(): SqlDriver {
        val file = Files.createTempFile("momtime-v3", ".db").also { tempFiles.add(it) }
        lastFile = file
        Files.copy(Path.of("src/commonMain/sqldelight/databases/1.db"), file, StandardCopyOption.REPLACE_EXISTING)
        return openJvmSqliteDriver("jdbc:sqlite:$file").also { drivers.add(it) }
    }

    private fun exec(
        driver: SqlDriver,
        sql: String,
    ) {
        driver.execute(null, sql, 0)
    }

    private fun migrate(
        driver: SqlDriver,
        from: Long,
        to: Long,
    ) {
        MomTimeDatabase.Schema.migrate(driver, from, to)
    }

    /** A version 2 database: the version 1 baseline migrated by `1.sqm`. [seed] runs on it before the 2 to 3 step. */
    private fun v2(seed: (SqlDriver) -> Unit): SqlDriver {
        val driver = baselineCopy()
        migrate(driver, 1, 2)
        seed(driver)
        return driver
    }

    private fun seedParents(driver: SqlDriver) {
        exec(
            driver,
            "INSERT INTO pregnancy(id, phase, created_at, phase_changed_at) VALUES ('preg-1', 'PRENATAL', 0, 0)",
        )
        exec(
            driver,
            "INSERT INTO schedule_template(id, pregnancy_id, title, task_type, criticality, time_of_day, " +
                "time_zone_id, recurrence_type) VALUES ('tmpl-1', 'preg-1', 'Iron tablet', 'SUPPLEMENT', " +
                "'CRITICAL', '08:00', 'Asia/Kolkata', 'DAILY')",
        )
    }

    private fun event(
        driver: SqlDriver,
        id: String,
        type: String,
    ) = exec(
        driver,
        "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, effective_at, source) " +
            "VALUES ('$id', NULL, '$type', 0, NULL, 'SYSTEM')",
    )

    private fun telemetry(
        driver: SqlDriver,
        eventId: String,
        canaryScheduled: String,
        canaryActual: String,
    ) = exec(
        driver,
        "INSERT INTO alarm_delivery_telemetry(event_id, alarm_slot, resolved_tier, canary_scheduled_at, " +
            "canary_actual_at, screen_on, audio_focus_obtained, battery_pct, doze_state) VALUES " +
            "('$eventId', 7, 'TIER_2', $canaryScheduled, $canaryActual, 1, 0, 42, 'IDLE')",
    )

    private fun seedEventsWithTelemetry(driver: SqlDriver) {
        seedParents(driver)
        // A canary that fired, a canary never seen to fire, a canary recorded with no telemetry row at
        // all (the form nothing else wrote), and events that carried only device state.
        event(driver, "canary-fired", "CANARY_RESULT")
        telemetry(driver, "canary-fired", "100", "150")
        event(driver, "canary-missed", "CANARY_RESULT")
        telemetry(driver, "canary-missed", "200", "NULL")
        event(driver, "canary-bare", "CANARY_RESULT")
        event(driver, "fired", "ALARM_FIRED")
        telemetry(driver, "fired", "NULL", "NULL")
        event(driver, "repair", "WATCHDOG_REPAIR")
        telemetry(driver, "repair", "NULL", "NULL")
    }

    private fun tables(driver: SqlDriver): Set<String> =
        driver
            .executeQuery(
                null,
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                { cursor ->
                    val names = mutableSetOf<String>()
                    while (cursor.next().value) names += checkNotNull(cursor.getString(0))
                    QueryResult.Value(names.toSet())
                },
                0,
            ).value

    private fun columns(
        driver: SqlDriver,
        table: String,
    ): List<String> =
        driver
            .executeQuery(
                null,
                "PRAGMA table_info($table)",
                { cursor ->
                    val names = mutableListOf<String>()
                    while (cursor.next().value) names += checkNotNull(cursor.getString(1))
                    QueryResult.Value(names.toList())
                },
                0,
            ).value

    private fun assertV3(driver: SqlDriver) {
        val events = SqlDelightEventRepository(MomTimeDatabase(driver))
        assertTrue("alarm_delivery_telemetry" !in tables(driver), "the telemetry table must be gone")
        assertEquals(
            EventPayload.Canary(Instant.fromEpochMilliseconds(100), Instant.fromEpochMilliseconds(150)),
            events.findById("canary-fired")?.payload,
            "the canary instants must move onto their event",
        )
        assertEquals(
            EventPayload.Canary(Instant.fromEpochMilliseconds(200), null),
            events.findById("canary-missed")?.payload,
            "a canary never seen to fire keeps no actual instant",
        )
        // The old form: a CANARY_RESULT with no instants anywhere still decodes, as no payload.
        assertEquals(EventPayload.None, events.findById("canary-bare")?.payload)
        assertEquals(EventPayload.None, events.findById("fired")?.payload)
        assertEquals(EventPayload.None, events.findById("repair")?.payload)
        assertEquals(emptyList(), driver.foreignKeyViolations(), "the migration left foreign key violations")
    }

    @Test
    fun `a version 2 database migrates to 3 and keeps its canary instants`() {
        val driver = v2 { seedEventsWithTelemetry(it) }

        migrate(driver, 2, 3)

        assertV3(driver)
    }

    @Test
    fun `a version 1 database migrates to 3 in one run`() {
        val driver = baselineCopy()
        // A version 1 settings row that cannot satisfy the version 2 CHECK is cleared by 1.sqm.
        exec(driver, "INSERT INTO app_settings(id, quiet_hours_start, quiet_hours_end) VALUES (0, '22:00', '22:00')")
        seedEventsWithTelemetry(driver)

        migrate(driver, 1, 3)

        assertV3(driver)
        assertNull(SqlDelightAppSettingsRepository(MomTimeDatabase(driver)).current().quietHours)
        // 1.sqm still ran: the terminal trigger exists.
        exec(
            driver,
            "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, time_zone_id, state, alarm_slot) VALUES ('o', 'tmpl-1', '2026-01-01', 0, 'Asia/Kolkata', 'MISSED', 1)",
        )
        assertFailsWith<Exception> { exec(driver, "UPDATE occurrence SET state = 'PENDING' WHERE id = 'o'") }
    }

    @Test
    fun `a fresh database has the canary columns and no telemetry table`() {
        val driver = JvmDatabaseDriverFactory.inMemory().createDriver().also { drivers.add(it) }

        assertTrue("alarm_delivery_telemetry" !in tables(driver))
        val eventColumns = columns(driver, "event")
        assertTrue("canary_scheduled_at" in eventColumns && "canary_actual_at" in eventColumns, "$eventColumns")
    }

    // Foreign keys cannot be switched off inside a migration, so a step that left an orphan behind
    // would pass every other check. The orphan is planted on a plain connection, which does not
    // enforce, and neither migration step touches it: only foreign_key_check reports it.
    @Test
    fun `an orphan the migration let through is reported only by foreign_key_check`() {
        val driver =
            v2 { v2driver ->
                seedEventsWithTelemetry(v2driver)
                JdbcSqliteDriver("jdbc:sqlite:$lastFile").use { plain ->
                    check(plain.pragma("foreign_keys") == "0") { "the planting connection must not enforce" }
                    plain.execute(
                        null,
                        "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, time_zone_id, " +
                            "state, alarm_slot) VALUES ('orphan', 'no-such-template', '2026-01-02', 0, " +
                            "'Asia/Kolkata', 'PENDING', 9)",
                        0,
                    )
                }
            }

        migrate(driver, 2, 3)

        val violations = driver.foreignKeyViolations()
        assertEquals(1, violations.size, "expected exactly the planted orphan: $violations")
        assertTrue(violations.single().startsWith("occurrence "), violations.single())
    }
}
