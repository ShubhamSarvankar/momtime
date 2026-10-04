package com.momtime.android.delivery

import android.content.Context
import android.os.Build
import com.momtime.android.arming.FireOutcome
import com.momtime.android.arming.Presentation
import com.momtime.android.ring.RingSessions
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.EventType
import com.momtime.shared.domain.OccurrenceState
import com.momtime.shared.domain.QuietHours
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.time.Duration.Companion.minutes

/**
 * Every delivery path, through the real delivery port over a real database and the shadowed platform, at SDK 29, 31,
 * 33, 34 and 36 (34 is where full screen intent changes, ADR 0050). The ringer service launch, the overlay and the
 * speaker are fakes, which is what a Robolectric test can have: it shows the code takes the path it says, through the
 * call it says, and not that a device makes a sound or turns its screen on (`MANUAL_CHECKS.md` P2-16 and P2-17).
 *
 * Test names are short on purpose: Robolectric names its data directory after the class and the method, and a long
 * path breaks the database on Windows.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 34, 36])
class DeliveryTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val sessions: RingSessions get() = f.arming.graph.get()
    private val canLoseExact = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @After
    fun tearDown() = f.close()

    private fun path(occurrenceId: String) = f.telemetryOf(occurrenceId).deliveryPath

    /** The one request the ringer was given, asserting there was one before reading it. */
    private fun ringRequest(): RingerRequest {
        assertEquals("the ringer was started once: ${f.ringer.starts}", 1, f.ringer.starts.size)
        return f.ringer.starts.single()
    }

    // Tier 3: full screen intent effective, exact, battery exempt. The ringer starts, asked to hold a notification
    // that launches the screen, on the channel of the occurrence's criticality.
    @Test
    fun `a ring is a full screen notification and the ringer`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        val outcome = f.fire(a)

        assertTrue(outcome is FireOutcome.Fired)
        val request = ringRequest()
        assertEquals(NotificationChannels.STANDARD, request.channelId)
        assertTrue("a full screen intent while it is effective", request.fullScreenIntent)
        assertEquals("RING", path("a"))
        assertEquals("the ringer service is what started", 1, f.ringer.starts.size)
        assertEquals(0, f.overlay.launches)
        assertNull("a ring is not also a plain notification", f.posted(31))
        assertEquals(listOf("Iron tablet"), sessions.items().map { it.title })
        assertEquals(true, sessions.isRinging("a"))
    }

    // Tier 2: exact but no full screen intent. A heads up and the ringer; the overlay route only if it is granted.
    @Test
    fun `a heads up is the ringer without a full screen intent`() {
        f.fullScreenIntent(false)
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        f.fire(a)

        val request = ringRequest()
        assertFalse("never a full screen intent while it is not effective", request.fullScreenIntent)
        assertEquals("HEADS_UP", path("a"))
        assertEquals("no overlay while it is not granted", 0, f.overlay.launches)
    }

    @Test
    fun `the overlay opens the screen when full screen intent is not available and it is granted`() {
        f.fullScreenIntent(false)
        f.overlayAllowed(true)
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        f.fire(a)

        assertEquals("HEADS_UP", path("a"))
        assertEquals("the overlay route is used", 1, f.overlay.launches)
        assertEquals("and it is not the ringing mechanism: the ringer started too", 1, f.ringer.starts.size)
    }

    // The overlay is never used when a full screen intent is available, granted or not.
    @Test
    fun `the overlay is not used when full screen intent is available`() {
        f.overlayAllowed(true)
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        f.fire(a)

        assertEquals("RING", path("a"))
        assertEquals(0, f.overlay.launches)
    }

    // Notifications denied: the ringer sounds and nothing else is shown, except through the overlay if it is granted.
    @Test
    fun `audio only is the ringer with notifications denied`() {
        f.notificationsEnabled(false)
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        f.fire(a)

        assertFalse(
            f.ringer.starts
                .single()
                .fullScreenIntent,
        )
        assertEquals("AUDIO_ONLY", path("a"))
        assertEquals(0, f.overlay.launches)
    }

    @Test
    fun `audio only opens the screen through the overlay when it is granted`() {
        f.notificationsEnabled(false)
        f.overlayAllowed(true)
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        f.fire(a)

        assertEquals("AUDIO_ONLY", path("a"))
        assertEquals(1, f.overlay.launches)
    }

    // Tier 1: no exact capability. A plain notification on the criticality channel, replacing the one for the same
    // occurrence, and no ringer. Below API 31 capability cannot be lost, so SDK 29 asserts that and the ring.
    @Test
    fun `tier 1 is a plain notification`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val a = f.due("a", Criticality.CRITICAL, slot = 31)

        f.fire(a)

        if (canLoseExact) {
            assertEquals("PLAIN", path("a"))
            f.assertNothingRang()
            assertEquals(NotificationChannels.CRITICAL, f.channelOf(31))
            assertEquals(0, f.overlay.launches)
        } else {
            assertEquals("capability cannot be lost below API 31", "RING", path("a"))
        }
    }

    // ADR 0056: a rung beyond the catch up window is a silent notice. No ring and no heads up, on the Gentle channel,
    // and it does not spend the interruption budget.
    @Test
    fun `a rung beyond the window is a silent notice`() {
        f.budget.ensureSeeded(kotlinx.datetime.LocalDate(2026, 3, 1))
        val a = f.due("a", Criticality.CRITICAL, slot = 31)

        val outcome = f.fire(a, now = a.scheduledInstant + 31.minutes) as FireOutcome.Fired

        assertEquals(Presentation.SILENT_NOTICE, outcome.rung.presentation)
        assertEquals("SILENT_NOTICE", path("a"))
        f.assertNothingRang()
        assertEquals(NotificationChannels.GENTLE, f.channelOf(31))
        assertEquals(0, f.budget.current().ringCount)
        assertNotNull(f.posted(31))
    }

    // The domain's quiet hours, through the fire path. STANDARD is silent, CRITICAL overrides.
    @Test
    fun `quiet hours silence a standard rung`() {
        f.settings.updateQuietHours(QuietHours(LocalTime(7, 0), LocalTime(9, 0)))
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        f.fire(a)

        assertEquals("SILENT", path("a"))
        f.assertNothingRang()
        assertEquals(NotificationChannels.GENTLE, f.channelOf(31))
        assertEquals("a silent notification does not spend the budget", 0, f.budget.current().ringCount)
    }

    @Test
    fun `quiet hours never silence a critical rung`() {
        f.settings.updateQuietHours(QuietHours(LocalTime(7, 0), LocalTime(9, 0)))
        val a = f.due("a", Criticality.CRITICAL, slot = 31)

        f.fire(a)

        assertEquals("RING", path("a"))
        assertEquals(
            NotificationChannels.CRITICAL,
            f.ringer.starts
                .single()
                .channelId,
        )
    }

    // The interruption budget, through the domain: a ring spends one, and when it is spent a standard rung is
    // silent while a critical one still rings.
    @Test
    fun `the budget is spent by a ring and then silences a standard rung`() {
        f.settings.updateRingGradeDailyBudget(1)
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        val b = f.due("b", Criticality.STANDARD, slot = 32)
        val c = f.due("c", Criticality.CRITICAL, slot = 33)

        f.fire(a)
        assertEquals("a ring spends one", 1, f.budget.current().ringCount)
        f.fire(b)
        assertEquals("SILENT", path("b"))
        assertEquals("a silent notification spends nothing", 1, f.budget.current().ringCount)
        f.fire(c)
        assertEquals("a critical rung is never budget limited", "RING", path("c"))
        assertEquals(2, f.budget.current().ringCount)
    }

    // The right channel for each criticality, on the ring path.
    @Test
    fun `each criticality rings on its own channel`() {
        f.settings.updateRingGradeDailyBudget(10)
        val c = f.due("c", Criticality.CRITICAL, slot = 31)
        f.fire(c)
        sessions.end()
        val s = f.due("s", Criticality.STANDARD, slot = 32)
        f.fire(s)
        sessions.end()
        val g = f.due("g", Criticality.GENTLE, slot = 33)
        f.fire(g)

        assertEquals(
            listOf(NotificationChannels.CRITICAL, NotificationChannels.STANDARD, NotificationChannels.GENTLE),
            f.ringer.starts.map { it.channelId },
        )
    }

    // ADR 0062: a rung for an occurrence already ringing continues the ring. No second start, no second screen,
    // ALARM_FIRED still written, and the budget counts the occurrence once.
    @Test
    fun `a rung for an occurrence that is ringing continues the ring`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)

        val again =
            f.fire(
                a,
                now = a.scheduledInstant + 10.minutes,
                rung = a.scheduledInstant + 10.minutes,
            ) as FireOutcome.Fired

        assertTrue("it is a continuation", again.rung.continuing)
        assertEquals("no second start", 1, f.ringer.starts.size)
        assertEquals("ALARM_FIRED is still written", 2, f.arming.count(EventType.ALARM_FIRED, "a"))
        assertEquals("the budget counts it once", 1, f.budget.current().ringCount)
        assertEquals(1, sessions.items().size)
        assertEquals(0, f.overlay.launches)
    }

    // A fire for a different occurrence joins the session and does not start a second one.
    @Test
    fun `a rung for another occurrence joins the session`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        val b = f.due("b", Criticality.CRITICAL, slot = 32)
        f.fire(a)

        f.fire(b)

        assertEquals("one ringer, not two", 1, f.ringer.starts.size)
        assertEquals(listOf("a", "b"), sessions.items().map { it.occurrenceId })
        assertNotNull("the notification is refreshed with both", f.posted(RingNotifications.RING_ID))
        assertEquals("each occurrence that begins to ring spends one", 2, f.budget.current().ringCount)
    }

    // A refusal to start the ringer degrades to the notification path. Never a crash, and it is recorded.
    @Test
    fun `a refused ringer degrades to a notification`() {
        val refusals =
            listOf(
                IllegalStateException("not allowed to start a foreground service"),
                SecurityException("no permission"),
            )
        for ((index, refusal) in refusals.withIndex()) {
            f.ringer.refusal = refusal
            val id = "r$index"
            val occurrence = f.due(id, Criticality.CRITICAL, slot = 40 + index)

            val result = runCatching { f.fire(occurrence) }

            assertTrue("a refusal reached the caller: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals("the ring is a notification now", 1, f.arming.count(EventType.ALARM_FIRED, id))
            assertNotNull(f.posted(RingNotifications.RING_ID))
            assertNotNull(
                "with a full screen intent while it is effective",
                f.posted(RingNotifications.RING_ID)?.fullScreenIntent,
            )
            assertEquals("the refusal is recorded", false, f.telemetryOf(id).ringerStarted)
            assertFalse("the session ended, so the next rung tries again", sessions.isActive)
        }
    }

    @Test
    fun `a refused ringer without full screen intent posts no full screen intent`() {
        f.fullScreenIntent(false)
        f.ringer.refusal = IllegalStateException("not allowed")
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        f.fire(a)

        assertNotNull(f.posted(RingNotifications.RING_ID))
        assertNull(f.posted(RingNotifications.RING_ID)?.fullScreenIntent)
    }

    // The ring's notification asks for a full screen intent only while it is effective.
    @Test
    fun `the full screen intent is on the notification only while it is effective`() {
        f.ringer.refusal = IllegalStateException("not allowed")
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)
        assertNotNull(f.posted(RingNotifications.RING_ID)?.fullScreenIntent)
    }

    // Dismissal is not completion: stopping the sound ends the session and stops the service and writes nothing,
    // and the next rung still fires and rings.
    @Test
    fun `stopping the sound writes nothing and the next rung still rings`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)
        val log = f.arming.eventLog("a")

        f.arming.graph
            .get<RingController>()
            .stopSound()

        assertEquals("the service was stopped", 1, f.ringer.stops)
        assertFalse(sessions.isActive)
        assertEquals("nothing was written", log, f.arming.eventLog("a"))
        assertEquals(
            OccurrenceState.PENDING,
            f.arming.occurrences
                .findById("a")
                ?.state,
        )
        assertEquals(0, f.arming.count(EventType.COMPLETED, "a"))
        assertEquals("the next rung is still armed", 1, f.arming.alarms().size)

        f.fire(a, now = a.scheduledInstant + 10.minutes, rung = a.scheduledInstant + 10.minutes)
        assertEquals("and it rings: a new session", 2, f.ringer.starts.size)
    }

    // Policy applies to the content of what she sees: her words, verbatim.
    @Test
    fun `the notification carries her title, dosage and instructions as typed`() {
        f.ringer.refusal = IllegalStateException("not allowed")
        val instructions = "Take with food (not on an empty stomach).\nDo not crush;  2x daily - per Dr. A"
        val a =
            f.arming.seed(
                "a",
                Criticality.STANDARD,
                a0,
                slot = 31,
                dosage = "2 tablets",
                doctorInstructions = instructions,
            )
        f.arming.coordinator.ensureArmed()

        f.fire(a)

        val extras = checkNotNull(f.posted(RingNotifications.RING_ID)).extras
        assertEquals("Iron tablet", extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("2 tablets", extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals(
            "2 tablets\n$instructions",
            extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT).toString(),
        )
    }

    private val a0 = com.momtime.android.arming.t0
}
