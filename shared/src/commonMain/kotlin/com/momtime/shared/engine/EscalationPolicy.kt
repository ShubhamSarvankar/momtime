package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

enum class RungDelivery { RING, SILENT_NOTIFICATION }

/**
 * Criticality defaults, quiet hours, interruption budget (ARCHITECTURE.md sections 4.3/4.4).
 * CRITICAL is never budget limited and always overrides quiet hours (ADR 0012/0013) — this is
 * checked first and short-circuits both other conditions.
 */
object EscalationPolicy {
    fun resolveDelivery(
        criticality: Criticality,
        rungInstant: Instant,
        zone: TimeZone,
        quietHoursStart: LocalTime?,
        quietHoursEnd: LocalTime?,
        budgetExhausted: Boolean,
    ): RungDelivery {
        if (criticality == Criticality.CRITICAL) return RungDelivery.RING

        val inQuietHours =
            quietHoursStart != null &&
                quietHoursEnd != null &&
                isWithinQuietHours(rungInstant, zone, quietHoursStart, quietHoursEnd)
        if (inQuietHours) return RungDelivery.SILENT_NOTIFICATION

        if (budgetExhausted) return RungDelivery.SILENT_NOTIFICATION

        return RungDelivery.RING
    }

    /** Evaluated against current-zone wall clock (ADR 0032) — the window travels with her. */
    private fun isWithinQuietHours(
        instant: Instant,
        zone: TimeZone,
        start: LocalTime,
        end: LocalTime,
    ): Boolean {
        val time = instant.toLocalDateTime(zone).time
        return if (start <= end) {
            time >= start && time < end
        } else {
            // Wraps midnight, e.g. 22:00-06:00.
            time >= start || time < end
        }
    }
}
