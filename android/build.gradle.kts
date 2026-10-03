import org.gradle.api.GradleException
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.momtime.android"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.momtime.android"
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and resources on the test classpath.
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric's SDK 36 sandbox reaches into JDK internals; on JDK 17 and later those
                // packages must be opened to it (Robolectric's "Using Robolectric on JDK 17+" guidance).
                test.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.nio=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                )
            }
        }
    }
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}

dependencies {
    implementation(project(":shared"))
    // android's own declarations of what its sources import. shared declares these as
    // `implementation`, so they are not visible here unless declared.
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.runtime)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// --- Structural check: no generated SQLDelight query type is reachable from android. ---
// The generated `updateOccurrenceState` query is reachable through `database.occurrenceQueries`.
// The terminal trigger (ADR 0037) guards terminal states, but a non terminal state change through
// that query would skip the event append, so no android source may be able to name it (invariant 3).
// Rules, applied to every android source file (main, test, androidTest):
//   1. no identifier ending in `Queries`, anywhere, including the DI package;
//   2. no `MomTimeDatabase` outside the android DI package (com.momtime.android.di);
//   3. no reference to com.momtime.shared.data.shared (the generated implementation package);
//   4. no wildcard import of com.momtime.shared.data, which would bring the generated row classes in;
//   5. no reference to a generated row class by its qualified name. The names are read from the code
//      SQLDelight generated, not listed here, so a new table is covered with no edit.
// Hand written types in that package (the repository interfaces) stay importable.

val androidDiPath = "com/momtime/android/di"

fun isInDiPackage(file: File) = file.invariantSeparatorsPath.contains("/$androidDiPath/")

/** Top level type names in the code SQLDelight generated for the shared database. */
fun generatedSharedTypeNames(generatedDir: File): Set<String> =
    generatedDir
        .listFiles { f -> f.isFile && f.extension == "kt" }
        .orEmpty()
        .map { it.nameWithoutExtension }
        .toSet()

fun findGeneratedTypeReferences(
    files: Iterable<File>,
    generatedNames: Set<String>,
    queriesPattern: Regex = Regex("""\b[A-Za-z0-9_]*Queries\b"""),
): List<String> {
    val rowNames = (generatedNames - "MomTimeDatabase").filterNot { it.endsWith("Queries") }
    val rowPattern =
        if (rowNames.isEmpty()) null else Regex("""com\.momtime\.shared\.data\.(${rowNames.joinToString("|")})\b""")
    val databasePattern = Regex("""\bMomTimeDatabase\b""")
    val implPattern = Regex("""com\.momtime\.shared\.data\.shared\b""")
    val wildcardPattern = Regex("""import\s+com\.momtime\.shared\.data\.\*""")
    val offenders = mutableListOf<String>()
    files.forEach { file ->
        file.readLines().forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@forEachIndexed
            val line = raw.substringBefore("//")
            val problems = mutableListOf<String>()
            if (queriesPattern.containsMatchIn(line)) problems += "generated Queries type"
            if (!isInDiPackage(file) && databasePattern.containsMatchIn(line)) {
                problems += "MomTimeDatabase outside com.momtime.android.di"
            }
            if (implPattern.containsMatchIn(line)) problems += "generated implementation package"
            if (wildcardPattern.containsMatchIn(line)) problems += "wildcard import of com.momtime.shared.data"
            if (rowPattern != null && rowPattern.containsMatchIn(line)) problems += "generated row class"
            if (problems.isNotEmpty()) {
                offenders += "${file.relativeTo(projectDir)}:${index + 1}: ${problems.joinToString(", ")}: $trimmed"
            }
        }
    }
    return offenders
}

val androidKotlinFiles =
    listOf("main", "test", "androidTest")
        .map { layout.projectDirectory.dir("src/$it") }
        .flatMap { dir -> fileTree(dir) { include("**/*.kt") }.files }

val sharedGeneratedDir =
    rootProject.layout.projectDirectory
        .dir("shared/build/generated/sqldelight/code/MomTimeDatabase/commonMain/com/momtime/shared/data")
        .asFile

val verifyNoGeneratedQueries =
    tasks.register("verifyNoGeneratedQueries") {
        group = "verification"
        description = "Fails if an android source can reach a generated SQLDelight type (Phase 2 structural check)"
        dependsOn(":shared:generateCommonMainMomTimeDatabaseInterface")
        inputs.files(androidKotlinFiles)
        doLast {
            val names = generatedSharedTypeNames(sharedGeneratedDir)
            // Fail closed: a generated directory that is missing or lacks the database class means
            // the row class rule would apply to nothing.
            if ("MomTimeDatabase" !in names || names.none { it.endsWith("Queries") }) {
                throw GradleException(
                    "verifyNoGeneratedQueries found no generated types in $sharedGeneratedDir: the check would " +
                        "apply to nothing",
                )
            }
            val offenders = findGeneratedTypeReferences(androidKotlinFiles, names)
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "android source reaches a generated SQLDelight type (Phase 2 structural check):\n" +
                        offenders.joinToString("\n"),
                )
            }
        }
    }

