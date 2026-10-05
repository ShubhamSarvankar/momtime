# 0090. A ring has a maximum length, and the end of grace ends a ring

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by Claude (technical review) in the third review of the Phase 3 plan: the cap and its placeholder value, that it is a dismissal, where its clock starts, that the end of grace ends a ring, and its own pull request (PR 2b, on Fable). What happens to the notification and the ring screen, the evidence field and the change to "Stop the sound" are the planning session's, for review. It builds on ADR 0061 (the ringer), ADR 0062 (the ring session), ADR 0065 (the ramp and the backup sound), ADR 0066 (dismissal is not completion) and ADR 0080 (per occurrence notifications).

## Context

The ringer has no limit. `RingerService` stops only when she acts, when she presses "Stop the sound", or when the session ends for another reason. That makes the Critical ladder's repeat at five minutes meaningless, because the first ring is still sounding and the repeat only "continues" it (ADR 0062). It also lets an unattended phone ring for hours on the battery her next reminder needs. And nothing ends a ring when its occurrence becomes `MISSED`: `Reconcile` changes the state, and the session is not told.

## Decision

1. **`MAX_RING`: a ring session's sound and vibration stop on their own after 4 minutes.** A placeholder, like the ramp and the backup interval, for a device to judge. The backup sound still starts at 2 minutes. `MAX_RING` must be shorter than the shortest gap between two device rungs of any default ladder (5 minutes, Critical's), so that a repeat is always a new ring; a test reads the default ladders and fails if it is not.
2. **The cap is a dismissal and writes nothing**, exactly as "Stop the sound" is (`ARCHITECTURE.md` section 4.6). No event, no state change. Every occurrence that was ringing stays open, its next rung is armed as before, and grace runs as before.
3. **Her way to answer stays in front of her.** When the cap ends the sound, the ringer service leaves the foreground and its notification is reposted as an ordinary one: not ongoing, no full screen intent, only alert once (so it makes no new sound), with the occurrence's actions if the session never spread; the per occurrence notifications of a spread session stay as they are (ADR 0080). **"Stop the sound" now does the same** instead of cancelling the notification, for review: both are the same dismissal, and today pressing it removes the only notification that carries her actions.
4. **The ring screen** is not closed. If it is showing, it keeps listing what was due, each with its actions, with the line that says the sound is off, as it already does after "Stop the sound" (ADR 0062), and it releases keep screen on so the display can sleep.
5. **Where the clock starts.** The cap counts from the start of the session's sound. A fire that rings again, whether it starts a new session after the cap (a repeat rung, a snooze's end) or continues or joins one that is still sounding, starts a fresh cap: a session rings for up to `MAX_RING` from the latest fire that rang. The timer is the ringer's own scheduler, the one the backup sound uses; it reads no clock and arms no alarm. If the process dies the sound dies with it.
6. **A repeat is now a ring of its own, and is counted as one.** Because the first ring has ended, the repeat rung starts a session instead of continuing one, so it spends the interruption budget like any ring (ADR 0060) and can be silenced by a budget spent, or quiet hours begun, in between. This is the direct consequence of item 1 and is stated so that nobody is surprised by a Standard reminder that costs two.
7. **The end of grace ends a ring.** Today the sound can outlive grace. After every `Reconcile` that android dispatches (the fire path, the watchdog pass, a process start, a system pass, the template edit pass), the ring session drops every occurrence that is no longer open, cancels its notification, and ends the ring if none is left. With the cap, a ring can outlive its occurrence's grace by at most `MAX_RING` even if no pass runs.
8. **Evidence.** `fire_telemetry` in the android store gains `ring_ended_by_cap`: set on the row of the fire whose ring ran out unanswered. A ring that ran its whole length is evidence that she may not have heard it. It is a count in the reliability report and in the export, with no banner of its own until a device has judged the cap. Android store schema version 6 (`migrations/5.sqm`, one added column), with its forward migration test.

## Alternatives considered

- **No cap; make the repeat rung restart the sound louder.** Rejected by the review: the battery, and hours of sound in an empty room.
- **The cap writes an event.** Rejected: a dismissal is not something she did or something the domain decided; the device's account of a fire belongs in the android store (ADR 0048).
- **The cap closes the ring screen and cancels the notification.** Rejected: she would return to a phone that shows nothing about a dose that is still open.
- **A cap per criticality.** Not now: one placeholder for a device to judge first.

## Evidence

To be recorded in `docs/phase-3-traceability.md` (PR 2b): the tests and mutations in `docs/phase-3-plan.md`. `MANUAL_CHECKS.md` P2-20 and P2-21 gain `MAX_RING` among the placeholders a device judges.
