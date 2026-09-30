# 0005. taskType carries no behaviour; criticality does

Date: 2026-09-29
Status: Accepted

## Context

It's tempting to hardcode that medicines are more important than food reminders — `taskType == MEDICINE` implies `CRITICAL`, say. That reasoning is exactly the kind of dose/medical judgment this app is not allowed to make, and it's also factually wrong for many users: an iron supplement might matter more to one woman than a prescribed medicine does to another.

## Decision

`taskType` (`MEDICINE`, `SUPPLEMENT`, `MEAL`, `FOOD`, `CUSTOM`) is a default source and a display icon only. It carries no behaviour. All escalation, budget, and grace-window behaviour reads `criticality`, which the user sets herself.

## Alternatives considered

- Deriving criticality from taskType with an override — rejected, since it re-introduces exactly the implicit "medicine matters more" judgment call this is meant to avoid, just with an escape hatch. A default that most users never touch is still a default that encodes the judgment.
- Removing taskType entirely and using criticality alone — rejected, since taskType is still useful as a display icon and a sensible default for onboarding's starter schedule; the point is that it stops there.

## Consequences

Any future contributor who proposes "if taskType is MEDICINE, treat it as CRITICAL automatically" is proposing to reopen a decision that was made deliberately, and this ADR is the record of why that was rejected.
