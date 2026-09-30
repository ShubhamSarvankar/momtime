# 0025. Caregiver access is read-only with one-way sync, by construction

Date: 2026-09-29
Status: Accepted

## Context

A caregiver being able to mark a dose complete "on her behalf" sounds like a convenience feature (she's asleep, the caregiver saw her take it) but it opens a real question: whose record wins if the device and the caregiver disagree about what happened, and it introduces a write path a compromised or careless caregiver account could abuse against a medical record.

## Decision

There is no caregiver write path anywhere in the codebase. Sync is one-way: device is the source of truth, server is a mirror plus a detector. A caregiver can only ever read what the device has reported.

## Alternatives considered

- Allowing a caregiver to mark completion with device confirmation required afterward — rejected; it still creates a window where two sources of truth can disagree, and it undermines what makes the event log meaningful (a fact about what *she* did, not what someone else observed or claimed).
- Read-only by convention (UI hides write actions) but a write endpoint exists server-side "for future use" — rejected; an endpoint that exists but is unused by the shipped client is still an attack surface and a design commitment nobody asked for, contradicting invariant 7's "no library/capability for a problem that doesn't exist yet."

## Consequences

There is no conflict-resolution logic to design or test, because there is no path by which a conflict can arise. Revocation (§0007 in `ARCHITECTURE.md`, "hard delete") is safe to implement as a destructive operation precisely because a caregiver never held authoritative data of their own to lose.
