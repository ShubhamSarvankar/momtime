package com.momtime.android.reliability

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.arming.ArmingFixture
import com.momtime.android.arming.t0
import com.momtime.android.ring.RingActivity
import com.momtime.android.ring.RingEntryPoint
import com.momtime.android.ring.RingHost
import com.momtime.android.ring.RingItem
import com.momtime.android.ring.RingSessions
import com.momtime.android.settings.AndroidSettings
import com.momtime.android.store.FireTelemetry
import com.momtime.android.store.FireTelemetryRepository
import com.momtime.shared.domain.Criticality
import com.momtime.shared.domain.DeliveryCapability
import com.momtime.shared.domain.EventType
import com.momtime.shared.engine.OccurrenceAction
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The reliability check screen and the way to it (ADR 0069, ADR 0070): the test she starts and watches, the report
 * as plain sentences, the opt in, and the export through the system's document picker. The screen reads and acts only
 * through the host, off the main thread; here the executor runs at once. Layout at 200 percent font scale in three
 * locales is Phase 3's screenshot gate and is not claimed here.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class ReliabilityCheckActivityTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val fixture = ArmingFixture(context)
    private val host: ReliabilityHost get() = fixture.graph.get()

    init {
        ReliabilityEntryPoint.provider = { fixture.graph.get<ReliabilityHost>() }
        ReliabilityEntryPoint.executor = Executor { it.run() }
    }

    @After
    fun tearDown() {
        ReliabilityEntryPoint.provider = null
        RingEntryPoint.provider = null
        fixture.close()
    }

    private fun open(): Activity =
        Robolectric
            .buildActivity(ReliabilityCheckActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

    private fun Activity.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `before any test the status says none has been run and the report says there is nothing measured`() {
        val activity = open()

        assertEquals(activity.getString(R.string.check_status_none), activity.text(R.id.check_status))
        val report = activity.text(R.id.check_report)
        assertTrue(report, report.contains(activity.getString(R.string.report_last_check_none)))
        assertTrue(report, report.contains(activity.getString(R.string.report_no_fires)))
        assertTrue("all seven days are unobserved", report.contains("7 of the last 7 days had no reminder"))
    }

    @Test
    fun `starting the test arms its alarm and shows the countdown, with a plural`() {
        val activity = open()

        activity.findViewById<Button>(R.id.check_start).performClick()

        assertEquals("The test alarm is due in 60 seconds.", activity.text(R.id.check_status))
        assertEquals(1, fixture.checkAlarms().size)
        fixture.clock.now = t0 + 59.seconds
        assertEquals(
            "singular at one second",
            "The test alarm is due in 1 second.",
            Robolectric
                .buildActivity(
                    ReliabilityCheckActivity::class.java,
                ).create()
                .start()
                .resume()
                .get()
                .text(R.id.check_status),
        )
    }

    @Test
    fun `while a test is running the screen counts down on its own and reports it due`() {
        val activity = open()
        activity.findViewById<Button>(R.id.check_start).performClick()

        fixture.clock.now = t0 + 61.seconds
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

        assertEquals(activity.getString(R.string.check_status_due), activity.text(R.id.check_status))
    }

    @Test
    fun `a test that arrived shows how late it was, and the last test line says it arrived`() {
        val activity = open()
        activity.findViewById<Button>(R.id.check_start).performClick()
        val id = checkNotNull(host.pendingCheck()).id
        fixture.clock.now = t0 + 60.seconds + 7.seconds
        fixture.graph.get<CanaryRunner>().onFired(id)

        val reopened = open()

        assertEquals("It arrived 7 seconds after it was due.", reopened.text(R.id.check_status))
        assertTrue(reopened.text(R.id.check_report).contains(reopened.getString(R.string.report_last_check_fired)))
    }

    @Test
    fun `a test that did not arrive says so, once its time is up`() {
        val activity = open()
        activity.findViewById<Button>(R.id.check_start).performClick()
        fixture.clock.now = t0 + 60.seconds + CheckPolicy.TIMEOUT

        val reopened = open()

        assertEquals(reopened.getString(R.string.check_status_missed), reopened.text(R.id.check_status))
        assertTrue(reopened.text(R.id.check_report).contains(reopened.getString(R.string.report_last_check_missed)))
    }

    @Test
    fun `asking again while a test is running, or when a reminder is near, says why and arms nothing`() {
        val activity = open()
        activity.findViewById<Button>(R.id.check_start).performClick()
        activity.findViewById<Button>(R.id.check_start).performClick()
        assertEquals(activity.getString(R.string.check_already_running), activity.text(R.id.check_status))
        assertEquals(1, fixture.checkAlarms().size)

        fixture.clock.now = t0 + 60.seconds + CheckPolicy.TIMEOUT
        fixture.seed("a", Criticality.STANDARD, fixture.clock.now + CheckPolicy.DELAY + 30.seconds, slot = 31)
        fixture.coordinator.ensureArmed()
        val near = open()
        near.findViewById<Button>(R.id.check_start).performClick()
        assertEquals(near.getString(R.string.check_start_near), near.text(R.id.check_status))
    }

    @Test
    fun `the report reads the measured fires as sentences with plurals`() {
        fixture.seed("a", Criticality.STANDARD, t0, slot = 31)
        fixture.clock.now = t0 - 1.hours
        fixture.coordinator.ensureArmed()
        fixture.clock.now = t0 + 12.seconds
        fixture.handler.onFire(31, t0)
        val fired = fixture.eventsOf("a", EventType.ALARM_FIRED).first().id
        fixture.graph.get<FireTelemetryRepository>().insert(
            FireTelemetry(
                fired,
                DeliveryCapability.TIER_3,
                true,
                true,
                80,
                "ACTIVE",
                false,
                fixture.bootCount,
                "RING",
                true,
                false,
                0,
            ),
        )

        val report = open().text(R.id.check_report)

        assertTrue(report, report.contains("Tier 3: 1 reminder, middle 12 s, slowest 12 s."))
        assertTrue(report, report.contains("0 reminders were set ahead and never sounded."))
        assertTrue(report, report.contains("6 of the last 7 days had no reminder"))
    }

    @Test
    fun `the opt in is off by default, is remembered, and records nothing differently`() {
        val settings = fixture.graph.get<AndroidSettings>()
        val activity = open()
        val box = activity.findViewById<CheckBox>(R.id.check_opt_in)
        assertFalse(box.isChecked)
        assertFalse(settings.shareReliabilityOptIn())
        assertTrue(activity.text(R.id.check_opt_in_copy).contains("It never includes your medicines"))

        box.performClick()

        assertTrue(settings.shareReliabilityOptIn())
        assertTrue(
            "remembered when the screen is opened again",
            open().findViewById<CheckBox>(R.id.check_opt_in).isChecked,
        )
        box.performClick()
        assertFalse(settings.shareReliabilityOptIn())
    }

    @Test
    fun `the export asks the system's document picker for a json file and writes the report to what she chose`() {
        val activity = open()

        activity.findViewById<Button>(R.id.check_export).performClick()

        val asked = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, asked.intent.action)
        assertEquals(ReliabilityExport.MIME_TYPE, asked.intent.type)
        assertEquals(ReliabilityExport.FILE_NAME, asked.intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(asked.intent.categories.contains(Intent.CATEGORY_OPENABLE))

        val target = File.createTempFile("momtime-export", ".json")
        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, Intent().setData(Uri.fromFile(target)))

        val written = JSONObject(target.readText())
        assertEquals(ReliabilityExport.SCHEMA_VERSION, written.getInt("schemaVersion"))
        assertEquals(host.exportJson(), target.readText())
        target.delete()
    }

    @Test
    fun `cancelling the picker writes nothing, even if it names a file`() {
        val activity = open()
        activity.findViewById<Button>(R.id.check_export).performClick()
        val asked = shadowOf(activity).nextStartedActivityForResult
        val target = File.createTempFile("momtime-cancelled", ".json")
        target.delete()

        shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, Intent().setData(Uri.fromFile(target)))

        assertFalse("a cancelled picker must not create the file", target.exists())
    }

    @Test
    fun `with nothing ringing the ring screen offers the way to the check, and it opens the check screen`() {
        RingEntryPoint.provider = { quietHost(RingSessions()) }
        val ring =
            Robolectric
                .buildActivity(
                    RingActivity::class.java,
                    RingActivity.intent(context),
                ).create()
                .start()
                .resume()
                .get()

        val link = ring.findViewById<Button>(R.id.ring_open_check)
        assertEquals(android.view.View.VISIBLE, link.visibility)
        link.performClick()

        assertEquals(
            ComponentName(context, ReliabilityCheckActivity::class.java),
            shadowOf(ring).nextStartedActivity.component,
        )
    }

    @Test
    fun `while a reminder is ringing the ring screen does not offer the check`() {
        val sessions = RingSessions()
        sessions.join(
            RingItem("occ", "Iron tablet", null, null, alarmSlot = 31, actions = setOf(OccurrenceAction.ACKNOWLEDGE)),
        )
        RingEntryPoint.provider = { quietHost(sessions) }
        val ring =
            Robolectric
                .buildActivity(
                    RingActivity::class.java,
                    RingActivity.intent(context),
                ).create()
                .start()
                .resume()
                .get()

        assertEquals(android.view.View.GONE, ring.findViewById<Button>(R.id.ring_open_check).visibility)
    }

    private fun quietHost(ringSessions: RingSessions) =
        object : RingHost {
            override val sessions = ringSessions

            override fun stopSound() = Unit

            override fun act(
                occurrenceId: String,
                action: OccurrenceAction,
            ) = Unit
        }
}
