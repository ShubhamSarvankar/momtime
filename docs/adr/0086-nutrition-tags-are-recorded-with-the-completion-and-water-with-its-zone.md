# 0086. The two reductions: nutrition tags are recorded with the completion, and water with the zone it was logged in

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by the Phase 3 planning session, for review. It follows ADR 0040 (attribution, effect time, `asOf`, the outcome of an occurrence) and ADR 0052 (event columns are checked per event type), and it is part of shared schema version 6 (ADR 0079).

## Decision

**Nutrition.**

1. **A completion records the tags the template had when she completed it.** `COMPLETED` and `COMPLETED_BACKFILLED` events carry `EventPayload.Completion(nutritionTags)`, stored in a new sparse column `event.nutrition_tags` (the tag names joined by commas in enum order, or null for none), allowed on those two types alone by the decode guard. `OccurrenceActionCommand` reads the template's tags inside the same transaction as the transition. Editing a template's tags therefore changes what later completions count as and never what she already did. A backfill records the tags at the time it is entered. A row written before version 6 has null and counts under no tag; no released build wrote a tagged completion before it.
2. **`NutritionReduction.servings(occurrences, events, asOf)` is pure, in `shared`, and returns the count per tag per scheduled local date.** An occurrence counts if its outcome as of `asOf` is completed under ADR 0040's rule (the counted terminal event with the latest effect time wins, ties prefer completion), and it counts once under each tag of the completion event that won. It is attributed to the occurrence's scheduled `localDate`. Per week figures are sums of days by the caller's week boundaries. Counts only: no quantity, no target, no percentage.
3. **`COMPLETED_BACKFILLED` counts**, on the scheduled date, from when it is entered, as in ADR 0040. **`MISSION_VERIFIED` and `MISSION_BYPASSED` are not completions** and count nothing by themselves: they are not terminal events and ADR 0040 ignores them. Whichever phase builds missions writes `COMPLETED` beside them, with its tags, and that is the event this reduction reads. `SKIPPED`, `MISSED` and `WITHDRAWN` count nothing.

**Water.**

4. **`WATER_LOGGED` records the zone it was logged in.** `EventPayload.Water(waterMl, zone)`, stored in a new sparse column `event.zone_id` (an IANA id), allowed on `WATER_LOGGED` alone; a `WATER_LOGGED` row without it fails decoding, and none exists. This is invariant 9's form: an instant and a zone, never a formatted local time.
5. **`WaterReduction.totals(events, asOf)` is pure, in `shared`, and returns millilitres per local date**, each event attributed to the local date of its `deviceTimestamp` in the zone it was logged in, counted if its `deviceTimestamp` is at or before `asOf`. A glass she drank at 23:30 in Mumbai belongs to that Mumbai day for ever, wherever she reads the total. "Today" on a screen is the current date in the current zone, which the caller passes.

## Alternatives considered

- **Read the template's current tags in the reduction.** Rejected: editing a tag would rewrite the record of what she did, in a view she may hand to her doctor (Phase 6's report).
- **Snapshot tags on the occurrence at materialisation.** Rejected: the edit command would have to maintain a child table for open occurrences, and a table has no terminal trigger; the completion is the fact being recorded.
- **Attribute water by the device's current zone.** Rejected: past days would change when she travels.
- **A local date column for water.** Rejected: invariant 9.

## Evidence

To be recorded in `docs/phase-3-traceability.md` (PR 5): the reductions' tests and mutations, specified in `docs/phase-3-plan.md`.
