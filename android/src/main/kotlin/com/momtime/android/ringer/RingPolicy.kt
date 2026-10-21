package com.momtime.android.ringer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How long a ring may sound (ADR 0090). A ring session's sound and vibration stop on their own [MAX_RING] after
 * the latest fire that rang, and that stop is a dismissal: it writes nothing, the occurrence stays open, its next
 * rung is armed as before and grace runs as before. It is a placeholder for a device to judge, beside the ramp
 * ([VolumeRamp]) and the backup sound's interval (`AndroidSettings.DEFAULT_BACKUP_DELAY`, 2 minutes), which still
 * starts inside it.
 *
 * It must be shorter than the shortest gap between two device rungs of any default ladder (5 minutes, Critical's
 * repeat), so that a repeat rung always finds the sound off and is a new ring; `RingLimitTest` reads the ladders
 * and fails if it is not. Android only: nothing here is in the shared schema, the domain or the event log
 * (invariant 5).
 */
object RingPolicy {
    val MAX_RING: Duration = 4.minutes
}
