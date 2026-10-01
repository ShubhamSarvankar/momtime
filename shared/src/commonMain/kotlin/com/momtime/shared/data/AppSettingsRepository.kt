package com.momtime.shared.data

import com.momtime.shared.domain.AppSettings
import com.momtime.shared.domain.QuietHours
import kotlinx.datetime.LocalTime

interface AppSettingsRepository {
    fun ensureSeeded()

    fun current(): AppSettings

    fun updateQuietHours(quietHours: QuietHours?)

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
            quietHours = row.toQuietHours(),
            ringGradeDailyBudget = row.ring_grade_daily_budget.toInt(),
            snoozeDurationMinutes = row.snooze_duration_minutes.toInt(),
            localeOverride = row.locale_override,
            telemetryOptIn = row.telemetry_opt_in.toBoolean(),
        )
    }

    override fun updateQuietHours(quietHours: QuietHours?) {
        database.appSettingsQueries.updateQuietHours(quietHours?.start?.toString(), quietHours?.end?.toString())
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

    // The quiet_hours_valid CHECK guarantees both set and different, or both null (ADR 0037), so a
    // half-set row cannot reach here; if it somehow did, failing loudly beats guessing.
    private fun com.momtime.shared.data.App_settings.toQuietHours(): QuietHours? {
        val start = quiet_hours_start
        val end = quiet_hours_end
        return when {
            start == null && end == null -> null
            start != null && end != null -> QuietHours(LocalTime.parse(start), LocalTime.parse(end))
            else -> error("app_settings has exactly one quiet hours bound set")
        }
    }
}
