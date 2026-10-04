package com.momtime.shared.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.momtime.shared.domain.EventPayload
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Schema version 4 (ADR 0066): a snooze records when it ends, in `event.snoozed_until`. Forward migration tests from
 * every prior version (invariant 2), with foreign keys enforced and ending in `foreign_key_check` (ADR 0043).
 * Versions 2 and 3 have no committed snapshot, so each is made the way it was made: the committed version 1
 * baseline, migrated by `1.sqm` (and `2.sqm`).
 *
 * A `SNOOZED` event written before the column has no end. The migration gives it its own timestamp plus ten
 * minutes, the default duration in force for every snooze recorded so far, and leaves every other event alone.
 */
class SchemaV4Test {
    private val tempFiles = mutableListOf<Path>()
    private val drivers = mutableListOf<SqlDriver>()

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        tempFiles.forEach { Files.deleteIfExists(it) }
    }

    private fun baselineCopy(): SqlDriver {
        val file = Files.createTempFile("momtime-v4", ".db").also { tempFiles.add(it) }
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

    /** A database at [version] (1 to 3): the baseline, migrated step by step. [seed] runs on it at that version. */
    private fun at(
        version: Long,
        seed: (SqlDriver) -> Unit,
    ): SqlDriver {
        val driver = baselineCopy()
        if (version >= 2) migrate(driver, 1, 2)
        if (version >= 3) migrate(driver, 2, 3)
        seed(driver)
        return driver
    }

    private fun seedSnoozes(driver: SqlDriver) {
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
        exec(
            driver,
            "INSERT INTO occurrence(id, template_id, local_date, scheduled_instant, time_zone_id, state, " +
                "alarm_slot) VALUES ('occ-1', 'tmpl-1', '2026-01-01', 0, 'Asia/Kolkata', 'SNOOZED', 1)",
        )
        // Two snoozes already recorded, and events of other types that must come through untouched.
        exec(
            driver,
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source, snooze_number) " +
                "VALUES ('snz-1', 'occ-1', 'SNOOZED', 1000, 'USER', 1)",
        )
        exec(
            driver,
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source, snooze_number) " +
                "VALUES ('snz-2', 'occ-1', 'SNOOZED', 5000, 'USER', 2)",
        )
        exec(
            driver,
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, source) " +
                "VALUES ('fired', 'occ-1', 'ALARM_FIRED', 900, 'SYSTEM')",
        )
        exec(
            driver,
            "INSERT INTO event(id, occurrence_id, event_type, device_timestamp, effective_at, source) " +
                "VALUES ('missed', 'occ-1', 'MISSED', 9000, 7200000, 'SYSTEM')",
        )
    }

    private fun columns(driver: SqlDriver): List<String> =
        driver
            .executeQuery(
                null,
                "PRAGMA table_info(event)",
                { cursor ->
                    val names = mutableListOf<String>()
                    while (cursor.next().value) names += checkNotNull(cursor.getString(1))
                    QueryResult.Value(names.toList())
                },
                0,
            ).value

    private fun assertV4(driver: SqlDriver) {
        val events = SqlDelightEventRepository(MomTimeDatabase(driver))
        assertTrue("snoozed_until" in columns(driver), "the column must exist")
        assertEquals(
            EventPayload.Snooze(1, Instant.fromEpochMilliseconds(1000 + 600_000)),
            events.findById("snz-1")?.payload,
            "a snooze recorded before the column ends ten minutes after it was taken",
        )
        assertEquals(
            EventPayload.Snooze(2, Instant.fromEpochMilliseconds(5000 + 600_000)),
            events.findById("snz-2")?.payload,
        )
        assertEquals(EventPayload.None, events.findById("fired")?.payload, "other events are untouched")
        assertNull(events.findById("fired")?.effectiveAt)
        assertEquals(Instant.fromEpochMilliseconds(7_200_000), events.findById("missed")?.effectiveAt)
        assertEquals(emptyList(), driver.foreignKeyViolations(), "the migration left foreign key violations")
    }

    @Test
    fun `a version 3 database migrates to 4 and gives each snooze its end`() {
        val driver = at(3) { seedSnoozes(it) }

        migrate(driver, 3, 4)

        assertV4(driver)
    }

    @Test
    fun `a version 2 database migrates to 4 in one run`() {
        val driver = at(2) { seedSnoozes(it) }

        migrate(driver, 2, 4)

        assertV4(driver)
    }

    @Test
    fun `a version 1 database migrates to 4 in one run`() {
        val driver = at(1) { seedSnoozes(it) }

        migrate(driver, 1, 4)

        assertV4(driver)
    }

    @Test
    fun `a fresh database has the column`() {
        val driver = JvmDatabaseDriverFactory.inMemory().createDriver().also { drivers.add(it) }

        assertTrue("snoozed_until" in columns(driver))
    }
}
