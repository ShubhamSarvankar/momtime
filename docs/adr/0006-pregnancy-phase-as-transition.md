# 0006. Pregnancy phase as a state transition, not an end-of-life event

Date: 2026-09-29
Status: Accepted

## Context

A pregnancy has a due date, and due dates pass. The naive model treats week 40 as the end of the app's usefulness for that record — archive it, stop tracking, done. But postpartum medication, supplement, and hydration reminders for a breastfeeding mother are plausibly a longer tracking window than the pregnancy itself, and a user may have several pregnancy records over time.

## Decision

`PregnancyPhase` (`PRENATAL` or `POSTPARTUM`) is present from schema version 1. Ending a pregnancy is a phase transition on the same record, not an end-of-life event on the account. Adherence history, templates, and occurrences stay scoped by `pregnancyId` across the transition.

## Alternatives considered

- Archiving the pregnancy record at week 40 and starting a fresh "postpartum tracking" entity — rejected, since it fragments history that a doctor would want to see continuously and duplicates scoping logic that already exists for `pregnancyId`.
- Not modelling phase at all, treating postpartum as just "a pregnancy with a due date in the past" — rejected, since due-date-in-the-past has legitimate other causes (a revised due date, a weeks-overdue pregnancy) and postpartum needs its own UI treatment (§3.5), not an inferred one.

## Consequences

The app's data model doesn't need a special case for "pregnancy is over," which simplifies every query that would otherwise need to branch on due-date-vs-now. It does mean phase transitions need their own tested behaviour (golden scenario 19) so a transition can't accidentally rescope or drop history.
