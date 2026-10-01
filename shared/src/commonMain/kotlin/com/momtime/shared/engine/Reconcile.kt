package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * MISSED is a derivation, not an observation (ADR 0030): a pure function of the occurrence, its
 * grace window, the absence of a terminal event, and now. Idempotent — safe to dispatch
 * repeatedly from the watchdog, app foreground, and boot; none of those callers mutate
 * occurrence state directly, they all call this and the domain decides (invariant 3).
 *
 * The returned event's effectiveAt is the computed grace-expiry instant — the moment the
 * occurrence actually became missed — kept separate from deviceTimestamp (now, i.e. whenever
 * this happens to run). Adherence/reports must read effectiveAt for MISSED events, never
 * deviceTimestamp, so reconciliation timing cannot affect adherence accuracy.
 */
object Reconcile {
    fun evaluate(
        occurrence: Occurrence,
        criticality: Criticality,
        now: Instant,
        hasTerminalEvent: Boolean,
        generateId: () -> String,
    ): Event? {
        if (hasTerminalEvent) return null
        // A terminal state with no terminal event is a divergent row. Do nothing: Reconcile never
        // rewrites a terminal occurrence, and never appends a second terminal event for it.
        if (occurrence.isTerminal) return null

        val graceExpiry = graceExpiryInstant(occurrence, criticality)
        if (now < graceExpiry) return null

        return Event(
            id = generateId(),
            occurrenceId = occurrence.id,
            eventType = EventType.MISSED,
            deviceTimestamp = now,
            effectiveAt = graceExpiry,
            source = EventSource.SYSTEM,
            payload = EventPayload.None,
        )
    }

    /** CRITICAL 2h, STANDARD 4h, GENTLE same local day (ARCHITECTURE.md section 4.5). */
    private fun graceExpiryInstant(
        occurrence: Occurrence,
        criticality: Criticality,
    ): Instant =
        when (criticality) {
            Criticality.CRITICAL -> occurrence.scheduledInstant + 2.hours
            Criticality.STANDARD -> occurrence.scheduledInstant + 4.hours
            Criticality.GENTLE -> endOfLocalDay(occurrence.scheduledInstant, occurrence.timeZoneId)
        }

    private fun endOfLocalDay(
        instant: Instant,
        zone: TimeZone,
    ): Instant {
        val date = instant.toLocalDateTime(zone).date
        val nextDate = date.plus(DatePeriod(days = 1))
        return nextDate.atStartOfDayIn(zone)
    }
}
