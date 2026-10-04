# 0058. The watchdog pass and the evidence that an alarm was lost

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in decision 6 of the Phase 2 progress file and the PR 4 prompt; implemented in `phase-2/workers`. The tolerances and the reading of "app version" are the implementing session's proposals, recorded here for review. It builds on ADR 0017 (one alarm and a watchdog), ADR 0053 (one entry point), ADR 0056 (catch up) and ADR 0057 (the work).

## Decision

**A pass,** run by the 15 minute job (`Watchdog.run`, exclusive of the fire path):

1. Dispatch `Reconcile` (`ReconcileCommand`, in the domain layer), so an occurrence whose grace expired while the app was closed is derived to `MISSED` even if no alarm will ever fire for it. Open occurrences, `PENDING` and `SNOOZED`, are read, so a snoozed occurrence is reconciled too.
2. Resolve capability, then read the evidence below against the rung the domain expects.
3. Write `WATCHDOG_REPAIR`, once, only if there is positive evidence that the armed alarm was lost. With no expected rung there is nothing to have lost and no event.
4. Call `ensureArmed`, which is idempotent: on a correct state it replaces the alarm with identical parameters, rewrites the armed record and writes nothing (golden scenario 17).

The worker never starts the ringer and never fails the periodic work over a transient error. An overdue rung is armed for now and the alarm path rings it (ADR 0056).

**The evidence** (`WatchdogEvidence`, a pure function over what the pass saw), each positive on its own:

| Evidence | The test |
|---|---|
| `RECORD_MISSING` | the android store has no armed record although a rung is expected |
| `RECORD_MISMATCH` | the record is for another slot or another rung than the one expected |
| `ALARM_ABSENT` | the `PendingIntent` probe (`FLAG_NO_CREATE`) for the expected slot finds nothing |
| `BOOT_COUNT_CHANGED` | `Settings.Global.BOOT_COUNT` now differs from the one recorded at arm, when the platform reported both |
| `EXACT_CAPABILITY_CHANGED` | exact capability now differs from what it was at arm, in either direction |
| `APP_UPDATED` | the package was installed or updated after the record's `armedAt` |
| `RUNG_OVERDUE` | the expected rung is overdue by more than the tolerance for the mechanism that was armed |

The expected rung is the first one that has not fired (selection reads the record of fired rungs), so an overdue rung has no `ALARM_FIRED` by construction, which is the "no ALARM_FIRED" condition of the review.

**Tolerances,** because a Tier 1 inexact alarm may legitimately run late:

- An exact alarm (`setAlarmClock`): **2 minutes.** It fires within seconds, and the margin covers the fire path itself (the receiver writes `ALARM_FIRED` a moment after the alarm) and a watchdog that starts as an alarm fires.
- An inexact alarm (`setAndAllowWhileIdle`, Tier 1): **15 minutes.** In Doze it is deferred to a maintenance window and limited to about one fire per app per nine minutes; 15 minutes is also the watchdog's own period, so a late alarm gets a full cycle before it is called lost.
- What was armed decides (`armed.exactAllowed`), not what is allowed now. With no record the capability now stands in, and the missing record is evidence already.
- Both are inside the 30 minute catch up window, which a test asserts, so an overdue rung that is evidence is also a rung that can still be rung. Beyond the window the rung is not offered at all.

**Reboot** is read from the boot count through the injected seam `BootCount`, never from uptime. Comparing `elapsedRealtime` now with `elapsedRealtime` at arm misses every restart after which the device has been up longer than it had been when the alarm was armed, which is the ordinary case. A test models that case (uptime one hour at arm, five hours after the restart) and asserts the restart is found. A count the platform does not report, at either end, is no evidence.

**"App version changed"** is read as `PackageInfo.lastUpdateTime` later than the record's `armedAt`, through the seam `AppUpdate`. The armed record has no version field, and adding one is a migration of the android store for a signal that `lastUpdateTime` carries without it (an update replaces the package and clears its alarms; after the update the pass re arms and `armedAt` moves past the update time, so the signal does not repeat). It also catches a reinstall of the same version. It depends on the wall clock, so a clock set far backward after an update could hide it, and one set forward after arming could fire it once; both cost one extra re arm and at worst one extra `WATCHDOG_REPAIR`.

**Exclusivity.** The watchdog and the fire path run inside `ArmingCoordinator.exclusive`. A fire writes `ALARM_FIRED` and then arms the next rung; a watchdog pass that read the state between the two would see a record that does not match the expected rung and write a repair for an alarm that was not lost. The cost is that a fire waits for a watchdog pass that is already running, which is a few queries.

**The app start path** (`AppStart`) dispatches `Reconcile` and calls `ensureArmed` and writes no repair: a process start is not evidence of loss. After an Auto Backup restore it is the first code that runs and it arms the restored schedule; a restored database's slot counter goes on from where it was (`RestoreTest`).

## Alternatives considered

- **Always write `WATCHDOG_REPAIR` when the pass re arms.** Rejected: it would write an event every 15 minutes, which is what golden scenario 17 forbids and what makes the repair count meaningless as a reliability signal.
- **Detect loss by comparing the platform's alarm list with the record** (`dumpsys` style). Not available to an app, and `PendingIntent` with `FLAG_NO_CREATE` is the supported probe.
- **A store column for the app version.** Rejected for now (above); revisit if `lastUpdateTime` proves unreliable on a device.
- **Detect a restart from uptime.** Rejected, with a test that fails it (above).
- **One tolerance for both mechanisms.** Rejected: a tolerance short enough to catch a lost exact alarm fires on every Doze deferral of a Tier 1 one.

## Limits

The probe, the boot count and the update time are read through seams with real defaults that have their own tests; what a device reports for them is `MANUAL_CHECKS.md` P2-13. The tolerances are proposals measured against nothing yet: the Phase 7 soak reads actual lateness of both mechanisms (P2-14) and may move them.
