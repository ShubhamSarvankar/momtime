# 0060. Delivery: one path per presentation, policy at fire time, and three channels

Date: 2026-10-04
Status: Accepted

Decided by Claude (technical review) in the PR 5 prompt; implemented in `phase-2/delivery`. It builds on ADR 0050 (tiers from capability), ADR 0053 (the fire path), ADR 0056 (a late rung is presented quietly) and ADR 0048 (device state is android's). Items marked "judgment" are the implementing session's, for review.

## Context

PR 3 left delivery as a recording port: the fire path handed a rung over and nothing sounded or showed. PR 5 is everything from a fire to what she sees and hears. Two things must be decided in the domain and not by a platform: whether a rung rings or is silent (quiet hours, the interruption budget), and what a rung that fires late is. Everything else is presentation, which is android's.

## Decision

**The fire path asks the domain.** After it writes `ALARM_FIRED`, the fire path asks `DeliveryPolicyCommand` (in `shared`) what the rung should do. The command reads her stored settings and today's budget and applies Phase 1's `EscalationPolicy`, so android reimplements neither the quiet hours window nor the budget. `CRITICAL` is never budget limited and overrides quiet hours. Quiet hours and the budget day are evaluated in the current zone (ADR 0032), at the instant of the fire. Deciding changes nothing; `recordRing` is what spends the budget, and it is called for a rung that is to ring and is not a continuation (ADR 0062). A rung beyond the catch up window is a silent notice whatever the policy says, so the domain is not asked and nothing is spent.

Judgment: **every ring grade interruption counts toward the budget, `CRITICAL` included.** The budget measures how often the app interrupts her. `CRITICAL` is exempt from being limited, not from being counted. If the review prefers that `CRITICAL` does not spend the budget, the change is one condition in `DeliveryDecider`.

**Six paths, one each.** The path is a function of the rung's presentation, the domain's decision, and the capability resolution (`DeliveryPath.choose`), and of nothing else:

| Path | When | What happens |
|---|---|---|
| `SILENT_NOTICE` | the rung fired beyond the window and nothing is ringing for the occurrence | a silent notification on the Gentle channel, with a line saying the reminder is late |
| `SILENT` | the domain says silent (quiet hours, or the budget is spent) | a silent notification on the Gentle channel |
| `PLAIN` | Tier 1: no exact capability | a notification on the criticality channel, replacing the one for the same occurrence; no ringer |
| `RING` | Tier 3: effective full screen intent | the ring session, a full screen intent notification and the ringer service |
| `HEADS_UP` | Tier 2: exact, no effective full screen intent, notifications delivered | the ring session, a heads up notification and the ringer service; the overlay route if it is granted |
| `AUDIO_ONLY` | exact, notifications not delivered | the ring session and the ringer service, and nothing else visible; the overlay route if it is granted |

A rung that continues a ring in progress keeps ringing whatever it would have been alone (ADR 0062). Silent presentations never start the ringer and never take a session.

**Channels.** Three, split by criticality and never by task type (ADR 0053's table, `ARCHITECTURE.md` section 5.6). Critical and Standard are importance high, because a heads up and a full screen intent need it; Critical carries the alarm tone on the alarm stream, so a Tier 1 reminder, which has no ringer behind it, is still heard. Gentle is importance low, with no sound. Judgment: **every silent presentation goes to the Gentle channel whatever the occurrence's criticality**, because a notification cannot be made silent on a channel that sounds, and the platform offers no per notification silence below API 31. The cost is that a user who blocks Gentle also hides the silent notices; the Critical channel, the one onboarding says not to mute, is not touched by that. A known limit: on the ring paths the ring notification arrives on a channel that has a sound, so the channel sound and the ringer's own sound overlap for a moment at the start; it is a device check (`MANUAL_CHECKS.md` P2-17) and PR 5b's sound design can settle it.

**The Critical channel input** (progress decision 26). `PlatformCapabilityReader` reads the Critical channel's importance: importance none is blocked, and a channel that does not exist yet is not. A blocked Critical channel fails Tier 3 for critical delivery, because the capability table already says so (`criticalChannelAllowed`), and `resolveDelivery` is unchanged.

**Full screen intent only while it is effective.** `setFullScreenIntent` is called only when the resolution says full screen intent is effective, and never otherwise, including on the degraded path after the ringer is refused. The answer to "full screen intent is not available" is the Tier 2 path, not a try (CLAUDE.md).

**Telemetry.** Each delivery writes a row in the android store (never fatal, ADR 0054): the resolved tier, whether the screen was on, battery and Doze state, the boot count, which path was taken and whether the ringer started. The ringer adds what audio focus said. Schema version 3 of the android store adds `delivery_path` and `ringer_started` (`migrations/2.sqm`, with a forward migration test from version 1 and from version 2).

**The reset notification** (ADR 0051). Posted on the Critical channel by the corruption handler of the shared database, on both paths, when corruption is found on open and in the middle of a query, after the marker is durable and, on the second path, before the process ends. It never throws. The android store does not post it (ADR 0048).

## Alternatives considered

- **Android decides quiet hours and the budget.** Rejected by the review: two implementations of a rule that Phase 1 already tests, and the server shares the policy.
- **`CRITICAL` does not spend the budget.** Possible; see the judgment above.
- **A fourth channel for silent notices.** Rejected: the review fixed three, split by criticality.
- **Silent notices on the criticality channel with a per notification silence.** Not available on the supported releases, and it would sound on a channel that sounds.
- **Start the ringer for Tier 1.** Rejected: an inexact alarm's broadcast does not carry the exact alarm exemption that lets the foreground service start (ADR 0061), so Tier 1 is a notification, as decision 9 of the Phase 2 progress file says.

## Evidence

`DeliveryPolicyCommandTest` (shared: quiet hours, the zone, the budget, a new local day), `DeliveryTest` (every path at SDK 29, 31, 33, 34 and 36, the channel for each criticality, quiet hours and the budget through the fire path, the full screen intent only while effective, her words verbatim), `ChannelsTest` (the channels and their importance, the Critical channel input, `DeliveryPath.choose` as a table), `DatabaseCorruptionTest` (the reset notification on both paths), `AndroidStoreMigrationTest`. Mutations are in `phase-2-traceability.md`.
