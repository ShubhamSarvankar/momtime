package com.momtime.android.arming

import com.momtime.shared.data.ReconcileCommand
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.engine.ArmingSelection
import com.momtime.shared.engine.CatchUp
import com.momtime.shared.engine.RungDelivery
import kotlin.time.Instant

/**
 * How a fired rung is to be presented (ADR 0056). [NORMAL] is "as the domain decided": a ring, or a silent
 * notification under quiet hours or the interruption budget. [SILENT_NOTICE] is a rung that fired beyond the
 * catch up window: a notification with no ring, no vibration and no heads up, so that nothing sounds hours
 * late and nothing is dropped.
 */
enum class Presentation { NORMAL, SILENT_NOTICE }

/**
 * The rung handed to delivery when an alarm fires, and how it is to be presented. [policy] is the domain's
 * decision for this rung (ring, or silent because of quiet hours or the budget, ADR 0060). [continuing] is true
 * when the occurrence is already ringing: the ring in progress goes on, with no restart and no second screen
 * (ADR 0062). [afterSnooze] is true when this is not a ladder rung but the end of a snooze (ADR 0066).
 */
data class FiredRung(
    val occurrenceId: String,
    val alarmSlot: Int,
    val rung: EscalationRung,
    val presentation: Presentation = Presentation.NORMAL,
    val policy: RungDelivery = RungDelivery.RING,
    val continuing: Boolean = false,
    val afterSnooze: Boolean = false,
)

/**
 * Where a fired rung goes to be shown and heard. [eventId] is the `ALARM_FIRED` event the fire wrote, which the
 * device telemetry row is keyed by. [AndroidDeliveryPort][com.momtime.android.delivery.AndroidDeliveryPort] is the
 * implementation in use; [RecordingDeliveryPort] stands in where a test wants to see only what the fire path
 * handed over.
 */
fun interface DeliveryPort {
    fun deliver(
        rung: FiredRung,
        eventId: String,
    )
}

/** Remembers what it was given. */
class RecordingDeliveryPort : DeliveryPort {
    private val received = mutableListOf<FiredRung>()

    val delivered: List<FiredRung> get() = synchronized(received) { received.toList() }

    override fun deliver(
        rung: FiredRung,
        eventId: String,
    ) {
        synchronized(received) { received += rung }
    }
}

/** What a fire did. */
sealed interface FireOutcome {
    /** The alarm named a slot that no occurrence owns: after a reset or a restore. Nothing was written. */
    data object UnknownSlot : FireOutcome

    /** The occurrence is terminal, so there is nothing to ring for. Nothing was written. */
    data object Terminal : FireOutcome

    /** The alarm was not for the rung the log says is next, so it is a leftover. Nothing was written. */
    data object NotExpected : FireOutcome

    /** The expected rung fired: `ALARM_FIRED` was written and the rung was handed to delivery. */
    data class Fired(
        val rung: FiredRung,
    ) : FireOutcome
}

/**
 * The fire path (ADR 0053). An alarm that fires names a slot and the rung it was armed for. It is resolved
 * to its occurrence and checked, and only the rung that is expected next is acted on:
 *
 * - an unknown slot, a terminal occurrence, or a rung that is not the expected one writes nothing and
 *   delivers nothing. These are alarms left over from before a reset, a completion or a re arm, and
 *   ringing for them would ring for a medication she has already taken, or for one that does not exist;
 * - the expected rung writes `ALARM_FIRED` through the domain (the count of these is the record of what has
 *   fired) and is handed to delivery. While a snooze is running the expected "rung" is the snooze's end, and that
 *   writes `SNOOZE_ENDED` instead: a snooze is not a rung, so it must not be counted as one (ADR 0066), and the
 *   rungs that came due during it are armed once it has ended, by the count of rungs that really fired. Within
 *   the catch up window it is presented as the domain decides (a ring, or silent under quiet hours or the
 *   budget, ADR 0060); beyond the window, and still within grace, it is a silent notice (ADR 0056); and if its
 *   occurrence is already ringing the ring in progress continues (ADR 0062). This is the only place
 *   the catch up decision is made: the watchdog and boot arm the earliest rung that has not fired, for now,
 *   and leave it to this.
 *
 * `Reconcile` is dispatched first, so an alarm that fires after the end of grace finds the occurrence `MISSED`
 * and delivers nothing, and does not arm itself again (ADR 0030).
 *
 * In every case, and also if something above throws, the next alarm is armed: a chain that stops at one
 * stale fire would be silently dead until the watchdog found it.
 */
class AlarmFireHandler internal constructor(
    private val candidates: ArmCandidates,
    private val log: AlarmLog,
    private val delivery: DeliveryPort,
    private val coordinator: ArmingCoordinator,
    private val reconcile: ReconcileCommand,
    private val decider: DeliveryDecider,
) {
    fun onFire(
        slot: Int,
        rungInstant: Instant,
    ): FireOutcome =
        // Exclusive of the watchdog: between writing `ALARM_FIRED` and arming the next rung the state is half
        // done, and a watchdog pass must not read it as a lost alarm.
        coordinator.exclusive {
            try {
                dispatch(slot, rungInstant)
            } finally {
                coordinator.ensureArmed()
            }
        }

    private fun dispatch(
        slot: Int,
        rungInstant: Instant,
    ): FireOutcome {
        reconcile.dispatch(log.now())
        val occurrence = candidates.owning(slot)
        return when {
            occurrence == null -> FireOutcome.UnknownSlot
            occurrence.isTerminal -> FireOutcome.Terminal
            else -> fireIfExpected(occurrence, rungInstant)
        }
    }

    private fun fireIfExpected(
        occurrence: Occurrence,
        rungInstant: Instant,
    ): FireOutcome {
        val candidate = candidates.of(occurrence)
        val expected = ArmingSelection.expectedFor(candidate, DEVICE_CHANNELS)
        if (expected == null || expected.instant != rungInstant) return FireOutcome.NotExpected
        val afterSnooze = candidate.snoozeEnd != null
        val eventId = if (afterSnooze) log.snoozeEnded(occurrence.id) else log.fired(occurrence.id)
        val now = log.now()
        val withinWindow = CatchUp.isWithinWindow(expected.instant, now)
        val presentation = if (withinWindow) Presentation.NORMAL else Presentation.SILENT_NOTICE
        val decision = decider.decide(occurrence.id, candidate.criticality, now, mayRing = withinWindow)
        val fired =
            FiredRung(
                occurrence.id,
                occurrence.alarmSlot,
                expected,
                presentation,
                decision.policy,
                decision.continuing,
                afterSnooze,
            )
        delivery.deliver(fired, eventId)
        return FireOutcome.Fired(fired)
    }
}