val selfTestVerifyNoGeneratedQueries =
    tasks.register("selfTestVerifyNoGeneratedQueries") {
        group = "verification"
        description = "Proves verifyNoGeneratedQueries detects each violation and passes clean code, with fixtures"
        doLast {
            val root =
                layout.buildDirectory
                    .dir("generated-queries-fixture")
                    .get()
                    .asFile
            root.deleteRecursively()
            val names = setOf("MomTimeDatabase", "OccurrenceQueries", "Event", "Occurrence")

            fun fixture(
                path: String,
                body: String,
            ): File =
                File(root, path).also {
                    it.parentFile.mkdirs()
                    it.writeText("package fixture\n\n$body\n")
                }

            val queriesUse = fixture("app/QueriesUse.kt", "fun f(db: Any) = db.occurrenceQueries")
            val queriesImport = fixture("app/QueriesImport.kt", "import com.momtime.shared.data.OccurrenceQueries")
            val queriesInDi = fixture("$androidDiPath/QueriesInDi.kt", "fun f(db: Any) = db.occurrenceQueries")
            val databaseOutside = fixture("app/DatabaseOutside.kt", "import com.momtime.shared.data.MomTimeDatabase")
            val impl = fixture("app/Impl.kt", "import com.momtime.shared.data.shared.MomTimeDatabaseImpl")
            val wildcard = fixture("app/Wildcard.kt", "import com.momtime.shared.data.*")
            val rowClass = fixture("app/RowClass.kt", "import com.momtime.shared.data.Occurrence")
            val databaseInDi =
                fixture("$androidDiPath/DatabaseInDi.kt", "import com.momtime.shared.data.MomTimeDatabase")
            val clean =
                fixture(
                    "app/Clean.kt",
                    "import com.momtime.shared.data.OccurrenceRepository\n" +
                        "import com.momtime.shared.domain.Event\n\n" +
                        "// OccurrenceQueries MomTimeDatabase in a comment\n" +
                        "/** MomTimeDatabase in a doc comment */\nfun f(r: OccurrenceRepository) = r",
                )

            fun hits(vararg files: File) = findGeneratedTypeReferences(files.toList(), names).size
            val failures = mutableListOf<String>()
            val mustBeFlagged =
                mapOf(
                    "an identifier ending in Queries" to queriesUse,
                    "a Queries import" to queriesImport,
                    "a Queries identifier inside the DI package" to queriesInDi,
                    "MomTimeDatabase outside the DI package" to databaseOutside,
                    "the generated implementation package" to impl,
                    "a wildcard import of the data package" to wildcard,
                    "a generated row class by qualified name" to rowClass,
                )
            for ((what, file) in mustBeFlagged) {
                if (hits(file) != 1) failures += "not detected exactly once: $what"
            }
            if (hits(databaseInDi) != 0) failures += "MomTimeDatabase inside the DI package was flagged"
            if (hits(clean) !=
                0
            ) {
                failures += "clean code was flagged: ${findGeneratedTypeReferences(listOf(clean), names)}"
            }
            root.deleteRecursively()
            if (failures.isNotEmpty()) {
                throw GradleException("verifyNoGeneratedQueries self-test failed:\n" + failures.joinToString("\n"))
            }
            logger.lifecycle("verifyNoGeneratedQueries self-test passed.")
        }
    }

// --- CLAUDE.md invariant 8, android half: no Clock.System and no System.currentTimeMillis outside the
// android DI package. SystemClock and the boot count join the ban when the injected seam exists (PR 4).

fun findWallClockReferences(files: Iterable<File>): List<String> {
    val pattern = Regex("""\bClock\.System\b|\bSystem\.currentTimeMillis\b""")
    val offenders = mutableListOf<String>()
    files.filterNot { isInDiPackage(it) }.forEach { file ->
        file.readLines().forEachIndexed { index, line ->
            if (pattern.containsMatchIn(line)) {
                offenders += "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}"
            }
        }
    }
    return offenders
}

val verifyNoClockSystem =
    tasks.register("verifyNoClockSystem") {
        group = "verification"
        description = "Fails if android code reads the wall clock outside com.momtime.android.di (invariant 8)"
        inputs.files(androidKotlinFiles)
        doLast {
            val offenders = findWallClockReferences(androidKotlinFiles)
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "Clock.System or System.currentTimeMillis outside com.momtime.android.di (invariant 8):\n" +
                        offenders.joinToString("\n"),
                )
            }
        }
    }

val selfTestVerifyNoClockSystem =
    tasks.register("selfTestVerifyNoClockSystem") {
        group = "verification"
        description = "Proves the android clock check detects each call and exempts the DI package, with fixtures"
        doLast {
            val root =
                layout.buildDirectory
                    .dir("clock-ban-fixture")
                    .get()
                    .asFile
            root.deleteRecursively()

            fun fixture(
                path: String,
                body: String,
            ): File =
                File(root, path).also {
                    it.parentFile.mkdirs()
                    it.writeText("package fixture\n\n$body\n")
                }

            val system = fixture("app/System.kt", "val a = kotlin.time.Clock.System.now()")
            val millis = fixture("app/Millis.kt", "val b = System.currentTimeMillis()")
            val inDi = fixture("$androidDiPath/InDi.kt", "val c = kotlin.time.Clock.System.now()")
            val failures = mutableListOf<String>()
            if (findWallClockReferences(listOf(system)).size != 1) failures += "Clock.System was not detected"
            if (findWallClockReferences(listOf(millis)).size !=
                1
            ) {
                failures += "System.currentTimeMillis was not detected"
            }
            if (findWallClockReferences(listOf(inDi)).isNotEmpty()) failures += "the DI package was not exempted"
            root.deleteRecursively()
            if (failures.isNotEmpty()) {
                throw GradleException("android verifyNoClockSystem self-test failed:\n" + failures.joinToString("\n"))
            }
            logger.lifecycle("android verifyNoClockSystem self-test passed.")
        }
    }

tasks.named("check") {
    dependsOn(
        verifyNoGeneratedQueries,
        selfTestVerifyNoGeneratedQueries,
        verifyNoClockSystem,
        selfTestVerifyNoClockSystem,
    )
}
