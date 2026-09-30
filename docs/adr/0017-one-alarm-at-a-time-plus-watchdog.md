# 0017. One alarm armed at a time, plus a WorkManager watchdog

Date: 2026-09-29
Status: Accepted

## Context

The natural implementation pre-arms every upcoming alarm for the next N days. With twelve occurrences a day and a four-rung ladder, that's 48 alarms a day, and Samsung is reported to cap scheduled alarms per app in the region of 500 — a week of pre-arming would approach or exceed that ceiling on some devices, and a hit ceiling means new alarms silently fail to schedule.

## Decision

Arm exactly one alarm at a time: the next pending escalation rung across all occurrences. Re-arm the next one on each fire. A `WorkManager` periodic job at the 15-minute floor verifies the correct next alarm exists and repairs it if not, logging `WATCHDOG_REPAIR`.

## Alternatives considered

- Pre-arming a rolling window of alarms (e.g., the next 48 hours) — rejected due to the Samsung alarm-count ceiling risk, and because it multiplies the request-code space unnecessarily for no benefit given the ladder is already sequential.
- No watchdog, relying on the OS to always deliver `setAlarmClock` — rejected; OEM kill behaviour and edge cases (this is a real Android app-killing landscape, not a hypothetical) mean a single dropped fire without a watchdog silently ends all future reminders for that occurrence chain.

## Consequences

The watchdog converts "one lost alarm" into "one late alarm" instead of "the reminder chain is silently dead." This is judged the single most important reliability decision in the architecture, which is why it gets its own ADR rather than being folded into a general alarm-subsystem note.
