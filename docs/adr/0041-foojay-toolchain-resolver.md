# 0041. Foojay toolchain resolver for the JDK 21 toolchain

Date: 2026-10-03
Status: Accepted

## Context

`shared` and `server` pin `jvmToolchain(21)`. Gradle can only use a toolchain it can find. On a machine with no JDK 21 visible to it, the build failed with "Cannot find a Java installation ... languageVersion=21 ... Toolchain download repositories have not been configured". This was found locally in Phase 1 close-out (the machine had JDK 25 and Android Studio's JBR, which Gradle did not register as a toolchain). The README told contributors to install JDK 21 by hand.

This is a new build dependency (invariant 7), so it needed explicit approval. Shubham approved it on 2026-10-03, with the conditions below.

## Decision

Add the settings plugin `org.gradle.toolchains.foojay-resolver-convention`, version **1.0.0**, pinned exactly in `settings.gradle.kts` (no dynamic or range version). It registers the Foojay Disco API as a toolchain download repository, so Gradle can fetch a missing JDK 21.

- **Vendor.** The toolchain is constrained to `JvmVendorSpec.ADOPTIUM` in `shared` and `server`. CI's `actions/setup-java@v4` uses `distribution: temurin`, which is Eclipse Temurin, the Adoptium build. Local and CI builds therefore compile on the same JDK distribution. Android Studio's JBR is not a match for this constraint; it still works as the Gradle launcher, and Temurin 21 is provisioned beside it for compilation.
- **CI does not download JDKs.** `setup-java` already installs Temurin 21, which toolchain detection finds, so nothing is provisioned. The CI log is checked for this (see the PR).
- **Dependency verification.** The repository has no Gradle dependency verification metadata (`gradle/verification-metadata.xml` does not exist), so there was nothing to update.
- **Not changed.** The `android` module sets no toolchain and compiles on the launcher JDK as before. The plugin does not change the launcher: Gradle must still be started on JDK 17 or later.

## Proof that it can fail

With a temporary `GRADLE_USER_HOME` containing `org.gradle.java.installations.auto-detect=false` and the launcher on JDK 25 (so no JDK 21 was visible), `./gradlew :shared:compileKotlinJvm`:

- without the plugin: failed with "Cannot find a Java installation ... {languageVersion=21, vendor=Eclipse Temurin ...}. Toolchain download repositories have not been configured.";
- with the plugin: provisioned `OpenJDK21U-jdk_x64_windows_hotspot_21-Eclipse-Temurin-21.0.12.1_1` into the temporary home and succeeded.

## Alternatives considered

- **Document a manual JDK 21 install only** (the previous state). Kept as a fallback in the README. Rejected as the sole answer: it is exactly what failed.
- **Declare `foojay-resolver` without a vendor constraint.** Rejected: a local build could compile on a different JDK distribution than CI.
- **A `java.toolchain` repository block written by hand.** Gradle has no built-in resolver implementation; one needs a plugin either way.

## Consequences

- A first local build with no Temurin 21 downloads about 200 MB from the Foojay Disco API and Adoptium. That is a network dependency at first build only, and the JDK is cached in the Gradle user home.
- The build now trusts one more plugin at configuration time. It is pinned to an exact version, and it is a settings plugin published by the Gradle project.
- A launcher on JDK 11 still fails before the plugin is read (README).
