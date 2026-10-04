package com.momtime.android.arming

import com.momtime.shared.domain.EscalationRung
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.engine.ArmingSelection
import kotlin.time.Instant

/** The rung handed to delivery when an alarm fires. */
data class FiredRung(
    val occurrenceId: String,
    val alarmSlot: Int,
    val rung: EscalationRung,
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
 *   fired) and is handed to delivery.
 *
 * In every case, and also if something above throws, the next alarm is armed: a chain that stops at one
 * stale fire would be silently dead until the watchdog found it.
 */
class AlarmFireHandler internal constructor(
    private val candidates: ArmCandidates,
    private val log: AlarmLog,
    private val delivery: DeliveryPort,
    private val coordinator: ArmingCoordinator,
) {
    fun onFire(
        slot: Int,
        rungInstant: Instant,
    ): FireOutcome =
        try {
            dispatch(slot, rungInstant)
        } finally {
            coordinator.ensureArmed()
        }

    private fun dispatch(
        slot: Int,
        rungInstant: Instant,
    ): FireOutcome {
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
        val fired = FiredRung(occurrence.id, occurrence.alarmSlot, expected)
        delivery.deliver(fired)
        return FireOutcome.Fired(fired)
    }
}
