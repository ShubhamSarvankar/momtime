package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.FireTiming.Exclusion
import com.momtime.shared.engine.FireTiming.FireSample
import com.momtime.shared.engine.FireTiming.UnfiredRung
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The fire timing reduction (ADR 0070): drift is measured on rungs armed ahead of time only, exclusions are an
 * input, a never fired rung needs an open occurrence whose first rung was armed ahead, and everything is as of an
 * explicit instant. Each test names the case that distinguishes the behaviour it pins.
 */
class FireTimingTest {
    private val rung = Instant.parse("2026-03-01T08:00:00Z")
    private val channels = setOf(Channel.RING, Channel.RING_REPEAT)
    private val after = 15.minutes
    private val farAsOf = rung + 48.hours
    private var seq = 0

    private val occurrence =
        Occurrence(
            id = "occ",
            templateId = "tmpl",
            localDate = LocalDate(2026, 3, 1),
            scheduledInstant = rung,
            timeZoneId = TimeZone.UTC,
            state = OccurrenceState.PENDING,
            alarmSlot = 1,
            criticality = Criticality.CRITICAL,
        )

    private fun event(
        type: EventType,
        at: Instant,
        id: String = "e${seq++}",
        occurrenceId: String? = occurrence.id,
        payload: EventPayload = EventPayload.None,
    ) = Event(id, occurrenceId, type, at, null, EventSource.SYSTEM, payload)

    private fun scheduled(
        id: String,
        at: Instant,
    ) = event(EventType.ALARM_SCHEDULED, at, id)

    private fun fired(
        id: String,
        at: Instant,
    ) = event(EventType.ALARM_FIRED, at, id)

    /** Every arming armed [rung] unless the test says otherwise. */
    @Suppress("LongParameterList")
    private fun compute(
        events: List<Event>,
        asOf: Instant = farAsOf,
        occurrences: List<Occurrence> = listOf(occurrence),
        channelsArg: Set<Channel> = channels,
        neverFiredAfter: Duration = after,
        rungArmedBy: (String) -> Instant? = { rung },
        exclusion: (FireSample) -> Exclusion? = { null },
        unfiredExclusion: (UnfiredRung) -> Exclusion? = { null },
    ) = FireTiming.compute(
        occurrences,
        events,
        { Criticality.STANDARD },
        channelsArg,
        asOf,
        neverFiredAfter,
        rungArmedBy,
        exclusion,
        unfiredExclusion,
    )

    @Test
    fun `a fire of a rung armed ahead is a sample with its latency`() {
        val result = compute(listOf(scheduled("s1", rung - 1.hours), fired("f1", rung + 30.seconds)))
        eq(listOf(FireSample("f1", "s1", rung, rung - 1.hours, rung + 30.seconds, "occ")), result.samples)
        eq(30.seconds, result.samples.single().latency)
        eq(0, result.catchUp)
        eq(0, result.neverFired)
    }

    @Test
    fun `a rung armed at or after its own instant is a catch up and never drift`() {
        val late = compute(listOf(scheduled("s1", rung + 10.minutes), fired("f1", rung + 10.minutes + 1.seconds)))
        eq(emptyList(), late.samples)
        eq("armed after the rung", 1, late.catchUp)

        val atTheInstant = compute(listOf(scheduled("s1", rung), fired("f1", rung + 1.seconds)))
        eq(emptyList(), atTheInstant.samples)
        eq("armed at the instant is not ahead of it", 1, atTheInstant.catchUp)

        val justAhead = compute(listOf(scheduled("s1", rung - 1.milliseconds), fired("f1", rung + 1.seconds)))
        eq("one millisecond ahead is ahead", 1, justAhead.samples.size)
        eq(0, justAhead.catchUp)
    }

