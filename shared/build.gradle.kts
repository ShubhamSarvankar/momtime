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
                    // SQLDelight puts its generated implementation in the sub-package data.shared
                    // (Schema.create / Schema.migrate and the query wiring). Nothing hand-written
                    // lives there. An earlier exclusion named it under the wrong package and never
                    // matched. See ADR 0038.
                    "com.momtime.shared.data.shared.*",
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

// --- Branch coverage gate (ADR 0038). ---
// Kover 0.9 cannot attach a filter to a verification rule, so per-package branch thresholds are
// checked from Kover's own XML report by this task. Coverage is the floor, not the evidence: the
// mutation checks recorded in each PR are the evidence.
val branchCoverageThresholds =
    mapOf(
        "com/momtime/shared/engine" to 95,
        "com/momtime/shared/domain" to 85,
        "com/momtime/shared/data" to 85,
    )

fun branchCoverageShortfalls(
    report: File,
    thresholds: Map<String, Int>,
): List<String> {
    val factory =
        javax.xml.parsers.DocumentBuilderFactory
            .newInstance()
    // The JaCoCo-style report names an external DTD; do not try to fetch it.
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    val doc = factory.newDocumentBuilder().parse(report)
    val shortfalls = mutableListOf<String>()
    val packages = doc.getElementsByTagName("package")
    val found = mutableSetOf<String>()
    for (i in 0 until packages.length) {
        val pkg = packages.item(i) as org.w3c.dom.Element
        val name = pkg.getAttribute("name")
        val threshold = thresholds[name] ?: continue
        found += name
        val counters = pkg.childNodes
        for (j in 0 until counters.length) {
            val node = counters.item(j)
            if (node is org.w3c.dom.Element && node.tagName == "counter" && node.getAttribute("type") == "BRANCH") {
                val covered = node.getAttribute("covered").toInt()
                val total = covered + node.getAttribute("missed").toInt()
                val percent = if (total == 0) 100.0 else 100.0 * covered / total
                if (percent < threshold) {
                    shortfalls +=
                        "$name: branch coverage $covered/$total (%.1f%%) is below %d%%".format(percent, threshold)
                }
            }
        }
    }
    (thresholds.keys - found).forEach { shortfalls += "$it: package not found in the coverage report" }
    return shortfalls
}

val verifyBranchCoverage =
    tasks.register("verifyBranchCoverage") {
        group = "verification"
        description = "Fails the build if per-package branch coverage is below its threshold (ADR 0038)"
        dependsOn("koverXmlReport")
        val report = layout.buildDirectory.file("reports/kover/report.xml")
        doLast {
            val shortfalls = branchCoverageShortfalls(report.get().asFile, branchCoverageThresholds)
            if (shortfalls.isNotEmpty()) {
                throw GradleException("Branch coverage gate failed (ADR 0038):\n" + shortfalls.joinToString("\n"))
            }
        }
    }

val selfTestVerifyBranchCoverage =
    tasks.register("selfTestVerifyBranchCoverage") {
        group = "verification"
        description = "Proves the branch coverage gate fails a package below its threshold and passes one above it"
        doLast {
            val dir =
                layout.buildDirectory
                    .dir("branch-coverage-gate-fixture")
                    .get()
                    .asFile
            dir.deleteRecursively()
            dir.mkdirs()

            fun fixture(
                covered: Int,
                missed: Int,
            ) = File(dir, "report-$covered-$missed.xml").also {
                it.writeText(
                    "<report name=\"x\"><package name=\"p\">" +
                        "<counter type=\"BRANCH\" missed=\"$missed\" covered=\"$covered\"/>" +
                        "</package></report>",
                )
            }
            val below = branchCoverageShortfalls(fixture(94, 6), mapOf("p" to 95))
            val atThreshold = branchCoverageShortfalls(fixture(95, 5), mapOf("p" to 95))
            val missing = branchCoverageShortfalls(fixture(1, 0), mapOf("absent" to 95))
            dir.deleteRecursively()
            if (below.size != 1 || atThreshold.isNotEmpty() || missing.size != 1) {
                throw GradleException(
                    "verifyBranchCoverage self-test failed: below=$below atThreshold=$atThreshold missing=$missing",
                )
            }
            logger.lifecycle("verifyBranchCoverage self-test passed.")
        }
    }

tasks.named("check") {
    dependsOn(
        verifyNoAndroidImports,
        selfTestVerifyNoAndroidImports,
        verifyNoClockSystem,
        selfTestVerifyNoClockSystem,
        "koverVerify",
        verifyBranchCoverage,
        selfTestVerifyBranchCoverage,
    )
}
