# 0018. PendingIntent request codes derived from the alarmSlot monotonic column, never a hash

Date: 2026-09-29
Status: Accepted

## Context

`PendingIntent` request codes are `Int`s that must uniquely identify an alarm within the app. A natural shortcut is hashing an occurrence/rung id into an int. Hash collisions are rare but not impossible, and when one occurs, one alarm silently overwrites (cancels) another's `PendingIntent` — with no error, no crash, and no way to reproduce it on a bench, since it depends on the specific ids involved.

## Decision

Request codes derive from the `alarmSlot` monotonic integer column, never from a hash of ids. `alarmSlot` is `Int`, allocated monotonically per install from a counter row (ADR 0031).

## Alternatives considered

- Hashing a UUID or composite key into an int — rejected for the collision risk above, which is exactly the kind of bug that passes every test and fails silently in production.
- A `Long` composite key encoding occurrence id and rung index — rejected; `PendingIntent` request codes are `Int`, so this would need truncation, reintroducing the same collision risk one level removed.

## Consequences

Request-code uniqueness becomes a property of a monotonic counter rather than a probabilistic hash property, which is exactly the kind of guarantee that's cheap to test deterministically (Robolectric, Phase 2) and cannot silently regress.
