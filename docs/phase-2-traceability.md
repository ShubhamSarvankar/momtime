# Phase 2 traceability

Maintained through Phase 2 and kept at close (unlike `phase-2-progress.md`). One row per mandatory Robolectric item, per named Phase 2 exit criterion, and per mutation the reviewer required, mapped to the test that covers it, the mutation tried, and the failure observed. A row is not PASS until its mutation has been observed to fail on the final commit of its PR.

Columns: **ID**; **Requirement and source** (plan item, exit criterion or scenario); **Test** (class and method); **Mutation tried** (file and change); **Observed failure** (test and message); **Mutated at** (SHA); **Status** (PASS, PENDING with owning PR, or DEFERRED with owner); **Device remainder** (the `MANUAL_CHECKS.md` row for what no automated test can settle).

## Mandatory Robolectric items (`IMPLEMENTATION_PLAN.md`, Phase 2)

| ID | Requirement | Test | Mutation tried | Observed failure | Mutated at | Status | Device remainder |
|---|---|---|---|---|---|---|---|
| R1 | Correct alarm scheduled with correct trigger time, type and request code | | | | | PENDING (PR 3) | |
| R2 | Request code uniqueness across occurrences and rungs; a forced collision must fail to collide | | | | | PENDING (PR 3) | |
| R3 | Re-arm on fire produces exactly the next pending rung | | | | | PENDING (PR 3) | |
| R4 | Watchdog detects a manually cleared alarm and repairs it | | | | | PENDING (PR 4) | |
| R5 | Boot reschedules without starting a ringer service | | | | | PENDING (PR 6) | |
| R6 | Boot outside the catch up window transitions to `MISSED` without ringing | | | | | PENDING (PR 6) | |
| R7 | `SecurityException` on `setAlarmClock` re-resolves capability and arms through the resulting tier (reworded from "degrades to Tier 2" by the tier ADR, PR 2) | | | | | PENDING (PR 3) | |
| R8 | `canUseFullScreenIntent()` false routes to the Tier 2 path | | | | | PENDING (PR 2) | |
| R9 | Tier resolution for each permission combination, under `@Config` at SDK 29, 31, 33 and the latest | | | | | PENDING (PR 2) | |
| R10 | Acknowledge, snooze and skip from the ring screen each write the correct event and nothing else (exact event log delta and state delta) | | | | | PENDING (PR 5) | |

## Phase 2 exit criteria (`IMPLEMENTATION_PLAN.md`)

| ID | Requirement | Test | Mutation tried | Observed failure | Mutated at | Status | Device remainder |
|---|---|---|---|---|---|---|---|
| E1 | Full Robolectric suite green | | | | | PENDING (PR 9) | |
| E2 | Golden scenario 3: exact alarm revoked mid schedule, tier downgrade and ladder adjustment, with no reliance on a broadcast | | | | | PENDING (PR 2 and 3) | P2-5 |
| E3 | Golden scenario 14: device clock set backward while a ladder is armed; next rung selected from the record of fired rungs, never a now comparison | | | | | PENDING (PR 6) | |
| E4 | Golden scenario 17: watchdog finds the correct alarm already armed, no event, no state change, armed alarm unchanged | | | | | PENDING (PR 4) | |
| E5 | Materialisation race under `AndroidSqliteDriver`: the contending run waits, not is refused; positive control shows refusal with two drivers on one file | | | | | PENDING (PR 1) | |
| E6 | The Koin graph exposes repositories only | | | | | PENDING (PR 1) | |
| E7 | The generated `Queries` structural check is in CI and fails on its fixture | | | | | PENDING (PR 1) | |
| E8 | Every mandatory Robolectric item has a row in this table | this file | | | | PENDING (PR 9) | |
| E9 | `MANUAL_CHECKS.md` updated with what remains unverifiable and why | | | | | PENDING (PR 9) | P2-1 to P2-5, more to come |

## Required mutations by PR

### PR A, `phase-2/sqlite-parity` (mutated at `90755bc`, the final code commit; the PR head differs only in docs)

