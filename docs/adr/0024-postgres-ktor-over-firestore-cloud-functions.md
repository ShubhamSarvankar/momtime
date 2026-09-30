# 0024. Postgres + Ktor over Firestore/Cloud Functions

Date: 2026-09-29
Status: Accepted

## Context

Firestore + Cloud Functions is the default "serverless GCP backend" pattern and would be a faster initial build for simple CRUD. But the server's actual job (§0021) is running the *same* recurrence-expansion and escalation-deadline logic the Kotlin client runs, so the caregiver dead-man switch agrees with what the device itself believes about when a dose is due.

## Decision

Domain data lives in Postgres behind a Ktor service that depends on `shared`'s `jvm` target directly. No Firestore, no Cloud Functions.

## Alternatives considered

- Firestore + Cloud Functions (likely in TypeScript or Python) — rejected; it would require reimplementing recurrence expansion and deadline/grace-window logic in a second language, which is precisely the class of bug (client and server silently disagreeing about when a dose is due) this architecture exists to prevent.
- Firestore with the domain logic still in Kotlin via Cloud Functions' (limited, awkward) JVM support — rejected as fighting the platform for no benefit over a plain Ktor service that runs the same JVM artifact `shared` already produces.

## Consequences

The server can call the exact same `Recurrence` expansion and escalation functions the app uses, with no port or reimplementation. This is what makes "the server evaluates deadlines using shared code" a literal fact rather than an aspiration kept in sync by discipline alone.
