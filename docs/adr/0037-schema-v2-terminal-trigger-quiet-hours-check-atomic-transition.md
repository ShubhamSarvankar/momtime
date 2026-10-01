# 0037. Schema v2: terminal-state trigger, quiet-hours CHECK, atomic state and event

Date: 2026-09-30
Status: Accepted

## Context

Three guarantees were held only by convention.

1. **Terminal occurrences are immutable** (`ARCHITECTURE.md` 3.2, ADR 0014: "`COMPLETED`, `SKIPPED`, and `MISSED` are terminal and immutable"). The only protection was that re-materialisation happens to skip existing dates. `updateState` was a bare `UPDATE ... WHERE id = ?`, so any future receiver or worker could overwrite a completed or missed occurrence.
2. **Quiet hours** were two independent nullable columns and two nullable Kotlin parameters. A window with only one end set was representable, and equal start and end had no defined meaning. `STANDARD`, the default criticality, is the criticality quiet hours affect, so a wrong reading silences most doses.
3. **State and event** were separate calls. Nothing stopped an occurrence from reaching a terminal state with no event in the log, or an event with no state change.

The docs were checked for a sanctioned transition out of a terminal state, such as a late confirmation after `MISSED`, before the trigger was written. None exists. ADR 0014 says late completion "is written as `COMPLETED_BACKFILLED`, not `COMPLETED`", and `ARCHITECTURE.md` 4.5 says "After the grace window an occurrence is terminal. Late marking is still allowed but is written as `COMPLETED_BACKFILLED`". Both describe an event. The scenario 6 test keeps the occurrence `MISSED` and appends the backfill event. So the trigger permits no transition out of a terminal state. How a backfilled completion is surfaced in adherence reporting is a separate, open question (see Consequences).

## Decision

One migration, version 1 to 2, containing:

- **Trigger `occurrence_terminal_immutable`**, `BEFORE UPDATE ON occurrence`: aborts when `old.state` is `COMPLETED`, `SKIPPED` or `MISSED` and `new.state` differs. It lives in the schema because every write passes through the schema. An update that leaves the state unchanged is allowed. `Occurrence.isTerminal` remains the single Kotlin definition of terminal, and `SchemaV2Test` iterates every `OccurrenceState`, using `isTerminal` to decide what the trigger must reject, so a terminal state added to the enum without updating the trigger fails the build.
- **CHECK `quiet_hours_valid` on `app_settings`**: both bounds null, or both non-null and unequal. SQLite cannot add a CHECK to an existing table, so the migration renames the old table aside, creates the final definition under its real name, copies and drops. Version 1 rows that cannot satisfy the CHECK (one bound only, or equal bounds) are copied with both bounds cleared rather than failing the migration. No released build holds such data.
- **`QuietHours(start, end)` value object**, nullable as a whole, with `start != end` enforced in its constructor. `AppSettings` and `EscalationPolicy` take `QuietHours?`. A half-set window is unrepresentable. **Equal start and end is invalid**: reading it as a full day silences `STANDARD` all day; reading it as no window silently discards a setting the user chose. Rejecting it at input never guesses intent. Start is inclusive and end is exclusive.
- **`OccurrenceRepository.transition(occurrenceId, to, event)`** appends the event and sets the state in one transaction, and the bare `updateState` is removed from the interface. If the trigger rejects the change, the event rolls back with it. `Reconcile` guards on `isTerminal` instead of its own state list and keeps its defensive return for a terminal occurrence with no terminal event, so it never appends a second terminal event for a divergent row.

## Alternatives considered

- Guard terminal immutability only in Kotlin (`isTerminal` checked in a repository method) — rejected. It protects only callers that go through that method; the schema is the one layer every write passes through.
- Treat equal quiet hours as a full day, or as no window — rejected, as above.
- Keep two nullable parameters and add a runtime check — rejected. It leaves the half-set state representable and the check forgettable.
- Leave `updateState` public and rely on callers also appending an event — rejected. It leaves state and event able to diverge.

## Consequences

- Any write that tries to move a terminal occurrence fails with a constraint error. Phase 2 receivers cannot overwrite a `COMPLETED` or `MISSED` row even by mistake.
- The migration is verified three ways: the Gradle `verifySqlDelightMigration` check fails on the version 2 definitions before the migration exists (observed) and passes after; it covers the trigger (removing it from the migration fails the check, observed); and `SchemaV2Test` applies the migration to a copy of the committed version 1 baseline with seeded data and re-runs every constraint test against that database as well as a fresh one.
- SQLDelight's compiler resolves `old` and `new` in a trigger only in lower case and only inside the trigger body, not in a `WHEN` clause, so the condition is a `WHERE` on the `RAISE` select.
- **Open, not decided here:** `EventLogReduction.adherenceFigures` reads occurrence state, and a backfilled completion never changes the state, so a late completion is not reflected in any adherence figure. The documents say the `COMPLETED` / `COMPLETED_BACKFILLED` distinction exists for a doctor, but do not say how the figures show it. That needs a decision before Phase 6 reporting.
