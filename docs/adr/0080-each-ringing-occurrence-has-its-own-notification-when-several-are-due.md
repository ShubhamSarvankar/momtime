# 0080. When several occurrences are due, each has its own notification with its own actions

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by the Phase 3 planning session, for review. It completes ADR 0066, which put buttons only on a notification for one occurrence, and builds on ADR 0060 (the delivery paths), ADR 0062 (one ring session) and ADR 0064 (channels, and no per notification silence).

## What AOSP says, read by tag

Read from `frameworks/base` at the tags `android-10.0.0_r1`, `android-13.0.0_r1`, `android-14.0.0_r1`, `android-15.0.0_r1` and `android-16.0.0_r1`:

- `Notification.MAX_ACTION_BUTTONS` is 3 at every tag.
- A notification that the app put in no group is grouped by the system once the package has `config_autoGroupAtCount` of them: 4 at android-10, 2 at android-13 to android-16 (`core/res/res/values/config.xml`, `GroupHelper`). At android-14 the automatic summary takes the ongoing and no clear flags if any child has them. A notification in a group the app named is left to the app (`GroupHelper.onNotificationPosted`: `isAppGroup`).
- A foreground service's notification gets `FLAG_NO_CLEAR`, and an app's cancel of a group does not cancel a child that is a foreground service's notification (`NotificationManagerService`).
- android-16's `GroupHelper` has a force grouping path for sparse groups and for groups with no summary, behind a flag. It was not read in full; what it does to this design is a device question.

## Decision

1. **Reminders that arrive as notifications already have one each** (Tier 1 and the silent presentations: id is the occurrence's slot, with its offered actions). Unchanged.
2. **A ring session with one occurrence is unchanged:** the session's notification (`RING_ID`, held by the ringer's foreground service) carries that occurrence's actions.
3. **When a second occurrence joins a ring session, the session spreads:** every occurrence in the session gets its own notification (id is its slot, on its criticality's channel, category alarm, content intent the ring screen, its offered actions as buttons, no full screen intent, no delete intent), and the session's notification is rebuilt with no occurrence buttons and with only alert once. `RingSessions` records that the session has spread; it stays spread until it ends, so the buttons never move back.
4. **The invariant:** while an occurrence is ringing, exactly one notification carries exactly the actions the domain offers for it: the session's notification if the session never spread, its own otherwise. Acting on it cancels its own notification and resolves it from the session as today; a refused action refreshes the buttons where they are.
5. **No app group, no summary and no group alert behaviour is set.** Each per occurrence notification alerts as its channel says, because a second reminder that is due is a second reminder; the system's automatic grouping is left to the system. ADR 0064's reasoning stands: an effect that shows only on a device is not used as a mechanism.
6. **The session's notification keeps the full screen intent** when it is effective, so Tier 3 is unchanged: the ring screen is on screen and has each occurrence's actions.

## What stays a device question (`MANUAL_CHECKS.md`)

P3-2: in Tier 2 with two reminders due at once, a heads up appears for each with its three buttons while the ringer sounds. P3-3: with the system's automatic grouping (two or more on Android 13 and above), each bundled notification still shows its actions when expanded, on Pixel and on One UI. P3-4: the channel's sound on each per occurrence notification against the ringer's sound (the same question as P2-17). P3-5: Android 16's forced grouping.

## Alternatives considered

- **An app group with the session's notification as summary.** Rejected: which member alerts, and whether a silenced summary still launches its full screen intent, are group alert behaviours that cannot be shown under Robolectric.
- **Always a notification per occurrence, even for one.** Rejected: two notifications for one reminder in the common case.
- **Per occurrence notifications on the Quiet notices channel.** Rejected: an on time critical reminder would arrive on the channel for silent presentations.
- **More than three actions on one notification.** Not possible: `MAX_ACTION_BUTTONS`.
