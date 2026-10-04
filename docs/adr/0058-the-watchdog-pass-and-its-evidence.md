# 0058. The watchdog pass and the evidence that an alarm was lost

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in decision 6 of the Phase 2 progress file and the PR 4 prompt; implemented in `phase-2/workers`. The tolerances are the implementing session's proposals, accepted in the review as proposals that P2-14 will measure; the version code evidence was decided in that review. It builds on ADR 0017 (one alarm and a watchdog), ADR 0053 (one entry point), ADR 0056 (catch up) and ADR 0057 (the work).

## Decision

**A pass,** run by the 15 minute job (`Watchdog.run`, exclusive of the fire path):

1. Dispatch `Reconcile` (`ReconcileCommand`, in the domain layer), so an occurrence whose grace expired while the app was closed is derived to `MISSED` even if no alarm will ever fire for it. Open occurrences, `PENDING` and `SNOOZED`, are read, so a snoozed occurrence is reconciled too.
2. Resolve capability, then read the evidence below against the rung the domain expects.
3. Write `WATCHDOG_REPAIR`, once, only if there is positive evidence that the armed alarm was lost. With no expected rung there is nothing to have lost and no event.
4. Call `ensureArmed`, which is idempotent: on a correct state it replaces the alarm with identical parameters, rewrites the armed record and writes nothing (golden scenario 17).

The worker never starts the ringer and never fails the periodic work over a transient error. An overdue rung is armed for now and the fire path decides how it is presented (ADR 0056).

**The evidence** (`WatchdogEvidence`, a pure function over what the pass saw), each positive on its own:

| Evidence | The test |
|---|---|
| `RECORD_MISSING` | the android store has no armed record although a rung is expected |
| `RECORD_MISMATCH` | the record is for another slot or another rung than the one expected |
| `ALARM_ABSENT` | the `PendingIntent` probe (`FLAG_NO_CREATE`) for the expected slot finds nothing |
| `BOOT_COUNT_CHANGED` | `Settings.Global.BOOT_COUNT` now differs from the one recorded at arm, when the platform reported both |
| `EXACT_CAPABILITY_CHANGED` | exact capability now differs from what it was at arm, in either direction |
| `APP_UPDATED` | the app's version code differs from the one recorded when the alarm was armed |
| `RUNG_OVERDUE` | the expected rung is overdue by more than the tolerance for the mechanism that was armed |

The expected rung is the first one that has not fired (selection reads the record of fired rungs), so an overdue rung has no `ALARM_FIRED` by construction, which is the "no ALARM_FIRED" condition of the review.

**Tolerances,** because a Tier 1 inexact alarm may legitimately run late:

- An exact alarm (`setAlarmClock`): **2 minutes.** It fires within seconds, and the margin covers the fire path itself (the receiver writes `ALARM_FIRED` a moment after the alarm) and a watchdog that starts as an alarm fires.
- An inexact alarm (`setAndAllowWhileIdle`, Tier 1): **15 minutes.** In Doze it is deferred to a maintenance window and limited to about one fire per app per nine minutes; 15 minutes is also the watchdog's own period, so a late alarm gets a full cycle before it is called lost.
- What was armed decides (`armed.exactAllowed`), not what is allowed now. With no record the capability now stands in, and the missing record is evidence already.
- A rung overdue beyond the 30 minute catch up window is armed for now like any other and presented by the fire path as a silent notice (ADR 0056), so it is evidence like any other overdue rung.

**Reboot** is read from the boot count through the injected seam `BootCount`, never from uptime. Comparing `elapsedRealtime` now with `elapsedRealtime` at arm misses every restart after which the device has been up longer than it had been when the alarm was armed, which is the ordinary case. A test models that case (uptime one hour at arm, five hours after the restart) and asserts the restart is found. A count the platform does not report, at either end, is no evidence.

**"App version changed"** is read from the version code. The armed record carries `version_code`, the app's `PackageInfo.longVersionCode` when the alarm was armed, and the pass compares it with the version code now, through the seam `AppVersion`. An update replaces the package and clears its alarms, and a different version code says so without depending on the wall clock. (The first form compared `PackageInfo.lastUpdateTime` with the record's `armedAt`, which fails when the clock is set back after an update; the review replaced it.) A downgrade is a different version code too. A version the platform did not report, at either end, is no evidence, as with the boot count. The new column is a migration of the android store, version 1 to 2 (`migrations/1.sqm`, `ALTER TABLE armed_alarm ADD COLUMN version_code INTEGER NOT NULL DEFAULT 0`), with the committed baseline snapshot as its starting point and a forward migration test. A record from before the column reads as version 0, which is never a real version code, so the first pass after the update finds the version different, which is right: the update that brought the migration replaced the package.

**Exclusivity.** The watchdog and the fire path run inside `ArmingCoordinator.exclusive`. A fire writes `ALARM_FIRED` and then arms the next rung; a watchdog pass that read the state between the two would see a record that does not match the expected rung and write a repair for an alarm that was not lost. The cost is that a fire waits for a watchdog pass that is already running, which is a few queries.

**The app start path** (`AppStart`) dispatches `Reconcile` and calls `ensureArmed` and writes no repair: a process start is not evidence of loss. After an Auto Backup restore it is the first code that runs and it arms the restored schedule; a restored database's slot counter goes on from where it was (`RestoreTest`).

## Alternatives considered

- **Always write `WATCHDOG_REPAIR` when the pass re arms.** Rejected: it would write an event every 15 minutes, which is what golden scenario 17 forbids and what makes the repair count meaningless as a reliability signal.
- **Detect loss by comparing the platform's alarm list with the record** (`dumpsys` style). Not available to an app, and `PendingIntent` with `FLAG_NO_CREATE` is the supported probe.
- **Compare `lastUpdateTime` with `armedAt`, with no store column.** The first form. Rejected by the review: it depends on the wall clock, and the store's migration discipline exists for a change like this.
- **Detect a restart from uptime.** Rejected, with a test that fails it (above).
- **One tolerance for both mechanisms.** Rejected: a tolerance short enough to catch a lost exact alarm fires on every Doze deferral of a Tier 1 one.

## Limits

The probe, the boot count and the app version are read through seams with real defaults that have their own tests; what a device reports for them is `MANUAL_CHECKS.md` P2-13. The tolerances are proposals measured against nothing yet: the Phase 7 soak reads actual lateness of both mechanisms (P2-14) and may move them.
