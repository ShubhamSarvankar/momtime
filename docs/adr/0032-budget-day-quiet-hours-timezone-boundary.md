# 0032. Budget day and quiet-hours boundary semantics under timezone change

Date: 2026-09-29
Status: Accepted

## Context

The interruption budget (ADR 0012) and quiet hours (ADR 0013) are both defined per "day" or "window," but neither `ARCHITECTURE.md` nor the schema specified what a "day" means for a traveller, or what should happen to an in-progress budget window or quiet-hours evaluation when the user crosses a timezone boundary mid-day. A Phase 0 planning review flagged this as schema-relevant and genuinely open, not a detail to leave implicit.

## Decision

The budget "day" is the local calendar date in the **current** zone, stored from schema v1 as `budgetDate` (a `LocalDate`) plus a count. Quiet hours evaluate against current-zone wall clock as well. A timezone change may shorten or lengthen that day's effective budget window or quiet-hours window; this is accepted as correct behaviour, since pinning either to a zone she has already left is worse — a budget that never resets because it's still tracking a departed timezone's midnight is a worse failure mode than a slightly short or long day while travelling.

## Alternatives considered

- Pinning the budget/quiet-hours day to the timezone active when the occurrence's template was created — rejected; it produces a budget that drifts out of sync with her actual local day the moment she travels, silently working against the local-context reasoning both features exist for.
- Using UTC calendar days uniformly to avoid timezone-boundary edge cases entirely — rejected; it would make the budget reset and quiet-hours window fire at arbitrary, non-intuitive local times depending on her timezone, defeating the purpose of both being "local day" concepts in the first place.

## Consequences

Both `budgetDate` and the quiet-hours window need to be recomputed against current zone whenever evaluated, not cached against a stale zone. This is directly exercised by golden scenario 8 (timezone travel) and is schema-relevant because `budgetDate` is a stored field from v1, not a derived-on-the-fly value.
