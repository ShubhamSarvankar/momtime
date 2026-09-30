package com.momtime.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

data class WaterGoal(
    val pregnancyId: String,
    val dailyGoalMl: Int,
    val nudgeTimesPerDay: Int,
)

data class AppSettings(
    val quietHoursStart: LocalTime?,
    val quietHoursEnd: LocalTime?,
    val ringGradeDailyBudget: Int,
    val snoozeDurationMinutes: Int,
    val localeOverride: String?,
    val telemetryOptIn: Boolean,
)

/**
 * budgetDate is the local calendar date in the current zone (ADR 0032) — a timezone change may
 * shorten or lengthen a day's effective window, which is accepted as correct.
 */
data class InterruptionBudget(
    val budgetDate: LocalDate,
    val ringCount: Int,
)

data class SyncState(
    val occurrencesSyncedThrough: Instant?,
    val lastSyncAt: Instant?,
)
