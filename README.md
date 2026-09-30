# MomTime

A medication, supplement, hydration and routine adherence app for pregnant and postpartum women. Android only in v1.

The product is one thing: **a reminder fires within 60 seconds of its scheduled time, on a mid range Android phone, in Doze, with the app killed, offline.** Service level objective: 99.5% of critical occurrences delivered within 60 seconds of `scheduledInstant`, on devices resolved to capability Tier 3. Everything else in this repo exists to serve that.

## Reading order

- [`CLAUDE.md`](CLAUDE.md) — the non-negotiable working rules. Wins over the two documents below if they ever conflict.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — technical decisions and scope.
- [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) — phased plan, deliverables, and the current phase status.
- [`docs/adr/`](docs/adr/) — the decision record. Every decision that closes off an alternative gets one; they're never edited once accepted.

## Module layout

```
momtime/
├── shared/     Kotlin Multiplatform, jvm target only in v1 (no android target — see ADR 0027).
│               Domain models, event log, recurrence/escalation engine, SQLDelight schema.
│               Compiles and tests on the JVM with no Android dependency.
├── android/    Compose UI, alarm subsystem, platform adapters. Depends on shared's jvm artifact.
├── server/     Ktor service. Depends on shared (jvm target) so the server evaluates deadlines
│               with the exact same recurrence/escalation code the app uses.
├── infra/      Terraform (Phase 4).
└── docs/       This directory.
```

## Running the test suites

| Suite | Command | Runs on |
|---|---|---|
| `shared` JVM tests + the `android.*` import ban | `./gradlew :shared:jvmTest :shared:check` | CI, no device |
| Migration tests | `./gradlew :shared:verifySqlDelightMigration` (added in Phase 1, once a schema exists) | CI, no device |
| Server tests | `./gradlew :server:test` | CI, no device |
| Android assemble + lint | `./gradlew :android:assembleDebug :android:lint` | CI, no device |
| Detekt | `./gradlew detekt` | CI, no device |
| ktlint | `./gradlew ktlintCheck` | CI, no device |
| Robolectric (alarm subsystem, from Phase 2) | `./gradlew :android:testDebugUnitTest` | CI, no device |
| Roborazzi screenshots (from Phase 3) | `./gradlew :android:recordRoborazziDebug` / `verifyRoborazziDebug` | CI, no device |
| Device verification | See [`docs/MANUAL_CHECKS.md`](docs/MANUAL_CHECKS.md) | Real hardware only, Phase 7 |

`./gradlew check` runs the full local verification set (tests, Detekt, ktlint, the import ban and its self-test) in one command.
