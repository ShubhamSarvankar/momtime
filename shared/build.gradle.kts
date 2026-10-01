import org.gradle.api.GradleException
import java.io.File
import java.util.Locale

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

// Classes excluded from coverage. Each pattern must match at least one compiled class: the gate
// (verifyBranchCoverage) fails if one matches nothing, so a misspelt or renamed exclusion cannot
// pass silently. See ADR 0038 and ADR 0039.
val coverageExcludedClasses =
    listOf(
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

kover {
    reports {
        filters {
            excludes {
                // SQLDelight-generated query/row classes are mechanical, not hand-written logic;
                // measuring them would misrepresent what invariant coverage actually verifies.
                // Named precisely (not by wildcard on the whole package) so a hand-written class
                // added to com.momtime.shared.data later isn't silently excluded by accident.
                classes(*coverageExcludedClasses.toTypedArray())
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

// --- Branch coverage gate (ADR 0038, made fail-closed by ADR 0039). ---
// Kover 0.9 cannot attach a filter to a verification rule, so per-package branch thresholds are
// checked from Kover's own XML report by this task. Coverage is the floor, not the evidence: the
// mutation checks recorded in each PR are the evidence.
//
// A check that is configured but silently applies to nothing is the failure to design against (the
// generated-code exclusion once named the wrong package and matched nothing for all of Phase 1). So
// the gate fails closed: it fails if the report is missing, if a gated package is absent from the
// report or has no branches, and if any exclusion pattern matches no compiled class.
val branchCoverageThresholds =
    mapOf(
        "com/momtime/shared/engine" to 95,
        "com/momtime/shared/domain" to 85,
        "com/momtime/shared/data" to 85,
    )

data class BranchFigure(
    val covered: Int,
    val missed: Int,
) {
    val total get() = covered + missed
    val percent get() = if (total == 0) 0.0 else 100.0 * covered / total

    fun meets(threshold: Int) = total > 0 && covered * 100 >= threshold * total

    override fun toString() = "$covered/$total (" + "%.1f".format(Locale.ROOT, percent) + "%)"
}

/** The package-level BRANCH counter of every package in a JaCoCo-style XML report. */
fun branchFigures(report: File): Map<String, BranchFigure> {
    val factory =
        javax.xml.parsers.DocumentBuilderFactory
            .newInstance()
    // The report names an external DTD; do not try to fetch it.
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    val doc = factory.newDocumentBuilder().parse(report)
    val figures = mutableMapOf<String, BranchFigure>()
    val packages = doc.getElementsByTagName("package")
    for (i in 0 until packages.length) {
        val pkg = packages.item(i) as org.w3c.dom.Element
        // Only the package's own counters. Its classes and source files carry counters of their own.
        val children = pkg.childNodes
        for (j in 0 until children.length) {
            val node = children.item(j)
            if (node is org.w3c.dom.Element && node.tagName == "counter" && node.getAttribute("type") == "BRANCH") {
                figures[pkg.getAttribute("name")] =
                    BranchFigure(node.getAttribute("covered").toInt(), node.getAttribute("missed").toInt())
            }
        }
    }
    return figures
}

fun branchCoverageProblems(
    report: File,
    thresholds: Map<String, Int>,
): List<String> {
    if (!report.isFile) return listOf("coverage report is missing: ${report.path}")
    val figures = branchFigures(report)
    val problems = mutableListOf<String>()
    for ((pkg, threshold) in thresholds) {
        val figure = figures[pkg]
        when {
            figure == null -> problems += "$pkg: gated package is absent from the coverage report"
            figure.total == 0 -> problems += "$pkg: gated package has no branches in the coverage report"
            !figure.meets(threshold) -> problems += "$pkg: branch coverage $figure is below $threshold%"
        }
    }
    return problems
}

/** Kover class patterns: `*` matches any characters, `?` one character, otherwise an exact name. */
fun patternToRegex(pattern: String) =
    Regex(
        pattern
            .map { c ->
                if (c ==
                    '*'
                ) {
                    ".*"
                } else if (c == '?') {
                    "."
                } else {
                    Regex.escape(c.toString())
                }
            }.joinToString(""),
    )

fun exclusionsMatchingNothing(
    patterns: List<String>,
    classNames: Collection<String>,
): List<String> = patterns.filter { pattern -> classNames.none { patternToRegex(pattern).matches(it) } }

fun compiledClassNames(classesDir: File): List<String> =
    classesDir
        .walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .map {
            it
                .relativeTo(classesDir)
                .path
                .removeSuffix(".class")
                .replace(File.separatorChar, '.')
        }.toList()

val verifyBranchCoverage =
    tasks.register("verifyBranchCoverage") {
        group = "verification"
        description = "Fails the build if branch coverage is below threshold, or the gate cannot be trusted (ADR 0039)"
        dependsOn("koverXmlReport", "compileKotlinJvm")
        val report = layout.buildDirectory.file("reports/kover/report.xml")
        val classesDir = layout.buildDirectory.dir("classes/kotlin/jvm/main")
        doLast {
            val problems = mutableListOf<String>()
            problems += branchCoverageProblems(report.get().asFile, branchCoverageThresholds)
            val dir = classesDir.get().asFile
            if (!dir.isDirectory) {
                problems += "compiled classes directory is missing, so exclusions cannot be checked: ${dir.path}"
            } else {
                exclusionsMatchingNothing(coverageExcludedClasses, compiledClassNames(dir)).forEach {
                    problems += "coverage exclusion matches no compiled class: $it"
                }
            }
            if (problems.isEmpty()) {
                branchFigures(report.get().asFile).filterKeys { it in branchCoverageThresholds }.forEach { (pkg, fig) ->
                    logger.lifecycle("branch coverage $pkg: $fig (gate ${branchCoverageThresholds.getValue(pkg)}%)")
                }
            } else {
                throw GradleException("Branch coverage gate failed (ADR 0039):\n" + problems.joinToString("\n"))
            }
        }
    }

val selfTestVerifyBranchCoverage =
    tasks.register("selfTestVerifyBranchCoverage") {
        group = "verification"
        description = "Proves the gate computes the right figures from known counters and fails closed"
        doLast {
            val dir =
                layout.buildDirectory
                    .dir("branch-coverage-gate-fixture")
                    .get()
                    .asFile
            dir.deleteRecursively()
            dir.mkdirs()
            // Package p: 47/50 branches, with LINE counters and nested class and source-file counters
            // that must NOT be read as the package's figure. Package z has no branches at all.
            val report =
                File(dir, "report.xml").also {
                    it.writeText(
                        "<report name=\"x\">" +
                            "<package name=\"p\">" +
                            "<class name=\"p/A\"><counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/></class>" +
                            "<sourcefile name=\"A.kt\">" +
                            "<counter type=\"BRANCH\" missed=\"2\" covered=\"2\"/></sourcefile>" +
                            "<counter type=\"LINE\" missed=\"10\" covered=\"90\"/>" +
                            "<counter type=\"BRANCH\" missed=\"3\" covered=\"47\"/>" +
                            "</package>" +
                            "<package name=\"z\"><counter type=\"BRANCH\" missed=\"0\" covered=\"0\"/></package>" +
                            "<package name=\"q\">" +
                            "<counter type=\"BRANCH\" missed=\"1\" covered=\"9\"/>" +
                            "<class name=\"q/B\"><counter type=\"BRANCH\" missed=\"5\" covered=\"0\"/></class>" +
                            "</package>" +
                            "</report>",
                    )
                }
            val failures = mutableListOf<String>()

            fun expect(
                what: String,
                actual: Any?,
                expected: Any?,
            ) {
                if (actual != expected) failures += "$what: expected <$expected> but was <$actual>"
            }

            // 1. The computed figures, not just a pass or fail.
            val figures = branchFigures(report)
            expect("package p figure", figures["p"], BranchFigure(covered = 47, missed = 3))
            expect("package p total", figures.getValue("p").total, 50)
            expect("package p percent", "%.1f".format(Locale.ROOT, figures.getValue("p").percent), "94.0")
            expect("package p rendering", figures.getValue("p").toString(), "47/50 (94.0%)")
            expect("package z figure", figures["z"], BranchFigure(0, 0))
            // Package q lists its own counter BEFORE a nested class counter: the package figure is 9/10
            // whichever order the counters appear in.
            expect("package q figure", figures["q"], BranchFigure(covered = 9, missed = 1))

            // 2. Thresholds, including exactly at the threshold.
            expect("94 is met by 47/50", branchCoverageProblems(report, mapOf("p" to 94)), emptyList<String>())
            expect(
                "95 is not met by 47/50",
                branchCoverageProblems(report, mapOf("p" to 95)),
                listOf("p: branch coverage 47/50 (94.0%) is below 95%"),
            )

            // 3. Fail closed.
            expect(
                "missing report",
                branchCoverageProblems(File(dir, "nope.xml"), mapOf("p" to 85)),
                listOf("coverage report is missing: ${File(dir, "nope.xml").path}"),
            )
            expect(
                "absent package",
                branchCoverageProblems(report, mapOf("renamed" to 85)),
                listOf("renamed: gated package is absent from the coverage report"),
            )
            expect(
                "package with no branches",
                branchCoverageProblems(report, mapOf("z" to 85)),
                listOf("z: gated package has no branches in the coverage report"),
            )

            // 4. Exclusion patterns that match nothing.
            val classes = listOf("a.b.Foo", "a.b.FooQueries", "a.b.Bar", "a.b.Outer\$Inner")
            expect(
                "unmatched exclusions",
                exclusionsMatchingNothing(
                    listOf("a.b.Foo", "a.b.*Queries*", "a.b.Outer\$*", "x.y.Gone", "a.b.Fo"),
                    classes,
                ),
                listOf("x.y.Gone", "a.b.Fo"),
            )

            dir.deleteRecursively()
            if (failures.isNotEmpty()) {
                throw GradleException("verifyBranchCoverage self-test failed:\n" + failures.joinToString("\n"))
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
