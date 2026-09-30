# 0013. Quiet hours with a CRITICAL override

Date: 2026-09-29
Status: Accepted

## Context

Respecting a user-configured do-not-disturb window is a reasonable default for a reminder app, but a medication reminder that silently defers because it landed at 2am creates a real gap: a nighttime dose simply doesn't ring.

## Decision

A user-configured quiet-hours window suppresses ring-grade delivery for `STANDARD` and `GENTLE`, deferring them to a silent notification. `CRITICAL` occurrences override quiet hours and ring anyway.

## Alternatives considered

- Applying quiet hours uniformly to all criticalities, with an explicit opt-out per template — rejected; it puts the burden on the user to remember to configure the override for exactly the tasks where a silent failure matters most, rather than making safety the default.
- No quiet hours at all — rejected; most reminders genuinely should respect a sleep window, and only critical ones need to override it.

## Consequences

A `CRITICAL` occurrence can wake her at night, which is the intended and necessary behaviour for a medication that must be taken on a schedule. This is why choosing `CRITICAL` correctly (ADR 0005 — it's her call, not the app's inference) matters: it directly controls whether quiet hours get overridden.
