package com.momtime.shared.data

import com.momtime.shared.domain.CaregiverLink
import com.momtime.shared.domain.InterruptionBudget
import com.momtime.shared.domain.NotificationPolicy
import com.momtime.shared.domain.Pregnancy
import com.momtime.shared.domain.PregnancyPhase
import com.momtime.shared.domain.QuietHours
import com.momtime.shared.domain.SyncState
import com.momtime.shared.domain.WaterGoal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class RemainingRepositoriesTest {
    private lateinit var driverFactory: JvmDatabaseDriverFactory
    private lateinit var database: MomTimeDatabase

    @BeforeTest
    fun setUp() {
        driverFactory = JvmDatabaseDriverFactory.inMemory()
        database = MomTimeDatabase(driverFactory.createDriver())
    }

    @AfterTest
    fun tearDown() {
        driverFactory.createDriver().close()
    }

    @Test
    fun `caregiver link round trips and revocation hard deletes it`() {
        val repo = SqlDelightCaregiverLinkRepository(database)
        val link =
            CaregiverLink(
                id = "link-1",
                displayName = "Mom",
                invitedAt = Instant.fromEpochMilliseconds(0),
                scopeWeightVisible = false,
                scopeNotesVisible = true,
                scopeDoctorInstructionsVisible = false,
                notificationPolicy = NotificationPolicy.DIGEST,
                pausedUntil = null,
            )
        repo.insert(link)
        assertEquals(listOf(link), repo.findAll())
        assertEquals(1L, repo.count())

        repo.updatePause("link-1", Instant.fromEpochMilliseconds(1_000))
        assertEquals(Instant.fromEpochMilliseconds(1_000), repo.findAll().single().pausedUntil)

        repo.updateNotificationPolicy("link-1", NotificationPolicy.OFF)
        assertEquals(NotificationPolicy.OFF, repo.findAll().single().notificationPolicy)

        // Revocation — hard delete, never a soft flag (ADR 0025).
        repo.deleteById("link-1")
        assertEquals(emptyList(), repo.findAll())
        assertEquals(0L, repo.count())
    }

    // Golden scenario 7, the part whose subject is shared code: revoking one link removes exactly
    // that link and nothing else. The sweep it races with is server-side (Phase 4 exit criterion).
    @Test
    fun `revoking one caregiver link deletes only that link`() {
        val repo = SqlDelightCaregiverLinkRepository(database)

        fun link(id: String) =
            CaregiverLink(
                id = id,
                displayName = id,
                invitedAt = Instant.fromEpochMilliseconds(0),
                scopeWeightVisible = false,
                scopeNotesVisible = false,
                scopeDoctorInstructionsVisible = false,
                notificationPolicy = NotificationPolicy.PER_EVENT_CRITICAL,
                pausedUntil = null,
            )
        repo.insert(link("link-1"))
        repo.insert(link("link-2"))

        repo.deleteById("link-1")

        assertEquals(listOf("link-2"), repo.findAll().map { it.id })
        assertEquals(1L, repo.count())
    }

    @Test
    fun `water goal upserts rather than duplicating`() {
        val pregnancyRepo = SqlDelightPregnancyRepository(database)
        val pregnancy =
            Pregnancy(
                "preg-1",
                PregnancyPhase.PRENATAL,
                Instant.fromEpochMilliseconds(0),
                Instant.fromEpochMilliseconds(0),
            )
        pregnancyRepo.insert(pregnancy)

        val repo = SqlDelightWaterGoalRepository(database)
        repo.upsert(WaterGoal(pregnancy.id, dailyGoalMl = 2000, nudgeTimesPerDay = 3))
        assertEquals(WaterGoal(pregnancy.id, 2000, 3), repo.findForPregnancy(pregnancy.id))

        repo.upsert(WaterGoal(pregnancy.id, dailyGoalMl = 2500, nudgeTimesPerDay = 4))
        assertEquals(WaterGoal(pregnancy.id, 2500, 4), repo.findForPregnancy(pregnancy.id))
    }

    private fun pregnancy(id: String) =
        Pregnancy(id, PregnancyPhase.PRENATAL, Instant.fromEpochMilliseconds(0), Instant.fromEpochMilliseconds(0))

    // The zero-row branch: no goal exists, so the UPDATE changes nothing and the INSERT must run.
    @Test
    fun `a water goal is created where none exists`() {
        SqlDelightPregnancyRepository(database).insert(pregnancy("preg-1"))
        val repo = SqlDelightWaterGoalRepository(database)
        assertNull(repo.findForPregnancy("preg-1"))

        repo.upsert(WaterGoal("preg-1", dailyGoalMl = 1800, nudgeTimesPerDay = 2))

        assertEquals(WaterGoal("preg-1", 1800, 2), repo.findForPregnancy("preg-1"))
    }

    // The update branch: a goal exists, so the UPDATE must change it and no second insert may be
    // attempted (a plain INSERT of an existing key throws). Another pregnancy's goal is untouched,
    // which fails if the UPDATE loses its WHERE clause.
    @Test
    fun `a water goal update changes that pregnancy's goal and no other`() {
        val pregnancies = SqlDelightPregnancyRepository(database)
        pregnancies.insert(pregnancy("preg-1"))
        pregnancies.insert(pregnancy("preg-2"))
        val repo = SqlDelightWaterGoalRepository(database)
        repo.upsert(WaterGoal("preg-1", 2000, 3))
        repo.upsert(WaterGoal("preg-2", 1500, 1))

        repo.upsert(WaterGoal("preg-1", 2600, 5))

        assertEquals(WaterGoal("preg-1", 2600, 5), repo.findForPregnancy("preg-1"))
        assertEquals(WaterGoal("preg-2", 1500, 1), repo.findForPregnancy("preg-2"))
    }

    @Test
    fun `app settings default to seeded values and each field updates independently`() {
        val repo = SqlDelightAppSettingsRepository(database)
        val defaults = repo.current()
        assertEquals(12, defaults.ringGradeDailyBudget)
        assertEquals(10, defaults.snoozeDurationMinutes)
        assertNull(defaults.quietHours)
        assertEquals(false, defaults.telemetryOptIn)

        repo.updateQuietHours(QuietHours(LocalTime(22, 0), LocalTime(6, 0)))
        repo.updateRingGradeDailyBudget(8)
        repo.updateSnoozeDurationMinutes(15)
        repo.updateLocaleOverride("hi")
        repo.updateTelemetryOptIn(true)

        val updated = repo.current()
        assertEquals(QuietHours(LocalTime(22, 0), LocalTime(6, 0)), updated.quietHours)
        assertEquals(8, updated.ringGradeDailyBudget)
        assertEquals(15, updated.snoozeDurationMinutes)
        assertEquals("hi", updated.localeOverride)
        assertEquals(true, updated.telemetryOptIn)
    }

    @Test
    fun `interruption budget resets and increments`() {
        val repo = SqlDelightInterruptionBudgetRepository(database)
        val day1 = LocalDate(2026, 1, 1)
        repo.ensureSeeded(day1)
        assertEquals(InterruptionBudget(day1, 0), repo.current())

        repo.increment()
        repo.increment()
        assertEquals(InterruptionBudget(day1, 2), repo.current())

        val day2 = LocalDate(2026, 1, 2)
        repo.reset(day2)
        assertEquals(InterruptionBudget(day2, 0), repo.current())
    }

    @Test
    fun `sync state updates independently of outbox events`() {
        val repo = SqlDelightSyncStateRepository(database)
        assertEquals(SyncState(null, null), repo.current())

        val synced = SyncState(Instant.fromEpochMilliseconds(500), Instant.fromEpochMilliseconds(1000))
        repo.update(synced)
        assertEquals(synced, repo.current())
    }
}
