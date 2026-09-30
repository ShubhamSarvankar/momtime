import org.gradle.api.GradleException
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
    jvm()
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt-shared.yml"))
    buildUponDefaultConfig = true
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

tasks.named("check") {
    dependsOn(verifyNoAndroidImports, selfTestVerifyNoAndroidImports)
}
