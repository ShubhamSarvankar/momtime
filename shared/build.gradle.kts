import org.gradle.api.GradleException
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}

sqldelight {
    databases {
        create("MomTimeDatabase") {
            packageName.set("com.momtime.shared.data")
            verifyMigrations.set(true)
            // VerifyMigrationTask reads its baseline snapshot from the SQLDelight *source*
            // folder, not from a build-output directory — this .db file is a committed
            // artifact (schema version 1, frozen), not a regenerated build product. Any future
            // .sq change without a corresponding .sqm migration makes verification fail against
            // it, which is invariant 2's enforcement mechanism working as intended.
            schemaOutputDirectory.set(layout.projectDirectory.dir("src/commonMain/sqldelight/databases"))
            // Android 10+ (minSdk 29) ships SQLite well past 3.24; the plugin's default
            // 3.18 dialect predates ON CONFLICT ... DO UPDATE, which water_goal's upsert uses.
            dialect(libs.sqldelight.dialect.sqlite338)
        }
    }
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt-shared.yml"))
    buildUponDefaultConfig = true
}

// Deliberately NOT wiring verify*Migration to depend on generate*Schema. The schema snapshot
// under src/commonMain/sqldelight/databases/ is a committed baseline (schema version 1,
// frozen) — regenerating it automatically on every check/CI run would make verification a
// tautology (it would silently re-derive "the expected baseline" from whatever .sq currently
// says, then trivially compare it to itself, defeating the entire point). Regenerating that
// snapshot is a deliberate, manual, reviewed step for when a schema version is intentionally
// finalised — see docs/adr/0035 — never an automatic build side effect.

kover {
    reports {
        filters {
            excludes {
                // SQLDelight-generated query/row classes are mechanical, not hand-written logic;
                // measuring them would misrepresent what invariant coverage actually verifies.
                // Named precisely (not by wildcard on the whole package) so a hand-written class
                // added to com.momtime.shared.data later isn't silently excluded by accident.
                classes(
                    "com.momtime.shared.data.MomTimeDatabase",
                    "com.momtime.shared.data.MomTimeDatabaseImpl",
                    "com.momtime.shared.data.Pregnancy",
                    "com.momtime.shared.data.Due_date_revision",
                    "com.momtime.shared.data.Schedule_template",
                    "com.momtime.shared.data.Schedule_template_nutrition_tag",
                    "com.momtime.shared.data.Alarm_slot_counter",
                    "com.momtime.shared.data.Occurrence",
                    "com.momtime.shared.data.Event",
                    "com.momtime.shared.data.Alarm_delivery_telemetry",
                    "com.momtime.shared.data.Water_goal",
                    "com.momtime.shared.data.Caregiver_link",
                    "com.momtime.shared.data.App_settings",
                    "com.momtime.shared.data.Interruption_budget",
                    "com.momtime.shared.data.Outbox_event",
                    "com.momtime.shared.data.Sync_state",
                    "com.momtime.shared.data.Content",
                    "com.momtime.shared.data.*Queries*",
                )
            }
        }
        verify {
            rule("shared must stay above 90% line coverage") {
                minBound(90)
            }
        }
    }
}

// SQLDelight registers its generated code as part of the commonMain source set, which
// otherwise pulls it into ktlint's scan too (detekt already excludes generated/build output
// by default). Exclude it explicitly rather than lint code nobody hand-writes.
ktlint {
    filter {
        exclude { entry -> entry.file.path.contains("${File.separator}generated${File.separator}") }
    }
}

// --- CLAUDE.md invariant 1: no android.*/androidx.* reference anywhere under shared/. ---
// shared declares no android KMP target (docs/adr/0027), so this scans the whole module
// with no carve-out — commonMain, commonTest, jvmMain, jvmTest.

fun findAndroidReferences(files: Iterable<File>): List<String> {
    val pattern = Regex("""\b(android|androidx)\.[A-Za-z_][A-Za-z0-9_.]*""")
    val offenders = mutableListOf<String>()
    files.forEach { file ->
        file.readLines().forEachIndexed { index, line ->
            if (pattern.containsMatchIn(line)) {
                offenders += "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}"
            }
        }
    }
    return offenders
}

val bannedSourceDirs =
    listOf("commonMain", "commonTest", "jvmMain", "jvmTest")
        .map { layout.projectDirectory.dir("src/$it") }

