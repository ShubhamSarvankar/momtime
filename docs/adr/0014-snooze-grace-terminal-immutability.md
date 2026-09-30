# 0014. Snooze/grace policy and terminal-state immutability

Date: 2026-09-29
Status: Accepted

## Context

Without bounds, snoozing could push a dose indefinitely, and without a grace window a pending occurrence would stay open forever with no clear point at which it becomes a missed dose for reporting purposes. Once an occurrence is resolved, later edits to its template (a time change, a criticality change) shouldn't be able to reach back and alter it.

## Decision

Snooze: 10 minutes default, maximum 3 per occurrence, never past the next occurrence of the same template. Grace window after which a pending occurrence becomes `MISSED`: `CRITICAL` 2 hours, `STANDARD` 4 hours, `GENTLE` same local day. `COMPLETED`, `SKIPPED`, and `MISSED` are terminal and immutable — template edits never touch them, and late completion after grace is written as `COMPLETED_BACKFILLED`, not `COMPLETED`.

## Alternatives considered

- Unlimited snoozing — rejected; it lets a critical dose drift indefinitely, which defeats the point of criticality tiers.
- Allowing template edits to retroactively adjust terminal occurrences (e.g., "recompute whether this was actually late" after changing `timeOfDay`) — rejected; it would silently rewrite a doctor-facing history based on an unrelated later edit, which is worse than leaving stale data alone.

## Consequences

Reports are stable once an occurrence resolves, regardless of what happens to the template afterward. The distinction between `COMPLETED` and `COMPLETED_BACKFILLED` preserves the difference between "done on time" and "done late, self-reported," which a doctor can use and a single merged status could not.
