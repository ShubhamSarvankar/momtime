# 0053. One "ensure armed" entry point, selection from the fired record, and the fire path

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the PR 3 prompt; implemented in `phase-2/arming`. It builds on ADR 0017 (one alarm at a time), ADR 0031 (`alarmSlot`, one per occurrence), ADR 0050 (tiers from capability) and ADR 0048 (device state is android's).

## Context

Several things need the next alarm to exist: a fired alarm, the watchdog (PR 4), boot and the foreground (PR 6). If each armed alarms itself there would be four implementations of selection, capability resolution and the armed record, and a fix to one would miss the others.

## Decision

**One coordinator, one operation.** `ArmingCoordinator.ensureArmed()` is the only thing that arms an alarm. In one pass it resolves capability, reads the pending occurrences, selects the next rung, arms it, and keeps the armed record. Capability is read at the start of every pass and never remembered between passes.

**The port.** `AlarmScheduler` in `shared` has `arm(slot, at)` and `cancel(slot)`. It says nothing about tier or mechanism and mentions no platform type. The Android implementation arms through `setAlarmClock` when exact alarms are allowed (Tier 3 and Tier 2) and through `setAndAllowWhileIdle` with `RTC_WAKEUP` when they are not (Tier 1). An instant that has passed is armed for now, never in the past.

**Selection** (decision 14, golden scenario 14):
- Only the channels the device delivers, `RING` and `RING_REPEAT`, are eligible. The caller passes the set to `NextRungResolver` (invariant 6: the engine does not know what a platform does with a rung). The filter runs before the earliest rung is chosen, and before the fired rungs are counted, so a caregiver rung never delays another occurrence's ring and never shifts the count. This is the shared change: `NextRungResolver.remaining` and `globalNext` each take a `channels` parameter, and `ArmingSelection` (pure, in `engine`) combines them.
- The rung that is next is read from the record of rungs that fired, which is the count of `ALARM_FIRED` events for the occurrence. It is never a comparison of a rung's instant with the time, so a clock set backward cannot bring a fired rung back.
- Two rungs at the same instant are ordered by occurrence id, then by their position in the ladder, so the choice never depends on the order the occurrences were read in. When the first has fired, the second is armed immediately.

**One alarm across occurrences.** Request codes are per occurrence (invariant 10: the code is `alarmSlot` and nothing else), so arming another occurrence's rung does not replace the alarm already armed. When the head moves, the previous alarm is cancelled by its slot, read from the armed record. After every arming operation the platform holds exactly one alarm.

**`ALARM_SCHEDULED`** is appended when a rung is first armed or the expected rung changes, never on a refresh (decision 6). "First armed or changed" is judged against the armed record. A lost record therefore writes one extra event, and the alternative, a record in the shared log, would put device state there (invariant 5).

**The fire path.** The receiver is declared `exported="false"`, is reached only by an explicit component intent, takes `goAsync()`, runs the work on a bounded executor and calls `finish()` in a `finally`. The `PendingIntent` is `FLAG_IMMUTABLE` and `FLAG_UPDATE_CURRENT`, its request code is the slot, and the slot and the rung's instant also travel as extras, because a receiver cannot see the request code. The handler resolves the slot to an occurrence and then:
- an unknown slot (after a reset or a restore, decision 21), a terminal occurrence, or a rung that is not the one expected next writes nothing and delivers nothing;
- the expected rung appends `ALARM_FIRED`, is handed to a delivery port, and is the rung that is counted from then on.

In every case, and also when something above throws, `ensureArmed()` runs afterwards, so a stale fire cannot end the chain. `ALARM_SCHEDULED` and `ALARM_FIRED` change no occurrence's state (invariant 3). Delivery is a recording port until PR 5.

**A refused exact alarm.** `setAlarmClock` can throw `SecurityException`. The scheduler then resolves capability again and arms through the resulting tier. If the platform still reports exact capability, the refusal is trusted over the report and the alarm is armed inexactly: an inexact alarm that fires is better than an exact one that never does. The next pass reads the platform afresh.

## Alternatives considered

- **Each caller arms for itself.** Rejected: four copies of selection and of the capability read.
- **Put the tier in the port.** Rejected by the review: the port deals in slots and instants, and the tier is delivery.
- **Cancel every pending slot but the selected one, instead of the previous one from the record.** More robust to a lost record, but the review specified the record, and a leftover alarm is handled by the stale fire path, which writes nothing and arms the next rung.
- **Compare rungs with the time to find the next.** Rejected: scenario 14.

## Limits

Robolectric's `ShadowAlarmManager` never throws `SecurityException` for an exact alarm, so the refusal is a seam in the test. A snoozed occurrence is not selected: arming a snooze belongs to the ring actions in PR 5. `setAlarmClock` takes a show intent, and there is no activity yet, so it is null (`MANUAL_CHECKS.md` P2-11). What a device does with any of this is `MANUAL_CHECKS.md`.
