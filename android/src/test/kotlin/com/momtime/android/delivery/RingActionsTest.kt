package com.momtime.android.delivery

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.arming.AlarmReceiver
import com.momtime.android.ring.RingActivity
import com.momtime.android.ring.RingEntryPoint
import com.momtime.android.ring.RingSessions
import com.momtime.shared.data.ActionResult
import com.momtime.shared.data.OccurrenceActionCommand
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.QuietHours
import com.momtime.shared.engine.OccurrenceAction
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.minutes

/**
 * Acknowledge, snooze and skip (ADR 0066), from the ring screen and from a notification's buttons, through the real
 * controller, the domain's command, the real database and the one `ensureArmed` entry point. Each test reads the
 * exact event log and the exact state before and after, so "and nothing else" can fail: a stray event is a
 * difference. This closes the plan's Robolectric item "acknowledge, snooze and skip from the ring screen each
 * write the correct event and nothing else".
 *
 * Test names are short on purpose: Robolectric names its data directory after the class and the method.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 33, 36])
class RingActionsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val controller: RingController get() = f.arming.graph.get()
    private val sessions: RingSessions get() = f.arming.graph.get()

    @Before
    fun setUp() {
        RingEntryPoint.provider = { controller }
        RingEntryPoint.executor = Executor { it.run() }
        NotificationActionEntryPoint.provider = { controller }
    }

    @After
    fun tearDown() {
        RingEntryPoint.provider = null
        NotificationActionEntryPoint.provider = null
        f.close()
    }

    private fun state(id: String): OccurrenceState? =
        f.arming.occurrences
            .findById(id)
            ?.state

    /** The events written since [before], sorted, as (type, occurrence). */
    private fun delta(
        before: List<Pair<EventType, String>>,
        vararg ids: String,
    ) = f.arming.eventDelta(*ids, before = before)

    private fun ringing(
        id: String,
        criticality: Criticality = Criticality.CRITICAL,
        slot: Int = 31,
    ): Occurrence = f.due(id, criticality, slot = slot).also { f.fire(it) }

    // --- from the ring screen: the exact event and state delta of each action

    // The fire has written ALARM_FIRED and armed the next rung. Acknowledging writes COMPLETED and nothing else:
    // nothing is pending after it, so ensureArmed cancels the alarm and writes no event.
    @Test
    fun `acknowledge writes COMPLETED and nothing else`() {
        val a = ringing("a")
        val before = f.arming.eventLog("a")

        controller.act("a", OccurrenceAction.ACKNOWLEDGE)

        assertEquals(listOf(EventType.COMPLETED to "a"), delta(before, "a"))
        assertEquals(OccurrenceState.COMPLETED, state("a"))
        assertEquals("ensureArmed ran: nothing is pending, so no alarm", 0, f.arming.alarms().size)
        assertEquals(1, f.arming.eventsOf("a", EventType.COMPLETED).size)
        assertEquals(
            clockNow(),
            f.arming
                .eventsOf("a", EventType.COMPLETED)
                .single()
                .deviceTimestamp,
        )
        assertEquals(a.scheduledInstant, clockNow())
    }

    @Test
    fun `skip writes SKIPPED and nothing else`() {
        ringing("a")
        val before = f.arming.eventLog("a")

        controller.act("a", OccurrenceAction.SKIP)

        assertEquals(listOf(EventType.SKIPPED to "a"), delta(before, "a"))
        assertEquals(OccurrenceState.SKIPPED, state("a"))
        assertEquals("skip is not completion", 0, f.arming.count(EventType.COMPLETED, "a"))
        assertEquals(0, f.arming.alarms().size)
    }

    // The one other thing a snooze causes is ensureArmed arming the snooze's end, which is a new head for the
    // armed alarm, so ALARM_SCHEDULED is appended (decision 6). It is not an ALARM_FIRED.
    @Test
    fun `snooze writes SNOOZED, arms its end and nothing else`() {
        ringing("a")
        val before = f.arming.eventLog("a")

        controller.act("a", OccurrenceAction.SNOOZE)

        assertEquals(
            listOf(EventType.ALARM_SCHEDULED to "a", EventType.SNOOZED to "a"),
            delta(before, "a"),
        )
        assertEquals(OccurrenceState.SNOOZED, state("a"))
        val snoozed = f.arming.eventsOf("a", EventType.SNOOZED).single()
        assertEquals(
            com.momtime.shared.domain.EventPayload
                .Snooze(1, clockNow() + 10.minutes),
            snoozed.payload,
        )
        val alarm = f.arming.alarms().single()
        assertEquals((clockNow() + 10.minutes).toEpochMilliseconds(), alarm.triggerAtMs)
        assertEquals("the request code is the slot", 31, f.arming.requestCode(alarm))
        assertEquals("no rung was consumed", 1, f.arming.count(EventType.ALARM_FIRED, "a"))
    }

    private fun clockNow() = f.arming.clock.now

    // Each action takes the occurrence out of the session, and the last one ends it: the sound stops.
    @Test
    fun `acting on the only occurrence ends the session and stops the ringer`() {
        ringing("a")
        assertTrue(sessions.isActive)

        controller.act("a", OccurrenceAction.ACKNOWLEDGE)

        assertFalse("the session ended", sessions.isActive)
        assertEquals("the ringer was stopped", 1, f.ringer.stops)
        assertTrue("nothing is left listed", sessions.items().isEmpty())
        assertNull("the ring notification is gone", f.posted(RingNotifications.RING_ID))
    }

    // --- several occurrences: each has its own actions, and acting on one leaves the others ringing

    @Test
    fun `acting on one leaves the other ringing`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        val b = f.due("b", Criticality.STANDARD, slot = 32)
        f.fire(a)
        f.fire(b)
        assertEquals(listOf("a", "b"), sessions.items().map { it.occurrenceId })
        assertEquals(
            "each occurrence has its own actions",
            listOf(true, true),
            sessions.items().map { it.actions.size == 3 },
        )
        val beforeB = f.arming.eventLog("b")

        controller.act("a", OccurrenceAction.ACKNOWLEDGE)

        assertEquals(OccurrenceState.COMPLETED, state("a"))
        assertEquals("b is untouched", OccurrenceState.PENDING, state("b"))
        assertEquals(
            "b has no new event of its own",
            beforeB.filter { it.first != EventType.ALARM_SCHEDULED },
            f.arming.eventLog("b").filter {
                it.first !=
                    EventType.ALARM_SCHEDULED
            },
        )
        assertEquals(0, f.arming.count(EventType.COMPLETED, "b"))
        assertTrue("the session is still going", sessions.isActive)
        assertTrue("b is still ringing", sessions.isRinging("b"))
        assertFalse("a is not", sessions.isRinging("a"))
        assertEquals(0, f.ringer.stops)
        assertEquals("only b is listed now", listOf("b"), sessions.items().map { it.occurrenceId })
        val notification = checkNotNull(f.posted(RingNotifications.RING_ID))
        assertEquals(
            "the ring notification now names b",
            "Iron tablet",
            notification.extras.getString(Notification.EXTRA_TITLE),
        )

        controller.act("b", OccurrenceAction.SKIP)

        assertFalse("now nothing is left unacknowledged", sessions.isActive)
        assertEquals(1, f.ringer.stops)
        assertEquals(OccurrenceState.SKIPPED, state("b"))
    }

    // --- an action that is not available is unavailable on the screen and refused if sent anyway

    // A Standard occurrence: a Gentle one no longer rings (ADR 0089), and these cases are about the session.
    private fun snoozeThrice(id: String) {
        f.due(id, Criticality.STANDARD, slot = 31)
        val a = f.arming.occurrences.findById(id)!!
        f.fire(a)
        var now = a.scheduledInstant
        repeat(3) {
            controller.act(id, OccurrenceAction.SNOOZE)
            now += 10.minutes
            f.fire(a, now = now, rung = now)
        }
    }

    @Test
    fun `the fourth snooze is not offered and is refused`() {
        snoozeThrice("a")
        assertEquals(3, f.arming.count(EventType.SNOOZED, "a"))
        val item = sessions.items().single()
        assertFalse("snooze is not offered", OccurrenceAction.SNOOZE in item.actions)
        assertTrue(OccurrenceAction.ACKNOWLEDGE in item.actions && OccurrenceAction.SKIP in item.actions)
        val before = f.arming.eventLog("a")

        controller.act("a", OccurrenceAction.SNOOZE)

        assertEquals("a refused snooze writes nothing", before, f.arming.eventLog("a"))
        assertEquals(OccurrenceState.SNOOZED, state("a"))
        assertEquals(3, f.arming.count(EventType.SNOOZED, "a"))
        val command: OccurrenceActionCommand = f.arming.graph.get()
        assertEquals(ActionResult.NotAvailable, command.dispatch("a", OccurrenceAction.SNOOZE, clockNow()))
        assertTrue("the occurrence is still listed and ringing", sessions.isRinging("a"))
    }

    @Test
    fun `the screen offers no snooze button and says why`() {
        snoozeThrice("a")

        val activity =
            Robolectric
                .buildActivity(RingActivity::class.java)
                .create()
                .start()
                .resume()
                .visible()
                .get()

        assertNull("no snooze button", activity.window.decorView.findViewWithTag<Button>("snooze:a"))
        assertNotNull(activity.window.decorView.findViewWithTag<Button>("ack:a"))
        assertNotNull(activity.window.decorView.findViewWithTag<Button>("skip:a"))
        val texts = allTexts(activity.window.decorView)
        assertTrue(activity.getString(R.string.ring_snooze_unavailable) in texts)
    }

    // Golden scenario 4 on the screen: a snooze that would reach the next occurrence of the same template is not
    // offered. The second occurrence is five minutes later, and a snooze is ten.
    @Test
    fun `a snooze past the next occurrence is not offered`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.arming.occurrences.insert(
            a.copy(
                id = "a2",
                localDate = a.localDate.let { kotlinx.datetime.LocalDate(2026, 3, 2) },
                scheduledInstant =
                    a.scheduledInstant + 5.minutes,
                alarmSlot = 32,
            ),
        )
        f.fire(a)
        val before = f.arming.eventLog("a")

        assertFalse(OccurrenceAction.SNOOZE in sessions.items().single().actions)
        controller.act("a", OccurrenceAction.SNOOZE)

        assertEquals("nothing was written", before, f.arming.eventLog("a"))
        assertEquals(OccurrenceState.PENDING, state("a"))
    }

    @Test
    fun `a snooze that fits before the next occurrence is offered`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.arming.occurrences.insert(
            a.copy(
                id = "a2",
                localDate = kotlinx.datetime.LocalDate(2026, 3, 2),
                scheduledInstant =
                    a.scheduledInstant + 11.minutes,
                alarmSlot = 32,
            ),
        )
        f.fire(a)

        assertTrue(OccurrenceAction.SNOOZE in sessions.items().single().actions)
    }

    private fun allTexts(root: android.view.View): List<String> {
        val all = mutableListOf<String>()

        fun walk(view: android.view.View) {
            if (view is TextView) all += view.text.toString()
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(root)
        return all
    }

    // --- the ring screen's buttons dispatch through the host

    @Test
    fun `the screen buttons write the events`() {
        ringing("a", Criticality.CRITICAL, 31)
        val activity =
            Robolectric
                .buildActivity(RingActivity::class.java)
                .create()
                .start()
                .resume()
                .visible()
                .get()
        val before = f.arming.eventLog("a")

        activity.window.decorView
            .findViewWithTag<Button>("ack:a")
            .performClick()

        assertEquals(listOf(EventType.COMPLETED to "a"), delta(before, "a"))
        assertEquals(OccurrenceState.COMPLETED, state("a"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull("the screen no longer lists it", activity.window.decorView.findViewWithTag<Button>("ack:a"))
    }

    // --- opening the ring screen with no session starts nothing and writes nothing

    @Test
    fun `opening the screen with no session starts no sound and writes nothing`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        val before = f.arming.eventLog("a")
        val states = state("a")

        val activity =
            Robolectric
                .buildActivity(RingActivity::class.java)
                .create()
                .start()
                .resume()
                .visible()
                .get()
        // The alarm clock's show intent opens it too.
        Robolectric
            .buildActivity(RingActivity::class.java, RingActivity.intent(context))
            .create()
            .start()
            .resume()

        assertEquals(0, f.sound.starts)
        assertEquals(0, f.ringer.starts.size)
        assertEquals(0, f.vibration.starts.size)
        assertEquals(before, f.arming.eventLog("a"))
        assertEquals(states, state("a"))
        assertEquals(a.state, state("a"))
        assertEquals(
            activity.getString(R.string.ring_nothing_due),
            activity.findViewById<TextView>(R.id.ring_count).text.toString(),
        )
        assertFalse(sessions.isActive)
    }

    // --- notification buttons

    /** A reminder notification: quiet hours make a STANDARD rung a silent notification with the buttons on it. */
    private fun notified(
        id: String = "a",
        slot: Int = 31,
    ): Notification {
        f.settings.updateQuietHours(QuietHours(LocalTime(7, 0), LocalTime(9, 0)))
        f.fire(f.due(id, Criticality.STANDARD, slot = slot))
        return checkNotNull(f.posted(slot))
    }

    private fun button(
        notification: Notification,
        label: Int,
    ): Notification.Action = notification.actions.single { it.title.toString() == context.getString(label) }

    private fun press(action: Notification.Action) {
        val pending = shadowOf(action.actionIntent)
        val done = deliverToActionReceiver(context, pending.savedIntent)
        assertTrue("the receiver did not finish", finished(done))
    }

    @Test
    fun `a notification button acknowledges, and writes only that`() {
        val n = notified()
        val before = f.arming.eventLog("a")

        press(button(n, R.string.ring_action_acknowledge))

        assertEquals(listOf(EventType.COMPLETED to "a"), delta(before, "a"))
        assertEquals(OccurrenceState.COMPLETED, state("a"))
        assertNull("the notification is cancelled", f.posted(31))
    }

    @Test
    fun `a notification button skips, and writes only that`() {
        val n = notified()
        val before = f.arming.eventLog("a")

        press(button(n, R.string.ring_action_skip))

        assertEquals(listOf(EventType.SKIPPED to "a"), delta(before, "a"))
        assertEquals(OccurrenceState.SKIPPED, state("a"))
        assertNull(f.posted(31))
    }

    // A STANDARD ladder's next rung is ten minutes in, which is also when a ten minute snooze ends, so the armed
    // alarm keeps its trigger time and ensureArmed writes no ALARM_SCHEDULED: the delta is the SNOOZED event alone.
    @Test
    fun `a notification button snoozes, and the snooze is armed`() {
        val n = notified()
        val before = f.arming.eventLog("a")

        press(button(n, R.string.ring_action_snooze))

        assertEquals(listOf(EventType.SNOOZED to "a"), delta(before, "a"))
        assertEquals(OccurrenceState.SNOOZED, state("a"))
        assertEquals(
            (clockNow() + 10.minutes).toEpochMilliseconds(),
            f.arming
                .alarms()
                .single()
                .triggerAtMs,
        )
    }

    // --- dismissal is not completion

    // Swiping a notification away fires its delete intent, if it has one. It has none, and a test sends it if it
    // does: whatever the swipe does, it writes nothing, the occurrence keeps its state, and the next rung fires.
    @Test
    fun `swiping a notification away writes nothing and the next rung fires`() {
        val n = notified()
        val before = f.arming.eventLog("a")

        n.deleteIntent?.let { pending ->
            val done = deliverToActionReceiver(context, shadowOf(pending).savedIntent)
            assertTrue(finished(done))
        }
        f.notifications.cancel(31)

        assertEquals("a swipe writes no event", before, f.arming.eventLog("a"))
        assertEquals(OccurrenceState.PENDING, state("a"))
        // The next rung (STANDARD: ten minutes in) is still the armed one and still fires.
        val alarm = f.arming.alarms().single()
        assertEquals((clockNow() + 10.minutes).toEpochMilliseconds(), alarm.triggerAtMs)
        val firedBefore = f.arming.count(EventType.ALARM_FIRED, "a")
        f.arming.clock.now = clockNow() + 10.minutes
        f.arming.handler.onFire(31, clockNow())
        assertEquals(firedBefore + 1, f.arming.count(EventType.ALARM_FIRED, "a"))
    }

    // The same for the ring notification while it rings: it is ongoing, has no delete intent, and stopping the
    // sound writes nothing either.
    @Test
    fun `the ring notification has no delete intent`() {
        ringing("a")

        val notification = RingNotifications.ring(context, sessions.items(), NotificationChannels.CRITICAL, true)

        assertNull(notification.deleteIntent)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("one occurrence's buttons are on it", 3, notification.actions.size)
        for (action in notification.actions) assertTrue(shadowOf(action.actionIntent).isImmutable)
    }

    // With several occurrences the screen has each one's own buttons, and the shared notification has none.
    @Test
    fun `a notification for several occurrences has no buttons`() {
        f.fire(f.due("a", Criticality.CRITICAL, slot = 31))
        f.fire(f.due("b", Criticality.CRITICAL, slot = 32))

        val notification = RingNotifications.ring(context, sessions.items(), NotificationChannels.CRITICAL, true)

        assertTrue(notification.actions.isNullOrEmpty())
    }

    // --- the PendingIntents behind the buttons: immutable, explicit, not exported

    @Test
    fun `the button intents are immutable, explicit and reach an unexported receiver`() {
        val n = notified()

        assertEquals("three buttons", 3, n.actions.size)
        for (action in n.actions) {
            val pending = shadowOf(action.actionIntent)
            assertTrue("a broadcast", pending.isBroadcastIntent)
            assertTrue("immutable: ${action.title}", pending.isImmutable)
            val intent = pending.savedIntent
            assertEquals(ComponentName(context, RingActionReceiver::class.java), intent.component)
            assertNull("no package wide broadcast", intent.`package`)
            assertNotNull(intent.action)
        }
        val info = context.packageManager.getReceiverInfo(ComponentName(context, RingActionReceiver::class.java), 0)
        assertFalse("the receiver must not be exported", info.exported)
    }

    // Request codes derive from the slot (invariant 10), never collide between actions or occurrences, and are not
    // the alarm's.
    @Test
    fun `the button request codes come from the slot and never collide`() {
        val n = notified("a", 31)
        val codes = n.actions.map { shadowOf(it.actionIntent).requestCode }
        assertEquals(OccurrenceAction.entries.map { RingActionIntents.requestCode(31, it) }.sorted(), codes.sorted())
        val other = OccurrenceAction.entries.map { RingActionIntents.requestCode(32, it) }
        assertTrue("two occurrences never share a code", (codes intersect other.toSet()).isEmpty())
        assertEquals("three distinct codes", 3, codes.toSet().size)
        for (action in n.actions) {
            assertTrue(
                "not the alarm receiver",
                shadowOf(
                    action.actionIntent,
                ).savedIntent.component != ComponentName(context, AlarmReceiver::class.java),
            )
        }
    }

    // A notification button names its occurrence by slot. An unknown slot and a stray intent do nothing.
    @Test
    fun `a stale or stray button does nothing`() {
        val n = notified()
        val before = f.arming.eventLog("a")
        val stale = shadowOf(button(n, R.string.ring_action_acknowledge).actionIntent).savedIntent

        press(button(n, R.string.ring_action_skip))
        val afterSkip = f.arming.eventLog("a")
        assertTrue("the skip was taken", afterSkip.size > before.size)
        // The occurrence is skipped now: the stale acknowledge is refused and writes nothing.
        assertTrue(finished(deliverToActionReceiver(context, stale)))
        assertEquals(afterSkip, f.arming.eventLog("a"))
        assertEquals(OccurrenceState.SKIPPED, state("a"))
        // A stray intent names no action, and an unknown slot names no occurrence.
        assertTrue(finished(deliverToActionReceiver(context, Intent("nothing"))))
        assertTrue(
            finished(
                deliverToActionReceiver(context, Intent(stale).putExtra("com.momtime.android.extra.ACTION_SLOT", 999)),
            ),
        )
        assertEquals(afterSkip, f.arming.eventLog("a"))
    }
}
