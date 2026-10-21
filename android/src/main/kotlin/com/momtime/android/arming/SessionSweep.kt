package com.momtime.android.arming

import com.momtime.shared.domain.Occurrence

/**
 * What every `Reconcile` android dispatches is followed by (ADR 0090 item 7): the ring session drops every
 * occurrence that is no longer open, cancels its notification, and ends the ring if none is left. The fire path, the
 * watchdog pass, a process start (which the system pass is) and the zone change pass each call it after their
 * dispatch; the template edit pass calls [withdrawn] (ADR 0079 item 8). It is a seam so that the arming package,
 * which dispatches `Reconcile`, need not name the ring session or anything in delivery.
 */
internal fun interface SessionSweep {
    fun dropClosed()

    /**
     * What a template edit withdrew: everything [dropClosed] does, and the notification of each withdrawn
     * occurrence, which [dropClosed] cannot reach when the occurrence is not in the ring session (a plain
     * notification at Tier 1, a Gentle one, a silent notice). A withdrawn occurrence is terminal, so
     * [dropClosed] already finds it.
     */
    fun withdrawn(occurrences: List<Occurrence>) = dropClosed()
}
