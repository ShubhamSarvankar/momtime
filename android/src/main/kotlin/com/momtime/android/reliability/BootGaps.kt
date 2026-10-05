package com.momtime.android.reliability

import com.momtime.android.store.BootInstant
import kotlin.time.Instant

/**
 * Boots the app did not run in (ADR 0071). Every boot the app saw is recorded with its boot count, and the count goes
 * up by one with each boot, so two recorded boots whose counts differ by more than one have unseen boots between them:
 * neither the boot pass nor a process start ran, which is what One UI's deep sleep does to an app that is not opened.
 * The first record after an install has no predecessor and so no gap: the app cannot know about boots before it was
 * there. Pure; the records are an input.
 */
object BootGaps {
    /** How many boots lie between [boot] and the recorded boot before it, or zero if it is the first record. */
    fun unseenBefore(
        boots: List<BootInstant>,
        boot: BootInstant,
    ): Int {
        val previous = boots.filter { it.bootCount < boot.bootCount }.maxOfOrNull { it.bootCount } ?: return 0
        return (boot.bootCount - previous - 1).toInt()
    }

    /** The unseen boots that ended at a boot recorded at or after [from]. A count and nothing else: no instant. */
    fun unseenSince(
        boots: List<BootInstant>,
        from: Instant,
    ): Int = boots.filter { it.bootedAt >= from }.sumOf { unseenBefore(boots, it) }
}
