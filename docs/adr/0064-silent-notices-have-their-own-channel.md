# 0064. Silent notices have a channel of their own

Date: 2026-10-04
Status: Accepted

Decided by Claude (technical review) in the PR 5b prompt, as a review of PR #19. It supersedes the channel decision of ADR 0060 (three channels, every silent presentation on Gentle) and `ARCHITECTURE.md` section 5.6's "three channels". ADR 0060 is not edited; its other decisions stand.

## Context

ADR 0060 put every silent presentation (a rung beyond the catch up window, quiet hours, the spent budget) on the Gentle channel, whatever the criticality of its occurrence, because a notification cannot be made silent on a channel that sounds. The review of PR #19 found the consequence: Gentle is the channel she is most likely to have muted, because gentle reminders are the ones she chose to be unobtrusive. A late dose of a critical medicine, which ADR 0056 says must be presented and never dropped, would then arrive silently on the one channel she may have switched off. That is the wrong outcome.

## Decision

**A fourth channel, Quiet notices (`momtime.quiet`), at importance low, with no sound,** carries every silent presentation: `SILENT_NOTICE` (a rung beyond the window), and `SILENT` (quiet hours, and the interruption budget). It is used whatever the occurrence's criticality. Gentle (`momtime.gentle`) is unchanged in importance and no longer carries silent presentations: it is for occurrences she made gentle, and only those.

The platform can silence one notification, by giving it a group alert behaviour that suppresses it (`NotificationCompat`'s `setSilent` does this). That is not used: its effect shows only on a device, whereas a channel's importance is something a test here can assert. Quiet notices and Gentle are both importance low, so the platform treats them alike; what differs is what is in them, and that she can see the difference and decide for each.

The channel's description tells her what is in it, including that a late reminder for a medicine she must not miss can arrive there, so that she keeps it on. Onboarding (PR 8) names it next to the Critical channel: Critical is the one not to mute, and Quiet notices is the one to keep on.

A user can still block it. Capability resolution has no input for it: a blocked Quiet channel loses silent notices, which is the cost of her choice, and the ring paths and the Critical channel are not touched by it.

## Alternatives considered

- **Keep silent presentations on Gentle** (ADR 0060). Rejected by the review for the reason above.
- **Silent notices on the criticality channel with a per notification silence.** Rejected: untestable here, and on a channel that sounds a failure of the silence is a sound at the wrong time.
- **Put critical silent notices on Critical and the rest on Gentle.** Rejected: a late critical dose would sound on a channel that has an alarm tone, which is what the catch up window exists to avoid, and the split makes where a notice is posted depend on two things.

## Evidence

`ChannelsTest` (four channels, Quiet notices at importance low with no sound, and not Gentle, and no criticality maps to it) and `DeliveryTest` (`every silent presentation is posted on the quiet channel and never on gentle`: beyond the window for each criticality, quiet hours, the spent budget). Mutations are in `phase-2-traceability.md`. What a device does with the channel is `MANUAL_CHECKS.md` P2-22.
