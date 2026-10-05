import org.gradle.api.GradleException
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
    implementation(libs.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
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
// android DI package, and (PR 4) no SystemClock read and no Settings.Global.BOOT_COUNT read either: the device
// clock and the boot count are read only through the seams in that package, so a test can set them and a
// watchdog test can tell a restart from a long uptime.

fun findWallClockReferences(files: Iterable<File>): List<String> {
    val pattern =
        Regex(
            """\bClock\.System\b|\bSystem\.currentTimeMillis\b|\bSystemClock\.|\bBOOT_COUNT\b|""" +
                """\bTimeZone\.currentSystemDefault\b|\bTimeZone\.getDefault\b""",
        )
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
                    "Clock.System, System.currentTimeMillis, SystemClock, BOOT_COUNT or the device time zone outside " +
                        "com.momtime.android.di (invariant 8):\n" +
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
            val inDiBoot =
                fixture(
                    "$androidDiPath/InDiBoot.kt",
                    "val e = android.os.SystemClock.elapsedRealtime()\nval f = Settings.Global.BOOT_COUNT",
                )
            val uptime = fixture("app/Uptime.kt", "val d = android.os.SystemClock.elapsedRealtime()")
            val bootCount =
                fixture("app/Boot.kt", "val e = Settings.Global.getLong(r, Settings.Global.BOOT_COUNT, -1L)")
            val zoneKotlin = fixture("app/ZoneKotlin.kt", "val g = kotlinx.datetime.TimeZone.currentSystemDefault()")
            val zoneJava = fixture("app/ZoneJava.kt", "val h = java.util.TimeZone.getDefault()")
            val zoneInDi =
                fixture("$androidDiPath/ZoneInDi.kt", "val i = kotlinx.datetime.TimeZone.currentSystemDefault()")
            val zoneFixed = fixture("app/ZoneFixed.kt", "val j = kotlinx.datetime.TimeZone.UTC")
            val failures = mutableListOf<String>()
            if (findWallClockReferences(listOf(zoneKotlin)).size != 1) {
                failures += "TimeZone.currentSystemDefault() was not detected"
            }
            if (findWallClockReferences(listOf(zoneJava)).size != 1) {
                failures += "java.util.TimeZone.getDefault() was not detected"
            }
            if (findWallClockReferences(listOf(zoneInDi)).isNotEmpty()) {
                failures += "the DI package was not exempted for the zone"
            }
            if (findWallClockReferences(listOf(zoneFixed)).isNotEmpty()) failures += "a fixed zone was flagged"
            if (findWallClockReferences(listOf(uptime)).size != 1) failures += "SystemClock was not detected"
            if (findWallClockReferences(listOf(bootCount)).size != 1) failures += "BOOT_COUNT was not detected"
            if (findWallClockReferences(listOf(inDiBoot)).isNotEmpty()) {
                failures += "the DI package was not exempted for SystemClock and BOOT_COUNT"
            }
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

// --- The ring screen never touches data (ADR 0062). The ring UI package, com.momtime.android.ring, is what the
// ring Activity and its state live in. It may not name a repository, a store type, a generated query, either
// database, or the packages those live in, so the screen can only show what the ring session holds and stop the
// sound: it cannot read an occurrence, and it cannot write an event, which is what keeps dismissal from being
// completion and the Activity from mutating state (CLAUDE.md invariant 3).

val ringUiPath = "com/momtime/android/ring"

val ringUiForbidden =
    Regex(
        """\b[A-Za-z0-9_]*Repository\b|\b[A-Za-z0-9_]*Queries\b|\bAndroidStoreDatabase\b|\bMomTimeDatabase\b|""" +
            """\bcom\.momtime\.android\.(store|di)\b|\bcom\.momtime\.shared\.data\b""",
    )

fun findRingUiViolations(
    files: Iterable<File>,
    forbidden: Regex = ringUiForbidden,
): List<String> {
    val offenders = mutableListOf<String>()
    files.filter { isInPackage(it, ringUiPath) }.forEach { file ->
        file.readLines().forEachIndexed { index, line ->
            if (forbidden.containsMatchIn(line)) {
                offenders += "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}"
            }
        }
    }
    return offenders
}

val verifyRingUiBoundary =
    tasks.register("verifyRingUiBoundary") {
        group = "verification"
        description = "Fails if the ring UI package names a repository, a store type or a database (ADR 0062)"
        inputs.files(androidKotlinFiles)
        doLast {
            // Fail closed: a ring UI package with no source means the check read nothing.
            if (androidKotlinFiles.none { isInPackage(it, ringUiPath) }) {
                throw GradleException("verifyRingUiBoundary found no source under $ringUiPath")
            }
            val offenders = findRingUiViolations(androidKotlinFiles)
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "the ring UI package names a repository, a store type or a database (ADR 0062):\n" +
                        offenders.joinToString("\n"),
                )
            }
        }
    }

