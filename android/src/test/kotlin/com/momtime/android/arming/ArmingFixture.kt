package com.momtime.android.arming

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import com.momtime.android.data.TestGraph
import com.momtime.android.data.pregnancy
import com.momtime.android.data.template
import com.momtime.android.data.testZone
import com.momtime.android.di.BootCount
import com.momtime.android.di.DeliveryWiring
import com.momtime.android.di.DeviceManufacturer
import com.momtime.android.di.UnusedAppRestrictions
import com.momtime.android.di.Uptime
import com.momtime.android.di.armingModule
import com.momtime.android.reliability.CheckReceiver
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.android.work.WorkPasses
import com.momtime.shared.data.EventRepository
import com.momtime.shared.data.OccurrenceRepository
import com.momtime.shared.data.PregnancyRepository
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.Event
import com.momtime.shared.domain.EventPayload
import com.momtime.shared.domain.EventSource
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import kotlinx.datetime.LocalDate
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The time tests run at: a morning in the third week of pregnancy of nobody in particular. */
internal val t0: Instant = Instant.parse("2026-03-01T08:00:00Z")

/** A clock a test moves, including backward (golden scenario 14). */
internal class MutableClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

/**
 * The platform's alarm API with the one thing Robolectric cannot do: refuse an exact alarm. The shadow reports
 * `canScheduleExactAlarms` but never throws `SecurityException`, so a test that needs the refusal sets
 * [refuseExact]. Every call still reaches the shadow, which is the oracle for what was armed.
 */
internal class SwitchableAlarmApi(
    private val delegate: AlarmApi,
) : AlarmApi {
    @Volatile
    var refuseExact = false
    var exactAttempts = 0
        private set

    override fun setAlarmClock(
        triggerAtMillis: Long,
        show: PendingIntent?,
        operation: PendingIntent,
    ) {
        exactAttempts++
        if (refuseExact) throw SecurityException("exact alarms are not allowed")
        delegate.setAlarmClock(triggerAtMillis, show, operation)
    }

    override fun setAndAllowWhileIdle(
        triggerAtMillis: Long,
        operation: PendingIntent,
    ) = delegate.setAndAllowWhileIdle(triggerAtMillis, operation)

    override fun cancel(operation: PendingIntent) = delegate.cancel(operation)
}

/**
 * A production graph over a real database and the shadowed `AlarmManager`, with the clock, the ids, the boot
 * count and the refusal of exact alarms in the test's hands. Occurrences are written straight to the
 * repositories, as the materialiser would have.
 */
