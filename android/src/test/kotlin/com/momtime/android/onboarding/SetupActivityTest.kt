package com.momtime.android.onboarding

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.momtime.android.R
import com.momtime.android.arming.t0
import com.momtime.android.delivery.DeliveryFixture
import com.momtime.android.reliability.ReliabilityCheckActivity
import com.momtime.android.settings.AndroidSettings
import com.momtime.shared.domain.Criticality
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
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.hours

/**
 * The permission screen (ADR 0072), at SDK 29, 31, 33, 34 and 36, over the real graph: each flow launches the right
 * intent or none at each API level; coming back from a flow resolves capability again and calls `ensureArmed`; the
 * Samsung steps are reached only on a Samsung; a settings screen that cannot be started falls back and never crashes.
 * It shows the intents and the arming. It does not show a permission being granted on a real phone
 * (`MANUAL_CHECKS.md` P2-38).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 31, 33, 34, 36])
class SetupActivityTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val sdk = Build.VERSION.SDK_INT
    private val power: PowerManager get() = context.getSystemService(PowerManager::class.java)

    init {
        // Created now, so that it reads the platform as it is before a test changes it.
        f.arming.resolver
        SetupEntryPoint.provider = { f.arming.graph.get<SetupHost>() }
        SetupEntryPoint.executor = Executor { it.run() }
    }

    @After
    fun tearDown() {
        SetupEntryPoint.provider = null
        f.close()
    }

    private fun batteryExempt(exempt: Boolean) =
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, exempt)

    private fun exactAllowed(allowed: Boolean) = ShadowAlarmManager.setCanScheduleExactAlarms(allowed)

    /** Takes everything away, so that every flow is needed. */
    private fun allDenied() {
        exactAllowed(false)
        f.notificationsEnabled(false)
        f.fullScreenIntent(false)
        f.overlayAllowed(false)
        batteryExempt(false)
        f.arming.unusedAppExempt = false
    }

    private fun allAllowed() {
        exactAllowed(true)
        f.notificationsEnabled(true)
        f.fullScreenIntent(true)
        f.overlayAllowed(true)
        batteryExempt(true)
        f.arming.unusedAppExempt = true
    }

    private fun open(): Activity =
        Robolectric
            .buildActivity(SetupActivity::class.java)
            .create()
            .start()
            .resume()
            .get()

    private fun Activity.button(flow: PermissionFlow): Button? =
        findViewById<View>(R.id.setup_items).findViewWithTag("allow:${flow.name}")

    private fun Activity.buttons(): Set<PermissionFlow> = PermissionFlow.entries.filter { button(it) != null }.toSet()

    private fun Activity.text(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun `with nothing allowed a flow offers its button exactly where it can launch at this API level`() {
        allDenied()

        val activity = open()

        val expected =
            buildSet {
                add(PermissionFlow.NOTIFICATIONS)
                add(PermissionFlow.BATTERY)
                add(PermissionFlow.OVERLAY)
                if (sdk == Build.VERSION_CODES.S || sdk == Build.VERSION_CODES.S_V2) add(PermissionFlow.EXACT_ALARM)
                if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(PermissionFlow.FULL_SCREEN_INTENT)
                if (sdk >= Build.VERSION_CODES.R) add(PermissionFlow.UNUSED_APP_RESTRICTIONS)
            }
        assertEquals(expected, activity.buttons())
    }

    @Test
    fun `a flow that is needed but cannot launch here says so, with no button`() {
        allDenied()

        val activity = open()

        // On 33 and above exact alarms need no screen (USE_EXACT_ALARM), so a missing one cannot be fixed here.
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            val row =
                activity
                    .findViewById<View>(
                        R.id.setup_items,
                    ).findViewWithTag<View>(PermissionFlow.EXACT_ALARM.name)
            assertNull(activity.button(PermissionFlow.EXACT_ALARM))
            assertTrue(
                (row as android.view.ViewGroup).let { g ->
                    (0 until g.childCount).map { g.getChildAt(it) }.filterIsInstance<TextView>().any {
                        it.text ==
                            context.getString(R.string.setup_status_cannot)
                    }
                },
            )
        }
    }

    @Test
    fun `each button launches the flow's own screen or request`() {
        allDenied()
        val activity = open()

        for (flow in activity.buttons()) {
            activity.button(flow)!!.performClick()
            if (flow == PermissionFlow.NOTIFICATIONS && sdk >= Build.VERSION_CODES.TIRAMISU) {
                val requested = shadowOf(activity).lastRequestedPermission
                assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), requested.requestedPermissions.toList())
                continue
            }
            val started = shadowOf(activity).nextStartedActivity
            val expected =
                when (flow) {
                    PermissionFlow.NOTIFICATIONS -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    PermissionFlow.EXACT_ALARM -> Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                    PermissionFlow.FULL_SCREEN_INTENT -> Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
                    PermissionFlow.BATTERY -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                    PermissionFlow.OVERLAY -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION
                    PermissionFlow.UNUSED_APP_RESTRICTIONS -> Intent.ACTION_AUTO_REVOKE_PERMISSIONS
                }
            assertEquals("$flow", expected, started.action)
        }
    }

    @Test
    fun `once the notification request has been made the button opens the app's settings`() {
        allDenied()
        val first = open()

        first.button(PermissionFlow.NOTIFICATIONS)!!.performClick()
        assertTrue(
            f.arming.graph
                .get<AndroidSettings>()
                .notificationsRequested() == (sdk >= Build.VERSION_CODES.TIRAMISU),
        )

        val second = open()
        second.button(PermissionFlow.NOTIFICATIONS)!!.performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(second).nextStartedActivity.action)
    }

    @Test
    fun `with everything allowed nothing is offered and every row says allowed`() {
        allAllowed()

        val activity = open()

        assertEquals(emptySet<PermissionFlow>(), activity.buttons())
        val container = activity.findViewById<android.view.ViewGroup>(R.id.setup_items)
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as android.view.ViewGroup
            val statuses =
                (0 until row.childCount)
                    .map {
                        row.getChildAt(it)
                    }.filterIsInstance<TextView>()
                    .map { it.text.toString() }
            assertTrue("${row.tag}: $statuses", context.getString(R.string.setup_status_ok) in statuses)
        }
        assertEquals(context.getString(R.string.setup_tier_3), activity.text(R.id.setup_tier))
    }

    // Coming back resolves capability again, from the platform as it is now, and calls ensureArmed. She allowed the
    // battery exemption in settings while the screen was away: the tier on the screen moves from 2 to 3, and an alarm
    // that was lost in the meantime is armed again.
    @Test
    fun `coming back resolves capability again and arms`() {
        allAllowed()
        batteryExempt(false)
        f.due("a", Criticality.STANDARD, t0 + 2.hours, slot = 31)
        f.arming.clock.now = t0
        f.arming.coordinator.ensureArmed()
        val controller =
            Robolectric
                .buildActivity(SetupActivity::class.java)
                .create()
                .start()
                .resume()
        assertEquals(context.getString(R.string.setup_tier_2), controller.get().text(R.id.setup_tier))

        batteryExempt(true)
        f.arming.loseAlarm(31)
        assertEquals("the alarm was lost while she was away", 0, f.arming.alarms().size)
        controller.pause().resume()

        assertEquals(context.getString(R.string.setup_tier_3), controller.get().text(R.id.setup_tier))
        assertEquals("ensureArmed armed it again", 1, f.arming.alarms().size)
    }

    @Test
    @Config(sdk = [31, 33, 34, 36])
    fun `allowing exact alarms in settings arms the next reminder through the exact call on return`() {
        allAllowed()
        exactAllowed(false)
        f.due("a", Criticality.STANDARD, t0 + 2.hours, slot = 31)
        f.arming.clock.now = t0
        f.arming.coordinator.ensureArmed()
        assertTrue(
            "armed inexactly while exact alarms were not allowed",
            f.arming
                .shapes()
                .single()
                .allowWhileIdle,
        )
        val controller =
            Robolectric
                .buildActivity(SetupActivity::class.java)
                .create()
                .start()
                .resume()

        exactAllowed(true)
        controller.pause().resume()

        assertTrue(
            "now armed through setAlarmClock",
            f.arming
                .shapes()
                .single()
                .alarmClock,
        )
    }

    @Test
    fun `continuing goes to the Samsung steps on a Samsung and to the check on any other phone`() {
        f.arming.manufacturer = "samsung"
        val samsung = open()
        samsung.findViewById<Button>(R.id.setup_continue).performClick()
        assertEquals(
            ComponentName(context, SamsungStepsActivity::class.java),
            shadowOf(samsung).nextStartedActivity.component,
        )

        f.arming.manufacturer = "Google"
        val other = open()
        other.findViewById<Button>(R.id.setup_continue).performClick()
        assertEquals(
            ComponentName(context, ReliabilityCheckActivity::class.java),
            shadowOf(other).nextStartedActivity.component,
        )
    }

    @Test
    fun `the maker is matched without regard to case`() {
        for (name in listOf("samsung", "SAMSUNG", "Samsung")) {
            f.arming.manufacturer = name
            val activity = open()
            activity.findViewById<Button>(R.id.setup_continue).performClick()
            assertEquals(
                name,
                SamsungStepsActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component!!.className,
            )
        }
    }

    // A settings screen that cannot be started never crashes the screen: it falls back to the general settings, and to
    // nothing if even that cannot be started.
    @Test
    fun `a settings screen that cannot be started falls back to the general settings and then to nothing`() {
        allDenied()
        shadowOf(context as Application).checkActivities(true)
        val activity = open()

        activity.button(PermissionFlow.BATTERY)!!.performClick()
        assertNull("nothing could be started, and nothing was thrown", shadowOf(activity).nextStartedActivity)

        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Settings.ACTION_SETTINGS), ResolveInfo())
        activity.button(PermissionFlow.BATTERY)!!.performClick()
        assertEquals(Settings.ACTION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun `neither screen is exported`() {
        for (type in listOf(SetupActivity::class.java, SamsungStepsActivity::class.java)) {
            val info = context.packageManager.getActivityInfo(ComponentName(context, type), 0)
            assertFalse("${type.simpleName} must not be exported", info.exported)
        }
        assertNotNull(context)
    }
}
