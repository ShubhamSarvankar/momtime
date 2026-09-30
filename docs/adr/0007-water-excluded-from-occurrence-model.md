# 0007. Water excluded from the occurrence/adherence model entirely

Date: 2026-09-29
Status: Accepted

## Context

Water intake looks superficially like a scheduled task — "drink a glass at 10am" — and modelling it as an `Occurrence` with a template would reuse existing materialisation and alarm machinery for free.

## Decision

Water is not an occurrence. It's a separate table: a daily goal in millilitres plus `WATER_LOGGED` events, with an optional nudge schedule capped at a small number per day, never alarm-grade, never escalating. Water is excluded from the adherence percentage entirely.

## Alternatives considered

- Modelling water glasses as low-criticality occurrences — rejected, since a missed glass of water would then count toward "missed" in the same adherence figures as a missed medication dose, which conflates a hydration nudge with a medical adherence record and produces a false sense of missed doses.
- Tracking water with no goal or nudges at all, purely a log — rejected, since a goal and gentle nudges are useful without needing occurrence/escalation machinery; the point is decoupling from alarms and adherence, not from all structure.

## Consequences

Water logging stays simple and low-stakes, with no interaction with the interruption budget or the escalation ladder. It does mean water needs its own small set of queries and UI rather than inheriting the occurrence machinery, which is a deliberate, small amount of duplication in exchange for correctness of the adherence numbers.
