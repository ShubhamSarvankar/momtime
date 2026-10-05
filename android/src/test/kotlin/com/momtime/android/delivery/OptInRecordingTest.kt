package com.momtime.android.delivery

import android.content.Context
import com.momtime.android.settings.AndroidSettings
import com.momtime.android.store.ClockChangeRepository
import com.momtime.android.store.FireTelemetry
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Instant

/**
 * The opt in gates upload and nothing else (ARCHITECTURE.md section 5.10, ADR 0070): the local records are always kept,
 * because the reliability view reads them and they stay on the device. So the same fire, with the opt in off and on,
 * leaves the same telemetry row and the same events. And the fire records how many times the clock had been set, which
 * is what drift compares with the count at arming.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [36])
class OptInRecordingTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private data class Recorded(
        val telemetry: FireTelemetry,
        val events: List<Pair<EventType, String>>,
    )

    private fun recorded(optIn: Boolean): Recorded {
        val f = DeliveryFixture(context)
        try {
            f.arming.graph
                .get<AndroidSettings>()
                .setShareReliabilityOptIn(optIn)
            val a = f.due("a", Criticality.STANDARD, slot = 31)
            f.fire(a)
            return Recorded(f.telemetryOf("a").copy(eventId = "-"), f.arming.eventLog("a"))
        } finally {
            f.close()
        }
    }

    @Test
    fun `the same fire leaves the same records whether she opted in or not`() {
        val off = recorded(optIn = false)
        val on = recorded(optIn = true)

        assertTrue(
            "and it has the device state in it",
            off.telemetry.screenOn != null && off.telemetry.dozeState != null,
        )
        assertEquals(off.telemetry, on.telemetry)
        assertEquals(off.events, on.events)
        assertTrue(off.events.any { it.first == EventType.ALARM_FIRED })
    }

    @Test
    fun `the opt in is off until she turns it on`() {
        val f = DeliveryFixture(context)
        try {
            val settings = f.arming.graph.get<AndroidSettings>()
            assertFalse(settings.shareReliabilityOptIn())
            settings.setShareReliabilityOptIn(true)
            assertTrue(settings.shareReliabilityOptIn())
            settings.setShareReliabilityOptIn(false)
            assertFalse(settings.shareReliabilityOptIn())
        } finally {
            f.close()
        }
    }

    @Test
    fun `a fire records how many times the clock had been set`() {
        val f = DeliveryFixture(context)
        try {
            val clock = f.arming.graph.get<ClockChangeRepository>()
            val a = f.due("a", Criticality.STANDARD, slot = 31)
            clock.record(Instant.fromEpochMilliseconds(1))
            clock.record(Instant.fromEpochMilliseconds(2))

            f.fire(a)

            assertEquals(2L, f.telemetryOf("a").clockChanges)
        } finally {
            f.close()
        }
    }
}