val selfTestVerifyRingUiBoundary =
    tasks.register("selfTestVerifyRingUiBoundary") {
        group = "verification"
        description = "Proves the ring UI boundary check detects each kind of reference and ignores other packages"
        doLast {
            val root =
                layout.buildDirectory
                    .dir("ring-ui-fixture")
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

            val mustBeFlagged =
                mapOf(
                    "a repository type" to fixture("$ringUiPath/A.kt", "class A(val r: OccurrenceRepository)"),
                    "a store repository" to fixture("$ringUiPath/B.kt", "class B(val r: FireTelemetryRepository)"),
                    "a generated query" to fixture("$ringUiPath/C.kt", "val q: EventQueries? = null"),
                    "the shared database" to fixture("$ringUiPath/D.kt", "val d: MomTimeDatabase? = null"),
                    "the android store database" to fixture("$ringUiPath/E.kt", "val d: AndroidStoreDatabase? = null"),
                    "an import from the store package" to
                        fixture("$ringUiPath/F.kt", "import com.momtime.android.store.ArmedAlarm"),
                    "an import from the shared data package" to
                        fixture("$ringUiPath/G.kt", "import com.momtime.shared.data.EventRepository"),
                    "an import from the di package" to
                        fixture("$ringUiPath/H.kt", "import com.momtime.android.di.momTimeModules"),
                )
            val mustPass =
                mapOf(
                    "the ring session and its item" to
                        fixture("$ringUiPath/Ok.kt", "class Ok(val sessions: RingSessions, val item: RingItem)"),
                    "a repository in another package" to
                        fixture("com/momtime/android/delivery/Other.kt", "class Other(val r: OccurrenceRepository)"),
                    "the word in lower case" to
                        fixture("$ringUiPath/Lower.kt", "// it holds no repository and no store type"),
                )

            val failures = mutableListOf<String>()
            for ((what, file) in mustBeFlagged) {
                val found = findRingUiViolations(listOf(file)).size
                if (found != 1) failures += "not detected exactly once ($found): $what"
            }
            for ((what, file) in mustPass) {
                val found = findRingUiViolations(listOf(file))
                if (found.isNotEmpty()) failures += "was flagged: $what: $found"
            }
            root.deleteRecursively()
            if (failures.isNotEmpty()) {
                throw GradleException("verifyRingUiBoundary self-test failed:\n" + failures.joinToString("\n"))
            }
            logger.lifecycle("verifyRingUiBoundary self-test passed.")
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

// --- Manifest permissions (ADR 0050). Every permission changes the Play declarations, and a library can
// bring its own (WorkManager does in PR 4), so the merged manifest's permissions are an exact allowlist, in
// both directions: nothing extra, and nothing missing. Attributes such as maxSdkVersion are part of the
// entry, so SCHEDULE_EXACT_ALARM losing its maxSdkVersion of 32 is a change too. A permission is added to
// this list on purpose, in the PR that needs it, or the build fails.

val permissionAllowlist =
    setOf(
        "uses-permission|android.permission.USE_EXACT_ALARM|",
        "uses-permission|android.permission.SCHEDULE_EXACT_ALARM|maxSdkVersion=32",
        "uses-permission|android.permission.USE_FULL_SCREEN_INTENT|",
        "uses-permission|android.permission.POST_NOTIFICATIONS|",
        // From androidx.work:work-runtime 2.12.0 (ADR 0057), each read from its AndroidManifest.xml:
        // WAKE_LOCK keeps the CPU awake while a job runs (normal permission, install time).
        "uses-permission|android.permission.WAKE_LOCK|",
        // ACCESS_NETWORK_STATE is for WorkManager's network constraints, which the app does not use.
        "uses-permission|android.permission.ACCESS_NETWORK_STATE|",
        // RECEIVE_BOOT_COMPLETED lets WorkManager's reschedule receiver hear the boot; the app's own boot
        // handling (PR 6) needs it too.
        "uses-permission|android.permission.RECEIVE_BOOT_COMPLETED|",
        // FOREGROUND_SERVICE is for WorkManager's expedited and long running jobs, which the app does not
        // use; the ringer (PR 5) needs it too.
        "uses-permission|android.permission.FOREGROUND_SERVICE|",
        // The signature level permission androidx.core declares for itself so that a dynamically registered
        // receiver can be kept unexported on older releases. It is the app's own permission, not a platform
        // one, and appears in no Play declaration.
        "uses-permission|com.momtime.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION|",
        // From the ringer (ADR 0061, PR 5). FOREGROUND_SERVICE_MEDIA_PLAYBACK is the type specific permission a
        // mediaPlayback foreground service needs from Android 14; the service plays the alarm sound of a reminder.
        "uses-permission|android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK|",
        // SYSTEM_ALERT_WINDOW is the overlay route (ADR 0061): it lets the app start the ring screen from the
        // background when a full screen intent is not available. A special permission, reviewed by Play, requested
        // only when it is needed (PR 8); never the ringing mechanism.
        "uses-permission|android.permission.SYSTEM_ALERT_WINDOW|",
        // From the ring's vibration (ADR 0065, PR 5b). VIBRATE lets the ringer vibrate with the template's pattern
        // while the sound plays, with the alarm usage. A normal permission granted at install; it is not a Play
        // declaration and it never lets the app vibrate without the ring.
        "uses-permission|android.permission.VIBRATE|",
    )

/** What the permission check's self-test fixtures are checked against: fixed, so the real list can grow. */
val fixtureAllowlist =
    setOf(
        "uses-permission|android.permission.USE_EXACT_ALARM|",
        "uses-permission|android.permission.SCHEDULE_EXACT_ALARM|maxSdkVersion=32",
        "uses-permission|android.permission.USE_FULL_SCREEN_INTENT|",
        "uses-permission|android.permission.POST_NOTIFICATIONS|",
    )

/** Every `uses-permission*` element as `element|name|attributes`, attributes sorted and without the name. */
fun declaredPermissions(manifest: File): Set<String> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    val elements = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("*")
    val found = mutableSetOf<String>()
    for (i in 0 until elements.length) {
        val element = elements.item(i) as Element
        if (!element.tagName.startsWith("uses-permission")) continue
        val name = element.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        val others =
            (0 until element.attributes.length)
                .map { element.attributes.item(it) }
                .filter { it.localName != "name" && !it.nodeName.startsWith("xmlns") }
                .map { "${it.localName}=${it.nodeValue}" }
                .sorted()
        found += "${element.tagName}|$name|${others.joinToString(";")}"
    }
    return found
}

fun permissionProblems(
    manifest: File,
    allowed: Set<String>,
): List<String> {
    val found = declaredPermissions(manifest)
    return (found - allowed).sorted().map { "${manifest.parentFile.name}: not in the allowlist: $it" } +
        (allowed - found).sorted().map { "${manifest.parentFile.name}: missing from the manifest: $it" }
}

val verifyManifestPermissions =
    tasks.register("verifyManifestPermissions") {
        group = "verification"
        description = "Fails if the merged manifest's permissions differ from the exact allowlist (ADR 0050)"
        dependsOn("processDebugManifest", "processReleaseManifest")
        doLast {
            // Fail closed: a merged manifest that is missing means the check read nothing.
            val missing = mergedManifests.filterNot { it.isFile }
            if (missing.isNotEmpty()) {
                throw GradleException("verifyManifestPermissions found no merged manifest at: $missing")
            }
            val problems = mergedManifests.flatMap { permissionProblems(it, permissionAllowlist) }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    "the merged manifest's permissions differ from the allowlist; every permission is added on " +
                        "purpose (ADR 0050):\n" + problems.joinToString("\n"),
                )
            }
        }
    }