The commits of this branch were reworded once to remove a conversation link from their messages (a project rule). The code tree of `90755bc` is byte for byte the tree the mutations ran on: it has the same tree hash, `9010cbdd7599293fdc80ab376b2097cece4cc3df`, as the original commit `0ebd616`, so the mutation results hold. Two of the nine mutations below were run by hand rather than through the test runner, and both ran on the working tree whose code was identical to `90755bc` (the documentation files of the head commit were not yet committed): the real `iif` query for `verifySqliteFloor`, and the scan list replaced by a name that matches nothing for `selfTestVerifySqliteFloor`. Neither was rerun after the reword, because the code tree did not change.

| ID | Requirement | Test | Mutation tried | Observed failure | Mutated at | Status | Device remainder |
|---|---|---|---|---|---|---|---|
| A1 | Foreign keys enforced in the JVM factory; an orphan insert through the repository layer is rejected | `ForeignKeyEnforcementTest`: `the factory turns foreign keys on...`, `a caller cannot turn enforcement off...`, `the repository layer rejects an orphan row for every declared foreign key it can write` | `JvmDatabaseDriverFactory.kt`: comment out `setProperty("foreign_keys", "true")` | the three tests above fail (107 run, 3 failed) | `90755bc` | PASS | |
| A2 | The SQLite 3.22 floor is enforced by the dialect | build (`generateCommonMainMomTimeDatabaseInterface`) | `WaterGoal.sq`: add an `ON CONFLICT ... DO UPDATE` upsert query | compile error `WaterGoal.sq:26:0 ',' expected, got 'ON'`, build fails before any test | `90755bc` | PASS | P2-4 |
| A3 | Zero-row branch of `upsert`: a goal is created where none exists | `RemainingRepositoriesTest`: `a water goal is created where none exists` (also `DataCoverageTest` `water goal round trips`, `ForeignKeyEnforcementTest` orphan test) | `WaterGoalRepository.kt`: the insert branch condition replaced by `false` | `a water goal is created where none exists`, `a water goal update changes that pregnancy's goal and no other`, `water goal upserts rather than duplicating`, `water goal round trips`, and the orphan test fail (107 run, 5 failed) | `90755bc` | PASS | |
| A4 | Update branch of `upsert`: an existing goal is changed and no second row is inserted | `RemainingRepositoriesTest`: `a water goal update changes that pregnancy's goal and no other`, `water goal upserts rather than duplicating` | `WaterGoalRepository.kt`: the `updateWaterGoal` call made unreachable | the water goal tests fail, since the plain insert of an existing key throws (107 run, 4 failed) | `90755bc` | PASS | |
| A4b | The update is scoped by pregnancy | `RemainingRepositoriesTest`: `a water goal update changes that pregnancy's goal and no other` | `WaterGoal.sq`: `WHERE pregnancy_id = ? OR 1 = 1` | that test fails (107 run, 1 failed) | `90755bc` | PASS | |
| A5 | A migration test ends with `PRAGMA foreign_key_check`; an orphan only that check catches fails it | `SchemaV2Test`: `foreign_key_check reports an orphan the migration let through`, `a migration test with an orphan fails on the foreign key check` | `SchemaV2Test.kt`: the end-of-migration check in `migratedFromV1` disabled | `a migration test with an orphan fails on the foreign key check` fails (107 run, 1 failed) | `90755bc` | PASS | |
| A5b | The check itself reports violations | same two tests | `ForeignKeySupport.kt`: `foreignKeyViolations` runs a query that returns nothing | both orphan tests fail (107 run, 2 failed) | `90755bc` | PASS | |
| A6 | `verifySqliteFloor` detects the one post-3.22 function the dialect accepts | `selfTestVerifySqliteFloor` (fixture with `iif(`, and a comment containing it) | (1) a real `iif(1,2,3)` query added to a `.sq` file; (2) the scan list in `shared/build.gradle.kts` replaced by a name that matches nothing | (1) `verifySqliteFloor` fails naming `Probe.sq:2`; (2) `selfTestVerifySqliteFloor` fails: "failed to detect a deliberately bad fixture" | `90755bc` | PASS | |
