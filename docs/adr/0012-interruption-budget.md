# 0012. Interruption budget as a hard product requirement

Date: 2026-09-29
Status: Accepted

## Context

A user with twelve occurrences a day and a four-rung ladder faces up to 48 potential interruptions daily if nothing caps them. Uninstalls from notification fatigue are a known failure mode for exactly this category of app, and it would defeat the product's purpose if the reliability engineering worked perfectly and she uninstalled anyway.

## Decision

A daily cap on ring-grade interruptions. When exceeded, `STANDARD` and `GENTLE` downgrade to silent notifications for the rest of the day (calendar day in current zone — see ADR 0032). `CRITICAL` is never budget-limited, because it is never allowed to be silent.

## Alternatives considered

- No cap, relying on users to tune quiet hours and per-channel settings themselves — rejected; the product requirement is to protect users who never touch settings, not just power users who do.
- Capping `CRITICAL` too, with an override switch — rejected outright; a medication reminder that can silently fail to ring because a budget was exhausted is a safety regression, not a UX nicety.

## Consequences

`STANDARD`/`GENTLE` tasks can silently downgrade to notifications-only on a busy day, which is the intended tradeoff — better than uninstall, and she still sees the notification. It does mean the domain layer needs a budget-tracking field in schema v1 (`budgetDate` + count, ADR 0032) from day one.
