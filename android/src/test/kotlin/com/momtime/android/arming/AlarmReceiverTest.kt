package com.momtime.android.arming

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.momtime.android.MomTimeApplication
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The receiver (ADR 0053): not exported, reached only by an explicit component intent, and it always finishes
 * the broadcast it took with `goAsync()`, whatever the fire path does.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class AlarmReceiverTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)

    @After
    fun tearDown() {
        ArmingEntryPoint.provider = null
        fixture.close()
    }

    /**
     * Gives [receiver] the pending result the platform gives a receiver it dispatches, so `goAsync()` returns a
     * real one whose `finish()` can be observed. Called directly, a receiver has none.
     */
    private fun withPendingResult(receiver: BroadcastReceiver): BroadcastReceiver.PendingResult {
        val pending =
            ReflectionHelpers.callStaticMethod<BroadcastReceiver.PendingResult>(
                ShadowBroadcastPendingResult::class.java,
                "create",
                ClassParameter.from(Int::class.javaPrimitiveType, 0),
                ClassParameter.from(String::class.java, null),
                ClassParameter.from(Bundle::class.java, null),
                ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            )
        ReflectionHelpers.callInstanceMethod<Any>(
            receiver,
            "setPendingResult",
            ClassParameter.from(BroadcastReceiver.PendingResult::class.java, pending),
        )
        return pending
    }

    /** Delivers the intent of the armed alarm to a receiver and returns the receiver and its pending result. */
    private fun deliver(
        intent: Intent = shadowOf(fixture.operation(fixture.alarms().single())).savedIntent,
    ): Pair<BroadcastReceiver, BroadcastReceiver.PendingResult> {
        val receiver = AlarmReceiver()
        val pending = withPendingResult(receiver)
        shadowOf(receiver).onReceive(context, intent, AtomicBoolean())
        return receiver to pending
    }

    /** True once the broadcast the receiver took was finished. A receiver that never finishes times out. */
    private fun finished(pending: BroadcastReceiver.PendingResult): Boolean {
        val future = Shadow.extract<ShadowBroadcastPendingResult>(pending).future
        return try {
            future.get(5, TimeUnit.SECONDS)
            true
        } catch (_: TimeoutException) {
            false
        }
    }

    @Test
    fun `the receiver goes async, fires and finishes`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()
        ArmingEntryPoint.provider = { fixture.handler }

        val (receiver, pending) = deliver()

        assertTrue("the receiver must call goAsync()", shadowOf(receiver).wentAsync())
        assertTrue("finish() was not called", finished(pending))
        assertEquals("the fire path ran", 1, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
        assertEquals(1, fixture.delivery.delivered.size)
        assertEquals(
            "and armed the next rung",
            (t0 + 10.minutes).toEpochMilliseconds(),
            fixture.alarms().single().triggerAtMs,
        )
    }

    @Test
    fun `the receiver finishes when the fire path fails`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()
        ArmingEntryPoint.provider = { error("no graph") }

        val (_, pending) = deliver()

        assertTrue("finish() was not called after a failure", finished(pending))
        assertEquals("nothing was fired", 0, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
    }

    // An intent with no slot or rung reads as an unknown slot, and is handled like one.
    @Test
    fun `an intent with no extras is an unknown slot`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        ArmingEntryPoint.provider = { fixture.handler }

        val (_, pending) = deliver(Intent(AlarmIntents.ACTION_FIRE))

        assertTrue(finished(pending))
        assertEquals(0, fixture.eventsOf("a", EventType.ALARM_FIRED).size)
        assertEquals(1, fixture.alarms().size)
    }

    @Test
    fun `the receiver is not exported`() {
        val info = context.packageManager.getReceiverInfo(ComponentName(context, AlarmReceiver::class.java), 0)
        assertFalse("the alarm receiver must not be exported", info.exported)
    }

    @Test
    fun `the alarm intent is explicit and immutable`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0
        fixture.coordinator.ensureArmed()

        val operation = shadowOf(fixture.operation(fixture.alarms().single()))

        assertEquals(ComponentName(context, AlarmReceiver::class.java), operation.savedIntent.component)
        assertNull("no package-wide broadcast", operation.savedIntent.`package`)
        assertTrue(operation.isImmutable)
        assertNotNull(operation.savedIntent.action)
    }

    // The real application starts the graph and installs the fire path the receiver reads.
    @Test
    @Config(application = MomTimeApplication::class, sdk = [36])
    fun `the application installs the fire path`() {
        try {
            assertNotNull("the application must install the fire path", ArmingEntryPoint.provider)
            assertNotNull(GlobalContext.get().get<AlarmFireHandler>())
        } finally {
            stopKoin()
        }
    }
}
