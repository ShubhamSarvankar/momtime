package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EventLogReductionTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private val base = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val farFuture = base + 3650.days
    private var seq = 0

    private fun occ(
        id: String = "occ-${seq++}",
        state: OccurrenceState = OccurrenceState.PENDING,
        scheduled: Instant = base,
    ) = Occurrence(
        id = id,
        templateId = "tmpl",
        localDate = scheduled.toLocalDateTime(zone).date,
        scheduledInstant = scheduled,
        timeZoneId = zone,
        state = state,
        alarmSlot = 1,
    )

    private fun event(
        occurrence: Occurrence,
        type: EventType,
        at: Instant,
    ) = Event(
        id = "evt-${seq++}",
        occurrenceId = occurrence.id,
        eventType = type,
        deviceTimestamp = at,
        effectiveAt = null,
        source = EventSource.USER,
        payload = EventPayload.None,
    )

    private fun missed(
        occurrence: Occurrence,
        criticality: Criticality,
        reconciledAt: Instant,
    ): Event =
        checkNotNull(
            Reconcile.evaluate(occurrence.copy(state = OccurrenceState.PENDING), criticality, reconciledAt, hasTerminalEvent = false, generateId = { "evt-${seq++}" }),
        ) { "expected MISSED" }

    private fun figures(
        occurrences: List<Occurrence>,
        events: List<Event>,
        asOf: Instant = farFuture,
    ) = EventLogReduction.adherenceFigures(occurrences, events, asOf)

    private fun criticalDays(
        byDate: Map<LocalDate, List<Occurrence>>,
        events: List<Event>,
        window: List<LocalDate>,
        criticality: Criticality = Criticality.CRITICAL,
        asOf: Instant = farFuture,
    ) = EventLogReduction.criticalCompletionDays(byDate, events, { criticality }, window, asOf)

    @Test
    fun `adherence figures report three separate counts, never one collapsed percentage`() {
        val a = occ()
        val b = occ()
        val c = occ()
        val d = occ()
        val e = occ()
        val events =
            listOf(
                event(a, EventType.COMPLETED, base),
                event(b, EventType.COMPLETED, base),
                missed(c, Criticality.CRITICAL, base + 3.hours),
                event(d, EventType.SKIPPED, base),
            )
        assertEquals(
            EventLogReduction.AdherenceFigures(completed = 2, missed = 1, skipped = 1),
            figures(listOf(a, b, c, d, e), events),
        )
    }

    // An occurrence with no counted terminal event is in none of the three figures, exactly as a
    // PENDING or SNOOZED occurrence always was. Non-terminal events and events with no occurrence
    // do not count.
    @Test
    fun `occurrences with no counted terminal event are excluded from all three figures`() {
        val snoozed = occ()
        val pending = occ()
        val done = occ()
        val events =
            listOf(
                event(snoozed, EventType.SNOOZED, base),
                event(snoozed, EventType.ALARM_FIRED, base),
                event(done, EventType.COMPLETED, base),
                Event("evt-x", null, EventType.COMPLETED, base, null, EventSource.USER, EventPayload.None),
            )
        assertEquals(
            EventLogReduction.AdherenceFigures(completed = 1, missed = 0, skipped = 0),
            figures(listOf(snoozed, pending, done), events),
        )
        assertEquals(EventLogReduction.AdherenceFigures(0, 0, 0), figures(listOf(snoozed, pending), events))
    }

    @Test
    fun `figures only count the occurrences passed in`() {
        val mine = occ()
        val other = occ()
        val events = listOf(event(mine, EventType.COMPLETED, base), event(other, EventType.COMPLETED, base))
        assertEquals(EventLogReduction.AdherenceFigures(1, 0, 0), figures(listOf(mine), events))
    }

    @Test
    fun `when effect times tie, completion beats skip beats miss regardless of list order`() {
        val o = occ()
        val t = base + 5.hours
        val done = event(o, EventType.COMPLETED, t)
        val skip = event(o, EventType.SKIPPED, t)
        val miss = event(o, EventType.MISSED, t)
        assertEquals(EventLogReduction.AdherenceFigures(1, 0, 0), figures(listOf(o), listOf(miss, skip, done)))
        assertEquals(EventLogReduction.AdherenceFigures(1, 0, 0), figures(listOf(o), listOf(done, skip, miss)))
        assertEquals(EventLogReduction.AdherenceFigures(0, 0, 1), figures(listOf(o), listOf(miss, skip)))
        assertEquals(EventLogReduction.AdherenceFigures(0, 0, 1), figures(listOf(o), listOf(skip, miss)))
    }

    // ---- backfill (ADR 0040) -------------------------------------------------------------------

    @Test
    fun `a backfilled occurrence counts once, as completed, never as missed`() {
        val o = occ(state = OccurrenceState.MISSED)
        val miss = missed(o, Criticality.CRITICAL, base + 2.hours)
        val backfill = event(o, EventType.COMPLETED_BACKFILLED, base + 10.hours)
        assertEquals(EventLogReduction.AdherenceFigures(1, 0, 0), figures(listOf(o), listOf(miss, backfill)))
        assertEquals(EventLogReduction.AdherenceFigures(1, 0, 0), figures(listOf(o), listOf(backfill, miss)))
    }

    // A backfill takes effect when it is entered: before it, the occurrence is missed.
    @Test
    fun `a backfill takes effect when entered, so as of an earlier instant the occurrence is missed`() {
        val o = occ(state = OccurrenceState.MISSED)
        val miss = missed(o, Criticality.CRITICAL, base + 2.hours)
        val backfill = event(o, EventType.COMPLETED_BACKFILLED, base + 10.hours)
        val events = listOf(miss, backfill)
        assertEquals(EventLogReduction.AdherenceFigures(0, 1, 0), figures(listOf(o), events, base + 10.hours - 1.milliseconds))
        assertEquals(EventLogReduction.AdherenceFigures(1, 0, 0), figures(listOf(o), events, base + 10.hours))
    }

    // Attribution: the day the dose was due owns the outcome, not the day it was backfilled.
    @Test
    fun `a backfill is attributed to the scheduled date, not the backfill date`() {
        val scheduledDay = base.toLocalDateTime(zone).date
        val backfillDay = (base + 3.days).toLocalDateTime(zone).date
        val o = occ(state = OccurrenceState.MISSED)
        val events =
            listOf(
                missed(o, Criticality.CRITICAL, base + 2.hours),
                event(o, EventType.COMPLETED_BACKFILLED, base + 3.days),
            )
        val byDate = mapOf(o.localDate to listOf(o))
        assertEquals(1, criticalDays(byDate, events, listOf(scheduledDay, backfillDay)))
        assertEquals(0, criticalDays(byDate, events, listOf(backfillDay)))
    }

    @Test
    fun `a backfilled critical occurrence makes its day qualify, a missed one does not`() {
        val date = base.toLocalDateTime(zone).date
        val fine = occ()
        val late = occ(state = OccurrenceState.MISSED)
        val byDate = mapOf(date to listOf(fine, late))
        val common = listOf(event(fine, EventType.COMPLETED, base), missed(late, Criticality.CRITICAL, base + 2.hours))
        val backfilled = common + event(late, EventType.COMPLETED_BACKFILLED, base + 5.hours)
        assertEquals(0, criticalDays(byDate, common, listOf(date)))
        assertEquals(1, criticalDays(byDate, backfilled, listOf(date)))
        // asOf before the backfill: still not qualifying.
        assertEquals(0, criticalDays(byDate, backfilled, listOf(date), asOf = base + 5.hours - 1.milliseconds))
    }

    // ---- critical completion days --------------------------------------------------------------

    @Test
    fun `a day with no critical occurrences does not count toward the 30-day metric`() {
        val date = base.toLocalDateTime(zone).date
        val o = occ()
        val events = listOf(event(o, EventType.COMPLETED, base))
        assertEquals(0, criticalDays(mapOf(date to listOf(o)), events, listOf(date), criticality = Criticality.STANDARD))
    }

    @Test
    fun `a day counts only when every critical occurrence that day is completed`() {
        val date = base.toLocalDateTime(zone).date
        val a = occ()
        val b = occ()
        val byDate = mapOf(date to listOf(a, b))
        val both = listOf(event(a, EventType.COMPLETED, base), event(b, EventType.COMPLETED, base))
        val oneMissed = listOf(event(a, EventType.COMPLETED, base), missed(b, Criticality.CRITICAL, base + 3.hours))
        val oneOpen = listOf(event(a, EventType.COMPLETED, base))
        assertEquals(1, criticalDays(byDate, both, listOf(date)))
        assertEquals(0, criticalDays(byDate, oneMissed, listOf(date)))
        assertEquals(0, criticalDays(byDate, oneOpen, listOf(date)))
    }

    @Test
    fun `a date with no occurrences does not count toward the 30-day metric`() {
        val withData = LocalDate(2026, 1, 2)
        val absent = LocalDate(2026, 1, 3)
        val o = occ()
        val events = listOf(event(o, EventType.COMPLETED, base))
        assertEquals(1, criticalDays(mapOf(withData to listOf(o)), events, listOf(withData, absent)))
        assertEquals(0, criticalDays(emptyMap(), emptyList(), listOf(absent)))
    }

    // ---- asOf boundary -------------------------------------------------------------------------

    @Test
    fun `an event counts at effect time == asOf and not one millisecond earlier - user event`() {
        val o = occ()
        val events = listOf(event(o, EventType.COMPLETED, base + 1.hours))
        assertEquals(1, figures(listOf(o), events, base + 1.hours).completed)
        assertEquals(0, figures(listOf(o), events, base + 1.hours - 1.milliseconds).completed)
    }

    @Test
    fun `an event counts at effect time == asOf and not one millisecond earlier - derived MISSED reads effectiveAt`() {
        val o = occ()
        val expiry = base + 2.hours
        // Written a day after it became true; effect time is the expiry, not the write time.
        val miss = missed(o, Criticality.CRITICAL, expiry + 1.days)
        assertEquals(1, figures(listOf(o), listOf(miss), expiry).missed)
        assertEquals(0, figures(listOf(o), listOf(miss), expiry - 1.milliseconds).missed)
    }

    // GENTLE expiry is the start of the next local day; a daily figure for day D with asOf at the
    // end of D includes it (inclusive bound).
    @Test
    fun `a GENTLE miss is included by an asOf at the end of its scheduled day`() {
        val o = occ()
        val miss = missed(o, Criticality.GENTLE, base + 3.days)
        val endOfDay = checkNotNull(miss.effectiveAt)
        assertEquals(o.localDate.toEpochDays() + 1, endOfDay.toLocalDateTime(zone).date.toEpochDays())
        assertEquals(1, figures(listOf(o), listOf(miss), endOfDay).missed)
    }

    // ---- generated histories -------------------------------------------------------------------

    private enum class Fate { OPEN, SNOOZED, COMPLETED, SKIPPED, MISSED }

    private class History(
        val occurrences: List<Occurrence>,
        val events: List<Event>,
        val criticality: Map<String, Criticality>,
    ) {
        fun latestEffectTime(): Instant = events.maxOfOrNull { it.effectiveAt ?: it.deviceTimestamp } ?: Instant.fromEpochMilliseconds(0)
    }

    private fun generate(random: Random): History {
        val occurrences = mutableListOf<Occurrence>()
        val events = mutableListOf<Event>()
        val criticality = HashMap<String, Criticality>()
        repeat(random.nextInt(0, 25)) {
            val scheduled = base + random.nextLong(0, 12L * 24 * 60).minutes
            val level = Criticality.entries[random.nextInt(Criticality.entries.size)]
            val fate = Fate.entries[random.nextInt(Fate.entries.size)]
            val state =
                when (fate) {
                    Fate.OPEN -> OccurrenceState.PENDING
                    Fate.SNOOZED -> OccurrenceState.SNOOZED
                    Fate.COMPLETED -> OccurrenceState.COMPLETED
                    Fate.SKIPPED -> OccurrenceState.SKIPPED
                    Fate.MISSED -> OccurrenceState.MISSED
                }
            val o = occ(id = "g-${seq++}", state = state, scheduled = scheduled)
            occurrences += o
            criticality[o.id] = level
            val at = scheduled + random.nextLong(0, 90).minutes
            when (fate) {
                Fate.OPEN -> Unit
                Fate.SNOOZED -> events += event(o, EventType.SNOOZED, at)
                Fate.COMPLETED -> events += event(o, EventType.COMPLETED, at)
                Fate.SKIPPED -> events += event(o, EventType.SKIPPED, at)
                Fate.MISSED -> {
                    val expiry = checkNotNull(missed(o, level, scheduled + 3650.days).effectiveAt)
                    events += missed(o, level, expiry + random.nextLong(0, 3L * 24 * 60).minutes)
                }
            }
        }
        return History(occurrences, events, criticality)
    }

    // Oracle property (ADR 0040): with no backfill and asOf at or after every effect time, the
    // log-based figures equal the state-based reference exactly. The rewrite changes no figure
    // except where backfill is involved.
    @Test
    fun `log-based figures equal the state-based oracle for histories without backfill - property test`() {
        val random = Random(1311)
        repeat(500) { run ->
            val h = generate(random)
            val asOf = h.latestEffectTime() + random.nextLong(0, 5L * 24 * 60).minutes
            assertEquals(
                StateBasedAdherenceOracle.adherenceFigures(h.occurrences),
                figures(h.occurrences, h.events, asOf),
                "adherence figures diverged from the oracle at run=$run",
            )
            val byDate = h.occurrences.groupBy { it.localDate }
            val window = byDate.keys.toList() + LocalDate(2030, 1, 1)
            val criticalityOf = { o: Occurrence -> checkNotNull(h.criticality[o.id]) }
            assertEquals(
                StateBasedAdherenceOracle.criticalCompletionDays(byDate, criticalityOf, window),
                EventLogReduction.criticalCompletionDays(byDate, h.events, criticalityOf, window, asOf),
                "critical completion days diverged from the oracle at run=$run",
            )
        }
    }

    // Golden scenario 5, second case: the same history reconciled at the exact expiry, an hour
    // later and a day later produces identical figures as of a fixed asOf. asOf is placed so that
    // some reconcile delays cross it (write time after asOf, effect time before), which is the only
    // way the property can tell effectiveAt from deviceTimestamp.
    @Test
    fun `scenario 5 - figures as of a fixed instant are identical however late Reconcile ran - property test`() {
        val random = Random(5)
        var crossing = 0
        repeat(300) { run ->
            val scheduled = base + random.nextLong(0, 30L * 24 * 60).minutes
            val level = Criticality.entries[random.nextInt(Criticality.entries.size)]
            val o = occ(id = "s5-$run", state = OccurrenceState.MISSED, scheduled = scheduled)
            val expiry = checkNotNull(missed(o, level, scheduled + 3650.days).effectiveAt)
            val asOf = expiry + random.nextLong(0, 60).minutes
            val delays = listOf(0.minutes, 1.hours, 1.days)
            val results =
                delays.map { delay ->
                    val event = missed(o, level, expiry + delay)
                    if (event.deviceTimestamp > asOf) crossing++
                    figures(listOf(o), listOf(event), asOf)
                }
            assertEquals(EventLogReduction.AdherenceFigures(0, 1, 0), results[0], "run=$run at expiry")
            results.forEachIndexed { i, r -> assertEquals(results[0], r, "run=$run delay=${delays[i]}") }
        }
        check(crossing > 0) { "generator never crossed asOf; property cannot tell the two timestamps apart" }
    }
}
