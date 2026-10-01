package com.momtime.shared.data

import com.momtime.shared.domain.AlarmDeliveryTelemetry
import com.momtime.shared.domain.CaregiverLink
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
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

    @Test
    fun `alarm delivery telemetry is absent when declined and present when recorded`() {
        val eventRepo = SqlDelightEventRepository(database)
        val event =
            Event(
                id = "evt-1",
                occurrenceId = null,
                eventType = EventType.ALARM_FIRED,
                deviceTimestamp = Instant.fromEpochMilliseconds(0),
                effectiveAt = null,
                source = EventSource.SYSTEM,
                payload = EventPayload.None,
            )
        eventRepo.insert(event)

        val telemetryRepo = SqlDelightAlarmDeliveryTelemetryRepository(database)
        // Declined telemetry: zero rows, not nulls scattered through the log (ADR 0033 Q3).
        assertNull(telemetryRepo.findForEvent(event.id))

        val telemetry =
            AlarmDeliveryTelemetry(
                eventId = event.id,
                alarmSlot = 7,
                resolvedTier = DeliveryCapability.TIER_2,
                canaryScheduledAt = Instant.fromEpochMilliseconds(100),
                canaryActualAt = Instant.fromEpochMilliseconds(150),
                screenOn = true,
                audioFocusObtained = false,
                batteryPct = 42,
                dozeState = "ACTIVE",
            )
        telemetryRepo.insert(telemetry)
        assertEquals(telemetry, telemetryRepo.findForEvent(event.id))
    }
}
