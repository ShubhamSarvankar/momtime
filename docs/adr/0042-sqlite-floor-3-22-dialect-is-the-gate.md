# 0042. The SQLite floor is 3.22, and the SQLDelight dialect is the gate

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) during Phase 2 orientation; implemented in `phase-2/sqlite-parity`.

## Context

`minSdk` is 29 (`ARCHITECTURE.md` section 11). The SQLite an Android release ships is fixed by the OS, not by the app. The official table (developer.android.com, `android.database.sqlite` package summary) gives 3.22 for API 28 and 29 (an unlisted release uses the next lower listed one), 3.28 for API 30, 3.32 for API 31 to 33, 3.39 then 3.42 for API 34, 3.44 for API 35.

`shared` was configured with the `sqlite-3-38-dialect`, under a comment saying Android 10 "ships SQLite well past 3.24". That is wrong: API 29 ships 3.22. The wrong dialect is why `upsertWaterGoal` compiled. It used `INSERT ... ON CONFLICT ... DO UPDATE` (SQLite 3.24), so on API 29 the statement fails at runtime, and no Phase 1 test could see it because the JVM tests run on SQLite 3.53.4 (measured).

## Decision

1. The SQLite floor is **3.22**.
2. `shared` compiles its SQL under the **`sqlite-3-18-dialect`**, the highest SQLDelight dialect at or below the floor (the published dialects are 3.18, 3.24, 3.25, 3.30, 3.33, 3.35, 3.37, 3.38, 3.39 and 3.44, all at 2.4.0 on Maven Central). The SQLDelight compiler is the gate: it rejects syntax newer than the floor when the schema is compiled. The 3.38 dialect artifact leaves the version catalog, and `sqlite-3-18-dialect` enters it at the SQLDelight version.
3. `upsertWaterGoal` is replaced by an `UPDATE`, then a plain `INSERT` when `changes()` is zero, both in one transaction (`SqlDelightWaterGoalRepository.upsert`). Not `INSERT OR IGNORE`: that is the pattern ADR 0036 removed, because it hides a bug. Inside the transaction nothing can insert the row between the two statements, so a plain `INSERT` that fails is a real failure.
4. A text scan survives only for what the dialect was shown to accept although it is newer than the floor. `verifySqliteFloor` (with `selfTestVerifySqliteFloor`, both wired into `:shared:check`) names exactly one: **`iif()`**, added in SQLite 3.32 and accepted by the 3.18 dialect. It runs inside the existing `shared-test` CI job, so there is no new required check.

## Evidence

Probed against the 3.18 dialect with a throwaway `.sq` file, not assumed:

- Rejected at compile time: `ON CONFLICT ... DO UPDATE` (the original query: "`,` expected, got `ON`"), `RETURNING`, window functions (`OVER`), `FILTER`, `UPDATE ... FROM`, `NULLS FIRST/LAST`, generated columns, `STRICT`, `ALTER TABLE ... RENAME COLUMN`, and the unknown functions `json`, `json_extract`, `unixepoch`, `concat`, `string_agg`, `octet_length`, `unhex`, `timediff`, `sqlite_offset`, `format`, `sign`, `ceil`, `floor`, `trunc`, `pow`, `sqrt`, `ln`, `if`, `load_extension`.
- Accepted although newer than 3.22: `iif`. That is the one scan entry.
- Accepted and available on 3.22 (not a problem): common table expressions, `changes()`, `total_changes()`, `printf`, `strftime` and the date functions, `group_concat`, `quote`, `zeroblob`, `randomblob`, `unicode`, `likelihood`, `unlikely`, `sqlite_source_id`.

## Limits of this decision

- The probe covered the constructs listed, not every SQLite feature. The scan list grows when a probe shows another accepted construct; a name is added only together with the probe that showed it.
- The dialect checks syntax and function names. It does not check behaviour that changed between versions (for example `legacy_alter_table` semantics changed around 3.25 and 3.26, which matters for a migration that rebuilds a table another table references). Migrations that rebuild such a table need a test on the floor behaviour; `1.sqm` rebuilds only `app_settings`, which nothing references.
- Robolectric's SQLite is not the device's, so no Robolectric test can prove API 29 compatibility. The emulator suite in Phase 7 includes an API 29 managed device for that reason (`IMPLEMENTATION_PLAN.md`, Phase 7), and `MANUAL_CHECKS.md` records the row.
- The 3.22 floor holds while `minSdk` is 29. Raising `minSdk` to 30 (3.28) would raise it, and that is a new ADR.

## Alternatives considered

- **Raise `minSdk` to 30.** Rejected: `minSdk` 29 has its own rationale (`ARCHITECTURE.md` section 11), and a one-statement problem is not a reason to drop a release.
- **Keep the 3.38 dialect and add a regex scan for newer constructs.** Rejected by Claude (technical review): a compiler rejects syntax, a regex only matches text, and a regex list can silently miss a construct.
- **`INSERT OR IGNORE` after an `UPDATE`.** Rejected, as above.
- **Ship a second implementation per SQLite version.** Rejected: a floor is simpler than two code paths.

## Consequences

- A construct newer than 3.22 fails the `shared` build, not a user's phone.
- The query change needs no schema migration: `water_goal`'s definition is unchanged.
- The generated query surface changes (`updateWaterGoal`, `insertWaterGoal`, `changedRows` replace `upsertWaterGoal`). Nothing outside `shared` referenced it.
