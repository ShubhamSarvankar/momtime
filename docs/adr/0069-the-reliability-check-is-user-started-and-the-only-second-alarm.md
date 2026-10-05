# 0069. Real fires are the measurement; the one second alarm is the check she starts

Date: 2026-10-05
Status: Accepted

Decided by Claude (technical review) in the review of the PR 7 design report: the measurement, the dropped daily canary, the narrowed invariant, the request code and its tests, and the single writer of the check's result. The construction (the runner, its timeout, the screen it is reached from) is the implementing session's, for review. It supersedes the daily silent canary of `ARCHITECTURE.md` section 5.10 and narrows CLAUDE.md's "one alarm armed at a time" and invariant 10. It builds on ADR 0017 (one alarm at a time), ADR 0053 (the one entry point), ADR 0048 (what android keeps) and ADR 0058 (the watchdog). ADR 0017 and ADR 0053 are not edited.

## Context

`ARCHITECTURE.md` section 5.10 planned two canaries: a 60 second test in onboarding, and a daily silent alarm at a quiet hour whose scheduled against actual time was the passive evidence that reminders arrive. CLAUDE.md says one alarm is armed at a time, and that `PendingIntent` request codes derive from `alarmSlot`. A canary is a second alarm, and has no occurrence and so no slot. The design report for PR 7 set out the options (a second `setAlarmClock` alarm, a canary folded into `ensureArmed`, an inexact canary, real fires only) and what each measures.

## Decision

1. **Real fires are the measurement.** Every reminder that fires takes exactly the path a reminder takes, so its latency (the rung against `ALARM_FIRED`) is the evidence, and a rung that was armed ahead and never fired is the headline failure (ADR 0070 defines both). Nothing is armed to measure it.
2. **The daily silent canary is dropped.** On a day with no reminders it would be the only armed alarm and would show on her lock screen as a "next alarm" at a quiet hour. It would also need scheduling logic beside the reminder chain, for measurement on days when nothing of hers depends on delivery. Days with no real fire are **unobserved**, and the report says so (`daysWithFires`). Option B, folding a canary into `ensureArmed`, was rejected in the review: it puts a canary in the critical path, and reliability wins.
3. **The check she starts stays.** From the ring screen's idle state she can start a test alarm 60 seconds out and watch it. It is her action and she is looking at the phone, so a lock screen display is irrelevant. It is the **only permitted second alarm**, and it is cancelled when the check ends, by firing or by timing out.
4. **The invariant is narrowed.** One *reminder* alarm is armed at a time (ADR 0017 holds for reminders); the check she started is the only permitted second alarm. It is armed through the same mechanism a reminder would use (`setAlarmClock`, or the inexact call without exact capability) so that it measures that path. The check is deferred while a reminder is due within 2 minutes of its due time (`CheckPolicy.NEAR_REMINDER`), so the two never sit side by side closely enough to be confused.
5. **Request code 0, a named exception to invariant 10.** The check has no occurrence and no slot. Its `PendingIntent` has the constant request code 0, its own action (`com.momtime.android.action.RELIABILITY_CHECK`) and its own receiver component (`CheckReceiver`, not exported). It is safe because the slot counter starts at 1, so no reminder has code 0, and because the action and component also differ, so nothing `ensureArmed` arms, replaces or cancels can name it. Tests, each mutation checked: the slot counter never yields 0; the check's identity is its own in action, component and code; arming the check leaves the reminder's `PendingIntent`, trigger and armed record unchanged; `ensureArmed` never cancels the check.
6. **The check's result has one writer: `CanaryRunner`.** It records `CANARY_RESULT` with `actualAt` when the alarm fires, and with `actualAt` null when the check times out (3 minutes after it was due, `CheckPolicy.TIMEOUT`). If the process died mid check, the result is written the next time the screen opens, by `settleOverdue`. The watchdog plays no part, and `ensureArmed` never knows the check exists. A fire after the check timed out is ignored, and a check is settled once, whoever races to it (a conditional update on the pending row). A check whose alarm cannot be armed never ran, is removed from the history, and writes no result.
7. **`CANARY_RESULT` is written only by the check.** It carries `Canary(scheduledAt, actualAt?)`, no occurrence, and changes no state. The tier the check was armed under is android's (`reliability_check`, ADR 0048).

## Alternatives considered

- **A second `setAlarmClock` canary every day.** Rejected by Claude (technical review): the lock screen display, the second armed alarm beside the reminder chain, and measurement only on days nothing of hers depends on.
- **A canary folded into `ensureArmed` (the earliest of reminder or canary is armed).** Rejected by Claude (technical review): one alarm stays armed, but a canary bug could then delay a reminder.
- **An inexact canary.** Rejected: it measures only the inexact path (Tier 1), which says nothing about the exact path a reminder takes.
- **A reserved `alarmSlot` for the check.** Not chosen: it would need an occurrence or a pseudo row in the shared schema for the sake of one platform's test alarm (invariant 5). The constant code with a component of its own is the smaller change.
- **The watchdog settles a timed out check.** Rejected by Claude (technical review): two writers of one result.
- **A daily canary only on days with no real fire.** Rejected by Claude (technical review): it is the same scheduling logic and the same lock screen display.

## Consequences

- `ARCHITECTURE.md` section 5.10 and CLAUDE.md's alarm rules are updated in the same commit. The plan's Phase 2 deliverable "daily silent canary" is removed, by Claude (technical review).
- `MANUAL_CHECKS.md` item 1: the soak procedure must include a reminder after the low use period, because real fires are now the only passive evidence. P2-31 holds what the check cannot show without a device.
- Nothing here has been run on a device.
