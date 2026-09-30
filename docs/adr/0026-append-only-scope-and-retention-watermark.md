# 0026. Append-only scope: operational vs. administrative deletion, and the retention watermark

Date: 2026-09-29
Status: Accepted

## Context

CLAUDE.md invariant 4 states "the event log is append only... nothing updates or deletes an event," while `ARCHITECTURE.md` separately describes a 90-day server-side retention hard-delete, an immediate hard delete of a revoked caregiver's mirrored data, and user-initiated deletion paths required for DPDP compliance. Read literally and without qualification, these directly contradict each other, and a Phase 0 planning review surfaced this as a real error rather than a misreading — four separate places in the docs describe deleting event rows while one invariant says never.

## Decision

"Append only" governs **operational mutation**: no domain-layer code path ever issues an `UPDATE` or `DELETE` against an event as part of a state transition, and nothing mutates a counter. Retention expiry, caregiver revocation, and user-initiated erasure are **administrative** paths that sit entirely outside the domain layer, and they may hard delete rows. This is safe because the device log is the record of authority and a server-side row is only ever a mirror — purging a mirror row loses nothing that matters, and deleting data at the user's explicit request is supposed to remove it from aggregates. No tombstones anywhere.

The one consequence this creates: a hard-deleted event's id was also its outbox idempotency key. A late outbox retry for an event the server has already purged would otherwise look like a new event and get re-inserted, silently resurrecting deleted data. The server keeps a **retention watermark** — an outbox write for an event with `deviceTimestamp` older than the watermark is acknowledged and discarded, never re-inserted. Device outbox entries expire at the same boundary so they stop retrying.

## Alternatives considered

- Tombstoning every deletable row instead of hard-deleting, to make "append only" literally true even administratively — rejected; the device log is already the record of authority, so a tombstoned mirror row buys no correctness benefit and adds a filtering burden to every query for no purpose given nothing downstream reads a purged server row.
- No retention watermark, accepting that a very late outbox retry against a purged event might re-insert it — rejected once identified; this is a genuine data-integrity gap (a "ghost" event reappearing after being deleted for retention or compliance reasons) that a wrong-idempotency-key edge case would otherwise reintroduce silently.

## Consequences

CLAUDE.md invariant 4 is rewritten to state this split explicitly. Every future deletion feature (per-record, per-category, full-account) is an administrative-path feature by construction, and any code path outside that explicit administrative boundary that deletes or mutates an event is a bug, not a stylistic choice.
