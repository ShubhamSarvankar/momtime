# 0028. Cloud SQL, asia-south1, and connection ceiling vs. scale-to-zero compute

Date: 2026-09-29
Status: Accepted

## Context

`ARCHITECTURE.md` originally said "Managed Postgres, nearest available region to Mumbai" without naming a provider, and separately described a connection-pooling problem as "a scale to zero runtime against serverless Postgres." A Phase 0 planning review flagged this as loose: "serverless Postgres" (a database that itself scales compute to zero) is a specific product class that GCP's Cloud SQL — the default implied by the rest of an all-GCP stack — does not provide. Left unresolved, this would have either silently introduced a second cloud vendor (a non-GCP serverless Postgres provider) or misdescribed the actual pooling problem.

## Decision

Cloud SQL for PostgreSQL, smallest shared-core tier, `asia-south1`. One vendor, one Terraform provider, one IAM story, private connectivity to Cloud Run, no second cloud account for what is a solo project. Roughly 10–15 USD/month, judged worth paying to avoid the operational cost of a second vendor.

This reframes the connection-pooling problem honestly: it was never a scale-to-zero *database* problem, because Cloud SQL isn't scale-to-zero. It's a scale-to-zero **compute** problem — Cloud Run instances churn (spin up cold, spin down idle) against Cloud SQL's fixed `max_connections` ceiling, and naive per-request or per-instance connection handling can exhaust that ceiling under normal traffic patterns, not just spikes. The fix is a connection pool sized to `max_connections / max_instances`, with `max_instances` capped low (3), through the Cloud SQL connector rather than a raw TCP connection string.

## Alternatives considered

- A non-GCP serverless Postgres provider (e.g., a Neon-style scale-to-zero database) — rejected; it would genuinely solve a scale-to-zero-database problem that doesn't exist here (grace windows are measured in hours, so idle database compute cost is not the actual concern), while introducing a second vendor, a second IAM/auth story, and a second thing that can have an outage independent of GCP.
- AlloyDB — rejected as unnecessary; this workload (a handful of tables, modest write volume, a 5-minute sweep) doesn't need AlloyDB's performance profile, and Cloud SQL is the simpler, cheaper, better-documented default for a workload this size.

## Consequences

The pooling ADR's actual scope is now honest: pool sizing and `max_instances` capping, not scale-to-zero database behaviour. Terraform reads a single GCP project id (deferred — see `docs/IMPLEMENTATION_PLAN.md` Open Items) rather than a second provider's credentials.
