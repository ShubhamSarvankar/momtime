# 0059. An event's source is `USER` or `SYSTEM`

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the PR 4 prompt, settling the contradiction found in PR 3 and recorded in the Phase 2 progress file. Nothing in the schema or the code changes; `ARCHITECTURE.md` section 3.3 is corrected.

## Context

`ARCHITECTURE.md` section 3.3 listed the event `source` as `USER`, `SYSTEM`, `CAREGIVER`. The enum (`EventSource`) and the `CHECK (source IN ('USER', 'SYSTEM'))` on the `event` table have been `USER, SYSTEM` since Phase 1, and no ADR removed `CAREGIVER`: the word in the architecture dates from the first draft. A document and the code disagreed, and the document was the older one.

## Decision

1. `source` is `USER` or `SYSTEM`. `ARCHITECTURE.md` section 3.3 says so.
2. **`CAREGIVER` is not an event source.** Caregivers are read only, always (`CLAUDE.md`, ADR 0025). There is no caregiver write path anywhere, so no caregiver ever authors an event and no event needs to say that one did.
3. **Whether server written events** (`CAREGIVER_NOTIFIED`, which the sweep writes back to the device's stream, ADR 0029) **need a source of their own is a Phase 4 decision.** They are written by the system, so `SYSTEM` is the natural reading, but the server is a different system from the device, and the caregiver view or a report may want to tell them apart. It is an Open Item in `IMPLEMENTATION_PLAN.md`, to be closed with the server schema, before any server event is written.

## Alternatives considered

- **Add `CAREGIVER` to the enum and the CHECK.** Rejected: it implies a caregiver write path that does not and must not exist, and it is a schema change (a CHECK rebuild on SQLite 3.22) for a value nothing writes.
- **Leave the document and say nothing.** Rejected: the project's documents are public and must be accurate at every commit.

## Evidence

No code changes. `EventSource` has two values and `Event.sq` has `CHECK (source IN ('USER', 'SYSTEM'))`.
