# 0011. Escalation increases channel reach, not intensity

Date: 2026-09-29
Status: Accepted

## Context

The obvious escalation model for a reminder app is a ramp: start quiet, get louder/more intrusive over time if unacknowledged. For a medication reminder that model is backwards — by the time a ramp would reach "urgent," a critical dose could already be dangerously late.

## Decision

A `CRITICAL` occurrence rings full-screen, full-volume at t+0. Escalation increases *reach* (who and what gets notified — repeat ring, then caregiver info, then caregiver urgent) rather than *intensity* of the same channel ramping up over time.

## Alternatives considered

- A volume/intrusiveness ramp starting gentle and building to full-screen alarm — rejected, since it delays the one thing (full-screen, full-volume alert) that actually gets a critical reminder acknowledged, in exchange for being less startling, which is the wrong tradeoff for a medication reminder.
- Escalating both reach and intensity simultaneously — rejected as unnecessary complexity once t+0 already rings at full intensity for `CRITICAL`; there's nothing louder to escalate to on the same channel.

## Consequences

`CRITICAL` reminders are maximally intrusive immediately, which is uncomfortable by design and is the entire point of the criticality tier. `STANDARD` and `GENTLE` get gentler starting points precisely because they aren't making this tradeoff.
