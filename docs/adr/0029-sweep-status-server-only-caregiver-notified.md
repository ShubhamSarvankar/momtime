# 0029. sweep_status as server-only bookkeeping, plus CAREGIVER_NOTIFIED as the one new shared event type

Date: 2026-09-29
Status: Accepted

## Context

`ARCHITECTURE.md` §6.2 described occurrences transitioning to `missed_confirmed` on the server, but the shared event-type list (§3.3) had no `MISSED_CONFIRMED` entry, and the device's own `Occurrence.state` enum has a differently-cased, differently-meaning `MISSED`. A Phase 0 planning review flagged this as a genuine gap: as written, the server produced a status the shared vocabulary never named, which is exactly the kind of ambiguity invariant 5 (platform differences never add to the shared vocabulary) exists to prevent from being decided by accident.

## Decision

`sweep_status` is a server-only column on the expected-occurrence table — `AWAITING`, `SATISFIED`, `MISSED_CONFIRMED`, `SUPERSEDED` — pure sweep bookkeeping that never enters `shared`. It is deliberately a different concept from the device's `MISSED`: `MISSED` is the device's own belief, derived locally (ADR 0030); `MISSED_CONFIRMED` is the server's independent corroboration that the device went silent. They're correctly named differently because they *are* different things, computed by different systems from different evidence.

Exactly one new event type enters the shared vocabulary: `CAREGIVER_NOTIFIED`, written server-side when a caregiver is actually notified and synced back down to the device so the app can show her that it happened. This satisfies invariant 5 because it isn't a platform-capability difference — every platform that talks to the server produces and consumes the same event.

## Alternatives considered

- Promoting `missed_confirmed` to a ninth `Occurrence.state` value shared across device and server — rejected; the device has no way to directly observe "the server didn't hear from me in time," so a shared state the device can't itself derive would be a schema field the device-side domain logic could never legitimately set, which is a modelling smell.
- Adding `MISSED_CONFIRMED` as a shared event type instead of a server-only column — rejected; it's not an event that happens to an occurrence in the domain sense, it's bookkeeping about the sweep's own confidence, scoped entirely to server-side detection logic.

## Consequences

The shared event-type enum grows by exactly one member (`CAREGIVER_NOTIFIED`), which is the honest cost of the caregiver-notification feature. `sweep_status` lives entirely in the server's Postgres schema (Phase 4) and never needs a `shared`-side migration.
