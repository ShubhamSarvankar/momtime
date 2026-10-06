package com.momtime.android.delivery

import android.content.Context
import com.momtime.android.R
import com.momtime.android.arming.FireOutcome
import com.momtime.android.arming.t0
import com.momtime.android.data.template
import com.momtime.android.data.testZone
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.VibrationPattern
import com.momtime.android.settings.AndroidSettings
import com.momtime.shared.data.ScheduleTemplateRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.Occurrence
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.QuietHours
import com.momtime.shared.engine.OccurrenceAction
import com.momtime.shared.engine.RungDelivery
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * A Gentle reminder is a notification only (ADR 0089): through the real fire path, the real delivery port over a
 * real database and the shadowed platform, with every capability granted, at SDK 29, 31, 33, 34 and 36. The ringer
 * service launch and the overlay are fakes, so this shows which path the code takes and not what a device does
 * (`MANUAL_CHECKS.md` P2-19 is what a Gentle notification looks and sounds like on its channel).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 34, 36])
class GentleDeliveryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val sessions: RingSessions get() = f.arming.graph.get()
    private val controller: RingController get() = f.arming.graph.get()
    private val settings: AndroidSettings get() = f.arming.graph.get()

    @After
    fun tearDown() = f.close()

    private fun path(occurrenceId: String) = f.telemetryOf(occurrenceId).deliveryPath

    private fun state(id: String): OccurrenceState? =
        f.arming.occurrences
            .findById(id)
            ?.state

    // ADR 0089 items 1, 3 and 6: no ringer, no session, no overlay; one notification with her actions, on the
    // Gentle channel, with the occurrence's slot as its id, no full screen intent and no delete intent; the fire
    // is still a fire (ALARM_FIRED) and the telemetry row says which path was taken.
    @Test
    fun `Gentle at Tier 3 starts no ringer and posts one notification`() {
        val g = f.due("g", Criticality.GENTLE, slot = 33)

        val outcome = f.fire(g)

        assertTrue("the rung fired: $outcome", outcome is FireOutcome.Fired)
        assertEquals(RungDelivery.NOTIFICATION, (outcome as FireOutcome.Fired).rung.policy)
        f.assertNothingRang()
        assertFalse("no ring session", sessions.isActive)
        assertFalse(sessions.isRinging("g"))
        assertEquals("no overlay launch", 0, f.overlay.launches)
        assertEquals("exactly one notification", 1, shadowOf(f.notifications).allNotifications.size)
        val posted = checkNotNull(f.posted(33)) { "the notification's id is the occurrence's slot" }
        assertEquals(NotificationChannels.GENTLE, posted.channelId)
        assertNull("no full screen intent", posted.fullScreenIntent)
        assertNull("no delete intent", posted.deleteIntent)
        assertEquals(
            "the occurrence's three actions",
            listOf(R.string.ring_action_acknowledge, R.string.ring_action_snooze, R.string.ring_action_skip)
                .map(context::getString),
            posted.actions.map { it.title.toString() },
        )
        assertEquals("ALARM_FIRED is written", 1, f.arming.count(EventType.ALARM_FIRED, "g"))
        assertEquals("PLAIN", path("g"))
    }

    // ADR 0089 item 4: a Gentle notification is not a ring grade interruption. The Standard fire after it is the
    // control that the counter works.
    @Test
    fun `the budget is unspent after a Gentle fire`() {
        f.settings.updateRingGradeDailyBudget(10)
        f.budget.ensureSeeded(LocalDate(2026, 3, 1))
        val g = f.due("g", Criticality.GENTLE, slot = 33)
        val s = f.due("s", Criticality.STANDARD, slot = 32)

        f.fire(g)
        assertEquals("a Gentle fire spends nothing", 0, f.budget.current().ringCount)
        f.fire(s)
        assertEquals("a Standard fire spends one", 1, f.budget.current().ringCount)
    }

    // ADR 0089 item 5: quiet hours, the spent budget and the catch up window are decided before Gentle's own
    // answer, so each of the three is still a silent notification on Quiet notices (ADR 0064, ADR 0056).
    @Test
    fun `Gentle in quiet hours, with the budget spent, and beyond the catch up window is as before`() {
        // Quiet hours: t0 is 08:00 in the device zone (UTC here).
        f.settings.updateQuietHours(QuietHours(LocalTime(7, 0), LocalTime(9, 0)))
        val q = f.due("q", Criticality.GENTLE, slot = 41)
        f.fire(q)
        assertEquals("SILENT", path("q"))
        assertEquals(NotificationChannels.QUIET, f.channelOf(41))
        f.settings.updateQuietHours(null)

        // The budget spent by a Standard ring.
        f.settings.updateRingGradeDailyBudget(1)
        val s = f.due("s", Criticality.STANDARD, slot = 42)
        f.fire(s)
        sessions.end()
        val b = f.due("b", Criticality.GENTLE, slot = 43)
        f.fire(b)
        assertEquals("SILENT", path("b"))
        assertEquals(NotificationChannels.QUIET, f.channelOf(43))

        // Beyond the catch up window.
        val l = f.due("l", Criticality.GENTLE, slot = 44)
        f.fire(l, now = l.scheduledInstant + 31.minutes)
        assertEquals("SILENT_NOTICE", path("l"))
        assertEquals(NotificationChannels.QUIET, f.channelOf(44))

        assertEquals("only the Standard control rang", 1, f.ringer.starts.size)
    }

    // ADR 0089 item 6: a Gentle fire never joins a session. The Critical ring's items, its style and its one ringer
    // start are what they were, and the ring notification's slot is untouched (the ringer service holds that
    // notification and is a fake here, so the port must post nothing under its id: a join would have); the Gentle
    // occurrence has its own notification; acting on it leaves the ring ringing.
    @Test
    fun `a Gentle fire during a ringing Critical session leaves the session as it was`() {
        val c = f.due("c", Criticality.CRITICAL, slot = 31)
        f.fire(c)
        val itemsBefore = sessions.items()
        val styleBefore = checkNotNull(sessions.style)
        val startsBefore = f.ringer.starts.toList()
        assertEquals(listOf("c"), itemsBefore.map { it.occurrenceId })
        assertNull("the ringer service holds the ring notification, not the port", f.posted(RingNotifications.RING_ID))

        val g = f.due("g", Criticality.GENTLE, slot = 33)
        f.fire(g)

        assertEquals("the session's items", itemsBefore, sessions.items())
        assertSame("the session's style", styleBefore, sessions.style)
        assertNull("nothing was posted under the ring notification's id", f.posted(RingNotifications.RING_ID))
        assertEquals("the ringer starts", startsBefore, f.ringer.starts)
        assertTrue(sessions.isRinging("c"))
        assertFalse("the Gentle occurrence did not join", sessions.isRinging("g"))
        assertEquals(NotificationChannels.GENTLE, f.channelOf(33))
        assertEquals("PLAIN", path("g"))

        controller.act("g", OccurrenceAction.ACKNOWLEDGE)

        assertEquals(OccurrenceState.COMPLETED, state("g"))
        assertNull("its notification is gone", f.posted(33))
        assertTrue("the ring goes on", sessions.isRinging("c"))
        assertEquals(0, f.ringer.stops)
        assertEquals(listOf("c"), sessions.items().map { it.occurrenceId })
    }

    /** A template with one criticality and an occurrence of it with another, through the repositories, armed. */
    private fun divergent(
        id: String,
        templateCriticality: Criticality,
        occurrenceCriticality: Criticality,
        slot: Int,
    ): Occurrence {
        f.arming.graph.get<ScheduleTemplateRepository>().insert(
            template("tmpl-$id").copy(criticality = templateCriticality),
        )
        val occurrence =
            Occurrence(
                id,
                "tmpl-$id",
                LocalDate(2026, 3, 1),
                t0,
                testZone,
                OccurrenceState.PENDING,
                slot,
                occurrenceCriticality,
            )
        f.arming.occurrences.insert(occurrence)
        f.arming.clock.now = t0 - 1.hours
        f.arming.coordinator.ensureArmed()
        f.arming.clock.now = t0
        return occurrence
    }

    // ADR 0079 item 6, ADR 0089 item 2: the policy is given the occurrence's criticality. With the template saying
    // the opposite each time, the occurrence's value is the one that decides.
    @Test
    fun `the decision reads the occurrence, not the template`() {
        val gentle =
            divergent(
                "g",
                templateCriticality = Criticality.CRITICAL,
                occurrenceCriticality = Criticality.GENTLE,
                slot = 33,
            )
        f.fire(gentle)
        assertEquals("a Gentle occurrence of a Critical template is a notification", "PLAIN", path("g"))
        f.assertNothingRang()

        val critical =
            divergent(
                "c",
                templateCriticality = Criticality.GENTLE,
                occurrenceCriticality = Criticality.CRITICAL,
                slot = 31,
            )
        f.fire(critical)
        assertEquals("a Critical occurrence of a Gentle template rings", "RING", path("c"))
        assertEquals(1, f.ringer.starts.size)
    }

    // ADR 0089 item 7: a stored pattern is ignored for a Gentle occurrence and kept, so a Standard occurrence of
    // the same template vibrates as she chose. The Gentle fire never reaches the ringer, so the direct read is what
    // shows the stored choice was neither used nor deleted.
    @Test
    fun `a stored vibration is ignored for Gentle and kept`() {
        val g = f.due("g", Criticality.GENTLE, slot = 33)
        settings.setVibration("tmpl-g", VibrationPattern.URGENT)

        f.fire(g)

        f.assertNothingRang()
        assertEquals(
            "no vibration for a Gentle occurrence",
            VibrationPattern.NONE,
            settings.vibrationFor("tmpl-g", Criticality.GENTLE),
        )

        val s =
            Occurrence(
                "s",
                "tmpl-g",
                LocalDate(2026, 3, 2),
                t0 + 1.hours,
                testZone,
                OccurrenceState.PENDING,
                34,
                Criticality.STANDARD,
            )
        f.arming.occurrences.insert(s)
        f.arming.coordinator.ensureArmed()
        f.fire(s)

        assertEquals(
            "her choice, for a Standard occurrence of the same template",
            VibrationPattern.URGENT,
            f.ringer.starts
                .single()
                .vibration,
        )
        assertEquals(
            "the stored value is still there",
            VibrationPattern.URGENT,
            settings.vibrationFor("tmpl-g", Criticality.STANDARD),
        )
    }
}
