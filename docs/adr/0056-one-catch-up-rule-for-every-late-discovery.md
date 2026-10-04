# 0056. One catch up rule for every late discovery

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the PR 4 prompt; implemented in `phase-2/workers`. It extends `ARCHITECTURE.md` section 4.5, which stated the rule for boot only, and builds on ADR 0030 (`MISSED` is a derivation) and ADR 0053 (selection reads the fired record).

## Context

Section 4.5 says that on boot an occurrence whose time has passed fires late only inside a 30 minute catch up window, and otherwise goes to `MISSED` without ringing: "Nobody wants a 7 AM alarm at 11 AM." The watchdog (PR 4) discovers late rungs too. A watchdog delayed by Doze, or by a phone that was off, could find a rung hours overdue and ring it. That is the outcome the boot rule exists to prevent, arrived at by another route.

## Decision

1. **One rule, one pure function.** `CatchUp` in `shared/engine` holds it. A rung is within the window if `at - rung <= 30 minutes`. The boundary is inclusive: a rung exactly 30 minutes overdue still rings, one millisecond more does not. A rung not yet due is within the window. The watchdog and boot both reach it through `ArmingSelection`; nothing else in the code compares a rung with the time.
2. **A rung found overdue within the window** is armed for now through `ensureArmed`, and the alarm path rings it when the alarm fires. A worker never starts the ringer.
3. **A rung beyond the window does not ring.** Selection moves on to the next rung that is still ahead or within the window. If none remains, nothing is armed, and `Reconcile` derives `MISSED` at the end of the occurrence's grace (ADR 0030), with `effectiveAt` the grace expiry.
4. **The window is measured from the rung's own instant**, not from the occurrence's scheduled instant. Section 4.5 says "scheduledInstant has passed" and was written for the first rung. A repeat rung at +5 minutes is 30 minutes late at +35, and is measured that way, so the rule is the same for every rung and every caller.
5. **The window applies when an alarm fires, too.** The fire path asks the same selection what is expected, as of the time of the fire. An alarm that fires more than 30 minutes after its rung (a Tier 1 inexact alarm deferred by Doze for that long) is a leftover: it writes nothing and delivers nothing, and the pass that follows arms whatever is next. This is a consequence of one rule for every late discovery, not a separate choice, and it is the part of the decision with a cost: a Tier 1 reminder delayed by more than half an hour is dropped, not rung. It is recorded in `MANUAL_CHECKS.md` (P2-14) so the soak shows how late a real inexact alarm runs.

## How the record of fired rungs stays consistent with skipped rungs

ADR 0053 reads the next rung from the count of `ALARM_FIRED` events. A skipped rung has no such event, so a count alone drifts: with the first rung skipped and the second fired, a count of one would offer the second again. The record needs no new event type and no new column (invariants 2 and 5). It is the `deviceTimestamp` of each `ALARM_FIRED`:

- `CatchUp.remaining` walks the ladder once. Each recorded fire consumed the first rung that was still within the window at the instant it fired, and any rung before it that was not was skipped without ringing. After the last fire, rungs that are too late as of now are skipped too.
- It is a function of the log and of the current time, and the time decides only which unfired rungs are too late. A fired rung never comes back whatever the clock says (golden scenario 14). A clock set backward can make a rung that was skipped look ahead again, and it then rings once, inside its window; it cannot bring back a rung that fired.
- When every fire was on time, the walk equals the count. The normal case is unchanged.

`ArmCandidate` carries `firedAt: List<Instant>` instead of `firedCount: Int`, and `ArmingSelection.next` and `expectedFor` take `now`. `NextRungResolver.remaining(ladder, firedCount, channels)` is no longer called by production code: golden scenario 2's Phase 1 tests still assert it, and removing it is left for a later cleanup, so that this PR does not rewrite a Phase 1 test. `NextRungResolver.globalNext` is still the cross-occurrence choice.

## Alternatives considered

- **Count the skipped rungs as fired by writing `ALARM_FIRED` for them.** Rejected: the event says an alarm fired, and it did not. The reliability view and the server would read it as a ring.
- **Write a new event for a skipped rung.** Rejected: a schema and domain change to record what the log already implies, and invariant 5 says to be careful with new event types.
- **Different windows for boot and the watchdog.** Rejected by the review: two numbers would drift, and the reason for the rule is the same.
- **Exempt the fire path** (an alarm that actually fired always rings). Considered: it keeps a Tier 1 reminder that Doze delayed by 40 minutes. Rejected because the consumed rung then has to be recorded, which needs the record above to carry information it does not hold, and because one rule is the decision of the review. Revisit if P2-14 shows inexact alarms regularly running more than half an hour late.

## Evidence

`CatchUpTest` (the boundary both sides, the walk, a skipped rung staying skipped, a fired rung not returning when the clock goes back), `ArmingSelectionTest`, and through the watchdog `WatchdogTest`: `a rung overdue within the window is armed for now and the worker does not ring it`, `a rung exactly at the boundary is armed and one millisecond later it is not`, `a stale rung is not rung and the occurrence ends MISSED`. Mutations are in `phase-2-traceability.md`. Boot wiring is PR 6, which calls the same selection.
