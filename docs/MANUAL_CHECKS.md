# Manual checks

This file records what was checked, on what device, on what date, with what result. It exists because six things about the alarm subsystem cannot be proven without real hardware and real time (`IMPLEMENTATION_PLAN.md` Phase 7). Everything else is verified by automated suites — see `IMPLEMENTATION_PLAN.md`'s testing strategy summary.

Do not mark the alarm subsystem complete based on automated coverage alone. Report it as passing all automated layers with device checks outstanding until every row below has a result.

## The six items

| # | Item | Scripted procedure | Status |
|---|---|---|---|
| 1 | One UI's Sleeping apps / Deep sleeping apps behaviour killing alarms after days of low use | Soak script on a Galaxy A15 (Phase 7); read the reliability report after 72+ hours | Not yet run |
| 2 | Real Doze maintenance window cadence over multi-hour spans | A15 soak, real Doze, not `dumpsys deviceidle force-idle` simulation | Not yet run |
| 3 | Audio actually audible at correct volume through the real speaker, over Do Not Disturb | A15 soak + manual listen check | Not yet run |
| 4 | Full screen intent actually turning the screen on from locked, on real hardware | A15 soak, canary telemetry (`screen on` field) cross-checked against a manual observation | Not yet run |
| 5 | Timing drift under real thermal and battery conditions | A15 soak, canary telemetry (`scheduledInstant` vs `actualFiredAt`) | Not yet run |
| 6 | Samsung's scheduled alarm count behaviour, if ever exceeded | Only relevant if the one-alarm-at-a-time design (ADR 0017) is ever violated; not expected to trigger | Not yet run |

## Phase 2 device checks outstanding

Added in Phase 2 as the unverifiable remainders of automated work come up, so that none is lost between phases. Status is "Not yet run" until a row records a device, a date and a result. More rows are added as Phase 2 proceeds.

| # | Item | Why no automated test can settle it | Procedure | Status |
|---|---|---|---|---|
| P2-1 | Force-stop clears the app's alarms and WorkManager jobs | The platform behaviour is read from AOSP source (`JobSchedulerService` cancels and removes the app's jobs on `ACTION_PACKAGE_RESTARTED`, android-13 to android-16), not run. Robolectric does not model it. | On the Galaxy A15: arm a reminder, `adb shell am force-stop com.momtime.android`, `adb shell dumpsys alarm` and `dumpsys jobscheduler` for the package, then confirm nothing fires until the app is launched, and that launching restores the alarm. | Not yet run |
| P2-2 | Whether an OEM "clean" or "optimise" action is a force-stop or a plain kill | OEM behaviour is not in AOSP. A plain kill leaves alarms and jobs, so the watchdog recovers; a force-stop does not. | On the A15 with One UI: run the Device care clean-up and "Put unused apps to sleep" paths, then check `dumpsys alarm` and `dumpsys jobscheduler` for the package, and whether the process exit reason is `REASON_USER_REQUESTED`. | Not yet run |
| P2-3 | Direct boot: a phone that restarts and stays locked across a reminder | Alarms do not survive a reboot, `BOOT_COMPLETED` arrives only after first unlock, and WorkManager is not direct-boot aware, so the reminder cannot ring until unlock. Whether this is a real exposure depends on how often the device restarts overnight and stays locked. Not built in Phase 2 (`IMPLEMENTATION_PLAN.md` Open Items). | Phase 7 soak on the A15 reads the boot count the android store records with each fire and canary, and reports reboots-while-locked across a scheduled reminder. The decision to build direct boot follows from that count. | Not yet run |
| P2-4 | Every statement runs on SQLite 3.22 (API 29) | Robolectric's SQLite is not the device's (ADR 0042), and the JVM tests run on 3.53.4. The SQLDelight 3.18 dialect gate and `verifySqliteFloor` reject known newer constructs at build time, but only a real API 29 image shows the app's statements run there. | The Phase 7 emulator suite on an API 29 managed device runs the data layer, including the v1 to current migration. | Not yet run |
| P2-5 | Exact alarm revocation on a real device | With `USE_EXACT_ALARM` held, which cannot be revoked, revocation is reachable only on API 31 and 32 or in a build without that permission. | Phase 7 needs a debug variant without `USE_EXACT_ALARM` to revoke "Alarms and reminders" on the A15. The variant is not built in Phase 2. | Not yet run |

### Added by `phase-2/android-data-wiring`

