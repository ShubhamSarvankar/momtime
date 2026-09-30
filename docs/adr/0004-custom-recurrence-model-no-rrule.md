# 0004. Custom narrow recurrence model, no RRULE

Date: 2026-09-29
Status: Accepted

## Context

Recurring reminders need a way to express "daily," "specific weekdays," and "every N days." RFC 5545 (RRULE) is the standard answer and the one every calendar library reaches for, but it is a general-purpose grammar built for calendaring software, not for a small fixed set of medication/supplement schedules.

## Decision

A narrow sealed-interface model — `Daily`, `Weekly(daysOfWeek)`, `EveryNDays(n, anchorDate)` — covers every schedule shape a doctor actually prescribes. No RRULE parser anywhere in the codebase. The engine expands any of these into concrete instants rather than handing a rule to the platform.

## Alternatives considered

- Adopting an RRULE library — rejected. It would be a large, mostly-unused dependency (invariant 7) pulled in to parse a grammar that supports monthly-by-weekday, yearly, and dozens of other patterns this product will never offer, and RRULE's own edge cases (BYSETPOS, COUNT vs UNTIL interactions) are a maintenance burden with no product payoff.
- Hand-writing a general rule interpreter "for future flexibility" — rejected per the working-style rule against designing for hypothetical future requirements; if a genuinely new recurrence shape is needed later, it's a new sealed-interface case and a schema migration, not a parser.

## Consequences

Adding a new recurrence shape is a deliberate, reviewable schema change rather than an emergent capability of a general grammar. The engine emitting concrete instants (not rules) is also what lets iOS pre-schedule its own ladder later without needing to reimplement RRULE expansion in Swift.
