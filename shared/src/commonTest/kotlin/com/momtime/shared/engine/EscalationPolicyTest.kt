package com.momtime.shared.engine

import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.QuietHours
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EscalationPolicyTest {
    private val zone = TimeZone.of("Asia/Kolkata")

    private fun instantAt(
        hour: Int,
        minute: Int,
    ): kotlin.time.Instant = LocalDateTime(2026, 6, 15, hour, minute).toInstant(zone)

    private fun delivery(
        criticality: Criticality,
        at: kotlin.time.Instant,
        quietHours: QuietHours?,
        budgetExhausted: Boolean = false,
    ) = EscalationPolicy.resolveDelivery(criticality, at, zone, quietHours, budgetExhausted)

    // Golden scenario 10: budget exhaustion downgrades STANDARD while CRITICAL is untouched.
    @Test
    fun `budget exhaustion downgrades standard but never critical`() {
        val standard = delivery(Criticality.STANDARD, instantAt(14, 0), null, budgetExhausted = true)
        val critical = delivery(Criticality.CRITICAL, instantAt(14, 0), null, budgetExhausted = true)
        assertEquals(RungDelivery.SILENT_NOTIFICATION, standard)
        assertEquals(RungDelivery.RING, critical)
    }

    // Golden scenario 16: quiet hours mid-ladder — CRITICAL still rings, STANDARD defers.
    @Test
    fun `quiet hours defer standard but never critical`() {
        val quiet = QuietHours(LocalTime(22, 0), LocalTime(6, 0))

        val standard = delivery(Criticality.STANDARD, instantAt(23, 0), quiet)
        val critical = delivery(Criticality.CRITICAL, instantAt(23, 0), quiet)
        assertEquals(RungDelivery.SILENT_NOTIFICATION, standard)
        assertEquals(RungDelivery.RING, critical)
    }

    @Test
    fun `no quiet hours configured never defers`() {
        assertEquals(RungDelivery.RING, delivery(Criticality.STANDARD, instantAt(23, 0), null))
    }

    // STANDARD is the default criticality, so an off-by-one at a window edge decides whether most
    // doses ring. Start is inclusive and end is exclusive; both window shapes are checked at one
    // minute before start, start, one minute before end, and end.
    @Test
    fun `wrapping window boundaries - start inclusive, end exclusive`() {
        val quiet = QuietHours(LocalTime(22, 0), LocalTime(6, 0))
        for (criticality in listOf(Criticality.STANDARD, Criticality.GENTLE)) {
            assertEquals(RungDelivery.RING, delivery(criticality, instantAt(21, 59), quiet), "one minute before start")
            assertEquals(RungDelivery.SILENT_NOTIFICATION, delivery(criticality, instantAt(22, 0), quiet), "start")
            assertEquals(
                RungDelivery.SILENT_NOTIFICATION,
                delivery(criticality, instantAt(5, 59), quiet),
                "one before end",
            )
            assertEquals(RungDelivery.RING, delivery(criticality, instantAt(6, 0), quiet), "end")
        }
    }

    @Test
    fun `non-wrapping window boundaries - start inclusive, end exclusive`() {
        val quiet = QuietHours(LocalTime(13, 0), LocalTime(15, 0))
        for (criticality in listOf(Criticality.STANDARD, Criticality.GENTLE)) {
            assertEquals(RungDelivery.RING, delivery(criticality, instantAt(12, 59), quiet), "one minute before start")
            assertEquals(RungDelivery.SILENT_NOTIFICATION, delivery(criticality, instantAt(13, 0), quiet), "start")
            assertEquals(
                RungDelivery.SILENT_NOTIFICATION,
                delivery(criticality, instantAt(14, 59), quiet),
                "one before end",
            )
            assertEquals(RungDelivery.RING, delivery(criticality, instantAt(15, 0), quiet), "end")
        }
    }

    @Test
    fun `critical rings at every boundary of both window shapes`() {
        val windows =
            listOf(QuietHours(LocalTime(22, 0), LocalTime(6, 0)), QuietHours(LocalTime(13, 0), LocalTime(15, 0)))
        val times =
            listOf(
                instantAt(12, 59),
                instantAt(13, 0),
                instantAt(14, 59),
                instantAt(15, 0),
                instantAt(22, 0),
                instantAt(5, 59),
            )
        for (quiet in windows) {
            for (at in times) {
                assertEquals(RungDelivery.RING, delivery(Criticality.CRITICAL, at, quiet))
            }
        }
    }

    // Equal start and end is invalid at construction (ADR 0037): it would be ambiguous between a
    // full day and no window.
    @Test
    fun `quiet hours with equal start and end is rejected`() {
        assertFailsWith<IllegalArgumentException> { QuietHours(LocalTime(22, 0), LocalTime(22, 0)) }
        assertFailsWith<IllegalArgumentException> { QuietHours(LocalTime(0, 0), LocalTime(0, 0)) }
    }
}
