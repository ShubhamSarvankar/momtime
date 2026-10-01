package com.momtime.shared.engine

import com.momtime.shared.domain.Channel
import com.momtime.shared.domain.Criticality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
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

    // Golden scenario 3's shared slice (no DeliveryCapability anywhere in the engine) is
    // CapabilityBoundaryTest in jvmTest.

    // Golden scenario 14, the part whose subject is shared code. The ladder is a pure function of
    // (scheduledInstant, criticality) that emits absolute instants, so a device clock moved backward
    // cannot change an already-computed ladder, and no rung can come out before the one it follows
    // (a negative delay). The AlarmManager arming arithmetic is Android and is a Phase 2 exit
    // criterion. Translation invariance is what shows there is no hidden clock input: shifting the
    // scheduled instant shifts every rung by exactly the same amount.
    @Test
    fun `rungs are ordered, never before the scheduled instant, and shift exactly with it`() {
        val shift = 7.days
        for (criticality in Criticality.entries) {
            val ladder = EscalationLadder.forOccurrence(t0, criticality)
            assertEquals(t0, ladder.first().instant, "$criticality first rung")
            ladder.zipWithNext().forEach { (earlier, later) ->
                assertTrue(later.instant >= earlier.instant, "$criticality rung order")
            }
            assertTrue(ladder.all { it.instant >= t0 }, "$criticality rung before the scheduled instant")

            val shifted = EscalationLadder.forOccurrence(t0 + shift, criticality)
            assertEquals(ladder.map { it.channel }, shifted.map { it.channel }, "$criticality channels")
            assertEquals(ladder.map { it.instant + shift }, shifted.map { it.instant }, "$criticality shift")
        }
    }
}
