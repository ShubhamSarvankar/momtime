package com.momtime.android.delivery

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.widget.Button
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.ring.RingActivity
import com.momtime.android.ring.RingEntryPoint
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.FakePlayer
import com.momtime.android.ringer.FakePlayers
import com.momtime.android.ringer.FakeScheduler
import com.momtime.android.ringer.RingSound
import com.momtime.android.ringer.RingerEntryPoint
import com.momtime.android.ringer.RingerService
import com.momtime.android.ringer.VolumeRamp
import com.momtime.android.work.TemplateEditPass
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.engine.OccurrenceAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.minutes

/**
 * What the two test classes of ADR 0080 share (`SpreadTest`, `SpreadRefreshTest`): the fixtures, wired as the
 * screen's, the buttons' and the ringer service's host; a fixture over the real `RingerService` and the real sound
 * over fake players, for the cases where the session's notification must be the one the service holds; and the
 * readers of what the platform shows. The invariant (ADR 0080 item 4) is read from the platform, not from the
 * code's own bookkeeping: for every active notification, the slot and the action each button's intent names, so a
 * notification that carries an occurrence's buttons is found whatever id it was posted under.
 */
internal abstract class SpreadSupport {
    protected val context: Context = RuntimeEnvironment.getApplication()
    protected val fixtures = mutableListOf<DeliveryFixture>()

    /** The fixture over the fake ringer launch, built on first use and wired as the screen's and the buttons' host. */
    protected val f: DeliveryFixture by lazy { fixture() }
    protected val sessions: RingSessions get() = f.arming.graph.get()
    protected val controller: RingController get() = f.arming.graph.get()

    @After
    fun tearDown() {
        RingEntryPoint.provider = null
        RingerEntryPoint.provider = null
        NotificationActionEntryPoint.provider = null
        ringers.forEach { it.destroyAll() }
        fixtures.forEach { it.close() }
    }

    protected fun fixture(): DeliveryFixture = DeliveryFixture(context).also(::wire)

    protected fun wire(fixture: DeliveryFixture) {
        fixtures += fixture
        val host: RingController = fixture.arming.graph.get()
        RingEntryPoint.provider = { host }
        RingEntryPoint.executor = Executor { run -> run.run() }
        NotificationActionEntryPoint.provider = { host }
        RingerEntryPoint.provider = { host }
    }

    /** The ringer service itself, driven through Robolectric's controller, started and stopped as the launcher does. */
    protected inner class RealRinger : RingerLauncher {
        val starts = mutableListOf<RingerRequest>()
        var stops = 0
        private val services = mutableListOf<ServiceController<RingerService>>()
        private val live = mutableListOf<ServiceController<RingerService>>()

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
            RingerService.running?.detach()
            live.removeLastOrNull()?.destroy()
        }

