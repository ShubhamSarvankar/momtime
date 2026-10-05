package com.momtime.android.reliability

import com.momtime.android.arming.DEVICE_CHANNELS
import com.momtime.android.capability.CapabilityInputs
import com.momtime.android.di.BootCount
import com.momtime.android.di.CorruptionMarker
import com.momtime.android.store.ArmingContextRepository
import com.momtime.android.store.BootInstant
import com.momtime.android.store.BootInstantRepository
import com.momtime.android.store.CheckOutcome
import com.momtime.android.store.ClockChangeRepository
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.android.store.ReliabilityCheck
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventType
import com.momtime.shared.engine.FireTiming
import com.momtime.shared.engine.FireTiming.Exclusion
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Fire timing for one delivery tier over the window. */
data class TierDrift(
    val tier: DeliveryCapability,
    val fires: Int,
    val median: Duration,
    val slowest: Duration,
)

/**
 * One counted fire: how late, under which tier, and what the device was doing. No identifier of any kind. When it was
 * is the local date (as days since the epoch) and the local hour of the day of the fire and of its rung, in the zone of
 * its occurrence, and never a minute or a second: the hour is what makes a latency diagnostic (Doze behaves differently
 * overnight), and the minute is the time she takes her medicine (ADR 0070).
 */
data class FireRow(
    val firedAt: Instant,
    val tier: DeliveryCapability,
    val latency: Duration,
    val telemetry: FireTelemetry,
    val localDay: Long,
    val fireHour: Int,
    val rungHour: Int,
)

/** A first rung that never fired, as the local date and the local hour of day it was due. */
data class UnfiredRow(
    val localDay: Long,
    val hour: Int,
)

/**
 * What the reliability view and the export are made from (ADR 0070): the fire timing reduction of `shared` joined with
 * what android kept, the counts of the log, the store's own health and the check's history. Everything is as of
 * [asOf]. Days of the window with no reminder fire are **not observed**: real fires are the only passive evidence
 * (ADR 0069), so [daysWithFires] says how much of the window the figures stand on.
 */
data class ReliabilityReport(
    val asOf: Instant,
    val windowDays: Int,
    val daysWithFires: Int,
    val fires: List<FireRow>,
    val drift: List<TierDrift>,
    val catchUp: Int,
    val excluded: Map<Exclusion, Int>,
    val neverFired: Int,
    val neverFiredRungs: List<UnfiredRow>,
    val neverFiredExcluded: Map<Exclusion, Int>,
    val clockChanges: Long,
    val missedOccurrences: Int,
    val watchdogRepairs: Int,
    val mutedFires: Int,
    val storeFailures: Map<String, Long>,
    val corruption: CorruptionMarker?,
    val checks: List<ReliabilityCheck>,
    val capability: CapabilityInputs,
    /**
     * Boots in the window that the app did not run in, from gaps in the boot counts it recorded (ADR 0071). A count and
     * no instant: it is direct evidence that the app was asleep through a restart.
     */
    val unseenBoots: Int,
    /** True if exempt from Android's unused app restrictions, false if they apply, null below API 30 (ADR 0072). */
    val unusedAppExempt: Boolean?,
    val samsung: Boolean,
) {
    val lastSettledCheck: ReliabilityCheck? get() = checks.firstOrNull { it.outcome != CheckOutcome.PENDING }

    val banners: List<Banner>
        get() =
            Banners.compute(
                BannerInputs(
                    exactTierLatencies = fires.filter { Banners.isExactTier(it.tier) }.map { it.latency },
                    neverFired = neverFired,
                    lastCheck = lastSettledCheck?.outcome,
                    capability = capability,
                    mutedFires = mutedFires,
                    storeFailures = storeFailures.values.sum(),
                    corruption = corruption != null,
                    unseenBoots = unseenBoots,
                    samsung = samsung,
                    unusedAppExempt = unusedAppExempt,
                ),
            )
}

