# 0002. Three-layer domain model

Date: 2026-09-29
Status: Accepted

## Context

The app needs to represent what was configured (a recurring reminder), what is due today (a concrete instance), and what actually happened (a fact). Collapsing these into one mutable record is the natural first instinct and the one that breaks under template edits, backfills, and audit requirements.

## Decision

Keep `ScheduleTemplate` (configuration), `Occurrence` (a materialised instance on a specific day), and the event log (append-only facts) as three distinct layers. Occurrences are terminal (`COMPLETED`, `SKIPPED`, `MISSED`) once resolved and immutable thereafter; template edits never touch terminal occurrences.

## Alternatives considered

- A single `Reminder` table with mutable status fields — rejected, since editing a template would then either silently rewrite history or require ad hoc guards scattered through every edit path, and there would be no natural place for an append-only fact record.
- Deriving occurrences on the fly from templates with no persisted row — rejected, since alarms need a stable row to schedule against (`alarmSlot`, §0018) and a terminal occurrence needs to survive a later template edit unchanged.

## Consequences

Template edits are simple to reason about (only pending/future occurrences move). Adherence reporting is a reduction over facts, not a read of mutable state. The cost is three tables to keep synchronised instead of one, which SQLDelight's generated types and the domain layer's ownership of transitions (invariant 3) are what keep that synchronisation correct.