val selfTestVerifyManifestPermissions =
    tasks.register("selfTestVerifyManifestPermissions") {
        group = "verification"
        description = "Proves verifyManifestPermissions detects each kind of difference, with fixtures"
        doLast {
            val root =
                layout.buildDirectory
                    .dir("manifest-permissions-fixture")
                    .get()
                    .asFile
            root.deleteRecursively()

            fun manifest(
                name: String,
                body: String,
            ): File {
                val dir = File(root, name).also { it.mkdirs() }
                return File(dir, "AndroidManifest.xml").also {
                    it.writeText(
                        "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n$body\n" +
                            "<application />\n</manifest>\n",
                    )
                }
            }

            val exact = "<uses-permission android:name=\"android.permission.USE_EXACT_ALARM\" />"
            val schedule =
                "<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" android:maxSdkVersion=\"32\" />"
            val fullScreen = "<uses-permission android:name=\"android.permission.USE_FULL_SCREEN_INTENT\" />"
            val notifications = "<uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" />"
            val all = listOf(exact, schedule, fullScreen, notifications)

            val failures = mutableListOf<String>()
            val clean = permissionProblems(manifest("clean", all.joinToString("\n")), fixtureAllowlist)
            if (clean.isNotEmpty()) failures += "the exact list was flagged: $clean"
            val reordered =
                permissionProblems(manifest("reordered", all.reversed().joinToString("\n")), fixtureAllowlist)
            if (reordered.isNotEmpty()) failures += "a reordered list was flagged: $reordered"

            val mustBeFlagged =
                mapOf(
                    "an extra permission" to
                        all + "<uses-permission android:name=\"android.permission.INTERNET\" />",
                    "SCHEDULE_EXACT_ALARM without its maxSdkVersion" to
                        listOf(
                            exact,
                            "<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" />",
                            fullScreen,
                            notifications,
                        ),
                    "SCHEDULE_EXACT_ALARM with another maxSdkVersion" to
                        listOf(
                            exact,
                            "<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" android:maxSdkVersion=\"33\" />",
                            fullScreen,
                            notifications,
                        ),
                    "a missing permission" to listOf(exact, schedule, fullScreen),
                    "a uses-permission-sdk-23 variant" to
                        listOf(
                            "<uses-permission-sdk-23 android:name=\"android.permission.USE_EXACT_ALARM\" />",
                            schedule,
                            fullScreen,
                            notifications,
                        ),
                )
            for ((what, body) in mustBeFlagged) {
                val found =
                    permissionProblems(manifest(what.replace(' ', '-'), body.joinToString("\n")), fixtureAllowlist)
                if (found.isEmpty()) failures += "not detected: $what"
            }
            root.deleteRecursively()
            if (failures.isNotEmpty()) {
                throw GradleException("verifyManifestPermissions self-test failed:\n" + failures.joinToString("\n"))
            }
            logger.lifecycle("verifyManifestPermissions self-test passed.")
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
        verifyManifestPermissions,
        selfTestVerifyManifestPermissions,
        verifyRingUiBoundary,
        selfTestVerifyRingUiBoundary,
    )
}
