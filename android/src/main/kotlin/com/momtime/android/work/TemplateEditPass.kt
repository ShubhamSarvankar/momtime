package com.momtime.android.work

import com.momtime.android.arming.AlarmLog
import com.momtime.android.arming.ArmingCoordinator
import com.momtime.android.arming.SessionSweep
import com.momtime.shared.data.EditResult
import com.momtime.shared.data.EditTemplateCommand
import com.momtime.shared.data.MaterialiseCommand
import com.momtime.shared.data.ReconcileCommand
import com.momtime.shared.domain.ScheduleTemplate

/**
 * The pass a template edit is answered with (ADR 0079 item 8), run inside the coordinator's exclusion as
 * [TimeZonePass] is, so a fire or a watchdog pass never reads the half done state and the alarm is correct when the
 * edit returns:
 *
 * 1. the edit command ([EditTemplateCommand], one transaction): the template, the occurrences it withdraws and the
 *    ones that take the new time or criticality;
 * 2. the window is materialised, so a date the edit now wants has its occurrence (a reactivation, a wider
 *    recurrence) and a date it no longer wants has none;
 * 3. `Reconcile`: an occurrence that has come due and was never answered is judged against its own, unmoved grace;
 * 4. the ring session and the notifications drop what was withdrawn ([SessionSweep.withdrawn]): each from the
 *    session, the session's notification and the sound when nothing is left, and each one's own notification;
 * 5. `ensureArmed`, always (also when the edit was refused or threw, as the fire path does): a chain that stops at
 *    one edit would be silently dead until the watchdog found it. It arms the earliest rung that has not fired, so a
 *    moved occurrence's alarm is re armed at its new instant (same slot, a new `ALARM_SCHEDULED`) and a withdrawn
 *    occurrence's alarm is cancelled by its slot.
 *
 * Nothing here changes an occurrence's state: the edit command does, through the domain (invariant 3). The time is
 * the log's clock, never read here (invariant 8). It is called by the editor of PR 11; until then by tests.
 */
@Suppress("LongParameterList")
class TemplateEditPass internal constructor(
    private val edit: EditTemplateCommand,
    private val materialise: MaterialiseCommand,
    private val reconcile: ReconcileCommand,
    private val coordinator: ArmingCoordinator,
    private val log: AlarmLog,
    private val sweep: SessionSweep,
) {
    /** Dispatches [edited] and answers it. Returns what the command did. */
    fun run(edited: ScheduleTemplate): EditResult =
        coordinator.exclusive {
            try {
                val result = edit.dispatch(edited, log.now())
                if (result is EditResult.Edited) {
                    materialise.dispatch(log.now())
                    reconcile.dispatch(log.now())
                    sweep.withdrawn(result.withdrawn)
                }
                result
            } finally {
                coordinator.ensureArmed()
            }
        }
}
