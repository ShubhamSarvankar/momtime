package com.momtime.android.delivery

import android.app.Notification
import android.content.ComponentName
import com.momtime.android.R
import com.momtime.android.arming.t0
import com.momtime.android.ringer.RingPolicy
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.QuietHours
import com.momtime.shared.engine.OccurrenceAction
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Each ringing occurrence has its own notification when several are due (ADR 0080; `docs/phase-3-plan.md`, PR 6):
 * the spread, the invariant along every path, the buttons, a refused action and a slot that already had a
 * notification, through the real fire path, the real delivery port over a real database, the real controller, the
 * real action receiver and the shadowed platform, at SDK 29 and 36 (the first test at 29, 33, 34 and 36, Tier 2 and
 * Tier 3). The rest of ADR 0080 (a swipe, a process death, an edit, a tap) is `SpreadRefreshTest`; what the two
 * share is `SpreadSupport`. Nothing here ran on a device: what the per occurrence notifications look and sound like
 * beside the ringer, and what the system's grouping does to them, are `MANUAL_CHECKS.md` P3-2 to P3-5.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
internal class SpreadTest : SpreadSupport() {
    /** The next occurrence of [a]'s template, five minutes on: a ten minute snooze of [a] would reach it. */
    private fun nextOf(a: Occurrence): Occurrence =
        a.copy(id = "a2", localDate = LocalDate(2026, 3, 2), scheduledInstant = t0 + 5.minutes, alarmSlot = 33)

    /**
     * The events written since [before] over a, b and c, less the `ALARM_SCHEDULED` the action's `ensureArmed`
     * writes when it arms another occurrence's rung or the snooze's end (ADR 0066: "then `ensureArmed` runs").
     */
    private fun herEvents(before: List<Pair<EventType, String>>): List<Pair<EventType, String>> =
        f.arming.eventDelta("a", "b", "c", before = before).filter { it.first != EventType.ALARM_SCHEDULED }

    // ---------------------------------------------------------------- one occurrence is unchanged

    // ADR 0080 item 2: with one occurrence the session's notification, the one the ringer service holds, carries its
    // three actions, and nothing is posted under its slot. Tier 3 and Tier 2, at every SDK where full screen intent
    // can differ.
    @Test
    @Config(sdk = [29, 33, 34, 36])
    fun `one occurrence keeps its buttons on the session notification`() {
        for (fullScreen in listOf(true, false)) {
            val r = Real()
            r.f.fullScreenIntent(fullScreen)
            val a = r.f.due("a", Criticality.CRITICAL, slot = 31)

            r.f.fire(a)

            val label = "fullScreen=$fullScreen"
            assertEquals("$label: the tier", if (fullScreen) "RING" else "HEADS_UP", r.f.telemetryOf("a").deliveryPath)
            val session = session(r.f, "$label: the service holds the ring notification")
            assertEquals("$label: the three actions", threeActions, labels(session))
            assertNull("$label: no notification under the slot", r.f.posted(31))
            assertEquals("$label: one notification", 1, count(r.f))
            assertFalse("$label: the session has not spread", r.sessions.isSpread)
            assertInvariant("one occurrence, $label", r.f)
            r.controller.act("a", OccurrenceAction.ACKNOWLEDGE)
            r.ringer.destroyAll()
        }
    }

    // ---------------------------------------------------------------- the spread

    // ADR 0080 item 3: the second occurrence spreads the session. The session's notification is rebuilt with no
    // buttons and only alert once, and keeps the full screen intent in Tier 3; each occurrence's own notification is
    // under its slot, on its criticality's channel, with exactly its offered actions as immutable explicit intents
    // to the unexported receiver, with no full screen intent and no delete intent. The second fire is the step that
    // would put a full screen intent or a delete intent on a per occurrence notification if the shape were wrong.
    @Test
    fun `a second occurrence spreads the session`() {
        val r = Real()
        val a = r.f.due("a", Criticality.CRITICAL, slot = 31)
        val b = r.f.due("b", Criticality.STANDARD, slot = 32)
        r.f.fire(a)
        assertEquals(threeActions, labels(checkNotNull(r.f.posted(RingNotifications.RING_ID))))

        r.f.fire(b)

        assertTrue("the session has spread", r.sessions.isSpread)
        assertEquals("one ringer", 1, r.ringer.starts.size)
        assertNotNull("the sound is on", r.playing())
        val session = checkNotNull(r.f.posted(RingNotifications.RING_ID)) { "the session's notification is posted" }
        assertTrue("the session's notification has no actions: ${labels(session)}", labels(session).isEmpty())
        assertTrue("only alert once", hasFlag(session, Notification.FLAG_ONLY_ALERT_ONCE))
        assertTrue("still ongoing", hasFlag(session, Notification.FLAG_ONGOING_EVENT))
        assertNotNull("it keeps the full screen intent in Tier 3", session.fullScreenIntent)
        val twoDue = context.resources.getQuantityString(R.plurals.notification_due_title, 2, 2)
        assertEquals("it says two are due", twoDue, title(session))
        val own = postedUnder(r.f, 31, "a's own notification is posted at the spread")
        val other = postedUnder(r.f, 32, "b's own notification is posted at the spread")
        assertOwn(own, NotificationChannels.CRITICAL, threeActions, "a")
        assertOwn(other, NotificationChannels.STANDARD, threeActions, "b")
        assertEquals("a's own names a", "Iron tablet", title(own))
        assertEquals("exactly three notifications", 3, count(r.f))
        val info = context.packageManager.getReceiverInfo(ComponentName(context, RingActionReceiver::class.java), 0)
        assertFalse("the receiver is not exported", info.exported)
        assertInvariant("after the spread", r.f)
    }

