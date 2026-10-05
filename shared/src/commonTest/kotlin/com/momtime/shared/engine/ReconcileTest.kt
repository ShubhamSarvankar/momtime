package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ReconcileTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val scheduledInstant = Instant.fromEpochMilliseconds(1_700_000_000_000)

    private fun occurrence(state: OccurrenceState = OccurrenceState.PENDING) =
        Occurrence(
            id = "occ-1",
            templateId = "tmpl-1",
            localDate = scheduledInstant.toLocalDateTime(zone).date,
            scheduledInstant = scheduledInstant,
            timeZoneId = zone,
            state = state,
            alarmSlot = 1,
            criticality = Criticality.CRITICAL,
        )

    @Test
    fun `before grace expiry produces nothing`() {
        val result =
            Reconcile.evaluate(
                occurrence = occurrence(),
                criticality = Criticality.CRITICAL,
                now = scheduledInstant + 1.hours,
                hasTerminalEvent = false,
                generateId = { "evt-1" },
            )
        assertNull(result)
    }

    // Golden scenario 5: grace expiring while the app is closed produces MISSED without a ring,
    // where deviceTimestamp reflects discovery time but effectiveAt reflects the true expiry
    // instant.
    @Test
    fun `after grace expiry produces MISSED with effectiveAt at the true expiry instant, not now`() {
        val discoveredMuchLater = scheduledInstant + 30.hours
        val result =
            Reconcile.evaluate(
                occurrence = occurrence(),
                criticality = Criticality.CRITICAL,
                now = discoveredMuchLater,
                hasTerminalEvent = false,
                generateId = { "evt-1" },
            )
        checkNotNull(result)
        assertEquals(EventType.MISSED, result.eventType)
        assertEquals(discoveredMuchLater, result.deviceTimestamp)
        assertEquals(scheduledInstant + 2.hours, result.effectiveAt)
    }

    @Test
    fun `a terminal event already present produces nothing`() {
        val result =
            Reconcile.evaluate(
                occurrence = occurrence(state = OccurrenceState.COMPLETED),
                criticality = Criticality.CRITICAL,
                now = scheduledInstant + 30.hours,
                hasTerminalEvent = true,
                generateId = { "evt-1" },
            )
        assertNull(result)
    }

    @Test
    fun `gentle grace window is end of local day, not a fixed duration`() {
        // scheduledInstant is 2023-11-14T22:13:20Z = 2023-11-15T03:43:20 IST. End of that local
        // day is 2023-11-16T00:00 IST, ~20h17m after scheduledInstant.
        val stillSameDay =
            Reconcile.evaluate(
                occurrence = occurrence(),
                criticality = Criticality.GENTLE,
                now = scheduledInstant + 10.hours,
                hasTerminalEvent = false,
                generateId = { "evt-1" },
            )
        val pastMidnight =
            Reconcile.evaluate(
                occurrence = occurrence(),
                criticality = Criticality.GENTLE,
                now = scheduledInstant + 21.hours,
                hasTerminalEvent = false,
                generateId = { "evt-1" },
            )
        assertNull(stillSameDay)
        checkNotNull(pastMidnight)
    }

    // Required property: adherence figures are invariant to reconciliation timing. A single
    // fixture isn't enough — this runs across many randomised reconciliation delays (fixed
    // seed for determinism) and asserts effectiveAt, and therefore every adherence figure
    // derived from it, never depends on when Reconcile happened to run.
    @Test
    fun `effectiveAt is identical regardless of reconciliation delay - property test`() {
        val random = Random(42)
        val expectedEffectiveAt = scheduledInstant + 2.hours

        repeat(200) {
            // Any delay from just-past-grace to ~10 days later.
            val delayMinutes = random.nextLong(0, 10 * 24 * 60)
            val now = expectedEffectiveAt + delayMinutes.minutes

            val result =
                Reconcile.evaluate(
                    occurrence = occurrence(),
                    criticality = Criticality.CRITICAL,
                    now = now,
                    hasTerminalEvent = false,
                    generateId = { "evt-1" },
                )

            checkNotNull(result) { "expected a MISSED event at delay=${delayMinutes}m" }
            assertEquals(expectedEffectiveAt, result.effectiveAt, "effectiveAt drifted at delay=${delayMinutes}m")
            assertEquals(now, result.deviceTimestamp, "deviceTimestamp should track now, not effectiveAt")
        }
    }

    private fun evaluate(
        criticality: Criticality,
        now: Instant,
        state: OccurrenceState = OccurrenceState.PENDING,
    ) = Reconcile.evaluate(
        occurrence = occurrence(state),
        criticality = criticality,
        now = now,
        hasTerminalEvent = false,
        generateId = { "evt-1" },
    )

    // Every criticality has its own grace arm. Nothing one minute before expiry, MISSED exactly at
    // expiry, with effectiveAt the expiry instant. scheduledInstant is 2023-11-15T03:43:20 IST, so
    // the GENTLE expiry (the end of that local day) is 2023-11-16T00:00 IST = 2023-11-15T18:30Z.
    @Test
    fun `every criticality grace arm - nothing a minute before expiry, MISSED exactly at expiry`() {
        val expiries =
            mapOf(
                Criticality.CRITICAL to scheduledInstant + 2.hours,
                Criticality.STANDARD to scheduledInstant + 4.hours,
                Criticality.GENTLE to Instant.parse("2023-11-15T18:30:00Z"),
            )
        for ((criticality, expiry) in expiries) {
            assertNull(evaluate(criticality, expiry - 1.minutes), "$criticality one minute before expiry")
            val event = checkNotNull(evaluate(criticality, expiry)) { "$criticality exactly at expiry" }
            assertEquals(EventType.MISSED, event.eventType)
            assertEquals(expiry, event.effectiveAt, "$criticality effectiveAt")
        }
    }

    // A snoozed occurrence that outlives its grace window must still become MISSED. If SNOOZED were
    // treated like a terminal state, a snoozed dose would never be marked missed.
    // The guard inside evaluate, on its own: no terminal event is reported, so only isTerminal stands between a
    // withdrawn occurrence well past its grace and a MISSED event (ADR 0079).
    @Test
    fun `Reconcile evaluate leaves a withdrawn occurrence alone`() {
        val result =
            Reconcile.evaluate(
                occurrence = occurrence(OccurrenceState.WITHDRAWN),
                criticality = Criticality.CRITICAL,
                now = scheduledInstant + 30.hours,
                hasTerminalEvent = false,
                generateId = { "evt-1" },
            )
        assertNull(result)
    }

    // The pure half of the test of the same name in CommandsTest, which dispatches the command over a database:
    // a withdrawn occurrence with its WITHDRAWN event, a day after its grace, produces no MISSED.
    @Test
    fun `a withdrawn occurrence is in none of the three figures, even after its grace`() {
        for (criticality in Criticality.entries) {
            val withdrawn = occurrence(OccurrenceState.WITHDRAWN)
            val dayAfterGrace = Reconcile.graceExpiryInstant(withdrawn, criticality) + 24.hours
            assertNull(
                Reconcile.evaluate(withdrawn, criticality, dayAfterGrace, hasTerminalEvent = true) { "evt-1" },
                "$criticality",
            )
        }
    }

    @Test
    fun `a SNOOZED occurrence past grace becomes MISSED, and is untouched before it`() {
        val expiry = scheduledInstant + 2.hours
        assertNull(evaluate(Criticality.CRITICAL, expiry - 1.minutes, OccurrenceState.SNOOZED))
        val event = checkNotNull(evaluate(Criticality.CRITICAL, expiry + 5.minutes, OccurrenceState.SNOOZED))
        assertEquals(EventType.MISSED, event.eventType)
        assertEquals(expiry, event.effectiveAt)
    }
}
