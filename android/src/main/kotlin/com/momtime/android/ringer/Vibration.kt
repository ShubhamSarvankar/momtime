package com.momtime.android.ringer

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.momtime.shared.domain.Criticality

/**
 * A vibration pattern for a ring (ADR 0065): alternating off and on durations in milliseconds, starting with an
 * off (the platform's waveform convention), repeated until the ring stops. Android only: it is not in the shared
 * schema, the domain or the event log (invariant 5). The default for a template is its criticality's pattern, and
 * she may choose another per template (settings screen: Phase 3). The patterns are placeholders for hardware to
 * judge (`MANUAL_CHECKS.md`).
 */
@Suppress("MagicNumber") // the patterns are data: durations in milliseconds
enum class VibrationPattern(
    val timings: LongArray,
) {
    /** Three firm pulses and a pause: for `CRITICAL`. */
    URGENT(longArrayOf(0, 700, 300, 700, 300, 700, 1000)),

    /** Two pulses and a pause: for `STANDARD`. */
    STEADY(longArrayOf(0, 500, 500, 500, 1500)),

    /** One short pulse and a long pause: for `GENTLE`. */
    LIGHT(longArrayOf(0, 200, 1800)),

    /** She turned vibration off for this template. */
    NONE(longArrayOf()),
    ;

    companion object {
        fun defaultFor(criticality: Criticality): VibrationPattern =
            when (criticality) {
                Criticality.CRITICAL -> URGENT
                Criticality.STANDARD -> STEADY
                Criticality.GENTLE -> LIGHT
            }

        /** The pattern named [name], or null if none is. */
        fun fromName(name: String): VibrationPattern? = entries.firstOrNull { it.name == name }
    }
}

/** The vibration of a ring. A seam, so that a test can see what was asked without a vibrator motor. */
interface AlarmVibration {
    /** Starts [pattern], repeating until [stop]. [VibrationPattern.NONE] vibrates nothing. */
    fun start(pattern: VibrationPattern)

    /** Stops the vibration. Stopping one that is not running is not an error. */
    fun stop()
}

/**
 * The platform's vibrator, with the alarm usage (`AudioAttributes.USAGE_ALARM`, the same attributes as the sound), so
 * that it is not suppressed the way a notification's vibration is and follows the same rules the alarm sound does. A
 * device without a vibrator does nothing. The `AudioAttributes` overload is deprecated from API 33 in favour of
 * `VibrationAttributes`, which does not exist on API 29: this is the one call that works on the whole range.
 */
class PlatformVibration(
    context: Context,
) : AlarmVibration {
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    @Suppress("DEPRECATION")
    override fun start(pattern: VibrationPattern) {
        val target = vibrator ?: return
        if (pattern == VibrationPattern.NONE || !target.hasVibrator()) return
        target.vibrate(VibrationEffect.createWaveform(pattern.timings, REPEAT_FROM_START), AlarmAudio.attributes())
    }

    override fun stop() {
        vibrator?.cancel()
    }

    private companion object {
        const val REPEAT_FROM_START = 0
    }
}