/**
 * Reads the report: the `shared` reduction over the events and occurrences of the window, with the exclusions it asks
 * for answered from what android kept (ADR 0070). A fire is left out of drift when the phone restarted or its clock
 * was set between arming and the fire, and when android cannot vouch for either, and it is counted as left out and
 * never silently dropped.
 */
@Suppress("LongParameterList")
class ReliabilityReader internal constructor(
    private val occurrences: OccurrenceRepository,
    private val templates: ScheduleTemplateRepository,
    private val events: EventRepository,
    private val telemetry: FireTelemetryRepository,
    private val contexts: ArmingContextRepository,
    private val clockChanges: ClockChangeRepository,
    private val boots: BootInstantRepository,
    private val bootCount: BootCount,
    private val unusedAppExempt: () -> Boolean?,
    private val samsung: () -> Boolean,
    private val checks: () -> List<ReliabilityCheck>,
    private val failureCounts: () -> Map<String, Long>,
    private val corruption: () -> CorruptionMarker?,
    private val capability: () -> CapabilityInputs,
) {
    @Suppress("LongMethod")
    fun read(asOf: Instant): ReliabilityReport {
        val from = asOf - WINDOW
        val inWindow = occurrences.findInWindow(from, asOf + LOOKAHEAD)
        val log = inWindow.flatMap { events.findForOccurrence(it.id) }
        val criticality = templates.findAll().associate { it.id to it.criticality }
        val bootInstants = boots.all()
        val zoneOf = inWindow.associate { it.id to it.timeZoneId }

        val timings =
            FireTiming.compute(
                occurrences = inWindow,
                events = log,
                criticalityOf = { checkNotNull(criticality[it.templateId]) { "occurrence without a template" } },
                channels = DEVICE_CHANNELS,
                asOf = asOf,
                neverFiredAfter = NEVER_FIRED_AFTER,
                rungArmedBy = { contexts.find(it)?.rungInstant },
                exclusion = { sample -> excludeFire(sample) },
                unfiredExclusion = { unfired -> excludeUnfired(unfired, bootInstants, bootCount.read()) },
            )

        val rows =
            timings.samples.mapNotNull { sample ->
                val zone = zoneOf.getValue(sample.occurrenceId)
                telemetry.findForEvent(sample.fireEventId)?.let {
                    val fired = sample.firedAt.toLocalDateTime(zone)
                    FireRow(
                        sample.firedAt,
                        it.resolvedTier,
                        sample.latency,
                        it,
                        fired.date.toEpochDays(),
                        fired.hour,
                        sample.rung.toLocalDateTime(zone).hour,
                    )
                }
            }
        val unfired =
            timings.neverFiredRungs.map {
                val due = it.rung.toLocalDateTime(zoneOf.getValue(it.occurrenceId))
                UnfiredRow(due.date.toEpochDays(), due.hour)
            }
        val firesInWindow = log.filter { it.eventType == EventType.ALARM_FIRED && it.deviceTimestamp in from..asOf }
        val muted = firesInWindow.count { telemetry.findForEvent(it.id)?.alarmStreamMuted == true }
        return ReliabilityReport(
            asOf = asOf,
            windowDays = WINDOW_DAYS,
            daysWithFires = localDaysWithFires(firesInWindow, zoneOf),
            fires = rows,
            drift = driftByTier(rows),
            catchUp = timings.catchUp,
            excluded = timings.excluded,
            neverFired = timings.neverFired,
            neverFiredRungs = unfired,
            neverFiredExcluded = timings.neverFiredExcluded,
            clockChanges = clockChanges.count() ?: 0L,
            missedOccurrences = log.count { it.eventType == EventType.MISSED && it.inWindow(from, asOf) },
            watchdogRepairs = log.count { it.eventType == EventType.WATCHDOG_REPAIR && it.inWindow(from, asOf) },
            mutedFires = muted,
            storeFailures = failureCounts(),
            corruption = corruption(),
            checks = checks(),
            capability = capability(),
            unseenBoots = BootGaps.unseenSince(bootInstants, from),
            unusedAppExempt = unusedAppExempt(),
            samsung = samsung(),
        )
    }

    private fun Event.inWindow(
        from: Instant,
        asOf: Instant,
    ) = (effectiveAt ?: deviceTimestamp) in from..asOf

    /** A fire is vouched for only if arming and fire agree on the boot count and the clock change count. */
    private fun excludeFire(sample: FireTiming.FireSample): Exclusion? {
        val context = contexts.find(sample.scheduleEventId)
        val fire = telemetry.findForEvent(sample.fireEventId)
        val armedClock = context?.clockChanges
        val firedClock = fire?.clockChanges
        val armedBoot = context?.bootCount
        val firedBoot = fire?.bootCount
        return when {
            armedClock == null || firedClock == null || armedBoot == null || firedBoot == null -> Exclusion.UNVERIFIABLE
            armedClock < 0 -> Exclusion.UNVERIFIABLE
            armedBoot != firedBoot -> Exclusion.BOOT_CHANGED
            armedClock != firedClock -> Exclusion.CLOCK_CHANGED
            else -> null
        }
    }

    /**
     * A rung that never fired is excused only if the phone stayed off past the end of its occurrence's grace: the
     * first boot after the rung's instant began after that grace ended (ADR 0070). A phone that was running at any
     * point within grace after the rung has its overdue rungs armed for now and delivered late, so a rung that still
     * never fired is a real failure, however many times it restarted; and a clock change excuses nothing (a backward
     * jump puts the rung back in the future, and a forward jump arms it for now, so it fires). A boot the app never
     * saw is not known, and a rung is then not excused: not across a gap in the recorded boot counts, and not when the
     * boot count is unknown (ADR 0071).
     */
    private fun excludeUnfired(
        unfired: FireTiming.UnfiredRung,
        bootInstants: List<BootInstant>,
        bootNow: Long,
    ): Exclusion? {
        val firstAfter = bootInstants.firstOrNull { it.bootedAt > unfired.rung }
        // Never excused when the boot count is unknown, and never across a gap in the boot counts between the rung and
        // the first boot recorded after it: the app did not run in at least one boot, so the phone may have been
        // running through grace (ADR 0071).
        val excused =
            bootNow >= 0 &&
                firstAfter != null &&
                BootGaps.unseenBefore(bootInstants, firstAfter) == 0 &&
                firstAfter.bootedAt > unfired.graceEnd
        return if (excused) Exclusion.OFF_THROUGH_GRACE else null
    }

    private fun localDaysWithFires(
        fires: List<Event>,
        zoneOf: Map<String, TimeZone>,
    ): Int =
        fires
            .mapNotNull { fire -> zoneOf[fire.occurrenceId]?.let { fire.deviceTimestamp.toLocalDateTime(it).date } }
            .toSet()
            .size

    private fun driftByTier(rows: List<FireRow>): List<TierDrift> =
        DeliveryCapability.entries.mapNotNull { tier ->
            val latencies = rows.filter { it.tier == tier }.map { it.latency }.sorted()
            if (latencies.isEmpty()) {
                null
            } else {
                TierDrift(
                    tier,
                    latencies.size,
                    Banners.median(latencies),
                    latencies.last(),
                )
            }
        }

    companion object {
        /** The window every figure stands on: the last seven days. */
        const val WINDOW_DAYS = 7
        val WINDOW: Duration = WINDOW_DAYS.days

        /** Occurrences scheduled a little ahead are read too: their arming is as of now. */
        private val LOOKAHEAD: Duration = 1.days

        /**
         * How long after its instant a first rung must have gone unfired to count as never fired: the inexact
         * tolerance (`ARCHITECTURE.md` section 5.3), so a Tier 1 alarm running late in Doze is not held against it.
         */
        val NEVER_FIRED_AFTER: Duration = 15.minutes
    }
}
