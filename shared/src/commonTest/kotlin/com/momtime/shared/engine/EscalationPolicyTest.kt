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
    // minute before start, start, one minute before end, and end. Outside the window STANDARD rings and
    // GENTLE is a notification (ADR 0089); inside, both are silent.
    private val outside =
        mapOf(
            Criticality.STANDARD to RungDelivery.RING,
            Criticality.GENTLE to RungDelivery.NOTIFICATION,
        )

    @Test
    fun `wrapping window boundaries - start inclusive, end exclusive`() {
        val quiet = QuietHours(LocalTime(22, 0), LocalTime(6, 0))
        for ((criticality, open) in outside) {
            assertEquals(open, delivery(criticality, instantAt(21, 59), quiet), "one minute before start")
            assertEquals(RungDelivery.SILENT_NOTIFICATION, delivery(criticality, instantAt(22, 0), quiet), "start")
            assertEquals(
                RungDelivery.SILENT_NOTIFICATION,
                delivery(criticality, instantAt(5, 59), quiet),
                "one before end",
            )
            assertEquals(open, delivery(criticality, instantAt(6, 0), quiet), "end")
        }
    }

    @Test
    fun `non-wrapping window boundaries - start inclusive, end exclusive`() {
        val quiet = QuietHours(LocalTime(13, 0), LocalTime(15, 0))
        for ((criticality, open) in outside) {
            assertEquals(open, delivery(criticality, instantAt(12, 59), quiet), "one minute before start")
            assertEquals(RungDelivery.SILENT_NOTIFICATION, delivery(criticality, instantAt(13, 0), quiet), "start")
            assertEquals(
                RungDelivery.SILENT_NOTIFICATION,
                delivery(criticality, instantAt(14, 59), quiet),
                "one before end",
            )
            assertEquals(open, delivery(criticality, instantAt(15, 0), quiet), "end")
        }
    }

    // ADR 0089: the whole table of criticality by quiet hours by budget. NOTIFICATION appears in exactly one
    // cell, Gentle outside quiet hours with budget left; every other cell is what Phase 1 decided. The order of
    // the checks is what the Gentle cells pin: a spent budget or quiet hours silence a Gentle rung before its
    // criticality makes it a notification.
    @Test
    fun `a gentle rung is a notification only outside quiet hours and with budget left`() {
        val quiet = QuietHours(LocalTime(22, 0), LocalTime(6, 0))
        val inside = instantAt(23, 0)
        val outsideWindow = instantAt(14, 0)
        val expected =
            mapOf(
                Triple(Criticality.CRITICAL, false, false) to RungDelivery.RING,
                Triple(Criticality.CRITICAL, true, false) to RungDelivery.RING,
                Triple(Criticality.CRITICAL, false, true) to RungDelivery.RING,
                Triple(Criticality.CRITICAL, true, true) to RungDelivery.RING,
                Triple(Criticality.STANDARD, false, false) to RungDelivery.RING,
                Triple(Criticality.STANDARD, true, false) to RungDelivery.SILENT_NOTIFICATION,
                Triple(Criticality.STANDARD, false, true) to RungDelivery.SILENT_NOTIFICATION,
                Triple(Criticality.STANDARD, true, true) to RungDelivery.SILENT_NOTIFICATION,
                Triple(Criticality.GENTLE, false, false) to RungDelivery.NOTIFICATION,
                Triple(Criticality.GENTLE, true, false) to RungDelivery.SILENT_NOTIFICATION,
                Triple(Criticality.GENTLE, false, true) to RungDelivery.SILENT_NOTIFICATION,
                Triple(Criticality.GENTLE, true, true) to RungDelivery.SILENT_NOTIFICATION,
            )
        assertEquals(Criticality.entries.size * 4, expected.size, "every cell of the table")
        for ((cell, answer) in expected) {
            val (criticality, inQuietHours, budgetSpent) = cell
            assertEquals(
                answer,
                delivery(
                    criticality,
                    if (inQuietHours) inside else outsideWindow,
                    quiet,
                    budgetExhausted = budgetSpent,
                ),
                "$criticality, quiet hours $inQuietHours, budget spent $budgetSpent",
            )
        }
        assertEquals(
            listOf(Triple(Criticality.GENTLE, false, false)),
            expected.filterValues { it == RungDelivery.NOTIFICATION }.keys.toList(),
            "NOTIFICATION in exactly one cell",
        )
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
