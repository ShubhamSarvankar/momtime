# 0003. Append-only event log as source of truth

Date: 2026-09-29
Status: Accepted

## Context

Adherence figures, nutrition aggregates, and the 30-day critical completion metric all need to be defensible to a doctor and immune to the class of bug where a reinstall or a race condition double-counts or silently loses history. A mutable counter model (increment on completion, decrement on undo) can't provide that guarantee cheaply.

## Decision

The event log is the single source of truth. Nothing computed is stored as a counter; adherence, aggregates, and reports are all reductions over the log. See ADR 0026 for the scope of "append-only" once retention and deletion are accounted for.

## Alternatives considered

- Mutable summary tables updated transactionally alongside events — rejected, since it doubles the surface that can drift out of sync and gives no audit trail for how a number was reached.
- Event sourcing with snapshots for performance — rejected as premature; the data volumes here (tens of occurrences a day, at most) don't need snapshotting, and it would add a second thing that could disagree with the log.

## Consequences

Every report is recomputable from scratch, which makes bugs in aggregation logic fixable retroactively rather than requiring a data migration. It costs more read-side computation than a maintained counter would, which is acceptable at this data volume.
