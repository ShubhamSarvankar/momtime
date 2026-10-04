package com.momtime.shared.data

import com.momtime.shared.domain.Criticality
import com.momtime.shared.engine.EscalationPolicy
import com.momtime.shared.engine.RungDelivery
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * What a rung that is about to be delivered should do, decided in the domain and never by a platform
 * (ADR 0060, ARCHITECTURE.md sections 4.3 and 4.4): ring, or a silent notification because of quiet hours or
 * the interruption budget. It reads her settings and today's budget and applies [EscalationPolicy], which is
 * Phase 1's resolution, so android reimplements neither the quiet hours window nor the budget.
 *
 * `CRITICAL` is never budget limited and overrides quiet hours ([EscalationPolicy] checks it first). The
 * budget day is the local calendar date in [zone], the current zone (ADR 0032): the first decision of a new
 * day starts the count at zero. Quiet hours are evaluated against [zone] too, at the instant it is asked.
 *
 * [decide] changes nothing. [recordRing] is what spends the budget, and the caller calls it once for each
 * occurrence that begins to ring: a rung that continues a ring already in progress is not another
 * interruption (ADR 0062). Every ring grade interruption counts, `CRITICAL` included: the budget measures
 * how often the app interrupts her, and `CRITICAL` is exempt from being limited, not from being counted.
 */
class DeliveryPolicyCommand(
    private val settings: AppSettingsRepository,
    private val budget: InterruptionBudgetRepository,
    private val zone: () -> TimeZone,
) {
    /** The delivery for a rung of [criticality] at [now]. */
    fun decide(
        criticality: Criticality,
        now: Instant,
    ): RungDelivery {
        val current = settings.current()
        val exhausted = ringCountToday(now) >= current.ringGradeDailyBudget
        return EscalationPolicy.resolveDelivery(criticality, now, zone(), current.quietHours, exhausted)
    }

    /** Records one ring grade interruption for today. */
    fun recordRing(now: Instant) {
        ringCountToday(now)
        budget.increment()
    }

    /** Today's count, with the budget started or reset first if the local date has changed. */
    private fun ringCountToday(now: Instant): Int {
        val today = now.toLocalDateTime(zone()).date
        budget.ensureSeeded(today)
        if (budget.current().budgetDate != today) budget.reset(today)
        return budget.current().ringCount
    }
}
