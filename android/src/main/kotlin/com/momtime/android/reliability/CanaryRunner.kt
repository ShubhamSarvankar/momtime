package com.momtime.android.reliability

import android.content.Context
import com.momtime.android.arming.AlarmApi
import com.momtime.android.arming.CapabilityResolver
import com.momtime.android.capability.DeliveryMechanism
import com.momtime.android.store.ArmedAlarmRepository
import com.momtime.android.store.CheckOutcome
import com.momtime.android.store.ReliabilityCheck
import com.momtime.android.store.ReliabilityCheckRepository
import com.momtime.shared.data.EventRepository
import com.momtime.shared.engine.AlarmEvents
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The constants of the check she starts (ADR 0069). */
object CheckPolicy {
    /** How long after she starts the check that its alarm is due. */
    val DELAY: Duration = 60.seconds

    /** How long after its due time a check that has not fired is a failed one. */
    val TIMEOUT: Duration = 3.minutes

    /**
     * A reminder's rung armed this close to the check's due time defers the check: the check is the one permitted
     * second alarm (ADR 0069) and it must never sit beside a reminder it could be mistaken for.
     */
    val NEAR_REMINDER: Duration = 2.minutes
}

/** What asking to start the check came to. */
sealed interface StartResult {
    data class Started(
        val check: ReliabilityCheck,
    ) : StartResult

    /** A check is already pending. */
    data object AlreadyRunning : StartResult

    /** A reminder is due within [CheckPolicy.NEAR_REMINDER] of when the check would be. Try again afterwards. */
    data object ReminderNear : StartResult

    /** The alarm could not be armed or recorded. Nothing is left pending. */
    data object Failed : StartResult
}

/**
 * The check she starts from the ring screen (ADR 0069): an alarm 60 seconds out, through the same mechanism her
 * reminders are armed through, that she watches. It is the **single writer** of the check's result: it records
 * `CANARY_RESULT` with the instant it fired when the alarm fires, and with none when the check times out, including
 * when the process died mid check and the result is only written on the next open. The watchdog plays no part.
 *
 * It is the one permitted second alarm. It never calls `ensureArmed`, and `ensureArmed` never knows it: the check
 * has its own action, its own receiver and its own request code (`CheckIntents`), so arming or cancelling it touches
 * no reminder's alarm, armed record or event, and nothing a reminder does cancels it. The alarm is cancelled when the
 * check ends, by firing or by timing out.
 *
 * It reads capability to arm through the mechanism a reminder would use, and arms nothing but its own alarm.
 */
@Suppress("LongParameterList")
class CanaryRunner internal constructor(
    private val context: Context,
    private val checks: ReliabilityCheckRepository,
    private val events: EventRepository,
    private val api: AlarmApi,
    private val resolver: CapabilityResolver,
    private val armed: ArmedAlarmRepository,
    private val clock: Clock,
    private val newId: () -> String,
) {
    /**
     * Starts a check due [CheckPolicy.DELAY] from now, unless one is running (after settling one that has timed out)
     * or a reminder is near.
     */
    @Synchronized
    fun start(): StartResult {
        settleOverdue()
        val due = clock.now() + CheckPolicy.DELAY
        return when {
            checks.pending() != null -> StartResult.AlreadyRunning
            reminderNear(due) -> StartResult.ReminderNear
            else -> begin(due)
        }
    }

    private fun reminderNear(due: Instant): Boolean {
        val reminder = armed.current() ?: return false
        return abs((reminder.rungInstant - due).inWholeMilliseconds) <= NEAR_MILLIS
    }

    private fun begin(due: Instant): StartResult {
        val resolution = resolver.resolve()
        val id = checks.startPending(due, resolution.capability) ?: return StartResult.Failed
        return try {
            arm(id, due, resolution.mechanism)
            // Arming may have fallen back to an inexact alarm: the check records the tier it really had.
            val achieved = resolver.current.capability
            if (achieved != resolution.capability) checks.retier(id, achieved)
            StartResult.Started(checkNotNull(checks.pending()))
        } catch (
            @Suppress("TooGenericExceptionCaught") _: RuntimeException,
        ) {
            // It never ran: nothing is left pending that no alarm will settle, and no result is written for it.
            checks.abandon(id)
            cancelAlarm()
            StartResult.Failed
        }
    }

    /** The alarm fired. It is recorded once, whoever races to it; a fire after the check timed out is ignored. */
    @Synchronized
    fun onFired(checkId: Long) {
        val pending = checks.pending()?.takeIf { it.id == checkId } ?: return
        val now = clock.now()
        if (checks.markFired(pending.id, now)) {
            events.insert(AlarmEvents.canaryResult(newId(), pending.scheduledAt, now, now))
        }
        cancelAlarm()
    }

    /**
     * Settles a check whose time is up: a result with no actual instant. Called when the screen opens and while it is
     * open, so a check the process died in is settled on the next open. Returns the outcome it settled, or null.
     */
    @Synchronized
    fun settleOverdue(): CheckOutcome? {
        val now = clock.now()
        val overdue = checks.pending()?.takeIf { now >= it.scheduledAt + CheckPolicy.TIMEOUT }
        // Settled once, whoever races to it: only the call that moved the row out of PENDING writes the result.
        return overdue?.takeIf { checks.markMissed(it.id) }?.let {
            events.insert(AlarmEvents.canaryResult(newId(), it.scheduledAt, null, now))
            cancelAlarm()
            CheckOutcome.MISSED
        }
    }

    fun pending(): ReliabilityCheck? = checks.pending()

    fun recent(limit: Int): List<ReliabilityCheck> = checks.recent(limit)

    private fun arm(
        id: Long,
        due: Instant,
        mechanism: DeliveryMechanism,
    ) {
        val operation = CheckIntents.fire(context, id)
        val trigger = due.toEpochMilliseconds()
        try {
            armThrough(mechanism, trigger, operation)
        } catch (_: SecurityException) {
            // The platform refused exact alarms although it said they were allowed: arm as a reminder would then.
            resolver.refuseExact()
            armThrough(DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE, trigger, operation)
        }
    }

    private fun armThrough(
        mechanism: DeliveryMechanism,
        trigger: Long,
        operation: android.app.PendingIntent,
    ) = when (mechanism) {
        DeliveryMechanism.SET_ALARM_CLOCK -> api.setAlarmClock(trigger, CheckIntents.show(context), operation)
        DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE -> api.setAndAllowWhileIdle(trigger, operation)
    }

    private fun cancelAlarm() {
        CheckIntents.existing(context)?.let {
            api.cancel(it)
            it.cancel()
        }
    }

    private companion object {
        val NEAR_MILLIS = CheckPolicy.NEAR_REMINDER.inWholeMilliseconds
    }
}
