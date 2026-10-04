package com.momtime.android.delivery

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.Vibrator
import com.momtime.android.ring.RingEntryPoint
import com.momtime.android.ringer.PlatformVibration
import com.momtime.android.ringer.RingerEntryPoint
import com.momtime.android.ringer.RingerService
import com.momtime.android.ringer.VibrationPattern
import com.momtime.android.settings.AndroidSettings
import com.momtime.shared.domain.Criticality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The ring's vibration (ADR 0065): per template patterns that default by criticality, played with the alarm usage
 * while the ringer service runs and stopped with it, and her choices and defaults kept in android's own settings
 * file. The motor is the shadow's, so this shows what the platform is asked and not what a hand feels
 * (`MANUAL_CHECKS.md`).
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 33, 36])
class VibrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val settings: AndroidSettings get() = f.arming.graph.get()

    @After
    fun tearDown() {
        RingEntryPoint.provider = null
        RingerEntryPoint.provider = null
        f.close()
    }

    // --- the platform call: alarm usage

    private fun vibrator(): Vibrator {
        val vibrator = context.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        return vibrator
    }

    // A vibration with the alarm usage is not suppressed the way a notification's is, and follows the alarm's rules.
    @Test
    fun `the vibration is played with the alarm usage`() {
        val vibrator = vibrator()

        PlatformVibration(context).start(VibrationPattern.URGENT)

        assertTrue(shadowOf(vibrator).isVibrating)
        assertTrue("the alarm usage: ${usageOf(vibrator)}", usageOf(vibrator) == "ALARM")
    }

    /**
     * The usage the last vibration carried, as the shadow recorded it. Up to API 32 it keeps the `AudioAttributes` it
     * was given; from API 33 the platform turns them into `VibrationAttributes` first, and the shadow keeps those.
     */
    private fun usageOf(vibrator: Vibrator): String {
        val shadow = shadowOf(vibrator)
        val audio = shadow.audioAttributesFromLastVibration
        val vibration = shadow.vibrationAttributesFromLastVibration as? VibrationAttributes
        return when {
            audio != null -> if (audio.usage == AudioAttributes.USAGE_ALARM) "ALARM" else "other ${audio.usage}"
            vibration != null ->
                if (vibration.usage == VibrationAttributes.USAGE_ALARM) "ALARM" else "other ${vibration.usage}"
            else -> "none"
        }
    }

    @Test
    fun `stopping cancels the vibration and none vibrates nothing`() {
        val vibrator = vibrator()
        val vibration = PlatformVibration(context)

        vibration.start(VibrationPattern.NONE)
        assertFalse("none vibrates nothing", shadowOf(vibrator).isVibrating)

        vibration.start(VibrationPattern.STEADY)
        assertTrue(shadowOf(vibrator).isVibrating)
        vibration.stop()
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `a device with no vibrator does nothing`() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(false)

        PlatformVibration(context).start(VibrationPattern.URGENT)

        assertFalse(shadowOf(vibrator).isVibrating)
    }

    // --- the patterns

    @Test
    fun `the default pattern follows the criticality`() {
        assertEquals(VibrationPattern.URGENT, VibrationPattern.defaultFor(Criticality.CRITICAL))
        assertEquals(VibrationPattern.STEADY, VibrationPattern.defaultFor(Criticality.STANDARD))
        assertEquals(VibrationPattern.LIGHT, VibrationPattern.defaultFor(Criticality.GENTLE))
        assertEquals(
            "three different defaults",
            3,
            Criticality.entries
                .map(VibrationPattern::defaultFor)
                .toSet()
                .size,
        )
    }

    @Test
    fun `every pattern starts with an off and has an on and an off in turn`() {
        for (pattern in VibrationPattern.entries - VibrationPattern.NONE) {
            assertTrue("$pattern", pattern.timings.size >= 2 && pattern.timings.first() == 0L)
            assertTrue("$pattern has only positive durations after the first", pattern.timings.drop(1).all { it > 0 })
        }
        assertEquals(0, VibrationPattern.NONE.timings.size)
    }

    // --- her choices, and the defaults

    @Test
    fun `a template vibrates with its criticality's pattern until she chooses another`() {
        assertEquals(VibrationPattern.URGENT, settings.vibrationFor("t1", Criticality.CRITICAL))
        assertEquals(VibrationPattern.LIGHT, settings.vibrationFor("t1", Criticality.GENTLE))

        settings.setVibration("t1", VibrationPattern.NONE)

        assertEquals("hers wins", VibrationPattern.NONE, settings.vibrationFor("t1", Criticality.CRITICAL))
        assertEquals(
            "another template is unaffected",
            VibrationPattern.URGENT,
            settings.vibrationFor("t2", Criticality.CRITICAL),
        )

        settings.setVibration("t1", null)

        assertEquals("back to the default", VibrationPattern.URGENT, settings.vibrationFor("t1", Criticality.CRITICAL))
    }

    @Test
    fun `a stored name this version does not know reads as the default`() {
        context
            .getSharedPreferences(AndroidSettings.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("vibration.t1", "FROM_A_NEWER_VERSION")
            .commit()

        assertEquals(VibrationPattern.STEADY, settings.vibrationFor("t1", Criticality.STANDARD))
    }

    @Test
    fun `the backup interval defaults to two minutes and can be turned off`() {
        assertEquals(AndroidSettings.DEFAULT_BACKUP_DELAY, settings.backupSoundDelay())

        settings.setBackupSoundDelay(null)
        assertEquals(null, settings.backupSoundDelay())

        settings.setBackupSoundDelay(AndroidSettings.DEFAULT_BACKUP_DELAY)
        assertEquals(AndroidSettings.DEFAULT_BACKUP_DELAY, settings.backupSoundDelay())
    }

    // --- through the fire path and the service

    @Test
    fun `the ring asks for the template's pattern`() {
        settings.setVibration("tmpl-b", VibrationPattern.LIGHT)
        f.fire(f.due("a", Criticality.CRITICAL, slot = 31))
        f.arming.graph
            .get<com.momtime.android.ring.RingSessions>()
            .end()
        f.fire(f.due("b", Criticality.CRITICAL, slot = 32))

        assertEquals(
            "the default for CRITICAL, then hers for the second template",
            listOf(VibrationPattern.URGENT, VibrationPattern.LIGHT),
            f.ringer.starts.map { it.vibration },
        )
    }

    @Test
    fun `the service vibrates with the pattern it was asked for and stops with the sound`() {
        f.fire(f.due("a", Criticality.STANDARD, slot = 31))
        RingerEntryPoint.provider = { f.arming.graph.get<RingController>() }
        val request = f.ringer.starts.single()
        assertEquals(VibrationPattern.STEADY, request.vibration)

        val service =
            Robolectric.buildService(RingerService::class.java, RingerService.startIntent(context, request)).create()
        service.startCommand(0, 1)

        assertEquals(listOf(VibrationPattern.STEADY), f.vibration.starts)
        assertEquals(0, f.vibration.stops)

        service.destroy()

        assertEquals("it stops with the sound", 1, f.vibration.stops)
        assertEquals(1, f.sound.stops)
    }

    @Test
    fun `a stray intent vibrates nothing`() {
        RingerEntryPoint.provider = { f.arming.graph.get<RingController>() }

        Robolectric
            .buildService(
                RingerService::class.java,
                android.content.Intent("nothing"),
            ).create()
            .startCommand(0, 1)

        assertTrue(f.vibration.starts.isEmpty())
        assertNotNull(f.vibration)
    }
}
