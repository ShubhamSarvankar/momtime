# 0043. Foreign keys are enforced on every driver, and migrations end with a foreign key check

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) during Phase 2 orientation; the JVM half is implemented in `phase-2/sqlite-parity`, the Android half lands with the Android driver factory in `phase-2/android-data-wiring`.

## Context

The schema declares eight foreign keys, all `REFERENCES` with no `ON DELETE` or `ON UPDATE` clause (so `NO ACTION`): `schedule_template.pregnancy_id`, `schedule_template_nutrition_tag.template_id`, `due_date_revision.pregnancy_id`, `water_goal.pregnancy_id`, `occurrence.template_id`, `event.occurrence_id` (nullable), `outbox_event.event_id`, `alarm_delivery_telemetry.event_id`.

SQLite enforces none of them unless a connection sets `PRAGMA foreign_keys = ON`. Measured on SQLDelight 2.4.0 with xerial sqlite-jdbc 3.53.4.0, in memory and on a file: `PRAGMA foreign_keys` returns 0, an orphan insert succeeds, and `PRAGMA foreign_key_check` then reports it. `JdbcSqliteDriver` passes its properties straight to the JDBC connection and sets no `foreign_keys` itself. Nothing in the project set it. The Android framework default is also off (`SQLiteDatabaseConfiguration.foreignKeyConstraintsEnabled` defaults to false, applied on every connection open), and `AndroidSqliteDriver.Callback` does not override `onConfigure`.

So every Phase 1 test ran with enforcement off, and the declared constraints were decoration. This is the same failure as a coverage exclusion that matched no class (ADR 0039): a configured check that applies to nothing.

Running the whole `:shared:jvmTest` suite with enforcement on: 99 tests, 6 failures, all `SQLITE_CONSTRAINT_FOREIGNKEY`, all fixtures that inserted rows with no parent (five in `DataCoverageTest`, one in `SchemaV2Test`). No production code path failed. The unpatched baseline was 99 of 99.

## Decision

1. **Enforcement is on for every driver the project opens.** On the JVM, every driver is opened through `openJvmSqliteDriver`, which sets `foreign_keys=true` and cannot be overridden by the caller's properties. `JvmDatabaseDriverFactory` and all tests use it. On Android, the driver factory passes a `Callback` whose `onConfigure` calls `setForeignKeyConstraintsEnabled(true)`; `onConfigure` is the only correct place, because the call throws inside a transaction and the framework runs `onConfigure` before `onCreate` and `onUpgrade`. That factory lands in `phase-2/android-data-wiring`; until it lands there is no Android driver, and no document claims one is configured.
2. **The six fixtures are repaired by inserting their parents.** No constraint is loosened and no `ON DELETE` clause is added.
3. **Migration tests run with enforcement on and end with `PRAGMA foreign_key_check`, which must return nothing.** `PRAGMA foreign_keys` is a no-op inside a transaction, and Android runs `onUpgrade` inside one, so a migration that rebuilds a table other tables reference cannot switch enforcement off to do it. The end-of-migration check is what stops such a rebuild from leaving orphans behind. `SchemaV2Test` shows the check can fail: an orphan planted with enforcement off, which the v1 to v2 migration does not touch and does not notice, is reported by `foreign_key_check`, and `migratedFromV1` fails on it.
4. **`EventRepository.deleteById` and `ON DELETE` are left alone.** With enforcement on, `deleteById` fails for an event that still has an `outbox_event` or `alarm_delivery_telemetry` row. The hard-delete paths (retention expiry, revocation, user erasure, invariant 4) are administrative and belong to Phase 4. Whether they delete children first or the schema cascades is a retention and erasure decision made there, with the server mirror in view. It is recorded as an Open Items row in `IMPLEMENTATION_PLAN.md`.

## Alternatives considered

- **Leave enforcement off and rely on the repositories.** Rejected: nothing then protects the schema from a repository bug, and the constraints would stay documentation.
- **Loosen the six fixtures or catch the exception.** Rejected: the fixtures were wrong; they inserted rows no valid database holds.
- **`ON DELETE CASCADE` now.** Rejected: deleting an event cascading into delivery rows is a Phase 4 decision, and a cascade chosen early encodes a guess about erasure.
- **Switch `foreign_keys` off inside a migration to rebuild a table.** Not possible inside a transaction (see 3).

## Consequences

- An orphan row fails at insert, in tests and in the app, instead of surviving silently.
- Migration tests are stricter than before. A future migration that rebuilds `pregnancy`, `schedule_template`, `occurrence` or `event` has to leave the foreign key check clean, and `1.sqm` was safe only because nothing references `app_settings`.
- Retention and erasure code in Phase 4 must delete child rows before an event, or Phase 4 must decide otherwise, before that code can run.
- The driver parity differences that remain (journal mode, busy timeout, transaction start, SQLite version) are listed in `phase-2-progress.md` and are covered by the Android driver tests in `phase-2/android-data-wiring`.
