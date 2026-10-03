# 0046. The app runs in one process

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the review of PR #14; implemented in `phase-2/telemetry-split`.

## Context

ADR 0045 showed that a contending materialisation run waits in the Android framework's connection pool (`SQLiteConnectionPool.waitForConnection`), and that a second driver on the same file is refused with `SQLiteDatabaseLockedException: database is locked (code 5 SQLITE_BUSY)` after the framework's busy timeout. The pool is a Java object, so it serialises within one process only. A component running in another process opens its own driver and its own pool, and meets the refusal.

So "the app stays in one process" became a condition of a claim the project makes (`ARCHITECTURE.md` section 3.2: overlapping runs wait, they are not refused). Nothing enforced it. It holds today only because no component declares a process. It matters from PR 4, when WorkManager's components and their libraries merge into the manifest, and later for the ringer service, which is the natural candidate for a separate process to isolate it from crashes.

## Decision

1. Every component of the app runs in the app's default process. No `android:process`, and no `android:isolatedProcess="true"` (an isolated service runs in its own process), on any component, including components merged in from libraries.
2. `verifySingleProcess`, with its fixture self-test, fails the build if the merged manifest declares either. It reads the processed output (`merged_manifests/<variant>/process<Variant>Manifest/AndroidManifest.xml`) for debug and release, not the source manifest, because a library can declare a process the app never wrote. It fails closed if a merged manifest is missing. XML comments are removed before matching, since the merged manifest carries explanatory ones. It runs in the `verify-android-structure` job.
3. `ARCHITECTURE.md` section 3.2 states the constraint next to the serialisation claim it supports.

## Alternatives considered

- **Allow several processes and make them cooperate.** Rejected: it needs a cross-process protocol for the database (a content provider that owns it, or WAL with its backup consequences, ADR 0047), and the claim ADR 0045 supports would have to be re-shown across processes. None of it buys anything for a single-user app with a handful of components.
- **A separate process for the ringer service.** Rejected for now. If it is ever wanted it needs its own ADR, because it would reopen this one.
- **Rely on review.** Rejected: the case that breaks it is a library's manifest, which review does not read.

## Consequences

- The check passes trivially today and starts to matter when WorkManager merges in (PR 4). It is proven on fixtures: a service, a provider, a receiver, an isolated service and the application element are each caught, and a manifest with only a comment and look-alike attributes (`android:processOwner`, `isolatedProcess="false"`) is not.
- It reads the manifest. It cannot see a process chosen some other way, but an Android component's process can only be chosen in the manifest.
- Anything that needs another process has to supersede this ADR.
