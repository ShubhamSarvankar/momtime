package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * How closely real alarms fired to the rung they were armed for, as a reduction over the log (ADR 0070, ADR 0003).
 * Real fires are the measurement: they take exactly the path her reminders take (ADR 0069). Nothing here is stored,
 * nothing reads a clock, and everything is relative to an explicit [asOf], as the adherence reductions are (ADR 0040).
 *
 * **A sample** is one `ALARM_FIRED` event paired with the `ALARM_SCHEDULED` event that armed its rung: the last one
 * written for the occurrence before it. Its latency is when the fire was recorded minus the rung's instant. A sample
 * is counted only if the rung was armed ahead of time, that is strictly before the rung's instant. A rung armed at or
 * after its own instant (an overdue rung armed for now: a catch up after boot, the watchdog, or a clock change) is a
 * catch up, which says nothing about how the platform delivers an alarm, so it is counted apart and never as drift.
 *
 * **Exclusions are an input.** Whether the phone restarted or its clock was set between arming and the fire is a fact
 * the platform holds and the shared log does not, so the caller says it through [exclusion]; the reduction counts what
 * it is told to exclude, by reason, and leaves it out of the samples. A rung whose arming the caller cannot vouch for
 * is [Exclusion.UNVERIFIABLE]. The rung's instant is also an input ([rungArmedBy]): it is what was armed, kept when it
 * was armed, so a later change to the occurrence's instant (a time zone change moves it, ADR 0068) cannot rewrite it.
 *
 * **A never fired rung** is an occurrence's first device rung that was armed ahead of time, whose instant plus
 * [neverFiredAfter] had passed by [asOf], with no `ALARM_FIRED` for the occurrence at or before [asOf]. An occurrence
 * that she completed or skipped before its first rung is not a failure and is not counted: nothing was left to ring
 * for. A completion after the rung does not excuse it. The first rung only, because it is the one that depends on
 * nothing she did. The caller may excuse a rung through [unfiredExclusion], which is given the rung and the instant its
 * occurrence's grace ends: the one excuse is a phone that was off past the end of grace (ADR 0070). A phone that was
 * running at any point within grace after the rung gets its rungs armed for now and delivered late, so a rung that
 * still never fired is a real failure, and a restart that left the phone running through grace excuses nothing.
 *
 * Pure: no clock (invariant 8), no scheduling (invariant 6), nothing platform specific (invariant 5).
 */
object FireTiming {
    /**
     * Why a fire, or an unfired rung, was left out of the figures. [BOOT_CHANGED] and [CLOCK_CHANGED] are reasons a
     * fire's latency means nothing; [OFF_THROUGH_GRACE] is the one reason a rung that never fired is excused.
     */
    enum class Exclusion { BOOT_CHANGED, CLOCK_CHANGED, OFF_THROUGH_GRACE, UNVERIFIABLE }

    /** One fire and the arming that preceded it. [fireEventId] and [scheduleEventId] are in-process keys only. */
    data class FireSample(
        val fireEventId: String,
        val scheduleEventId: String,
        val rung: Instant,
        val armedAt: Instant,
        val firedAt: Instant,
        val occurrenceId: String,
    ) {
        val latency: Duration get() = firedAt - rung
    }

    /** A first rung that was armed ahead of time and never fired, with the instant its occurrence's grace ends. */
    data class UnfiredRung(
        val occurrenceId: String,
        val scheduleEventId: String,
        val rung: Instant,
        val graceEnd: Instant,
    )

    data class Timings(
        /** The fires counted, in the order they were recorded per occurrence. */
        val samples: List<FireSample>,
        /** Fires of a rung that was armed at or after its own instant. */
        val catchUp: Int,
        /** Fires left out, by reason. */
        val excluded: Map<Exclusion, Int>,
        /** First rungs that were armed ahead of time and never fired. */
        val neverFiredRungs: List<UnfiredRung>,
        /** First rungs that never fired but could not be held against the platform, by reason. */
        val neverFiredExcluded: Map<Exclusion, Int>,
    ) {
        val neverFired: Int get() = neverFiredRungs.size
    }

