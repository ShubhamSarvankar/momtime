package com.momtime.android.delivery

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.arming.AlarmIntents
import com.momtime.android.ring.RingActivity
import com.momtime.android.ring.RingEntryPoint
import com.momtime.android.ring.RingItem
import com.momtime.android.ring.RingSessions
import com.momtime.android.ringer.RingerEntryPoint
import com.momtime.android.ringer.RingerService
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The ringer service and the ring screen (ADR 0061, ADR 0062), driven through Robolectric's own lifecycle, with the
 * speaker a fake. They show the service goes foreground with the media playback type, starts the sound once and
 * records what audio focus said, and degrades without crashing when it cannot; and the screen shows her words
 * verbatim, reaches no data, and only stops the sound.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 33, 34, 36])
class RingerAndScreenTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val controller: RingController get() = f.arming.graph.get()

    @After
    fun tearDown() {
        RingEntryPoint.provider = null
        RingerEntryPoint.provider = null
        f.close()
    }

    private fun firedRing(): RingerRequest {
        val a = f.due("a", Criticality.CRITICAL, slot = 31)
        f.fire(a)
        RingerEntryPoint.provider = { controller }
        return f.ringer.starts.single()
    }

    @Test
    fun `the service goes foreground as media playback and starts the sound once`() {
        val request = firedRing()
        val service =
            Robolectric.buildService(RingerService::class.java, RingerService.startIntent(context, request)).create()

        service.startCommand(0, 1)

        val shadow = shadowOf(service.get())
        assertNotNull("the service must go foreground", shadow.lastForegroundNotification)
        assertEquals(RingNotifications.RING_ID, shadow.lastForegroundNotificationId)
        assertEquals("one start of the sound", 1, f.sound.starts)
        assertEquals(NotificationChannels.CRITICAL, shadow.lastForegroundNotification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, shadow.lastForegroundNotification.category)
        val row = f.telemetryOf("a")
        assertEquals("the ringer started", true, row.ringerStarted)
        assertEquals("audio focus was obtained and recorded", true, row.audioFocusObtained)
    }

    @Test
    fun `the outcome of audio focus is recorded whatever it is`() {
        f.sound.focus = false
        val request = firedRing()
        Robolectric
            .buildService(
                RingerService::class.java,
                RingerService.startIntent(context, request),
            ).create()
            .startCommand(0, 1)

        assertEquals("the sound plays whatever focus says", 1, f.sound.starts)
        assertEquals(false, f.telemetryOf("a").audioFocusObtained)
    }

    // The platform may refuse after the service began. It stops itself, tells the host, which posts the notification
    // and records the refusal. It does not crash.
    @Test
    fun `a refusal inside the service degrades to a notification`() {
        f.sound.failure = IllegalStateException("not allowed")
        val request = firedRing()
        val service =
            Robolectric
                .buildService(
                    RingerService::class.java,
                    RingerService.startIntent(context, request),
                ).create()

        val result = runCatching { service.startCommand(0, 1) }

        assertTrue("the refusal reached the caller: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(false, f.telemetryOf("a").ringerStarted)
        assertNotNull("the ring is a notification now", f.posted(RingNotifications.RING_ID))
        assertFalse(
            "the session ended",
            f.arming.graph
                .get<RingSessions>()
                .isActive,
        )
        assertTrue("and the service stopped itself", shadowOf(service.get()).isStoppedBySelf)
    }

    @Test
    fun `destroying the service stops the sound and an unknown action does nothing`() {
        val request = firedRing()
        val service =
            Robolectric
                .buildService(
                    RingerService::class.java,
                    RingerService.startIntent(context, request),
                ).create()
        service.startCommand(0, 1)

        service.destroy()
        assertEquals(1, f.sound.stops)

        val stray = Robolectric.buildService(RingerService::class.java, android.content.Intent("nothing")).create()
        stray.startCommand(0, 2)
        assertEquals("a stray intent starts no sound", 1, f.sound.starts)
    }

    @Test
    fun `the manifest declares the ring screen and the ringer as not exported, and the ringer as media playback`() {
        val screen = context.packageManager.getActivityInfo(ComponentName(context, RingActivity::class.java), 0)
        assertFalse(screen.exported)
        val ringer = context.packageManager.getServiceInfo(ComponentName(context, RingerService::class.java), 0)
        assertFalse(ringer.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, ringer.foregroundServiceType)
    }

    // The alarm clock's show intent opens the app now that an activity exists: no longer null.
    @Test
    fun `the alarm clock has a show intent that opens the ring screen`() {
        f.due("a", Criticality.STANDARD, slot = 31)

        val info =
            checkNotNull(
                f.arming
                    .alarms()
                    .single()
                    .alarmClockInfo,
            )

        val show = checkNotNull(info.showIntent)
        assertEquals(ComponentName(context, RingActivity::class.java), shadowOf(show).savedIntent.component)
        assertTrue(shadowOf(show).isActivityIntent)
        assertEquals("its request code is the slot", 31, shadowOf(show).requestCode)
        assertNotNull(AlarmIntents.show(context, 31))
    }

    // --- the ring screen

    private fun screen(sessions: RingSessions): android.app.Activity {
        val stopped = booleanArrayOf(false)
        RingEntryPoint.provider =
            {
                object : com.momtime.android.ring.RingHost {
                    override val sessions = sessions

                    override fun stopSound() {
                        stopped[0] = true
                        sessions.end()
                    }
                }
            }
        return Robolectric
            .buildActivity(RingActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
            .get()
    }

    private fun texts(activity: android.app.Activity): List<String> {
        val items = activity.findViewById<LinearLayout>(R.id.ring_items)
        val all = mutableListOf<String>()

        fun walk(view: android.view.View) {
            if (view is TextView && view !is Button) all += view.text.toString()
            if (view is android.view.ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(items)
        return all
    }

    @Test
    fun `the screen shows her title, dosage and instructions as she typed them`() {
        val sessions = RingSessions()
        val instructions = "Take with food (not on an empty stomach).\nDo not crush;  2x daily - per Dr. A"
        sessions.join(RingItem("a", "Iron (ferrous) tablet", "2 tablets", instructions))

        val activity = screen(sessions)

        assertTrue("shown over the lock screen", shadowOf(activity).showWhenLocked)
        assertTrue("and the screen is turned on", shadowOf(activity).turnScreenOn)
        val shown = texts(activity)
        assertTrue(shown.toString(), "Iron (ferrous) tablet" in shown)
        assertTrue("2 tablets" in shown)
        assertTrue("the instructions are shown exactly, not parsed or trimmed: $shown", instructions in shown)
        assertEquals(
            activity.resources.getQuantityString(R.plurals.ring_due_count, 1, 1),
            activity.findViewById<TextView>(R.id.ring_count).text.toString(),
        )
    }

    @Test
    fun `fields that are not set are not shown, and each due occurrence is listed`() {
        val sessions = RingSessions()
        sessions.join(RingItem("a", "Water", null, null))
        sessions.join(RingItem("b", "Folic acid", "400 mcg", null))

        val shown = texts(screen(sessions))

        assertEquals(listOf("Water", "Folic acid", "Dosage", "400 mcg").sorted(), shown.sorted())
    }

    @Test
    fun `the screen can only stop the sound`() {
        val sessions = RingSessions()
        sessions.join(RingItem("a", "Water", null, null))
        val activity = screen(sessions)
        val stop = activity.findViewById<Button>(R.id.ring_stop_sound)
        assertEquals(android.view.View.VISIBLE, stop.visibility)

        stop.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("the session ended", sessions.isActive)
        assertEquals("what was due is still listed", 1, sessions.items().size)
        assertEquals(
            activity.getString(R.string.ring_sound_stopped),
            activity.findViewById<TextView>(R.id.ring_sound_stopped).text.toString(),
        )
        assertEquals(android.view.View.VISIBLE, activity.findViewById<TextView>(R.id.ring_sound_stopped).visibility)
    }

    @Test
    fun `with nothing ringing the screen says so`() {
        val activity = screen(RingSessions())

        assertEquals(
            activity.getString(R.string.ring_nothing_due),
            activity.findViewById<TextView>(R.id.ring_count).text.toString(),
        )
        assertTrue(texts(activity).isEmpty())
        assertNull(
            activity.findViewById<android.view.View>(R.id.ring_mission_slot).let {
                (it as android.view.ViewGroup).getChildAt(0)
            },
        )
    }

    // Stopping the sound from the screen writes nothing: the real controller, the real log.
    @Test
    fun `stopping the sound from the screen leaves the log alone`() {
        val a = f.due("a", Criticality.STANDARD, slot = 31)
        f.fire(a)
        RingEntryPoint.provider = { controller }
        val log = f.arming.eventLog("a")
        val activity =
            Robolectric
                .buildActivity(RingActivity::class.java)
                .create()
                .start()
                .resume()
                .visible()
                .get()

        activity.findViewById<Button>(R.id.ring_stop_sound).performClick()

        assertEquals(log, f.arming.eventLog("a"))
        assertEquals(0, f.arming.count(EventType.COMPLETED, "a"))
        assertEquals(1, f.ringer.stops)
    }
}
