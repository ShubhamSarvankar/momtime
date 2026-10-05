# MomTime

A medication, supplement, hydration and routine adherence app for pregnant and postpartum women. Android only in v1.

The product is one thing: **a reminder fires within 60 seconds of its scheduled time, on a mid range Android phone, in Doze, with the app killed, offline.** Service level objective: 99.5% of critical occurrences delivered within 60 seconds of `scheduledInstant`, on devices resolved to capability Tier 3. Everything else in this repo exists to serve that.

> **This is a test build. Nobody should rely on it for real medication until the Phase 7 device checks pass.** The alarm subsystem passes every automated layer (shared, Robolectric, server) and has not been run on a real device for hours, in Doze, on the phones that matter. `docs/MANUAL_CHECKS.md` lists what is unverified and why. A reminder that does not fire is a real harm to the person who relies on it, so until those checks have results, use it to test, not to remember a dose.

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
| Robolectric (SDK 29, 31, 33, 34 and 36; native SQLite): the whole Android side, which is the alarm scheduling adapter read from `ShadowAlarmManager`, the fire path and the watchdog (through WorkManager's test driver), delivery and the ring screen, ring actions and snooze, the system broadcasts and a time zone change, the reliability report and its export, the permission flows and the Samsung walkthrough, the migrations of both databases, and the debug seed (`src/testDebug`) | `./gradlew :android:testDebugUnitTest` | CI, no device |
| Android structural checks (no generated query type in android sources; no wall clock or device zone outside the DI package; no component in another process; the SQLite floor for the android store; the manifest's permissions as an exact allowlist; the ring screen's boundary; no debug only component in the release manifest), each with a fixture self test | `./gradlew :android:verifyNoGeneratedQueries :android:selfTestVerifyNoGeneratedQueries :android:verifyNoClockSystem :android:selfTestVerifyNoClockSystem :android:verifySingleProcess :android:selfTestVerifySingleProcess :android:verifySqliteFloor :android:selfTestVerifySqliteFloor :android:verifyManifestPermissions :android:selfTestVerifyManifestPermissions :android:verifyRingUiBoundary :android:selfTestVerifyRingUiBoundary :android:verifyNoDebugComponents :android:selfTestVerifyNoDebugComponents` | CI, no device |
| Roborazzi screenshots (from Phase 3) | `./gradlew :android:recordRoborazziDebug` / `verifyRoborazziDebug` | CI, no device |
| Device verification | See [`docs/MANUAL_CHECKS.md`](docs/MANUAL_CHECKS.md) | Real hardware only, Phase 7 |

`./gradlew check` runs the full local verification set (tests, Detekt, ktlint, the import ban and its self-test) in one command.

On Windows, keep Robolectric test names short: Robolectric names its temporary data directory after the test class and method, and a database path longer than 260 characters fails to open (`SQLITE_CANTOPEN`). CI runs on Linux and is not affected.

`shared/src/commonMain/sqldelight/databases/1.db` is a **committed** schema snapshot, not a build artifact — it's what migration verification compares the current `.sq` files against (ADR 0035). Regenerating it (`./gradlew :shared:generateCommonMainMomTimeDatabaseSchema`) is a deliberate, reviewed step, never something CI or `check` does automatically; doing so routinely would make migration verification a tautology.

## Building the debug APK, and why it must come from one machine

```
./gradlew :android:assembleDebug        # on Windows: gradlew.bat :android:assembleDebug
```

The APK is `android/build/outputs/apk/debug/android-debug.apk`. It needs an Android SDK (compile SDK 36) and the JDK above.

**Build every test APK on the same machine.** A debug build is signed with the debug keystore that the Android Gradle Plugin creates on first use in `~/.android/debug.keystore` (on Windows `%USERPROFILE%\.android\debug.keystore`), and **that key is different on every machine**. Android refuses to install an APK over an installed one that was signed with another key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so an APK built on a second machine will not update the one on the phone. Either keep building on the one machine, or, if two people build, copy the same `debug.keystore` file to both machines once (a debug key is not a secret). The only way past a mismatch is to uninstall the app first, which **deletes its data**: `adb uninstall com.momtime.android`.

Install with `adb install -r android/build/outputs/apk/debug/android-debug.apk`, or copy the file to the phone and open it (the phone has to allow installing from that source).

## The seed screen

The debug build, and only the debug build, has a second launcher entry named **MomTime seed**. It exists so that someone can put a real reminder in front of a real alarm before Phase 3's schedule builder exists. Open it and press **Seed test reminders**: it creates, through the app's own repositories, one **Critical** test reminder three to four minutes from now (a one off) and three **daily Standard** reminders at 08:00, 13:00 and 20:00 in the phone's time zone, then arms the next alarm. Lock the phone and wait: the test reminder should ring. Pressing it a second time changes nothing ("Already seeded"); to start again, clear the app's data (Settings, Apps, MomTime, Storage).

The app has no launcher entry of its own until Phase 3, so the permission and check screens are reached from the ring screen: once a reminder is armed, tap the "next alarm" line of the system's quick settings (or the ring notification) to open the ring screen, and use **Set up and check my reminders** on it. `docs/MANUAL_CHECKS.md` says which device checks can be attempted informally with this build and which wait for Phase 7. The release build has no seed screen: `verifyNoDebugComponents` fails the build if any debug component reaches the release manifest.
