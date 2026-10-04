package com.momtime.android.arming

import com.momtime.shared.data.ReconcileCommand
import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.engine.ArmingSelection
import com.momtime.shared.engine.CatchUp
import kotlin.time.Instant

/**
 * How a fired rung is to be presented (ADR 0056). [NORMAL] is "as policy says": a ring, or silent under quiet hours
 * or the interruption budget, which PR 5 decides. [SILENT_NOTICE] is a rung that fired beyond the catch up window:
 * a notification with no ring, no vibration and no heads up, so that nothing sounds hours late and nothing is
 * dropped.
 */
enum class Presentation { NORMAL, SILENT_NOTICE }

/** The rung handed to delivery when an alarm fires, and how it is to be presented. */
data class FiredRung(
    val occurrenceId: String,
    val alarmSlot: Int,
    val rung: EscalationRung,
    val presentation: Presentation = Presentation.NORMAL,
)

/**
 * Where a fired rung goes to be shown and heard. The ringer service, the notification and the ring screen
 * arrive in PR 5; until then [RecordingDeliveryPort] stands in, so the fire path is complete and testable.
 */
fun interface DeliveryPort {
    fun deliver(rung: FiredRung)
}

/** Remembers what it was given. The delivery in use until PR 5. */
class RecordingDeliveryPort : DeliveryPort {
    private val received = mutableListOf<FiredRung>()

    val delivered: List<FiredRung> get() = synchronized(received) { received.toList() }

    override fun deliver(rung: FiredRung) {
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
 *   fired) and is handed to delivery. Within the catch up window it is presented as policy says; beyond the
 *   window, and still within grace, it is presented as a silent notice (ADR 0056). This is the only place
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
        val expected = ArmingSelection.expectedFor(candidates.of(occurrence), DEVICE_CHANNELS)
        if (expected == null || expected.instant != rungInstant) return FireOutcome.NotExpected
        log.fired(occurrence.id)
        val presentation =
            if (CatchUp.isWithinWindow(expected.instant, log.now())) Presentation.NORMAL else Presentation.SILENT_NOTICE
        val fired = FiredRung(occurrence.id, occurrence.alarmSlot, expected, presentation)
        delivery.deliver(fired)
        return FireOutcome.Fired(fired)
    }
}
