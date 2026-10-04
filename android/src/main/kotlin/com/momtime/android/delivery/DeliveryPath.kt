package com.momtime.android.delivery

import com.momtime.android.arming.FiredRung
import com.momtime.android.arming.Presentation
import com.momtime.android.capability.DeliveryResolution
import com.momtime.android.capability.ResolvedTier
import com.momtime.shared.engine.RungDelivery

/**
 * The six ways a fired rung reaches her (ADR 0060), one path each. The path is a function of the rung's
 * presentation, what the domain decided, and the capability resolution, and of nothing else.
 */
internal enum class DeliveryPath {
    /** Tier 3: a full screen intent notification and the ringer service. */
    RING,

    /** Tier 2, exact but without an effective full screen intent: a heads up notification and the ringer. */
    HEADS_UP,

    /** Notifications denied: the ringer sounds and nothing else is shown, except through the overlay route. */
    AUDIO_ONLY,

    /** Tier 1, no exact capability: a plain notification, and no ringer. */
    PLAIN,

    /** A rung beyond the catch up window: a silent notification, no ring and no heads up (ADR 0056). */
    SILENT_NOTICE,

    /** Quiet hours or the interruption budget, decided by the domain: a silent notification. */
    SILENT,
    ;

    /** True if this path starts, or continues, a ring session: the ringer is behind it. */
    val rings: Boolean get() = this == RING || this == HEADS_UP || this == AUDIO_ONLY

    companion object {
        /**
         * A rung that continues a ring already in progress keeps ringing whatever its own presentation would
         * have been, so only a rung that is not continuing can be one of the silent paths.
         */
        fun choose(
            rung: FiredRung,
            resolution: DeliveryResolution,
        ): DeliveryPath =
            when {
                !rung.continuing && rung.presentation == Presentation.SILENT_NOTICE -> SILENT_NOTICE
                !rung.continuing && rung.policy == RungDelivery.SILENT_NOTIFICATION -> SILENT
                resolution.tier == ResolvedTier.INEXACT -> PLAIN
                resolution.fullScreenIntent -> RING
                resolution.headsUp -> HEADS_UP
                else -> AUDIO_ONLY
            }
    }
}
