package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

class EscalationPolicyTest {
    private val zone = TimeZone.of("Asia/Kolkata")

    private fun instantAt(
        hour: Int,
        minute: Int,
    ): kotlin.time.Instant = LocalDateTime(2026, 6, 15, hour, minute).toInstant(zone)

    // Golden scenario 10: budget exhaustion downgrades STANDARD while CRITICAL is untouched.
    @Test
    fun `budget exhaustion downgrades standard but never critical`() {
        val standard =
            EscalationPolicy.resolveDelivery(
                criticality = Criticality.STANDARD,
                rungInstant = instantAt(14, 0),
                zone = zone,
                quietHoursStart = null,
                quietHoursEnd = null,
                budgetExhausted = true,
            )
        val critical =
            EscalationPolicy.resolveDelivery(
                criticality = Criticality.CRITICAL,
                rungInstant = instantAt(14, 0),
                zone = zone,
                quietHoursStart = null,
                quietHoursEnd = null,
                budgetExhausted = true,
            )
        assertEquals(RungDelivery.SILENT_NOTIFICATION, standard)
        assertEquals(RungDelivery.RING, critical)
    }

    // Golden scenario 16: quiet hours mid-ladder — CRITICAL still rings, STANDARD defers.
    @Test
    fun `quiet hours defer standard but never critical`() {
        val quietStart = LocalTime(22, 0)
        val quietEnd = LocalTime(6, 0)

        val standard =
            EscalationPolicy.resolveDelivery(
                criticality = Criticality.STANDARD,
                rungInstant = instantAt(23, 0),
                zone = zone,
                quietHoursStart = quietStart,
                quietHoursEnd = quietEnd,
                budgetExhausted = false,
            )
        val critical =
            EscalationPolicy.resolveDelivery(
                criticality = Criticality.CRITICAL,
                rungInstant = instantAt(23, 0),
                zone = zone,
                quietHoursStart = quietStart,
                quietHoursEnd = quietEnd,
                budgetExhausted = false,
            )
        assertEquals(RungDelivery.SILENT_NOTIFICATION, standard)
        assertEquals(RungDelivery.RING, critical)
    }

    @Test
    fun `quiet hours window wraps midnight correctly`() {
        val quietStart = LocalTime(22, 0)
        val quietEnd = LocalTime(6, 0)

        // 5:59am is still within the wrapped window; 6:01am is not.
        val stillQuiet =
            EscalationPolicy.resolveDelivery(
                Criticality.STANDARD,
                instantAt(5, 59),
                zone,
                quietStart,
                quietEnd,
                budgetExhausted = false,
            )
        val noLongerQuiet =
            EscalationPolicy.resolveDelivery(
                Criticality.STANDARD,
                instantAt(6, 1),
                zone,
                quietStart,
                quietEnd,
                budgetExhausted = false,
            )
        assertEquals(RungDelivery.SILENT_NOTIFICATION, stillQuiet)
        assertEquals(RungDelivery.RING, noLongerQuiet)
    }

    @Test
    fun `no quiet hours configured never defers`() {
        val result =
            EscalationPolicy.resolveDelivery(
                Criticality.STANDARD,
                instantAt(23, 0),
                zone,
                quietHoursStart = null,
                quietHoursEnd = null,
                budgetExhausted = false,
            )
        assertEquals(RungDelivery.RING, result)
    }
}
