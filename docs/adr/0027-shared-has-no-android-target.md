# 0027. shared has no android target — resolution taken

Date: 2026-09-29
Status: Accepted

## Context

`ARCHITECTURE.md` §2 originally described `shared` as "jvm + android targets in v1," implying a real `androidMain` Kotlin Multiplatform source set. CLAUDE.md invariant 1 says "no `android.*` imports, no `Context`, no platform types under `shared/`," with no stated exception. A Phase 0 planning review surfaced the direct tension: if `androidMain` exists as a genuine KMP target, it exists specifically so platform actuals can live there, which is exactly what invariant 1 forbids. One of the two had to give, and which one was not obvious without testing the actual Gradle mechanics — the interaction between a JVM-only Kotlin Multiplatform library and an Android application module consuming it is a documented source of variant-resolution friction (a JVM-only KMP target's outgoing `org.gradle.jvm.environment=standard-jvm` attribute doesn't always satisfy an Android consumer's `org.gradle.jvm.environment=android` request cleanly across every AGP/Kotlin version combination).

## Decision

Dropped `androidTarget()` entirely. `shared` in v1 is `commonMain` + `jvmMain` only, with no `android` KMP target and therefore no `androidMain` source set. The `android` module consumes `shared`'s `jvm` artifact as an ordinary project dependency (`implementation(project(":shared"))`), exactly as it would consume any plain Kotlin/JVM library.

This was verified, not guessed. A throwaway spike (Kotlin 2.1.0, AGP 8.7.2, Gradle 9.0.0, `compileSdk 35`) configured `shared` with `jvm()` only and had a `com.android.application` module depend on it directly. The build resolved variants cleanly and proceeded through manifest merging, resource processing, desugaring, dex merging, and APK packaging (`assembleDebug`) with no attribute-matching error at any point — `BUILD SUCCESSFUL`. Branch A (drop the target) is therefore viable in this toolchain combination, and it's the version that makes invariant 1 literally true with zero exception, so it's preferred over the fallback (Branch B: keep `androidTarget()`, scope the import ban to `commonMain`/`jvmMain`/`commonTest`, and enforce a CI file-path allowlist on `androidMain`).

Platform-specific implementations that would have lived in `shared/androidMain` — principally the SQLDelight driver actual — live in the `android` module instead, behind an interface declared in `commonMain`.

## Alternatives considered

- Branch B (keep the `android` target, add a bounded `androidMain` allowlist enforced by CI) — not needed once the spike showed Branch A works cleanly, but retained as the documented fallback if a future AGP/Kotlin upgrade reintroduces the variant-resolution friction this spike didn't hit.
- Leaving the contradiction unresolved and deciding case by case — rejected; CLAUDE.md and `ARCHITECTURE.md` are about to be treated as authority for the rest of the project, and an internally contradictory invariant would get "resolved" inconsistently by whoever hits it first.

## Consequences

`shared`'s KMP shape is now `commonMain` + `jvmMain` in v1, with `android` and `ios` both added later as genuine future targets if and when they're needed — today, `android` doesn't need its own KMP target at all, since a plain project dependency on the `jvm` artifact already works. The import-ban CI check (Phase 0) scans the whole of `shared/` with no carve-out, matching the literal text of invariant 1 exactly.

## Verification note — dex-time linkage (2026-09-29)

The original spike proved dependency *resolution* (Gradle picked a compatible variant and the build reached `assembleDebug`), but `shared` had no code and no file in `android` referenced it, so it didn't prove a `commonMain` declaration is actually reachable from Android code at dex time — a real gap, since variant resolution and symbol linkage are different failure modes.

Closed before any Phase 1 code, in the real repo rather than the scratch spike: added a throwaway `object LinkCheck { const val MARKER = ... }` to `shared/src/commonMain`, referenced it from a throwaway file in `android/src/main/kotlin`, and ran `:android:assembleDebug`. `compileDebugKotlin` executed (not `NO-SOURCE`) and succeeded, meaning the Kotlin compiler resolved the cross-module reference. Grepping the merged dex output confirmed both halves landed in the APK's class set: `LinkCheck` appears in `mergeLibDexDebug`'s `classes.dex` (the class from `shared`), and `LinkCheckUser`/`LinkCheckUserKt` appear in `mergeProjectDexDebug`'s `classes.dex` (the referencing code in `android`). Both throwaway files were deleted afterward; the repo rebuilds clean without them.

This closes the gap: a `commonMain` declaration is confirmed reachable from Android code through to dex, not just resolvable as a Gradle dependency.
