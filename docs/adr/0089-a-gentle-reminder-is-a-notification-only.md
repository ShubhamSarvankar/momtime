# 0089. A Gentle reminder is a notification only: a correction of Phase 2 to match the architecture

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by Claude (technical review) in the third review of the Phase 3 plan: `ARCHITECTURE.md` section 4.2 is right and the build is wrong; the fix goes in PR 2; and each rule below. The shape of the domain change was proposed by the planning session and accepted by Claude (technical review) in the fourth review, with the test of an edit from Gentle to Critical placed in PR 4, where the edit command exists. It corrects ADR 0060 and ADR 0065 in what they did for Gentle, and neither is edited.

## Context

`ARCHITECTURE.md` section 4.2 gives Gentle the ladder "t+0 notification only, no repeat". Phase 2 built something else, and no check noticed: `EscalationLadder` gives Gentle one `RING` rung, `EscalationPolicy.resolveDelivery` answers `RING` for it outside quiet hours and the budget, `DeliveryPath.choose` does not look at criticality, and so on an exact tier a Gentle reminder starts the ringer service, with the alarm sound and a light vibration. `DeliveryTest` `each criticality rings on its own channel` asserts that ringer start. It was found by the Phase 3 planning session while checking the onboarding line "A quiet notification" against the build. It is the same kind of failure as the precedents in `CLAUDE.md`'s testing section: a document said one thing, a green test asserted another, and nothing compared them.

## Decision

1. **A Gentle occurrence is a notification only:** no ringer, no full screen intent, no overlay, no repeat.
2. **The domain decides it, at fire time, as it decides quiet hours and the budget.** `RungDelivery` gains a third value, `NOTIFICATION`. `EscalationPolicy.resolveDelivery` answers, in this order: `RING` for Critical; `SILENT_NOTIFICATION` inside quiet hours or with the budget spent; `NOTIFICATION` for Gentle; `RING` otherwise. The criticality it is given is the occurrence's (ADR 0079 item 6). `DeliveryPath.choose` sends a rung whose policy is `NOTIFICATION` to the plain notification path, on any tier; it branches on the domain's answer and never on a criticality android reads for itself.
3. **What she gets:** one notification on the Gentle channel (importance low, no sound), with the occurrence's offered actions, exactly as a Tier 1 reminder is posted today (its id is the occurrence's slot, no full screen intent, no delete intent).
4. **The budget.** A Gentle notification is not a ring grade interruption and spends nothing: `recordRing` is called only for `RING`, as today.
5. **Quiet hours and the catch up window are unchanged.** Inside quiet hours and with the budget spent a Gentle rung is already silent, on the Quiet notices channel (ADR 0064); a rung beyond the catch up window is a silent notice whatever its criticality (ADR 0056).
6. **Ring sessions.** A Gentle fire never starts and never joins a ring session. If it lands while a session is ringing, the session is untouched: no spread (ADR 0080 spreads when a second occurrence joins a session, and a Gentle one does not join), no change to the session's notification, and the Gentle occurrence's notification is its own.
7. **Vibration.** The default for Gentle is none. The editor offers the vibration choice only for Critical and Standard. A pattern stored for a template whose occurrence is Gentle is ignored, not deleted, so that changing the template back to Critical or Standard restores her choice. The `LIGHT` pattern stays as a choice for the other two.
8. **Arming is unchanged, and its visible consequence is accepted.** A Gentle rung is still armed with `setAlarmClock` on an exact tier, because that is the only exact mechanism (`CLAUDE.md`, alarm subsystem rules), so the system's "next alarm" indicator shows a Gentle reminder's time as it shows any other. The ladder still has its one rung and its channel is still `RING` in the shared vocabulary: a rung is something to deliver, and how it is delivered is the policy's answer. Grace is unchanged (the same local day).
9. **One Phase 2 expectation changes, by authorisation:** `DeliveryTest` `each criticality rings on its own channel` expects, for Gentle, a plain notification on the Gentle channel and no ringer start. It is the one exception to PR 2's STOP on changed expectations.

## Alternatives considered

- **Correct the document instead.** Rejected by the review: a reminder she marked Gentle should not play the alarm sound.
- **A branch on criticality in `DeliveryPath.choose`.** Rejected by the review: android would decide a domain rule from a value it read for itself, and the server, which shares the policy, would not know it.
- **A new `Channel` value for Gentle's rung.** Rejected: the escalation vocabulary would change for a presentation decision, and `ArmingSelection`'s eligible channels with it.
- **An inexact alarm for Gentle**, to keep it out of "next alarm". Rejected: a second arming mechanism chosen by criticality, and a Gentle reminder minutes late on a phone that could have been exact.

## Evidence

To be recorded in `docs/phase-3-traceability.md` (PR 2, and PR 4 for the edit): the tests and mutations in `docs/phase-3-plan.md`. Nothing here has run on a device; what a Gentle notification looks and sounds like on the Gentle channel is already `MANUAL_CHECKS.md` P2-19's question.
