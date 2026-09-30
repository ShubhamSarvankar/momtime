# 0016. setAlarmClock as primary delivery, setExactAndAllowWhileIdle as Tier 2 fallback only

Date: 2026-09-29
Status: Accepted

## Context

Android offers several ways to schedule a time-sensitive wake, and they behave very differently under Doze. `setExactAndAllowWhileIdle` looks like the obvious choice by name, but it's throttled to roughly one fire per app per nine minutes in deep Doze — which cannot sustain a 5-minute escalation ladder rung-to-rung.

## Decision

`AlarmManager.setAlarmClock()` is the primary delivery mechanism; the system does not adjust delivery time for these and delivers them even in low-power modes. `setExactAndAllowWhileIdle` is a Tier 2 fallback only, used when the device/permission state can't support `setAlarmClock`, not a substitute for it.

## Alternatives considered

- Using `setExactAndAllowWhileIdle` as the primary mechanism, since it doesn't show a persistent "alarm set" system icon the way `setAlarmClock` does — rejected; the throttling under deep Doze makes it unable to meet the 60-second SLO reliably, which is the one thing that can't be compromised.
- WorkManager expedited/periodic work as the primary trigger — rejected; WorkManager provides no guarantee of exact-time firing and is explicitly not designed for user-visible-at-a-precise-instant alerts.

## Consequences

The app shows a persistent alarm-clock icon in the status bar while an alarm is armed, which is a minor UX cost worth paying for delivery reliability. Tier 2/Tier 1 exist specifically for the permission-revoked case, not as an equally-good alternative.
