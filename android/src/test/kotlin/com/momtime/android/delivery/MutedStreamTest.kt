package com.momtime.android.delivery

import android.content.Context
import android.media.AudioManager
import com.momtime.shared.domain.Criticality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * A fire records whether the alarm stream was muted (ADR 0065): a ring she cannot hear is a reliability fact, and
 * the banner that tells her (PR 7) reads it. Delivery only records it. The stream's volume is hers and is never
 * changed by the app, so a muted stream is still muted afterwards, and the ring is delivered all the same.
 */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class MutedStreamTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val f = DeliveryFixture(context)
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    @After
    fun tearDown() = f.close()

    @Test
    fun `a fire with the alarm stream at zero records it`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        f.fire(f.due("a", Criticality.CRITICAL, slot = 31))

        assertEquals(true, f.telemetryOf("a").alarmStreamMuted)
        assertEquals("the ring was delivered all the same", 1, f.ringer.starts.size)
        assertEquals("and the stream is still hers", 0, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun `a fire with the alarm stream audible records that`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)

        f.fire(f.due("a", Criticality.CRITICAL, slot = 31))

        assertEquals(false, f.telemetryOf("a").alarmStreamMuted)
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    // Every path records it, not only the ones with a ringer: a silent notice or a plain notification is a fire too.
    @Test
    fun `a silent path records it as well`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)
        val a = f.due("a", Criticality.STANDARD, slot = 31)

        f.fire(
            a,
            now = a.scheduledInstant + com.momtime.shared.engine.CatchUp.WINDOW + kotlin.time.Duration.parse("1m"),
        )

        assertEquals("SILENT_NOTICE", f.telemetryOf("a").deliveryPath)
        assertEquals(true, f.telemetryOf("a").alarmStreamMuted)
    }
}
