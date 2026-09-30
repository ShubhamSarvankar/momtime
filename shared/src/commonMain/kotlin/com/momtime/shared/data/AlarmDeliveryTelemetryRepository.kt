package com.momtime.shared.data

import com.momtime.shared.domain.AlarmDeliveryTelemetry
import com.momtime.shared.domain.DeliveryCapability

interface AlarmDeliveryTelemetryRepository {
    /** Opt-in (ARCHITECTURE.md section 5.10) — caller only invokes this when telemetry is on. */
    fun insert(telemetry: AlarmDeliveryTelemetry)

    fun findForEvent(eventId: String): AlarmDeliveryTelemetry?
}

class SqlDelightAlarmDeliveryTelemetryRepository(
    private val database: MomTimeDatabase,
) : AlarmDeliveryTelemetryRepository {
    override fun insert(telemetry: AlarmDeliveryTelemetry) {
        database.alarmDeliveryTelemetryQueries.insertAlarmDeliveryTelemetry(
            event_id = telemetry.eventId,
            alarm_slot = telemetry.alarmSlot?.toLong(),
            resolved_tier = telemetry.resolvedTier?.name,
            canary_scheduled_at = telemetry.canaryScheduledAt.toDbOrNull(),
            canary_actual_at = telemetry.canaryActualAt.toDbOrNull(),
            screen_on = telemetry.screenOn?.toDb(),
            audio_focus_obtained = telemetry.audioFocusObtained?.toDb(),
            battery_pct = telemetry.batteryPct?.toLong(),
            doze_state = telemetry.dozeState,
        )
    }

    override fun findForEvent(eventId: String): AlarmDeliveryTelemetry? =
        database.alarmDeliveryTelemetryQueries.selectTelemetryForEvent(eventId).executeAsOneOrNull()?.let {
            AlarmDeliveryTelemetry(
                eventId = it.event_id,
                alarmSlot = it.alarm_slot?.toInt(),
                resolvedTier = it.resolved_tier?.let { tier -> DeliveryCapability.valueOf(tier) },
                canaryScheduledAt = it.canary_scheduled_at.toInstantOrNull(),
                canaryActualAt = it.canary_actual_at.toInstantOrNull(),
                screenOn = it.screen_on.toBooleanOrNull(),
                audioFocusObtained = it.audio_focus_obtained.toBooleanOrNull(),
                batteryPct = it.battery_pct?.toInt(),
                dozeState = it.doze_state,
            )
        }
}
