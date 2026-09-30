# 0015. Dismissal is not completion

Date: 2026-09-29
Status: Accepted

## Context

The easiest implementation swipes the alert away and calls that "handled." That conflates "she made the ringing stop" with "she took the medicine," which are very different facts, and the difference is exactly what a doctor needs from this app.

## Decision

Stopping the alert (swipe, back button, screen off) does not write a `COMPLETED` event. Completion is written only when the user explicitly acknowledges in the app or taps a notification action. If the alert is stopped without acknowledgement, the next escalation rung fires as scheduled, exactly as if nothing happened.

## Alternatives considered

- Treating any interaction with the ring screen as implicit completion — rejected; a user silencing a 3am alarm to go back to sleep is the exact scenario this would misrecord as "took the medicine."
- A middle "acknowledged but not confirmed" state — rejected as unnecessary complexity; the ladder already handles "not yet confirmed" by continuing to escalate, so there's no missing state to fill.

## Consequences

This is the invariant that makes the iOS port viable later (`ARCHITECTURE.md` §4.6) — as long as both platforms respect "dismiss ≠ complete," the domain layer's escalation continuation logic doesn't need to know which platform silenced the alert.
