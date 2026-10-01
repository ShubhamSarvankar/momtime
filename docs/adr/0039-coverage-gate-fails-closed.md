# 0039. The branch coverage gate fails closed

Date: 2026-10-01
Status: Accepted

ADR 0038 introduced the per-package branch gate and is not edited. This ADR changes how the gate behaves and corrects two statements made when it landed.

## Context

The failure the gate exists to prevent is a configured check that silently applies to nothing. It had already happened once: the exclusion for SQLDelight's generated code named `com.momtime.shared.data.MomTimeDatabaseImpl`, the class is generated in `com.momtime.shared.data.shared`, so the exclusion matched no class and inflated line coverage for all of Phase 1. The gate introduced in ADR 0038 could fail the same way:

- If the Kover report was missing or unreadable the task had nothing to compare and its behaviour was not defined as a failure.
- If a gated package was renamed, a threshold keyed on the old name would silently stop applying. (The task did report an absent package, but nothing proved it.)
- If an exclusion pattern was misspelt or stopped matching, nothing noticed.

What the ADR 0038 self-test asserted: only the number of shortfall messages, from fixtures that held a single counter per package. It checked that 94 of 100 below a threshold of 95 gave one shortfall, 95 of 100 gave none, and an unknown package gave one. It did not assert a computed figure, it did not include LINE counters or the class and source-file counters that sit inside a package in a real report, and so it could not have caught a gate that computed the wrong percentage or read the wrong counter. Raising a threshold to 100 and watching it fail showed only that the task can fail.

Two statements made at that time were also wrong. The data package was reported as 86/90 branches in one place and 87/90 in another, for what is deterministic coverage. The 86/90 figure was taken before the `findByTemplateAndDate` test was added; the code that landed on `main` measures 87/90. The one branch that was left unnamed at the time was the present-row branch of `findByTemplateAndDate`, which was reachable and is now covered.

## Decision

`verifyBranchCoverage` fails closed. It fails the build if:

1. the Kover XML report is missing;
2. any gated package is absent from the report, or is present with no branches (a rename cannot drop a package out of the gate);
3. any exclusion pattern matches no compiled class. The patterns live in one list, `coverageExcludedClasses`, used both to configure Kover and by this check, and are matched against the compiled class files under `build/classes/kotlin/jvm/main` with Kover's own wildcard rules (`*`, `?`, otherwise an exact name);
4. the compiled classes directory is missing, so (3) could not run.

The self-test, `selfTestVerifyBranchCoverage`, now asserts computed figures against a fixture with known counters: a package whose own counter is 47 covered and 3 missed, with LINE counters and nested class and source-file counters that must not be read as the package's figure (the package counter appears after them in one package and before them in another, so reading counters in document order cannot pass); the exact figure, total, percent and rendering; a threshold met exactly and one missed by a single branch; the missing-report, absent-package and no-branch cases; and the unmatched-exclusion matcher.

Each rule was mutation-checked on the real build: renaming a gated package, misspelling an exclusion, pointing the generated-code exclusion back at the wrong package (the original bug), and deleting the report each fail the build with a message naming the problem. Mutating the gate's own computation (swapping covered and missed, turning `>=` into `>`, reading nested counters) fails the self-test.

## Alternatives considered

- Trust the self-test of ADR 0038 and the absent-package message — rejected for the reasons above.
- Fail only on the exclusion list, not the report — rejected; a missing report is the plainest way to pass a gate that checks nothing.

## Consequences

- Adding an exclusion requires a class it matches, so an exclusion cannot outlive the class it was written for.
- Every exclusion is still named by class, in one list, with its reason in a comment. Nothing hand-written is excluded.
- The corrected figure, at `ec02826`, is data 87/90 (96.7%), engine 72/72, domain 8/8: 167/170 branches (98.2%) and 616/632 lines (97.5%) over `shared`. The three uncovered branches are all one state, exactly one quiet-hours bound set, in `toQuietHours`; the `quiet_hours_valid` CHECK makes it unreachable and the arm is kept as a loud failure, not excluded.
