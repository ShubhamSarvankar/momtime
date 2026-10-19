package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.QuietHours
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * What a rung does when it is delivered. [RING] is a ring grade interruption. [SILENT_NOTIFICATION] is a
 * notification with no sound, because of quiet hours or the interruption budget. [NOTIFICATION] is a plain
 * notification that may make its channel's sound and is not a ring: what a Gentle occurrence gets (ADR 0089).
 */
enum class RungDelivery { RING, SILENT_NOTIFICATION, NOTIFICATION }

/**
 * Criticality defaults, quiet hours, interruption budget (ARCHITECTURE.md sections 4.2 to 4.4), decided in this
 * order: CRITICAL rings, and is never budget limited and always overrides quiet hours (ADR 0012/0013), so it is
 * checked first and short-circuits everything else; quiet hours and a spent budget make the rung silent; a
 * GENTLE rung outside both is a notification and never a ring (ADR 0089); everything else rings. The
 * criticality given is the occurrence's own, never its template's (ADR 0079 item 6).
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

        if (criticality == Criticality.GENTLE) return RungDelivery.NOTIFICATION

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
