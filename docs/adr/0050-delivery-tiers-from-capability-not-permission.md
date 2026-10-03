# 0050. Delivery tiers are resolved from capability, and `setAlarmClock` is the only exact mechanism

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the Step 1 review and confirmed in the review of PR #14; implemented in `phase-2/capability`. Supersedes, in part, ADR 0016 (its sentence that `setExactAndAllowWhileIdle` is a Tier 2 fallback) and the tier definitions of `ARCHITECTURE.md` section 5.8 as written in ADR 0019's table. Neither ADR is edited. CLAUDE.md's alarm subsystem bullet is replaced, with the wording approved in the Step 1 review.

## Context

CLAUDE.md, ADR 0016 and `ARCHITECTURE.md` section 5.1 called `setExactAndAllowWhileIdle` the Tier 2 fallback for when `setAlarmClock` cannot be used. Section 5.8 defined Tier 2 by full screen intent being denied, not by exact alarms. The two did not agree, and the first is wrong.

Read from AOSP, `AlarmManagerService.setImpl`, at android-12 through android-16: on a target of 31 or higher, `setAlarmClock`, `setExact` and `setExactAndAllowWhileIdle` all require `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`, and throw `SecurityException` otherwise. `setAlarmClock` is not exempt. `setAndAllowWhileIdle` and `set` need nothing. The listener-based exact variants need no permission, but their alarms are dropped when the process is cached, so they are useless with the app killed. An app on the power-save allowlist (the user granted the battery optimisation exemption) is exempt from the requirement, and `canScheduleExactAlarms()` returns true for it. So a missing exact alarm capability cannot be rescued by `setExactAndAllowWhileIdle`: it needs the same capability and is throttled to roughly one fire per app per nine minutes in deep Doze besides.

Two more platform facts shape the tiers (read from AOSP, and from the platform docs for the notification case). A full screen intent is not launched when the notification is not posted, and there is no background activity launch exemption for alarm `PendingIntent`s, so with notifications denied the only route to a ring screen is the overlay. A user can also block a single notification channel while notifications stay enabled overall.

## Decision

1. **`setAlarmClock` is the only exact mechanism.** CLAUDE.md now says: "`setAlarmClock` is the only exact mechanism. On API 31 and above it needs the same exact alarm capability as every other exact API, so there is no exact fallback. Exact capability is what `canScheduleExactAlarms()` reports, never a permission check, because a battery optimisation exemption also grants it. Without exact capability the app arms an inexact `setAndAllowWhileIdle` alarm and delivers Tier 1." `setExactAndAllowWhileIdle` has no role.
2. **The inputs** are read by a platform adapter, separately from the resolution:
   - exact capability: `canScheduleExactAlarms()` on API 31 and above, and true on API 29 and 30, where exact alarms need no permission and the method does not exist and is not called;
   - full screen intent: `canUseFullScreenIntent()` on API 34 and above, and below that whether `USE_FULL_SCREEN_INTENT` is declared (it is a normal permission there, granted at install);
   - notifications enabled;
   - battery exemption;
   - overlay permission;
   - whether the Critical channel is blocked. Channels arrive in PR 5, which wires this input. Until then it is read as not blocked, through a seam, and the progress file records it.
3. **Resolution is a pure function** from those inputs to android's own type: a tier, the mechanism that arms the alarm, and presentation flags. Effective full screen intent is the platform's full screen intent, and notifications enabled, and the Critical channel not blocked.
   - **Tier 3:** exact capability, plus effective full screen intent, plus battery exemption. Mechanism `setAlarmClock`.
   - **Tier 2:** exact capability, short of Tier 3. Mechanism `setAlarmClock`. Tier 3 and Tier 2 differ only in presentation.
   - **Tier 1:** no exact capability. Mechanism `setAndAllowWhileIdle`, delivered as plain notifications on the criticality channel, honest in the UI that timing is approximate.
   This redefines Tier 2 from "full screen intent denied" to "exact, short of Tier 3". The combination of exact capability and full screen intent without a battery exemption, which `ARCHITECTURE.md` section 5.8 left in no tier, is Tier 2.
4. **Presentation flags**: full screen intent (exact and effective full screen intent), heads up (exact and notifications delivered), overlay available (exact and the overlay permission, because the overlay route needs the ringer service, which needs an exact alarm to start from), audio only (exact, with none of the three visible), and undeliverable (no exact capability and no notification delivered: the blocking banner and onboarding gate of decision 7 of the Step 1 review).
5. **The shared enum keeps its three values.** The android tier maps to `DeliveryCapability` in one function with its own test. Android knowledge stays in android (invariant 5).
6. **The expected result for every combination is written out as data**, 64 rows (six inputs), not computed by the code under test. The test enumerates all 64 and fails if any is missing from the table or duplicated, so a new input cannot be added without the table being extended.
7. **The manifest declares** `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` with `android:maxSdkVersion="32"` (Android's manifest guidance, decision 8 of the Step 1 review), `USE_FULL_SCREEN_INTENT` and `POST_NOTIFICATIONS`. Requesting them is PR 8. `verifyManifestPermissions` is an exact allowlist over the merged manifest, attributes such as `maxSdkVersion` included, so every permission added later (WorkManager brings some of its own in PR 4) has to come through the list on purpose, because each one changes the Play declarations.
8. **The code never depends on which permission is held**, only on what the platform reports. If Play rules out `USE_EXACT_ALARM`, the fix touches the manifest only (the Open Item written in `IMPLEMENTATION_PLAN.md`).
9. **The plan's Robolectric item** is reworded to "`SecurityException` on `setAlarmClock` resolves capability again and arms through the resulting tier". That test lands in PR 3, with arming.

## Alternatives considered

- **Keep `setExactAndAllowWhileIdle` as a Tier 2 fallback.** Rejected: it needs the same capability, so it can only fail the same way, and it cannot sustain a five minute ladder.
- **Infer capability from the OS version.** Rejected by ADR 0019 and unchanged: an Android 15 device with full screen intent revoked behaves worse than an Android 11 device with everything granted.
- **Check the permission rather than the capability.** Rejected: a battery optimisation exemption also grants exact capability, and `USE_EXACT_ALARM` cannot be revoked, so a permission check gets both wrong.
- **A fourth shared tier for "undeliverable".** Rejected: the shared enum is three values on purpose, and whether anything can be delivered is a presentation matter, shown as a flag and a banner.
- **Compute the expected results in the test.** Rejected: a test that computes its answer with the same rules passes whatever the rules are.

## Limits

- The adapter is tested against Robolectric's shadows at SDK 29, 31, 33 and 36. They show that each input is read from the platform call it should be; they do not show what a real device reports. Inputs with no shadow are read through a seam, named in `MANUAL_CHECKS.md`.
- Whether Play grants `USE_EXACT_ALARM` or the battery exemption request to a medication reminder is not settled (Open Items).
- The Critical channel input is a constant until PR 5.

## Consequences

- A change to the tier rules is a change to the data table and fails until both agree.
- Every capability dependent path reads the resolution, never a permission or a version.
- A new permission in the manifest, including one a library brings, fails the build until it is added to the allowlist on purpose.