    @Test
    fun `the exclusion asked of the caller removes a fire and is counted by reason`() {
        val events = listOf(scheduled("s1", rung - 1.hours), fired("f1", rung + 3.hours))
        val seen = mutableListOf<FireSample>()
        val excluded =
            compute(events, exclusion = { sample ->
                seen += sample
                Exclusion.BOOT_CHANGED
            })
        eq(emptyList(), excluded.samples)
        eq(mapOf(Exclusion.BOOT_CHANGED to 1), excluded.excluded)
        eq(
            "the caller is given the sample",
            listOf("f1" to "s1"),
            seen.map {
                it.fireEventId to
                    it.scheduleEventId
            },
        )

        val clock = compute(events, exclusion = { Exclusion.CLOCK_CHANGED })
        eq(mapOf(Exclusion.CLOCK_CHANGED to 1), clock.excluded)

        val kept = compute(events)
        eq("with nothing excluded the same fire is counted", 1, kept.samples.size)
        eq(emptyMap(), kept.excluded)
    }

    @Test
    fun `a catch up is not offered to the exclusion`() {
        val seen = mutableListOf<FireSample>()
        compute(listOf(scheduled("s1", rung + 1.hours), fired("f1", rung + 1.hours)), exclusion = {
            seen += it
            null
        })
        eq(emptyList(), seen)
    }

    @Test
    fun `a rung the caller cannot vouch for is unverifiable and its instant is the one armed`() {
        val events = listOf(scheduled("s1", rung - 1.hours), fired("f1", rung + 30.seconds))
        val unknown = compute(events, rungArmedBy = { null })
        eq(emptyList(), unknown.samples)
        eq(mapOf(Exclusion.UNVERIFIABLE to 1), unknown.excluded)

        // The occurrence moved after the arming (a time zone change): the armed rung is the measure.
        val moved = compute(events, rungArmedBy = { rung + 10.seconds })
        eq(20.seconds, moved.samples.single().latency)
    }

    @Test
    fun `a fire with no arming before it is not a sample`() {
        val result = compute(listOf(fired("f1", rung + 30.seconds)))
        eq(emptyList(), result.samples)
        eq(0, result.catchUp)
        eq(emptyMap(), result.excluded)
    }

    @Test
    fun `the second fire is paired with the arming written after the first and not the one before it`() {
        // The arming of rung 2 carries the same timestamp as the fire of rung 1, as a fixed clock writes them, and
        // is stored after it. Stored order is the tie break, so the first fire still pairs with the first arming.
        val events =
            listOf(
                scheduled("s1", rung - 1.hours),
                fired("f1", rung + 1.seconds),
                scheduled("s2", rung + 1.seconds),
                fired("f2", rung + 5.minutes),
            )
        val result = compute(events, rungArmedBy = { if (it == "s1") rung else rung + 5.minutes })
        eq(listOf("f1" to "s1", "f2" to "s2"), result.samples.map { it.fireEventId to it.scheduleEventId })
        eq(listOf(1.seconds, Duration.ZERO), result.samples.map { it.latency })
    }

    @Test
    fun `only events as of the instant count`() {
        val events = listOf(scheduled("s1", rung - 1.hours), fired("f1", rung + 30.seconds))
        eq(1, compute(events, asOf = rung + 30.seconds).samples.size)
        eq(
            "a fire one millisecond after asOf is not yet in",
            0,
            compute(
                events,
                asOf =
                    rung + 30.seconds - 1.milliseconds,
            ).samples.size,
        )
    }

    @Test
    fun `an open occurrence whose first rung passed with no fire is a never fired rung at the tolerance`() {
        val events = listOf(scheduled("s1", rung - 1.hours))
        eq(1, compute(events, asOf = rung + after).neverFired)
        eq(
            "one millisecond short of the tolerance",
            0,
            compute(events, asOf = rung + after - 1.milliseconds).neverFired,
        )
    }

    @Test
    fun `an occurrence completed or skipped before its first rung is not a failure`() {
        val armed = scheduled("s1", rung - 1.hours)
        eq(0, compute(listOf(armed, event(EventType.COMPLETED, rung - 1.minutes))).neverFired)
        eq(0, compute(listOf(armed, event(EventType.SKIPPED, rung - 1.minutes))).neverFired)
        eq(
            "at the rung's instant it was still done before it fired",
            0,
            compute(listOf(armed, event(EventType.COMPLETED, rung))).neverFired,
        )
        eq(
            "a completion after the rung does not excuse a rung that never fired",
            1,
            compute(listOf(armed, event(EventType.COMPLETED, rung + 1.minutes))).neverFired,
        )
    }