    @Suppress("LongParameterList")
    fun compute(
        occurrences: List<Occurrence>,
        events: List<Event>,
        channels: Set<Channel>,
        asOf: Instant,
        neverFiredAfter: Duration,
        rungArmedBy: (scheduleEventId: String) -> Instant?,
        exclusion: (FireSample) -> Exclusion?,
        unfiredExclusion: (UnfiredRung) -> Exclusion?,
    ): Timings {
        val byOccurrence = events.filter { it.occurrenceId != null }.groupBy { it.occurrenceId }
        val samples = mutableListOf<FireSample>()
        var catchUp = 0
        val excluded = mutableMapOf<Exclusion, Int>()
        val neverFiredRungs = mutableListOf<UnfiredRung>()
        val neverFiredExcluded = mutableMapOf<Exclusion, Int>()

        for (occurrence in occurrences) {
            // A stable sort: events with the same timestamp keep the order they were stored in.
            val log = byOccurrence[occurrence.id].orEmpty().sortedBy { it.deviceTimestamp }
            var lastSchedule: Event? = null
            var fired = 0
            for (event in log) {
                if (event.deviceTimestamp > asOf) break
                when (event.eventType) {
                    EventType.ALARM_SCHEDULED -> lastSchedule = event
                    EventType.ALARM_FIRED -> {
                        fired++
                        val schedule = lastSchedule ?: continue
                        val rung = rungArmedBy(schedule.id)
                        if (rung == null) {
                            bump(excluded, Exclusion.UNVERIFIABLE)
                            continue
                        }
                        val sample =
                            FireSample(
                                event.id,
                                schedule.id,
                                rung,
                                schedule.deviceTimestamp,
                                event.deviceTimestamp,
                                occurrence.id,
                            )
                        when {
                            sample.armedAt >= rung -> catchUp++
                            else -> {
                                val reason = exclusion(sample)
                                if (reason == null) samples += sample else bump(excluded, reason)
                            }
                        }
                    }
                    else -> Unit
                }
            }
            if (fired == 0) {
                // The occurrence's own criticality decides its first rung and its grace (ADR 0079 item 6).
                val criticality = occurrence.criticality
                val first = firstRung(occurrence, criticality, channels) ?: continue
                if (first + neverFiredAfter > asOf) continue
                if (log.any { it.deviceTimestamp <= first && it.eventType in USER_CLOSES_BEFORE_RUNG }) continue
                val armedAhead =
                    log.lastOrNull { it.eventType == EventType.ALARM_SCHEDULED && it.deviceTimestamp < first }
                if (armedAhead == null) continue
                val unfired =
                    UnfiredRung(
                        occurrence.id,
                        armedAhead.id,
                        first,
                        Reconcile.graceExpiryInstant(occurrence, criticality),
                    )
                val reason = unfiredExclusion(unfired)
                if (reason == null) neverFiredRungs += unfired else bump(neverFiredExcluded, reason)
            }
        }
        return Timings(samples, catchUp, excluded, neverFiredRungs, neverFiredExcluded)
    }

    private fun firstRung(
        occurrence: Occurrence,
        criticality: Criticality,
        channels: Set<Channel>,
    ): Instant? =
        EscalationLadder
            .forOccurrence(occurrence.scheduledInstant, criticality)
            .firstOrNull { it.channel in channels }
            ?.instant

    private fun bump(
        counts: MutableMap<Exclusion, Int>,
        reason: Exclusion,
    ) {
        counts[reason] = (counts[reason] ?: 0) + 1
    }

    private val USER_CLOSES_BEFORE_RUNG = setOf(EventType.COMPLETED, EventType.SKIPPED)
}