| # | Item | Why no automated test can settle it | Procedure | Status |
|---|---|---|---|---|
| P2-6 | The database journal mode on a device | Under Robolectric the effective `journal_mode` is `memory` (asserted by `AndroidDriverConfigurationTest`). The device default is `TRUNCATE` (AOSP `config.xml`, `db_default_journal_mode`), read from source, not run. An OEM overlay or a Google-pushed setting could change it, and ADR 0044 depends on it not being WAL. | On the Galaxy A15, a debug build logs `PRAGMA journal_mode` and `PRAGMA foreign_keys` from the app's own driver, and the result is recorded here. | Not yet run |
| P2-7 | Auto Backup and restore of the database on a device | `BackupRulesTest` reads the XML. It does not show what a device's backup agent copies, whether `momtime.db-journal` is picked up, or that the `.corrupt` copy stays out. Also: a restored app is in the stopped state, so after a restore nothing rings until she opens the app (`ARCHITECTURE.md` section 5.4). | `adb shell bmgr backupnow com.momtime.android`, then `bmgr restore` onto a second device or a wiped one; confirm the database restores, `.corrupt` does not, and record how long after restore the first alarm is armed (after first launch). Also `adb shell dumpsys backup` for the transport and the device-transfer path. | Not yet run |
| P2-8 | Corruption detected in the middle of use, and the `.corrupt` move on a device | The test writes a file that is not a database, which is detected on open. The framework also reports corruption raised during a query; that path is not exercised. The file move is tested on a JVM filesystem, not on the app's private storage. | On a debug build, damage the middle of `momtime.db` on the A15 (`run-as`, then overwrite bytes in a page after the header) and confirm the app opens a fresh database, keeps `momtime.db.corrupt`, and writes the marker. Also corrupt during a running session. | Not yet run |
| P2-9 | The materialisation wait on a device | `MaterialiseRaceTest` shows the wait in the framework's Java connection pool over Robolectric's native SQLite (ADR 0045), not the device's SQLite library, and observes B waiting for 2 seconds, not how the pool behaves over a long hold. | Instrumented test in the Phase 7 emulator suite on API 29 and a current image: two threads, one holding the materialisation transaction, the other waiting; no refusal. | Not yet run |

## Remote CI checks

Not device checks. Recorded here because they verify the real GitHub remote rather than a local Gradle run.

### Phase 0 exit criterion 1: bad-commit PR (2026-09-30)

- **Setup:** PR #2 from `ci-demo/bad-android-import` into `main`, one commit adding `import android.content.Context` at `shared/src/commonMain/kotlin/com/momtime/shared/BadImportDemo.kt:3`. Branch protection on `main` was active.
- **Result, `verify-no-android-imports`:** failed, with `android.*/androidx.* reference found under shared/ (CLAUDE.md invariant 1)` and `src/commonMain/kotlin/com/momtime/shared/BadImportDemo.kt:3: import android.content.Context` (Actions run 36787423284).
- **Other jobs on the same commit:** `android-assemble`, `server-test` and `shared-test` also failed. `android-assemble` and `server-test` failed with "Unresolved reference 'android'" at the same file and line; the `shared-test` failure reason was not read. `detekt`, `ktlint`, `migration-test` and `verify-no-clock-system` passed.
- **Merge state:** the API reported `mergeable_state: blocked` for the PR, both while checks were still queued and after they had failed. Before the ruleset existed, the same branch showed "Able to merge".
- **Control:** under the same ruleset, PR #3 (docs only, all 8 checks green) reported `mergeable_state: clean`, while PR #4 reported `blocked` immediately after creation, with its checks still pending. So the state followed check results, not the PR's existence.
- **Limit of this evidence:** `blocked` is GitHub's rolled-up state and does not name the rule. It shows the merge was not allowed, and the control makes required checks the likely cause, but the API does not say which rule blocked PR #2. The PR was not merged, and no merge was attempted.
- **Cleanup:** PR closed unmerged, branch deleted.
- **Status:** closed.

## Deferred tests

Golden scenarios (`IMPLEMENTATION_PLAN.md`, Phase 1) with a part that needs a component outside `shared`. Each is also a named exit criterion of the owning phase, so it cannot disappear from this table alone. The part whose subject is shared code is tested in `shared` now. The race test against `AndroidSqliteDriver` is a Phase 2 exit criterion and is not tracked here.

| Scenario | Owning phase | Non-shared component it needs | Shared part, and its tests | Status |
|---|---|---|---|---|
| 3. Exact alarm permission revoked mid schedule: tier downgrade and ladder adjustment | Phase 2 (Robolectric) | Android: capability resolution and the tier downgrade in `android` | `DeliveryCapability` appears nowhere in the escalation engine or the rung types, by structural scan with a positive control (`CapabilityBoundaryTest`); remaining rungs after a restart are the tail of the ladder (`NextRungResolverTest`) | Android part not yet implemented |
| 7. Caregiver revocation arriving mid sweep | Phase 4 (server suite) | Backend: the sweep, `sweep_status` and the server mirror | Revocation hard deletes the link and only that link (`RemainingRepositoriesTest`: `caregiver link round trips and revocation hard deletes it`, `revoking one caregiver link deletes only that link`) | Server part not yet implemented |
| 14. Device clock set backward while a ladder is armed | Phase 2 (Robolectric) | Android: `AlarmManager` arming arithmetic and `ACTION_TIME_CHANGED` | A ladder is a pure function of the scheduled instant: absolute instants, never before the one it follows, shifted exactly with the scheduled instant (`EscalationLadderTest`: `rungs are ordered, never before the scheduled instant, and shift exactly with it`); resuming takes no clock input (`NextRungResolverTest`) | Android part not yet implemented |
| 17. The watchdog finds the correct alarm already armed: a no-op pass | Phase 2 (Robolectric) | Android: `WorkManager` and the watchdog pass | Reconciling an unchanged state emits nothing: before grace, a terminal event present, a snoozed occurrence before expiry (`ReconcileTest`); the resolver is a pure function of the ladders it is given (`NextRungResolverTest`) | Android part not yet implemented |

## Honesty constraint on claims

With one Samsung device (Galaxy A15) plus Firebase Test Lab's clean-state fleet, the defensible claim after Phase 7 is delivery measured on a specific device family under specific conditions — not validation across hostile OEM skins. No MIUI or ColorOS device will have been tested in its default aggressive configuration unless one is separately acquired. The A15 with One UI is moderately aggressive, not worst case.