    @Test
    fun `a missed occurrence that never fired is a never fired rung`() {
        val missed =
            Event(
                "m",
                occurrence.id,
                EventType.MISSED,
                rung + 5.hours,
                rung + 4.hours,
                EventSource.SYSTEM,
                EventPayload.None,
            )
        eq(1, compute(listOf(scheduled("s1", rung - 1.hours), missed)).neverFired)
    }

    @Test
    fun `a rung not armed ahead of time is not held against the platform`() {
        eq("never armed", 0, compute(emptyList()).neverFired)
        eq("armed after the rung", 0, compute(listOf(scheduled("s1", rung + 1.minutes))).neverFired)
        eq("armed at the rung", 0, compute(listOf(scheduled("s1", rung))).neverFired)
    }

    @Test
    fun `any fire of the occurrence, even a late one, is not a never fired rung`() {
        val armed = scheduled("s1", rung - 1.hours)
        eq(0, compute(listOf(armed, fired("f1", rung + 3.hours))).neverFired)
        eq(
            "a fire after asOf has not happened yet",
            1,
            compute(listOf(armed, fired("f1", rung + 3.hours)), asOf = rung + 2.hours).neverFired,
        )
    }

    @Test
    fun `a never fired rung the caller excludes is counted apart, and is asked with the rung and its grace end`() {
        val events = listOf(scheduled("s1", rung - 1.hours))
        val seen = mutableListOf<UnfiredRung>()
        val result =
            compute(events, unfiredExclusion = {
                seen += it
                Exclusion.OFF_THROUGH_GRACE
            })
        eq(0, result.neverFired)
        eq(mapOf(Exclusion.OFF_THROUGH_GRACE to 1), result.neverFiredExcluded)
        eq(emptyList<UnfiredRung>(), result.neverFiredRungs)
        // A STANDARD occurrence's grace ends four hours after it is due (ARCHITECTURE.md section 4.5).
        eq(
            "it is asked about the first rung's arming, the rung and the end of grace",
            listOf(
                UnfiredRung(
                    "occ",
                    "s1",
                    rung,
                    rung + 4.hours,
                ),
            ),
            seen,
        )
    }

    @Test
    fun `a counted never fired rung is listed with its occurrence, its rung and its grace end`() {
        val result = compute(listOf(scheduled("s1", rung - 1.hours)))
        eq(listOf(UnfiredRung("occ", "s1", rung, rung + 4.hours)), result.neverFiredRungs)
        eq(1, result.neverFired)
    }

    @Test
    fun `an occurrence with no rung on a delivered channel has nothing to fail`() {
        val result = compute(listOf(scheduled("s1", rung - 1.hours)), channelsArg = emptySet())
        eq(0, result.neverFired)
    }

    @Test
    fun `events that belong to no occurrence are ignored`() {
        val canary =
            event(EventType.CANARY_RESULT, rung, occurrenceId = null, payload = EventPayload.Canary(rung, rung))
        val result = compute(listOf(canary, scheduled("s1", rung - 1.hours), fired("f1", rung + 1.seconds)))
        eq(1, result.samples.size)
    }

    @Test
    fun `occurrences are measured separately`() {
        val other = occurrence.copy(id = "other", scheduledInstant = rung + 1.hours)
        val events =
            listOf(
                scheduled("s1", rung - 1.hours),
                fired("f1", rung + 10.seconds),
                event(EventType.ALARM_SCHEDULED, rung, "s2", "other"),
                event(EventType.ALARM_FIRED, rung + 1.hours + 20.seconds, "f2", "other"),
            )
        val result =
            compute(events, occurrences = listOf(occurrence, other), rungArmedBy = {
                if (it ==
                    "s1"
                ) {
                    rung
                } else {
                    rung + 1.hours
                }
            })
        eq(listOf("f1", "f2"), result.samples.map { it.fireEventId })
        eq(listOf(10.seconds, 20.seconds), result.samples.map { it.latency })
    }
}

private fun <T> eq(
    expected: T,
    actual: T,
) = assertEquals(expected, actual)

private fun <T> eq(
    message: String,
    expected: T,
    actual: T,
) = assertEquals(expected, actual, message)
