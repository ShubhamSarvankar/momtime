# 0051. Corruption found mid-use ends the process for the shared database and reopens the android store

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the review of PR #15; implemented in `phase-2/capability`. ADR 0049 is not edited. It recorded that after the corruption handler runs in the middle of a query the live process holds a closed database, listed three options, and left the decision open. This is the decision.

## Context

After corruption is found during a query, the handler has closed the database and moved the file aside (ADR 0044). The process still holds the closed driver, and every later repository call throws `IllegalStateException` until a new driver opens (ADR 0049, probed at SDK 29 and 36).

The two databases lose different things when that happens, so they are treated differently.

- **The shared database** is the record of authority and is read by everything: components hold injected repositories, and from Phase 3 the UI will hold SQLDelight query listeners over it. A listener on a closed database does not fail, it goes silent.
- **The android store** (ADR 0048) is read only by our own telemetry and armed record code, which has no listeners, and what it holds is diagnostics and a record the watchdog recomputes.

## Decision

**The shared database: end the process.**

1. When corruption is found in the middle of a query, the handler closes the database, moves the file aside, makes the marker durable (the bytes are synced before the file is renamed into place), and then ends the process through an injected seam, `ProcessEnd`. Production uses `KillOwnProcess`; tests observe the call instead of dying.
2. From PR 5, before it ends the process, the handler also posts the reset notification on the Critical channel (decision 20 in `phase-2-progress.md`: her reminders were reset and need setting up again). That needs notification channels, which arrive in PR 5.
3. Why not reopen or rebuild the graph: both leave stale references. Components hold injected repositories, and the UI will hold query listeners that would go silent rather than fail. A process that ends has no stale state.
4. Ending the process is not a force stop. Alarms and jobs survive it, so the next start, whether from an alarm, the watchdog or the user, opens a fresh database. An alarm that then fires for a lost occurrence is the unknown slot case assigned to PR 3 (decision 21).
5. The accepted cost is that a ring in progress stops. Its occurrence is lost with the database anyway.
6. **Only for corruption found in the middle of a query.** Corruption found on open does not end the process. The framework's own retry opens a fresh database in place of the file just moved aside, so nothing holds a closed database, and ending the process would cut off an alarm that just started the process. The handler tells the two apart by whether the database is open when the handler runs: `isOpen` is false while a database is still being opened and true in use. This distinction is my judgment and is not in the review's wording; the review's decision concerns "a database closed by mid query corruption".

**The android store: reopen, and never fail the alarm path.**

1. The store's holder replaces a closed driver with a fresh one, under a lock: when the handler reports that corruption found in the middle of a query closed the driver, the next call closes that driver and builds a new one. There is exactly one live driver per database at any time.
2. The repositories read the database through the holder on every call and keep no reference to it, so a replaced database is picked up.
3. The process is never ended for the store. Ending it would kill a valid ring over a lost telemetry row.
4. Every android store call on the alarm and watchdog paths is non fatal: no repository call throws. A failed read of the armed record is "record missing", which sends the watchdog to its repair path, and that is acceptable. A failed write returns false. The failure caught is `RuntimeException`, which is what the framework throws (`SQLiteException`, `IllegalStateException` for a closed database); an `Error` still propagates. A constraint violation is also swallowed this way, which hides a bug in the caller as a `false`; that is the price of never stopping an alarm, and it is why the schema's CHECKs are still asserted by the store tests.

## Evidence

- `CorruptionHandlerTest` pins the order: the database is closed first; the process ends only after the marker is readable, the damaged copy is in place and the database is closed; corruption found on open neither ends the process nor reports a closed database; an in-memory database ends nothing.
- `DatabaseCorruptionTest`, mid-query, at SDK 29 and 36: the shared database's process end is called exactly once, with the marker readable and the damaged copy in place at that instant; corruption on open calls it zero times.
- `AndroidStoreTest`, at both SDKs: after corruption found in the middle of a query the read that hit it returns "nothing read" and does not throw, the damaged file is kept byte for byte, the marker is written, the process is not ended, the next write succeeds on a reopened store, there is exactly one live driver (the old one is closed) and two were created in all, and the fresh store holds nothing from before. A separate test closes the store underneath the repositories and shows that none of the six calls throws.

## Limits

- Under Robolectric, on the framework's Java code and a native SQLite library, with the process end replaced by a seam. Whether `Process.killProcess` on a device behaves as assumed (an app process killed this way is restarted by the next alarm, job or launch, and is not marked force-stopped) is read from the platform's behaviour for `killProcess`, not run; it is `MANUAL_CHECKS.md` P2-8.
- The marker is synced to storage, but its directory entry is not (the JVM offers no directory sync). A power loss in the instant between the rename and the process end could lose it. A kill of the process, which is what this does, does not.
- Only corruption detected through the paths the framework routes to the handler is covered (ADR 0049). Damage to the schema, found when a statement is prepared, is not.

## Alternatives considered

- **Reopen the shared database too, or rebuild the Koin graph.** Rejected by Claude (technical review), as above: stale references, and silent listeners from Phase 3.
- **End the process for the store too.** Rejected: it would kill a valid ring over a lost telemetry row.
- **Leave the live process holding a closed database until it restarts.** Rejected: every call in it fails, and an alarm path must not.
- **End the process on corruption found on open too.** Rejected, as above: the framework recovers on its own there.

## Consequences

- A process that finds the shared database corrupt mid-use ends, leaving the file, the marker and, from PR 5, the notification behind. The next start is clean.
- The store can fail and reopen without anything noticing except the telemetry it lost.
- Every component added from PR 3 may assume that a repository it was handed is not a closed database, because a process holding one no longer exists.
