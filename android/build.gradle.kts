import org.gradle.api.GradleException
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.sqldelight)
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

// The android store: a second database, owned by this module, for what the shared schema must not hold
// (the delivery tier, the state of the device, the armed alarm record; ADR 0048). Same SQLite floor as
// the shared database (ADR 0042), a committed baseline snapshot and migration verification (ADR 0035).
sqldelight {
    databases {
        create("AndroidStoreDatabase") {
            packageName.set("com.momtime.android.store.db")
            verifyMigrations.set(true)
            schemaOutputDirectory.set(layout.projectDirectory.dir("src/main/sqldelight/databases"))
            dialect(libs.sqldelight.dialect.sqlite318)
        }
    }
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
//   1. no identifier ending in `Queries`, anywhere, including the DI package, except the android store's
//      own, and those only in its repository package (com.momtime.android.store);
//   2. no `MomTimeDatabase` outside the android DI package (com.momtime.android.di);
//   3. no reference to com.momtime.shared.data.shared (the generated implementation package);
//   4. no wildcard import of com.momtime.shared.data, which would bring the generated row classes in;
//   5. no reference to a generated row class by its qualified name. The names are read from the code
//      SQLDelight generated, not listed here, so a new table is covered with no edit;
//   6. `AndroidStoreDatabase`, the android store's database, only in the DI package and the store's
//      repository package;
//   7. the android store's generated row classes (com.momtime.android.store.db.*) only in the store's
//      repository package, and its generated implementation package nowhere.
// Hand written types in the shared data package (the repository interfaces) stay importable.

val androidDiPath = "com/momtime/android/di"
val androidStorePath = "com/momtime/android/store"

fun isInPackage(
    file: File,
    path: String,
) = file.invariantSeparatorsPath.contains("/$path/")

fun isInDiPackage(file: File) = isInPackage(file, androidDiPath)

fun isInStorePackage(file: File) = isInPackage(file, androidStorePath)

/** Top level type names in the code SQLDelight generated into [generatedDir]. */
fun generatedTypeNames(generatedDir: File): Set<String> =
    generatedDir
        .listFiles { f -> f.isFile && f.extension == "kt" }
        .orEmpty()
        .map { it.nameWithoutExtension }
        .toSet()

fun findGeneratedTypeReferences(
    files: Iterable<File>,
    sharedNames: Set<String>,
    storeNames: Set<String>,
    queriesPattern: Regex = Regex("""\b[A-Za-z0-9_]*Queries\b"""),
): List<String> {
    val sharedRowNames = (sharedNames - "MomTimeDatabase").filterNot { it.endsWith("Queries") }
    val sharedRowPattern =
        if (sharedRowNames.isEmpty()) {
            null
        } else {
            Regex("""com\.momtime\.shared\.data\.(${sharedRowNames.joinToString("|")})\b""")
        }
    val storeInternalNames = (storeNames - "AndroidStoreDatabase")
    val storeInternalPattern =
        if (storeInternalNames.isEmpty()) {
            null
        } else {
            Regex("""com\.momtime\.android\.store\.db\.(${storeInternalNames.joinToString("|")})\b""")
        }
    // The android store's own query identifiers: the class names and the property names that expose them.
    val storeQueryIdentifiers =
        storeNames
            .filter { it.endsWith("Queries") }
            .flatMap { listOf(it, it.replaceFirstChar { c -> c.lowercase() }) }
    val databasePattern = Regex("""\bMomTimeDatabase\b""")
    val storeDatabasePattern = Regex("""\bAndroidStoreDatabase\b""")
    val implPattern = Regex("""com\.momtime\.shared\.data\.shared\b""")
    val storeImplPattern = Regex("""com\.momtime\.android\.store\.db\.android\b""")
    val wildcardPattern = Regex("""import\s+com\.momtime\.shared\.data\.\*""")
    val storeWildcardPattern = Regex("""import\s+com\.momtime\.android\.store\.db\.\*""")
    val offenders = mutableListOf<String>()
    files.forEach { file ->
        file.readLines().forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@forEachIndexed
            val line = raw.substringBefore("//")
            // In the store's repository package its own query identifiers are allowed; nowhere else.
            val queriesLine =
                if (isInStorePackage(file)) {
                    storeQueryIdentifiers.fold(line) { acc, id -> acc.replace(Regex("""\b$id\b"""), "") }
                } else {
                    line
                }
            val inDi = isInDiPackage(file)
            val inStore = isInStorePackage(file)
            val problems = mutableListOf<String>()
            if (queriesPattern.containsMatchIn(queriesLine)) problems += "generated Queries type"
            if (!inDi && databasePattern.containsMatchIn(line)) {
                problems += "MomTimeDatabase outside com.momtime.android.di"
            }
            if (!inDi && !inStore && storeDatabasePattern.containsMatchIn(line)) {
                problems += "AndroidStoreDatabase outside com.momtime.android.di and com.momtime.android.store"
            }
            if (implPattern.containsMatchIn(line)) problems += "generated implementation package"
            if (storeImplPattern.containsMatchIn(line)) problems += "android store generated implementation package"
            if (wildcardPattern.containsMatchIn(line)) problems += "wildcard import of com.momtime.shared.data"
            if (!inStore && storeWildcardPattern.containsMatchIn(line)) {
                problems += "wildcard import of com.momtime.android.store.db"
            }
            if (sharedRowPattern != null && sharedRowPattern.containsMatchIn(line)) problems += "generated row class"
            if (!inStore && storeInternalPattern != null && storeInternalPattern.containsMatchIn(line)) {
                problems += "android store generated type outside com.momtime.android.store"
            }
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

val storeGeneratedDir =
    layout.buildDirectory
        .dir("generated/sqldelight/code/AndroidStoreDatabase/debug/com/momtime/android/store/db")
        .get()
        .asFile

val verifyNoGeneratedQueries =
    tasks.register("verifyNoGeneratedQueries") {
        group = "verification"
        description = "Fails if an android source can reach a generated SQLDelight type (Phase 2 structural check)"
        dependsOn(":shared:generateCommonMainMomTimeDatabaseInterface", "generateDebugAndroidStoreDatabaseInterface")
        inputs.files(androidKotlinFiles)
        doLast {
            val sharedNames = generatedTypeNames(sharedGeneratedDir)
            val storeNames = generatedTypeNames(storeGeneratedDir)
            // Fail closed: a generated directory that is missing or lacks the database class means
            // the rules that read it would apply to nothing.
            if ("MomTimeDatabase" !in sharedNames || sharedNames.none { it.endsWith("Queries") }) {
                throw GradleException(
                    "verifyNoGeneratedQueries found no generated types in $sharedGeneratedDir: the check would " +
                        "apply to nothing",
                )
            }
            if ("AndroidStoreDatabase" !in storeNames || storeNames.none { it.endsWith("Queries") }) {
                throw GradleException(
                    "verifyNoGeneratedQueries found no generated types in $storeGeneratedDir: the check would " +
                        "apply to nothing",
                )
            }
            val offenders = findGeneratedTypeReferences(androidKotlinFiles, sharedNames, storeNames)
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
            val sharedNames = setOf("MomTimeDatabase", "OccurrenceQueries", "Event", "Occurrence")
            val storeNames = setOf("AndroidStoreDatabase", "FireTelemetryQueries", "Fire_telemetry")

            fun fixture(
                path: String,
                body: String,
            ): File =
                File(root, path).also {
                    it.parentFile.mkdirs()
                    it.writeText("package fixture\n\n$body\n")
                }

            val dbQueries = "fun f(db: Any) = db.occurrenceQueries"
            val storeQueries = "fun f(db: Any) = db.fireTelemetryQueries"
            val storeDatabaseImport = "import com.momtime.android.store.db.AndroidStoreDatabase"
            val mustBeFlagged =
                mapOf(
                    "an identifier ending in Queries" to fixture("app/QueriesUse.kt", dbQueries),
                    "a Queries import" to
                        fixture("app/QueriesImport.kt", "import com.momtime.shared.data.OccurrenceQueries"),
                    "a Queries identifier inside the DI package" to fixture("$androidDiPath/QueriesInDi.kt", dbQueries),
                    "the shared database's Queries inside the store package" to
                        fixture("$androidStorePath/SharedQueries.kt", dbQueries),
                    "the store's Queries outside the store package" to fixture("app/StoreQueries.kt", storeQueries),
                    "the store's Queries inside the DI package" to
                        fixture("$androidDiPath/StoreQueries.kt", storeQueries),
                    "MomTimeDatabase outside the DI package" to
                        fixture("app/DatabaseOutside.kt", "import com.momtime.shared.data.MomTimeDatabase"),
                    "MomTimeDatabase inside the store package" to
                        fixture(
                            "$androidStorePath/SharedDatabase.kt",
                            "import com.momtime.shared.data.MomTimeDatabase",
                        ),
                    "AndroidStoreDatabase outside the DI and store packages" to
                        fixture("app/StoreDatabaseOutside.kt", storeDatabaseImport),
                    "a store row class outside the store package" to
                        fixture("app/StoreRow.kt", "import com.momtime.android.store.db.Fire_telemetry"),
                    "a store row class inside the DI package" to
                        fixture("$androidDiPath/StoreRow.kt", "import com.momtime.android.store.db.Fire_telemetry"),
                    "the store's generated implementation package" to
                        fixture(
                            "$androidStorePath/StoreImpl.kt",
                            "import com.momtime.android.store.db.android.AndroidStoreDatabaseImpl",
                        ),
                    "a wildcard import of the store's generated package outside the store" to
                        fixture("app/StoreWildcard.kt", "import com.momtime.android.store.db.*"),
                    "the shared generated implementation package" to
                        fixture("app/Impl.kt", "import com.momtime.shared.data.shared.MomTimeDatabaseImpl"),
                    "a wildcard import of the shared data package" to
                        fixture("app/Wildcard.kt", "import com.momtime.shared.data.*"),
                    "a shared row class by qualified name" to
                        fixture("app/RowClass.kt", "import com.momtime.shared.data.Occurrence"),
                )
            val mustPass =
                mapOf(
                    "MomTimeDatabase inside the DI package" to
                        fixture("$androidDiPath/DatabaseInDi.kt", "import com.momtime.shared.data.MomTimeDatabase"),
                    "the store's Queries inside the store package" to
                        fixture("$androidStorePath/StoreQueries.kt", storeQueries),
                    "AndroidStoreDatabase inside the DI package" to
                        fixture("$androidDiPath/StoreDatabaseInDi.kt", storeDatabaseImport),
                    "AndroidStoreDatabase inside the store package" to
                        fixture("$androidStorePath/StoreDatabaseInStore.kt", storeDatabaseImport),
                    "a store row class inside the store package" to
                        fixture(
                            "$androidStorePath/StoreRowInStore.kt",
                            "import com.momtime.android.store.db.Fire_telemetry",
                        ),
                    "clean code and mentions in comments" to
                        fixture(
                            "app/Clean.kt",
                            "import com.momtime.shared.data.OccurrenceRepository\n" +
                                "import com.momtime.shared.domain.Event\n\n" +
                                "// OccurrenceQueries MomTimeDatabase in a comment\n" +
                                "/** MomTimeDatabase in a doc comment */\nfun f(r: OccurrenceRepository) = r",
                        ),
                )

            val failures = mutableListOf<String>()
            for ((what, file) in mustBeFlagged) {
                val found = findGeneratedTypeReferences(listOf(file), sharedNames, storeNames).size
                if (found != 1) failures += "not detected exactly once ($found): $what"
            }
            for ((what, file) in mustPass) {
                val found = findGeneratedTypeReferences(listOf(file), sharedNames, storeNames)
                if (found.isNotEmpty()) failures += "was flagged: $what: $found"
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

// --- SQLite floor 3.22 for the android store's SQL (ADR 0042, ADR 0048): the same scan as shared.
extra["sqliteFloorSqlDir"] = layout.projectDirectory.dir("src/main/sqldelight").asFile
apply(from = rootProject.file("gradle/sqlite-floor.gradle.kts"))

// --- One process (ADR 0046). The materialisation wait is the framework connection pool's, and a pool
// serialises within one process only: a component in another process gets its own pool and its own
// driver, and meets a refusal, not a wait (ADR 0045). So no component may declare android:process.
// The check reads the merged manifest, the processed output that includes library components, because
// a library's service or provider is merged in and a library can declare a process the app never wrote.

fun findProcessDeclarations(manifest: File): List<String> {
    // XML comments are removed first; the merged manifest carries explanatory ones.
    val text = manifest.readText().replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
    val pattern = Regex("""android:process\s*=\s*"[^"]*"|android:isolatedProcess\s*=\s*"true"""")
    return pattern
        .findAll(text)
        .map { match ->
            val before = text.substring(0, match.range.first)
            val element = Regex("""<([A-Za-z0-9_.\-]+)[^<]*$""").find(before)?.groupValues?.get(1) ?: "?"
            "${manifest.name}: <$element> declares ${match.value}"
        }.toList()
}

val mergedManifests =
    listOf("debug", "release").map { variant ->
        layout.buildDirectory
            .file(
                "intermediates/merged_manifests/$variant/process${variant.replaceFirstChar {
                    it.uppercase()
                }}Manifest/AndroidManifest.xml",
            ).get()
            .asFile
    }

val verifySingleProcess =
    tasks.register("verifySingleProcess") {
        group = "verification"
        description = "Fails if the merged manifest declares android:process on any component (ADR 0046)"
        dependsOn("processDebugManifest", "processReleaseManifest")
        doLast {
            // Fail closed: a merged manifest that is missing means the check read nothing.
            val missing = mergedManifests.filterNot { it.isFile }
            if (missing.isNotEmpty()) {
                throw GradleException("verifySingleProcess found no merged manifest at: $missing")
            }
            val offenders = mergedManifests.flatMap { findProcessDeclarations(it) }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "a component declares android:process; the app must stay in one process (ADR 0046):\n" +
                        offenders.joinToString("\n"),
                )
            }
        }
    }

val selfTestVerifySingleProcess =
    tasks.register("selfTestVerifySingleProcess") {
        group = "verification"
        description = "Proves verifySingleProcess detects android:process on each kind of component, with fixtures"
        doLast {
            val root =
                layout.buildDirectory
                    .dir("single-process-fixture")
                    .get()
                    .asFile
            root.deleteRecursively()
            root.mkdirs()

            fun manifest(
                name: String,
                body: String,
            ): File =
                File(root, name).also {
                    it.writeText(
                        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                            "<application>\n$body\n</application>\n</manifest>\n",
                    )
                }

            val mustBeFlagged =
                mapOf(
                    "a service" to
                        manifest("service.xml", "<service android:name=\".S\" android:process=\":remote\" />"),
                    "a provider from a library" to
                        manifest(
                            "provider.xml",
                            "<provider android:name=\"x.P\" android:process=\"com.other.proc\" />",
                        ),
                    "a receiver" to
                        manifest("receiver.xml", "<receiver android:name=\".R\" android:process = \":r\" />"),
                    "an isolated service" to
                        manifest("isolated.xml", "<service android:name=\".I\" android:isolatedProcess=\"true\" />"),
                    "the application element" to
                        File(root, "application.xml").also {
                            it.writeText(
                                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n" +
                                    "<application android:process=\":x\" />\n</manifest>\n",
                            )
                        },
                )
            val clean =
                manifest(
                    "clean.xml",
                    "<!-- android:process=\":documented\" is only mentioned here -->\n" +
                        "<service android:name=\".S\" android:exported=\"false\" />\n" +
                        "<service android:name=\".J\" android:isolatedProcess=\"false\" />\n" +
                        "<activity android:name=\".A\" android:processOwner=\"nothing\" />",
                )
            val failures = mutableListOf<String>()
            for ((what, file) in mustBeFlagged) {
                val found = findProcessDeclarations(file).size
                if (found != 1) failures += "not detected exactly once ($found): $what"
            }
            val cleanFound = findProcessDeclarations(clean)
            if (cleanFound.isNotEmpty()) failures += "a clean manifest was flagged: $cleanFound"
            root.deleteRecursively()
            if (failures.isNotEmpty()) {
                throw GradleException("verifySingleProcess self-test failed:\n" + failures.joinToString("\n"))
            }
            logger.lifecycle("verifySingleProcess self-test passed.")
        }
    }

tasks.named("check") {
    dependsOn(
        verifyNoGeneratedQueries,
        selfTestVerifyNoGeneratedQueries,
        verifyNoClockSystem,
        selfTestVerifyNoClockSystem,
        verifySingleProcess,
        selfTestVerifySingleProcess,
    )
}
