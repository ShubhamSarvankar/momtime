# 0040. Adherence as a reduction over the event log: attribution, asOf, and backfill

Date: 2026-10-03
Status: Accepted

ADR 0037 is not edited. This ADR closes the question it left open ("how a backfilled completion is surfaced in adherence reporting") and corrects a Phase 1 deliverable that did not match its spec.

## Context

The spec says adherence is a reduction over the log, and that it reads `effectiveAt`:

- `ARCHITECTURE.md` 3.3: "Adherence figures and reports read `effectiveAt`; audit and sync read `deviceTimestamp` as with every other event." and "Adherence percentages, streak equivalents, nutrition tag aggregates and compliance reports are all reductions over this log."
- `IMPLEMENTATION_PLAN.md` Phase 1: "adherence figures (read from `effectiveAt`, not `deviceTimestamp`, for any derived event)".

`EventLogReduction.adherenceFigures` and `criticalCompletionDays` did neither. They counted the occurrence `state` column and read no event field at all. Two consequences:

1. The required property "adherence figures are invariant to reconciliation timing" held by construction. The only test named for it asserted the `effectiveAt` of `Reconcile`'s output and never called a reduction. Replacing `effectiveAt` with `deviceTimestamp` inside the reduction was impossible because the reduction read neither. Golden scenario 5's second case (identical figures after reconciling at expiry, an hour later and a day later) was never asserted.
2. A `COMPLETED_BACKFILLED` event never changes occurrence state (ADR 0037), so a dose she took late was counted as missed.

The spec is silent on which day an event belongs to. For `MISSED` the options were the occurrence's scheduled date or the local date of `effectiveAt`. They disagree: a `GENTLE` miss has `effectiveAt` at the start of the next day, and a `CRITICAL` dose at 23:00 expires at 01:00.

## Decision

Both functions are reductions over `List<Event>` and read no occurrence state.

- **Attribution.** Every event is attributed to its occurrence's scheduled `localDate`. The day a dose was due owns its outcome.
- **Effect time.** An event's effect time is `effectiveAt` for derived events (`MISSED`) and `deviceTimestamp` for user events (`COMPLETED`, `COMPLETED_BACKFILLED`, `SKIPPED`).
- **`asOf`.** Both functions take an explicit `asOf: Instant`. It is a parameter; the reduction never reads a clock (invariant 8). An event counts in a figure as of T only if its effect time is `<= T`. The bound is inclusive, so a daily figure for day D with T at the end of D includes a `GENTLE` miss whose `effectiveAt` is the start of D+1.
- **Outcome of an occurrence as of T.** The counted terminal event with the latest effect time wins. Ties prefer completed, then skipped, then missed. Events of other types, and events with no occurrence, are ignored.
- **Not yet terminal.** An occurrence with no counted terminal event as of T is in none of the three figures, which is how `PENDING` and `SNOOZED` occurrences were always treated. No new category.
- **Backfill.** `COMPLETED_BACKFILLED` counts as completed, attributed to the scheduled date, and is never counted as missed. A backfill takes effect when it is entered: as of a T before it the occurrence is missed, after it completed. The occurrence state stays `MISSED`; the log carries the backfill. Nothing about the trigger or the state machine changes.
- `criticalCompletionDays` counts a day when it has at least one critical occurrence and every one has outcome completed as of T.

Timeliness (on time against late) is a separate question. If a later phase needs an on-time figure it becomes a new figure; it does not distort this one.

## Alternatives considered

- **Attribute `MISSED` to the local date of `effectiveAt`.** Rejected: puts every `GENTLE` miss and every late-night `CRITICAL` miss on the wrong day, and disagrees with the backfill attribution.
- **Count a backfill as missed in the figures, showing timeliness there.** Rejected: an adherence figure that shows a dose she took as missed is wrong data, and showing it to a caregiver is the punitive signal the safety rules exclude.
- **Leave the state-based implementation and fix only the tests.** Rejected: it does not match the spec and cannot reflect a backfill.
- **Read a clock inside the reduction.** Rejected: impure, and untestable without `Clock.System`.

## Consequences

- The previous state-based implementation is kept in test sources as a reference oracle (`StateBasedAdherenceOracle`). A property test asserts that for generated histories with no backfill, and `asOf` at or after every effect time, the log-based figures equal the oracle exactly. The rewrite changes no figure except where backfill is involved.
- The timing property is golden scenario 5's second case. Its generator includes reconcile delays that cross `asOf`; without them the property cannot tell `effectiveAt` from `deviceTimestamp`. Mutation: using `deviceTimestamp` as the effect time for `MISSED` makes it fail.
- Callers must supply events and `asOf`. No production code calls these functions yet; Phase 6 reporting will.
- Phase 1's status previously counted scenario 5 among the passing scenarios with half of it asserted. That is corrected in `IMPLEMENTATION_PLAN.md`.
