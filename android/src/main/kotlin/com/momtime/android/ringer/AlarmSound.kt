package com.momtime.android.ringer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import com.momtime.android.R

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
 * The real sound: the placeholder `ring_primary.wav`, looped on the alarm stream, with transient audio focus
 * requested first. If the bundled sound cannot be played, the system's alarm tone is the fallback, because a
 * silent ring is worse than the wrong sound. The final sounds are Shubham's to choose (ADR 0061).
 */
class MediaPlayerAlarmSound(
    private val context: Context,
) : AlarmSound {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

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
        player = prepared(bundled()) ?: prepared(systemAlarmTone())
        player?.start()
        return granted
    }

    @Synchronized
    override fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun bundled(): MediaPlayer =
        MediaPlayer().apply {
            setAudioAttributes(AlarmAudio.attributes())
            context.resources.openRawResourceFd(R.raw.ring_primary).use {
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
