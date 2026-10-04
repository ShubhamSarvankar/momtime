# 0057. WorkManager: what it adds, how it starts, and the work

Date: 2026-10-03
Status: Accepted

Decided in the PR 4 prompt by Claude (technical review) for the approved dependency (`androidx.work:work-runtime` 2.12.0, `work-testing` 2.12.0 for tests); the initialiser choice and the work design are the implementing session's judgment, recorded here for review. It closes the Phase 2 follow up to read WorkManager's manifest from the artifact that ships, and it is the PR 4 half of ADR 0046 (one process).

## What the 2.12.0 artifact adds

Read from `AndroidManifest.xml` inside `work-runtime-2.12.0.aar` in the Gradle cache, and from the merged manifest of the app, which is what ships.

**Permissions** (four from WorkManager, one from `androidx.core`, which arrives through it): `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, and `com.momtime.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (a signature level permission `androidx.core` declares and requests for itself). Each is in `verifyManifestPermissions`'s allowlist with its reason, because every permission changes the Play declarations. Three of them the app does not use through WorkManager: `ACCESS_NETWORK_STATE` is for network constraints, `FOREGROUND_SERVICE` is for expedited and long running jobs, and `RECEIVE_BOOT_COMPLETED` is for a reschedule receiver that is disabled by default. The ringer (PR 5) and boot handling (PR 6) need the last two themselves, so removing them with `tools:node="remove"` would be undone within two PRs. `ACCESS_NETWORK_STATE` could be removed; it was left because WorkManager's network tracker reads it and removing a permission a library expects is a risk for no benefit.

**Components:** `androidx.startup.InitializationProvider` (meta-data for `WorkManagerInitializer` and `ProfileInstallerInitializer`, not exported), `SystemJobService` (exported, bound by `BIND_JOB_SERVICE`), `SystemForegroundService` (not exported; it declares no `android:foregroundServiceType`, in the AAR or in either merged manifest, so it commits the app to no foreground service type for Play, and the app never calls `setForeground`, which is where a type would come from; it is left in place), `ForceStopRunnable$BroadcastReceiver` (not exported), `RescheduleReceiver` (disabled, with a `BOOT_COMPLETED` filter), `DiagnosticsReceiver` (exported, guarded by the `DUMP` permission), `androidx.room.MultiInstanceInvalidationService` (not exported, from Room, which WorkManager's own database uses), and `ProfileInstallReceiver` (exported, guarded by `DUMP`).

**No component declares `android:process`** (counted in the AAR and in both merged manifests), so ADR 0046 holds and `verifySingleProcess` passes.

**Direct boot:** every WorkManager component sets `android:directBootAware="false"`, except Room's `MultiInstanceInvalidationService` (`true`), which is not what runs jobs. So WorkManager does nothing between a restart and the first unlock. This is read from the 2.12.0 artifact, not `androidx-main`, and replaces the evidence the earlier notes held. It changes nothing in Phase 2 (direct boot is not built, `IMPLEMENTATION_PLAN.md` Open Items) and it is the reason a locked restart is a real exposure (`MANUAL_CHECKS.md` P2-3).

**An alarm of its own below API 30.** On API 29 WorkManager arms one `AlarmManager` alarm through `ForceStopRunnable` to notice a force stop. `WorkTest` observes it: exactly one alarm that is not ours on SDK 29, none from SDK 30. It is not the alarm path's, so it is not counted against "one alarm armed at a time" (ADR 0017); the alarm oracle in the tests counts only alarms whose operation names `AlarmReceiver`. It adds one to the total the platform holds for the app, which matters for the Samsung count only if that cap is ever near (`MANUAL_CHECKS.md` row 6).

## Initialisation: the default initialiser

WorkManager starts with its default `androidx.startup` initialiser, before `Application.onCreate`. The alternative, on demand initialisation, removes the initialiser from the manifest and makes the `Application` a `Configuration.Provider` with a `WorkerFactory`, so workers can be constructed with their dependencies.

The default is chosen because:

- Workers do not need constructor injection. A worker finds its pass through `WorkEntryPoint`, set when the application starts, exactly as the alarm receiver finds the fire path through `ArmingEntryPoint`. The default initialiser runs before that, but a worker runs only after the process has started, and `Application.onCreate` has run by then.
- On demand initialisation puts a manifest edit (`tools:node="remove"` on the initialiser's meta-data) between the app and a silently uninitialised WorkManager. A mistake there stops every job, and the failure is a crash at the first `WorkManager.getInstance`, found only by running it. The default has no such step.
- Robolectric does not run the default initialiser, so tests start the test `WorkManager` themselves; `MomTimeApplication.startWork()` is open for that and is the only seam.

The cost: WorkManager's database opens at every process start, including a process an alarm woke. That is milliseconds against a 60 second budget, and it is a number the Phase 7 soak can show. If it matters, on demand initialisation is a manifest and `Application` change that touches no worker.

## The work

- **Watchdog:** unique periodic work `momtime.watchdog` at the 15 minute floor, `ExistingPeriodicWorkPolicy.KEEP`. Enqueued at every start. KEEP leaves an existing job alone, so a process that restarts more often than every 15 minutes does not reset the timer and starve the watchdog; REPLACE would. A periodic job runs once when it is first enqueued.
- **Daily materialisation:** unique periodic work `momtime.materialise.daily` at 24 hours, KEEP. Each run materialises 48 hours ahead and then calls `ensureArmed`, because a new occurrence can be earlier than the rung that is armed.
- **On demand:** `Work.enqueueMaterialisation` is unique one time work, `ExistingWorkPolicy.APPEND_OR_REPLACE`, for the template edit path in Phase 3 to call. A run already going when an edit lands may have read the old template, so a new run is appended after it; REPLACE would cancel the one in progress and KEEP would drop the new one. A run that failed or was cancelled is replaced. `MaterialisationQueueTest` shows it with a real thread pool and the first run held on a latch: the second request waits for the first, `BLOCKED`, and runs after it; KEEP would drop it and REPLACE would cancel the first run, and each fails that test.
- **Unique work is an efficiency measure, not a correctness mechanism.** The transaction in `materialiseWindow` is what stops overlapping runs from leaving a duplicate (ADR 0036). A test shows a second run adds nothing.
- **Workers are plain `Worker`s,** not `CoroutineWorker`s, so no coroutines dependency arrives. They catch `RuntimeException`, log its class and nothing else (invariant 11), and return `Result.retry()`, so a failed pass is tried again after WorkManager's backoff instead of waiting a whole period. A failed result would not end a periodic job in 2.12 either: `WorkTest` shows the job stays enqueued with no retry, so retry is chosen for the earlier second attempt and not for the job's survival. Errors that are not exceptions in production (a store failure is counted, ADR 0054) are `Error`s in tests and fail the suite.
- **No constraints:** no network, battery or charging constraint. A constraint is a condition under which the watchdog may not run.

## Alternatives considered

- **On demand initialisation** (above). Not chosen, and reversible.
- **`WorkManager` for the alarm itself.** Rejected long ago (ADR 0017): a job is not exact.
- **Expedited work for the watchdog.** Rejected: it needs `FOREGROUND_SERVICE` machinery on older releases and is quota limited, and nothing in the watchdog is urgent at the second.
- **Removing the permissions the app does not use through WorkManager.** Discussed above.

## Limits

Robolectric's `TestDriver` says the period has elapsed and runs the worker. It shows the worker, the entry point and the pass, not when a real device runs the job in Doze (`MANUAL_CHECKS.md` P2-13), and not that `JobScheduler` keeps the job across a process kill (read from AOSP, `ARCHITECTURE.md` section 5.3).
