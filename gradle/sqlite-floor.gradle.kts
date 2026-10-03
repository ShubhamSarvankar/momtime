import org.gradle.api.GradleException
import java.io.File

// Applied by every module that has SQLDelight sources (shared and android). The applying module sets
//   extra["sqliteFloorSqlDir"] = <the directory holding its .sq and .sqm files>
// before applying this script. One list, one self-test, so the pinned set cannot drift between modules.

// --- SQLite floor 3.22 (ADR 0042). The gate is the SQLDelight 3.18 dialect, which rejects newer
// syntax at compile time. It does not check every function name: a probe of about 110 function names
// found five it accepts that the Android SQLite on the floor cannot run. Every other post-3.22
// construct probed (upsert, RETURNING, window functions, FILTER, UPDATE FROM, NULLS FIRST/LAST,
// generated columns, STRICT, RENAME COLUMN, json*, jsonb*, unixepoch, concat, concat_ws, string_agg,
// format, octet_length, unhex, timediff, and the 3.35 math functions) is a compile error under the
// dialect. The five accepted ones:
//   iif                       added in SQLite 3.32; API 29 ships 3.22.
//   soundex                   needs SQLITE_SOUNDEX, set in no Android build from API 29 to 36.
//   bm25, highlight, snippet  FTS5 functions; no Android build from API 29 to 36 compiles FTS5
//                             (external/sqlite dist/Android.bp enables FTS3 and FTS4 only).
// Add a name here only with the probe that showed the dialect accepts it. The self-test pins the
// expected set separately from this list, so removing a name fails it.
// Read here, at the top of the script: inside a task configuration block `extra` is the task's, not the project's.
val sqliteFloorSqlDir = project.extra["sqliteFloorSqlDir"] as File

val sqliteFunctionsNewerThanFloor = listOf("iif", "soundex", "bm25", "highlight", "snippet")

fun findPostFloorSqliteFunctions(files: Iterable<File>): List<String> {
    val offenders = mutableListOf<String>()
    val pattern = Regex("""\b(${sqliteFunctionsNewerThanFloor.joinToString("|")})\s*\(""", RegexOption.IGNORE_CASE)
    files.forEach { file ->
        file.readLines().forEachIndexed { index, line ->
            val code = line.substringBefore("--")
            if (pattern.containsMatchIn(code)) {
                offenders += "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}"
            }
        }
    }
    return offenders
}

val verifySqliteFloor =
    tasks.register("verifySqliteFloor") {
        group = "verification"
        description = "Fails if a .sq/.sqm file uses a function newer than SQLite 3.22 that the 3.18 dialect accepts"
        val sqlFiles =
            fileTree(sqliteFloorSqlDir) { include("**/*.sq", "**/*.sqm") }.files
        inputs.files(sqlFiles)
        doLast {
            val offenders = findPostFloorSqliteFunctions(sqlFiles)
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "function newer than the SQLite 3.22 floor (ADR 0042):\n" + offenders.joinToString("\n"),
                )
            }
        }
    }

val selfTestVerifySqliteFloor =
    tasks.register("selfTestVerifySqliteFloor") {
        group = "verification"
        description = "Proves verifySqliteFloor detects a violation and ignores comments, using fixture files"
        doLast {
            val fixtureDir =
                layout.buildDirectory
                    .dir("sqlite-floor-fixture")
                    .get()
                    .asFile
            fixtureDir.deleteRecursively()
            fixtureDir.mkdirs()
            // Pinned here on purpose, separately from sqliteFunctionsNewerThanFloor: dropping a name from
            // that list must fail this test, not shrink its own expectation.
            val expectedNames = listOf("iif", "soundex", "bm25", "highlight", "snippet")
            val missed =
                expectedNames.filter { name ->
                    val probe = File(fixtureDir, "Probe_$name.sq")
                    probe.writeText("pick:\nSELECT $name(daily_goal_ml) FROM water_goal;\n")
                    findPostFloorSqliteFunctions(listOf(probe)).size != 1
                }
            if (missed.isNotEmpty()) {
                throw GradleException("verifySqliteFloor failed to detect: $missed")
            }
            val bad = File(fixtureDir, "Bad.sq")
            bad.writeText("pick:\nSELECT iif(daily_goal_ml > 0, 1, 0) FROM water_goal;\n")
            val commented = File(fixtureDir, "Commented.sq")
            commented.writeText("-- iif( is not used here\nSELECT 1;\n")
            val badHits = findPostFloorSqliteFunctions(listOf(bad))
            val commentedHits = findPostFloorSqliteFunctions(listOf(commented))
            fixtureDir.deleteRecursively()
            if (badHits.size != 1) {
                throw GradleException("verifySqliteFloor failed to detect a deliberately bad fixture: $badHits")
            }
            if (commentedHits.isNotEmpty()) {
                throw GradleException("verifySqliteFloor flagged a comment: $commentedHits")
            }
            logger.lifecycle("verifySqliteFloor self-test passed.")
        }
    }

tasks.named("check") {
    dependsOn("verifySqliteFloor", "selfTestVerifySqliteFloor")
}
