package com.momtime.android.delivery

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.widget.Button
import com.momtime.android.R
import com.momtime.android.arming.t0
import com.momtime.android.ring.RingActivity
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.RingPolicy
import com.momtime.android.work.TemplateEditPass
import com.momtime.shared.data.EditResult
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.OccurrenceAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The rest of ADR 0080 (`docs/phase-3-plan.md`, PR 6), over the same fixtures as `SpreadTest` (`SpreadSupport`):
 * a swiped per occurrence notification, the stale notifications after a process death, an edit reaching every
 * notification that is still showing and none she dismissed (decided by Claude (technical review); the Open items
 * row deferred from PR 4), PR 4's display test with the session spread, and a tap on a per occurrence notification.
 * SDK 29 and 36. Nothing here ran on a device (`MANUAL_CHECKS.md` P3-2 to P3-5).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
internal class SpreadRefreshTest : SpreadSupport() {
    private val canLoseExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // ---------------------------------------------------------------- a swipe

    // ADR 0066: a per occurrence notification sets no delete intent, so a swipe writes nothing and the next rung
    // still fires; it is not ongoing, so she can swipe it; and nothing brings it back before its next ring (decided
    // by Claude (technical review): still showing is read from the platform's active notifications). Its next ring,
    // after the cap, posts it again, as a repeat rung posts a reminder again.
    @Test
    fun `a swiped per occurrence notification is accepted as gone until its next ring`() {
        val r = Real()
        val a = r.f.due("a", Criticality.CRITICAL, slot = 31)
        val b = r.f.due("b", Criticality.CRITICAL, slot = 32)
        r.f.fire(a)
        r.f.fire(b)
        val own = checkNotNull(r.f.posted(32))
        val log = r.f.arming.eventLog("a", "b")
        val screen = screen()

        own.deleteIntent?.let { pending ->
            assertTrue(finished(deliverToActionReceiver(context, shadowOf(pending).savedIntent)))
        }
        r.f.notifications.cancel(32)

        assertEquals("a swipe writes nothing", log, r.f.arming.eventLog("a", "b"))
        assertEquals(OccurrenceState.PENDING, r.state("b"))
        assertTrue("b is still listed", r.sessions.items().any { it.occurrenceId == "b" })
        assertEquals("with its buttons on the screen", sixButtons, buttonTags(screen))
        assertNull("and no notification of its own", r.f.posted(32))
        assertEquals("a's own and the session's are as they were", 2, count(r.f))

        // Another occurrence's action, and the cap, bring it back no more than an edit does.
        r.controller.act("a", OccurrenceAction.ACKNOWLEDGE)
        assertNull("a's action does not bring b's back", r.f.posted(32))
        r.scheduler.advance(RingPolicy.MAX_RING)
        assertFalse(r.sessions.isActive)
        assertNull("the cap does not bring it back", r.f.posted(32))
        assertTrue("the session's notification stays, with no actions", labels(session(r.f)).isEmpty())

        // Its next rung still fires (Critical: the repeat at five minutes) and rings again, and presents it again.
        r.f.fire(b, now = t0 + 5.minutes, rung = t0 + 5.minutes)

        assertEquals("a new ring", 2, r.ringer.starts.size)
        val again = postedUnder(r.f, 32, "its own notification is posted again at its next ring")
        assertOwn(again, NotificationChannels.CRITICAL, threeActions, "b, again")
        assertEquals("two notifications: the session's and b's", 2, count(r.f))
        assertInvariant("after the next ring", r.f, resolved = setOf(31))
    }

