package com.momtime.shared.domain

enum class Criticality { CRITICAL, STANDARD, GENTLE }

enum class TaskType { MEDICINE, SUPPLEMENT, MEAL, FOOD, CUSTOM }

enum class PregnancyPhase { PRENATAL, POSTPARTUM }

enum class NutritionTag { FRUIT, VEGETABLE, PROTEIN, IRON, CALCIUM, DAIRY, SUPPLEMENT }

enum class OccurrenceState { PENDING, COMPLETED, SNOOZED, SKIPPED, MISSED, WITHDRAWN }

/**
 * The single definition of a terminal state: `COMPLETED`, `SKIPPED`, `MISSED` and `WITHDRAWN` (ADR 0079). The
 * schema's trigger refuses any update to such a row, and a test fails if the two drift.
 */
val OccurrenceState.isTerminal: Boolean
    get() =
        this == OccurrenceState.COMPLETED ||
            this == OccurrenceState.SKIPPED ||
            this == OccurrenceState.MISSED ||
            this == OccurrenceState.WITHDRAWN

enum class EventSource { USER, SYSTEM }

enum class Channel { RING, RING_REPEAT, CAREGIVER_INFO, CAREGIVER_URGENT, PHONE_CALL }

enum class DeliveryCapability { TIER_1, TIER_2, TIER_3 }

enum class NotificationPolicy { PER_EVENT_CRITICAL, DIGEST, OFF }

enum class EventType {
    OCCURRENCE_MATERIALISED,
    ALARM_SCHEDULED,
    ALARM_FIRED,
    COMPLETED,
    COMPLETED_BACKFILLED,
    SNOOZED,
    SNOOZE_ENDED,
    SKIPPED,
    MISSED,
    WITHDRAWN,
    MISSION_VERIFIED,
    MISSION_BYPASSED,
    WATER_LOGGED,
    WEIGHT_LOGGED,
    CANARY_RESULT,
    WATCHDOG_REPAIR,
    CAREGIVER_LINKED,
    CAREGIVER_REVOKED,
    SHARING_PAUSED,
    SHARING_RESUMED,
    CAREGIVER_NOTIFIED,
}