        fun destroyAll() = live.toList().forEach { stop() }
    }

    private val ringers = mutableListOf<RealRinger>()

    /**
     * A fixture whose ringer is the real service and whose sound is the real sound over fake players. The shade
     * outlives a fixture (one `NotificationManager` per test process), so it starts with the shade empty.
     */
    protected inner class Real {
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
                .also { it.notifications.cancelAll() }
        val sessions: RingSessions get() = f.arming.graph.get()
        val controller: RingController get() = f.arming.graph.get()
        val pass: TemplateEditPass get() = f.arming.graph.get()

        fun template(id: String) =
            checkNotNull(
                f.arming.graph
                    .get<ScheduleTemplateRepository>()
                    .findById(id),
            )

        fun playing(): FakePlayer? = players.created.lastOrNull()?.takeIf { it.started && !it.stopped }

        fun state(id: String): OccurrenceState? =
            f.arming.occurrences
                .findById(id)
                ?.state
    }

    // ---------------------------------------------------------------- reading notifications

    protected val threeActions: List<String>
        get() =
            listOf(R.string.ring_action_acknowledge, R.string.ring_action_snooze, R.string.ring_action_skip)
                .map(context::getString)

    protected val twoActions: List<String>
        get() = listOf(R.string.ring_action_acknowledge, R.string.ring_action_skip).map(context::getString)

    protected val sixButtons = listOf("ack:a", "snooze:a", "skip:a", "ack:b", "snooze:b", "skip:b")

    protected fun labels(notification: Notification): List<String> =
        notification.actions.orEmpty().map { it.title.toString() }

    protected fun title(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    protected fun text(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    protected fun hasFlag(
        notification: Notification,
        flag: Int,
    ): Boolean = notification.flags and flag != 0

    protected fun count(fixture: DeliveryFixture): Int = shadowOf(fixture.notifications).allNotifications.size

    protected fun ids(fixture: DeliveryFixture): List<Int> = fixture.notifications.activeNotifications.map { it.id }

    /** The session's notification, asserted to be posted. */
    protected fun session(
        fixture: DeliveryFixture,
        label: String = "the session's notification is posted",
    ): Notification = checkNotNull(fixture.posted(RingNotifications.RING_ID)) { label }

    /** The (slot, action) each button of [notification] names, decoded from its intent. */
    private fun buttons(notification: Notification): List<Pair<Int, OccurrenceAction>> =
        notification.actions.orEmpty().map { action ->
            val intent = shadowOf(action.actionIntent).savedIntent
            RingActionIntents.slotOf(intent) to checkNotNull(RingActionIntents.actionOf(intent))
        }

    /**
     * ADR 0080 item 4, read from the platform: every listed occurrence (ringing, or listed after its sound was
     * dismissed) has exactly one notification carrying exactly its offered actions, whatever id it is under, and no
     * notification carries a button of any occurrence whose slot is in [resolved].
     */
    protected fun assertInvariant(
        label: String,
        fixture: DeliveryFixture,
        resolved: Set<Int> = emptySet(),
    ) {
        val sessions: RingSessions = fixture.arming.graph.get()
        val all = fixture.notifications.activeNotifications.associate { it.id to buttons(it.notification) }
        for (item in sessions.items()) {
            val carriers = all.filterValues { buttons -> buttons.any { it.first == item.alarmSlot } }
            assertEquals(
                "$label: ${item.occurrenceId} has exactly one notification carrying its actions, " +
                    "found ${carriers.keys}",
                1,
                carriers.size,
            )
            assertEquals(
                "$label: ${item.occurrenceId}'s notification carries exactly its offered actions",
                item.actions,
                carriers.values
                    .single()
                    .map { it.second }
                    .toSet(),
            )
        }
        for (slot in resolved) {
            val carriers = all.filterValues { buttons -> buttons.any { it.first == slot } }
            assertTrue(
                "$label: a resolved occurrence has no notification with its actions: ${carriers.keys}",
                carriers.isEmpty(),
            )
        }
    }

    /** Presses the button labelled [label] on [notification], through the real receiver. */
    protected fun press(
        notification: Notification,
        label: Int,
    ) {
        val action = notification.actions.single { it.title.toString() == context.getString(label) }
        val done = deliverToActionReceiver(context, shadowOf(action.actionIntent).savedIntent)
        assertTrue("the receiver did not finish", finished(done))
    }

    /** The per occurrence shape (ADR 0080 item 3), on each of its points. */
    protected fun assertOwn(
        notification: Notification,
        channel: String,
        actions: List<String>,
        label: String,
    ) {
        assertEquals("$label: on its criticality's channel", channel, notification.channelId)
        assertEquals("$label: exactly its offered actions", actions, labels(notification))
        assertNull("$label: no full screen intent", notification.fullScreenIntent)
        assertNull("$label: no delete intent", notification.deleteIntent)
        assertFalse("$label: not ongoing", hasFlag(notification, Notification.FLAG_ONGOING_EVENT))
        assertTrue("$label: only alert once", hasFlag(notification, Notification.FLAG_ONLY_ALERT_ONCE))
        assertFalse("$label: not auto cancelled by a tap", hasFlag(notification, Notification.FLAG_AUTO_CANCEL))
        assertEquals("$label: an alarm", Notification.CATEGORY_ALARM, notification.category)
        for (action in notification.actions) {
            val pending = shadowOf(action.actionIntent)
            assertTrue("$label: a broadcast", pending.isBroadcastIntent)
            assertTrue("$label: immutable", pending.isImmutable)
            assertEquals(
                "$label: explicit, to the action receiver",
                ComponentName(context, RingActionReceiver::class.java),
                pending.savedIntent.component,
            )
        }
    }

    protected fun screen(): android.app.Activity =
        Robolectric
            .buildActivity(RingActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()

    protected fun buttonTags(activity: android.app.Activity): List<String> {
        val tags = mutableListOf<String>()

        fun walk(view: android.view.View) {
            if (view is Button && view.tag is String) tags += view.tag as String
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(activity.findViewById(R.id.ring_items))
        return tags
    }

    /** Every line of text the screen shows under its list, in order; the buttons are left out. */
    protected fun itemTexts(activity: android.app.Activity): List<String> {
        val texts = mutableListOf<String>()

        fun walk(view: android.view.View) {
            if (view is TextView && view !is Button) texts += view.text.toString()
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(activity.findViewById(R.id.ring_items))
        return texts
    }

    protected fun state(id: String): OccurrenceState? =
        f.arming.occurrences
            .findById(id)
            ?.state
}
