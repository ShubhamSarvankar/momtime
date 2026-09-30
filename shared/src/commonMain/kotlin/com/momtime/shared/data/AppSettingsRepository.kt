package com.momtime.shared.data

import com.momtime.shared.domain.AppSettings
import kotlinx.datetime.LocalTime

interface AppSettingsRepository {
    fun ensureSeeded()

    fun current(): AppSettings

    fun updateQuietHours(
        start: LocalTime?,
        end: LocalTime?,
    )

    fun updateRingGradeDailyBudget(budget: Int)

    fun updateSnoozeDurationMinutes(minutes: Int)

    fun updateLocaleOverride(locale: String?)

    fun updateTelemetryOptIn(optIn: Boolean)
}

class SqlDelightAppSettingsRepository(
    private val database: MomTimeDatabase,
) : AppSettingsRepository {
    override fun ensureSeeded() {
        database.appSettingsQueries.seedAppSettings()
    }

    override fun current(): AppSettings {
        ensureSeeded()
        val row = database.appSettingsQueries.selectAppSettings().executeAsOne()
        return AppSettings(
            quietHoursStart = row.quiet_hours_start?.let { LocalTime.parse(it) },
            quietHoursEnd = row.quiet_hours_end?.let { LocalTime.parse(it) },
            ringGradeDailyBudget = row.ring_grade_daily_budget.toInt(),
            snoozeDurationMinutes = row.snooze_duration_minutes.toInt(),
            localeOverride = row.locale_override,
            telemetryOptIn = row.telemetry_opt_in.toBoolean(),
        )
    }

    override fun updateQuietHours(
        start: LocalTime?,
        end: LocalTime?,
    ) {
        database.appSettingsQueries.updateQuietHours(start?.toString(), end?.toString())
    }

    override fun updateRingGradeDailyBudget(budget: Int) {
        database.appSettingsQueries.updateRingGradeDailyBudget(budget.toLong())
    }

    override fun updateSnoozeDurationMinutes(minutes: Int) {
        database.appSettingsQueries.updateSnoozeDurationMinutes(minutes.toLong())
    }

    override fun updateLocaleOverride(locale: String?) {
        database.appSettingsQueries.updateLocaleOverride(locale)
    }

    override fun updateTelemetryOptIn(optIn: Boolean) {
        database.appSettingsQueries.updateTelemetryOptIn(optIn.toDb())
    }
}
