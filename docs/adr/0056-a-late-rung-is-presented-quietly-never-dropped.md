# 0056. A late rung is presented quietly, never dropped

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the PR 4 prompt and revised in its review, which corrected the first form of the rule (below); implemented in `phase-2/workers`. It extends `ARCHITECTURE.md` section 4.5, which stated a catch up rule for boot only, and builds on ADR 0030 (`MISSED` is a derivation) and ADR 0053 (selection reads the fired record).

## Context

Section 4.5 says that an occurrence found late at boot rings only inside a 30 minute catch up window: "Nobody wants a 7 AM alarm at 11 AM." The watchdog, an alarm that Doze deferred, and boot all discover late rungs. A watchdog delayed by Doze, or a phone that was off, could find a rung hours overdue and ring it. That is the outcome the window exists to prevent.

The first form of the rule, in the PR 4 prompt, was: a rung beyond the window does not ring, and selection moves on to the next. It was implemented, and applied at fire time too, and that showed the flaw: a Tier 1 alarm that Doze defers by more than 30 minutes was dropped, and she got nothing for an occurrence still inside its grace. The window exists so that nothing rings hours late, not so that a reminder disappears.

## Decision

**The window decides how a late rung is presented, never whether it is delivered. It is decided at fire time and nowhere else.**

When a rung fires (`AlarmFireHandler`):

1. The occurrence is terminal: nothing is written and nothing is delivered. `Reconcile` is dispatched first, so an alarm that fires after the end of grace finds the occurrence `MISSED` and delivers nothing, and does not arm itself again.
2. The rung is within 30 minutes of its own instant (inclusive): `ALARM_FIRED` is written and the rung is delivered as `NORMAL`, which means as policy says: a ring, or silent under quiet hours or the interruption budget (decision 15, PR 5).
3. The rung is beyond the window and the occurrence is still within grace: `ALARM_FIRED` is written and the rung is delivered as `SILENT_NOTICE`: a notification with no ring, no vibration and no heads up. Under ADR 0053 `ALARM_FIRED` is the record of what has fired, so the rung is consumed and the next selection does not offer it again.

The watchdog and boot make no catch up decision. They arm the earliest rung that has not fired, for now, through `ensureArmed`, whatever its lateness, and the fire path decides when it fires. A worker never delivers anything itself.

`MISSED` is exactly what ADR 0030 derives: `Reconcile`, at the end of grace, with `effectiveAt` the grace expiry. Beyond the window means no ring, not an early `MISSED`. `ARCHITECTURE.md` section 4.5 said an occurrence found late "goes straight to `MISSED`", which contradicted ADR 0030, and is corrected.

Several occurrences overdue at once (boot after a night with the phone off) each get their own silent notice. That is intended. Rungs of one occurrence that are overdue fire one after another, each armed for now, and each is presented by its own lateness.

`CatchUp` in `shared/engine` holds the window and the boundary, as a pure function. Presentation is a delivery matter and lives in `android` (`Presentation`, on the rung handed to the delivery port).

## Why the fired record is still a count

An earlier draft of this ADR kept the record of fired rungs as the timestamps of `ALARM_FIRED`, read by a walk over the ladder that skipped rungs it judged too late, because under the first form of the rule a stale rung was skipped without a fire. Under this rule nothing is skipped without a fire: every rung is armed and fires, in order, and the fire path is always for the first rung that has not fired. So the number of `ALARM_FIRED` events is exactly the number of rungs consumed, and the count of ADR 0053 is exact. The walk, and the timestamp list it needed, are gone, and `NextRungResolver.remaining(ladder, firedCount, channels)` is what production calls again. This is the implementing session's reading of the review's "ALARM_FIRED consumes every earlier rung": earlier rungs have already fired by construction, so one event consumes one rung. It is for review. It also keeps golden scenario 14 simple: a fired rung cannot come back, because selection reads no clock at all.

## Alternatives considered

- **The first form** (beyond the window: no ring, move on). Rejected by the review: a deferred Tier 1 alarm vanishes.
- **A rung beyond the window is `MISSED` at once.** Rejected: it contradicts ADR 0030, and she may still take the dose inside grace.
- **The watchdog decides** (delivers a notice itself, or skips). Rejected: two places would decide, and a worker would deliver.
- **Timestamps and a walk** (above). Not needed under this rule.

## Evidence

`CatchUpTest` (the boundary, both sides), `StaleFireTest` (`an alarm that fires beyond the window is a silent notice`: the notice is handed over once as `SILENT_NOTICE`, `ALARM_FIRED` consumes the rung and the next is armed, and the repeat rung that is within the window is `NORMAL`; the boundary is inclusive for presentation; an alarm after the end of grace delivers nothing and leaves the occurrence `MISSED`; several overdue occurrences each get a notice), `WatchdogTest` (`a rung beyond the window is armed for now too and the watchdog decides nothing`; `MISSED is derived at the end of grace and not before`), and `ArmingSelectionTest` (selection by the fired count; golden scenario 2 through it). Mutations are in `phase-2-traceability.md`. Boot wiring is PR 6, which calls the same selection and the same fire path.
