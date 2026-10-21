package com.momtime.android.delivery

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.momtime.android.R
import com.momtime.android.arming.DEVICE_CHANNELS
import com.momtime.android.arming.FireOutcome
import com.momtime.android.arming.t0
import com.momtime.android.data.testZone
import com.momtime.android.reliability.DeviceInfo
import com.momtime.android.reliability.ReliabilityExport
import com.momtime.android.reliability.ReliabilityReader
import com.momtime.android.ring.RingActivity
import com.momtime.android.ring.RingEntryPoint
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.FakePlayer
import com.momtime.android.ringer.FakePlayers
import com.momtime.android.ringer.FakeScheduler
import com.momtime.android.ringer.RingPolicy
import com.momtime.android.ringer.RingSound
import com.momtime.android.ringer.RingSoundKind
import com.momtime.android.ringer.RingerEntryPoint
import com.momtime.android.ringer.RingerService
import com.momtime.android.ringer.VibrationPattern
import com.momtime.android.ringer.VolumeRamp
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.EscalationLadder
import com.momtime.shared.engine.OccurrenceAction
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A ring has a maximum length, and the end of grace ends a ring (ADR 0090; `docs/phase-3-plan.md`, PR 2b).
 *
 * The cap is a timer on the ringer's own scheduler, a fake here that the test advances by hand; the fixture clock is
 * separate and is moved only where a test needs the domain to see time pass (grace). Most tests run over the fake
 * ringer launch, as `DeliveryTest` does; the two that assert what stops (the players, the vibration, the service
 * leaving the foreground) drive the real `RingerService` through Robolectric and the real `RingSound` over fake
 * players, on the same fake scheduler.
 *
 * Every assertion that something is absent (no event, no second start, no sound, no spend) follows the step that
 * would have produced it if the code were wrong: the scheduler advanced past the cap, the repeat fired, the pass run.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class RingLimitTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixtures = mutableListOf<DeliveryFixture>()

    /** The fixture over the fake ringer launch, built on first use and wired as the screen's and the buttons' host. */
    private val f: DeliveryFixture by lazy { DeliveryFixture(context).also(::install) }
    private val controller: RingController get() = f.arming.graph.get()
    private val sessions: RingSessions get() = f.arming.graph.get()

    @After
    fun tearDown() {
        RingEntryPoint.provider = null
        RingerEntryPoint.provider = null
        NotificationActionEntryPoint.provider = null
        ringers.forEach { it.destroyAll() }
        fixtures.forEach { it.close() }
    }

    private fun install(fixture: DeliveryFixture) {
        fixtures += fixture
        val host: RingController = fixture.arming.graph.get()
        RingEntryPoint.provider = { host }
        RingEntryPoint.executor = Executor { it.run() }
        NotificationActionEntryPoint.provider = { host }
        RingerEntryPoint.provider = { host }
    }

    private fun state(id: String): OccurrenceState? =
        f.arming.occurrences
            .findById(id)
            ?.state

    private fun fired(outcome: FireOutcome): FireOutcome.Fired = outcome as FireOutcome.Fired

    // ---------------------------------------------------------------- the real service and the real sound

    /** The ringer service itself, driven through Robolectric's controller, started and stopped as the launcher does. */
    private inner class RealRinger : RingerLauncher {
        val starts = mutableListOf<RingerRequest>()
        var stops = 0
        private val services = mutableListOf<ServiceController<RingerService>>()
        private val live = mutableListOf<ServiceController<RingerService>>()

        val service: RingerService get() = services.last().get()

        override fun start(request: RingerRequest) {
            starts += request
            val service =
                Robolectric
                    .buildService(RingerService::class.java, RingerService.startIntent(context, request))
                    .create()
            services += service
            live += service
            service.startCommand(0, services.size)
        }

        override fun stop() {
            stops++
            // Exactly what the platform launcher does: detach the notification, then stop the service.
            RingerService.running?.detach()
            live.removeLastOrNull()?.destroy()
        }

        fun destroyAll() = live.toList().forEach { stop() }
    }

    private val ringers = mutableListOf<RealRinger>()

    private inner class Real {
        val players = FakePlayers()
        val scheduler = FakeScheduler()
        val ringer = RealRinger().also(ringers::add)
        val f =
            DeliveryFixture(
                context,
                scheduler = scheduler,
                soundOverride = RingSound(context, players, scheduler, VolumeRamp()) { 2.minutes },
                ringerOverride = ringer,
            ).also(::install)
        val sessions: RingSessions get() = f.arming.graph.get()

        /** The player that is playing now, if one is: the primary, or the backup once it has replaced it. */
        fun playing(): FakePlayer? = players.created.lastOrNull()?.takeIf { it.started && !it.stopped }
    }

    // ---------------------------------------------------------------- the constant

    // ADR 0090 item 1: a repeat must always find the sound off and be a new ring, so the cap is shorter than every
    // gap between two device rungs of every default ladder. Fails closed: a ladder set with no two device rungs
    // would make the loop vacuous, and that is an error here.
    @Test
    fun `MAX_RING is shorter than every gap between device rungs`() {
        var gaps = 0
        for (criticality in Criticality.entries) {
            val device = EscalationLadder.forOccurrence(t0, criticality).filter { it.channel in DEVICE_CHANNELS }
            device.zipWithNext().forEach { (earlier, later) ->
                gaps++
                val gap = later.instant - earlier.instant
                assertTrue("$criticality: a gap of $gap is not longer than MAX_RING", gap > RingPolicy.MAX_RING)
            }
        }
        assertTrue("no ladder has two device rungs: nothing was checked", gaps > 0)
    }

    // ---------------------------------------------------------------- what stops at the cap

    @Test
    @Config(sdk = [29, 33, 34, 36])
    fun `the sound and the vibration stop at the cap`() {
        for (fullScreen in listOf(true, false)) {
            val r = Real()
            r.f.fullScreenIntent(fullScreen)
            val a = r.f.due("a", Criticality.CRITICAL, slot = 31)
            r.f.fire(a)
            assertEquals("the tier", if (fullScreen) "RING" else "HEADS_UP", r.f.telemetryOf("a").deliveryPath)
            assertNotNull(
                "the service holds the ring notification",
                shadowOf(r.ringer.service).lastForegroundNotification,
            )
            assertEquals(listOf(VibrationPattern.URGENT), r.f.vibration.starts)

            r.scheduler.advance(RingPolicy.MAX_RING - 1.milliseconds)

            val before = checkNotNull(r.playing()) { "fullScreen=$fullScreen: nothing is playing before the cap" }
            assertEquals("the backup has replaced the primary by now", RingSoundKind.BACKUP, before.kind)
            assertEquals("vibration is on", 0, r.f.vibration.stops)
            assertTrue(r.sessions.isActive)

            r.scheduler.advance(1.milliseconds)

            assertNull("fullScreen=$fullScreen: a player is still playing after the cap", r.playing())
            assertTrue("every player was stopped", r.players.created.all { it.stopped })
            assertEquals("the vibration was stopped", 1, r.f.vibration.stops)
            val shadow = shadowOf(r.ringer.service)
            assertTrue("the service left the foreground", shadow.isForegroundStopped)
            assertFalse("without asking for its notification to be removed", shadow.notificationShouldRemoved)
            assertEquals(1, r.ringer.stops)
            assertFalse("the session's sound is off", r.sessions.isActive)
        }
    }

    @Test
    fun `the backup sound still starts at two minutes`() {
        val r = Real()
        r.f.fire(r.f.due("a", Criticality.CRITICAL, slot = 31))

        r.scheduler.advance(2.minutes)

        assertEquals("the backup started at two minutes", 1, r.players.of(RingSoundKind.BACKUP).size)
        val backup = r.players.of(RingSoundKind.BACKUP).single()
        assertTrue("the backup plays at two minutes", backup.started && !backup.stopped)
        assertTrue(
            "in the primary's place",
            r.players
                .of(RingSoundKind.PRIMARY)
                .single()
                .stopped,
        )

        r.scheduler.advance(RingPolicy.MAX_RING - 2.minutes)

        assertTrue("both are silent after the cap", r.players.created.all { it.stopped })
        assertNull(r.playing())
    }

    // ---------------------------------------------------------------- a dismissal

    @Test
    fun `the cap is a dismissal and writes nothing`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        val log = f.arming.eventLog("a")
        val row = checkNotNull(f.arming.occurrences.findById("a"))
        val repeat = (a.scheduledInstant + 5.minutes).toEpochMilliseconds()
        assertEquals("the repeat is armed before the cap", listOf(repeat), f.arming.alarms().map { it.triggerAtMs })

        f.scheduler.advance(RingPolicy.MAX_RING)

        assertFalse(sessions.isActive)
        assertEquals("the ringer was stopped", 1, f.ringer.stops)
        assertEquals("no event was added", log, f.arming.eventLog("a"))
        assertEquals("the row is byte equal", row, f.arming.occurrences.findById("a"))
        assertEquals(OccurrenceState.PENDING, state("a"))
        assertEquals(
            "the repeat's alarm is still armed, untouched by the cap",
            listOf(repeat),
            f.arming.alarms().map { it.triggerAtMs },
        )

        f.arming.coordinator.ensureArmed()

        assertEquals("and ensureArmed holds the same alarm", listOf(repeat), f.arming.alarms().map { it.triggerAtMs })
        assertEquals(log, f.arming.eventLog("a"))
    }

    /** The session's notification, asserted to be still posted: a dismissal that cancelled it fails here. */
    private fun stillPosted(message: String): Notification {
        val notification = f.posted(RingNotifications.RING_ID)
        assertNotNull(message, notification)
        return notification!!
    }

    private fun assertOrdinary(notification: Notification) {
        assertEquals("not ongoing", 0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertNull("no full screen intent", notification.fullScreenIntent)
        assertTrue("only alert once", notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull("no delete intent", notification.deleteIntent)
    }

    private fun labels(notification: Notification): List<String> =
        notification.actions.orEmpty().map { it.title.toString() }

    private val threeActions: List<String>
        get() =
            listOf(R.string.ring_action_acknowledge, R.string.ring_action_snooze, R.string.ring_action_skip)
                .map(context::getString)

    private fun press(
        notification: Notification,
        label: Int,
    ) {
        val action = notification.actions.single { it.title.toString() == context.getString(label) }
        val done = deliverToActionReceiver(context, shadowOf(action.actionIntent).savedIntent)
        assertTrue("the receiver did not finish", finished(done))
    }

    @Test
    fun `her actions stay after the cap`() {
        // One occurrence: its three actions are on the notification, and Taken from it writes ADR 0066's delta.
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.scheduler.advance(RingPolicy.MAX_RING)

        val one = stillPosted("the notification is gone after the cap")
        assertOrdinary(one)
        assertEquals("exactly the offered actions", threeActions, labels(one))
        val before = f.arming.eventLog("a")
        press(one, R.string.ring_action_acknowledge)
        assertEquals(listOf(EventType.COMPLETED to "a"), f.arming.eventDelta("a", before = before))
        assertEquals(OccurrenceState.COMPLETED, state("a"))
        assertNull("acted on, the notification goes", f.posted(RingNotifications.RING_ID))
        assertTrue(sessions.items().isEmpty())

        // Two occurrences (ADR 0080, run again in PR 6 as this section said; its expectation changed there, by
        // authorisation of Claude (technical review)): the session has spread, so after the cap the session's
        // notification is ordinary and carries no actions, and each occurrence's own notification is still posted
        // with exactly its offered actions, not ongoing, with no full screen intent and only alert once, as it was
        // before the cap; each occurrence's actions are on the ring screen too. Taken on one writes ADR 0066's delta
        // and leaves the other's notification as it was: the buttons never move back to the session's notification.
        val b = f.due("b", Criticality.CRITICAL, scheduled = t0 + 1.hours, slot = 32)
        val c = f.due("c", Criticality.STANDARD, scheduled = t0 + 1.hours, slot = 33)
        f.fire(b)
        f.fire(c)
        assertTrue("none while ringing with two", labels(stillPosted("the notification, with two ringing")).isEmpty())
        val ownOfC = checkNotNull(f.posted(33)) { "c's own notification, posted at the spread" }
        f.scheduler.advance(RingPolicy.MAX_RING)

        val two = stillPosted("the notification is gone after the cap, with two")
        assertOrdinary(two)
        assertTrue("none after the cap with two", labels(two).isEmpty())
        for ((slot, name) in listOf(32 to "b", 33 to "c")) {
            val own = checkNotNull(f.posted(slot)) { "$name's own notification is still posted after the cap" }
            assertEquals("$name: exactly its offered actions", threeActions, labels(own))
            assertEquals("$name: not ongoing", 0, own.flags and Notification.FLAG_ONGOING_EVENT)
            assertNull("$name: no full screen intent", own.fullScreenIntent)
            assertTrue("$name: only alert once", own.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        }
        assertSame(c, "as it was before the cap", ownOfC)
        val screen =
            Robolectric
                .buildActivity(RingActivity::class.java)
                .create()
                .start()
                .resume()
                .visible()
                .get()
        assertEquals(
            "each occurrence's actions are on the screen",
            listOf("ack:b", "snooze:b", "skip:b", "ack:c", "snooze:c", "skip:c"),
            buttonTags(screen),
        )
        val beforeB = f.arming.eventLog("b")
        screen.window.decorView
            .findViewWithTag<Button>("ack:b")
            .performClick()
        assertEquals(listOf(EventType.COMPLETED to "b"), f.arming.eventDelta("b", before = beforeB))
        assertEquals(OccurrenceState.COMPLETED, state("b"))
        assertEquals(OccurrenceState.PENDING, state("c"))
        assertEquals("c has no event of its own but the re arm", 0, f.arming.count(EventType.COMPLETED, "c"))
        assertNull("b's own notification goes with b", f.posted(32))
        assertSame(c, "as it was, after Taken on b", ownOfC)
        val left = stillPosted("c is still waiting")
        assertOrdinary(left)
        assertTrue("the session stays spread: the session's notification carries none", labels(left).isEmpty())
    }

    /** c's own notification is still posted and is what [expected] was: the same actions, flags, channel and title. */
    private fun assertSame(
        c: Occurrence,
        message: String,
        expected: Notification,
    ) {
        val own = checkNotNull(f.posted(c.alarmSlot)) { "c's own notification is gone, $message" }
        assertEquals("c's own: actions $message", labels(expected), labels(own))
        assertEquals("c's own: flags $message", expected.flags, own.flags)
        assertEquals("c's own: channel $message", expected.channelId, own.channelId)
        assertEquals(
            "c's own: title $message",
            expected.extras.getCharSequence(Notification.EXTRA_TITLE),
            own.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
    }

    private fun buttonTags(activity: android.app.Activity): List<String> {
        val tags = mutableListOf<String>()

        fun walk(view: View) {
            if (view is Button && view.tag is String) tags += view.tag as String
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(activity.findViewById<LinearLayout>(R.id.ring_items))
        return tags
    }

    @Test
    fun `Stop the sound leaves the same state as the cap`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        val log = f.arming.eventLog("a")
        val row = checkNotNull(f.arming.occurrences.findById("a"))

        controller.stopSound()

        assertFalse(sessions.isActive)
        assertEquals(1, f.ringer.stops)
        val n = stillPosted("the notification is gone after Stop the sound")
        assertOrdinary(n)
        assertEquals(threeActions, labels(n))
        assertEquals("no event", log, f.arming.eventLog("a"))
        assertEquals(row, f.arming.occurrences.findById("a"))
        assertEquals(false, f.telemetryOf("a").ringEndedByCap)
        val before = f.arming.eventLog("a")
        press(n, R.string.ring_action_acknowledge)
        assertEquals(listOf(EventType.COMPLETED to "a"), f.arming.eventDelta("a", before = before))
        assertNull(f.posted(RingNotifications.RING_ID))
    }

    @Test
    fun `the ring screen keeps the list and lets the display sleep`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        val screen =
            Robolectric
                .buildActivity(RingActivity::class.java)
                .create()
                .start()
                .resume()
                .visible()
                .get()
        val keep = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertTrue("keep screen on while sounding", screen.window.attributes.flags and keep != 0)
        assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.ring_stop_sound).visibility)

        f.scheduler.advance(RingPolicy.MAX_RING)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("the screen is not closed", screen.isFinishing)
        assertEquals(
            "the item and its action buttons are still shown",
            listOf("ack:a", "snooze:a", "skip:a"),
            buttonTags(screen),
        )
        assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.ring_sound_stopped).visibility)
        assertEquals("the stop button is gone", View.GONE, screen.findViewById<View>(R.id.ring_stop_sound).visibility)
        assertEquals("the display may sleep", 0, screen.window.attributes.flags and keep)
    }

    // The screen opened from the kept notification after the cap, with the sound off and one occurrence still open
    // (added in the close out of PR 2b, as Claude (technical review) asked): it shows what is due with its actions,
    // and it starts nothing. The screen is opened the way a tap on the notification opens it, by its content intent.
    @Test
    fun `the ring screen opened from the kept notification shows the list and starts nothing`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.scheduler.advance(RingPolicy.MAX_RING)
        assertFalse("the cap ended the sound", sessions.isActive)
        val kept = stillPosted("the notification is gone after the cap")
        val starts = f.ringer.starts.size
        val log = f.arming.eventLog("a")
        val app = shadowOf(RuntimeEnvironment.getApplication())
        app.clearStartedServices()

        val opening = shadowOf(kept.contentIntent).savedIntent
        assertEquals("it opens the ring screen", RingActivity::class.java.name, opening.component?.className)
        val screen =
            Robolectric
                .buildActivity(RingActivity::class.java, opening)
                .create()
                .start()
                .resume()
                .visible()
                .get()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("the item and its actions are shown", listOf("ack:a", "snooze:a", "skip:a"), buttonTags(screen))
        assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.ring_sound_stopped).visibility)
        assertEquals("no stop button", View.GONE, screen.findViewById<View>(R.id.ring_stop_sound).visibility)
        assertNull("the screen started no service", app.nextStartedService)
        assertEquals("the ringer was not started", starts, f.ringer.starts.size)
        assertFalse("the sound is still off", sessions.isActive)
        val keep = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertEquals("keep screen on is not set", 0, screen.window.attributes.flags and keep)
        assertEquals("no event was written", log, f.arming.eventLog("a"))
        assertEquals(OccurrenceState.PENDING, state("a"))
    }

    // ---------------------------------------------------------------- a fresh cap per fire that rings

    @Test
    fun `the repeat rung rings again, for a fresh cap`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.scheduler.advance(RingPolicy.MAX_RING)
        assertEquals("the first ring ended at the cap", 1, f.ringer.stops)

        val repeat = fired(f.fire(a, now = a.scheduledInstant + 5.minutes, rung = a.scheduledInstant + 5.minutes))

        assertFalse("not a continuation", repeat.rung.continuing)
        assertEquals("a second ringer start", 2, f.ringer.starts.size)
        f.scheduler.advance(RingPolicy.MAX_RING - 1.milliseconds)
        assertTrue("the sound is still on just before four minutes after the repeat", sessions.isActive)
        assertEquals(1, f.ringer.stops)
        f.scheduler.advance(1.milliseconds)
        assertFalse("and off four minutes after the repeat's fire, not after the first", sessions.isActive)
        assertEquals(2, f.ringer.stops)
    }

    // A Standard first occurrence, so that its own repeat (ten minutes) cannot land inside the window and restart the
    // cap itself (decided by Claude (technical review)).
    @Test
    fun `a fire that continues or joins a sounding session restarts the cap`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        val b = f.due("b", Criticality.CRITICAL, scheduled = t0 + 3.minutes, slot = 32)
        f.fire(a)
        f.scheduler.advance(3.minutes)

        f.fire(b)

        assertEquals("b joined, no second start", 1, f.ringer.starts.size)
        f.scheduler.advance(1.minutes)
        assertTrue("still playing four minutes after the first fire", sessions.isActive)
        f.scheduler.advance(3.minutes - 1.milliseconds)
        assertTrue("and just before four minutes after the second", sessions.isActive)
        f.scheduler.advance(1.milliseconds)
        assertFalse("off four minutes after the second", sessions.isActive)
        assertEquals(1, f.ringer.stops)
    }

    // The continuation's own case (decided by Claude (technical review)): a Standard a fires at 0, the cap ends its
    // sound at 4, b joins at 7 and starts the sound again, and a's repeat continues the ring at 10.
    @Test
    fun `a fire that continues a sounding session restarts the cap`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        val b = f.due("b", Criticality.CRITICAL, scheduled = t0 + 7.minutes, slot = 32)
        f.fire(a)
        f.scheduler.advance(RingPolicy.MAX_RING)
        assertFalse(sessions.isActive)
        f.scheduler.advance(3.minutes)
        f.fire(b)
        assertEquals("b started the sound again", 2, f.ringer.starts.size)
        f.scheduler.advance(3.minutes)

        val repeat = fired(f.fire(a, now = t0 + 10.minutes, rung = t0 + 10.minutes))

        assertTrue("a's repeat continues the ring", repeat.rung.continuing)
        assertEquals(2, f.ringer.starts.size)
        f.scheduler.advance(RingPolicy.MAX_RING - 1.milliseconds)
        assertTrue("still playing just before fourteen minutes", sessions.isActive)
        f.scheduler.advance(1.milliseconds)
        assertFalse("and stopped just after", sessions.isActive)
        assertEquals(2, f.ringer.stops)
    }

    // ---------------------------------------------------------------- a repeat after the sound stopped rings again

    private fun repeatAfterTheSoundStopped(endSound: (Occurrence) -> Unit) {
        f.settings.updateRingGradeDailyBudget(10)
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)
        assertEquals(1, f.budget.current().ringCount)
        endSound(a)
        assertFalse(sessions.isActive)

        val repeat = fired(f.fire(a, now = t0 + 10.minutes, rung = t0 + 10.minutes))

        assertFalse("not marked a continuation", repeat.rung.continuing)
        assertEquals("the ringer starts a second time", 2, f.ringer.starts.size)
        assertEquals("the budget counts two", 2, f.budget.current().ringCount)
        assertEquals("RING", f.telemetryOf("a").deliveryPath)
        endSound(a)
        assertFalse("a's repeat's own sound has ended too", sessions.isActive)

        // With the budget spent in between (one ring left for c's first ring, none for its repeat), the repeat is a
        // silent notification on Quiet notices and there is no second start.
        f.settings.updateRingGradeDailyBudget(3)
        val c = f.due("c", Criticality.STANDARD, scheduled = t0 + 20.minutes, slot = 33)
        f.fire(c)
        assertEquals(3, f.ringer.starts.size)
        assertEquals(3, f.budget.current().ringCount)
        endSound(c)

        val silenced = fired(f.fire(c, now = t0 + 30.minutes, rung = t0 + 30.minutes))

        assertFalse(silenced.rung.continuing)
        assertEquals("no second start for c", 3, f.ringer.starts.size)
        assertEquals("SILENT", f.telemetryOf("c").deliveryPath)
        assertEquals(NotificationChannels.QUIET, f.channelOf(33))
        assertEquals("a silent notification spends nothing", 3, f.budget.current().ringCount)
    }

    @Test
    fun `a repeat after the sound has stopped rings again and spends again (ended by the cap)`() =
        repeatAfterTheSoundStopped { f.scheduler.advance(RingPolicy.MAX_RING) }

    @Test
    fun `a repeat after the sound has stopped rings again and spends again (ended by Stop the sound)`() =
        repeatAfterTheSoundStopped {
            f.scheduler.advance(1.minutes)
            controller.stopSound()
        }

    // ---------------------------------------------------------------- the end of grace ends the ring

    /**
     * A Critical occurrence snoozed so that its snooze ends two minutes before its grace ends; the snooze's end rings.
     * Returns the clock just past grace, with the sound still inside its cap (the scheduler is not advanced).
     */
    private fun ringingPastGrace(): Instant {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        f.arming.clock.now = t0 + 1.hours + 48.minutes
        controller.act("a", OccurrenceAction.SNOOZE)
        assertEquals(OccurrenceState.SNOOZED, state("a"))
        val end = t0 + 1.hours + 58.minutes
        f.fire(a, now = end, rung = end)
        assertTrue("the snooze's end rings", sessions.isActive)
        assertEquals(2, f.ringer.starts.size)
        val pastGrace = t0 + 2.hours + 1.minutes
        f.arming.clock.now = pastGrace
        return pastGrace
    }

    private fun assertRingEnded() {
        assertEquals(OccurrenceState.MISSED, state("a"))
        assertFalse("a is no longer listed", sessions.items().any { it.occurrenceId == "a" })
        assertEquals("a's ring ended: the sound and the vibration stopped with the ringer", 2, f.ringer.stops)
        assertNull("a's notification is cancelled", f.posted(31))
    }

    private fun assertSessionEnded() {
        assertRingEnded()
        assertFalse("the session has ended", sessions.isActive)
        assertTrue(sessions.items().isEmpty())
        assertNull(f.posted(RingNotifications.RING_ID))
    }

    @Test
    fun `the end of grace ends the ring (the watchdog pass)`() {
        ringingPastGrace()

        f.arming.watchdog.run()

        assertSessionEnded()
        f.scheduler.advance(RingPolicy.MAX_RING)
        assertEquals("the cap was cancelled with the ring", 2, f.ringer.stops)
    }

    // The fire path reconciles before it delivers: b's fire finds a's grace over, drops a and ends a's ring, and
    // then rings for b as a ring of its own.
    @Test
    fun `the end of grace ends the ring (the fire path)`() {
        val pastGrace = ringingPastGrace()
        val b = f.due("b", Criticality.STANDARD, scheduled = pastGrace, slot = 32)

        f.fire(b, now = pastGrace)

        assertRingEnded()
        assertEquals("b's own ring started after a's ended", 3, f.ringer.starts.size)
        assertTrue("and it is b's session that sounds now", sessions.isActive)
        assertEquals(listOf("b"), sessions.items().map { it.occurrenceId })
    }

    @Test
    fun `the end of grace ends the ring (a process start, which the system pass is)`() {
        ringingPastGrace()

        f.arming.passes.system
            .run()

        assertSessionEnded()
    }

    // The device's zone is the template's here, so the pass moves nothing and its Reconcile is what is tested.
    @Test
    fun `the end of grace ends the ring (the zone change pass)`() {
        ringingPastGrace()
        f.deviceZone = testZone

        f.arming.passes.timezone
            .run()

        assertSessionEnded()
    }

    @Test
    fun `a ring cannot outlive grace by more than the cap with no pass at all`() {
        ringingPastGrace()

        f.scheduler.advance(RingPolicy.MAX_RING)

        assertFalse("the sound has stopped", sessions.isActive)
        assertEquals(2, f.ringer.stops)
        assertEquals("no pass ran: the occurrence is still what the log says", OccurrenceState.SNOOZED, state("a"))
    }

    // ---------------------------------------------------------------- the evidence

    // Three fires: one acknowledged after a minute, one left to the cap, and one whose sound she stops after a minute
    // (the third decided by Claude (technical review), so that a flag set on the shared dismissal path is seen). The
    // acknowledged fire comes first and alone, so that her answer is what ends its session: a flag set whenever a
    // session ends would mark it. (Acknowledged after another occurrence had been capped and was still listed, her
    // answer left that session sounding for the other, and a flag set at a session's end had nothing to mark.)
    @Test
    fun `a ring that ran out is recorded, and one she answered is not`() {
        val b = f.due("b", Criticality.CRITICAL, slot = 31)
        f.fire(b)
        f.scheduler.advance(1.minutes)
        f.arming.clock.now = t0 + 1.minutes
        controller.act("b", OccurrenceAction.ACKNOWLEDGE)
        assertEquals(OccurrenceState.COMPLETED, state("b"))
        assertFalse("her answer ended the session", sessions.isActive)
        assertTrue("and nothing is listed", sessions.items().isEmpty())

        val a = f.due("a", Criticality.CRITICAL, scheduled = t0 + 10.minutes, slot = 32)
        f.fire(a)
        f.scheduler.advance(RingPolicy.MAX_RING)
        assertFalse("the cap ended a's sound", sessions.isActive)

        val c = f.due("c", Criticality.CRITICAL, scheduled = t0 + 20.minutes, slot = 33)
        f.fire(c)
        f.scheduler.advance(1.minutes)
        controller.stopSound()
        assertFalse(sessions.isActive)
        f.scheduler.advance(RingPolicy.MAX_RING)

        assertEquals("the ring that ran out", true, f.telemetryOf("a").ringEndedByCap)
        assertEquals("the one she answered", false, f.telemetryOf("b").ringEndedByCap)
        assertEquals("the one she stopped", false, f.telemetryOf("c").ringEndedByCap)
        val report =
            f.arming.graph
                .get<ReliabilityReader>()
                .read(t0 + 30.minutes)
        assertEquals("the report's count", 1, report.capEndedRings)
        val json = ReliabilityExport.toJson(report, DeviceInfo("Pixel 7", 36, "0.1.0", 1))
        assertEquals("the export carries the count", 1, JSONObject(json).getInt("capEndedRings"))
        assertFalse("and no timestamp", Regex("\"(firedAt|scheduledAt|time|timestamp)\"").containsMatchIn(json))
        assertFalse(json.contains((t0 + 14.minutes).toEpochMilliseconds().toString()))
    }
}
