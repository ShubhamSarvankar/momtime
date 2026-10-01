package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.QuietHours
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
        quietHours: QuietHours?,
        budgetExhausted: Boolean,
    ): RungDelivery {
        if (criticality == Criticality.CRITICAL) return RungDelivery.RING

        val inQuietHours = quietHours != null && isWithinQuietHours(rungInstant, zone, quietHours)
        if (inQuietHours) return RungDelivery.SILENT_NOTIFICATION

        if (budgetExhausted) return RungDelivery.SILENT_NOTIFICATION

        return RungDelivery.RING
    }

    /** Evaluated against current-zone wall clock (ADR 0032) — the window travels with her. */
    private fun isWithinQuietHours(
        instant: Instant,
        zone: TimeZone,
        quietHours: QuietHours,
    ): Boolean {
        val time = instant.toLocalDateTime(zone).time
        val (start, end) = quietHours
        // Start is inclusive and end is exclusive. Start never equals end (QuietHours enforces it).
        return if (start < end) {
            time >= start && time < end
        } else {
            // Wraps midnight, e.g. 22:00-06:00.
            time >= start || time < end
        }
    }
}
