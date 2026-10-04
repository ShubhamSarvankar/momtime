package com.momtime.android.arming

import com.momtime.android.capability.DeliveryMechanism
import com.momtime.android.store.ArmedAlarm
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.shared.domain.AlarmScheduler
import com.momtime.shared.domain.Channel
import com.momtime.shared.engine.ArmingSelection
import com.momtime.shared.engine.RungSelection

/** The channels this device delivers (decision 14). The rungs of every other channel are the server's. */
internal val DEVICE_CHANNELS: Set<Channel> = setOf(Channel.RING, Channel.RING_REPEAT)

/** What one "ensure armed" pass did. */
sealed interface EnsureResult {
    /** Nothing is pending. Any alarm that was armed has been cancelled. */
    data object NothingPending : EnsureResult

    /**
     * [selection] is armed through [mechanism]. [scheduledWritten] is whether an `ALARM_SCHEDULED` event was
     * appended: true when this rung was newly armed or the expected rung changed, false on a refresh.
     */
    data class Armed(
        val selection: RungSelection,
        val mechanism: DeliveryMechanism,
        val scheduledWritten: Boolean,
    ) : EnsureResult
}

/**
 * The one entry point of the alarm subsystem (ADR 0053). Every caller that needs the next alarm to exist
 * (a fired alarm, the watchdog, boot, the foreground) calls [ensureArmed] and nothing else: it resolves
 * capability, selects the next rung, arms it, and keeps the armed record. Nothing else arms an alarm.
 *
 * Exactly one alarm is armed at a time, across occurrences (ADR 0017). Request codes are per occurrence, so
 * arming a different occurrence's rung would not replace the alarm already armed; when the head moves to
 * another occurrence the previous alarm is cancelled explicitly, from the armed record. When nothing is
 * pending the armed alarm is cancelled and the record cleared.
 *
 * Selection reads the record of rungs that have fired (the `ALARM_FIRED` events), never a comparison of rung
 * instants with the time (golden scenario 14), and only the channels the device delivers (decision 14). A
 * rung found overdue beyond the catch up window is skipped, not rung (ADR 0056).
 *
 * `ALARM_SCHEDULED` is appended when a rung is first armed or the expected rung changes, and never on a
 * refresh (decision 6). Neither it nor anything here changes an occurrence's state (invariant 3).
 */
class ArmingCoordinator internal constructor(
    private val candidates: ArmCandidates,
    private val log: AlarmLog,
    private val scheduler: AlarmScheduler,
    private val armed: ArmedAlarmRepository,
    private val resolver: CapabilityResolver,
    private val bootCount: () -> Long,
) {
    /**
     * The rung the domain says is next as of now: what the armed alarm should be. The watchdog compares what it
     * finds with this, and [ensureArmed] arms it.
     */
    internal fun expected(): RungSelection? = ArmingSelection.next(candidates.pending(), DEVICE_CHANNELS, log.now())

    /**
     * Runs [block] while no other pass can arm. The fire path and the watchdog run inside it, so a watchdog
     * pass never reads the half done state of a fire that has written `ALARM_FIRED` and not yet armed the next
     * rung, and takes that for a lost alarm.
     */
    @Synchronized
    internal fun <T> exclusive(block: () -> T): T = block()

    @Synchronized
    fun ensureArmed(): EnsureResult {
        // Capability is resolved at runtime, at the start of every pass, never inferred or remembered.
        resolver.resolve()
        val selection = expected()
        val previous = armed.current()
        // The head moved to another occurrence, or there is none: the alarm already armed is cancelled
        // by its slot, because arming another slot would not replace it.
        if (previous != null && previous.alarmSlot != selection?.alarmSlot) scheduler.cancel(previous.alarmSlot)
        if (selection == null) {
            armed.clear()
            return EnsureResult.NothingPending
        }
        scheduler.arm(selection.alarmSlot, selection.rung.instant)
        val changed =
            previous == null ||
                previous.alarmSlot != selection.alarmSlot ||
                previous.rungInstant != selection.rung.instant
        if (changed) log.scheduled(selection.occurrenceId)
        val mechanism = resolver.current.mechanism
        armed.replace(
            ArmedAlarm(
                alarmSlot = selection.alarmSlot,
                rungInstant = selection.rung.instant,
                armedAt = log.now(),
                bootCount = bootCount(),
                exactAllowed = mechanism == DeliveryMechanism.SET_ALARM_CLOCK,
            ),
        )
        return EnsureResult.Armed(selection, mechanism, scheduledWritten = changed)
    }
}
