package com.momtime.android.arming

import android.app.PendingIntent
import android.content.Context
import com.momtime.android.capability.DeliveryMechanism
import com.momtime.shared.domain.AlarmScheduler
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Android [AlarmScheduler]. It arms through the mechanism the capability resolution names (ADR 0050):
 * `setAlarmClock` when exact alarms are allowed, which is Tier 3 and Tier 2, and `setAndAllowWhileIdle` with
 * `RTC_WAKEUP` otherwise, which is Tier 1. The port says nothing about either.
 *
 * It does not resolve capability itself: "ensure armed" calls [CapabilityResolver.resolve] at the start of a
 * pass and this arms through [CapabilityResolver.current]. The one exception is a `SecurityException`, which
 * means the platform no longer allows what [CapabilityResolver.current] said. It then resolves again, and
 * arms through whatever that gives. If the platform still reports exact capability, the refusal is trusted
 * over the report and the alarm is armed inexactly, because an inexact alarm that fires is better than an
 * exact one that never does.
 *
 * An instant that has already passed is armed for now, never in the past: a trigger in the past is a
 * negative delay (golden scenario 14). The rung's own instant stays in the intent, which is what the fire
 * path compares with the rung it expects.
 */
internal class AndroidAlarmScheduler(
    private val context: Context,
    private val api: AlarmApi,
    private val resolver: CapabilityResolver,
    private val clock: Clock,
) : AlarmScheduler {
    override fun arm(
        slot: Int,
        at: Instant,
    ) {
        val operation = AlarmIntents.fire(context, slot, at)
        val trigger = maxOf(at, clock.now()).toEpochMilliseconds()
        try {
            armThrough(resolver.current.mechanism, slot, trigger, operation)
        } catch (_: SecurityException) {
            val again = resolver.resolve()
            val resolution = if (again.mechanism == DeliveryMechanism.SET_ALARM_CLOCK) resolver.refuseExact() else again
            armThrough(resolution.mechanism, slot, trigger, operation)
        }
    }

    override fun cancel(slot: Int) {
        AlarmIntents.existing(context, slot)?.let {
            api.cancel(it)
            it.cancel()
        }
    }

    private fun armThrough(
        mechanism: DeliveryMechanism,
        slot: Int,
        trigger: Long,
        operation: PendingIntent,
    ) = when (mechanism) {
        DeliveryMechanism.SET_ALARM_CLOCK -> api.setAlarmClock(trigger, AlarmIntents.show(context, slot), operation)
        DeliveryMechanism.SET_AND_ALLOW_WHILE_IDLE -> api.setAndAllowWhileIdle(trigger, operation)
    }
}
