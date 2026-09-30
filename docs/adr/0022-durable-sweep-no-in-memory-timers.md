# 0022. Durable sweep over in-memory timers for missed-dose detection

Date: 2026-09-29
Status: Accepted

## Context

Detecting a missed dose server-side needs some mechanism that fires when a deadline passes with no completion event. An in-memory timer per expected occurrence is the obvious naive approach, and it doesn't survive a process restart, doesn't scale cleanly to zero, and can't be reasoned about or tested as simply as a table scan.

## Decision

A durable sweep — a query over the expected-occurrences table every 5 minutes, invoked by Cloud Scheduler — checks for anything past `deadline + grace` still awaiting confirmation. No in-memory timers anywhere in the server.

## Alternatives considered

- In-memory scheduled timers (e.g., a `ScheduledExecutorService` per occurrence) — rejected; they don't survive a restart or a scale-to-zero cold start, which Cloud Run's `min instances 0` setting makes a routine occurrence, not an edge case.
- A message queue with delayed delivery (e.g., Cloud Tasks with a scheduled dispatch time) — rejected as unnecessary infrastructure for a check that a five-minute table scan already handles correctly and testably, per the "prefer boring" working-style rule.

## Consequences

Five-minute granularity is coarser than an event-driven approach would give, which is fine because grace windows are measured in hours, not minutes — the sweep interval only needs to be much finer than the grace window, not instantaneous. This is also what lets Cloud Run scale to zero between sweeps and accept a cold start rather than paying to keep a container warm.
