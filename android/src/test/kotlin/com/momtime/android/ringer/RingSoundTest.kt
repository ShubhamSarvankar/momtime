package com.momtime.android.ringer

import android.content.Context
import android.media.AudioManager
import com.momtime.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A player that records what it was told. Its volume is its own, never the system stream's. */
internal class FakePlayer(
    val kind: RingSoundKind,
) : SoundPlayer {
    val volumes = mutableListOf<Float>()
    var started = false
    var stopped = false

    override fun setVolume(level: Float) {
        volumes += level
    }

    override fun start() {
        started = true
    }

    override fun stop() {
        stopped = true
    }
}

internal class FakePlayers : SoundPlayers {
    val created = mutableListOf<FakePlayer>()

    /** Kinds that cannot be played: [create] answers null for them. */
    val unplayable = mutableSetOf<RingSoundKind>()

    override fun create(kind: RingSoundKind): SoundPlayer? =
        if (kind in
            unplayable
        ) {
            null
        } else {
            FakePlayer(kind).also(created::add)
        }

    fun of(kind: RingSoundKind) = created.filter { it.kind == kind }
}

/** A scheduler a test drives by hand: nothing runs until [advance] says time has passed. */
internal class FakeScheduler : SoundScheduler {
    private class Task(
        val at: Long,
        val block: () -> Unit,
    ) {
        var cancelled = false
    }

    private var now = 0L
    private val tasks = mutableListOf<Task>()

    val pending: Int get() = tasks.count { !it.cancelled }

    override fun postDelayed(
        delayMillis: Long,
        block: () -> Unit,
    ): () -> Unit {
        val task = Task(now + delayMillis, block)
        tasks += task
        return { task.cancelled = true }
    }

    fun advance(millis: Long) {
        val target = now + millis
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
            now = next.at
            tasks.remove(next)
            next.block()
        }
        now = target
    }

    fun advance(duration: Duration) = advance(duration.inWholeMilliseconds)
}

