# MomTime Implementation Plan

Read `ARCHITECTURE.md` for the technical detail behind every item here. Read `CLAUDE.md` before writing any code.

Scope: Android only. Backend included. iOS deliberately excluded and deliberately unblocked.

---

## Sequencing rationale

Phases are ordered by two competing pressures, resolved as follows.

**Verifiability.** The shared module and the server can be fully verified by running tests, with no device. Those phases can be built and confirmed autonomously.

**Risk.** The alarm subsystem is where the product succeeds or fails, so it cannot be left to the end.

These are resolvable because the Robolectric layer (`ShadowAlarmManager`) makes most of the alarm subsystem verifiable on the JVM. So the alarm subsystem lands early, at Phase 2, with its device gate deferred to Phase 7. That is the specific reason Phase 2 sits where it does.

Phase 0 must complete before anything else. Phases 5 and 6 may run in either order. The Play 12 tester clock starts at the end of Phase 3 and runs in parallel with everything after it.

---

## Open items

Outstanding blockers that don't belong to any single code change, tracked here so they aren't lost between sessions.

| Item | Needed at | Owner | Status |
|---|---|---|---|
| GCP project id | Phase 4, first Terraform apply | User | Not yet provided. `infra/` stays `.gitkeep`-only until then; Terraform reads `TF_VAR_gcp_project_id` as a required variable with no default. Do not ask before Phase 4 actually needs it. |
| Firebase project id | Phase 4, Auth/FCM wiring (Phase 2/3 work that touches Firebase at runtime must be structured to build without it, or stop and ask) | User | Not yet provided. `google-services.json` stays gitignored and absent. Terraform reads `TF_VAR_firebase_project_id` as a required variable with no default; docs and Terraform allow it to differ from the GCP project id even though one project is expected to serve both. |
| Exact dependency versions (Kotlin, AGP, Gradle, SQLDelight, Koin, kotlinx-datetime, Detekt, ktlint, Ktor, Robolectric, Roborazzi) | Phase 0 scaffolding | Resolved live against Maven Central and the current AGP/Kotlin compatibility matrix, not guessed | Done. Pinned in `gradle/libs.versions.toml`. Kotlin 2.4.20 / AGP 9.4.1 / Gradle 9.8.0 verified with a real build, not just metadata. |
| Migration-test CI job | Phase 1, as an explicit exit criterion (not discovered later) — see Phase 1 below | Model | Done. Wired as its own named CI job (`migration-test`, `.github/workflows/ci.yml`) running `:shared:verifySqlDelightMigration`. Proven locally: passes at v1 with no migrations, fails on a deliberately mismatched migration with an exact column-level diff, passes again once corrected. |
| Green GitHub Actions run + bad-commit PR check on the real remote | Phase 0, before the phase is considered fully closed | Shubham (repo creation, push, and the PR-based bad-commit demonstration; a local Gradle run proves the task works, not that the workflow YAML is correct on the actual remote) | Green run confirmed: CI run 36769065695 on 28c0864, all 8 jobs succeeded (read from the Actions API). Bad-commit demonstration done as PR #2 (closed unmerged, branch deleted): `verify-no-android-imports` failed naming file, line and invariant 1, and the PR's merge state was `blocked`. **Closed.** See `MANUAL_CHECKS.md`. |
| `kotlinx-kover` dependency (coverage gate) | Phase 1 | Model, flagged rather than blocked on, per the explicit "no stopping again" instruction for this phase; invariant 7 still requires disclosure | Added. The only reasonable choice for Kotlin/KMP line coverage, official JetBrains plugin. Line gate at 90% via `koverVerify`; per-package branch gates (engine 95%, domain 85%, data 85%) via `verifyBranchCoverage`, both part of `:shared:check` (ADR 0038). |
| `EventRepository.deleteById` against child rows | Phase 4, before the retention, revocation and erasure code is written | Claude (technical review) | Open. With foreign keys enforced (ADR 0043), deleting an event that still has an `outbox_event` row fails, because no foreign key has an `ON DELETE` clause (the `alarm_delivery_telemetry` table that this row used to name was dropped in schema version 3, ADR 0048; the android store holds no foreign key into the shared database, so it needs its own deletion path, Phase 8). Phase 4 decides whether the hard-delete paths remove child rows first or the schema cascades. Left alone in Phase 2 on purpose: a cascade chosen early encodes a guess about erasure. |
| Play eligibility of `USE_EXACT_ALARM` | Phase 8 (the Play declaration) | Shubham | Open. Play restricts `USE_EXACT_ALARM` to alarm clock and calendar apps, and whether a medication reminder qualifies is not settled. Fallback, written in advance: drop `USE_EXACT_ALARM` and ship `SCHEDULE_EXACT_ALARM` without its `maxSdkVersion`. The fix touches the manifest only, because the code resolves capability from `canScheduleExactAlarms()` and never from which permission is held, and the `SCHEDULE_EXACT_ALARM`-only configuration is a tested path from Phase 2. |
| `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` Play eligibility | PR 8 (the permission request flow), and the Phase 8 Play declaration | Shubham | Open. The request intent falls under its own Play policy, just as `USE_EXACT_ALARM` does, and whether a medication reminder qualifies is not settled. Fallback, written in advance: send her to the general battery optimisation settings screen (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), which needs no permission, with a plain instruction of what to choose. The manifest does not declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and `verifyManifestPermissions` fails if it appears: PR 8 adds it to the allowlist on purpose, or uses the fallback. **PR 8 uses the fallback** (Claude (technical review), ADR 0072): the battery flow is the general settings screen, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` stays undeclared, and the direct request waits on this row. |
| Direct boot (`LOCKED_BOOT_COMPLETED`) | Phase 8, with the decision made from Phase 7 evidence | Claude | Open, not built and not deferred to Phase 9. Alarms do not survive a reboot, `BOOT_COMPLETED` arrives only after first unlock, and WorkManager cannot run before unlock, so a phone that restarts at 3 AM and stays locked rings nothing at 7 AM. One UI can restart a phone overnight on a schedule, so this is the case the product exists for. The android store records the boot count with each fire and canary; Phase 7 measures how often the Galaxy A15 reboots and stays locked across a reminder; the decision follows from that evidence. No ADR yet, since nothing is closed. See `MANUAL_CHECKS.md`. |
| WorkManager unique work for materialisation | Phase 2 | Model | Done in PR 4 (ADR 0057). Optimisation only, not a correctness mechanism. Avoids redundant daily and edit-triggered runs. Materialisation is atomic in the repository (ADR 0036), so overlapping runs are already safe. `Work.enqueueMaterialisation` is the function the template edit path (Phase 3) calls. |
| The `source` of server written events (`CAREGIVER_NOTIFIED`) | Phase 4, with the server schema, before any server event is written | Claude (technical review) | Open. ADR 0059 settles that `CAREGIVER` is not an event source (caregivers never author events, ADR 0025), so `source` is `USER` or `SYSTEM`. Whether an event the server writes to the device's stream needs a source of its own, or is `SYSTEM`, is decided in Phase 4: the server is a different system from the device, and the caregiver view or a report may want to tell them apart. |
| Which phase owns missions | Before any mission is built | Claude (technical review) | **Closed: Phase 5 owns missions, and the approval of ML Kit's bundled barcode model goes with Phase 5** (ADR 0088; recommended by the Phase 3 planning session, decided by Claude (technical review) in its review). Phase 3 builds none: the schedule builder writes `MissionConfig.None` and shows no mission control. Phase 5 carries one rule (its section below): a verified or bypassed completion also writes `COMPLETED`. |
| Required checks on `main` | Phase 2 close | Shubham | **Closed.** All 10 CI jobs are required checks on `main`, confirmed by Shubham (recorded by the Phase 3 planning session from his statement, 2026-10-05): `shared-test`, `verify-no-android-imports`, `verify-no-clock-system`, `migration-test`, `server-test`, `android-assemble`, `detekt`, `ktlint`, `android-unit-test` and `verify-android-structure`. Phase 3 adds an eleventh job, `android-screenshots` (`docs/phase-3-plan.md`, PR 7), which Shubham adds to the required checks when that pull request merges; until then green still means every check on the pull request head. |
| `android-screenshots` as a required check on `main` | When PR 7 of Phase 3 merges | Shubham | Open. PR 7 adds an eleventh CI job, `android-screenshots`, which verifies the committed screenshot goldens and uploads what the commit renders (`docs/phase-3-plan.md`, PR 7; ADR 0077). It becomes a required check on `main` when that pull request merges. Until Shubham confirms it, green means every check on the pull request head, this one included. |
| Final alarm sounds | Phase 7, before anyone relies on the app | Shubham | Open. Phase 2 ships placeholder sounds generated by `scripts/sounds/GenerateSounds.java` (ADR 0061): deterministic tones with no author or licence to credit, a primary and a louder backup. Shubham chooses the final sounds; each is a `.wav` under 30 seconds, loopable, with its provenance and licence recorded in the commit. The ramp, the backup interval and the vibration patterns are placeholders too, for a device to judge (`MANUAL_CHECKS.md` P2-20, P2-21). |
| Samsung walkthrough screenshots | Before Phase 3's onboarding ships, and for `MANUAL_CHECKS.md` P2-37 | Shubham | Open. Four placeholder drawables stand in. Supply `samsung_step_battery.png`, `samsung_step_sleeping_apps.png`, `samsung_step_deep_sleeping_apps.png` and `samsung_step_unused_apps.png` from the Galaxy A15 into `android/src/main/res/drawable-nodpi/` (not `drawable/`: a bitmap there is scaled by the screen density, about three times on xxhdpi, and a full screenshot becomes a bitmap of roughly 90 MB), deleting each placeholder `.xml`. At most 1080 pixels wide and 400 KB each; `DrawableDensityTest` fails the build otherwise. |
| UI toolkit for Phase 3 | The start of Phase 3 | Claude (technical review), with Shubham's approval of any dependency | **Resolved by ADR 0073 (proposed): Compose for every new screen; the ring screen stays framework views; the permission, Samsung and reliability check screens are ported to Compose.** Compose is pinned to BOM 2026.06.01, the newest that compiles against SDK 36. **The dependency list of ADR 0084, and the two fonts and 21 icons of ADR 0085, were approved by Shubham on 2026-10-05.** Each enters the build in the pull request that first uses it, and anything a pull request's code imports directly is declared directly. The eight sub modules of the approved Compose and Activity libraries that screen code imports from are declared by name, approved by Shubham in the second review (ADR 0084). |
| Does a Gentle reminder ring, and does an unanswered ring ever stop | Before Phase 3's PR 10; better before PR 2 | Claude (technical review) | Open. Found by the Phase 3 planning session while checking onboarding copy against the build. `ARCHITECTURE.md` section 4.2 says Gentle is "t+0 notification only, no repeat", but the build starts the ringer for a Gentle reminder on an exact tier (`EscalationLadder` gives it a `RING` rung, `DeliveryPath.choose` does not look at criticality, and `DeliveryTest` `each criticality rings on its own channel` asserts the ringer start). And `RingerService` has no maximum ring time for any criticality. One of the document and the build is wrong on the first; the second is undecided anywhere. `docs/phase-3-plan.md` section 8. Nothing is changed until this is decided. |
| Robolectric `@SQLiteMode` | When Robolectric is next upgraded | Claude | Open, low. The annotation is deprecated in Robolectric 4.17 (native SQLite is now the default). It is kept for its intent, and the real guard is `assertNativeSqliteMode()`, which reads the effective mode; when a later Robolectric deletes the annotation the tests stop compiling, which is the alarm to drop the annotation and keep the assertion (traceability B11). |

---

## Phase 0: Foundation

Nothing else begins until this is done.

**Deliverables**

- Repo with the module layout from `ARCHITECTURE.md` section 2. `shared`, `android`, `server`, `infra`, `docs`.
- `shared` configured for KMP with a `jvm` target only, `commonMain` + `jvmMain` source sets. No `android` target — `android` consumes `shared`'s `jvm` artifact as a plain project dependency (verified by a Phase 0 spike; see `ARCHITECTURE.md` section 2 and ADR 27). An `ios` target is not added yet but the source set layout must not preclude it.
- `README.md` at repo root: what the app is, the reliability SLO, module layout, how to run each test suite, links to `CLAUDE.md`/`ARCHITECTURE.md`/`IMPLEMENTATION_PLAN.md`.
- `docs/adr/` with one short file per decision already made in `ARCHITECTURE.md`, each recording the decision, the alternatives considered, and the rationale. Numbered, dated, never edited once accepted; superseded by new ADRs.
- `CLAUDE.md` at repo root.
- `docs/MANUAL_CHECKS.md`, initially listing the six automation ceiling items with their scripted procedures, marked not yet run.
- Gradle version catalog. Every dependency pinned.
- Detekt and ktlint configured, failing the build on violation.
- GitHub Actions workflow: `shared` JVM tests, migration tests, server tests, Android assemble, lint, Detekt. Runs on every push.
- CI check rejecting any commit that adds an `android.*`/`androidx.*` reference under `shared/`: a custom Gradle task (`verifyNoAndroidImports`) scanning `commonMain`/`jvmMain` by regex, wired into `check` with its own fixture-based test and its own named CI status check, plus Detekt's `ForbiddenImport` rule on a `shared`-scoped config as defense-in-depth. Not a git hook — bypassable with `--no-verify` and doesn't run on forked-repo PRs.
- `.gitignore` covering keystores, `google-services.json`, Terraform state, and local env files.

**Exit criteria.** CI green on an empty project. The import ban check demonstrably fails a deliberately bad commit.

**Status: closed.** Criterion 1 is verified on the real remote: CI green on 28c0864 (run 36769065695), and a deliberate bad commit in PR #2 (closed unmerged, branch deleted) failed `verify-no-android-imports` with file, line and invariant 1 named, with the PR's merge state reported as `blocked`. Details and the limits of that evidence are in `MANUAL_CHECKS.md`.

---

## Phase 1: Shared domain and engine

The correctness core. Entirely JVM verifiable. Expect this to be the largest test suite in the project.

**Status: all Phase 1 deliverables are done except three of the event log reductions listed under Deliverables: nutrition tag aggregation, water totals and the refill countdown from inventory decrement. They have no implementation in `shared` and are reassigned, not built here: nutrition tag aggregation and water totals to Phase 3, the refill countdown to Phase 6, each built with the consumer that first reads it (see Outcome). The exit criterion "all nineteen golden scenarios pass" was not met as written (see the per scenario status under Outcome); scenarios 7, 14 and 17 were deferred, with owner and reason, under "Deferred tests" in `MANUAL_CHECKS.md`, and scenario 3 is only partly tested here.** Everything else in the exit criteria is met, and the phase is not described as complete past that. The scenarios tested here are named tests; the two required properties (adherence invariant to reconciliation timing; materialisation idempotent under repetition) are randomised property tests with fixed seeds. Adherence figures and the 30 day critical completion count are reductions over the event log with an explicit `asOf` (ADR 0040), checked against a state based oracle; before that change the timing property asserted only `Reconcile`'s output, so scenario 5's second case was counted as passing without being asserted. The `Clock.System` ban (invariant 8) is a mechanical Gradle check, not a property test. The migration harness is proven (ADR 0035), now with a real v1 to v2 migration and a forward migration test (ADR 0037). No `android.*` under `shared`. Coverage: line coverage 97.5% (637/653) and branch coverage 98.0% (192/196) over `shared` (measured at `f0ef62a`), with per-package branch gates in CI at engine 95% (97/98), domain 85% (8/8) and data 85% (87/90), checked by `verifyBranchCoverage`, which fails closed (ADR 0038, ADR 0039). The three uncovered data branches are the unreachable state of exactly one quiet-hours bound set. The one uncovered engine branch is the compiler generated default of the exhaustive `when` over `Outcome?` in `adherenceFigures`: it is unreachable, because a missing arm fails compilation, and it is kept counted rather than excluded because Kover cannot exclude a single branch. An earlier line figure of 95.1% was measured with SQLDelight generated code wrongly included, because its exclusion named the wrong package. Coverage is the floor; the mutation checks recorded in the Phase 1 hardening PRs (#6, #7, the coverage PR and #11) are the evidence.

**Deliverables**

- Domain models: `ScheduleTemplate`, `Occurrence`, event log types, `Recurrence`, `Criticality`, `TaskType`, `NutritionTag`, `MissionConfig`, `PregnancyPhase`, `DeliveryCapability`, `EscalationRung`, `Channel`.
- SQLDelight schema version 1, including citation fields on the content table and `PregnancyPhase` on the pregnancy table, even though neither is used in v1.
- Migration harness and the rule that every schema change ships a migration plus a forward migration test from every prior version.
- Recurrence expansion engine.
- Occurrence materialisation over a rolling 48 hour window, idempotent, with `alarmSlot` allocation from a monotonic counter.
- Escalation ladder generation as an ordered list of rungs.
- Escalation policy resolution: criticality defaults, quiet hours, interruption budget.
- `Reconcile` domain command: derives `MISSED` for any occurrence whose grace window has expired with no terminal event, writing the event with `effectiveAt` (the computed grace-expiry instant) separate from `deviceTimestamp` (write time). Idempotent — safe to dispatch repeatedly from the watchdog, app foreground, and boot without changing the result. No caller mutates occurrence state directly; they all dispatch this command and the domain decides (invariant 3).
- Event log reduction: adherence figures (read from `effectiveAt`, not `deviceTimestamp`, for any derived event), nutrition tag aggregation, water totals, the 30 day critical completion metric, refill countdown from inventory decrement. (Nutrition tag aggregation and water totals are reassigned to Phase 3 and the refill countdown to Phase 6; see Outcome.)
- Repository interfaces plus SQLDelight implementations.
- Injected `Clock` throughout. No call to `Clock.System` outside the DI module.
- Koin modules for `shared`.

**Golden test scenarios.** These are mandatory and must exist as named tests. They are the scenarios most likely to be skipped if left to invention.

1. Recurrence expansion across a template edit that changes `timeOfDay` after some occurrences are already terminal. **Phase 1 asserted only that re-materialising the same window leaves a terminal occurrence untouched (the test "edits" a template with `setActive`, and nothing changes `timeOfDay`); it did not assert an edit that changes `timeOfDay`, because Phase 1 has no template edit path at all. So the scenario's subject was never exercised, though it was counted among the passing scenarios. Its real test is a named Phase 3 exit criterion, with the schedule builder's edit path (below).** This is the third golden scenario found counted without testing its subject, after 5 and 8.
2. Reboot part way through an escalation ladder, with the remaining rungs correctly derived.
3. Exact alarm permission revoked mid schedule, with tier downgrade and ladder adjustment. **Shared slice tested in Phase 1; the downgrade is deferred to Phase 2 — see "Deferred tests" in `MANUAL_CHECKS.md`.**
4. A snooze that would collide with the next occurrence of the same template.
5. A grace window expiring while the app is closed, producing `MISSED` without a ring, where `deviceTimestamp` reflects whenever `Reconcile` runs but `effectiveAt` reflects the true grace-expiry instant, and adherence figures come from `effectiveAt` — so a second case asserting adherence is identical whether reconciliation happens at the exact expiry instant, an hour later, or a day later is part of this scenario, not optional.
6. A backfilled completion arriving after a confirmed miss.
7. A caregiver revocation arriving mid sweep. **Deferred to Phase 4 — see "Deferred tests" in `MANUAL_CHECKS.md`.**
8. Timezone travel where the local scheduled time has already passed. **Phase 1 asserted only that materialisation uses the template's zone, so a dose materialised after travel gets the right local date; it did not assert travel itself. No code recomputed an open occurrence's instant, so the scenario's subject was never exercised, though it was counted among the passing scenarios. Its real test is Phase 2, PR 6b (ADR 0068): east, west, an overdue pending occurrence, a snoozed one and a terminal one, through the broadcast.**
9. `EveryNDays` expansion across a month boundary and across a leap day.
10. Interruption budget exhaustion downgrading `STANDARD` while leaving `CRITICAL` untouched.
11. Two templates producing occurrences at the same instant, confirming distinct `alarmSlot` values.
12. Materialisation run twice over the same window, producing no duplicates. **Note (PR #22 review, Claude (technical review)): the property test interleaves real operations between runs, but the only template edit it can make is `setActive`, because Phase 1 has no edit path. A toggle does change what a run reads (the materialiser returns nothing for an inactive template), so it is not the no-op `setActive(true)` of precedent 2; but it never changes `timeOfDay` or the recurrence, so no run is shown to read an edited field. The idempotence it asserts is real; its interleaved edit is narrow. A real interleaved edit is part of the Phase 3 exit criterion with scenario 1.**

Seven additional scenarios, added during Phase 0 planning review because they surface edge cases the original twelve don't reach:

13. Uninstall and reinstall re-materialises and re-arms from a fresh `alarmSlot` counter; no stale slot value or `PendingIntent` from the previous install remains to collide with, because uninstall and `MY_PACKAGE_REPLACED` both invalidate prior alarms (see `ARCHITECTURE.md` section 5.4, ADR 31 — this asserts the documented behaviour, it does not probe for a collision that can't occur).
14. Device clock set backward by the user while a ladder is armed does not cause already-fired rungs to re-fire or the next rung to compute a negative delay. **Deferred to Phase 2 — see "Deferred tests" in `MANUAL_CHECKS.md`.**
15. Two occurrences' ladders interleave — the one-alarm-at-a-time selection always arms the chronologically next rung across all occurrences, not just the next rung of whichever occurrence is currently being processed.
16. Quiet hours starting partway through an armed `CRITICAL` ladder still rings; a `STANDARD` ladder armed the same way defers the in-window rung to a silent notification.
17. `WorkManager` watchdog finds the correct alarm already armed — a no-op pass that emits no spurious `WATCHDOG_REPAIR` event. **Deferred to Phase 2 — see "Deferred tests" in `MANUAL_CHECKS.md`.** **Meaning of "no-op", confirmed by Claude (technical review): no event is written, no occurrence state changes, and the armed alarm is unchanged in type, trigger time and request code. A replacing call with identical parameters is allowed, so the watchdog may re-arm idempotently on every pass.**
18. App force-stopped by the OS (not the user) mid-ladder — the next watchdog pass within the 15 minute floor repairs the alarm rather than the chain staying silently dead until next app open. **A true force-stop clears the app's alarms and its jobs: the platform cancels the app's JobScheduler jobs and removes them from its store, so WorkManager's periodic watchdog goes with them. The watchdog covers process death and kills that are not force-stops; a force-stopped app recovers on its next launch or boot (`ARCHITECTURE.md` section 5.3). The scenario text is kept as written. What is tested for it is recovery from process death, and the OEM question is a row in `MANUAL_CHECKS.md`.**
19. Pregnancy phase transition (`PRENATAL` to `POSTPARTUM`) while occurrences are materialised against the old phase does not retroactively rescope or delete history scoped to the ending `pregnancyId`.

**Exit criteria.** All nineteen golden scenarios pass. Migration test passes from v1 to v1 (trivially) and the harness is proven by adding and reverting a throwaway migration. The migration test is wired into the CI workflow as its own job during this phase — not deferred again, per the Open Items entry above. No `android.*` anywhere in `shared`.

**Outcome.** **Per scenario status, as of PR 6b** (rebuilt from `phase-2-traceability.md`, `MANUAL_CHECKS.md` and the test names; it carries no count, and this paragraph is the single source for it; the same list is in `phase-2-progress.md`).

*Tested in `shared`* (named tests in `shared`; they predate the Phase 2 traceability file, which holds no row for them): 2 (`ArmingSelectionTest`: `scenario 2 a reboot part way through resumes at the next rung`, and `NextRungResolverTest`; mutation W-30 in the traceability file), 5 (`ReconcileTest`, and `EventLogReductionTest` for the second case since ADR 0040), 6 and 9 to 11, 13, 15, 16 and 19 (`GoldenScenariosDbTest`, `RecurrenceExpanderTest`, `EscalationPolicyTest`, `NextRungResolverTest`), and 12 with its caveat below (`GoldenScenariosDbTest`, `MaterialiseAtomicityTest`; the interleaved edit is only an activation toggle).

*Tested under Robolectric, with the device remainder named in `MANUAL_CHECKS.md`:* 3 (traceability E2, end to end through the watchdog worker, PR 4, with the entry point in PR 3; device P2-5, P2-11, P2-13); 4 (the shared policy and command, B4 and B5, and the ring screen, R10 and `RingActionsTest`, PR 5b; device P2-23); 8 (E10, through the broadcast, PR 6b; device P2-30); 14 (E3, through the clock change broadcast, PR 6; device P2-28); 17 (E4, `WatchdogTest`, PR 4; device P2-13); 18 (R4, the watchdog repair of a cleared alarm, `WatchdogTest` and `WorkTest`, PR 4; the shared part is `NextRungResolverTest`; the force-stop half is not tested and stays a device question, P2-1 and P2-2).

*Owed by a named phase:* 1 (Phase 3, with the schedule builder's edit path; its Phase 1 test never changed `timeOfDay`); 7 (Phase 4, the server sweep; the shared part, revocation hard deleting only its own link, is tested in `RemainingRepositoriesTest`). Scenario 12 also owes a real interleaved edit in Phase 3.

Phase 2 is open: these figures are what the automated layers show, and the device remainder of each Robolectric row is outstanding.

Scenario 8 was counted on a test that did not reach its subject (only that materialisation uses the template's zone, not that an open occurrence moves when she travels): the same failure as scenario 5's second case below, found in Phase 2 when the timezone broadcast had no command to dispatch, and closed in PR 6b (ADR 0068). Scenario 1 is the third (a named Phase 3 exit criterion: see Phase 3). Scenarios 1, 5 (until ADR 0040) and 8 were counted without testing their subject; their status today is in the per scenario status above. Scenario 5's second case (identical adherence figures after reconciling at expiry, an hour later and a day later) was not asserted until the adherence reduction was rewritten over the event log (ADR 0040); between the Phase 1 close-out and that change the scenario was counted among the passing scenarios with half of it asserted. The other three, 7, 14 and 17, and the Android half of scenario 3, need a component outside `shared`. Each is a named exit criterion of the phase that owns it: scenarios 3, 14 and 17 in Phase 2, scenario 7 in Phase 4, so none can disappear from a table. The part of each whose subject is shared code is tested now; the table in `MANUAL_CHECKS.md` names the component each one needs and the tests for its shared part. The migration criterion is met with a real v1 to v2 migration (ADR 0037) and a forward migration test, the `migration-test` CI job is green, and there is no `android.*` under `shared`.

Three event log reductions listed under Deliverables were not built in Phase 1 and are reassigned, each to the phase that first consumes it, as a deliverable and exit criterion there: nutrition tag aggregation and water totals to Phase 3, and the refill countdown from inventory decrement to Phase 6. Each stays a pure reduction in `shared`, built together with its consumer, because a reduction written before its consumer exists encodes guesses about what the view needs. Reassigned by Claude (technical review).

**Phase 1 closed 2026-10-03.** Closed by Claude (technical review), merged by Shubham. The exit criteria are as written above and the Outcome records where they stand, scenario by scenario: scenarios 3, 14 and 17 deferred to Phase 2 and scenario 7 to Phase 4; nutrition tag aggregation and water totals reassigned to Phase 3 and the refill countdown to Phase 6.

---

## Phase 2: Android alarm subsystem

The critical path. Built and verified on the JVM via Robolectric. Device verification is Phase 7.

**Status: the automated layers are closed by Claude (technical review) and merged by Shubham. Phase 2 is not complete in any human facing sense: it completes when the Phase 7 device checks pass.** The alarm subsystem passes every automated layer (the `shared` suite, the server suite and the Robolectric suite, all green in CI) with device checks outstanding: `docs/MANUAL_CHECKS.md` holds the six ceiling items and 41 Phase 2 rows (P2-1 to P2-41), none yet run, each with a procedure, and says which can be attempted informally now with the debug seed build. Phase 2's evidence is `docs/phase-2-traceability.md` (every mandatory Robolectric item and every exit criterion, with the test names and the mutations that were run to show each test can fail). Phase 2's working file, `docs/phase-2-progress.md`, was deleted by the close (PR 9, last present at commit `1e16483` in git history, which is what ADRs that cite it by name refer to); its open items now live in the plan section of the phase that owns each, or in the Open items table above, and its decisions are in the ADRs.

| Step | Branch | Pull request, merge commit | What it built (ADRs) |
|---|---|---|---|
| A | `phase-2/sqlite-parity` | #13, `43d4724` | The SQLite floor 3.22 and foreign keys enforced on the JVM drivers (0042, 0043) |
| 1 | `phase-2/android-data-wiring` | #14, `f5de0ca` | The Android driver factory, corruption kept aside, the Koin graph exposing repositories only, the structural checks, the materialisation race under `AndroidSqliteDriver` (0044, 0045) |
| B | `phase-2/telemetry-split` | #15, `397ae01` | Telemetry leaves the shared schema for the android store, the one process check, the rollback journal, corruption found during a query (0046 to 0049) |
| 2 | `phase-2/capability` | #16, `77aa203` | Delivery tiers from capability, the platform adapter, the manifest permissions allowlist, the live process after corruption (0050, 0051) |
| 3 | `phase-2/arming` | #17, `8720d03` | The `AlarmScheduler` port, the Android scheduler, one `ensureArmed` entry point, the fire path and its receiver (0052 to 0055) |
| 4 | `phase-2/workers` | #18, `6ef4909` | The watchdog and the daily materialisation as WorkManager jobs, a late rung presented quietly and never dropped, `CAREGIVER` is not an event source (0056 to 0059) |
| 5 | `phase-2/delivery` | #19, `48738c9` | The delivery paths, the channels, the ringer service, the ring screen and session, the overlay route, the reset notification (0060 to 0063) |
| 5b | `phase-2/ring-actions` | #20, `f266621` | Acknowledge, snooze and skip, the Quiet notices channel, the ramp, the backup sound, vibration, the snooze's own event (0064 to 0066) |
| 6 | `phase-2/system-broadcasts` | #21, `b93e1f0` | Boot, an app update, a clock change and a grant of exact alarm access (0067) |
| 6b | `phase-2/timezone` | #22, `e489301` | A time zone change moves open occurrences, one transaction, a terminal occurrence immutable in full (0068) |
| 7 | `phase-2/reliability` | #23, `33c7027` | Real fires as the measurement, the check she starts, fire timing, the report and the export (0069, 0070) |
| 8 | `phase-2/onboarding` | #24, `1e16483` | The permission flows, unused app restrictions, the Samsung walkthrough, the debug seed, boot count gaps (0071, 0072) |
| 9 | `docs/phase-2-close` | this pull request | The close: the exit criteria and deliverables reported, the progress file retired, a consistency read of ADRs 0042 to 0072, the screenshot density fix |

**Deliverables**

- `AlarmScheduler` interface in `shared`, Android implementation in `android`.
- `setAlarmClock` path, one alarm at a time, re armed on each fire.
- `WorkManager` watchdog at the 15 minute floor, verifying and repairing the next alarm, logging `WATCHDOG_REPAIR`, and dispatching the `Reconcile` command to the domain layer (see Phase 1) so any occurrence whose grace window has expired while the app was closed is derived to `MISSED`.
- `WorkManager` daily materialisation worker, regenerating the rolling 48 hour occurrence window (`ARCHITECTURE.md` section 3.2). Runs daily and on any template edit.
- `BroadcastReceiver` to ringer foreground service (`mediaPlayback`) to full screen intent notification to ring `Activity` with `showWhenLocked` and `turnScreenOn`.
- Ring `Activity` dispatching events to the domain and never mutating occurrence state.
- Overlay (`SYSTEM_ALERT_WINDOW`) secondary path for background activity start, with the Android 15 foreground service restriction handled.
- `DeliveryCapability` resolution: exact alarm availability, `canUseFullScreenIntent()`, battery optimisation status.
- Tier 2 and Tier 1 delivery paths.
- Permission request flows: `USE_EXACT_ALARM` declared, `SCHEDULE_EXACT_ALARM` fallback, full screen intent settings intent, battery optimisation exemption.
- Broadcast handlers: `BOOT_COMPLETED` (reschedule only, never ring, via WorkManager), `MY_PACKAGE_REPLACED`, `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`.
- The 30 minute catch up window: a rung that fires late is presented normally within 30 minutes of its own instant and as a silent notice beyond that, still within grace, and never dropped (ADR 0056; boot makes no catch up decision, it arms the earliest rung that has not fired, for now).
- Audio: `USAGE_ALARM`, `CATEGORY_ALARM`, `.wav` assets under 30 seconds, volume ramp, backup louder sound after an unacknowledged interval.
- Notification channels split by criticality.
- Per template vibration patterns.
- Reliability evidence: per fire telemetry recording scheduled versus actual, tier, screen on, audio focus, battery and Doze state, and the check she starts (a 60 second test alarm she watches, launched from the ring screen's idle state in Phase 2 and as an onboarding step in Phase 3). The tier and the state of the device are kept in the android store, not in the shared schema (ADR 0048); the shared log carries only platform neutral facts, the check's result among them. **The daily silent canary is removed from this deliverable, by Claude (technical review) in the review of the PR 7 design report: real fires are the measurement and days with no fire are unobserved (ADR 0069). The one alarm invariant is narrowed to reminders, with the check she starts as the only permitted second alarm.**
- Local telemetry storage, in app reliability view, export. No upload yet.
- Samsung One UI onboarding walkthrough: battery optimisation, Sleeping apps, Deep sleeping apps, Put unused apps to sleep, with deep link attempts and screenshot fallbacks.
- Koin graph that exposes repositories only, never `MomTimeDatabase` or any generated `*Queries` type. The generated `updateOccurrenceState` query is reachable through `database.occurrenceQueries`; the terminal trigger (ADR 0037) guards terminal states, but a non terminal state change through that query would skip the event append, so no `android` code may be able to reach it.
- A structural CI check, in the style of `verify-no-android-imports`, that fails if any `android` source references a generated `Queries` type, with a fixture based self test.

**Robolectric test coverage, mandatory**

- Correct alarm scheduled with correct trigger time, type, and request code.
- Request code uniqueness across occurrences and rungs. Deliberate attempt to force a collision must fail to collide.
- Re arm on fire produces exactly the next pending rung.
- Watchdog detects a manually cleared alarm and repairs it.
- Boot reschedules without starting a ringer service.
- Boot past an occurrence's grace transitions it to `MISSED` without ringing (`Reconcile`, ADR 0030); a rung inside grace but beyond the catch up window arrives as a silent notice, not as `MISSED` (ADR 0056).
- `SecurityException` on `setAlarmClock` resolves capability again and arms through the resulting tier (ADR 0050); tested in PR 3 against the one `ensureArmed` entry point (the shadow never throws it, so the refusal is a seam).
- `canUseFullScreenIntent()` false routes to the Tier 2 path.
- Tier resolution for each permission combination.
- Acknowledge, snooze and skip from the ring screen each write the correct event and nothing else.

**Exit criteria.** Full Robolectric suite green. Every deferred test assigned to this phase in `MANUAL_CHECKS.md` is implemented, and these are named so none can be dropped quietly: golden scenario 3 (exact alarm permission revoked mid schedule: tier downgrade and ladder adjustment); golden scenario 14 (device clock set backward while a ladder is armed: no already-fired rung re-fires and the next rung never gets a negative delay); golden scenario 17 (the watchdog finds the correct alarm already armed: a no-op pass that emits no `WATCHDOG_REPAIR`); and the materialisation race scenario of `MaterialiseAtomicityTest` run against `AndroidSqliteDriver` under Robolectric, showing the contending run waits in the framework's connection pool rather than being refused (ADR 0036, mechanism corrected by ADR 0045; until this passes nothing may claim the race is verified to serialise). The Koin graph exposes repositories only, and the structural check for generated `Queries` references in `android` sources is wired into CI and proven to fail on a fixture that references one. `MANUAL_CHECKS.md` updated with what remains unverifiable and why. **Claude Code does not mark this phase complete in any human facing sense; it is complete when Phase 7 device checks pass.**

**Deliverables, as built.** Every deliverable above, one by one, with where it landed and its record (the ADR number, the pull request, and the traceability rows). "Built" means built and verified at the automated layers; none is verified on a device.

| Deliverable | Result | Record |
|---|---|---|
| `AlarmScheduler` in `shared`, the Android implementation | Built | PR 3 (#17), ADR 0052, 0053 |
| `setAlarmClock`, one alarm at a time, re armed on each fire | Built, narrowed: one **reminder** alarm; the check she starts is the one permitted second alarm | PR 3, ADR 0053; ADR 0069 narrows it (`CLAUDE.md` carries both) |
| The WorkManager watchdog at 15 minutes, `WATCHDOG_REPAIR`, `Reconcile` | Built | PR 4 (#18), ADR 0057, 0058; traceability W rows, R4 |
| The daily materialisation worker (48 hour window, daily and on a template edit) | Built; the template edit path calls `Work.enqueueMaterialisation` and is Phase 3's | PR 4, ADR 0057 |
| Receiver, ringer foreground service, full screen intent, ring Activity | Built | PR 5 (#19), ADR 0060 to 0063; traceability P rows |
| The ring Activity dispatches events and never mutates state | Built | PR 5b (#20), ADR 0066; R10, A rows |
| The overlay path, with the Android 15 foreground service restriction handled | Built: the overlay route, and a refused foreground service start degrades to a notification and ends the session | PR 5, ADR 0061; P rows |
| `DeliveryCapability` resolution | Built | PR 2 (#16), ADR 0050; R7 to R9, C rows |
| Tier 2 and Tier 1 delivery paths | Built | PR 5, ADR 0060; traceability P rows |
| Permission request flows | Built, with one change: the battery flow is the general settings screen, not the direct request, which waits on the Open items row | PR 8 (#24), ADR 0072; Q rows |
| Broadcast handlers (boot, update, exact grant, time, time zone) | Built | PR 6 (#21) and PR 6b (#22), ADR 0067, 0068; K and L rows |
| The 30 minute catch up window | Built, changed to presentation only: a late rung is never dropped | PR 4, ADR 0056 |
| Audio, `.wav` assets, ramp, backup sound | Built, with placeholder sounds (Open items: final sounds, Shubham) | PR 5, 5b, ADR 0061, 0065 |
| Notification channels by criticality | Built: three, then a fourth, Quiet notices | PR 5, 5b, ADR 0060, 0064 |
| Per template vibration patterns | Built (placeholder patterns, a settings screen is Phase 3) | PR 5b, ADR 0065 |
| The canary: the onboarding 60 second test, the daily silent canary, per fire telemetry | The **daily silent canary was removed** (ADR 0069, Claude (technical review)); the check she starts was built (`CanaryRunner`, `ReliabilityCheckActivity`) and Phase 3 inherits it as an onboarding step; per fire telemetry built | PR 7 (#23), ADR 0048, 0069, 0070 |
| Local telemetry storage, an in app reliability view, export, no upload | Built: the store, a read only text report, a versioned JSON export; the reliability view as designed UI and the banner are Phase 3 | PR 7, ADR 0070 |
| The Samsung One UI walkthrough | Built, with unverified component names and placeholder screenshots | PR 8, ADR 0072; `MANUAL_CHECKS.md` P2-34 to P2-37 |
| The Koin graph exposing repositories only | Built | PR 1 (#14); E6 |
| The structural CI check for generated `Queries` references, with a fixture self test | Built | PR 1; E7 |

Not in the plan's list and added by Phase 2, each with its record: the android store (ADR 0048), the one process rule (ADR 0046), corruption handling (ADR 0044, 0049, 0051, 0055), store failure counts (ADR 0054), the ring session (ADR 0062), the snooze and `SNOOZE_ENDED` (ADR 0066), a time zone change command (ADR 0068), boot count gaps (ADR 0071), the debug seed screen (ADR 0072). Missions were not built and are not in any phase: the Open items row asks which phase owns them (the ring layout has an empty seam, ADR 0062). Nothing in the deliverable list lacks a record.

**Exit criteria report.** Each criterion, with its evidence. The traceability file holds the rows, the mutations and their failures; `MANUAL_CHECKS.md` holds the device remainders. Numbers are from the full local run of the CI commands on code commit `44395c6` (`shared`: 229 tests; android: 1165 test cases at the SDK levels each test is annotated for); the CI jobs on the head of PR 9 are reported in its description.

| Criterion | Evidence | Status |
|---|---|---|
| The full Robolectric suite green | `./gradlew :android:testDebugUnitTest`: 1165 test cases, none failing; CI job `android-unit-test` green on the head of PR 9 (E1) | Met |
| Golden scenario 3 (exact alarm revoked mid schedule) | `CapabilityChangeTest` `exact revoked then ensure armed`; `WorkTest` `exact revoked then the watchdog runs`, end to end through the worker with no broadcast (SDK 29, 31, 33, 36); mutations E-7, W-10a (E2). Device: P2-5, P2-11, P2-13 | Met at the automated layers |
| Golden scenario 14 (clock set backward) | `SystemBroadcastTest` `scenario 14 clock set backward re arms the next rung and fires nothing again` and `scenario 14 clock set forward arms the overdue rung for now and the fire path decides`, through the time change broadcast (SDK 29 to 36); mutations K4a, K4b, K10 (E3). Device: P2-28 | Met at the automated layers |
| Golden scenario 17 (the watchdog finds the correct alarm: a no op pass) | `WatchdogTest` `a pass over a correct state changes nothing` (three passes: no event, no state change, the alarm unchanged in type, trigger time and request code); mutation W-1 (E4). Device: P2-13 | Met at the automated layers |
| The materialisation race under `AndroidSqliteDriver` shows a wait | `MaterialiseRaceTest` `the contending run waits for the lock holder` and, as its positive control, `two drivers on one file refuse the contending run` (SDK 29 and 36, Robolectric native SQLite); mutation: the transaction removed (E5). The wait is the framework connection pool's (ADR 0045), observed for two seconds on Robolectric, not on a device. Device: P2-9 | Met under Robolectric; the device row is open |
| The Koin graph exposes repositories only | `KoinGraphTest` `resolving the database fails`, `resolving every repository creates exactly one driver`; `SharedModuleTest` `the graph does not expose the database`; mutations B3, B4 (E6) | Met |
| The generated `Queries` check in CI, failing on its fixture | `verifyNoGeneratedQueries` and `selfTestVerifyNoGeneratedQueries`, run by the CI job `verify-android-structure`; mutation B5 (E7) | Met |
| Every mandatory Robolectric item covered | Traceability R1 to R10, each with its tests, its mutations and its device remainder (E8) | Met |
| `MANUAL_CHECKS.md` states what remains unverifiable, and why | The six items and P2-1 to P2-41, each with why no automated test can settle it, a procedure, and when and by whom (E9) | Met |

The alarm subsystem is therefore reported as passing all automated layers with device checks outstanding. It is not described as complete, and no document says a device has validated any of it.


---

## Phase 3: Android UI and localisation

**Status: planned, not started.** The plan is `docs/phase-3-plan.md` (the decisions, the spike results, the dependency list, which Shubham approved on 2026-10-05, and seventeen pull requests with their tests and mutations), the visual specification is `docs/phase-3-design.md`, the proposed decisions are ADRs 0073 to 0088, and the evidence table is `docs/phase-3-traceability.md`. The template edit model adds a sixth occurrence state, `WITHDRAWN`, keeps criticality on the occurrence and takes the shared schema to version 6 (ADR 0079, proposed).

**Deliverables**

- Onboarding: due date, one medicine, permission walkthrough, the reliability check she starts. Nothing else. Everything else is deferred to first use. **It inherits Phase 2's parts and does not rebuild them:** the permission screen and its flows (`PermissionFlows`, `SetupController`), the Samsung walkthrough when the phone is a Samsung, and the check (`CanaryRunner`, with `ReliabilityCheckActivity` replaced by an onboarding step; the runner, its single writer of the result and its tests stay). The onboarding copy says what is kept on the device (local records are always kept; the opt in gates upload only), names the Quiet notices channel next to the Critical channel (ADR 0064), says plainly that Tier 1 timing is approximate, and blocks with a banner and a gate when notifications are denied without exact capability (ADR 0050).
- Starter schedule offered as an editable suggestion, clearly labelled as a suggestion to edit and not a prescription.
- Today view with four state display. It offers acknowledge, snooze and skip on an open occurrence, ringing or not, and **"Taken late" on a missed one**, which writes `COMPLETED_BACKFILLED` through a shared command, once per occurrence (`ARCHITECTURE.md` section 4.5 promises late marking; added to this line by Claude (technical review) in the review of the Phase 3 plan, ADR 0087).
- Schedule builder: create and edit templates, criticality, recurrence, nutrition tags, dosage, doctor instructions, inventory and refill threshold. **No mission config: missions are not in Phase 3 (Claude (technical review), ADR 0088); the builder writes `MissionConfig.None` and shows no mission control.**
- Water: one tap glass logging, goal configuration, progress display, optional nudge schedule.
- Nutrition aggregation view: tag counts per day and per week. Counts only. No targets, no colour coding for good or bad.
- Dashboard: today's completed, missed, skipped as three figures, water progress, gestational week, days to due date, 30 day critical completion metric.
- Settings: quiet hours, interruption budget, snooze duration, per channel preferences, locale override, telemetry opt in; and the Android only settings that live in `momtime_android_settings` (the backup sound interval and the per template vibration patterns, ADR 0065).
- Notification UX for several occurrences due at once: each occurrence gets its own actions. Phase 2 puts buttons only on a notification for one occurrence (ADR 0066), because a notification holds three actions; several due at once is likely her ordinary morning, so in Tier 2 the common case gets a heads up with no buttons. Phase 3 owns notification UX and gives each occurrence its own acknowledge, snooze and skip (per occurrence notifications in a group, or a design that fits the three action limit). Added in the review of PR #20 by Claude (technical review).
- Reliability view surfacing the check's results and the device fix path banner. The banner state is computed and tested in Phase 2 (`Banners`, with the thresholds as constants to be tuned from the A15 soak, and each banner naming its fix step as data: the Sleeping apps steps on a Samsung, the battery step elsewhere, the unused app screen); Phase 3 builds the banner as UI and the view that reads `ReliabilityReport`, over the export Phase 2 already writes.
- **The app's own home.** Phase 2 has no launcher entry of its own: the ring screen is the app's only screen, reached from the alarm clock's show intent, a ringing notification or the overlay (ADR 0061), and says so when nothing is ringing; its idle state links to the permission screen and the check ("Set up and check my reminders"). The debug build's seed screen is the only launcher entry, and only in debug. Phase 3 adds the launcher activity and the app's screens, and replaces the ring screen's idle link with the onboarding. The seed screen is kept past Phase 3 as a debug tool unless Phase 3 decides otherwise (Claude (technical review)), **but it depends on an inactive template's open occurrence still ringing**: its test reminder is an inactive template with one materialised occurrence. If the deactivation decision below is that deactivating a template stops its open occurrences ringing, the seed breaks and must seed its one off another way.
- Roborazzi screenshot tests cover the screens Phase 2 added too (the permission screen, the Samsung steps, the reliability check screen), in the same locale and scale combinations; their strings are English only until then.
- Hindi and Marathi strings for everything Phase 2 added in English only (the ring screen, the channels, the reset notification, the permission and Samsung screens, the reliability check and its report and the opt in copy), with human review of anything health adjacent; the placeholder Hindi and Marathi resources are not machine translated (`CLAUDE.md`).
- Dark mode.
- Home screen widget and quick settings tile for one tap water logging.
- Full localisation: English, Hindi, Marathi. Noto Sans Devanagari bundled, per locale line heights, `plurals` resources, no string concatenation.
- Roborazzi screenshot tests: every screen, in nine locale and scale combinations (three locales by 100%, 150% and 200% font scale) in the Light appearance, and in Dark and in each of the five Pastel palettes at English 100%, diffed against goldens. Corrected by Claude (technical review): the line named two scales where the exit criterion's nine combinations need three; appearances differ in colour only, and colour is covered by the contrast test (ADR 0075, ADR 0077).
- Accessibility pass: content descriptions, touch target sizes, large text throughout.

**Exit criterion, golden scenarios 1 and 12 (named, so they cannot be dropped).** The schedule builder's edit path ships with the real scenario 1 test: a template edit that changes `timeOfDay` after some of its occurrences are already terminal leaves every terminal occurrence untouched (the schema already refuses any update to one, ADR 0068) and moves only the open and future ones, asserted on the occurrences and not on a flag. The edit path must also decide what **deactivating a template does to its open occurrences**: today `setActive(false)` leaves them open, and they would keep ringing. **Record against this decision: the debug seed screen's test reminder rings only because of that behaviour** (see "The app's own home" above). Scenario 12's property test gains a real interleaved edit through the same path (a change of `timeOfDay` and of the recurrence between materialisation runs, asserting no duplicate dates or slots, no change to a materialised row, and convergence to the edited schedule). Added in the review of PR #22 by Claude (technical review).

**Exit criteria.** Screenshot suite green across all nine locale and scale combinations with no clipping, truncation or overflow. Human review of all health adjacent copy in Hindi and Marathi complete. Two event log reductions reassigned from Phase 1 are implemented as pure reductions in `shared`, built with the views that read them, each with its own tests, mutation checked: nutrition tag aggregation (serving counts per tag, never nutrient quantities) behind the nutrition aggregation view, and water totals behind water progress and the dashboard. Each follows ADR 0040's attribution and `asOf` semantics where they apply (an explicit `asOf` parameter, no clock read inside the reduction); any divergence gets its own ADR.

**Start the Play 12 tester clock here.** Register the developer account, set up Play App Signing, back up the upload key outside the repo, and get 12 testers opted in. Fourteen continuous days of wall clock time that cannot be compressed and should not be serialised after the build.

---

## Phase 4: Backend

Fully JVM verifiable.

**Deliverables**

- Ktor service depending on the `shared` jvm target. It uses the same recurrence and escalation code as the app. No reimplementation.
- Postgres schema (Cloud SQL) for users, pregnancies, expected occurrences (including `sweep_status`: `AWAITING`, `SATISFIED`, `MISSED_CONFIRMED`, `SUPERSEDED`), events, caregiver links, and scopes.
- Outbox ingest endpoint: idempotent on client generated event UUID, unique index enforced at the database level, at least once delivery tolerated. Rejects/discards writes older than the retention watermark (`ARCHITECTURE.md` section 6.5) instead of re-inserting them.
- Expected occurrence upload for a rolling 48 hour horizon.
- Sweep endpoint, invoked every 5 minutes by Cloud Scheduler. Durable table scan, no in memory timers. Transitions overdue occurrences from `sweep_status = AWAITING` to `MISSED_CONFIRMED` and writes `CAREGIVER_NOTIFIED` back to the device's event stream once the caregiver is actually notified.
- FCM dispatch with per caregiver notification policy: per event for `CRITICAL`, digest otherwise, or off.
- Clock authority: `serverReceivedAt` set server side, server clock authoritative for the dead man switch, skew detection reported back to the device.
- 90 day retention job with hard delete, plus the retention watermark that makes late outbox retries against purged events a no-op instead of a re-insert.
- Firebase Auth token verification.
- Structured logging with no PII, OpenTelemetry traces.
- Connection pooling for Cloud Run's scale-to-zero compute against Cloud SQL's fixed `max_connections`: pool sized to `max_connections / max_instances`, `max_instances` capped at 3, via the Cloud SQL connector. Its own ADR (28).
- Terraform for the whole stack: Cloud Run in `asia-south1` with min instances 0, Cloud SQL for PostgreSQL, Cloud Scheduler, Artifact Registry, IAM. Reads `TF_VAR_gcp_project_id` and `TF_VAR_firebase_project_id` as required variables with no default — see Open Items below.
- GitHub Actions deploy via Workload Identity Federation. No long lived service account keys anywhere.
- Local development: containerised Postgres, fake FCM sender, Firebase Local Emulator Suite's Auth emulator (server points at it via `FIREBASE_AUTH_EMULATOR_HOST` and verifies emulator-issued tokens). No production credentials reachable by the development environment.

**Carried from Phase 2**

- **Resend an occurrence that a time zone change moved.** A zone change moves open occurrences and writes no event (ADR 0068), so the server, which mirrors events and the occurrence table only as the upload sends them, would never learn the instant changed. The expected occurrence upload must resend any occurrence whose `scheduledInstant` or zone differs from what was last sent. (Claude (technical review), the review of PR #22.)
- **The server's fired count counts only the channels it passes in.** The server will call `NextRungResolver` with the caregiver channels, and the fired record is the count of `ALARM_FIRED` events (ADR 0053), so it must count the events of the channels it passes: `CAREGIVER_NOTIFIED` and the device's `ALARM_FIRED` must not share one count. (Claude (technical review), the review of PR #18.)
- **Whether a server written event needs a source of its own** (the Open items row): `CAREGIVER` is not an event source (ADR 0059), so `source` is `USER` or `SYSTEM`, and the question is whether the server's `CAREGIVER_NOTIFIED` needs a distinct one.
- **`EventRepository.deleteById` against child rows** (the Open items row), decided before the retention, revocation and erasure code is written.
- **The JVM driver factory runs `Schema.create` on every `createDriver()`** (`JvmDatabaseDriverFactory`, `shared/jvmMain`). On a database file that already has its tables the call fails, so nothing can reopen a file through it today; its comment says the server uses it for a real file, but the server is Postgres. Phase 4 decides whether the server uses this factory at all, and if anything reopens a file the factory must create or migrate by `user_version` (`Schema.migrate`), with a test that reopens a file. (Claude (technical review); the code is unchanged in Phase 2.)
- **A snooze's end is knowable without settings.** The `SNOOZED` event records `snoozedUntil` (ADR 0066) so that the server, which mirrors events and not settings, knows when a snooze ends.
- **A withdrawn occurrence must reach the server** (from the Phase 3 plan, ADR 0079, proposed). A template edit can withdraw an open occurrence (`WITHDRAWN`, a terminal state that is not an outcome). The expected occurrence upload must send the withdrawal, and the sweep must treat it as `SUPERSEDED`, or the dead man switch reports a dose she removed as missed to a caregiver. An occurrence an edit moved must be resent, as one a zone change moved is.

**Test coverage**

- Duplicate event ingest with the same UUID is a no op.
- Out of order event arrival resolves correctly.
- Sweep with a completion event that arrived after the deadline but before the sweep.
- Sweep with total device silence produces `missed_confirmed`.
- Sweep idempotency: running twice produces one notification.
- Device clock skew in both directions.
- Retention job boundary behaviour at exactly 90 days.

**Exit criteria.** Full server suite green. Every deferred test assigned to this phase in `MANUAL_CHECKS.md` is implemented, including golden scenario 7 by name (a caregiver revocation arriving mid sweep). `terraform apply` from a bare GCP project produces a working environment. Deploy pipeline runs keylessly.

---

## Phase 5: Caregiver

`ACCESS_NETWORK_STATE` stays in the manifest because WorkManager uses it and this phase needs network constraints (ADR 0057, Claude (technical review)). `MISSION_BYPASSED` carries the mission type (ADR 0052, confirmed); nothing is open there.

**Carried from Phase 3.** Phase 5 owns missions, with the approval of ML Kit's bundled barcode model (ADR 0088, Claude (technical review)). **A verified completion writes `COMPLETED` as well as `MISSION_VERIFIED`, and a bypassed one `COMPLETED` as well as `MISSION_BYPASSED`**, each with the template's nutrition tags as any completion carries them. The adherence and nutrition reductions read `COMPLETED` and ignore the mission events (ADR 0040, ADR 0086), so without this rule every dose taken through a mission is undercounted. The mission's test asserts both events and the figures.

**Deliverables**

- Account creation, required only at this point and not before.
- Invite code generation, short lived, single use.
- Caregiver join flow and link establishment, maximum 3 links.
- Per link field scope toggles. Default visible: title, scheduled time, state. Default hidden: weight, notes, doctor instructions.
- Per link notification policy controlled by her: per event `CRITICAL`, digest, or off.
- Pause sharing for a configurable period.
  - `CaregiverLink.isPausedAt` was written in Phase 1 with no caller and no test, and was deleted in Phase 1 cleanup. Only the `pausedUntil` field remains. Write the pause check again here, with its tests, when it has a caller.
- Revocation: immediate, hard deleting that link's server side mirrored data.
- Caregiver dashboard as a screen in the same app behind a role flag. Read only, with no write path at all.
- Verified, self reported and bypassed completions displayed distinctly. This distinction is the entire justification for the mission feature.
- Outbox sync wiring from device to server.
- Telemetry upload, gated on the opt in from Phase 2.

**Exit criteria.** Revocation verified to remove server side data, not flag it. Caregiver role demonstrably has no write path. Sync survives extended offline periods and replays without duplication.

---

## Phase 6: Reports, weight and export

**Deliverables**

- Weight logging: integer grams, kilogram display to one decimal, user initiated only, never prompted.
- Weight chart against gestational week. Data points only. No target line, no range bands, no colour zones, no commentary anywhere in the app.
- Per entry and full log deletion, not touching adherence history.
- PDF report generation, landscape A4, printable from the phone, populated from real collected data: adherence figures, nutrition tag counts, water history, weight chart, event detail.
- CSV export of the event log.
- Postpartum phase transition UI.
- Multiple pregnancy record support and history scoping.
- Refill reminders from inventory decrement.

**Exit criteria.** PDF renders correctly in all three locales. Export round trips. Phase transition does not corrupt or rescope historical data. The refill countdown from inventory decrement, reassigned from Phase 1, is implemented as a pure reduction in `shared`, built with refill reminders, tested and mutation checked. It decrements by a user entered quantity and computes nothing about dosing, in keeping with CLAUDE.md "Never reason about medication". It follows ADR 0040's attribution and `asOf` semantics where they apply; any divergence gets its own ADR.

---

## Phase 7: Device verification

The gate Phase 2 defers to. Structured so that most of it is passive.

### The automation ceiling

Six things cannot be proven without real hardware and real time. These are the only items that require a device, and each has a scripted procedure recorded in `docs/MANUAL_CHECKS.md`.

1. Whether One UI's Sleeping apps and Deep sleeping apps behaviour kills alarms after days of low use.
2. Real Doze maintenance window cadence over multi hour spans. `dumpsys deviceidle force-idle` is a simulation, not the real cadence.
3. Audio actually audible at correct volume through the real speaker, over Do Not Disturb.
4. Full screen intent actually turning the screen on from locked, on real hardware.
5. Timing drift under real thermal and battery conditions.
6. Samsung's scheduled alarm count behaviour, if ever exceeded.

### Making them passive

The real fires the app records from Phase 2 (and the check she starts) already record the evidence for items 1 through 5 during normal use, on days when a reminder fired. So the device check is reading a report, not performing a procedure. The app is the test harness.

**Deliverables**

- The emulator suite includes an API 29 managed device. Robolectric's SQLite is not the device's, and API 29 ships SQLite 3.22 (ADR 0042), so only a real API 29 image can show that every statement runs on the floor.
- Emulator instrumented suite in CI via Gradle managed devices, scripted through adb: Doze (`dumpsys deviceidle force-idle`, `step`, `unforce`), boot broadcast, permission revocation (`appops set`, `pm revoke`), app kill (`am force-stop`), timezone (`persist.sys.timezone`), locale switching, font scale, notification and ring screen assertions via UiAutomator.
- Firebase Test Lab runs of the same instrumented suite across a physical device matrix including Pixel, Samsung and Xiaomi models, driven from `gcloud` in CI. Known limits: per test time limits preclude multi hour soaks, and devices arrive clean so OEM battery managers are not in hostile configuration.
- Soak script for the Galaxy A15: one command that installs, seeds a schedule, starts a soak, and pulls a report. Involvement is plugging in a cable and running one command, then reading output a day later.
- `docs/MANUAL_CHECKS.md` completed, with recorded results and dates.
- Reliability report from real fire data on the A15, including a reminder after the low use period.

**Carried from Phase 2.** The device rows P2-1 to P2-41 in `docs/MANUAL_CHECKS.md` are run in this phase unless a row records an earlier device result. Specifically:

- **The API 29 managed device** also runs the migrations of both databases on the floor's rules. Some Android builds set `SQLITE_DEFAULT_LEGACY_ALTER_TABLE`, so `ALTER TABLE ... RENAME` may not rewrite references in triggers and views as the JVM's SQLite does; a future migration that renames a table other objects reference must be tested there (`ARCHITECTURE.md`, the SQLite floor; P2-4).
- **A debug variant without `USE_EXACT_ALARM`**, so that "Alarms and reminders" can be revoked on the A15 and on an API 31 to 33 phone (P2-5, P2-29). The variant is not built in Phase 2.
- **A reminder after the low use period** in the soak: real fires are the only passive evidence (ADR 0069), so with no reminder after the quiet days there is nothing to read (`MANUAL_CHECKS.md` item 1).
- **The direct boot measurement** (P2-3, and the Open items row): how often the Galaxy A15 reboots and stays locked across a reminder, read from the boot counts and boot instants the android store records (ADR 0070, ADR 0071). Whether a foreground service can start from `LOCKED_BOOT_COMPLETED` on Android 15 is unverified and belongs to the same decision.
- **Measure the tolerances and the thresholds**: the 2 and 15 minute watchdog tolerances and the 30 minute catch up window (P2-14; in Tier 1 the 15 minute tolerance can count a deferred alarm as lost, so Tier 1 repair counts are an upper bound), and the banner constants (`Banners`), tuned from the soak's reliability report.
- **Judge the placeholders** for a device: the ramp, the backup interval and the vibration patterns (P2-20, P2-21), the double sound at the start of a ring (P2-17), and the final alarm sounds (Open items, Shubham).
- **Two behaviours that are unverified without a device:** whether the ringer foreground service sounds with notifications denied (P2-16), and whether an OEM clean or optimise action is a force stop (P2-2).

**Exit criteria.** Emulator suite green in CI. Test Lab suite green across the matrix. A minimum 72 hour A15 soak with delivery inside SLO. All six ceiling items recorded as checked with results.

**Honesty constraint on claims.** With one Samsung device plus Test Lab's clean state fleet, the defensible claim is delivery measured on a specific device family under specific conditions. It is **not** validation across hostile OEM skins, because no MIUI or ColorOS device will have been tested in its default aggressive configuration. Either soften the claim or acquire one secondhand Redmi as a hostile target. The A15 with One UI is moderately aggressive, not worst case.

---

## Phase 8: Compliance and launch

**Deliverables**

- Privacy policy hosted at a public URL on GitHub Pages, versioned in this repo, in all three languages. Reachable without installing the app and not behind a login. Also available in app.
- Medical disclaimer in app and in the store listing, in all three languages, human reviewed.
- Play Health apps declaration.
- Play Data Safety form.
- Store listing localised into three languages.
- Deletion paths verified: per record, per category, full account. They cover the shared database's `.corrupt` copy (ADR 0044) and the android store, which holds no foreign key into the shared database and so needs its own deletion path (ADR 0048): the telemetry, the check's history, the arming contexts, the boot instants, the failure counts. The onboarding and the privacy policy say what is kept on the device.
- Confirmation that no PII appears in any log, client or server.
- Re verification of DPDP cross border status, which is moving and expected to become operative around 13 May 2027.
- Closed testing track satisfied: 12 testers, 14 continuous days.
- Production release.

---

## Phase 9: Deferred

Not in v1. Recorded here so they are not reinvented.

- iOS build. Constraints in `ARCHITECTURE.md` section 12 keep it additive. Realistically three to four weeks of new work whenever it starts, not three days. It requires a Mac, a physical iPhone on iOS 26 or later, and the Apple Developer Program at 99 USD per year. None of that is avoidable.
- `PHONE_CALL` escalation rung, already present in the enum and unimplemented. Requires a telephony provider and costs real money per call.
- Web caregiver dashboard.
- Monetization of any kind. If ever added, caregiver safety notifications stay outside any paywall.
- Health content library. The citation fields exist in schema version 1 so this needs no migration.
- Doctor sharing as a first class feature, which implies a consent and sharing posture that is real work.

Permanently excluded: AI or LLM features, nutrient quantity calculations, dose calculation or suggestion, physical exertion missions, advertising anywhere.

---

## Testing strategy summary

| Layer | Runs on | Covers |
|---|---|---|
| 1. JVM unit tests, `shared` | CI, no device | All domain correctness. Most real bugs live here. |
| 2. Robolectric + `ShadowAlarmManager` | CI, no device | Alarm scheduling adapter, request codes, watchdog, boot, tier resolution, permission degradation. |
| 3. Roborazzi screenshots | CI, no device | Devanagari clipping, overflow, wrapping, 200% font scale, three locales. |
| 4. Emulator instrumented, adb scripted | CI | Doze simulation, boot, permission revocation, force stop, timezone, locale, UI assertions. |
| 5. Firebase Test Lab | CI | Real hardware across OEMs, clean state. |
| 6. A15 soak, automated | One command, read later | Real Doze cadence, real timing drift, One UI sleeping apps, audio, screen on. |

Layers 1 through 5 are fully automated and verifiable without human time. Layer 6 is a script plus reading a report. The six ceiling items are the only genuinely manual surface, and real fires convert most of them into telemetry.

The standard to aim at is that a device run confirms what the automated suites already proved, rather than discovering new bugs. That is reachable for roughly the whole surface except the six enumerated items, which is why they are enumerated rather than waved at.

---

## Working conventions

Carried from the Phase 2 working file when it was deleted, so that they outlive it. They bind how the project is built; `CLAUDE.md` wins over them if they conflict.

- **Roles.** "Claude (technical review)" is the reviewing Claude in Shubham's chat: decisions it made are recorded in the ADRs and the plan as "by Claude (technical review)", with no link. Shubham owns the device checks, the merges, branch deletion, branch protection and the release decision. The implementing session never pushes to `main` and never merges. A judgment of the implementing session is labelled "for review"; one that reached `main` in a merged pull request was accepted in that pull request's review.
- **One branch at a time** from current `main`, only after the previous pull request is merged, never stacked. When a pull request is green the implementing session stops and hands over for review. Where an earlier plan and a later prompt name a branch differently, the later prompt wins.
- **Green means every check on the pull request head SHA, read through the GitHub MCP before handover, never while CI is running.** If the MCP job listing fails, the handover says how it failed: the fallback is a recorded exception, not a habit. Until a new job is confirmed as a required check on `main`, green means every check on the pull request.
- **Mutations run on the final code commit.** Each pull request description carries a mutation table (the mutation, the test that failed, the observed failure) and the counts, each with the SHA it was measured at; the traceability file holds the rows. Every Robolectric test that claims a scheduling call must fail when that call is removed; every boot test must fail if boot is changed to start the ringer.
- **No conversation or session link anywhere in the public repository**: not in commit messages or trailers, pull request titles or bodies, comments, code comments or docs (Shubham's instruction). Check each new commit message before every push, and a pull request body before opening or editing it, and say in the handover that this was done. Commits already on `main` stay as they are: rewriting them needs a force push to `main`, which branch protection blocks, and would break every cited SHA.
- **Strings.** Every user facing string is in `values/strings.xml`. In Phase 2 they are English only, and no Hindi or Marathi resource exists (Claude (technical review)): the three locale rule of `CLAUDE.md` is met in Phase 3, with human review of health adjacent copy.
- **No new `apply(from = ...)` script plugins without asking.** If more build logic needs sharing across modules, the next step is an included build, and that is a decision for Claude (technical review). (The android and shared builds still `apply(from)` the SQLite floor script, from before this rule.)
- **When the second half of an ADR's change lands later, do not edit the ADR.** Record the completion in the traceability file and in `ARCHITECTURE.md`.
- **Dependencies approved so far** (invariant 7; anything else needs approval first): `app.cash.sqldelight:android-driver` 2.4.0, `androidx.work:work-runtime` 2.12.0, `androidx.work:work-testing` 2.12.0 (test only), and the android module's own declarations of the already pinned `koin-core`, the SQLDelight runtime, `kotlinx-datetime`, Robolectric 4.17 and JUnit 4.13.2, plus `kotlinx-kover` (Phase 1). **Declined for now:** `koin-android`, the `androidx.test` artifacts, `kotlinx-coroutines-test`, a direct `androidx.core`. **Not approved and not in the build:** `kotlinx-serialization` (the export uses the platform's `org.json`) and any Compose artifact. If any android source imports an androidx class, its artifact is declared directly: nothing used directly may arrive only transitively through WorkManager.
- **Local build.** Gradle needs JDK 17 or later as launcher (`README.md`). The local equivalent of CI is every command in `.github/workflows/ci.yml`; the short form is `./gradlew :shared:check ktlintCheck detekt :android:assembleDebug :server:test`.
- **Robolectric notes.** It prints "A resource was acquired at attached stack trace but never released" during the WorkManager tests: WorkManager's test database, nothing in the app's code. Keep database test method names short on Windows (`README.md`).
