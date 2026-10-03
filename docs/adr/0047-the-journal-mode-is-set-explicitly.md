# 0047. The journal mode is set explicitly, to a rollback journal

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the review of PR #14; implemented in `phase-2/telemetry-split`. ADR 0044 is not edited; its item "journal mode is left at the framework default" is superseded by this ADR.

## Context

ADR 0044 left the journal mode at the framework default and asserted it was not WAL. The test could only assert what Robolectric showed (`memory`), and the device default was read from source. Android 9 introduced a compatibility WAL mode for databases that choose no journal mode, so the question was whether it applies on the SDKs we ship to.

Read from AOSP at android-10 (API 29), 11, 12, 13, 14, 15 and 16 (API 36):

- **It does not apply by default at any tag.** One global setting decides it: `SQLiteDatabase`'s constructor adds `ENABLE_LEGACY_COMPATIBILITY_WAL` when `SQLiteCompatibilityWalFlags.isLegacyCompatibilityWalEnabled()` is true. That reads `Settings.Global.SQLITE_COMPATIBILITY_WAL_FLAGS`, parsed as `legacy_compatibility_wal_enabled`, default `false` (identical from android-10 to android-15; android-16 adds a catch block). `db_compatibility_wal_supported` does not exist in `config.xml` at any of the seven tags. With no WAL, the framework applies `db_default_journal_mode` (`TRUNCATE`) and `db_default_sync_mode` (`FULL`).
- **It only takes effect for an app that has set neither a journal mode nor a sync mode** (`isLegacyCompatibilityWalEnabled()` requires `journalMode == null && syncMode == null`).
- **`SQLiteOpenHelper.setWriteAheadLoggingEnabled(false)`, which androidx's `FrameworkSQLiteOpenHelper` always calls, does not defeat it**: it removes the flag from the builder, and the `SQLiteDatabase` constructor adds it back when the global setting is on. `SQLiteDatabase.disableWriteAheadLogging()` clears both WAL flags and reconfigures the pool.
- **Unverified:** whether Google, Play services or an OEM sets `legacy_compatibility_wal_enabled=true` on production devices, and whether an OEM overrides `db_default_journal_mode`.

So on a stock device the default is a rollback journal, but the value depends on a global setting and a platform default the app does not control.

## Decision

Both databases (the shared database and the android store) set the mode in `onConfigure`, through one function, `useRollbackJournal`: `disableWriteAheadLogging()`, then `PRAGMA journal_mode=TRUNCATE` run through `query()` with the cursor consumed (the cursor is lazy and only executes the pragma when read). The effective mode is a rollback journal whatever the global setting or platform default is.

Why a rollback journal, and not WAL: under WAL the newest transactions sit in a separate `-wal` file that the backup rules do not include, so a restore could lose them (ADR 0044).

Details read from the same sources, which shaped the code:

- `onConfigure` runs after the framework has applied its journal configuration to the connection (`SQLiteConnection.open` calls `setJournalFromConfiguration` inside `openDatabase`, and `SQLiteOpenHelper` calls `onConfigure` after), so the explicit setting is the last word.
- A user-issued `PRAGMA journal_mode` is not special-cased by the framework: it goes straight to SQLite on the connection that runs it, and the framework re-applies its own configured mode only if a connection is opened or reconfigured. With one connection and no WAL change, that does not happen.
- `disableWriteAheadLogging()` throws if a connection is acquired while the mode changes. In `onConfigure` only the primary connection exists.

## Evidence

`AndroidDriverConfigurationTest` asserts `journal_mode` is `truncate` on both databases. Robolectric's unset default is `memory`, so removing the setting fails the test (it would also fail on `wal`). That is a real mutation, not a tautology: the assertion is for a value the framework does not produce on its own under test.

## Limits

- Robolectric shows the framework Java code on a native SQLite library, not a device. Whether a particular device overrides `db_default_journal_mode` or pushes the compatibility WAL setting is `MANUAL_CHECKS.md` P2-6, now reduced to confirming that the app's own pragma wins on the Galaxy A15.
- If the framework reconfigures a connection later, it applies its default, not ours. Nothing in the app causes that today.

## Alternatives considered

- **Leave the default and assert it.** Rejected by Claude (technical review): the value would still depend on a setting the app does not control.
- **`OpenParams.Builder.setJournalMode`.** Not reachable through androidx's `SupportSQLiteOpenHelper`; using it means writing a custom open helper factory, which is more code and a new surface for no extra guarantee.
- **WAL.** Rejected, as above.

## Consequences

- The journal mode no longer depends on a default. A change to it is a change to this function and fails a test.
- The explicit mode adds one pragma per open.
