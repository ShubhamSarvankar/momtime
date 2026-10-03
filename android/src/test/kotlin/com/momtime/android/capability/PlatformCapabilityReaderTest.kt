package com.momtime.android.capability

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSettings

/**
 * The platform adapter, input by input, against Robolectric's shadows at SDK 29, 31, 33 and 36 (ADR 0050).
 * The SDKs are the ones where the permission model differs: exact alarms need no permission at 29 and 30
 * and have `canScheduleExactAlarms()` from 31; full screen intent is the declared permission below 34 and
 * `canUseFullScreenIntent()` from 34.
 *
 * Each test sets the shadow to both values and reads the input back, so an adapter that read a constant
 * for that input would fail on one of them. Every input has a shadow, so no input is read through a seam
 * except the Critical channel, which is a seam until channels exist in PR 5.
 *
 * What this shows is that each input is read from the platform call it should be. It does not show what
 * a real device reports (`MANUAL_CHECKS.md` P2-10).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 31, 33, 36])
class PlatformCapabilityReaderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val sdk = Build.VERSION.SDK_INT
    private val reader = PlatformCapabilityReader(context)

    private val notificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val powerManager get() = context.getSystemService(PowerManager::class.java)

    @Before
    fun setUp() {
        // Start from a known state, so a test that sets one input reads the others as they were set here.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(notificationManager).setNotificationsEnabled(true)
        shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, false)
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test
    fun `exact capability follows the platform from API 31 and is true below`() {
        for (value in listOf(false, true)) {
            ShadowAlarmManager.setCanScheduleExactAlarms(value)
            val expected = if (sdk >= Build.VERSION_CODES.S) value else true
            assertEquals("exact alarm at SDK $sdk with the platform at $value", expected, reader.exactAlarm())
        }
    }

    @Test
    fun `full screen intent follows the platform from API 34 and the declared permission below`() {
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Robolectric has no shadow for canUseFullScreenIntent(), so the call is read through a seam
            // (MANUAL_CHECKS P2-10). The seam's value must be what the adapter returns, either way.
            for (value in listOf(false, true)) {
                val seamed = PlatformCapabilityReader(context, fullScreenIntentApi = { value })
                assertEquals("full screen intent at SDK $sdk with the seam at $value", value, seamed.fullScreenIntent())
            }
            // The default seam is the real call. Robolectric answers it with a constant false, so this
            // catches a default that reads true and cannot catch one that reads false.
            assertEquals(notificationManager.canUseFullScreenIntent(), reader.fullScreenIntent())
        } else {
            // The manifest declares USE_FULL_SCREEN_INTENT, so it reads as available.
            assertTrue("the manifest must declare USE_FULL_SCREEN_INTENT", reader.fullScreenIntent())
            // Take the declaration away and it reads as unavailable.
            val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
            info.requestedPermissions =
                info.requestedPermissions
                    .orEmpty()
                    .filterNot { it.endsWith("USE_FULL_SCREEN_INTENT") }
                    .toTypedArray()
            assertEquals("not declared at SDK $sdk", false, reader.fullScreenIntent())
        }
    }

    @Test
    fun `notifications enabled follows the platform`() {
        for (value in listOf(false, true)) {
            shadowOf(notificationManager).setNotificationsEnabled(value)
            assertEquals("notifications at SDK $sdk with the platform at $value", value, reader.notificationsEnabled())
        }
    }

    @Test
    fun `battery exemption follows the platform`() {
        for (value in listOf(false, true)) {
            shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, value)
            assertEquals("battery exemption at SDK $sdk with the platform at $value", value, reader.batteryExempt())
        }
    }

    @Test
    fun `overlay permission follows the platform`() {
        for (value in listOf(false, true)) {
            ShadowSettings.setCanDrawOverlays(value)
            assertEquals("overlay at SDK $sdk with the platform at $value", value, reader.overlayAllowed())
        }
    }

    @Test
    fun `the critical channel is read through the seam, as not blocked by default`() {
        assertTrue(reader.read().criticalChannelAllowed)
        assertEquals(
            false,
            PlatformCapabilityReader(context, criticalChannelBlocked = { true }).read().criticalChannelAllowed,
        )
        assertEquals(
            true,
            PlatformCapabilityReader(context, criticalChannelBlocked = { false }).read().criticalChannelAllowed,
        )
    }

    // Flipping one input on the platform changes that input and no other: nothing is wired to the wrong
    // call, and nothing is inferred from another input.
    @Test
    fun `each input on its own changes only itself`() {
        val baseline = reader.read()
        val flips: Map<String, Pair<() -> Unit, (CapabilityInputs) -> CapabilityInputs>> =
            mapOf(
                "notifications" to
                    (
                        { shadowOf(notificationManager).setNotificationsEnabled(false) } to
                            { it.copy(notificationsEnabled = false) }
                    ),
                "battery" to
                    (
                        { shadowOf(powerManager).setIgnoringBatteryOptimizations(context.packageName, true) } to
                            { it.copy(batteryExempt = true) }
                    ),
                "overlay" to ({ ShadowSettings.setCanDrawOverlays(true) } to { it.copy(overlayAllowed = true) }),
            )
        for ((name, flip) in flips) {
            setUp()
            flip.first()
            assertEquals("flipping $name changed another input at SDK $sdk", flip.second(baseline), reader.read())
        }
        if (sdk >= Build.VERSION_CODES.S) {
            setUp()
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            assertEquals(baseline.copy(exactAlarm = false), reader.read())
        }
        if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setUp()
            val on = PlatformCapabilityReader(context, fullScreenIntentApi = { true }).read()
            val off = PlatformCapabilityReader(context, fullScreenIntentApi = { false }).read()
            assertEquals(on.copy(fullScreenIntent = false), off)
        }
    }

    // An API that does not exist at the running SDK would throw NoSuchMethodError. The adapter guards each
    // such call, so reading everything at every SDK, including 29 where neither exists, must not.
    @Test
    fun `resolution at this SDK makes no call to an API that does not exist here`() {
        try {
            val inputs = reader.read()
            resolveDelivery(inputs)
        } catch (error: NoSuchMethodError) {
            fail("a call to an API that does not exist at SDK $sdk: $error")
        }
    }

    @Test
    fun `at SDK 29 and 30 exact capability is true however the platform shadow is set`() {
        if (sdk >= Build.VERSION_CODES.S) return
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertTrue("exact alarms need no permission below API 31", reader.exactAlarm())
        assertEquals(DeliveryMechanism.SET_ALARM_CLOCK, resolveDelivery(reader.read()).mechanism)
    }
}
