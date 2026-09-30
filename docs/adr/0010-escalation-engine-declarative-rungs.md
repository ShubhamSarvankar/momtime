# 0010. Escalation engine emits a declarative rung list

Date: 2026-09-29
Status: Accepted

## Context

Android and iOS have fundamentally different alarm-scheduling capabilities: Android can only safely keep one alarm armed at a time (§0017), while iOS's AlarmKit model is expected to support pre-scheduling a bounded list and cancelling the tail on completion. If the escalation logic itself calls platform scheduling APIs or takes callbacks, that logic becomes unportable and untestable without a device.

## Decision

The engine emits the full escalation ladder for an occurrence as an ordered, declarative list of `EscalationRung(instant, channel, slot)` values. It does not schedule anything and does not take a callback. Each platform decides how to consume the list — Android arms only the head, iOS will later arm the whole list.

## Alternatives considered

- A scheduler abstraction with a platform-provided callback interface injected into the engine — rejected, since it still couples the engine's API shape to a scheduling model that only really fits one platform's constraints, and it's harder to unit test than "call a pure function, assert on the returned list."
- Separate escalation logic per platform — rejected outright per the capability-line invariant; this is precisely the kind of domain logic that must not fork by platform.

## Consequences

The escalation engine is fully unit-testable on the JVM with no mocking of platform scheduling. The cost is that each platform adapter has to correctly interpret "consume the head" or "consume the whole list," which is where the Robolectric test suite (Phase 2) earns its keep.
