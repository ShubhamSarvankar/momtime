# 0052. Each event type has its own allowed columns, and decoding fails loudly on any other

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the review of PR #16; implemented in `phase-2/arming`. It extends the canary column guard of PR 2, which covered the canary columns only.

## Context

The `event` table stores a payload as sparse nullable columns (`snooze_number`, `mission_result_type`, `water_ml`, `weight_grams`, `caregiver_link_id`, `canary_scheduled_at`, `canary_actual_at`) next to `effective_at`. Its only constraint is `CHECK (source IN ('USER', 'SYSTEM'))`. Nothing in the schema ties a column to an event type, so a mapping bug could write a water amount on a `COMPLETED` event, or an `effective_at` on anything, and the row would decode as a payload nobody meant to write. The log is the record of authority and adherence is a reduction over it, so a row that says something it should not is worse than a decode that fails.

Phase 1 left that open for every column. PR 2 closed it for the canary columns because they were new. The review of PR #16 asked for it everywhere.

## Decision

1. `allowedColumns(EventType)` (in `EventColumn.kt`) lists, for each event type, the type dependent columns it may carry. Decoding collects the columns that are set on the row and throws `IllegalStateException` if any is not in the allowed set. An absent column is always fine: it is a list of what is allowed, not of what is required.
2. The `when` over `EventType` is exhaustive, so a new event type does not compile until it is given a set.
3. The sets: `MISSED` takes `effective_at` alone (ADR 0030); `SNOOZED` takes `snooze_number`; `MISSION_VERIFIED` and `MISSION_BYPASSED` take `mission_result_type`; `WATER_LOGGED` takes `water_ml`; `WEIGHT_LOGGED` takes `weight_grams`; the five caregiver events (`CAREGIVER_LINKED`, `CAREGIVER_REVOKED`, `SHARING_PAUSED`, `SHARING_RESUMED`, `CAREGIVER_NOTIFIED`) take `caregiver_link_id`; `CANARY_RESULT` takes both canary columns; every other type takes none.
4. A `CANARY_RESULT` with an actual instant and no scheduled one still fails to decode. One with neither still decodes as no payload (the old form).
5. No schema change. This is a check in the repository, not a CHECK in the table.

## Judgment calls

- **`MISSION_BYPASSED` may carry `mission_result_type`.** Nothing in the documents says whether a bypass names the mission it bypassed. Allowing it rejects no legitimate row, and a tighter rule can replace it when Phase 5 decides. It is a guess in the permissive direction, recorded here so it is not mistaken for a decision.
- **The check is on decode, not on insert.** The review asked for decode. An insert that failed on the alarm path would stop an alarm over a logging bug; a decode that fails is found by the test that reads the row.

## Alternatives considered

- **A CHECK constraint per column in the schema.** Rejected: it is a schema change, needs a migration and a forward migration test from every prior version (CLAUDE.md invariant 2), and a table rebuild on SQLite 3.22, to enforce what a decode check enforces.
- **Leave the other columns as they were.** Rejected by the review: the canary guard showed the cost of a mapping bug is a silently wrong row.

## Evidence

`EventColumnGuardTest` sets each column on its own on every event type and expects a decode failure exactly when the type does not allow it. The expectation is written out by hand in the test, not read from `allowedColumns`, so the test cannot agree with a mistake in the code by sharing it. The test counts both outcomes, so it cannot pass vacuously in either direction. The mutations tried are in `phase-2-traceability.md`.
