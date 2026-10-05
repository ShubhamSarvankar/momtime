# 0084. Phase 3 dependencies: what is asked for, and what stays declined

Date: 2026-10-05
Status: Accepted (2026-10-05, in the review of PR #26 by Claude (technical review)). **The list was approved by Shubham in the review of the first draft (2026-10-05), as written, together with Nunito, Noto Sans Devanagari and the 21 Material Symbols icons of ADR 0085.** Each artifact enters the build in the pull request that first uses it.

Decided by the Phase 3 planning session, for review. Versions were resolved live on 2026-10-05 from Google's Maven repository and Maven Central, and each "used in the spike" entry was built and run on Kotlin 2.4.20, AGP 9.4.1, Gradle 9.8.0 and compileSdk 36.

## Asked for

| Artifact | Version | Scope | Why | Cost of doing without |
|---|---|---|---|---|
| Gradle plugin `org.jetbrains.kotlin.plugin.compose` | 2.4.20 (the Kotlin version) | build | The Compose compiler | No Compose |
| `androidx.compose:compose-bom` | 2026.06.01 | main, test (platform) | Pins the Compose artifacts to 1.11.4 and Material 3 to 1.4.0, the newest set that compiles against SDK 36 (ADR 0073) | Each artifact pinned by hand |
| `androidx.compose.ui:ui` | by BOM (1.11.4) | main | Compose itself | No Compose |
| `androidx.compose.foundation:foundation` | by BOM (1.11.4) | main | Layout, lists, scrolling | No Compose |
| `androidx.compose.material3:material3` | by BOM (1.4.0) | main | The bottom sheet, top bar, navigation bar, text fields, time and date pickers | Weeks of hand built, accessibility tested controls |
| `androidx.activity:activity-compose` | 1.13.0 | main | `ComponentActivity.setContent`, `BackHandler` | No host for Compose |
| `androidx.compose.ui:ui-test-junit4` | by BOM (1.11.4) | test | Compose tests under Robolectric: finding nodes, clicking, the test clock | No behavioural test of any screen |
| `io.github.takahirom.roborazzi:roborazzi` | 1.76.0 (already pinned in the catalog) | test | Screenshot capture and comparison | No screenshot gate; a named exit criterion |
| `io.github.takahirom.roborazzi:roborazzi-compose` | 1.76.0 | test | Capturing a composable | Captures of whole windows only |
| Gradle plugin `io.github.takahirom.roborazzi` | 1.76.0 (already in the catalog, applied nowhere) | build | The record and verify tasks | Hand written task wiring |

Fonts and icons are files copied into the repo with their licences (ADR 0085), not artifacts.

**What these bring transitively, read from the spike's merged release manifest:** no new permission (`verifyManifestPermissions` passed unchanged); one exported receiver, `androidx.profileinstaller.ProfileInstallReceiver`, guarded by `DUMP` (ADR 0078); `androidx.startup.InitializationProvider` entries, unexported; `kotlinx-coroutines`, `androidx.lifecycle`, `androidx.core` and `androidx.test` classes on the classpath, none of which an android source may import (ADR 0081, and the existing rule that anything imported is declared).

## What is imported directly is declared directly

The standing rule (`IMPLEMENTATION_PLAN.md`, Working conventions) applies to every Phase 3 pull request: an artifact whose classes a source file imports is declared in the build, never left to arrive transitively. Each pull request section of the plan lists the androidx packages its code imports and the artifact that declares each.

**The sub modules, approved by Shubham in the second review.** The approved list names three Compose libraries (`ui`, `foundation`, `material3`), `activity-compose` and `ui-test-junit4`. Compose and Activity are split into more modules than that, and ordinary screen code and tests import from eight of them. They are declared by name, and nothing else is added by this approval:

| Artifact | Version | Scope | Imported as |
|---|---|---|---|
| `androidx.compose.runtime:runtime` | by BOM | main | `androidx.compose.runtime.*` |
| `androidx.compose.runtime:runtime-saveable` | by BOM | main | `androidx.compose.runtime.saveable.*` |
| `androidx.compose.ui:ui-graphics` | by BOM | main | `androidx.compose.ui.graphics.*` |
| `androidx.compose.ui:ui-text` | by BOM | main | `androidx.compose.ui.text.*` |
| `androidx.compose.ui:ui-unit` | by BOM | main | `androidx.compose.ui.unit.*` |
| `androidx.compose.foundation:foundation-layout` | by BOM | main | `androidx.compose.foundation.layout.*` |
| `androidx.compose.ui:ui-test` | by BOM | test | `androidx.compose.ui.test.*` |
| `androidx.activity:activity` | 1.13.0 | main | `androidx.activity.ComponentActivity` |

No version is written by hand where the BOM supplies one. `androidx.activity:activity` has no BOM, so it is written at 1.13.0, the version `activity-compose` 1.13.0 fixes. All eight were already on the classpath through the approved libraries; declaring them changes no resolved version. `DeclaredImportsTest` (plan, PR 7) keeps the rule from then on.

## Reconsidered and still declined

| Artifact | Reason |
|---|---|
| `koin-android`, `koin-compose` | The UI graph is installed as the other entry points are (ADR 0082) |
| `androidx.test` artifacts, declared directly | Tests use Robolectric's `buildActivity` and the Compose rule from `ui-test-junit4`; no test imports an `androidx.test` class |
| `androidx.compose.ui:ui-test-manifest` | It only declares `ComponentActivity`; the debug manifest declares it, unexported, in one line |
| `kotlinx-coroutines-test`, `kotlinx-coroutines-core` in android | No coroutine API in android sources (ADR 0081) |
| A direct `androidx.core` | No `NotificationCompat`, no `ContextCompat`: the framework classes serve API 29 and above |
| `navigation-compose`, `lifecycle-viewmodel-compose` | ADR 0081, ADR 0082 |
| `androidx.glance:glance-appwidget` | ADR 0083 |
| `androidx.appcompat` | Not needed for the locale override (ADR 0082) |
| An icon artifact (`material-icons-extended`) | Vector assets are copied in (ADR 0085) |
| ML Kit | Missions are not in Phase 3 (ADR 0088) |

## Alternatives considered

- **The newest Compose BOM (2026.09.00) with compileSdk 37.** ADR 0073.
