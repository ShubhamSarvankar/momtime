# 0038. Per-package branch coverage gate, and what is excluded from coverage

Date: 2026-09-30
Status: Accepted

## Context

CI gated only line coverage over all of `shared` (90%). Line coverage hides exactly the code that matters most: the engine decides whether a dose rings and whether `MISSED` is derived, and its untested branches (the `STANDARD` grace arm, a `SNOOZED` occurrence past grace, the non-wrapping quiet-hours window) were all invisible to a line figure. Mutation checks showed several tests that were green while verifying nothing, so coverage cannot be the evidence either.

While measuring, an earlier mistake surfaced: the Kover exclusion for SQLDelight's generated implementation named `com.momtime.shared.data.MomTimeDatabaseImpl`, but SQLDelight generates that class in `com.momtime.shared.data.shared`. The exclusion never matched, so the previously reported line figure of 95.1% included the generated `Schema` code.

## Decision

1. **Branch gates, per package, failing the build:** `engine` 95%, `domain` 85%, `data` 85%. The engine floor is high on purpose: a 90% floor would leave room for a new uncovered branch in the code that decides whether a dose rings. The existing 90% line gate stays.
2. **How it is enforced.** Kover 0.9 cannot attach a filter to a verification rule, so the per-package thresholds cannot be expressed in `kover { verify { } }`. A small Gradle task, `verifyBranchCoverage`, reads Kover's own XML report and fails with the package, the figure and the threshold. It has a fixture-based self-test (`selfTestVerifyBranchCoverage`) that proves it fails a package below its threshold and passes one at it, and both are wired into `check`, like the other mechanical checks.
3. **Exclusions are named by class and are only generated code.** Nothing hand-written is excluded.
   - `com.momtime.shared.data.shared.*`: SQLDelight's generated database implementation (`Schema.create`, `Schema.migrate` and the query wiring). Nothing hand-written lives in that package. This corrects the exclusion described above.
   - The generated row classes and query classes under `com.momtime.shared.data` that were already excluded by name (`Pregnancy`, `Due_date_revision`, `Schedule_template`, ..., `*Queries*`).
   No member or branch inside a hand-written class is excluded. The unreachable `else -> error(...)` arm in `toRecurrence` that was expected to need an exclusion does not: Kover does not count a string `when`'s `else` as a missed branch.
4. **Thresholds do not move to make a build pass.** If a package falls below its threshold, the gap is reported and fixed with tests or code, not by lowering a number.

## Alternatives considered

- Rely on line coverage — rejected, as above.
- A single overall branch threshold — rejected. It lets a well-covered `data` package carry an uncovered engine.
- Exclude individual branches by annotation — rejected. Kover excludes whole declarations, which would also drop their covered branches, and an excluded branch is a branch nobody has to think about.
- Lower the thresholds until the build passes — rejected outright.

## Consequences

- Reported line coverage now excludes generated code, so it is lower than the earlier 95.1% would suggest it should be for the same tests; the figures in `IMPLEMENTATION_PLAN.md` are the corrected ones.
- Coverage is the floor and not the evidence. Removing a test does not necessarily trip the gate: with `Reconcile` guarding on `isTerminal` there is no separate branch left for a `SNOOZED` test to cover, so deleting that test leaves the figures unchanged. Each test that asserts an invariant is therefore mutation-checked (CLAUDE.md, Testing), and the mutation and the test that failed are recorded in the PR.
- Known branches that no input can reach are not excluded. `toQuietHours` in `SqlDelightAppSettingsRepository` has an arm for exactly one quiet-hours bound set, which the `quiet_hours_valid` CHECK makes unreachable; it is kept as a loud failure and costs three branches in a package with headroom over its 85% floor.
