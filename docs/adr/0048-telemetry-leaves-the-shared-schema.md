# 0048. Delivery telemetry leaves the shared schema; the android store

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review). Supersedes, in part, ADR 0033 (question 3, the telemetry split into a shared table) and ADR 0034 (the acceptance of `alarm_delivery_telemetry` being backed up). Neither is edited. Implemented in `phase-2/telemetry-split`.

## Context

Phase 1 put `alarm_delivery_telemetry` in the shared schema, with `resolved_tier`, `screen_on`, `audio_focus_obtained`, `battery_pct` and `doze_state`, a domain type `AlarmDeliveryTelemetry` and a repository. ADR 0033 accepted it to keep telemetry migrations off the narrow `event` table, and ADR 0034 accepted that it would be backed up with the database.

CLAUDE.md invariant 5 says platform capability differences live only in delivery and presentation and never appear in the schema, the domain vocabulary or the event log. The delivery tier and the state of the device are exactly such differences. CLAUDE.md wins over an ADR. It was found in Phase 2 orientation: the payload types were clean, but the schema and the domain were not.

Every check passed while it stood. `CapabilityBoundaryTest` used `AlarmDeliveryTelemetry` as its positive control: it demonstrated that a type holding a `DeliveryCapability` can be flagged, and never asked whether a stored type held one. The scan covered the engine and the rung types and nothing that is written to a database.

## Decision

1. **The table is dropped, in schema version 3** (`2.sqm`). Nothing needs it:
   - Per fire timing is derivable from the log: the rung's instant against `ALARM_FIRED`'s `deviceTimestamp`.
   - `alarm_slot` is on the occurrence.
   - The canary's scheduled and actual instants describe the `CANARY_RESULT` event, so they become its payload.
   - Everything else (tier, screen on, audio focus, battery, Doze) is Android's and moves to the android store.
2. **`EventPayload.Canary(scheduledAt, actualAt?)`** is the payload of `CANARY_RESULT`, stored as two nullable columns on `event` (`ADD COLUMN`, which works on the 3.22 floor, ADR 0042). `actualAt` is null for a canary never seen to fire. Both are platform neutral instants. Before the drop, the migration copies the canary instants from the telemetry rows onto their events. The old form still decodes: a `CANARY_RESULT` with no instants anywhere decodes as `EventPayload.None`.
3. **The android store** is a second SQLDelight database owned by the android module, `AndroidStoreDatabase`, in the file `momtime_android.db`, generated into `com.momtime.android.store.db`. It has two tables:
   - `fire_telemetry`: the resolved tier, screen on, audio focus, battery, Doze state, the watchdog repair flag and the boot count, keyed by the shared event id. There is no foreign key across the two files: a row may outlive or precede its event.
   - `armed_alarm`: at most one row, the alarm this app believes it armed (slot, rung instant, when, boot count, whether exact alarms were allowed). It is state about the operating system, and a restored phone has armed nothing.
   It uses the same SQLite floor and dialect as the shared database (the 3.18 dialect, and `verifySqliteFloor` covers its `.sq` files), a committed baseline snapshot (`android/src/main/sqldelight/databases/1.db`) with migration verification in the `migration-test` job (`verifyDebugAndroidStoreDatabaseMigration`), and the same driver configuration: foreign keys enforced, the explicit rollback journal (ADR 0047), and corruption moved aside with its own marker (ADR 0044, ADR 0049). It has its own driver and holder, one driver per file, exposed through Koin as repositories only.
4. **It is never backed up.** The backup rules are an allow-list that includes only the shared database and its journal, so the store is out by not being included. Lint rejects an exclude that is not under an include, so the store has no exclude of its own; `BackupRulesTest` pins the include list and checks that no include is a prefix of a store file name.
5. **No reset notification when the store is corrupt** (ADR 0044 and PR 5 notify when the shared database is). Losing the store loses diagnostics and the armed record, and the watchdog arms the alarm again within its pass. Nothing she configured is lost. The store's corruption is recorded by its own marker for the reliability view.
6. **Local records are always kept; the telemetry opt-in gates upload only** (decision of Claude (technical review) from Step 1). The opt-in setting (`telemetry_opt_in`) stays in the shared `app_settings`, because it is a preference and not a capability. Onboarding copy says what is kept on the device, and the Phase 8 per category deletion covers the store.
7. **The boundary test looks at what is stored.** `CapabilityBoundaryTest` now scans the stored types (the event, its payloads, the occurrence, the template) and the shared `.sq` files for capability and device state columns, with a positive control that plants each.
8. **The structural check** confines the store's generated types: its queries only in its repository package (`com.momtime.android.store`), its database only there and in the DI package, its row classes only in the repository package, and the shared database's generated types are still banned from android by package.

## Alternatives considered

- **Keep a slim shared table** with the platform neutral columns. Rejected: nothing needs it once the canary instants are a payload, and a table that exists for no consumer encodes a guess.
- **Keep the table and document it.** Rejected: it contradicts invariant 5 and the document would be an apology for it.
- **Device state in the event payload.** Rejected: it puts platform capability into the log.
- **A hand written `SQLiteOpenHelper`, or `SharedPreferences`, for the store.** Rejected by Claude (technical review): SQLDelight gives compile checked SQL, the same dialect floor and the same migration verification.
- **A JSON blob.** Rejected for the reasons in ADR 0033: a blob lets the effective schema change with no migration.
- **The store in the shared database file with a separate backup rule.** Rejected: backup rules exclude files, not tables (ADR 0031's narrowing note), and putting it in the same file is the violation.

## Consequences

- The shared schema and domain hold no platform capability or device state. The boundary test would catch one being added.
- Telemetry upload in Phase 5 reads the android store and joins by event id.
- Retention and erasure paths (Phase 4 and Phase 8) must cover the android store as well as the shared database.
- The migration is verified from every prior version: `SchemaV3Test` migrates a version 2 database and a version 1 database, with foreign keys on and ending in `foreign_key_check`, and `AndroidSchemaTest` does the same on the Android driver.
- The `1.db` snapshot for the android store is a committed baseline like the shared one (ADR 0035) and is regenerated only deliberately.
