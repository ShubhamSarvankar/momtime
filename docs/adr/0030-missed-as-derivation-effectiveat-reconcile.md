# 0030. MISSED as a derivation: effectiveAt vs. deviceTimestamp, and Reconcile as a domain command

Date: 2026-09-29
Status: Accepted

## Context

Golden scenario 5 requires "a grace window expiring while the app is closed, producing `MISSED` without a ring." Neither `ARCHITECTURE.md` nor `IMPLEMENTATION_PLAN.md` originally named the mechanism that writes that event if no process is running at the exact grace-expiry instant. A Phase 0 planning review identified this as a real gap with a direct consequence for the schema: whichever mechanism is chosen affects what an event's `deviceTimestamp` actually means, and therefore the accuracy of every adherence figure computed from it.

## Decision

`MISSED` is treated as a **derivation**, not an observation: a pure function of an occurrence, its grace window, the absence of a terminal event, and the current instant — never something that has to be "caught" at the exact moment it becomes true. Its event payload carries two timestamps. `deviceTimestamp` is the ordinary write time, exactly as every other event has — it can lag the real miss if the app was closed when grace expired. `effectiveAt` is the *computed* grace-expiry instant, the moment the occurrence actually became `MISSED`. Adherence figures and reports read `effectiveAt`; audit and sync read `deviceTimestamp` as usual.

The domain exposes this as an idempotent `Reconcile` command. Three call sites dispatch it — the `WorkManager` watchdog pass, app foreground, and boot — and all three are harmless to run redundantly, since the domain derives and emits the event only if it isn't already recorded. None of the three callers mutate occurrence state directly; they dispatch the command and the domain decides, which is invariant 3 working exactly as intended, not an exception to it.

## Alternatives considered

- Requiring an active process at the exact grace-expiry instant to write `MISSED` (e.g., a per-occurrence alarm fired purely for reconciliation) — rejected; this reintroduces the alarm-count-ceiling problem (ADR 0017) purely to observe a fact that can be computed lazily just as correctly whenever it's next needed.
- Writing `MISSED` with only `deviceTimestamp` and accepting that adherence figures might be off by however long the app stayed closed — rejected once identified; this would silently degrade adherence accuracy specifically for the users least likely to open the app promptly, which is a bad property for a medical-adherence record to have.

## Consequences

Reconciliation timing has no effect on adherence accuracy — golden scenario 5 explicitly asserts this by comparing adherence figures computed after reconciliation at the exact expiry instant, an hour later, and a day later, and requiring them to be identical. This is the one place in the schema where an event legitimately needs two timestamps instead of one, and it's deliberate, not an inconsistency.