/**
 * The ring's sound (ADR 0065): a ramp on the player and nothing else, and a louder backup sound after the
 * unacknowledged interval. The players and the scheduler are fakes, driven by hand; the audio manager is the
 * platform's own (Robolectric's shadow), which is how a test reads the system stream volume.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 36])
class RingSoundTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private val players = FakePlayers()
    private val scheduler = FakeScheduler()
    private var delay: Duration? = 2.minutes
    private val sound = RingSound(context, players, scheduler, VolumeRamp()) { delay }

    @Before
    fun setUp() {
        // A volume the user chose, away from both ends, so a change either way is visible.
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
    }

    private fun streamVolume() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

    // --- the ramp

    @Test
    fun `the ramp is a function of the step and reaches full player volume`() {
        val ramp = VolumeRamp(start = 0.2f, steps = 4, stepMillis = 100)
        assertEquals(listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f, 1f), (0..5).map { ramp.levelAt(it) })
        assertEquals("a step before the start is the start", 0.2f, ramp.levelAt(-3), 0f)
    }

    @Test
    fun `the sound starts quietly and rises to full on the player`() {
        sound.start()
        val primary = players.of(RingSoundKind.PRIMARY).single()

        assertTrue(primary.started)
        assertEquals("it starts at the ramp's start", listOf(VolumeRamp.DEFAULT_START), primary.volumes)

        scheduler.advance(VolumeRamp.DEFAULT_STEPS * VolumeRamp.DEFAULT_STEP_MILLIS)

        assertEquals("one volume per step", 1 + VolumeRamp.DEFAULT_STEPS, primary.volumes.size)
        assertEquals("it rises, never falls", primary.volumes.sorted(), primary.volumes)
        assertEquals("and reaches full player volume", 1f, primary.volumes.last(), 0f)
        assertTrue("never above full", primary.volumes.all { it in 0f..1f })
    }

    // The system alarm stream volume is hers. The ramp is the player's volume only, so a ramp through to full and
    // a backup sound leave the stream exactly where she set it.
    @Test
    fun `the ramp and the backup never change the system stream volume`() {
        assertEquals(3, streamVolume())

        sound.start()
        assertEquals("starting", 3, streamVolume())
        scheduler.advance(VolumeRamp.DEFAULT_STEPS * VolumeRamp.DEFAULT_STEP_MILLIS)
        assertEquals("after the ramp", 3, streamVolume())
        scheduler.advance(3.minutes)
        assertEquals("after the backup sound started", 3, streamVolume())
        sound.stop()
        assertEquals("after stopping", 3, streamVolume())
        assertTrue(
            "the ramp did run",
            players
                .of(RingSoundKind.PRIMARY)
                .single()
                .volumes.size > 1,
        )
    }

    @Test
    fun `a muted stream stays muted`() {
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        sound.start()
        scheduler.advance(VolumeRamp.DEFAULT_STEPS * VolumeRamp.DEFAULT_STEP_MILLIS)

        assertEquals(0, streamVolume())
    }

    // --- the backup sound

    @Test
    fun `the backup sound plays only after the interval`() {
        sound.start()

        scheduler.advance(2.minutes - 1.seconds)
        assertTrue("not yet", players.of(RingSoundKind.BACKUP).isEmpty())
        assertFalse(players.of(RingSoundKind.PRIMARY).single().stopped)

        scheduler.advance(1.seconds)

        val backup = players.of(RingSoundKind.BACKUP).single()
        assertTrue(backup.started)
        assertEquals("the louder sound plays at full player volume", listOf(1f), backup.volumes)
        assertTrue("it takes the primary's place", players.of(RingSoundKind.PRIMARY).single().stopped)
    }

    @Test
    fun `the interval is the one asked for and none turns the backup off`() {
        delay = 30.seconds
        sound.start()
        scheduler.advance(29.seconds)
        assertTrue(players.of(RingSoundKind.BACKUP).isEmpty())
        scheduler.advance(1.seconds)
        assertEquals(1, players.of(RingSoundKind.BACKUP).size)
        sound.stop()

        delay = null
        sound.start()
        scheduler.advance(10.minutes)
        assertEquals("no second backup sound", 1, players.of(RingSoundKind.BACKUP).size)
    }

    // Stopping the ring (acknowledged, or the sound stopped) cancels the ramp and the backup.
    @Test
    fun `stopping cancels the ramp and the backup`() {
        sound.start()
        scheduler.advance(1.seconds)
        val primary = players.of(RingSoundKind.PRIMARY).single()
        val volumesAtStop = primary.volumes.size

        sound.stop()
        scheduler.advance(10.minutes)

        assertTrue(primary.stopped)
        assertEquals("no ramp step after stopping", volumesAtStop, primary.volumes.size)
        assertTrue("no backup after stopping", players.of(RingSoundKind.BACKUP).isEmpty())
        assertEquals("nothing is left scheduled", 0, scheduler.pending)
    }

    @Test
    fun `starting again stops the first and does not stack`() {
        sound.start()
        sound.start()

        val primaries = players.of(RingSoundKind.PRIMARY)
        assertEquals(2, primaries.size)
        assertTrue("the first was stopped", primaries.first().stopped)
        scheduler.advance(10.minutes)
        assertEquals("one backup, for the second start", 1, players.of(RingSoundKind.BACKUP).size)
    }

    // A backup that cannot be played leaves the primary going: a ring is never silenced by a missing file.
    @Test
    fun `a backup sound that cannot play leaves the primary going`() {
        players.unplayable += RingSoundKind.BACKUP
        sound.start()

        scheduler.advance(5.minutes)

        val primary = players.of(RingSoundKind.PRIMARY).single()
        assertFalse(primary.stopped)
        assertTrue(players.of(RingSoundKind.BACKUP).isEmpty())
    }

    @Test
    fun `audio focus is asked for and its answer returned`() {
        assertTrue("the shadow grants it", sound.start())
        sound.stop()
    }

    // --- the placeholder files

    private data class Wav(
        val rate: Int,
        val samples: Int,
        val peak: Int,
        val rms: Double,
    )

    private fun wav(resource: Int): Wav {
        val bytes = context.resources.openRawResource(resource).use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        val rate = buffer.getInt(24)
        val count = (bytes.size - 44) / 2
        var peak = 0
        var sum = 0.0
        for (i in 0 until count) {
            val sample = buffer.getShort(44 + i * 2).toInt()
            peak = maxOf(peak, kotlin.math.abs(sample))
            sum += sample.toDouble() * sample
        }
        return Wav(rate, count, peak, kotlin.math.sqrt(sum / count))
    }

    // Both are placeholders from the same script (ADR 0061, ADR 0065): the same format and length, under the 30
    // second limit, loopable, and the backup is louder in the file because the stream volume is never raised.
    @Test
    fun `the backup sound is a placeholder of the same format and is louder`() {
        val primary = wav(R.raw.ring_primary)
        val backup = wav(R.raw.ring_backup)

        assertEquals(primary.rate, backup.rate)
        assertEquals(primary.samples, backup.samples)
        assertTrue("under 30 seconds", backup.samples.toDouble() / backup.rate < 30)
        assertTrue("louder at the peak: ${backup.peak} against ${primary.peak}", backup.peak > primary.peak)
        assertTrue("and louder overall: ${backup.rms} against ${primary.rms}", backup.rms > primary.rms)
    }
}
