package com.momtime.android.arming

/**
 * What every `Reconcile` android dispatches is followed by (ADR 0090 item 7): the ring session drops every
 * occurrence that is no longer open, cancels its notification, and ends the ring if none is left. The fire path, the
 * watchdog pass, a process start (which the system pass is) and the zone change pass each call it after their
 * dispatch; the template edit pass will (PR 4). It is a seam so that the arming package, which dispatches
 * `Reconcile`, need not name the ring session or anything in delivery.
 */
internal fun interface SessionSweep {
    fun dropClosed()
}
