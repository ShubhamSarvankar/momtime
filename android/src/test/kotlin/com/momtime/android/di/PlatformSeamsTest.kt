package com.momtime.android.di

import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import com.momtime.android.arming.AlarmIntents
import com.momtime.android.arming.AppVersion
import com.momtime.android.arming.PlatformAlarmProbe
import com.momtime.android.arming.PlatformAppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * The defaults of the seams the watchdog reads the platform through: the boot count, the alarm probe and the
 * app's update time. The watchdog tests replace each with a fake they control, so these show that the real
 * implementations read the platform value they should. They live in the DI package because the android clock
 * check allows `BOOT_COUNT` only there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
class PlatformSeamsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `the boot count is what the platform reports`() {
        assertEquals("not reported", BootCount.UNKNOWN, platformBootCount(context).read())
        Settings.Global.putLong(context.contentResolver, Settings.Global.BOOT_COUNT, 41L)
        assertEquals(41L, platformBootCount(context).read())
        Settings.Global.putLong(context.contentResolver, Settings.Global.BOOT_COUNT, 42L)
        assertEquals("it follows the platform", 42L, platformBootCount(context).read())
    }

    // The probe asks without creating: asking about a slot that is not armed must not arm it.
    @Test
    fun `the probe finds an armed alarm and creates nothing`() {
        val probe = PlatformAlarmProbe(context)
        assertFalse("nothing is armed", probe.isArmed(77))
        assertFalse("and asking created nothing", probe.isArmed(77))

        val pending = AlarmIntents.fire(context, 77, Instant.fromEpochMilliseconds(1_000))
        assertTrue(probe.isArmed(77))
        assertFalse("another slot is not this one", probe.isArmed(78))

        pending.cancel()
        assertFalse("a cancelled PendingIntent is gone", probe.isArmed(77))
    }

    @Test
    fun `the version code is the package's`() {
        shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).setLongVersionCode(4242L)
        assertEquals(4242L, PlatformAppVersion(context).versionCode())
    }

    @Test
    fun `an unknown package has no version code`() {
        val other =
            object : ContextWrapper(context) {
                override fun getPackageName() = "com.example.not.installed"
            }
        assertEquals(AppVersion.UNKNOWN, PlatformAppVersion(other).versionCode())
    }
}
