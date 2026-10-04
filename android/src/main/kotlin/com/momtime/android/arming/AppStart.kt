package com.momtime.android.arming

import com.momtime.shared.data.ReconcileCommand

/**
 * What runs when the app's process starts, whatever started it (ARCHITECTURE.md section 5.4). A restored app
 * is in the stopped state and receives no broadcast, so after an Auto Backup restore nothing is armed until
 * the app next runs, and this is the first code that does. The armed record lives in the android store, which
 * is not backed up, so a restored phone has none and the pass arms from the restored occurrences.
 *
 * It dispatches `Reconcile` and then calls `ensureArmed`: the same two steps the watchdog begins and ends
 * with, without the evidence, because a process start is not evidence that anything was lost and writes no
 * `WATCHDOG_REPAIR`. On a correct state it re arms with identical parameters and writes nothing.
 */
class AppStart internal constructor(
    private val reconcile: ReconcileCommand,
    private val coordinator: ArmingCoordinator,
    private val log: AlarmLog,
) {
    fun run(): EnsureResult =
        coordinator.exclusive {
            reconcile.dispatch(log.now())
            coordinator.ensureArmed()
        }
}
