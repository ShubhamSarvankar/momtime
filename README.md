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

## Requirements

- **JDK to launch Gradle: 17 or later.** Gradle 9.8 itself needs JDK 17+ to start, so `JAVA_HOME` must point at one. Android Studio's bundled `jbr` directory (a JDK 21) works.
- **JDK 21 toolchain: provisioned for you.** `shared` and `server` pin their toolchain to Eclipse Temurin 21 (`jvmToolchain` with `languageVersion` 21 and vendor `ADOPTIUM`), the same distribution CI installs with `setup-java` (`temurin`), so local builds and CI compile on the same JDK. The Foojay toolchain resolver plugin (`org.gradle.toolchains.foojay-resolver-convention`, pinned to 1.0.0 in `settings.gradle.kts`, ADR 0041) downloads Temurin 21 on first use if Gradle cannot find one, so you do not need to install it. The launcher JDK is separate: the plugin does not change what Gradle starts on.
- **If Gradle fails to start** with "requires JVM 17 or later", `JAVA_HOME` points at an older JDK. That was the actual cause of the local failure found in Phase 1: `JAVA_HOME` pointed at a JDK 11 (Zulu). The plugin cannot fix this, because Gradle never gets as far as reading `settings.gradle.kts`. Point `JAVA_HOME` at JDK 17 or later.

## Running the test suites

| Suite | Command | Runs on |
|---|---|---|
| `shared` JVM tests + the `android.*` import ban | `./gradlew :shared:jvmTest :shared:check` | CI, no device |
| Migration tests | `./gradlew :shared:verifySqlDelightMigration :android:verifyDebugAndroidStoreDatabaseMigration` (the android store has its own committed baseline, ADR 0048) | CI, no device |
| Server tests | `./gradlew :server:test` | CI, no device |
| Android assemble + lint | `./gradlew :android:assembleDebug :android:lint` | CI, no device |
| Detekt | `./gradlew detekt` | CI, no device |
| ktlint | `./gradlew ktlintCheck` | CI, no device |
| Robolectric, native SQLite (Android data layer now; alarm subsystem as it lands) | `./gradlew :android:testDebugUnitTest` | CI, no device |
| Android structural checks (no generated query type in android sources; no wall clock outside the DI package; no component in another process; the SQLite floor for the android store) | `./gradlew :android:verifyNoGeneratedQueries :android:selfTestVerifyNoGeneratedQueries :android:verifyNoClockSystem :android:selfTestVerifyNoClockSystem :android:verifySingleProcess :android:selfTestVerifySingleProcess :android:verifySqliteFloor :android:selfTestVerifySqliteFloor` | CI, no device |
| Roborazzi screenshots (from Phase 3) | `./gradlew :android:recordRoborazziDebug` / `verifyRoborazziDebug` | CI, no device |
| Device verification | See [`docs/MANUAL_CHECKS.md`](docs/MANUAL_CHECKS.md) | Real hardware only, Phase 7 |

`./gradlew check` runs the full local verification set (tests, Detekt, ktlint, the import ban and its self-test) in one command.

On Windows, keep Robolectric test names short: Robolectric names its temporary data directory after the test class and method, and a database path longer than 260 characters fails to open (`SQLITE_CANTOPEN`). CI runs on Linux and is not affected.

`shared/src/commonMain/sqldelight/databases/1.db` is a **committed** schema snapshot, not a build artifact — it's what migration verification compares the current `.sq` files against (ADR 0035). Regenerating it (`./gradlew :shared:generateCommonMainMomTimeDatabaseSchema`) is a deliberate, reviewed step, never something CI or `check` does automatically; doing so routinely would make migration verification a tautology.
