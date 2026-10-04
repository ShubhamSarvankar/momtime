# 0062. One ring session at a time, and a ring screen that reaches no data

Date: 2026-10-04
Status: Accepted

Decided by Claude (technical review) in the PR 5 prompt (the session rules, and that a fire for an occurrence already ringing continues the ring); implemented in `phase-2/delivery`. Items marked "judgment" are the implementing session's, for review.

## Context

Several occurrences can be due at once: two templates at the same minute, or a boot after a night with the phone off. One alarm is armed at a time (ADR 0017), but each fire is its own, and ringing the screen and the sound separately for each would stack screens and restart the sound. Separately, the ring screen is the one place where a button press could be mistaken for a state change: dismissing the alert is not completion.

## Decision

**One session, covering every occurrence that is due.** A ring session is the sound and the screen for the occurrences currently ringing (`RingSessions`, in memory in the process). It is one at a time:

- A fire for an occurrence **not** in the session when none is going **starts** it: the ringer service starts.
- A fire for an occurrence **already in** the session **continues** it. No restart of the sound, no second screen. It still writes `ALARM_FIRED` (the count of those is the record of what has fired, ADR 0053), and the interruption budget counts the occurrence once, because it is one interruption and not two (ADR 0060). The fire path knows the occurrence is ringing before it asks the domain, so it asks nothing and spends nothing.
- A fire for a **different** occurrence **joins** the session. No second session and no second ringer: the session lists both, and the ring notification is refreshed to say so. It spends the budget once for itself.
- The screen lists each due occurrence, with its title, its dosage and her doctor's instructions.
- The session ends when the sound is stopped, and, in PR 5b, when nothing in it is left unacknowledged. After it ends the screen still lists what was due, with a line that the sound is off and the reminder is still waiting, until the next session starts. A rung that fires after the session ended starts a new one: stopping the sound is not completion, so the next rung still fires and rings.
- A session whose ringer was refused ends at once (ADR 0061), so the next rung tries again.

The session is process memory. If the process dies while it rings, the sound dies with it, and the next fire starts a session; the watchdog repairs a chain, not a session.

**The ring screen reaches no data.** `RingActivity` shows the session and has one action, which stops the sound. It never touches a repository, a store, an event or an occurrence, and `verifyRingUiBoundary` fails the build if anything in `com.momtime.android.ring` names a repository, a store type, a generated query, either database, or the packages they live in, with a fixture self test. Stopping the sound ends the session and stops the service and writes nothing. Acknowledge, snooze and skip are wired in PR 5b, through the domain.

It shows only fields the model has (a title, a dosage, the doctor's instructions), exactly as typed and never parsed. It is shown over the lock screen and turns the screen on. Layouts have no fixed width or height, so labels wrap at 200% font scale. Every string is a resource, English only.

**A seam for missions.** The layout holds an empty container for a mission's own UI, and nothing writes to it. Missions need a dependency nobody has approved and no phase schedules them (`IMPLEMENTATION_PLAN.md` Open Items; they are off by default, CLAUDE.md), so none is built.

## Alternatives considered

- **A session per occurrence.** Rejected by the review: stacked screens and sounds.
- **A fire for an occurrence already ringing restarts the sound.** Rejected by the review: the ring in progress is the ring.
- **Persist the session.** Rejected for now: a sound cannot outlive its process, and the watchdog already repairs the chain.
- **Let the screen read occurrences itself.** Rejected: it would be one step from writing them.

## Evidence

`DeliveryTest` (`a rung for an occurrence that is ringing continues the ring`, `a rung for another occurrence joins the session`, `stopping the sound writes nothing and the next rung still rings`), `RingerAndScreenTest` (the screen shows her words verbatim, only fields that are set, and can only stop the sound), `verifyRingUiBoundary` and its self test. Mutations are in `phase-2-traceability.md`.
