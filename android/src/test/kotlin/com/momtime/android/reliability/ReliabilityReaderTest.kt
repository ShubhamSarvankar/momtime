package com.momtime.android.reliability

import android.content.Context
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.t0
import com.momtime.android.di.BootCount
import com.momtime.android.di.CorruptionMarker
import com.momtime.android.store.ArmingContext
import com.momtime.android.store.ArmingContextRepository
import com.momtime.android.store.BootInstant
import com.momtime.android.store.BootInstantRepository
import com.momtime.android.store.ClockChangeRepository
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.engine.FireTiming.Exclusion
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The join of the `shared` fire timing reduction with what android kept (ADR 0070), through the real fire path and
 * the real arming: a fire is counted as drift only if its rung was armed ahead of time and the phone neither restarted
 * nor had its clock set between arming and the fire, and everything else is counted apart, by reason, and never
 * silently dropped. A rung that never fired is excused only if the phone stayed off past the end of its grace, and a
 * clock change excuses nothing (ADR 0070).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class ReliabilityReaderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val reader: ReliabilityReader get() = fixture.graph.get()
    private val telemetry: FireTelemetryRepository get() = fixture.graph.get()
    private val clockChanges: ClockChangeRepository get() = fixture.graph.get()
    private val contexts: ArmingContextRepository get() = fixture.graph.get()
    private val boots: BootInstantRepository get() = fixture.graph.get()

    @After
    fun tearDown() = fixture.close()

    private fun telemetryRow(
        eventId: String,
        boot: Long = fixture.bootCount,
        clock: Long? = 0,
        tier: DeliveryCapability = DeliveryCapability.TIER_3,
        muted: Boolean? = false,
    ) = FireTelemetry(
        eventId = eventId,
        resolvedTier = tier,
        screenOn = true,
        audioFocusObtained = true,
        batteryPct = 80,
        dozeState = "ACTIVE",
        watchdogRepair = false,
        bootCount = boot,
        deliveryPath = "RING",
        ringerStarted = true,
        alarmStreamMuted = muted,
        clockChanges = clock,
    )

    /**
     * An occurrence `id` due at [rung], its rung armed at [armedAt] through `ensureArmed`, and its alarm fired at
     * [firedAt] through the fire path, as the platform would deliver it. Returns the `ALARM_FIRED` event's id.
     */
    @Suppress("LongParameterList")
    private fun armedAndFired(
        id: String,
        slot: Int,
        rung: Instant,
        armedAt: Instant,
        firedAt: Instant,
        withTelemetry: Boolean = true,
    ): String {
        fixture.seed(id, Criticality.STANDARD, rung, slot)
        fixture.clock.now = armedAt
        fixture.coordinator.ensureArmed()
        fixture.clock.now = firedAt
        fixture.handler.onFire(slot, rung)
        val fired = fixture.eventsOf(id, EventType.ALARM_FIRED).first().id
        if (withTelemetry) telemetry.insert(telemetryRow(fired))
        // Done, so that the next occurrence a test seeds is the head of the ladder and is armed ahead on its own.
        fixture.complete(id)
        return fired
    }

    @Test
    fun `a fire of a rung armed ahead, with the phone undisturbed, is counted with its latency and its tier`() {
        armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 30.seconds)

        val report = reader.read(t0 + 1.hours)

        val row = report.fires.single()
        assertEquals(30.seconds, row.latency)
        assertEquals(DeliveryCapability.TIER_3, row.tier)
        assertEquals(listOf(TierDrift(DeliveryCapability.TIER_3, 1, 30.seconds, 30.seconds)), report.drift)
        assertEquals(0, report.catchUp)
        assertEquals(emptyMap<Exclusion, Int>(), report.excluded)
        assertEquals(0, report.neverFired)
    }

    @Test
    fun `fires under a changed boot count are left out of drift and counted by reason`() {
        val fired =
            armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 3.hours, withTelemetry = false)
        telemetry.insert(telemetryRow(fired, boot = fixture.bootCount + 1))

        val report = reader.read(t0 + 4.hours)

        assertEquals(emptyList<FireRow>(), report.fires)
        assertEquals(mapOf(Exclusion.BOOT_CHANGED to 1), report.excluded)
        assertEquals(emptyList<TierDrift>(), report.drift)
    }

    @Test
    fun `fires after the clock was set are left out of drift and counted by reason`() {
        val fired =
            armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 30.seconds, withTelemetry = false)
        telemetry.insert(telemetryRow(fired, clock = 1))

        val report = reader.read(t0 + 1.hours)

        assertEquals(emptyList<FireRow>(), report.fires)
        assertEquals(mapOf(Exclusion.CLOCK_CHANGED to 1), report.excluded)
    }

    @Test
    fun `a fire android cannot vouch for is counted as unverifiable, not dropped and not counted`() {
        armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 30.seconds, withTelemetry = false)

        val report = reader.read(t0 + 1.hours)

        assertEquals(emptyList<FireRow>(), report.fires)
        assertEquals(mapOf(Exclusion.UNVERIFIABLE to 1), report.excluded)
    }

    @Test
    fun `a fire whose arming recorded no clock change count is unverifiable`() {
        val fired =
            armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 30.seconds, withTelemetry = false)
        telemetry.insert(telemetryRow(fired, clock = null))

        assertEquals(mapOf(Exclusion.UNVERIFIABLE to 1), reader.read(t0 + 1.hours).excluded)
    }

    @Test
    fun `a rung armed for now after its instant is a catch up and never drift`() {
        armedAndFired("a", 31, rung = t0, armedAt = t0 + 5.minutes, firedAt = t0 + 5.minutes + 2.seconds)

        val report = reader.read(t0 + 1.hours)

        assertEquals(emptyList<FireRow>(), report.fires)
        assertEquals(1, report.catchUp)
        assertEquals(emptyMap<Exclusion, Int>(), report.excluded)
    }

    @Test
    fun `the figures are as of the instant asked`() {
        armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 30.seconds)

        assertEquals(1, reader.read(t0 + 30.seconds).fires.size)
        assertEquals(0, reader.read(t0 + 30.seconds - 1.milliseconds).fires.size)
    }

    @Test
    fun `only the last seven days are read`() {
        armedAndFired("old", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 30.seconds)

        assertEquals(1, reader.read(t0 + 6.days).fires.size)
        assertEquals("eight days on, the fire is outside the window", 0, reader.read(t0 + 8.days).fires.size)
    }

    @Test
    fun `an open occurrence whose first rung never fired is a never fired rung once the tolerance has passed`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()

        assertEquals(1, reader.read(t0 + ReliabilityReader.NEVER_FIRED_AFTER).neverFired)
        assertEquals(0, reader.read(t0 + ReliabilityReader.NEVER_FIRED_AFTER - 1.milliseconds).neverFired)
        assertEquals(Duration.parse("15m"), ReliabilityReader.NEVER_FIRED_AFTER)
    }

    /** A first rung due at [t0] that was armed an hour ahead and never fired. */
    private fun unfiredAtT0() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
    }

    private fun boot(
        count: Long,
        at: Instant,
    ) = boots.record(BootInstant(count, at))

    // The failure that matters most on One UI: the phone restarts at 3 AM, the app is asleep and never re-armed, and
    // the 7 AM rung never fires. The phone was running through grace, so this is a real failure and raises the banner.
    @Test
    fun `a restart before the rung with the phone running through grace does not excuse a rung that never fired`() {
        unfiredAtT0()
        boot(8, t0 - 4.hours)

        val report = reader.read(t0 + 1.hours)

        assertEquals(1, report.neverFired)
        assertEquals(emptyMap<Exclusion, Int>(), report.neverFiredExcluded)
        assertTrue(Banner.NeverFired(1) in report.banners)
    }

    @Test
    fun `a phone that stayed off past the end of grace excuses a rung that never fired`() {
        unfiredAtT0()
        // STANDARD grace ends four hours after the rung; the first boot after the rung began after that.
        boot(8, t0 + 5.hours)

        val report = reader.read(t0 + 6.hours)

        assertEquals(0, report.neverFired)
        assertEquals(mapOf(Exclusion.OFF_THROUGH_GRACE to 1), report.neverFiredExcluded)
        assertEquals(emptyList<Banner>(), report.banners.filterIsInstance<Banner.NeverFired>())
    }

    @Test
    fun `a boot within grace after the rung means the phone was running, so the rung that never fired is a failure`() {
        unfiredAtT0()
        boot(8, t0 + 1.hours)

        val report = reader.read(t0 + 6.hours)

        assertEquals(1, report.neverFired)
        assertEquals(emptyMap<Exclusion, Int>(), report.neverFiredExcluded)
    }

    @Test
    fun `a boot exactly at the end of grace is within it, and one a millisecond later is after it`() {
        unfiredAtT0()
        val graceEnd = t0 + 4.hours

        boot(8, graceEnd)
        assertEquals("exactly at the end of grace", 1, reader.read(t0 + 6.hours).neverFired)

        // Replace the boot with one a millisecond later (a fresh store, so the first answer is not kept).
        fixture.close()
        val again = ArmingFixture(context)
        try {
            again.seed("a", Criticality.STANDARD, t0, slot = 31)
            again.clock.now = t0 - 1.hours
            again.coordinator.ensureArmed()
            again.graph.get<BootInstantRepository>().record(BootInstant(8, graceEnd + 1.milliseconds))
            val report = again.graph.get<ReliabilityReader>().read(t0 + 6.hours)
            assertEquals("a millisecond after", 0, report.neverFired)
            assertEquals(mapOf(Exclusion.OFF_THROUGH_GRACE to 1), report.neverFiredExcluded)
        } finally {
            again.close()
        }
    }

    // The One UI case the excuse used to miss: the phone restarted once at 3 AM and the app never ran in that boot (it
    // was asleep), then it restarted again after grace. The boot counts are 7, an unseen 8, and 9, so the app knows it
    // missed a boot, and the phone may have been running through grace: the rung is a real failure (ADR 0071).
    @Test
    fun `an unseen boot followed by a second restart does not excuse a rung that never fired`() {
        unfiredAtT0()
        boot(7, t0 - 4.hours)
        boot(9, t0 + 5.hours)

        val report = reader.read(t0 + 6.hours)

        assertEquals(1, report.neverFired)
        assertEquals(emptyMap<Exclusion, Int>(), report.neverFiredExcluded)
        assertEquals(1, report.unseenBoots)
    }

    @Test
    fun `consecutive boot counts leave no gap, so a phone off past grace is still excused`() {
        unfiredAtT0()
        boot(7, t0 - 4.hours)
        boot(8, t0 + 5.hours)

        val report = reader.read(t0 + 6.hours)

        assertEquals(0, report.neverFired)
        assertEquals(mapOf(Exclusion.OFF_THROUGH_GRACE to 1), report.neverFiredExcluded)
        assertEquals(0, report.unseenBoots)
    }

    // The first record after an install has no predecessor and no gap: the app cannot know about boots before it was
    // there, so a phone whose count is already 40 when the app is installed has not "missed" 39 boots.
    @Test
    fun `the first record after an install is not a gap`() {
        unfiredAtT0()
        boot(40, t0 + 5.hours)

        val report = reader.read(t0 + 6.hours)

        assertEquals(0, report.unseenBoots)
        assertEquals(0, report.neverFired)
        assertEquals(mapOf(Exclusion.OFF_THROUGH_GRACE to 1), report.neverFiredExcluded)
        assertEquals(emptyList<Banner>(), report.banners.filterIsInstance<Banner.NotRunAfterRestart>())
    }

    @Test
    fun `a boot count the platform does not report never excuses a rung that never fired`() {
        unfiredAtT0()
        boot(7, t0 - 4.hours)
        boot(8, t0 + 5.hours)
        fixture.bootCount = -1

        val report = reader.read(t0 + 6.hours)

        assertEquals(1, report.neverFired)
        assertEquals(emptyMap<Exclusion, Int>(), report.neverFiredExcluded)
    }

    @Test
    fun `a gap raises the banner state, naming the sleeping apps steps on a Samsung and the battery step elsewhere`() {
        unfiredAtT0()
        boot(7, t0 - 4.hours)
        boot(9, t0 + 5.hours)

        assertTrue(Banner.NotRunAfterRestart(1, FixStep.BATTERY_STEP) in reader.read(t0 + 6.hours).banners)

        fixture.manufacturer = "samsung"
        assertTrue(Banner.NotRunAfterRestart(1, FixStep.SAMSUNG_SLEEPING_STEPS) in reader.read(t0 + 6.hours).banners)
    }

    @Test
    fun `only gaps that ended inside the window are counted`() {
        val asOf = t0 + 10.days
        boot(3, asOf - 20.days)
        boot(6, asOf - 8.days)
        boot(7, asOf - 1.days)
        boot(10, asOf - 1.hours)

        assertEquals(
            "the gap before 6 ended outside the window; the gap before 10 is two boots",
            2,
            reader.read(asOf).unseenBoots,
        )
    }

    @Test
    fun `unused app restrictions are carried into the report and raise their banner only when they apply`() {
        assertEquals(null, reader.read(t0).unusedAppExempt)
        assertEquals(emptyList<Banner>(), reader.read(t0).banners.filterIsInstance<Banner.UnusedAppRestrictions>())

        fixture.unusedAppExempt = true
        assertEquals(true, reader.read(t0).unusedAppExempt)
        assertEquals(emptyList<Banner>(), reader.read(t0).banners.filterIsInstance<Banner.UnusedAppRestrictions>())

        fixture.unusedAppExempt = false
        val report = reader.read(t0)
        assertEquals(false, report.unusedAppExempt)
        assertTrue(Banner.UnusedAppRestrictions(FixStep.UNUSED_APP_STEP) in report.banners)
    }

    @Test
    fun `with no boot known after the rung the rung that never fired is counted`() {
        unfiredAtT0()

        val report = reader.read(t0 + 6.hours)

        assertEquals(1, report.neverFired)
        assertEquals(emptyMap<Exclusion, Int>(), report.neverFiredExcluded)
    }

    // A backward jump puts the rung back in the future, so it is not counted; a forward jump arms it for now, so it
    // fires. Either way a rung that still never fired is a failure, and a clock change excuses nothing.
    @Test
    fun `a clock change never excuses a rung that never fired`() {
        unfiredAtT0()
        clockChanges.record(t0 - 30.minutes)

        val report = reader.read(t0 + 1.hours)

        assertEquals(1, report.neverFired)
        assertEquals(emptyMap<Exclusion, Int>(), report.neverFiredExcluded)
        assertEquals("the count is carried into the report", 1L, report.clockChanges)
    }

    @Test
    fun `an occurrence completed before its first rung is not a never fired rung`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        fixture.clock.now = t0 - 1.minutes
        fixture.complete("a")

        val report = reader.read(t0 + 1.hours)

        assertEquals(0, report.neverFired)
        assertEquals(emptyMap<Exclusion, Int>(), report.neverFiredExcluded)
    }

    @Test
    fun `the counts of missed occurrences and repaired alarms are read from the log within the window`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        val asOf = t0 + 1.days

        fun system(
            id: String,
            type: EventType,
            at: Instant,
            effective: Instant? = null,
        ) = fixture.events.insert(Event(id, "a", type, at, effective, EventSource.SYSTEM, EventPayload.None))
        system("m-in", EventType.MISSED, asOf, effective = asOf - 1.hours)
        system("m-edge", EventType.MISSED, asOf, effective = asOf - ReliabilityReader.WINDOW)
        system("m-out", EventType.MISSED, asOf, effective = asOf - ReliabilityReader.WINDOW - 1.milliseconds)
        system("r-in", EventType.WATCHDOG_REPAIR, asOf - 2.hours)
        system("r-out", EventType.WATCHDOG_REPAIR, asOf - ReliabilityReader.WINDOW - 1.milliseconds)

        val report = reader.read(asOf)

        assertEquals(
            "a missed occurrence counts by when it became missed, inside the window",
            2,
            report.missedOccurrences,
        )
        assertEquals(1, report.watchdogRepairs)
    }

    @Test
    fun `fires on a muted alarm stream are counted`() {
        val fired =
            armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 5.seconds, withTelemetry = false)
        telemetry.insert(telemetryRow(fired, muted = true))
        armedAndFired("b", 32, rung = t0 + 1.hours, armedAt = t0 + 1.minutes, firedAt = t0 + 1.hours + 5.seconds)

        assertEquals(1, reader.read(t0 + 2.hours).mutedFires)
    }

    // The hour of the day is local to the occurrence's zone (Asia/Kolkata in the fixture, UTC+5:30), is floored, and is
    // never a minute or a second. 08:00:30Z is 13:30:30 there.
    @Test
    fun `a counted fire carries its local date and the local hour of the fire and of its rung, and no minute`() {
        armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 30.seconds)

        val row = reader.read(t0 + 1.hours).fires.single()

        assertEquals(13, row.rungHour)
        assertEquals(13, row.fireHour)
        assertEquals(LocalDate(2026, 3, 1).toEpochDays(), row.localDay)
    }

    @Test
    fun `the hour is floored and the local date is the local one, across midnight`() {
        // 23:59:59 in Kolkata is 18:29:59Z; the fire is at 00:00:10 the next local day, 18:30:10Z.
        val rung = Instant.parse("2026-03-01T18:29:59Z")
        armedAndFired("a", 31, rung = rung, armedAt = rung - 1.hours, firedAt = Instant.parse("2026-03-01T18:30:10Z"))

        val row = reader.read(rung + 1.hours).fires.single()

        assertEquals("not rounded up to midnight", 23, row.rungHour)
        assertEquals(0, row.fireHour)
        assertEquals("the fire is on the next local day", LocalDate(2026, 3, 2).toEpochDays(), row.localDay)
    }

    @Test
    fun `a never fired rung is reported by its local date and hour`() {
        unfiredAtT0()

        val report = reader.read(t0 + 1.hours)

        assertEquals(listOf(UnfiredRow(LocalDate(2026, 3, 1).toEpochDays(), 13)), report.neverFiredRungs)
    }

    @Test
    fun `the days with a fire are counted, so the report can say how much of the window it stands on`() {
        armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 5.seconds)
        armedAndFired("b", 32, rung = t0 + 1.days, armedAt = t0 + 1.days - 1.hours, firedAt = t0 + 1.days + 5.seconds)
        armedAndFired("c", 33, rung = t0 + 1.days + 2.hours, armedAt = t0 + 1.days, firedAt = t0 + 1.days + 2.hours)

        val report = reader.read(t0 + 2.days)

        assertEquals(7, report.windowDays)
        assertEquals("two distinct days had a fire", 2, report.daysWithFires)
    }

    @Test
    fun `drift is grouped by the tier the alarm was delivered under`() {
        val a =
            armedAndFired("a", 31, rung = t0, armedAt = t0 - 1.hours, firedAt = t0 + 10.seconds, withTelemetry = false)
        telemetry.insert(telemetryRow(a, tier = DeliveryCapability.TIER_2))
        armedAndFired("b", 32, rung = t0 + 1.hours, armedAt = t0 + 1.minutes, firedAt = t0 + 1.hours + 40.seconds)
        val c =
            armedAndFired(
                "c",
                33,
                rung = t0 + 2.hours,
                armedAt = t0 + 1.hours + 1.minutes,
                firedAt =
                    t0 + 2.hours + 20.seconds,
                withTelemetry = false,
            )
        telemetry.insert(telemetryRow(c, tier = DeliveryCapability.TIER_3))

        val report = reader.read(t0 + 3.hours)

        assertEquals(
            listOf(
                TierDrift(DeliveryCapability.TIER_2, 1, 10.seconds, 10.seconds),
                TierDrift(DeliveryCapability.TIER_3, 2, 30.seconds, 40.seconds),
            ),
            report.drift,
        )
    }

    @Test
    fun `arming records the context of the rung once, when the alarm is first armed, and not on a refresh`() {
        fixture.seed("a", Criticality.STANDARD, t0 + 1.hours, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()
        val scheduled = fixture.eventsOf("a", EventType.ALARM_SCHEDULED).single()

        assertEquals(
            ArmingContext(scheduled.id, t0 + 1.hours, bootCount = fixture.bootCount, clockChanges = 0),
            contexts.find(scheduled.id),
        )

        fixture.coordinator.ensureArmed()
        assertEquals("a refresh writes no event", 1, fixture.eventsOf("a", EventType.ALARM_SCHEDULED).size)

        // A rung armed after the clock was set records the count then.
        clockChanges.record(t0)
        fixture.clock.now = t0 + 90.minutes
        fixture.handler.onFire(31, t0 + 1.hours)
        val second = fixture.eventsOf("a", EventType.ALARM_SCHEDULED).last()
        assertEquals(1L, contexts.find(second.id)?.clockChanges)
    }

    @Test
    @Config(sdk = [36])
    fun `the report carries the capability as it is now, and the banner names what is missing`() {
        org.robolectric.shadows.ShadowAlarmManager
            .setCanScheduleExactAlarms(false)

        val report = reader.read(t0)

        assertEquals(false, report.capability.exactAlarm)
        val below = report.banners.filterIsInstance<Banner.BelowTier3>().single()
        assertTrue(MissingInput.EXACT_ALARM in below.missing)
    }

    @Test
    fun `a never fired rung shows in the report's banners`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()

        val report = reader.read(t0 + 1.hours)

        assertTrue(Banner.NeverFired(1) in report.banners)
    }

    @Test
    fun `the store's failure counts and the corruption marker are carried into the report`() {
        val direct =
            ReliabilityReader(
                fixture.occurrences,
                fixture.graph.get(),
                fixture.events,
                telemetry,
                contexts,
                clockChanges,
                boots,
                BootCount { 7 },
                unusedAppExempt = { null },
                samsung = { false },
                checks = { emptyList() },
                failureCounts = { mapOf("fire_telemetry.insert" to 3L) },
                corruption = { CorruptionMarker(t0, preserved = true) },
                capability = { fixture.resolver.inputs },
            )

        val report = direct.read(t0)

        assertEquals(mapOf("fire_telemetry.insert" to 3L), report.storeFailures)
        assertNotNull(report.corruption)
        assertTrue(Banner.StoreTrouble(3, true) in report.banners)
        assertNull(report.lastSettledCheck)
    }
}
