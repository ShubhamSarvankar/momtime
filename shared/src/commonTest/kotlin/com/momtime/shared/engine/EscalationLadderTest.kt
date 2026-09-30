package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EscalationLadderTest {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)

    @Test
    fun `critical ladder rings immediately and escalates to caregiver`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.CRITICAL)
        assertEquals(
            listOf(
                Channel.RING to t0,
                Channel.RING_REPEAT to t0 + 5.minutes,
                Channel.CAREGIVER_INFO to t0 + 10.minutes,
                Channel.CAREGIVER_URGENT to t0 + 20.minutes,
            ),
            ladder.map { it.channel to it.instant },
        )
    }

    @Test
    fun `standard ladder rings and repeats once, no caregiver escalation`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.STANDARD)
        assertEquals(
            listOf(Channel.RING to t0, Channel.RING_REPEAT to t0 + 10.minutes),
            ladder.map { it.channel to it.instant },
        )
    }

    @Test
    fun `gentle ladder is a single rung with no repeat`() {
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.GENTLE)
        assertEquals(listOf(Channel.RING to t0), ladder.map { it.channel to it.instant })
    }

    // Golden scenario 3 (shared-testable slice): DeliveryCapability is a platform concept that
    // must never leak into the domain ladder (invariant 5). A tier downgrade mid-schedule
    // changes which platform mechanism realises a rung; it must never change the ladder itself.
    @Test
    fun `ladder generation takes no DeliveryCapability parameter at all`() {
        // There is no tier argument to pass — this test exists to make that architectural fact
        // explicit and regression-proof, not just to assert output equality.
        val ladder = EscalationLadder.forOccurrence(t0, Criticality.CRITICAL)
        assertEquals(4, ladder.size)
    }
}
