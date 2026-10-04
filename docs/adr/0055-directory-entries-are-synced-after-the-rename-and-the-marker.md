# 0055. The directory is synced after the rename and after the marker write

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the review of PR #16; implemented in `phase-2/arming`. ADR 0051 is not edited. It made the marker's bytes durable (synced before the rename); this is the rest.

## Context

Once the process is gone the kernel still holds what was written, so process death loses nothing. Power loss is different. A file's bytes are durable once the file is synced, but the entry that names it, a rename or a delete, lives in the directory and is durable only when the directory is synced too. The corruption marker and the `.corrupt` copy are the record that her schedule was lost, so losing the directory entry loses the record.

## Decision

1. After the corrupt database is moved aside (and its sidecar files deleted), the database directory is synced.
2. After the marker is renamed into place, its directory is synced.
3. Both use `android.system.Os` (`open`, `fsync`, `close` on the directory) through a seam, `DirectorySync`. Production uses `OsDirectorySync`.
4. It is best effort: a directory that cannot be opened or synced is not a failure of the handler, which must finish whatever happens, because reminders must keep working.

## Limits

Robolectric cannot observe an `fsync`. The tests show, through the seam, that the directory is synced at the right instants (after the rename, with the copy in place and the original gone; after the marker exists) and that syncing a directory that cannot be synced does not throw. That the call syncs a real directory and survives power loss is a device matter (`MANUAL_CHECKS.md` P2-8).

## Alternatives considered

- **Leave it.** Rejected by the review: the marker is the only evidence of the loss.
- **Sync the whole filesystem (`Os.sync()`).** Rejected: it is slower, and it syncs what the handler does not own.
