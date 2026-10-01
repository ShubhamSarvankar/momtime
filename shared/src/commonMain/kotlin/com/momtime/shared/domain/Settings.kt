package com.momtime.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

data class WaterGoal(
    val pregnancyId: String,
    val dailyGoalMl: Int,
    val nudgeTimesPerDay: Int,
)

/**
 * A quiet-hours window, evaluated against the wall clock in the current zone (ADR 0032). Start and
 * end must differ: equal times would be ambiguous between a full day and no window, and
 * rejecting them at construction is the only reading that never guesses intent (ADR 0037). The
 * window wraps midnight when start is later than end. "No quiet hours" is a null QuietHours,
 * which also makes a half-set window unrepresentable.
 */
data class QuietHours(
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        require(start != end) { "quiet hours start and end must differ" }
    }
}

data class AppSettings(
    val quietHours: QuietHours?,
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