    // Accepted by Claude (technical review) in the close out on this condition: after a swipe her actions stay
    // reachable. At Tier 3 the ring screen, which the session's full screen intent opens, lists the occurrence with
    // its actions; at Tier 2 tapping the session's notification (ongoing while it sounds, so it cannot be swiped)
    // opens the ring screen with it listed; Taken there writes ADR 0066's delta. The screen is built from the very
    // intent the session's notification carries at that tier.
    @Test
    fun `after she swipes away an occurrence's notification its actions are still reachable`() {
        for (fullScreen in listOf(true, false)) {
            val label = if (fullScreen) "Tier 3" else "Tier 2"
            val r = Real()
            r.f.fullScreenIntent(fullScreen)
            r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))
            val b = r.f.due("b", Criticality.CRITICAL, slot = 32)
            r.f.fire(b)
            assertEquals("$label: the tier", if (fullScreen) "RING" else "HEADS_UP", r.f.telemetryOf("b").deliveryPath)
            val own = checkNotNull(r.f.posted(32)) { "$label: b's own notification" }
            own.deleteIntent?.let { pending ->
                assertTrue(finished(deliverToActionReceiver(context, shadowOf(pending).savedIntent)))
            }
            r.f.notifications.cancel(32)
            assertNull("$label: b's own notification is gone", r.f.posted(32))
            val session = session(r.f, "$label: the session's notification is posted")
            val route = if (fullScreen) session.fullScreenIntent else session.contentIntent
            assertNotNull("$label: the session's notification carries the route to the screen", route)
            val opening = shadowOf(route!!).savedIntent
            assertEquals(
                "$label: it opens the ring screen",
                RingActivity::class.java.name,
                opening.component?.className,
            )

            val screen =
                Robolectric
                    .buildActivity(RingActivity::class.java, opening)
                    .create()
                    .start()
                    .resume()
                    .visible()
                    .get()

