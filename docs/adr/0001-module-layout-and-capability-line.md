# 0001. Module layout and the capability line

Date: 2026-09-29
Status: Accepted

## Context

MomTime needs to ship Android first while keeping a later iOS port from being a rewrite, and needs a server that cannot disagree with the client about domain logic (recurrence, escalation, adherence). Without an enforced boundary, platform-specific code and platform-neutral domain code drift together until neither is portable nor testable without a device.

## Decision

Platform capability differences live only in delivery and presentation (`android`, later `ios`). They never appear in the schema, the domain vocabulary, the event log, or the escalation engine (`shared`). Above the line: maximal expressiveness, testable on the JVM with no device. Below the line: each platform renders and delivers what it can. This is not a constraint on Android features — Android is maximal within its own module.

## Alternatives considered

- A single Android-only codebase with no `shared` module — rejected, since it makes the later iOS port a full rewrite rather than additive work, and it lets the server reimplement domain logic in a second language, which is the exact bug class this architecture exists to prevent.
- Sharing code via a common HTTP API only (client and server both call a shared service for domain decisions) — rejected, since the app must work fully offline; the domain logic has to run on-device.

## Consequences

Every future PR that adds a column or event type "to support one platform's UI" is wrong by construction, which is mechanically simpler to review than debating it case by case. It does constrain contributors to think about where code belongs before writing it, which is the point.
