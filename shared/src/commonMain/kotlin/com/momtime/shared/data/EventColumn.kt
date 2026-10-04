package com.momtime.shared.data

import com.momtime.shared.domain.EventType

/**
 * The nullable columns of `event` that depend on the event's type. Their names are the columns'.
 */
internal enum class EventColumn {
    EFFECTIVE_AT,
    SNOOZE_NUMBER,
    SNOOZED_UNTIL,
    MISSION_RESULT_TYPE,
    WATER_ML,
    WEIGHT_GRAMS,
    CAREGIVER_LINK_ID,
    CANARY_SCHEDULED_AT,
    CANARY_ACTUAL_AT,
}

/**
 * Which type dependent columns each event type may carry. The table stores the payload as sparse
 * nullable columns and ties no column to a type, so nothing in the schema stops a mapping bug writing a
 * water amount on a COMPLETED event. Decoding checks this instead and fails loudly (ADR 0052): a row that
 * breaks it is a bug in a writer, and decoding it as a payload nobody wrote would hide that.
 *
 * It is a list of what is allowed, so an absent column is always fine. The `when` is exhaustive, so a new
 * [EventType] does not compile until it is given a set. `EFFECTIVE_AT` belongs to MISSED alone (ADR 0030).
 * A bypassed mission may name the mission that was bypassed, so it takes the same column as a verified one.
 */
internal fun allowedColumns(type: EventType): Set<EventColumn> =
    when (type) {
        EventType.MISSED -> setOf(EventColumn.EFFECTIVE_AT)
        EventType.SNOOZED -> setOf(EventColumn.SNOOZE_NUMBER, EventColumn.SNOOZED_UNTIL)
        EventType.MISSION_VERIFIED, EventType.MISSION_BYPASSED -> setOf(EventColumn.MISSION_RESULT_TYPE)
        EventType.WATER_LOGGED -> setOf(EventColumn.WATER_ML)
        EventType.WEIGHT_LOGGED -> setOf(EventColumn.WEIGHT_GRAMS)
        EventType.CAREGIVER_LINKED,
        EventType.CAREGIVER_REVOKED,
        EventType.SHARING_PAUSED,
        EventType.SHARING_RESUMED,
        EventType.CAREGIVER_NOTIFIED,
        -> setOf(EventColumn.CAREGIVER_LINK_ID)
        EventType.CANARY_RESULT -> setOf(EventColumn.CANARY_SCHEDULED_AT, EventColumn.CANARY_ACTUAL_AT)
        EventType.OCCURRENCE_MATERIALISED,
        EventType.ALARM_SCHEDULED,
        EventType.ALARM_FIRED,
        EventType.COMPLETED,
        EventType.COMPLETED_BACKFILLED,
        EventType.SNOOZE_ENDED,
        EventType.SKIPPED,
        EventType.WATCHDOG_REPAIR,
        -> emptySet()
    }
