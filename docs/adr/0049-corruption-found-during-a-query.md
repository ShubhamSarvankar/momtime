# 0049. Corruption found during a query, and what it leaves behind

Date: 2026-10-03
Status: Accepted

ADR 0044 is not edited. It exercised open-time detection only and listed mid-query corruption as unverified. This ADR records what was read and shown, and a consequence it exposes.

## Context

ADR 0044 moves a corrupt database aside when the framework calls `onCorruption`. The framework calls it in two situations, and only the first was tested.

Read from AOSP at android-10 to android-16:

- **On open:** `SQLiteDatabase.open()` catches `SQLiteDatabaseCorruptException`, calls `onCorruption()` and retries `openInner()`.
- **During use:** `SQLiteQuery.fillWindow` (every cursor from `query` and `rawQuery`) and `SQLiteStatement.execute`, `executeUpdateDelete`, `executeInsert`, `simpleQueryForLong`, `simpleQueryForString` and `simpleQueryForBlobFileDescriptor` catch `SQLiteDatabaseCorruptException`, call `onCorruption()` and rethrow. `execSQL`, `insert`, `update`, `delete` and `DatabaseUtils.longForQuery` go through `SQLiteStatement`.
- **Not covered by the framework:** the `SQLiteProgram` constructor (statement preparation), which has no catch, and `SQLiteRawStatement` (android-16).
- androidx's `FrameworkSQLiteOpenHelper` wires `Callback.onCorruption` through a `DatabaseErrorHandler` into the framework helper.

## Decision

1. **The handler is the same for both paths**, and it closes the database before it moves the file aside. `CorruptionHandlerTest` makes that observable on any file system: a recording database notes whether the file is still in place at the instant `close` is called, so moving first fails the test even on Linux, where a file can be renamed while open.
2. **Mid-query corruption is tested** (`DatabaseCorruptionTest`): the database is opened and filled, then the second half of the file and the file change counter are overwritten (so the open connection does not trust cached pages), then a query is run. At SDK 29 and 36 the query fails with `SQLiteDatabaseCorruptException: database disk image is malformed (code 11 SQLITE_CORRUPT)`, which reaches the caller once, and the handler keeps the damaged file as `momtime.db.corrupt` byte for byte and writes the marker.
3. The damage leaves the schema and the first page alone. Damage to the schema is found when a statement is prepared, which the framework does not route to the handler (above); that path is not covered here.

## What it exposes: the live process is left holding a closed database

After the handler has closed the database, the running process still holds the driver. A probe at both SDKs, after a mid-query corruption in the same process:

- the next read fails with `IllegalStateException: attempt to re-open an already-closed object: SQLiteDatabase`;
- the next write fails with `IllegalStateException: Cannot perform this operation because the connection pool has been closed`;
- the fresh database is not created until a new driver opens the file.

So the corrupt file is safely kept and the next process start opens a fresh database, but until then every repository call in the live process throws. For reminders that is the case that matters: an alarm receiver, a worker or the ringer service that hit corruption and must still ring. Nothing here fixes it, deliberately: the fix is a design decision for the components that exist from PR 3 on. The options, recorded so the decision is made with them in view:

- rebuild the Koin graph (and so the driver) when a repository call finds the database closed;
- let the shared database holder reopen its driver when the old one is closed, which needs the repositories to read the database through the holder rather than hold it;
- end the process after the handler runs, so the next entry (an alarm, a job) starts clean, at the cost of cutting off whatever the process was doing, such as a ring in progress.

Any of them also needs a test, and an alarm path that rings from what it already has in memory is better placed than one that must read the database first. This is raised for decision before PR 3.

## Limits

- Under Robolectric, on the framework's Java code and a native SQLite library. The file move is tested on the JVM file system, not the app's private storage on a device (`MANUAL_CHECKS.md` P2-8).
- Only damage to later pages was exercised, not every way a file can be corrupt.

## Alternatives considered

- **Catch the exception in each repository and rebuild.** Not built here: it is the first of the options above and belongs with the components that need it.
- **Leave mid-query corruption untested and keep the manual check.** Rejected: it could be triggered under Robolectric, so it was.

## Consequences

- Corruption found mid-use no longer destroys the file, and the handler's close-before-move order is pinned.
- A known gap is recorded with its options, and is not described as handled.