    // ---------------------------------------------------------------- the invariant along every path

    /** One run of a row of the table: its fixture, the slots resolved so far, and the invariant after each step. */
    private inner class Run(
        val name: String,
    ) {
        val r = Real()
        val resolved = mutableSetOf<Int>()

        fun check(step: String) = assertInvariant("$name, after $step", r.f, resolved)

        fun own(slot: Int): Notification = checkNotNull(r.f.posted(slot)) { "$name: no notification under $slot" }

        fun sessionLabels(): List<String> = labels(own(RingNotifications.RING_ID))

        fun sessionOngoing(): Boolean = hasFlag(own(RingNotifications.RING_ID), Notification.FLAG_ONGOING_EVENT)

        fun press(
            slot: Int,
            label: Int,
        ) {
            press(own(slot), label)
            resolved += slot
        }
    }

    private class Row(
        val name: String,
        val run: Run.() -> Unit,
    )

    @Test
    @Suppress("LongMethod")
    fun `the invariant holds along every path`() {
        val rows =
            listOf(
                Row("a join") {
                    r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))
                    check("the first fire")
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    check("the second fire")
                    assertEquals(3, count(r.f))
                },
                Row("act on each") {
                    r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    press(31, R.string.ring_action_acknowledge)
                    check("Taken on a's own notification")
                    assertEquals(OccurrenceState.COMPLETED, r.state("a"))
                    assertTrue("the session's notification carries no actions while spread", sessionLabels().isEmpty())
                    r.controller.act("b", OccurrenceAction.SKIP)
                    resolved += 32
                    check("Skip on b from the screen")
                    assertEquals("nothing is posted", 0, count(r.f))
                },
                Row("a refusal") {
                    val a = r.f.due("a", Criticality.STANDARD, slot = 31)
                    r.f.fire(a)
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    val stale = own(31)
                    assertEquals(threeActions, labels(stale))
                    // The next occurrence of a's template, five minutes on: a ten minute snooze would reach it, so
                    // the domain no longer offers one (golden scenario 4), while a's notification still shows it.
                    r.f.arming.occurrences
                        .insert(nextOf(a))
                    val log = r.f.arming.eventLog("a", "b")
                    press(stale, R.string.ring_action_snooze)
                    assertEquals("a refused snooze writes nothing", log, r.f.arming.eventLog("a", "b"))
                    check("the refused snooze")
                    assertEquals("a's own notification offers no snooze now", twoActions, labels(own(31)))
                },
                Row("Stop the sound") {
                    r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    r.controller.stopSound()
                    assertFalse(r.sessions.isActive)
                    check("Stop the sound")
                    assertFalse("the session's notification is ordinary", sessionOngoing())
                    assertTrue("with no actions", sessionLabels().isEmpty())
                },
                Row("the last one acted on") {
                    r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    r.controller.act("a", OccurrenceAction.ACKNOWLEDGE)
                    resolved += 31
                    check("the first acted on")
                    r.controller.act("b", OccurrenceAction.ACKNOWLEDGE)
                    resolved += 32
                    check("the last acted on")
                    assertFalse(r.sessions.isActive)
                    assertFalse("the session is over, so it is not spread", r.sessions.isSpread)
                    assertEquals(0, count(r.f))
                },
                Row("a third joins after one left") {
                    r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    r.controller.act("a", OccurrenceAction.ACKNOWLEDGE)
                    resolved += 31
                    check("a acted on")
                    r.f.fire(r.f.due("c", Criticality.CRITICAL, slot = 33))
                    check("c joined")
                    assertEquals(listOf("b", "c"), r.sessions.items().map { it.occurrenceId })
                    assertTrue("still spread", sessionLabels().isEmpty())
                    assertEquals(3, count(r.f))
                },
                Row("the cap ends the sound") {
                    r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    val before = labels(own(31)) to labels(own(32))
                    r.scheduler.advance(RingPolicy.MAX_RING)
                    assertFalse("the cap ended the sound", r.sessions.isActive)
                    check("the cap")
                    assertEquals("every own notification stays as it was", before, labels(own(31)) to labels(own(32)))
                    assertFalse("the session's notification is ordinary", sessionOngoing())
                    assertTrue("with no actions", sessionLabels().isEmpty())
                },
                Row("the cap, then the next rung") {
                    val a = r.f.due("a", Criticality.CRITICAL, slot = 31)
                    r.f.fire(a)
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    r.scheduler.advance(RingPolicy.MAX_RING)
                    r.f.fire(a, now = t0 + 5.minutes, rung = t0 + 5.minutes)
                    assertTrue("a new ring", r.sessions.isActive)
                    assertEquals(2, r.ringer.starts.size)
                    check("the repeat after the cap")
                    assertEquals(
                        "no second notification for any occurrence: ${ids(r.f)}",
                        3,
                        count(r.f),
                    )
                    assertTrue("spread again from its first fire", sessionLabels().isEmpty())
                    assertTrue("the session's notification is ongoing again", sessionOngoing())
                },
                Row("a Gentle fire while the session rings") {
                    r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))
                    r.f.fire(r.f.due("b", Criticality.STANDARD, slot = 32))
                    val items = r.sessions.items()
                    r.f.fire(r.f.due("g", Criticality.GENTLE, slot = 33))
                    check("the Gentle fire")
                    assertEquals("the session is as it was", items, r.sessions.items())
                    assertEquals(NotificationChannels.GENTLE, r.f.channelOf(33))
                    assertEquals("its own notification carries its actions", threeActions, labels(own(33)))
                    assertEquals(4, count(r.f))
                },
                Row("grace ends for one of two") {
                    val a = r.f.due("a", Criticality.CRITICAL, slot = 31)
                    r.f.fire(a)
                    r.f.fire(r.f.due("b", Criticality.STANDARD, scheduled = t0 + 110.minutes, slot = 32))
                    check("b joined")
                    // The step that would leave a's notification if the drop were wrong: the watchdog pass after a's
                    // grace (Critical: two hours), with b still inside its own.
                    r.f.arming.clock.now = t0 + 2.hours + 1.minutes
                    r.f.arming.watchdog
                        .run()
                    assertEquals(OccurrenceState.MISSED, r.state("a"))
                    resolved += 31
                    check("the watchdog pass after a's grace")
                    assertEquals(listOf("b"), r.sessions.items().map { it.occurrenceId })
                    assertNull("a's own notification is cancelled", r.f.posted(31))
                    assertEquals("b keeps its own", threeActions, labels(own(32)))
                    assertTrue("still spread", sessionLabels().isEmpty())
                },
            )
        for (row in rows) {
            val run = Run(row.name)
            row.run(run)
            run.r.ringer.destroyAll()
        }
    }

    // ---------------------------------------------------------------- a button on a spread notification

    // ADR 0066's delta from a per occurrence notification's buttons, through the receiver: each writes exactly its own
    // event and state, cancels its own notification, and leaves the others as they were.
    @Test
    fun `a button on a spread notification writes exactly its event`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        val b = f.due("b", Criticality.STANDARD, slot = 32)
        val c = f.due("c", Criticality.CRITICAL, slot = 33)
        f.fire(a)
        f.fire(b)
        f.fire(c)
        assertTrue(sessions.isSpread)
        val before = f.arming.eventLog("a", "b", "c")

        press(checkNotNull(f.posted(31)), R.string.ring_action_acknowledge)

        assertEquals(listOf(EventType.COMPLETED to "a"), herEvents(before))
        assertEquals(OccurrenceState.COMPLETED, state("a"))
        assertNull("a's own notification is cancelled", f.posted(31))
        assertEquals("b's is as it was", threeActions, labels(checkNotNull(f.posted(32))))
        assertEquals("c's too", threeActions, labels(checkNotNull(f.posted(33))))
        assertEquals(listOf("b", "c"), sessions.items().map { it.occurrenceId })
        val afterA = f.arming.eventLog("a", "b", "c")

        press(checkNotNull(f.posted(32)), R.string.ring_action_snooze)

        assertEquals("a snooze: its event", listOf(EventType.SNOOZED to "b"), herEvents(afterA))
        assertEquals(OccurrenceState.SNOOZED, state("b"))
        assertNull("b's own notification is cancelled", f.posted(32))
        assertEquals("c's is still as it was", threeActions, labels(checkNotNull(f.posted(33))))
        val afterB = f.arming.eventLog("a", "b", "c")

        press(checkNotNull(f.posted(33)), R.string.ring_action_skip)

        assertEquals(listOf(EventType.SKIPPED to "c"), herEvents(afterB))
        assertEquals(OccurrenceState.SKIPPED, state("c"))
        assertEquals("no rung was consumed by the three", 3, f.arming.count(EventType.ALARM_FIRED, "a", "b", "c"))
        assertNull(f.posted(33))
        assertFalse("the last one ended the session", sessions.isActive)
        assertTrue(sessions.items().isEmpty())
        assertEquals("nothing is posted", 0, count(f))
        assertEquals("the ringer was stopped once, by the last", 1, f.ringer.stops)
    }

    // ---------------------------------------------------------------- a refused action

    // ADR 0080 item 4: a refused action refreshes the buttons where they are. a's own notification, posted by the
    // port at the spread with a snooze, is stale once the next occurrence of a's template is five minutes away (a ten
    // minute snooze would reach it, golden scenario 4); pressing its snooze is refused, a's own notification is
    // reposted without snooze, and the session's notification and b's are as they were.
    @Test
    fun `a refused action refreshes the buttons where they are`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)
        f.fire(f.due("b", Criticality.STANDARD, slot = 32))
        assertTrue(sessions.isSpread)
        val stale = checkNotNull(f.posted(31))
        assertEquals("a's own notification offers a snooze", threeActions, labels(stale))
        f.arming.occurrences.insert(nextOf(a))
        val session = session(f)
        val other = checkNotNull(f.posted(32))
        val log = f.arming.eventLog("a", "b")

        press(stale, R.string.ring_action_snooze)

        assertEquals("the refused snooze writes nothing", log, f.arming.eventLog("a", "b"))
        assertEquals(0, f.arming.count(EventType.SNOOZED, "a"))
        val refreshed = checkNotNull(f.posted(31)) { "a's own notification is still posted" }
        assertEquals("reposted without snooze", twoActions, labels(refreshed))
        assertOwn(refreshed, NotificationChannels.STANDARD, twoActions, "a, refreshed")
        assertEquals("the session's notification is as it was", labels(session), labels(session(f)))
        assertTrue(labels(session).isEmpty())
        assertEquals("b's is as it was", labels(other), labels(checkNotNull(f.posted(32))))
        assertTrue("a is still listed", sessions.items().any { it.occurrenceId == "a" })
        assertEquals(3, count(f))
        assertInvariant("after the refusal", f)
    }

    // ---------------------------------------------------------------- a notification already under the slot

    // ADR 0080 items 1 and 4, for an occurrence that arrived as a notification first: a silent one under quiet hours
    // is under its slot with its actions; when the next rung rings alone the session's notification carries the
    // actions, so the slot's goes and there is exactly one; when a second occurrence joins, the slot's is the per
    // occurrence notification, not the silent one.
    @Test
    fun `an occurrence that already had a notification joins with exactly one`() {
        val r = Real()
        r.f.settings.updateQuietHours(QuietHours(LocalTime(7, 0), LocalTime(9, 0)))
        val a = r.f.due("a", Criticality.STANDARD, slot = 31)
        r.f.fire(a)
        assertEquals("SILENT", r.f.telemetryOf("a").deliveryPath)
        assertEquals(NotificationChannels.QUIET, r.f.channelOf(31))
        assertEquals(threeActions, labels(checkNotNull(r.f.posted(31))))
        r.f.settings.updateQuietHours(null)

        r.f.fire(a, now = t0 + 10.minutes, rung = t0 + 10.minutes)

        assertTrue("it rings", r.sessions.isActive)
        assertEquals(1, r.ringer.starts.size)
        assertNull("the silent notification under its slot is gone", r.f.posted(31))
        assertEquals("the session's notification carries its actions", threeActions, labels(session(r.f)))
        assertEquals("exactly one notification", 1, count(r.f))
        assertInvariant("ringing alone after a silent one", r.f)

        r.f.fire(r.f.due("b", Criticality.CRITICAL, scheduled = t0 + 10.minutes, slot = 32), now = t0 + 10.minutes)

        val own = checkNotNull(r.f.posted(31)) { "a's own notification" }
        assertOwn(own, NotificationChannels.STANDARD, threeActions, "a, spread")
        assertEquals("one notification per slot, three in all", 3, count(r.f))
        assertInvariant("spread after a silent one", r.f)
    }
}
