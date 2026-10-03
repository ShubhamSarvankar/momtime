# 0045. A contending materialisation run waits in the framework's connection pool

Date: 2026-10-03
Status: Accepted

ADR 0036 is not edited. This ADR replaces the "Driver findings" row for `AndroidSqliteDriver` and its attribution of the wait to `BEGIN IMMEDIATE`, because the experiment ADR 0036 asked for was run and showed a different mechanism.

## Context

ADR 0036 made materialising a window one atomic repository operation, and recorded that whether a contending run waits for the lock or is refused depends on the driver. Its table said `AndroidSqliteDriver` starts transactions with `beginTransactionNonExclusive()`, which the framework implements as `BEGIN IMMEDIATE`, so "a second writer waits". It also said, plainly, that this rested on reading source and had not been run, and `IMPLEMENTATION_PLAN.md` made the run a Phase 2 exit criterion.

It was run in `phase-2/android-data-wiring`, as `MaterialiseRaceTest`, on the production driver factory.

## Experiment

Robolectric 4.17, native SQLite (`@SQLiteMode(NATIVE)`, asserted in every data test), SDK 29 and SDK 36, the production `AndroidDatabaseDriverFactory` through the Koin graph.

- Run A starts `materialiseWindow` for 10 days and pauses inside the `generateId` callback, which the engine calls after reading the existing dates and before inserting. A holds its transaction open on a latch.
- Run B, on another thread through the same repository, materialises 12 days.
- While A holds: after 2 seconds B had not completed, and B's `generateId` had not been called (0 calls), so B had not started its work. B's thread state was `TIMED_WAITING`, and its stack, read from the live thread, was:

  ```
  LockSupport.parkNanos
  SQLiteConnectionPool.waitForConnection
  SQLiteConnectionPool.acquireConnection
  SQLiteSession.acquireConnection
  SQLiteSession.beginTransactionUnchecked
  SQLiteSession.beginTransaction
  SQLiteDatabase.beginTransactionNonExclusive
  SqlDelightOccurrenceRepository.materialiseWindow
  ```

  The same frames, at different line numbers, appeared at SDK 29 and SDK 36.
- After A was released both completed. A created 10 occurrences; B saw A's rows and created only the 2 missing dates. The table held 12 rows with 12 distinct dates and 12 distinct `alarmSlot`s: no duplicate and no partial batch.
- Control: two separate drivers on one file. With A holding, B was refused while A was still paused, with `android.database.sqlite.SQLiteDatabaseLockedException: database is locked (code 5 SQLITE_BUSY)`, and left nothing behind. This is what shows the first test can tell waiting from refusing.
- Mutation: the transaction removed from `materialiseWindow`. B completed while A was paused and the wait assertion failed (see the PR's mutation table).

## Decision

The mechanism is the framework's in-process connection pool, not SQLite's lock under `BEGIN IMMEDIATE`. With one driver on one file and the database not in WAL mode, a writer holds the pool's primary connection for the whole transaction, and a second thread waits in `SQLiteConnectionPool.waitForConnection` before it reaches SQLite at all. `BEGIN IMMEDIATE` is real, and it matters across drivers or processes because it takes the write lock at the start, so contention surfaces at `BEGIN` and not part way through a batch. But it is not what made B wait here.

Consequences for the rest of the design:

1. **One driver per database file is load-bearing**, not a tidy-up. The serialisation exists only within one pool. The Koin graph builds exactly one driver (asserted by test), and `phase-2/android-data-wiring` ties that to this ADR.
2. **All database access stays in one process.** A second process opening the file is a second pool and meets the refusal shown by the control, after the framework's busy timeout. No component may declare `android:process` for database work, and `ARCHITECTURE.md` section 3.2 says so.
3. **WAL stays off** (ADR 0044). With WAL the framework opens a pool of read connections and the evidence above would not apply.
4. `ARCHITECTURE.md` section 3.2 states exactly what was shown and by what mechanism, and does not claim device behaviour.

## Limits of this evidence

- It is the Android framework's Java code (connection pool, session, transaction handling) running on Robolectric's native SQLite library under a JVM, at SDK 29 and 36. It is not a device. The SQLite library is Robolectric's, not the device's.
- The journal mode under Robolectric is `memory`; the device default is `TRUNCATE` (AOSP `config.xml`). That does not change the pool behaviour shown, which is Java code, but it is a difference no Robolectric test can remove (`MANUAL_CHECKS.md`, P2-6).
- B was observed waiting for 2 seconds, not indefinitely. Whether the pool ever times out was not tested.
- Intermediate SDKs (30 to 35) were not run.

## Alternatives considered

- **Leave ADR 0036's wording and add a note to the test.** Rejected: ADR 0036 named the wrong mechanism, and the next reader would take "`BEGIN IMMEDIATE` serialises" as the guarantee when the guarantee is "one pool".
- **Add `BEGIN IMMEDIATE` semantics to a second driver to make it wait.** Not built. The refusal is across pools, and the design keeps one.

## Consequences

- The Phase 2 exit criterion "the materialisation race passes under `AndroidSqliteDriver`, showing a wait rather than a refusal" is met under Robolectric, with the limits above. The device remainder stays in `MANUAL_CHECKS.md`.
- Anything that opens a second driver on the database file, including a test helper, will be refused rather than queued. The positive control is the reminder.
