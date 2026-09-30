# 0021. Backend exists solely as a server-side dead-man switch

Date: 2026-09-29
Status: Accepted

## Context

A device-only implementation of caregiver alerts can't work in exactly the case it exists for: if her phone is dead, in a drawer, or out of battery, the device itself cannot tell anyone she missed a dose. Building a server "for sync" or "for backup" as a general-purpose feature would be scope creep without addressing this specific gap.

## Decision

The server's entire justification is detecting silence from the device as the signal itself — a dead-man switch. It stores expected occurrences with deadlines and sweeps for ones that never got a completion event, notifying the caregiver when that happens.

## Alternatives considered

- No server at all, relying on the device to push caregiver notifications directly (e.g., via a peer-to-peer or best-effort background push) — rejected; this cannot handle the phone-is-dead case, which is the actual failure mode caregiver alerts need to cover.
- A general-purpose sync/backup server with caregiver notification as one feature among several — rejected as scope creep; every other server feature (multi-device sync, cloud backup) is explicitly out of scope for v1, and building general infrastructure "while we're at it" contradicts the phase-scoping discipline.

## Consequences

Every backend design decision (durable sweep over in-memory timers, dual clock authority, 90-day retention) exists to serve this one narrow purpose well, rather than to serve a general sync platform adequately. This keeps the server's scope small and its correctness properties easy to state and test.