            assertEquals("$label: the screen lists both with their buttons", sixButtons, buttonTags(screen))
            val before = r.f.arming.eventLog("a", "b")
            screen.window.decorView
                .findViewWithTag<Button>("ack:b")
                .performClick()
            assertEquals(
                "$label: Taken on the screen writes ADR 0066's delta",
                listOf(EventType.COMPLETED to "b"),
                r.f.arming
                    .eventDelta("a", "b", before = before)
                    .filter { it.first != EventType.ALARM_SCHEDULED },
            )
            assertEquals(OccurrenceState.COMPLETED, r.state("b"))
            assertEquals("$label: a stays listed", listOf("a"), r.sessions.items().map { it.occurrenceId })
            r.ringer.destroyAll()
        }
    }

    // ---------------------------------------------------------------- process death

    // The per occurrence notifications outlive the process that posted them (the system removes a dead foreground
    // service's notification; the ordinary ones stay in the shade). In a new process over the same database: a stale
    // button still writes exactly its event through the receiver and cancels its notification, and a fire that
    // rings alone leaves one notification per slot, because the stale per occurrence one is cancelled.
    @Test
    fun `after process death her stale notifications still work and a new ring leaves one per slot`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        val b = f.due("b", Criticality.CRITICAL, slot = 32)
        f.fire(a)
        f.fire(b)
        assertTrue(sessions.isSpread)
        assertEquals(3, count(f))
        // The process dies: the foreground service's notification goes with it, the others stay; the session is gone.
        f.notifications.cancel(RingNotifications.RING_ID)
        f.close()
        fixtures.remove(f)
        val next =
            DeliveryFixture(
                context,
                name = f.name,
                storeName = f.storeName,
                seedPregnancy = false,
                idPrefix = "next",
            ).also(::wire)
        next.arming.clock.now = t0 + 1.minutes
        val sessions: RingSessions = next.arming.graph.get()
        assertTrue("a new process has no session", sessions.items().isEmpty())
        assertEquals("the stale notifications are still in the shade", 2, count(next))
        assertEquals(
            "b is open in the database the new process opened",
            OccurrenceState.PENDING,
            next.arming.occurrences
                .findById("b")
                ?.state,
        )
        val before = next.arming.eventLog("a", "b")

        press(checkNotNull(next.posted(32)), R.string.ring_action_acknowledge)

        assertEquals(listOf(EventType.COMPLETED to "b"), next.arming.eventDelta("a", "b", before = before))
        assertEquals(
            OccurrenceState.COMPLETED,
            next.arming.occurrences
                .findById("b")
                ?.state,
        )
        assertNull("b's stale notification is cancelled", next.posted(32))
        assertNotNull("a's is still there", next.posted(31))
        assertNull("nothing was posted under the session's id", next.posted(RingNotifications.RING_ID))

        // a's repeat rings in the new process: alone, so the session's notification carries its actions and the stale
        // per occurrence one must go.
        next.fire(a, now = t0 + 5.minutes, rung = t0 + 5.minutes)

        assertTrue(sessions.isActive)
        assertFalse("one occurrence: not spread", sessions.isSpread)
        assertEquals(1, next.ringer.starts.size)
        assertNull("the stale notification under a's slot is cancelled", next.posted(31))
        assertEquals("one per slot: nothing but what the service holds, which is a fake here", 0, count(next))
        assertEquals(
            OccurrenceState.PENDING,
            next.arming.occurrences
                .findById("a")
                ?.state,
        )
    }

    // ---------------------------------------------------------------- an edit reaches every notification

    /** One kind of notification, in or out of the session, posted for `a` of `tmpl-a` under slot 31. */
    private class Kind(
        val name: String,
        val channel: String,
        val needsExactLoss: Boolean = false,
        val prepare: (DeliveryFixture) -> Unit,
    )

    private val kinds: List<Kind> =
        listOf(
            Kind("a per occurrence notification of a spread session", NotificationChannels.CRITICAL) { fx ->
                fx.fire(fx.due("a", Criticality.CRITICAL, slot = 31))
                fx.fire(fx.due("b", Criticality.STANDARD, slot = 32))
            },
            Kind("a Tier 1 plain notification", NotificationChannels.CRITICAL, needsExactLoss = true) { fx ->
                ShadowAlarmManager.setCanScheduleExactAlarms(false)
                fx.fire(fx.due("a", Criticality.CRITICAL, slot = 31))
            },
            Kind("a Gentle notification", NotificationChannels.GENTLE) { fx ->
                fx.fire(fx.due("a", Criticality.GENTLE, slot = 31))
            },
            Kind("a silent notice", NotificationChannels.QUIET) { fx ->
                val a = fx.due("a", Criticality.CRITICAL, slot = 31)
                fx.fire(a, now = a.scheduledInstant + 31.minutes)
            },
        )

    private fun edit(
        fx: DeliveryFixture,
        templateId: String,
    ): EditResult {
        val pass: TemplateEditPass = fx.arming.graph.get()
        val templates: ScheduleTemplateRepository = fx.arming.graph.get()
        val edited = checkNotNull(templates.findById(templateId)).copy(title = "Iron, with food", dosage = "2 tablets")
        return pass.run(edited)
    }

    private fun others(
        fx: DeliveryFixture,
        slot: Int,
    ): List<Pair<Int, List<String>>> =
        fx.notifications.activeNotifications
            .filter { it.id != slot }
            .map { it.id to labels(it.notification) }

    // Decided by Claude (technical review), PR 6: after an edit, every notification of the edited template's open
    // occurrences that is still showing takes the new title, dosage and instructions, with the same id, actions and
    // channel and only alert once, and nothing is written; "still showing" is the platform's active notifications,
    // so one she swiped away is not brought back. The edit is the step that would post it if the check were missing.
    @Test
    @Suppress("LongMethod")
    fun `an edit reaches every notification that is still showing, and brings back none she dismissed`() {
        // The swiped case first, so that a repost without the active notifications check fails it by name: a Gentle
        // notification she took away before the edit is not brought back.
        val swiped = fixture()
        swiped.notifications.cancelAll()
        swiped.fire(swiped.due("a", Criticality.GENTLE, slot = 31))
        assertNotNull(swiped.posted(31))
        swiped.notifications.cancel(31)
        val logBefore = swiped.arming.eventLog("a")

        edit(swiped, "tmpl-a")

        assertNull("the swiped notification is not brought back", swiped.posted(31))
        assertEquals("nothing is posted", 0, count(swiped))
        assertEquals("no event", logBefore, swiped.arming.eventLog("a"))
        assertEquals(
            "the template is edited all the same",
            "Iron, with food",
            swiped.arming.graph
                .get<ScheduleTemplateRepository>()
                .findById("tmpl-a")
                ?.title,
        )

        for (kind in kinds) {
            if (kind.needsExactLoss && !canLoseExact) continue
            val fx = fixture()
            // The shade outlives a fixture: each kind starts with it empty.
            fx.notifications.cancelAll()
            kind.prepare(fx)
            val before = checkNotNull(fx.posted(31)) { "${kind.name}: posted" }
            assertEquals("${kind.name}: on its channel", kind.channel, before.channelId)
            assertEquals("${kind.name}: her title as posted", "Iron tablet", title(before))
            val count = count(fx)
            val others = others(fx, 31)
            val starts = fx.ringer.starts.size
            val log = fx.arming.eventLog("a")

            val result = edit(fx, "tmpl-a")

            assertEquals("${kind.name}: nothing withdrawn, nothing moved", EditResult.Edited(emptyList(), 0), result)
            val after = checkNotNull(fx.posted(31)) { "${kind.name}: still posted" }
            assertEquals("${kind.name}: the new title", "Iron, with food", title(after))
            assertEquals("${kind.name}: the new dosage", "2 tablets", text(after))
            assertEquals("${kind.name}: the same actions", labels(before), labels(after))
            assertEquals("${kind.name}: the same channel", before.channelId, after.channelId)
            assertTrue("${kind.name}: only alert once", hasFlag(after, Notification.FLAG_ONLY_ALERT_ONCE))
            assertEquals(
                "${kind.name}: as ongoing as before",
                hasFlag(before, Notification.FLAG_ONGOING_EVENT),
                hasFlag(after, Notification.FLAG_ONGOING_EVENT),
            )
            assertEquals(
                "${kind.name}: late if it was",
                before.extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                after.extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            )
            assertEquals("${kind.name}: as many notifications as before", count, count(fx))
            assertEquals("${kind.name}: the others' actions are as they were", others, others(fx, 31))
            assertEquals("${kind.name}: no event", log, fx.arming.eventLog("a"))
            assertEquals("${kind.name}: no sound", 0, fx.sound.starts)
            assertEquals("${kind.name}: no ringer start by the edit", starts, fx.ringer.starts.size)
            fixtures.remove(fx)
            fx.close()
        }
    }

    // ---------------------------------------------------------------- an edit reaches a spread session's own

    // PR 4's display test, with the session spread (decided by Claude (technical review), PR 6 decision 4; it lives
    // here because `EditPassTest` is at detekt's class size limit). ADR 0080 with ADR 0079 item 4: an edit reaches
    // the edited template's per occurrence notification as well as the session's, each replaced under its id with
    // the same actions and only alert once; the other occurrence's own notification and the ring itself are
    // untouched, nothing is written, no sound starts.
    @Test
    @Suppress("LongMethod")
    fun `an edit of the dosage while ringing reaches the per occurrence notification at once, spread`() {
        val r = Real()
        val a = r.f.arming.seed("a", Criticality.CRITICAL, t0, slot = 31, dosage = "1 tablet")
        val b = r.f.arming.seed("b", Criticality.STANDARD, t0, slot = 32, dosage = "400 mcg")
        r.f.arming.clock.now = t0 - 1.hours
        r.f.arming.coordinator
            .ensureArmed()
        r.f.fire(a)
        r.f.fire(b)
        val screen = screen()
        assertTrue("the session has spread", r.sessions.isSpread)
        val sessionBefore = session(r.f)
        val ownBefore = checkNotNull(r.f.posted(31)) { "a's own notification is posted" }
        val otherBefore = checkNotNull(r.f.posted(32)) { "b's own notification is posted" }
        assertEquals("Iron tablet", title(ownBefore))
        assertEquals("1 tablet", text(ownBefore))
        val dosage = context.getString(R.string.ring_dosage_label)
        assertEquals(listOf("Iron tablet", dosage, "1 tablet", "Iron tablet", dosage, "400 mcg"), itemTexts(screen))
        val player = r.playing()
        val players = r.players.created.size
        val stops = r.ringer.stops
        val log = r.f.arming.eventLog("a", "b")
        val alarms = r.f.arming.shapes()

        val result = r.pass.run(r.template("tmpl-a").copy(title = "Iron, with food", dosage = "2 tablets"))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("nothing withdrawn, nothing moved", EditResult.Edited(emptyList(), 0), result)
        val item = r.sessions.items().single { it.occurrenceId == "a" }
        assertEquals("the session item carries the new title", "Iron, with food", item.title)
        assertEquals("and the new dosage", "2 tablets", item.dosage)
        assertEquals(
            "the ring screen shows the new text for a and b's as it was",
            listOf("Iron, with food", dosage, "2 tablets", "Iron tablet", dosage, "400 mcg"),
            itemTexts(screen),
        )
        assertEquals(sixButtons, buttonTags(screen))
        val own = checkNotNull(r.f.posted(31)) { "a's own notification is still posted" }
        assertEquals("a's own carries the new title", "Iron, with food", title(own))
        assertEquals("and the new dosage", "2 tablets", text(own))
        assertEquals("the same actions", labels(ownBefore), labels(own))
        assertTrue("only alert once", hasFlag(own, Notification.FLAG_ONLY_ALERT_ONCE))
        assertFalse("not ongoing", hasFlag(own, Notification.FLAG_ONGOING_EVENT))
        assertNull("no full screen intent", own.fullScreenIntent)
        assertEquals("on the same channel", ownBefore.channelId, own.channelId)
        val other = checkNotNull(r.f.posted(32)) { "b's own notification is still posted" }
        assertEquals("b's own is as it was", title(otherBefore), title(other))
        assertEquals(text(otherBefore), text(other))
        assertEquals(labels(otherBefore), labels(other))
        assertEquals(otherBefore.flags, other.flags)
        val session = session(r.f, "the session's notification is still posted")
        assertTrue("the session's notification carries no actions", labels(session).isEmpty())
        assertTrue("it names the new title", text(session).orEmpty().contains("Iron, with food"))
        assertTrue("only alert once", hasFlag(session, Notification.FLAG_ONLY_ALERT_ONCE))
        assertEquals(
            "ongoing as before",
            hasFlag(sessionBefore, Notification.FLAG_ONGOING_EVENT),
            hasFlag(session, Notification.FLAG_ONGOING_EVENT),
        )
        assertEquals("on the same channel", sessionBefore.channelId, session.channelId)
        assertEquals("three notifications: the session's and one per occurrence", 3, count(r.f))
        assertTrue("the ringer state is unchanged", r.sessions.isActive)
        assertEquals("the same sound", player, r.playing())
        assertEquals("no new player", players, r.players.created.size)
        assertEquals("no second ringer start", 1, r.ringer.starts.size)
        assertEquals("the ringer was not stopped by the edit", stops, r.ringer.stops)
        assertEquals("no event is written", log, r.f.arming.eventLog("a", "b"))
        assertEquals("the alarm is as it was", alarms, r.f.arming.shapes())
        assertEquals(OccurrenceState.PENDING, r.state("a"))
        assertEquals(OccurrenceState.PENDING, r.state("b"))
    }

    // ---------------------------------------------------------------- a tap on a per occurrence notification

    // ADR 0080 item 3: its content intent is the ring screen. A tap opens the screen, which lists every due
    // occurrence with its buttons and starts nothing; the notification is not auto cancelled by the tap, so her
    // actions stay in the shade after it (for review: the ADR names the content intent and not the auto cancel flag).
    @Test
    fun `a tap on a per occurrence notification opens the ring screen and leaves it posted`() {
        f.fire(f.due("a", Criticality.CRITICAL, slot = 31))
        f.fire(f.due("b", Criticality.STANDARD, slot = 32))
        val own = checkNotNull(f.posted(31))
        val content = shadowOf(checkNotNull(own.contentIntent))
        assertTrue("its content intent opens an activity", content.isActivityIntent)
        assertEquals(ComponentName(context, RingActivity::class.java), content.savedIntent.component)
        assertFalse("not auto cancelled by a tap", hasFlag(own, Notification.FLAG_AUTO_CANCEL))

        val screen =
            Robolectric
                .buildActivity(RingActivity::class.java, content.savedIntent)
                .create()
                .start()
                .resume()
                .visible()
                .get()

        assertEquals("the screen lists both with their buttons", sixButtons, buttonTags(screen))
        assertEquals("opening it started nothing", 1, f.ringer.starts.size)
        assertNotNull("the notification is still posted", f.posted(31))
    }
}
