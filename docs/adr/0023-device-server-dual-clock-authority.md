# 0023. Device/server dual clock authority

Date: 2026-09-29
Status: Accepted

## Context

The device schedules alarms against its own clock; the server evaluates missed-dose deadlines against its own. If either side simply trusted the other's clock, a user who changes their phone's time (accidentally or deliberately) or a phone with drifted time could produce phantom missed-dose alerts to a caregiver, or mask a genuinely missed dose.

## Decision

Every event records both `deviceTimestamp` (device clock at the moment of the event) and `serverReceivedAt` (set by the server only, on receipt). The server trusts its own clock for the dead-man switch; it never adjusts its deadline evaluation based on a claimed device time. Skew above a threshold surfaces a warning in the app rather than silently correcting for it.

## Alternatives considered

- The server trusting `deviceTimestamp` for deadline evaluation — rejected; this hands control of "was this dose missed" to a clock the server can't verify, which a misconfigured or manipulated device clock could exploit or accidentally trigger.
- Rejecting events with clock skew above a threshold instead of just warning — rejected; a broken clock is a device configuration problem, not a reason to lose the event data entirely (which the append-only log is supposed to preserve, ADR 0003/0026).

## Consequences

A caregiver alert reflects the server's own timeline, which is the timeline the caregiver-notification promise actually depends on. The tradeoff is that a device with significant genuine clock drift needs its own surfaced warning rather than silent server-side compensation, which is the more honest failure mode.
