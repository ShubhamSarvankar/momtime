package com.momtime.android.ringer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import com.momtime.android.R
import kotlin.time.Duration

/** The sound of a ring. A seam, so that a test can see what was started without a speaker. */
interface AlarmSound {
    /** Starts the sound, looping. True if audio focus was obtained; the sound plays whatever the answer. */
    fun start(): Boolean

    /** Stops the sound and gives up audio focus. Stopping a sound that is not playing is not an error. */
    fun stop()
}

/**
 * How the ringer's audio is described to the platform (ADR 0061): `USAGE_ALARM`, so that it plays on the alarm
 * stream and carries through Do Not Disturb without notification policy access (CLAUDE.md), with sonification
 * content. The notification is the one that carries `CATEGORY_ALARM`.
 */
object AlarmAudio {
    fun attributes(): AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
}

/**
 * The volume ramp (ADR 0065): a gentle start that reaches full player volume, so that a ring does not startle her
 * at the first beep, and nothing else. It is a function of the number of steps taken, never of a clock. It is the
 * volume of the player only: the system alarm stream volume is hers and is never changed by the app, so a ramp
 * cannot make the ring louder than she set her phone to, and a muted stream stays muted (which is recorded, and is
 * the reliability banner's concern, PR 7).
 */
class VolumeRamp(
    val start: Float = DEFAULT_START,
    val steps: Int = DEFAULT_STEPS,
    val stepMillis: Long = DEFAULT_STEP_MILLIS,
) {
    init {
        require(start in 0f..1f && steps > 0 && stepMillis > 0)
    }

    /** The player volume after [step] steps: [start] at 0, 1.0 from [steps] on, rising evenly between. */
    fun levelAt(step: Int): Float = if (step >= steps) 1f else start + (1f - start) * step.coerceAtLeast(0) / steps

    companion object {
        const val DEFAULT_START = 0.3f
        const val DEFAULT_STEPS = 12
        const val DEFAULT_STEP_MILLIS = 500L
    }
}

/** Which of the two sounds. */
enum class RingSoundKind { PRIMARY, BACKUP }

/** One playing loop. A seam over `MediaPlayer`. */
interface SoundPlayer {
    /** Sets this player's own volume, from 0 to 1. It is never the system stream volume. */
    fun setVolume(level: Float)

    fun start()

    fun stop()
}

/** Makes a player for a sound, or null if it cannot be played (and the caller falls back or stays silent). */
fun interface SoundPlayers {
    fun create(kind: RingSoundKind): SoundPlayer?
}

/** Runs something later and lets it be cancelled. A seam over the main looper. */
interface SoundScheduler {
    fun postDelayed(
        delayMillis: Long,
        block: () -> Unit,
    ): () -> Unit
}

/** The main looper's scheduler. A ring starts and stops on the main thread, so its steps do too. */
class MainLooperScheduler : SoundScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(
        delayMillis: Long,
        block: () -> Unit,
    ): () -> Unit {
        val runnable = Runnable { block() }
        handler.postDelayed(runnable, delayMillis)
        return { handler.removeCallbacks(runnable) }
    }
}

/**
 * The real sound (ADR 0061, ADR 0065): the primary sound looped on the alarm stream, with transient audio focus
 * requested first, started quietly and ramped up on the player ([VolumeRamp]); and, if the ring is still going when
 * [backupDelay] has passed, the louder backup sound in its place at full player volume. Stopping the ring (the sound
 * is stopped, or the session ended) cancels the ramp and the backup. Nothing here touches the system stream volume.
 *
 * If a sound cannot be played the next best is used and a ring is never silent for that: the primary falls back to
 * the system's alarm tone, and a backup that cannot be played leaves the primary going. The final sounds are
 * Shubham's to choose (ADR 0061).
 */
class RingSound(
    private val context: Context,
    private val players: SoundPlayers,
    private val scheduler: SoundScheduler,
    private val ramp: VolumeRamp = VolumeRamp(),
    private val backupDelay: () -> Duration?,
) : AlarmSound {
    private var player: SoundPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val cancellations = mutableListOf<() -> Unit>()

    @Synchronized
    override fun start(): Boolean {
        stop()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AlarmAudio.attributes())
                .setOnAudioFocusChangeListener { }
                .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focus = request
        player = players.create(RingSoundKind.PRIMARY)?.also { begin(it) }
        if (player != null) {
            scheduleRamp(player)
            backupDelay()?.let { delay ->
                cancellations +=
                    scheduler.postDelayed(delay.inWholeMilliseconds, ::playBackup)
            }
        }
        return granted
    }

    @Synchronized
    override fun stop() {
        cancellations.forEach { it() }
        cancellations.clear()
        player?.let { runCatching(it::stop) }
        player = null
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun begin(sound: SoundPlayer) {
        sound.setVolume(ramp.levelAt(0))
        sound.start()
    }

    private fun scheduleRamp(sound: SoundPlayer?) {
        for (step in 1..ramp.steps) {
            cancellations +=
                scheduler.postDelayed(step * ramp.stepMillis) { sound?.setVolume(ramp.levelAt(step)) }
        }
    }

    @Synchronized
    private fun playBackup() {
        val backup = players.create(RingSoundKind.BACKUP) ?: return
        player?.let { runCatching(it::stop) }
        backup.setVolume(1f)
        backup.start()
        player = backup
    }
}

/** `MediaPlayer`, looping on the alarm stream. */
class MediaPlayers(
    private val context: Context,
) : SoundPlayers {
    override fun create(kind: RingSoundKind): SoundPlayer? {
        val raw = if (kind == RingSoundKind.PRIMARY) R.raw.ring_primary else R.raw.ring_backup
        val player = prepared(bundled(raw)) ?: if (kind == RingSoundKind.PRIMARY) prepared(systemAlarmTone()) else null
        return player?.let(::MediaSoundPlayer)
    }

    private fun bundled(resource: Int): MediaPlayer =
        MediaPlayer().apply {
            setAudioAttributes(AlarmAudio.attributes())
            context.resources.openRawResourceFd(resource).use {
                setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
        }

    private fun systemAlarmTone(): MediaPlayer =
        MediaPlayer().apply {
            setAudioAttributes(AlarmAudio.attributes())
            setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        }

    @Suppress("TooGenericExceptionCaught")
    private fun prepared(candidate: MediaPlayer): MediaPlayer? =
        try {
            candidate.isLooping = true
            candidate.prepare()
            candidate
        } catch (_: RuntimeException) {
            candidate.release()
            null
        } catch (_: java.io.IOException) {
            candidate.release()
            null
        }
}

private class MediaSoundPlayer(
    private val player: MediaPlayer,
) : SoundPlayer {
    override fun setVolume(level: Float) = player.setVolume(level, level)

    override fun start() = player.start()

    override fun stop() {
        runCatching(player::stop)
        player.release()
    }
}
