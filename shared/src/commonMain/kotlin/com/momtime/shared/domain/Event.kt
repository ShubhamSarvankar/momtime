package com.momtime.shared.domain

import kotlin.time.Instant

/**
 * The append-only log (ARCHITECTURE.md section 3.3, ADR 0003). Nothing updates or deletes an
 * event as part of a domain state transition — see CLAUDE.md invariant 4 and ADR 0026 for the
 * operational/administrative deletion split.
 *
 * effectiveAt is non-null only for MISSED: the computed grace-expiry instant, distinct from
 * deviceTimestamp (write time). Adherence/reports read effectiveAt for MISSED; everything else
 * reads deviceTimestamp. See ADR 0030.
 */
data class Event(
    val id: String,
    val occurrenceId: String?,
    val eventType: EventType,
    val deviceTimestamp: Instant,
    val effectiveAt: Instant?,
    val source: EventSource,
    val payload: EventPayload,
)

/**
 * Typed, event-specific (ARCHITECTURE.md section 3.3). The data layer flattens this to sparse
 * nullable columns on the `event` table (ADR 0033's Q3 telemetry-split reasoning applies the
 * same way here: only one event_type's worth of fields is ever populated per row).
 */
sealed interface EventPayload {
    data object None : EventPayload

    data class Snooze(
        val snoozeNumber: Int,
    ) : EventPayload

    data class MissionResult(
        val missionType: MissionResultType,
    ) : EventPayload

    data class Water(
        val waterMl: Int,
    ) : EventPayload

    data class Weight(
        val weightGrams: Int,
    ) : EventPayload

    data class CaregiverReference(
        val caregiverLinkId: String,
    ) : EventPayload
}

enum class MissionResultType { BARCODE, PHOTO_MATCH }

/**
 * Delivery telemetry for ALARM_SCHEDULED/ALARM_FIRED/CANARY_RESULT/WATCHDOG_REPAIR events,
 * stored in the separate alarm_delivery_telemetry table (ADR 0033 Q3) so its migrations never
 * touch the narrow, hot `event` table. Opt-in — absent entirely (not null-filled) when declined.
 */
data class AlarmDeliveryTelemetry(
    val eventId: String,
    val alarmSlot: Int?,
    val resolvedTier: DeliveryCapability?,
    val canaryScheduledAt: Instant?,
    val canaryActualAt: Instant?,
    val screenOn: Boolean?,
    val audioFocusObtained: Boolean?,
    val batteryPct: Int?,
    val dozeState: String?,
)
