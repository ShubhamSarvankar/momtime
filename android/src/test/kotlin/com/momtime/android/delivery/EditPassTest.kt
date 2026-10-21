package com.momtime.android.delivery

import android.app.Notification
import android.content.Context
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.arming.FireOutcome
import com.momtime.android.arming.t0
import com.momtime.android.data.template
import com.momtime.android.data.testZone
import com.momtime.android.ring.RingActivity
import com.momtime.android.ring.RingEntryPoint
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.FakePlayer
import com.momtime.android.ringer.FakePlayers
import com.momtime.android.ringer.FakeScheduler
import com.momtime.android.ringer.RingPolicy
import com.momtime.android.ringer.RingSound
import com.momtime.android.ringer.RingerEntryPoint
import com.momtime.android.ringer.RingerService
import com.momtime.android.ringer.VolumeRamp
import com.momtime.android.work.TemplateEditPass
import com.momtime.shared.data.EditResult
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.OccurrenceAction
import com.momtime.shared.engine.RungDelivery
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.toInstant
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
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What a template edit does on the alarm path (ADR 0079 items 8 and 10; `docs/phase-3-plan.md`, PR 4), through the
 * real edit pass (`TemplateEditPass`), the real fire path, the real watchdog pass and the real delivery port over a
 * real database and the shadowed platform. The ringer service launch, the overlay and the speaker are fakes, so this
 * shows which path the code takes and not what a device does (`MANUAL_CHECKS.md` P3-7).
 *
 * Every assertion that something is absent (no `MISSED`, no silent notice, no event, no ringer) follows the step that
 * would have produced it if the code were wrong: the watchdog pass after grace, the old alarm's fire, the cap.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 36])
class EditPassTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixtures = mutableListOf<DeliveryFixture>()

    /** The fixture over the fake ringer launch, built on first use and wired as the screen's and the buttons' host. */
    private val f: DeliveryFixture by lazy { fixture() }
    private val sessions: RingSessions get() = f.arming.graph.get()
    private val templates: ScheduleTemplateRepository get() = f.arming.graph.get()
    private val pass: TemplateEditPass get() = f.arming.graph.get()
    private val canLoseExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @After
    fun tearDown() {
        RingEntryPoint.provider = null
        RingerEntryPoint.provider = null
        NotificationActionEntryPoint.provider = null
        ringers.forEach { it.destroyAll() }
        fixtures.forEach { it.close() }
    }

    private fun fixture(): DeliveryFixture = DeliveryFixture(context).also(::wire)

    private fun wire(fixture: DeliveryFixture) {
        fixtures += fixture
        val host: RingController = fixture.arming.graph.get()
        RingEntryPoint.provider = { host }
        RingEntryPoint.executor = Executor { run -> run.run() }
        NotificationActionEntryPoint.provider = { host }
        RingerEntryPoint.provider = { host }
    }

    private fun at(
        date: LocalDate,
        hour: Int,
    ): Instant = LocalDateTime(date, LocalTime(hour, 0)).toInstant(testZone)

    /** A template at [hour] with one occurrence on [date] (the template is inserted once), open and not yet armed. */
    @Suppress("LongParameterList")
    private fun seedAt(
        fixture: DeliveryFixture,
        id: String,
        criticality: Criticality,
        date: LocalDate,
        hour: Int,
        slot: Int,
        templateId: String = "tmpl-$id",
    ): Occurrence {
        val repository: ScheduleTemplateRepository = fixture.arming.graph.get()
        if (repository.findById(templateId) == null) {
            repository.insert(template(templateId).copy(criticality = criticality, timeOfDay = LocalTime(hour, 0)))
        }
        val occurrence =
            Occurrence(id, templateId, date, at(date, hour), testZone, OccurrenceState.PENDING, slot, criticality)
        fixture.arming.occurrences.insert(occurrence)
        return occurrence
    }

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

    /** A fixture whose ringer is the real service and whose sound is the real sound over fake players. */
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
            ).also(::wire)
        val sessions: RingSessions get() = f.arming.graph.get()
        val pass: TemplateEditPass get() = f.arming.graph.get()

        fun templates(id: String) =
            f.arming.graph
                .get<ScheduleTemplateRepository>()
                .findById(id)

        /** The player that is playing now, if one is. */
        fun playing(): FakePlayer? = players.created.lastOrNull()?.takeIf { it.started && !it.stopped }
    }

    private fun state(id: String): OccurrenceState? =
        f.arming.occurrences
            .findById(id)
            ?.state

    private fun deactivate(templateId: String): EditResult =
        pass.run(checkNotNull(templates.findById(templateId)).copy(active = false))

    private fun buttonTags(activity: android.app.Activity): List<String> {
        val tags = mutableListOf<String>()

        fun walk(view: View) {
            if (view is Button && view.tag is String) tags += view.tag as String
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(activity.findViewById<LinearLayout>(R.id.ring_items))
        return tags
    }

    /** Every line of text the screen shows under its list, in order; the buttons are left out. */
    private fun itemTexts(activity: android.app.Activity): List<String> {
        val texts = mutableListOf<String>()

        fun walk(view: View) {
            if (view is TextView && view !is Button) texts += view.text.toString()
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(activity.findViewById<LinearLayout>(R.id.ring_items))
        return texts
    }

    private fun labels(notification: Notification): List<String> =
        notification.actions.orEmpty().map { it.title.toString() }

    private fun screen(): android.app.Activity =
        Robolectric
            .buildActivity(RingActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()

    // ---------------------------------------------------------------- a time move re arms

    // ADR 0079 item 8: the alarm is correct when the edit returns. The armed occurrence moves to 09:00 and keeps its
    // slot, so the one alarm the platform holds is for 09:00 with the same request code, and one new
    // ALARM_SCHEDULED says so. Without the pass's ensureArmed the old 08:00 alarm is still the one held.
    @Test
    fun `an edit of the armed occurrence's time re arms it`() {
        val tomorrow = LocalDate(2026, 3, 2)
        seedAt(f, "a", Criticality.CRITICAL, tomorrow, hour = 8, slot = 31)
        f.arming.coordinator.ensureArmed()
        assertEquals(
            "armed for tomorrow 08:00",
            listOf(at(tomorrow, 8).toEpochMilliseconds()),
            f.arming.alarms().map { it.triggerAtMs },
        )
        assertEquals(1, f.arming.count(EventType.ALARM_SCHEDULED, "a"))

        val result = pass.run(checkNotNull(templates.findById("tmpl-a")).copy(timeOfDay = LocalTime(9, 0)))

        assertEquals("the edit moved the one occurrence", 1, (result as EditResult.Edited).moved)
        assertEquals(
            at(tomorrow, 9),
            f.arming.occurrences
                .findById("a")
                ?.scheduledInstant,
        )
        val shapes = f.arming.shapes()
        assertEquals("exactly one alarm", 1, shapes.size)
        assertEquals("at 09:00", at(tomorrow, 9).toEpochMilliseconds(), shapes.single().triggerAtMs)
        assertEquals("with the occurrence's slot", 31, shapes.single().requestCode)
        assertEquals("one new ALARM_SCHEDULED", 2, f.arming.count(EventType.ALARM_SCHEDULED, "a"))
    }

    // ---------------------------------------------------------------- deactivating a ring

    // ADR 0079 item 8: the ring session drops what was withdrawn, its sound stops and its notifications go, inside
    // the pass. The assertions come straight after the pass, before the cap and before any Reconcile could stop the
    // ring on its own. The watchdog pass after the end of grace is then the step that would write MISSED, post a
    // silent notice or repair an alarm if the occurrence were still open.
    @Test
    fun `deactivating a ringing reminder stops the ring`() {
        val r = Real()
        val a = r.f.due("a", Criticality.CRITICAL, slot = 31)
        r.f.fire(a)
        assertTrue("it is ringing", r.sessions.isActive)
        assertNotNull("the sound is playing", r.playing())
        assertNotNull("the service holds the ring notification", r.f.posted(RingNotifications.RING_ID))

        val result = r.pass.run(checkNotNull(r.templates("tmpl-a")).copy(active = false))

        assertEquals(1, (result as EditResult.Edited).withdrawn.size)
        assertFalse("the session has ended", r.sessions.isActive)
        assertTrue("nothing is listed", r.sessions.items().isEmpty())
        assertEquals("the ringer was stopped", 1, r.ringer.stops)
        assertNull("the sound is off", r.playing())
        assertNull("the session's notification is cancelled", r.f.posted(RingNotifications.RING_ID))
        assertNull("and the slot's", r.f.posted(31))
        assertTrue(
            "no alarm is armed",
            r.f.arming
                .alarms()
                .isEmpty(),
        )
        assertEquals(
            OccurrenceState.WITHDRAWN,
            r.f.arming.occurrences
                .findById("a")
                ?.state,
        )
        assertEquals("and she did not complete it", 0, r.f.arming.count(EventType.COMPLETED, "a"))

        // The step that would produce a miss, a silent notice or a repair if it were still open: the watchdog pass
        // after the occurrence's grace (Critical: two hours) has ended.
        r.f.arming.clock.now = a.scheduledInstant + 3.hours
        r.f.arming.watchdog
            .run()

        assertEquals("no MISSED", 0, r.f.arming.count(EventType.MISSED, "a"))
        assertEquals("no WATCHDOG_REPAIR", 0, r.f.arming.count(EventType.WATCHDOG_REPAIR, "a"))
        assertTrue("no silent notice", shadowOf(r.f.notifications).allNotifications.isEmpty())
        assertEquals(
            OccurrenceState.WITHDRAWN,
            r.f.arming.occurrences
                .findById("a")
                ?.state,
        )
    }

    @Test
    fun `deactivating one of two ringing leaves the other`() {
        val r = Real()
        val a = r.f.due("a", Criticality.CRITICAL, slot = 31)
        val b = r.f.due("b", Criticality.CRITICAL, slot = 32)
        r.f.fire(a)
        r.f.fire(b)
        assertEquals(listOf("a", "b"), r.sessions.items().map { it.occurrenceId })
        assertTrue(
            "with two, the notification carries no actions",
            labels(r.f.posted(RingNotifications.RING_ID)!!).isEmpty(),
        )

        r.pass.run(checkNotNull(r.templates("tmpl-a")).copy(active = false))

        assertEquals(
            "a is withdrawn",
            OccurrenceState.WITHDRAWN,
            r.f.arming.occurrences
                .findById("a")
                ?.state,
        )
        assertEquals(
            "b is untouched",
            OccurrenceState.PENDING,
            r.f.arming.occurrences
                .findById("b")
                ?.state,
        )
        assertTrue("the other still rings", r.sessions.isActive)
        assertNotNull("its sound is still playing", r.playing())
        assertEquals(listOf("b"), r.sessions.items().map { it.occurrenceId })
        assertEquals("the ringer was not stopped", 0, r.ringer.stops)
        val notification = checkNotNull(r.f.posted(RingNotifications.RING_ID)) { "the session's notification is gone" }
        assertEquals(
            "with its actions, as one occurrence in the session has them",
            listOf(R.string.ring_action_acknowledge, R.string.ring_action_snooze, R.string.ring_action_skip)
                .map(context::getString),
            labels(notification),
        )
        assertNull("a's slot notification is gone", r.f.posted(31))
        assertEquals(
            "b is the one left",
            b.id,
            r.sessions
                .items()
                .single()
                .occurrenceId,
        )
    }

    // The cap ends the sound and keeps her notification and the list (ADR 0090 item 3). A deactivation then has to
    // drop the occurrence even though no sound is on: the session lists nothing, the kept notification is cancelled,
    // the screen has nothing, and the watchdog pass after grace writes no MISSED.
    @Test
    fun `deactivating a reminder whose sound the cap ended drops it and cancels its kept notification`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        val screen = screen()
        f.scheduler.advance(RingPolicy.MAX_RING)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse("the cap ended the sound", sessions.isActive)
        assertEquals("it is still listed", listOf("a"), sessions.items().map { it.occurrenceId })
        assertNotNull("and her notification is kept", f.posted(RingNotifications.RING_ID))
        assertEquals(listOf("ack:a", "snooze:a", "skip:a"), buttonTags(screen))

        deactivate("tmpl-a")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertTrue("the session holds no item", sessions.items().isEmpty())
        assertNull("the kept notification is cancelled", f.posted(RingNotifications.RING_ID))
        assertNull("and the slot's", f.posted(31))
        assertTrue("the ring screen lists nothing", buttonTags(screen).isEmpty())
        assertEquals(OccurrenceState.WITHDRAWN, state("a"))
        assertTrue("no alarm is armed for it", f.arming.alarms().isEmpty())

        f.arming.clock.now = a.scheduledInstant + 3.hours
        f.arming.watchdog.run()

        assertEquals("no MISSED", 0, f.arming.count(EventType.MISSED, "a"))
        assertTrue("no silent notice", shadowOf(f.notifications).allNotifications.isEmpty())
    }

    // ---------------------------------------------------------------- a snoozed reminder deactivated

    // ADR 0079 rule 3: a snoozed occurrence is open, so a deactivation withdraws it, and the pass's ensureArmed
    // then arms the other occurrence's rung and cancels the snooze's alarm by its slot. The old snooze alarm's
    // broadcast after its end is the step that would write SNOOZE_ENDED, and the watchdog pass after it the step
    // that would repair, if the occurrence were still open.
    @Test
    fun `deactivating a snoozed reminder cancels its snooze alarm`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        val b = f.due("b", Criticality.CRITICAL, scheduled = t0 + 2.hours, slot = 32)
        f.fire(a)
        f.arming.clock.now = a.scheduledInstant + 1.minutes
        f.arming.graph
            .get<RingController>()
            .act("a", OccurrenceAction.SNOOZE)
        val end = a.scheduledInstant + 11.minutes
        assertEquals(OccurrenceState.SNOOZED, state("a"))
        assertEquals(
            "the snooze's end is the one alarm armed, in a's slot",
            listOf(end.toEpochMilliseconds() to 31),
            f.arming.shapes().map { it.triggerAtMs to it.requestCode },
        )
        val starts = f.ringer.starts.size

        val result = deactivate("tmpl-a")

        assertEquals(OccurrenceState.WITHDRAWN, state("a"))
        assertEquals(1, (result as EditResult.Edited).withdrawn.size)
        assertEquals(
            "exactly one alarm: b's rung, in b's slot",
            listOf(b.scheduledInstant.toEpochMilliseconds() to 32),
            f.arming.shapes().map { it.triggerAtMs to it.requestCode },
        )
        assertEquals(
            32,
            f.arming.armed
                .current()
                ?.alarmSlot,
        )
        assertTrue("nothing is listed", sessions.items().isEmpty())
        assertTrue("no notification is posted", shadowOf(f.notifications).allNotifications.isEmpty())

        // The snooze's old end has passed: the old alarm's broadcast arrives anyway, then the watchdog pass runs.
        f.arming.clock.now = end + 1.minutes
        val outcome = f.fire(a, now = end + 1.minutes, rung = end)
        assertEquals(FireOutcome.Terminal, outcome)
        assertEquals("no SNOOZE_ENDED", 0, f.arming.count(EventType.SNOOZE_ENDED, "a"))
        val watchdog = f.arming.watchdog.run()

        assertEquals("no WATCHDOG_REPAIR", 0, f.arming.count(EventType.WATCHDOG_REPAIR, "a", "b"))
        assertTrue("no evidence: ${watchdog.evidence}", watchdog.evidence.isEmpty())
        assertEquals("still no SNOOZE_ENDED", 0, f.arming.count(EventType.SNOOZE_ENDED, "a"))
        assertEquals("nothing rang again", starts, f.ringer.starts.size)
        assertEquals(
            "b's rung is still the one alarm",
            listOf(b.scheduledInstant.toEpochMilliseconds() to 32),
            f.arming.shapes().map { it.triggerAtMs to it.requestCode },
        )
        assertEquals(OccurrenceState.WITHDRAWN, state("a"))
    }

    // ---------------------------------------------------------------- the text of a ringing reminder

    // ARCHITECTURE.md 3.2 and ADR 0079 item 4: title, dosage and the doctor's instructions are display and change at
    // once. An edit of them while the occurrence rings, or is still listed after the cap ended the sound, reaches
    // the ring screen and the session's notification inside the pass: the notification is replaced under its id
    // with the same actions and only alert once; the ring itself is untouched, nothing is written, no sound starts.
    @Test
    fun `an edit of the dosage while ringing reaches the ring screen and the notification at once`() =
        textFollowsTheEdit(capped = false)

    @Test
    fun `an edit of the dosage after the cap ended the sound reaches the ring screen and the notification at once`() =
        textFollowsTheEdit(capped = true)

    @Suppress("LongMethod")
    private fun textFollowsTheEdit(capped: Boolean) {
        val r = Real()
        val a = r.f.arming.seed("a", Criticality.CRITICAL, t0, slot = 31, dosage = "1 tablet")
        r.f.arming.clock.now = t0 - 1.hours
        r.f.arming.coordinator
            .ensureArmed()
        r.f.fire(a)
        val screen = screen()
        if (capped) {
            r.scheduler.advance(RingPolicy.MAX_RING)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse("the cap ended the sound", r.sessions.isActive)
            assertNull(r.playing())
        } else {
            assertTrue("it is ringing", r.sessions.isActive)
            assertNotNull(r.playing())
        }
        val before = checkNotNull(r.f.posted(RingNotifications.RING_ID)) { "the session's notification is posted" }
        assertEquals("Iron tablet", before.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("1 tablet", before.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(
            listOf("Iron tablet", context.getString(R.string.ring_dosage_label), "1 tablet"),
            itemTexts(screen),
        )
        val sounding = r.sessions.isActive
        val player = r.playing()
        val players = r.players.created.size
        val stops = r.ringer.stops
        val log = r.f.arming.eventLog("a")
        val alarms = r.f.arming.shapes()

        val result =
            r.pass.run(
                checkNotNull(r.templates("tmpl-a")).copy(title = "Iron, with food", dosage = "2 tablets"),
            )
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("nothing withdrawn, nothing moved", EditResult.Edited(emptyList(), 0), result)
        val item = r.sessions.items().single()
        assertEquals("the session item carries the new title", "Iron, with food", item.title)
        assertEquals("and the new dosage", "2 tablets", item.dosage)
        assertEquals(
            "with its actions as they were",
            setOf(OccurrenceAction.ACKNOWLEDGE, OccurrenceAction.SNOOZE, OccurrenceAction.SKIP),
            item.actions,
        )
        assertEquals(
            "the ring screen shows the new text",
            listOf("Iron, with food", context.getString(R.string.ring_dosage_label), "2 tablets"),
            itemTexts(screen),
        )
        assertEquals("with its buttons", listOf("ack:a", "snooze:a", "skip:a"), buttonTags(screen))
        val after = checkNotNull(r.f.posted(RingNotifications.RING_ID)) { "the notification is still posted" }
        assertEquals(
            "the notification carries the new title",
            "Iron, with food",
            after.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals("and the new dosage", "2 tablets", after.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("the same actions", labels(before), labels(after))
        assertEquals(
            "only alert once",
            Notification.FLAG_ONLY_ALERT_ONCE,
            after.flags and Notification.FLAG_ONLY_ALERT_ONCE,
        )
        assertEquals(
            "ongoing as before",
            before.flags and Notification.FLAG_ONGOING_EVENT,
            after.flags and Notification.FLAG_ONGOING_EVENT,
        )
        assertEquals("on the same channel", before.channelId, after.channelId)
        assertEquals("one notification, under the session's id", 1, shadowOf(r.f.notifications).allNotifications.size)
        assertEquals("the ringer state is unchanged", sounding, r.sessions.isActive)
        assertEquals("the same sound, or none", player, r.playing())
        assertEquals("no new player", players, r.players.created.size)
        assertEquals("no second ringer start", 1, r.ringer.starts.size)
        assertEquals("the ringer was not stopped by the edit", stops, r.ringer.stops)
        assertEquals("no event is written", log, r.f.arming.eventLog("a"))
        assertEquals("the alarm is as it was", alarms, r.f.arming.shapes())
        assertEquals(
            OccurrenceState.PENDING,
            r.f.arming.occurrences
                .findById("a")
                ?.state,
        )
    }

    // ---------------------------------------------------------------- notifications outside any session

    // A withdrawn occurrence's notification is cancelled whether or not the ring session ever held it: a plain
    // notification at Tier 1, a Gentle one at Tier 3 and a silent notice beyond the catch up window are each posted
    // under the occurrence's slot and are in no session, so only the pass's own cancellation reaches them.
    @Test
    @Suppress("LongMethod")
    fun `a withdrawn occurrence's notification outside any session is cancelled`() {
        data class Case(
            val name: String,
            val slot: Int,
            val channel: String,
            val prepare: (DeliveryFixture) -> Occurrence,
            val fire: (DeliveryFixture, Occurrence) -> Unit,
        )
        val cases =
            buildList {
                if (canLoseExact) {
                    add(
                        Case(
                            "Tier 1 plain delivery",
                            31,
                            NotificationChannels.CRITICAL,
                            { fx ->
                                ShadowAlarmManager.setCanScheduleExactAlarms(false)
                                fx.due("a", Criticality.CRITICAL, slot = 31)
                            },
                            { fx, o -> fx.fire(o) },
                        ),
                    )
                }
                add(
                    Case(
                        "a Gentle notification at Tier 3",
                        33,
                        NotificationChannels.GENTLE,
                        { fx -> fx.due("a", Criticality.GENTLE, slot = 33) },
                        { fx, o -> fx.fire(o) },
                    ),
                )
                add(
                    Case(
                        "a silent notice beyond the catch up window",
                        31,
                        NotificationChannels.QUIET,
                        { fx -> fx.due("a", Criticality.CRITICAL, slot = 31) },
                        { fx, o -> fx.fire(o, now = o.scheduledInstant + 31.minutes) },
                    ),
                )
            }
        for (case in cases) {
            val fx = fixture()
            val a = case.prepare(fx)
            case.fire(fx, a)
            assertTrue(
                "${case.name}: no ring session holds it",
                fx.arming.graph
                    .get<RingSessions>()
                    .items()
                    .isEmpty(),
            )
            val posted = fx.posted(case.slot)
            assertNotNull("${case.name}: the notification is posted", posted)
            assertEquals("${case.name}: on its channel", case.channel, posted!!.channelId)

            val editPass: TemplateEditPass = fx.arming.graph.get()
            val result =
                editPass.run(
                    checkNotNull(
                        fx.arming.graph
                            .get<ScheduleTemplateRepository>()
                            .findById("tmpl-a"),
                    ).copy(active = false),
                )

            assertEquals("${case.name}: the occurrence is withdrawn", 1, (result as EditResult.Edited).withdrawn.size)
            assertNull("${case.name}: the slot's notification is gone", fx.posted(case.slot))
            assertTrue("${case.name}: nothing else is posted", shadowOf(fx.notifications).allNotifications.isEmpty())
        }
    }

    // ---------------------------------------------------------------- Gentle to Critical

    // ADR 0089 and ADR 0079 item 6. Today's Gentle occurrence has come due by its instant and has not fired: its
    // alarm is late. The edit to Critical must not reach it, so its late first rung, delivered within the catch up
    // window, is a plain notification on the Gentle channel and starts no ringer. Tomorrow's occurrence takes the edit
    // and rings on the Critical channel with a full screen intent.
    @Test
    fun `a template edited from Gentle to Critical rings on its next occurrence and not on one that has come due`() {
        val today = LocalDate(2026, 3, 1)
        val tomorrow = LocalDate(2026, 3, 2)
        val g0 = seedAt(f, "g0", Criticality.GENTLE, today, hour = 8, slot = 41, templateId = "tmpl-g")
        val g1 = seedAt(f, "g1", Criticality.GENTLE, tomorrow, hour = 8, slot = 42, templateId = "tmpl-g")
        f.arming.clock.now = g0.scheduledInstant - 1.hours
        f.arming.coordinator.ensureArmed()
        // Her alarm is late: ten minutes past its instant, nothing has fired and no ALARM_FIRED exists.
        f.arming.clock.now = g0.scheduledInstant + 10.minutes
        assertEquals(0, f.arming.count(EventType.ALARM_FIRED, "g0"))

        val result = pass.run(checkNotNull(templates.findById("tmpl-g")).copy(criticality = Criticality.CRITICAL))

        assertEquals("only tomorrow's takes the edit", 1, (result as EditResult.Edited).moved)
        assertEquals(
            "today's keeps GENTLE",
            Criticality.GENTLE,
            f.arming.occurrences
                .findById("g0")
                ?.criticality,
        )
        assertEquals(
            "tomorrow's is CRITICAL",
            Criticality.CRITICAL,
            f.arming.occurrences
                .findById("g1")
                ?.criticality,
        )

        // Today's late first rung, delivered within the window: still Gentle's plain notification.
        val late = f.fire(g0, now = g0.scheduledInstant + 20.minutes) as FireOutcome.Fired
        assertEquals(RungDelivery.NOTIFICATION, late.rung.policy)
        f.assertNothingRang()
        assertFalse("no ring session", sessions.isActive)
        val plain = checkNotNull(f.posted(41)) { "today's notification is posted under its slot" }
        assertEquals(NotificationChannels.GENTLE, plain.channelId)
        assertNull("with no full screen intent", plain.fullScreenIntent)
        assertEquals("GENTLE's path", "PLAIN", f.telemetryOf("g0").deliveryPath)

        // Tomorrow's fire: the ringer, on the Critical channel, with a full screen intent.
        f.fire(g1)
        val request = f.ringer.starts.single()
        assertEquals(NotificationChannels.CRITICAL, request.channelId)
        assertTrue("with a full screen intent", request.fullScreenIntent)
        assertEquals("RING", f.telemetryOf("g1").deliveryPath)
    }

    // ---------------------------------------------------------------- a withdrawn occurrence's old alarm

    // The old alarm's broadcast arrives after the occurrence was withdrawn (an alarm already in flight, or one the
    // platform kept): it writes nothing and delivers nothing, and the fire path still arms what is next.
    @Test
    fun `a withdrawn occurrence's late alarm delivers nothing`() {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.due("b", Criticality.CRITICAL, scheduled = t0 + 2.hours, slot = 32)
        deactivate("tmpl-a")
        assertEquals(OccurrenceState.WITHDRAWN, state("a"))
        assertEquals("b's rung is what is armed", listOf(32), f.arming.alarms().map { f.arming.requestCode(it) })
        val before = f.arming.eventLog("a", "b")
        // The platform delivers the old alarm anyway; nothing is armed when it arrives.
        f.arming.clearAlarms()
        assertTrue(f.arming.alarms().isEmpty())

        val outcome = f.fire(a)

        assertEquals(FireOutcome.Terminal, outcome)
        assertEquals("no event", before, f.arming.eventLog("a", "b"))
        assertTrue("no notification", shadowOf(f.notifications).allNotifications.isEmpty())
        f.assertNothingRang()
        assertFalse(sessions.isActive)
        assertEquals(
            "and ensureArmed ran: b's rung is armed again",
            listOf(32),
            f.arming.alarms().map { f.arming.requestCode(it) },
        )
    }
    // The pass's other two steps (ADR 0079 item 8), each its own guard. Added in PR 4 for review: the section names
    // no test for either.

    // The window is materialised inside the pass: a date the edited template wants has its occurrence when the edit
    // returns, and the one alarm is still the earliest rung.
    @Test
    fun `an edit's pass materialises the window`() {
        val today = LocalDate(2026, 3, 1)
        seedAt(f, "a", Criticality.CRITICAL, today, hour = 15, slot = 31)
        f.arming.coordinator.ensureArmed()
        assertEquals(
            "one occurrence before",
            1,
            f.arming.occurrences
                .findForTemplate("tmpl-a")
                .size,
        )

        pass.run(checkNotNull(templates.findById("tmpl-a")).copy(title = "Iron"))

        val dates =
            f.arming.occurrences
                .findForTemplate("tmpl-a")
                .map { it.localDate }
                .toSet()
        assertEquals("tomorrow's occurrence exists after the pass", setOf(today, LocalDate(2026, 3, 2)), dates)
        assertEquals("Iron", checkNotNull(templates.findById("tmpl-a")).title)
    }

    // Reconcile runs inside the pass: an occurrence whose grace ended while the app was closed is judged when the
    // edit returns, against its own unmoved grace, and not left for the watchdog.
    @Test
    fun `an edit's pass runs Reconcile`() {
        val a = f.due("a", Criticality.STANDARD, scheduled = t0 - 5.hours, slot = 31)
        assertEquals("still open: no pass has run since its grace ended", OccurrenceState.PENDING, state("a"))

        pass.run(checkNotNull(templates.findById("tmpl-a")).copy(title = "Iron"))

        assertEquals("MISSED, by the pass", OccurrenceState.MISSED, state("a"))
        assertEquals(1, f.arming.count(EventType.MISSED, "a"))
        assertEquals(
            "at the end of its own grace",
            a.scheduledInstant + 4.hours,
            f.arming
                .eventsOf("a", EventType.MISSED)
                .single()
                .effectiveAt,
        )
    }
}