@Suppress("LongParameterList")
internal class ArmingFixture(
    private val context: Context,
    val clock: MutableClock = MutableClock(t0),
    name: String = "t-${java.util.UUID.randomUUID().toString().take(8)}.db",
    storeName: String = "s-${java.util.UUID.randomUUID().toString().take(8)}.db",
    idPrefix: String = "gen",
    seedPregnancy: Boolean = true,
    delivery: DeliveryWiring = DeliveryWiring(),
) {
    val api = SwitchableAlarmApi(PlatformAlarmApi(context.getSystemService(AlarmManager::class.java)))
    private var nextId = 0

    /** What the device reports as its boot count and the app's version code. A test changes them. */
    var bootCount = 7L

    /** How long the device has been up: with the clock it gives the instant the boot began (ADR 0070). */
    var uptime: kotlin.time.Duration = kotlin.time.Duration.ZERO

    /** The maker of the phone, and whether Android's unused app restrictions are off (null below API 30). */
    var manufacturer = "Google"
    var unusedAppExempt: Boolean? = null
    var versionCode = 100L
    val graph =
        TestGraph(
            context,
            name = name,
            storeName = storeName,
            clock = clock,
            arming =
                armingModule(
                    context,
                    api,
                    BootCount { bootCount },
                    uptime = Uptime { uptime },
                    manufacturer = DeviceManufacturer { manufacturer },
                    unusedAppRestrictions = UnusedAppRestrictions { unusedAppExempt },
                    newId = { "$idPrefix-${nextId++}" },
                    appVersion = AppVersion { versionCode },
                    delivery = delivery,
                ),
        )

    val coordinator: ArmingCoordinator get() = graph.get()
    val handler: AlarmFireHandler get() = graph.get()
    val delivery: RecordingDeliveryPort get() = graph.get()
    val resolver: CapabilityResolver get() = graph.get()
    val watchdog: Watchdog get() = graph.get()
    val appStart: AppStart get() = graph.get()
    val passes: WorkPasses get() = graph.get()
    val armed: ArmedAlarmRepository get() = graph.get()
    val occurrences: OccurrenceRepository get() = graph.get()
    val events: EventRepository get() = graph.get()

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    init {
        // The shadow starts with exact alarms not allowed. A test starts from allowed and takes it away.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        if (seedPregnancy) graph.get<PregnancyRepository>().insert(pregnancy())
    }

    /** Every alarm the (shadowed) platform holds, whoever armed it. */
    fun allAlarms(): List<ShadowAlarmManager.ScheduledAlarm> = shadowOf(alarmManager).scheduledAlarms

    /**
     * The alarms this app's alarm path armed: those whose operation names [AlarmReceiver]. The oracle: what was
     * armed, whatever the code believes. WorkManager arms one of its own on API below 30 (see [allAlarms] and
     * `WorkTest`), which is not the alarm path's and is not counted against "exactly one alarm".
     */
    fun alarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        allAlarms().filter {
            shadowOf(it.operation).savedIntent.component ==
                ComponentName(context, AlarmReceiver::class.java)
        }

    /** The events written since [before], as (type, occurrence) pairs: a multiset difference, in a stable order. */
    fun eventDelta(
        vararg occurrenceIds: String,
        before: List<Pair<EventType, String>>,
    ): List<Pair<EventType, String>> {
        val remaining = eventLog(*occurrenceIds).toMutableList()
        before.forEach { remaining.remove(it) }
        return remaining
    }

    /** The alarms of the reliability check (ADR 0069): those whose operation names `CheckReceiver`. */
    fun checkAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        allAlarms().filter {
            shadowOf(it.operation).savedIntent.component == ComponentName(context, CheckReceiver::class.java)
        }

    fun clearAlarms() = alarms().toList().forEach { alarmManager.cancel(operation(it)) }

    /**
     * Loses the alarm for [slot] the way the system does when it drops one (a reboot, an update, a clean up): the
     * `PendingIntent` is cancelled and the alarm goes with it. This is the production signal the watchdog probes
     * (`FLAG_NO_CREATE` finds nothing); the shadow's list is only read afterwards, as the oracle.
     */
    fun loseAlarm(slot: Int) {
        val pending = AlarmIntents.existing(context, slot) ?: return
        alarmManager.cancel(pending)
        pending.cancel()
    }

    /** What an armed alarm is, as the platform holds it: its type, its trigger time and its request code. */
    data class AlarmShape(
        val alarmClock: Boolean,
        val allowWhileIdle: Boolean,
        val triggerAtMs: Long,
        val requestCode: Int,
    )

    fun shapes(): List<AlarmShape> =
        alarms().map {
            AlarmShape(it.alarmClockInfo != null, it.isAllowWhileIdle, it.triggerAtMs, requestCode(it))
        }

    /** The count of events of [type] over every occurrence in [occurrenceIds]. */
    fun count(
        type: EventType,
        vararg occurrenceIds: String,
    ): Int = occurrenceIds.sumOf { events.findByOccurrenceAndType(it, type).size }

    fun operation(alarm: ShadowAlarmManager.ScheduledAlarm): PendingIntent = checkNotNull(alarm.operation)

    fun requestCode(alarm: ShadowAlarmManager.ScheduledAlarm): Int = shadowOf(operation(alarm)).requestCode

    /** A template and one PENDING occurrence of it at [scheduled], in slot [slot]. Returns the occurrence. */
    @Suppress("LongParameterList")
    fun seed(
        id: String,
        criticality: Criticality,
        scheduled: Instant,
        slot: Int,
        dosage: String? = null,
        doctorInstructions: String? = null,
    ): Occurrence {
        val templateId = "tmpl-$id"
        graph.get<ScheduleTemplateRepository>().insert(
            template(
                templateId,
            ).copy(criticality = criticality, dosage = dosage, doctorInstructions = doctorInstructions),
        )
        val occurrence =
            Occurrence(
                id = id,
                templateId = templateId,
                localDate = LocalDate(2026, 3, 1),
                scheduledInstant = scheduled,
                timeZoneId = testZone,
                state = OccurrenceState.PENDING,
                alarmSlot = slot,
            )
        occurrences.insert(occurrence)
        return occurrence
    }

    fun eventsOf(
        occurrenceId: String,
        type: EventType,
    ): List<Event> = events.findByOccurrenceAndType(occurrenceId, type)

    /** Every event of every given occurrence, as (type, occurrence) pairs, to compare before and after. */
    fun eventLog(vararg occurrenceIds: String): List<Pair<EventType, String>> =
        occurrenceIds
            .flatMap { id ->
                events.findForOccurrence(id).map { it.eventType to id }
            }.sortedBy { it.toString() }

    /** Records that a rung fired, as the fire path would have, without going through it. */
    fun recordFired(
        occurrenceId: String,
        times: Int,
    ) = repeat(times) { index ->
        events.insert(
            Event(
                id = "pre-$occurrenceId-$index",
                occurrenceId = occurrenceId,
                eventType = EventType.ALARM_FIRED,
                deviceTimestamp = t0 + index.minutes,
                effectiveAt = null,
                source = EventSource.SYSTEM,
                payload = EventPayload.None,
            ),
        )
    }

    /** Completes [occurrenceId] through the domain's one transition, as the ring screen will. */
    fun complete(occurrenceId: String) =
        occurrences.transition(
            occurrenceId,
            OccurrenceState.COMPLETED,
            Event(
                id = "done-$occurrenceId",
                occurrenceId = occurrenceId,
                eventType = EventType.COMPLETED,
                deviceTimestamp = clock.now,
                effectiveAt = null,
                source = EventSource.USER,
                payload = EventPayload.None,
            ),
        )

    fun close() = graph.close()
}
