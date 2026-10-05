package com.momtime.shared.domain

import kotlinx.datetime.TimeZone
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
 * nullable columns on the `event` table (only one event_type's worth of fields is
 * ever populated per row).
 *
 * Nothing platform specific is in the log or in these payloads: the delivery tier, whether the
 * screen turned on, audio focus, battery and Doze state live in the android module's own store
 * (ADR 0048, CLAUDE.md invariant 5).
 */
sealed interface EventPayload {
    data object None : EventPayload

    /**
     * A snooze: its number (the first is 1) and the instant it ends, [snoozedUntil]. The end is a derived instant
     * and is recorded when it is decided, from the snooze duration then in force, as `MISSED` records its
     * `effectiveAt` (ADR 0030): a later change to her snooze setting does not move a snooze already taken, and a
     * server that mirrors events and not settings still knows when it ends (ADR 0066).
     */
    data class Snooze(
        val snoozeNumber: Int,
        val snoozedUntil: Instant,
    ) : EventPayload

    data class MissionResult(
        val missionType: MissionResultType,
    ) : EventPayload

    /**
     * The payload of `COMPLETED` and `COMPLETED_BACKFILLED`, and of nothing else: the nutrition tags the template
     * had when she completed it, so a later edit of the template's tags never changes what she already did
     * (ADR 0086). Every completion carries it; a completion of a template with no tags carries the empty set.
     */
    data class Completion(
        val nutritionTags: Set<NutritionTag>,
    ) : EventPayload

    /**
     * Water she logged and the zone she logged it in: an instant and a zone, never a formatted local time
     * (invariant 9, ADR 0086). [zone] is null only for a row from before schema version 6, whose zone was never
     * recorded.
     */
    data class Water(
        val waterMl: Int,
        val zone: TimeZone?,
    ) : EventPayload

    data class Weight(
        val weightGrams: Int,
    ) : EventPayload

    data class CaregiverReference(
        val caregiverLinkId: String,
    ) : EventPayload

    /**
     * The payload of CANARY_RESULT: when the canary was scheduled to fire, and when it did, or null
     * if it was never seen to fire. Both are platform neutral instants that describe this event.
     * Nothing about how the device delivered it belongs here (invariant 5, ADR 0048).
     */
    data class Canary(
        val scheduledAt: Instant,
        val actualAt: Instant?,
    ) : EventPayload
}

enum class MissionResultType { BARCODE, PHOTO_MATCH }
