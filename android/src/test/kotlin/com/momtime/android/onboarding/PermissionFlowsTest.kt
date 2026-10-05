package com.momtime.android.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.momtime.android.capability.CapabilityInputs
import com.momtime.android.capability.resolveDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which flow launches what at each API level (ADR 0072), at SDK 29, 31, 33, 34 and 36: the exact alarm request exists
 * on 31 and 32 only, the full screen intent screen from 34, the notification dialog from 33, the unused app screen from
 * 30; and the battery screen is the general one, on every level, with no permission to declare. Whether a flow is
 * needed is read from the capability inputs as they are, never from the version. What this shows is the intent each
 * launch would start; the screens themselves are the platform's (`MANUAL_CHECKS.md` P2-38).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 31, 33, 34, 36])
class PermissionFlowsTest {
    private val sdk = Build.VERSION.SDK_INT
    private val pkg = "com.momtime.android"

    private val nothing =
        CapabilityInputs(
            exactAlarm = false,
            fullScreenIntent = false,
            notificationsEnabled = false,
            batteryExempt = false,
            overlayAllowed = false,
            criticalChannelAllowed = false,
        )
    private val everything =
        CapabilityInputs(
            exactAlarm = true,
            fullScreenIntent = true,
            notificationsEnabled = true,
            batteryExempt = true,
            overlayAllowed = true,
            criticalChannelAllowed = true,
        )

    private fun items(
        inputs: CapabilityInputs,
        unusedAppExempt: Boolean? = false,
        requested: Boolean = false,
    ) = PermissionFlows.items(sdk, pkg, inputs, resolveDelivery(inputs), unusedAppExempt, requested)

    private fun launchOf(
        flow: PermissionFlow,
        inputs: CapabilityInputs = nothing,
        requested: Boolean = false,
    ) = items(inputs, requested = requested).single { it.flow == flow }.launch

    private fun settings(launch: FlowLaunch?): Intent = (launch as FlowLaunch.OpenSettings).intent

    @Test
    fun `the notification dialog is a runtime request from API 33, the first time, and the app's settings otherwise`() {
        val first = launchOf(PermissionFlow.NOTIFICATIONS)
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(FlowLaunch.RuntimeRequest(Manifest.permission.POST_NOTIFICATIONS), first)
        } else {
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings(first).action)
            assertEquals(pkg, settings(first).getStringExtra(Settings.EXTRA_APP_PACKAGE))
        }
        val afterRequest = launchOf(PermissionFlow.NOTIFICATIONS, requested = true)
        assertEquals(
            "after a request the dialog is not shown again",
            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            settings(afterRequest).action,
        )
    }

    @Test
    fun `the exact alarm request exists on API 31 and 32 only`() {
        val launch = launchOf(PermissionFlow.EXACT_ALARM)
        if (sdk == Build.VERSION_CODES.S || sdk == Build.VERSION_CODES.S_V2) {
            assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, settings(launch).action)
            assertEquals("package:$pkg", settings(launch).dataString)
        } else {
            assertNull("USE_EXACT_ALARM covers 33 and above, and nothing is needed below 31", launch)
        }
    }

    @Test
    fun `the full screen intent screen exists from API 34`() {
        val launch = launchOf(PermissionFlow.FULL_SCREEN_INTENT)
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, settings(launch).action)
            assertEquals("package:$pkg", settings(launch).dataString)
        } else {
            assertNull(launch)
        }
    }

    @Test
    fun `the battery flow is the general optimisation settings on every level, and never the direct request`() {
        val intent = settings(launchOf(PermissionFlow.BATTERY))

        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, intent.action)
        assertNull("the general screen takes no package", intent.data)
        assertFalse(intent.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
    }

    @Test
    fun `the overlay flow opens the overlay permission screen for this app`() {
        val intent = settings(launchOf(PermissionFlow.OVERLAY))

        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
        assertEquals("package:$pkg", intent.dataString)
    }

    @Test
    fun `the unused app restrictions screen exists from API 30`() {
        val launch = launchOf(PermissionFlow.UNUSED_APP_RESTRICTIONS)
        if (sdk >= Build.VERSION_CODES.R) {
            assertEquals(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, settings(launch).action)
            assertEquals("package:$pkg", settings(launch).dataString)
        } else {
            assertNull(launch)
        }
    }

    @Test
    fun `each flow is needed exactly when its input is missing`() {
        val needed = items(nothing, unusedAppExempt = false).filter { it.needed }.map { it.flow }.toSet()
        assertEquals(PermissionFlow.entries.toSet(), needed)

        assertEquals(
            "everything granted: nothing is needed",
            emptySet<PermissionFlow>(),
            items(
                everything,
                unusedAppExempt = true,
            ).filter {
                it.needed
            }.map { it.flow }
                .toSet(),
        )

        for (flow in listOf(
            PermissionFlow.NOTIFICATIONS,
            PermissionFlow.EXACT_ALARM,
            PermissionFlow.FULL_SCREEN_INTENT,
            PermissionFlow.BATTERY,
        )) {
            val missing =
                when (flow) {
                    PermissionFlow.NOTIFICATIONS -> everything.copy(notificationsEnabled = false)
                    PermissionFlow.EXACT_ALARM -> everything.copy(exactAlarm = false)
                    PermissionFlow.FULL_SCREEN_INTENT -> everything.copy(fullScreenIntent = false)
                    else -> everything.copy(batteryExempt = false)
                }
            val only = items(missing, unusedAppExempt = true).filter { it.needed }.map { it.flow }
            assertTrue("$flow is needed when its input is missing: $only", flow in only)
        }
        assertTrue(
            "a blocked Critical channel needs the notification flow",
            items(everything.copy(criticalChannelAllowed = false), true)
                .single {
                    it.flow ==
                        PermissionFlow.NOTIFICATIONS
                }.needed,
        )
    }

    // The overlay is the secondary route, never the ringing mechanism: it is offered only when an effective full screen
    // intent is not available.
    @Test
    fun `the overlay is offered only when an effective full screen intent is not available`() {
        val withFullScreen = everything.copy(overlayAllowed = false)
        assertFalse(items(withFullScreen, true).single { it.flow == PermissionFlow.OVERLAY }.needed)

        val noFullScreen = everything.copy(overlayAllowed = false, fullScreenIntent = false)
        assertTrue(items(noFullScreen, true).single { it.flow == PermissionFlow.OVERLAY }.needed)

        val blockedNotifications = everything.copy(overlayAllowed = false, notificationsEnabled = false)
        assertTrue(
            "with notifications off the full screen intent is not effective either",
            items(blockedNotifications, true)
                .single {
                    it.flow ==
                        PermissionFlow.OVERLAY
                }.needed,
        )

        assertFalse(
            "already allowed: not needed",
            items(noFullScreen.copy(overlayAllowed = true), true)
                .single {
                    it.flow ==
                        PermissionFlow.OVERLAY
                }.needed,
        )
    }

    @Test
    fun `the unused app restrictions are needed only when they apply`() {
        assertTrue(
            items(everything, unusedAppExempt = false)
                .single {
                    it.flow == PermissionFlow.UNUSED_APP_RESTRICTIONS
                }.needed,
        )
        assertFalse(
            items(everything, unusedAppExempt = true)
                .single {
                    it.flow == PermissionFlow.UNUSED_APP_RESTRICTIONS
                }.needed,
        )
        assertFalse(
            "below API 30 the setting does not exist",
            items(everything, unusedAppExempt = null)
                .single {
                    it.flow ==
                        PermissionFlow.UNUSED_APP_RESTRICTIONS
                }.needed,
        )
    }
}
