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

Golden scenarios (`IMPLEMENTATION_PLAN.md`, Phase 1) that have no honest shared-layer test, because the mechanism under test does not exist in `shared`. Each is owned by a later phase, and that phase's exit criteria require it to be implemented. Nothing here is covered by a stand-in test in `shared`.

| Scenario | Owning phase | Reason it is not tested in `shared` | Status |
|---|---|---|---|
| 3. Exact alarm permission revoked mid schedule: tier downgrade and ladder adjustment. **Partly deferred.** The shared slice (ladder generation takes no `DeliveryCapability`) is tested in `shared`; the downgrade itself is not. | Phase 2 (Robolectric suite) | Capability resolution and tier downgrade live in `android`, not `shared`. | Not yet implemented |
| 7. Caregiver revocation arriving mid sweep | Phase 4 (server suite) | The sweep, `sweep_status` and caregiver links' server mirror exist only in the server. `shared` has no sweep to interleave with. | Not yet implemented |
| 14. Device clock set backward while a ladder is armed: no re-fire of fired rungs, no negative delay on the next rung | Phase 2 (Robolectric suite) | `shared` computes only absolute instants and never a from-now delay, and does not handle `ACTION_TIME_CHANGED`. The risk is in the `AlarmManager` arming arithmetic in `android`. | Not yet implemented |
| 17. `WorkManager` watchdog finds the correct alarm already armed: no-op pass, no spurious `WATCHDOG_REPAIR` event | Phase 2 (Robolectric suite) | `WorkManager` and the watchdog pass do not exist in `shared`. The resume logic the watchdog calls (`NextRungResolver`) is tested in `shared` under scenarios 2, 15 and 18, but not the no-op pass itself. | Not yet implemented |

## Honesty constraint on claims

With one Samsung device (Galaxy A15) plus Firebase Test Lab's clean-state fleet, the defensible claim after Phase 7 is delivery measured on a specific device family under specific conditions — not validation across hostile OEM skins. No MIUI or ColorOS device will have been tested in its default aggressive configuration unless one is separately acquired. The A15 with One UI is moderately aggressive, not worst case.
