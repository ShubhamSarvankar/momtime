# 0044. A corrupt database is kept aside, and the backup rules

Date: 2026-10-03
Status: Accepted

The onCorruption decision is by Claude (technical review), recorded during Phase 2 orientation; implemented in `phase-2/android-data-wiring`.

## Context

`AndroidSqliteDriver.Callback` inherits `SupportSQLiteOpenHelper.Callback.onCorruption`, which deletes the database file and any attached files. This database holds the device's record of authority (ADR 0003, ADR 0026): the event log, adherence history and the schedule. A default that deletes it on a corruption error destroys that record with no copy and no trace.

Reminders are the product. If the database cannot be opened, the app must still ring whatever it can, so refusing to open is not an option either.

Auto Backup (ADR 0034) needed concrete rules: `android:allowBackup` was never declared in the manifest, and the rules for which files leave the device did not exist. ADR 0031's narrowing note and ADR 0034 settled that the shared database is backed up whole; they could not say which file names, because there was no database file yet.

## Decision

1. **`onCorruption` is overridden** in `MomTimeDatabaseCallback`. The corrupt file is moved aside as `<name>.corrupt` (`momtime.db.corrupt`); only the most recent is kept, a previous `.corrupt` copy is replaced. Its rollback journal, WAL and shared-memory sidecar files are deleted, because they belong to the corrupt database. The framework then opens a fresh database in the original place, so scheduling continues. If the file cannot be moved or copied, it is deleted instead: reminders keep working, and the marker records that nothing was preserved.
2. **A marker records it.** `database-corruption.marker`, in `noBackupFilesDir`, holds the time (from the injected clock) and whether the file was preserved. The reliability view (PR 7) reads it. This PR builds no UI. The marker is outside the database directory, so it is never backed up, and a restore onto another phone does not carry a stale warning.
3. **Journal mode is left at the framework default.** WAL is not enabled. The database stays one file for Auto Backup, with no `-wal` and `-shm` files to copy consistently, and the framework opens no pool of extra read connections (from the framework source; no test asserts the pool size). A test asserts the effective value, so enabling WAL fails a test.
4. **Backup rules are declared and tested.** The manifest sets `allowBackup="true"`, `dataExtractionRules` for API 31 and above, and `fullBackupContent` for API 29 and 30 (the latter is ignored from API 31). Both files include `momtime.db` and `momtime.db-journal` and exclude `momtime.db.corrupt`; `cloud-backup` and `device-transfer` say the same. The journal is included so that a copy taken while a transaction was hot is still recoverable. `BackupRulesTest` parses the XML and the manifest and takes the file names from `DatabaseFiles`, so renaming the database without renaming it in the rules fails. Because the rules name the files they include, any other database file (the android store, PR B) is excluded from backup unless it is added on purpose.
5. **The `.corrupt` copy is covered by the Phase 8 deletion paths.** It holds the same health data as the database. Per record, per category and full account deletion must remove it; `IMPLEMENTATION_PLAN.md` Phase 8 deliverable "Deletion paths verified" covers it.

## Alternatives considered

- **Keep the default `onCorruption`.** Rejected: it deletes the record of authority.
- **Delete the database and start fresh, recording a marker.** Rejected: nothing is gained over moving it aside, and the user's history and her doctor-facing record are lost for good.
- **Refuse to open the database, and run without it.** Rejected by Claude (technical review): reliability wins, and a phone with an unopenable database must still schedule. A fresh database lets the app keep working; the schedule has to be re-entered, and the marker says why.
- **Restore from Auto Backup automatically.** Not built. Restore is a system operation, not something the app can trigger, and a restored database could carry the same corruption.
- **Enable WAL for read concurrency.** Rejected for now: it changes backup (extra files) and the pool size, for no demonstrated need at this volume.

## Consequences

- A corrupt database no longer destroys history. The file can be recovered by hand from the `.corrupt` copy, and a later feature could offer it.
- After corruption the app opens empty. Reminders she had configured are gone from the app until she re-enters them; the reliability view (PR 7) surfaces the marker so she is told.
- Only open-time detection is exercised. The test writes a file that is not a database, which SQLite reports as `SQLITE_NOTADB` and the framework raises as a corruption exception on open. Corruption detected later, in the middle of a query, is a different path in the framework and is not tested here (`MANUAL_CHECKS.md`, P2-8).
- The tests run on the framework's Java code over Robolectric's native SQLite, not on a device.
