import org.gradle.api.GradleException
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
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
            schemaOutputDirectory.set(layout.buildDirectory.dir("dbSchema"))
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
// The DI module is com.momtime.shared.di (src/commonMain/kotlin/com/momtime/shared/di) —
// the only place allowed to construct a real Clock; everything else takes one injected.

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

val diExemptDir = layout.projectDirectory.dir("src/commonMain/kotlin/com/momtime/shared/di")

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
                .filterNot { it.startsWith(diExemptDir.asFile) }
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
    )
}
