# 0073. Compose for every new screen; the ring screen stays framework views

Date: 2026-10-05
Status: Accepted (2026-10-05, in the review of PR #26 by Claude (technical review))

Decided by Claude (technical review) in the Phase 3 planning prompt (D1): Compose for new screens, the ring screen stays views. The porting decision for the permission, Samsung and reliability check screens, and the version pins, are the planning session's, for review. It resolves the Open items row "UI toolkit for Phase 3".

## Decision

1. **Every new Phase 3 screen is Jetpack Compose**, hosted by one Activity, `MainActivity` (the launcher entry, ADR 0078).
2. **The ring screen (`RingActivity`) stays framework views.** It works on the lock screen path, it is covered by `verifyRingUiBoundary` and Phase 2's tests, and nothing justifies rewriting it. It takes the appearance through the shared colour roles (ADR 0075).
3. **The permission screen, the Samsung walkthrough and the reliability check screen are ported to Compose**, as onboarding steps that are also reachable from Settings. Evidence: each is a plain `Activity` of about 100 lines of programmatic views with no logic of its own (the logic is in `PermissionFlows`, `SetupController`, `SamsungStep`, `CanaryRunner`, `ReliabilityController`, `ReliabilityText`); the appearance switcher is required in the top bar of every onboarding screen (D2b) and is one Compose component, so a view screen would need a second implementation of it; and the bundled font chain and the per locale line heights (ADR 0085) are defined once for Compose. `SetupActivity`, `SamsungStepsActivity`, `ReliabilityCheckActivity` and their layouts are deleted in the onboarding pull request. `PermissionFlows`, `SetupController`, `SamsungStep`, `CanaryRunner`, `ReliabilityController`, `ReliabilityReader`, `ReliabilityText`, `Banners` and their tests are kept unchanged; the three Activity tests are expressed again against the Compose screens, assertion for assertion (the plan lists them).
4. **Compose is pinned to BOM 2026.06.01 (Compose 1.11.4, Material 3 1.4.0), not the newest.** Spike result: BOM 2026.09.00 (Compose 1.12.1) fails the build, because nine of its artifacts require compileSdk 37 and the project compiles against 36. `compileSdk` stays 36: raising it is a separate decision that touches Robolectric's supported SDK levels.
5. **The Compose compiler is the Kotlin plugin** `org.jetbrains.kotlin.plugin.compose` at the pinned Kotlin version (2.4.20), under AGP 9.4.1's built in Kotlin.

## Alternatives considered

- **Views everywhere.** No new dependency, but the schedule builder, the pickers, the bottom sheet and navigation would be built by hand, and `ARCHITECTURE.md` section 11 already says Compose.
- **Rewrite the ring screen in Compose.** Rejected by D1: risk on the lock screen path for no gain.
- **Theme the three Phase 2 screens as views.** Rejected on the evidence in item 3.
- **compileSdk 37 with the newest BOM.** Not now; see item 4.

## Evidence

Spikes on Kotlin 2.4.20, AGP 9.4.1, Gradle 9.8.0 and compileSdk 36, in a throwaway clone that was never committed: `:android:assembleDebug` builds with Compose; a Compose Activity renders under Robolectric 4.17 native graphics at SDK 29 and 36; Roborazzi 1.76.0 records and verifies it. Details and workarounds are in `docs/phase-3-plan.md` section 2.