val verifyNoAndroidImports =
    tasks.register("verifyNoAndroidImports") {
        group = "verification"
        description =
            "Fails the build if any android.*/androidx.* reference exists under shared/src (CLAUDE.md invariant 1)"
        val ktFiles = bannedSourceDirs.flatMap { dir -> fileTree(dir) { include("**/*.kt") }.files }
        inputs.files(ktFiles)
        doLast {
            val offenders = findAndroidReferences(ktFiles)
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "android.*/androidx.* reference found under shared/ (CLAUDE.md invariant 1):\n" +
                        offenders.joinToString("\n"),
                )
            }
        }
    }

val selfTestVerifyNoAndroidImports =
    tasks.register("selfTestVerifyNoAndroidImports") {
        group = "verification"
        description = "Proves verifyNoAndroidImports actually detects a violation, using a throwaway fixture file"
        doLast {
            val fixtureDir =
                layout.buildDirectory
                    .dir("android-import-ban-fixture")
                    .get()
                    .asFile
            fixtureDir.deleteRecursively()
            fixtureDir.mkdirs()
            val fixture = File(fixtureDir, "Fixture.kt")
            fixture.writeText(
                "package fixture\n\nimport android.content.Context\n\nclass Fixture(val c: Context)\n",
            )
            val offenders = findAndroidReferences(listOf(fixture))
            if (offenders.isEmpty()) {
                throw GradleException(
                    "verifyNoAndroidImports failed to detect a deliberately bad fixture file - the check is broken.",
                )
            }
            fixtureDir.deleteRecursively()
            logger.lifecycle(
                "verifyNoAndroidImports self-test passed: deliberately bad import was detected " +
                    "(${offenders.size} match(es)).",
            )
        }
    }

// --- CLAUDE.md invariant 8: no Clock.System call outside the DI module. ---
// The DI module is com.momtime.shared.di — the only place allowed to construct a real Clock;
// everything else takes one injected. Exempted in every source set, not just commonMain: a
// test verifying clockModule genuinely provides Clock.System legitimately needs to reference it
// too, and that test belongs in di's own package in whichever source set exercises it.

fun findClockSystemReferences(files: Iterable<File>): List<String> {
    val pattern = Regex("""Clock\.System""")
    val offenders = mutableListOf<String>()
    files.forEach { file ->
        file.readLines().forEachIndexed { index, line ->
            if (pattern.containsMatchIn(line)) {
                offenders += "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}"
            }
        }
    }
    return offenders
}

val diExemptDirs =
    listOf("commonMain", "commonTest", "jvmMain", "jvmTest")
        .map { layout.projectDirectory.dir("src/$it/kotlin/com/momtime/shared/di").asFile }

val clockCheckedSourceDirs =
    listOf("commonMain", "commonTest", "jvmMain", "jvmTest")
        .map { layout.projectDirectory.dir("src/$it") }

val verifyNoClockSystem =
    tasks.register("verifyNoClockSystem") {
        group = "verification"
        description = "Fails the build if Clock.System is referenced outside the DI module (CLAUDE.md invariant 8)"
        val ktFiles =
            clockCheckedSourceDirs
                .flatMap { dir -> fileTree(dir) { include("**/*.kt") }.files }
                .filterNot { file -> diExemptDirs.any { file.startsWith(it) } }
        inputs.files(ktFiles)
        doLast {
            val offenders = findClockSystemReferences(ktFiles)
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "Clock.System referenced outside com.momtime.shared.di (CLAUDE.md invariant 8):\n" +
                        offenders.joinToString("\n"),
                )
            }
        }
    }

val selfTestVerifyNoClockSystem =
    tasks.register("selfTestVerifyNoClockSystem") {
        group = "verification"
        description = "Proves verifyNoClockSystem actually detects a violation, using a throwaway fixture file"
        doLast {
            val fixtureDir =
                layout.buildDirectory
                    .dir("clock-system-ban-fixture")
                    .get()
                    .asFile
            fixtureDir.deleteRecursively()
            fixtureDir.mkdirs()
            val fixture = File(fixtureDir, "Fixture.kt")
            fixture.writeText(
                "package fixture\n\nimport kotlinx.datetime.Clock\n\nval now = Clock.System.now()\n",
            )
            val offenders = findClockSystemReferences(listOf(fixture))
            if (offenders.isEmpty()) {
                throw GradleException(
                    "verifyNoClockSystem failed to detect a deliberately bad fixture file - the check is broken.",
                )
            }
            fixtureDir.deleteRecursively()
            logger.lifecycle(
                "verifyNoClockSystem self-test passed: deliberate Clock.System reference was detected " +
                    "(${offenders.size} match(es)).",
            )
        }
    }

tasks.named("check") {
    dependsOn(
        verifyNoAndroidImports,
        selfTestVerifyNoAndroidImports,
        verifyNoClockSystem,
        selfTestVerifyNoClockSystem,
        "koverVerify",
    )
}
