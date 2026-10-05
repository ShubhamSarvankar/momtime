# Phase 3 plan: Android UI and localisation

Status: accepted on 2026-10-05 in the review of PR #26 by Claude (technical review) (after four reviews: the mockup read, the dependency list and its sub modules approved, late marking added and made reachable, the fixes of the reviews, and two corrections of Phase 2 decided in the third: a Gentle reminder does not ring, and a ring has a maximum length). Written by the Phase 3 planning session; decisions marked D1 to D10 are Claude (technical review)'s, from the planning prompt, and decisions a to i are the planning session's, for review. No production code and no dependency entered the build in this pull request. `CLAUDE.md` wins over this file, then `ARCHITECTURE.md`, then `IMPLEMENTATION_PLAN.md`.

The visual specification is `docs/phase-3-design.md`. The evidence table is `docs/phase-3-traceability.md`.

---

## 1. Repo state, and where the prompt and the repo disagree

**State.** PR #25 is merged; `main` is at `84a4c2a`; all 10 CI jobs of run 37338212323 on that commit succeeded (read through the GitHub MCP). This plan was branched from it as `docs/phase-3-plan`.

**Disagreements and findings.** None changed a decision of D1 to D10. Each is followed here as stated.

| # | Finding | What the plan does |
|---|---|---|
| C1 | The mockup's path in the first prompt was a placeholder, so the first draft was written without it. | **Closed in this revision:** the mockup was read at the path Shubham gave, its colours were sampled, and the design spec and the lavender palette were revised against it. What was not followed, and why, is the table at the top of `phase-3-design.md`. |
| C2 | The newest Compose (BOM 2026.09.00, Compose 1.12.1) needs compileSdk 37; the project is on 36. | Compose is pinned to BOM 2026.06.01 (1.11.4), which builds on 36 (ADR 0073). A spike failure that became a pin, not a change of decision. |
| C3 | "No receiver is exported" is not true of the merged manifest today: WorkManager's `SystemJobService` and `DiagnosticsReceiver` are exported, each behind a system permission, and Compose adds `ProfileInstallReceiver`. | The allowlist of D10 has six entries, not three (ADR 0078). |
| C4 | The schedule builder's deliverable line lists "mission config"; D7 says no mission control. | The line is corrected in `IMPLEMENTATION_PLAN.md` (ADR 0088). |
| C5 | Criticality has the problem the prompt names for nutrition tags: it is read live from the template, so an edit would rewrite `criticalCompletionDays` for days already lived, change a ladder in mid flight and could end a grace in the past. | Criticality is kept on the occurrence (ADR 0079). This is the largest consequence of decision a. |
| C6 | The shared schema has `app_settings.locale_override` and `telemetry_opt_in`; the live opt in is android's since Phase 2, and the override must be readable before the first frame. | Both shared columns stay unused and are recorded (ADR 0082). No migration. |
| C7 | The plan says the template edit path calls `Work.enqueueMaterialisation`. | The edit runs its pass inline inside the coordinator's exclusion, as the zone change does, so the alarm is right when the screen returns; the function stays for other callers (ADR 0079 item 8). |
| C8 | The prompt speaks of five occurrence states. | The model adds a sixth, `WITHDRAWN` (ADR 0079). The Today view still shows four (ADR 0087). |
| C9 | The roborazzi plugin and version are in the catalog and the README already names the record and verify tasks, but nothing applies the plugin. | PR 7 applies it. |
| C10 | "It applies before the first frame of every Activity ... with no flash" cannot cover the system's starting window, which is drawn from the manifest theme before app code. | Met for every frame the app draws; the limit is stated (ADR 0074 item 6) and is a device row. |
| C11 | Phase 3's deliverables did not name marking a missed dose as taken late, though `ARCHITECTURE.md` section 4.5 promises it. | **Decided by Claude (technical review): in PR 11.** No command wrote `COMPLETED_BACKFILLED`, so PR 11 adds `BackfillCommand` (ADR 0087 item 7). |
| C12 | `OccurrenceActions.available` offers snooze on any open occurrence, including one that is not yet due; Today is the first surface to show one. | Snooze is not offered before the occurrence's time; a rule added to the domain in PR 11, for review (ADR 0087 item 6). |
| C13 | "Anything a pull request imports directly is declared directly" and "nothing beyond the approved list" pulled against each other for the sub modules of the approved Compose and Activity libraries. | **Resolved by Shubham: the eight are declared by name** at the versions the approved BOM and `activity-compose` 1.13.0 fix (ADR 0084, section 4). |
| C14 | **Found while checking the onboarding lines against the build: a Gentle reminder rings.** `ARCHITECTURE.md` section 4.2 says Gentle is "t+0 notification only, no repeat"; the build starts the ringer for it on an exact tier, and `DeliveryTest` `each criticality rings on its own channel` asserts that. | **Decided by Claude (technical review): the document is right and the build is wrong.** Corrected in PR 2 under ADR 0089. |
| C15 | Found in the same check: **the ringer has no maximum ring time**, so the repeat rung only ever "continues" a ring that is still sounding, and a ring can outlive its occurrence's grace. | **Decided by Claude (technical review): a ring has a maximum length**, 4 minutes as a placeholder, and the end of grace ends a ring. A new pull request, PR 2b, under ADR 0090. |

---

## 2. Spike results

All spikes ran in a throwaway clone under the session's scratch directory, never committed, on Kotlin 2.4.20, AGP 9.4.1, Gradle 9.8.0, compileSdk 36, Robolectric 4.17, on Windows, with Android Studio's JDK 21 as the Gradle launcher.

| # | Question | Result | Versions, workaround |
|---|---|---|---|
| S1 | Compose builds in the android module | **Yes**, `:android:assembleDebug` succeeds | Kotlin plugin `org.jetbrains.kotlin.plugin.compose` 2.4.20, `buildFeatures.compose`, BOM **2026.06.01**, `activity-compose` 1.13.0. BOM 2026.09.00 fails: nine artifacts require compileSdk 37 |
| S2 | A Compose screen renders under Robolectric at SDK 29 and 36 | **Yes**, both | `@GraphicsMode(NATIVE)`, `createAndroidComposeRule`, a plain `Application` in `@Config`; the host `ComponentActivity` must be declared in a manifest (the debug manifest, ADR 0084) |
| S3 | Roborazzi records and verifies it | **Yes**, with one finding | Roborazzi 1.76.0. A capture taken straight after a click differed between runs: a button's ripple was caught in mid animation. With the Compose test clock paused and advanced before the capture, and `changeThreshold = 0`, two verify runs in a row passed. A change of one step in one colour channel over the whole background was **not** detected by the default comparison, so a screenshot is not evidence of colour: that is `ContrastTest`'s job (D5 already assumes this) |
| S4 | Devanagari with matras renders unclipped at 200 percent with the bundled Noto Sans Devanagari | **Yes** at SDK 29 and 36 | 16 sp on a 26 sp line and 22 sp on 34 sp are clean; a Latin tuned 16 on 18 collides between lines, which is the evidence for the per locale line heights (ADR 0085) |
| S5 | A view based Activity picks up an appearance chosen at runtime | **Yes** at SDK 29 and 36 | Read the choice before `super.onCreate`; the spike used `setTheme`, the plan applies the roles programmatically instead (ADR 0075) |
| S6 | A choice made from a bottom sheet applies to the open screen with no flash and no lost back stack | **Yes**, as far as Robolectric can show | Same Activity instance, no second `onCreate`, back stack depth unchanged, the choice persisted. "No visible flash" is inferred from there being no recreation; what the eye sees is device row P3-1 |
| S7 (added) | Mixed Latin and Devanagari in one line uses the bundled fonts for both | **Yes** | `Typeface.CustomFallbackBuilder` (API 29): a Devanagari run measures exactly as the bundled Noto alone (473.0) and not as Robolectric's system fallback (477.0); positive control with another fallback face differs |
| S8 (added) | Compose changes the merged manifest's permissions or exported components | No permission change; one exported receiver added | ADR 0078 |

**The golden recording route, proved (PR #27, a throwaway draft pull request, closed unmerged, its branch deleted).** Neither WSL2 nor Docker is usable on the implementing machine as it stands (the Ubuntu distribution has no JDK and no Android SDK, and the Docker engine is not running), and `gh` is not installed, so the route is a CI job plus the GitHub MCP. On the branch `spike/golden-route`, with only the approved dependencies, a job `android-screenshots` verified the committed golden, recorded what the commit renders on Linux, and uploaded it. Three runs:

| Run | Commit | What was pushed | `android-screenshots` |
|---|---|---|---|
| 37355818005 | `b2cb5af` | One Compose capture (Latin and Devanagari text and a button, strict comparison) with a golden recorded **on Windows** | **Success.** The golden the job then recorded on Linux was byte for byte the Windows file (same SHA-256, `b2a529b0...`) |
| 37356335976 | `dacd1ed` | The screen's text changed, the golden not | **Failure**, on the step that fails when verify failed; the artifacts `goldens` and `screenshot-diff` were uploaded |
| 37356903352 | `bb2f594` | The `goldens` artifact of the second run, downloaded through the MCP and committed unchanged | **Success** |

So the route works end to end, verify is shown able to fail, and a legitimately changed golden is replaced by downloading the job's artifact. One further finding: for this capture, Robolectric's native graphics rendered identically on Windows and Linux. That is one screen with Robolectric's own fonts, not a guarantee for 735 captures with bundled fonts, so the rule stays that only Linux recorded files are committed; but a local Windows recording is a faithful preview. The steps are in PR 7.

**Timing and size.** Warm captures took about half a second each for a small screen. The matrix is 49 screen states (design spec section 6): 49 by 9 in Light is 441, and 49 by 6 other appearances at English 100 percent is 294, **735 captures**, plus 9 type specimens. At half a second to one second each that is 6 to 12 minutes on one SDK level (36), plus the recording pass the job also makes, so screenshots run as their own CI job, `android-screenshots`. The throwaway run of that job, with one capture, took 2 minutes 39 seconds end to end, nearly all of it build. **Capture density is mdpi, not xhdpi, to keep the repository's history small** (ADR 0077 item 6, with the estimate): about 10 MB for one full set and about 40 MB of history across PRs 7 to 16, against about 32 MB and 125 MB at xhdpi. These are estimates from one measured file; PR 7 measures the real average per capture and reports it, and a single snapshot set above 60 MB is a STOP.

---

## 3. Decisions

| | Decision | ADR |
|---|---|---|
| D1 | Compose for new screens; the ring screen stays views; the permission, Samsung and check screens are ported | 0073 |
| D2, D2b | One appearance setting, four values, five Pastel palettes, one switcher sheet, stored in `momtime_android_settings` | 0074 |
| D2c | The visual spec | `phase-3-design.md` |
| D3 | Colour roles in one Kotlin model, drawn only as declared pairs, enforced by `verifyColourBoundary`; contrast is a test on the unrounded ratio; no state by colour alone; no good or bad colour | 0075 |
| D4, D6 | Cutesy is visual, never verbal; the starter schedule is structure only | 0076 |
| D5, D9 | Nine locale and scale combinations in Light plus six appearances at English 100 percent; goldens recorded on Linux by the `android-screenshots` job; fixtures until the human strings arrive; a CSV workflow | 0077 |
| D7 | Missions are not in Phase 3; recommended owner Phase 5 | 0088 |
| D8 | Water nudges are WorkManager work and never an alarm | 0083 |
| D10 | Exported components are an exact allowlist | 0078 |
| a | Template edits: one command; unwanted open occurrences are withdrawn; an occurrence that has come due is untouched; an edit never moves an occurrence into the past; criticality is kept on the occurrence; schema version 6 | 0079 |
| b | When a ring session has several occurrences, each gets its own notification with its own actions | 0080 |
| c | Screens observe a change signal from `shared`, read on an executor, use no ViewModel and no coroutine API | 0081 |
| d | A small back stack for navigation, a UI graph instead of koin-android, one locale override mechanism; a RemoteViews widget and a tile over one water command | 0082, 0083 |
| e | The dependency list | 0084 |
| f | Nunito with the bundled Noto Sans Devanagari as its explicit fallback; per locale line heights; icons copied in | 0085 |
| g | One source of colour roles for Compose, views and the widget | 0075 |
| h | Nutrition tags are recorded with the completion; water with its zone; both reductions pure with an explicit `asOf` | 0086 |
| i | Onboarding in six steps with one gate; Today's four states over the log, its three actions and late marking; banners; the reliability view | 0087 |
| | A Gentle reminder is a notification only: a correction of Phase 2 (decided in the third review) | 0089 |
| | A ring has a maximum length, and the end of grace ends a ring (decided in the third review) | 0090 |

---

## 4. The dependency list

**Approved by Shubham on 2026-10-05, as reported**, together with Nunito, Noto Sans Devanagari and the 21 Material Symbols icons, with licences and provenance recorded as ADR 0084 and ADR 0085 say. Nothing beyond it may be added.

- **Build:** the Kotlin Compose compiler plugin 2.4.20; the Roborazzi plugin 1.76.0 (in the catalog already).
- **Main:** `androidx.compose:compose-bom` 2026.06.01 with `ui`, `foundation`, `material3`; `androidx.activity:activity-compose` 1.13.0.
- **Test:** `androidx.compose.ui:ui-test-junit4` (by BOM); `roborazzi` and `roborazzi-compose` 1.76.0.
- **Files, not artifacts:** Nunito and Noto Sans Devanagari (SIL OFL 1.1); 21 Material Symbols icons (Apache 2.0).
- **Still declined:** koin-android, koin-compose, direct androidx.test, ui-test-manifest, coroutines in android, a direct androidx.core, navigation-compose, viewmodel-compose, Glance, appcompat, an icon artifact, ML Kit.

**The rule per pull request:** anything a pull request's code imports directly is declared directly. Each section below ends with "Imports": the androidx packages its code imports and the artifact that declares each. PRs 1 to 6 import no androidx package that is not already declared (`androidx.work` from `work-runtime`, `androidx.sqlite.db` from the SQLDelight android driver, as today).

**The sub modules** (approved by Shubham in the second review, and nothing else is added by that answer): `androidx.compose.runtime:runtime`, `runtime-saveable`, `androidx.compose.ui:ui-graphics`, `ui-text`, `ui-unit`, `androidx.compose.foundation:foundation-layout`, `androidx.compose.ui:ui-test` and `androidx.activity:activity` are declared by name, because screen code and tests import from them. The Compose ones take their version from the approved BOM, with no version written by hand; `androidx.activity:activity` is written at 1.13.0, the version `activity-compose` 1.13.0 fixes, because no BOM supplies it.

---

## 5. The PR sequence

Rules for every PR: one branch from current `main`, never stacked, only after the previous one is merged. The Working conventions of `IMPLEMENTATION_PLAN.md` apply in full: green means every check on the pull request head read through the GitHub MCP; mutations run on the final code commit, with the diff shown first and the failure confirmed to be the intended test, on an assertion, for the intended reason; no conversation or session link anywhere; every string in `values/strings.xml`. Every PR updates `docs/phase-3-traceability.md` (test names, mutation, observed failure, SHA) and reports: the head SHA and check states, test counts with their SHA, the mutation table, anything it stopped on, and the no links check.

"STOP" means: do not choose; write what was found and hand back.

**A cancelled job is not a failed job** (Claude (technical review), from what happened to this plan's own pull request). When a job's conclusion is `cancelled` because no runner picked it up (it sat queued and never started), push one empty commit to start a fresh run, and do that at most twice. Say so in the handover, with the SHA of the commit that carries the content and the SHA that is green. If it happens a third time, stop and ask Shubham to re-run it. A job that started and failed is a failure and is never answered with an empty commit.

Test tables give, for each test, its subject and setup, its assertion, and the mutation that must fail it. Every test named here reaches its subject through the production entry point named in its row; a test that asserts a helper's output where the row names a command or a screen does not count.

### The audit of absence assertions (second review, fix 1)

Done in full for the third revision and set out here in the fourth, because its report gave only the tests that changed. Every assertion in this plan that a miss, a skip, a failure, an event, a ring or a write is **absent** is listed with the step that would have produced the thing if the code were wrong. "Changed" means the step was missing and was added; "sound" means the test already contained it; "new" means the test was added by the audit or by a later decision.

| PR | Test | What must be absent | The step that would produce it | Status |
|---|---|---|---|---|
| 1 | `a withdrawn occurrence is in none of the three figures, even after its grace` | A `MISSED` event for a withdrawn occurrence; a count in any figure | `Reconcile` a day after its grace | Changed |
| 1 | `a date can be materialised again after a withdrawal and not otherwise` | A second open row for one date | The third insert | Sound |
| 2 | `the budget is unspent after a Gentle fire` | A spent budget | The Gentle fire itself, beside a Standard fire that spends one as the control | New |
| 2 | `a Gentle fire during a ringing Critical session leaves the session as it was` | A join, a spread, a second ringer start | The Gentle fire while the session rings | New |
| 2 | `Gentle at Tier 3 starts no ringer` | A ringer start, a full screen intent | A real fire at Tier 3 | New |
| 2b | `the cap is a dismissal and writes nothing` | Any event or state change | The scheduler advanced past `MAX_RING` | New |
| 2b | `the end of grace ends the ring` | Sound after the occurrence is `MISSED` | The watchdog pass after grace, with the session still sounding | New |
| 3 | Scenario 1, assertion 6 | A miss of D+1 because of the move | `Reconcile` at 13:00 on D+1 | Changed |
| 3 | `deactivation withdraws every open occurrence, due or not` | `MISSED` or `SKIPPED` for a withdrawn occurrence; a miss or a skip in the figures | `Reconcile` after the grace of both withdrawn occurrences, before the figures | Changed |
| 3 | `a recurrence change withdraws exactly the dates no longer wanted` | A miss on a withdrawn date | `Reconcile` after the grace of all three | Changed |
| 3 | `a criticality edit never ends a grace in the past` | A miss at once after the edit | `Reconcile` one minute after the edit | New |
| 3 | `reactivation materialises fresh occurrences and never a past one` | A new occurrence for an instant already past | The materialisation after reactivating | Sound |
| 3 | `an edit of a snoozed occurrence changes nothing of it` | Any change to the row or its events | The edit of time and criticality | Sound |
| 3 | `other fields touch no occurrence` | Any occurrence or event change | The edit; its positive control is the time edit | Sound |
| 3 | `validation refuses and writes nothing` | A changed template row | The dispatch of each invalid input | Sound |
| 3 | Scenario 12, final check | A miss produced or dated by an edit; a miss of a withdrawn row | `Reconcile` after the grace of every row in the span | Changed |
| 4 | `deactivating a ringing reminder stops the ring` | `MISSED`, a silent notice, a `WATCHDOG_REPAIR` | The watchdog pass after the occurrence's grace | Changed |
| 4 | `a withdrawn occurrence's late alarm delivers nothing` | An event, a notification | The old alarm's broadcast delivered | Sound |
| 4 | `an edit raises no never fired rung` | A never fired rung | The old instant passed by 20 minutes, beyond the 15 minute tolerance, before the report is read | Sound |
| 5 | `a backfill counts on its scheduled date from when it is entered` | A count before it is entered | The reduction with `asOf` before the backfill, which exists | Sound |
| 5 | `mission events, skips, misses and withdrawals count nothing` | A serving counted | One event of each kind present | Sound |
| 5 | `asOf is inclusive and nothing after it counts` | A count for a later event | An event after `asOf` present | Sound |
| 6 | `a second occurrence spreads the session` | A full screen intent or delete intent on a per occurrence notification | The second fire | Sound |
| 6 | `the invariant holds along every path` | A notification for a resolved occurrence | Each action in the table of paths | Sound |
| 8 | `DataChangesTest` | A signal after a rollback or after unsubscribing | The rolled back write; a write after unsubscribing | Sound |
| 8 | `a choice applies at once, persists, and recreates nothing` | A recreation, a confirm control | The choice made from the sheet | Sound |
| 10 | `the copy obligations are on screen` | The Tier 1 sentence at Tier 3 | The screen composed at Tier 3 | Sound |
| 10 | `the gate` | A gate when something can still reach her | All 64 capability combinations | Sound |
| 11 | `it is allowed once`, `it is refused on every state but MISSED` | A second or a wrongful backfill event | The second dispatch; a dispatch on each other state | Sound |
| 11 | `snooze is not offered before the occurrence's time` | A `SNOOZED` event | A snooze dispatched before the time | Sound |
| 11 | `four states over the log` | A withdrawn row on Today | The withdrawn occurrence present in the database for today | Sound |
| 11 | `an occurrence missed two days ago does not appear` | Older or already corrected rows in the group | Those rows present | Sound |
| 11 | `a read that meets corruption ends the process and posts nothing` | A state posted | The read against the corruption seam | Sound |
| 11 | `the confirm dialog lists exactly what will be withdrawn` | A write on cancel | Cancel pressed with occurrences to withdraw | Sound |
| 11 | `an edit to a time already past today says so, and names the date the new time starts` | The line when it does not apply | An edit to a time still ahead | Sound |
| 11 | `the editor's draft survives process death` | A database write while typing | Every field typed before the process death | Sound |
| 11 | `the editor never writes a mission and shows no mission control` | A mission | Every form state saved | Sound |
| 12 | `reaching the goal changes only the numbers` | Praise, a new node, a colour | Totals at and above the goal | Sound |
| 12 | `counts per day and per week, nothing else`; `three figures, never one` | A percentage, a target, a sum | Data with all three outcomes present | Sound |
| 13 | `the blocking banner cannot be dismissed and covers Today` | A dismiss control | The blocked capability | Sound |
| 13 | `the reliability view reads the report and the store failing soft is shown` | A throw | The store's seam made to fail | Sound |
| 13 | `RestoredPhoneTest` | Onboarding shown again | A launch with the flag restored and an empty android store | Sound |
| 14 | `a nudge never arms an alarm and never uses a reminder channel` | An alarm, an event, a spent budget | The worker run when a nudge is due, with a reminder armed | Sound |
| 14 | `WaterNudgePolicyTest` | A nudge at or above the goal, in quiet hours, or beyond the cap | The table's rows for each | Sound |
| 14 | `the nudge state is not backed up` | The file in the rules | The rules files read | Sound |


### PR 1. `phase-3/schema-v6` (Sonnet)

*Corrected in PR 1 (#28) to match what was built, as Claude (technical review) asked: the mutation of the last test, the two guard tests, the Phase 2 expectations and the traceability rows.*

**Goal.** Shared schema version 6 and the vocabulary it carries, with no change of behaviour.

**Scope.** `migrations/5.sqm` exactly as ADR 0079 lists; `Occurrence.sq` (the column, the partial index, the trigger), `Event.sq` (two columns); `OccurrenceState.WITHDRAWN`, `EventType.WITHDRAWN`; `Occurrence.criticality`; `EventPayload.Completion(nutritionTags)` and `EventPayload.Water(waterMl, zone)`; `EventColumn` allowances (`nutrition_tags` on `COMPLETED` and `COMPLETED_BACKFILLED`; `zone_id` on `WATER_LOGGED`); mappers; `OccurrenceMaterialiser` sets `criticality` from the template and `datesAlreadyMaterialisedForTemplate` ignores withdrawn rows; `OccurrenceActions.acknowledged` takes the tags and `OccurrenceActionCommand` passes the template's; every exhaustive `when` over the two enums compiles with the new value treated as terminal and uncounted. **Out of scope:** any reader switching to `occurrence.criticality` (PR 2), the edit command (PR 3), any writer of `WITHDRAWN` or `WATER_LOGGED`.

**Files.** `shared/src/commonMain/sqldelight/**`, `shared/.../domain/{Enums,Occurrence,Event}.kt`, `shared/.../data/{EventColumn,Mappers,OccurrenceRepository,OccurrenceActionCommand}.kt`, `shared/.../engine/{OccurrenceMaterialiser,OccurrenceActions,EventLogReduction}.kt`, their tests, `android` test fixtures that construct an `Occurrence`.

**Relies on.** ADR 0079 (schema version 6), ADR 0086 items 1 and 4, ADR 0042, 0043, 0052.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `SchemaV6Test` `migrates from every prior version` | Databases at versions 1 to 5, each with a template of each criticality, an occurrence in every state and one event of each type that has a payload, foreign keys on | After migrating: `foreign_key_check` is empty; every old row survives unchanged in its other columns | Change an unrelated column in the migration |
| `SchemaV6Test` `the migration fills each new column from version 5 data` | A version 5 database holding: occurrences of a CRITICAL, a STANDARD and a GENTLE template, open and terminal; a `COMPLETED` and a `COMPLETED_BACKFILLED` event of a template with two tags, and a `COMPLETED` event of a template with none; a `WATER_LOGGED` event | After migrating: each occurrence's `criticality` is its template's; the two tagged completions decode to exactly the template's two tags, compared as sets, and the untagged one to none; the water row's `zone_id` is null and it decodes as `Water(ml, zone = null)`; no other event has either column set | Remove the criticality `UPDATE` (CRITICAL and GENTLE rows read STANDARD); remove the tags `UPDATE` (the tagged completions decode empty); fill `zone_id` with a constant (the water row has a zone) |
| `SchemaV6Test` `a withdrawn row is immutable in full` | A `WITHDRAWN` row at version 6 | An update of each updatable column aborts with the trigger's message | Recreate the trigger without `WITHDRAWN` |
| `SchemaV6Test` `the other terminal states are still immutable after the migration` | Rows `COMPLETED`, `SKIPPED`, `MISSED` in a migrated database | Each update aborts | Delete the `CREATE TRIGGER` from `5.sqm` (the Android twin must fail too, as PR 6b's lesson says) |
| `SchemaV6Test` `a date can be materialised again after a withdrawal and not otherwise` | Insert an occurrence, set it `WITHDRAWN` with its event, insert another for the same template and date; then a third | The second insert succeeds; the third throws on the unique index | Make the index non partial: the second insert throws. Drop the index: the third succeeds |
| `AndroidSchemaTest` twins of the three above | The same under `AndroidSqliteDriver` at SDK 29 and 36 | The same | The same mutations, observed in the Android test |
| `EventColumnGuardTest` additions | A row of every other type with `nutrition_tags` or `zone_id` set | Decoding fails loudly, naming the column | Add the column to another type's allowance |
| `EventPayloadRoundTripTest` additions | `Completion` with zero, one and seven tags; `Water` with a zone | Round trip equality as sets; the column is the names sorted and joined by commas; `Water` with a null zone round trips | Write the names unsorted (use a set whose iteration order differs) |
| `OccurrenceActionCommandTest` `acknowledge records the template's tags as they are now` | A template with two tags; acknowledge; replace the template's tags directly in the test database; acknowledge the next occurrence | The first event carries the two old tags, the second the new ones | Pass `emptySet()` to `acknowledged` |
| `MaterialiserTest` `an occurrence takes its template's criticality` and `withdrawn dates are materialised again` | Materialise templates of each criticality; withdraw one row in the test database and materialise again | Criticality copied; one new row for the withdrawn date with a new id and a slot greater than every earlier slot | Hard code STANDARD; include withdrawn rows in the dates already materialised |
| `CommandsTest` `a withdrawn occurrence is in none of the three figures, even after its grace` (over a database; pure tests of the same name in `EventLogReductionTest` and `ReconcileTest`) | A withdrawn occurrence (its `WITHDRAWN` event and state written by the test) beside one completed, one skipped and one missed; **then `ReconcileCommand` dispatched a day after the withdrawn occurrence's grace has ended**, which is the step that would write `MISSED` for it if anything still treated it as open | No `MISSED` event exists for the withdrawn occurrence and its state is still `WITHDRAWN`; the figures as of then are 1, 1, 1 | Map `WITHDRAWN` to `SKIPPED` in `outcome()` (skipped reads 2). The mutation first written here, "include `WITHDRAWN` in `selectOpenOccurrences`", cannot fail this test: `Reconcile.evaluate` returns nothing for a terminal occurrence before any transition is tried, so each guard has a test of its own below (Claude (technical review), before PR 1 was coded) |
| `CommandsTest` `findOpen never returns a withdrawn occurrence` | Pending, snoozed and withdrawn rows, through `OccurrenceRepository` | `findOpen()` returns the pending and the snoozed only | Add `WITHDRAWN` to `selectOpenOccurrences` |
| `ReconcileTest` `Reconcile evaluate leaves a withdrawn occurrence alone` | A withdrawn occurrence well past its grace, with no terminal event reported, so only `isTerminal` is in the way | `evaluate` returns null | Remove `WITHDRAWN` from `isTerminal` |
| `CommandsTest` `an occurrence with a withdrawal event is not reconciled` | A row still `PENDING` with a `WITHDRAWN` event in its log (a divergent row); `ReconcileCommand` after its grace | Nothing is written; the row stays `PENDING` | Remove `WITHDRAWN` from `ReconcileCommand.TERMINAL_EVENTS` |
| `OccurrenceActionsTest` `a withdrawn occurrence offers no action` | `available` for a `WITHDRAWN` state | The empty set | Restore the list of three states in `available` (it reads `isTerminal`) |

**The fills** are ADR 0079's: `criticality` from the template (exact); `nutrition_tags` on existing completions from the template's tags, by a correlated `group_concat` over the tag rows (exact as a set: SQLite 3.22 does not guarantee the order in which `group_concat` joins, even over an ordered subquery, so the order of names in a migrated row is unspecified, decoding never depends on it, and `SchemaV6Test` compares migrated tags as sets); `zone_id` left null on existing water rows (unknowable), which `WaterReduction` attributes in a fallback zone (PR 5).

**Imports.** None new.

**Phase 2 expectations.** Three change, each authorised by Claude (technical review) before PR 1 was coded, because a `COMPLETED` or `COMPLETED_BACKFILLED` row decodes as `Completion(nutritionTags)` and never as no payload (ADR 0086): `EventPayloadRoundTripTest` `none payload round trips` moves from a `COMPLETED` event to a `SKIPPED` one, its subject unchanged; `OccurrenceActionsTest` `her three actions are user events and the snooze carries its number` expects `Completion` carrying the tags given to `acknowledged`; `OccurrenceActionCommandTest` `acknowledge writes COMPLETED and completes, and nothing else` expects `Completion` carrying the template's tags. Also decided there: every `COMPLETED` row decodes as `Completion`, an empty set is stored as null, and a column that is set but names no tag is a decode failure; all five `SchemaV6Test` tests have an Android twin at SDK 29 and 36; `OccurrenceActions.available` decides "terminal" by `isTerminal`; and `ReconcileCommand.TERMINAL_EVENTS`, the event level record that an occurrence is closed, gains `WITHDRAWN`.

**STOP points.** The 3.18 dialect or `verifySqliteFloor` rejects the partial index, `group_concat` or any other statement of `5.sqm`. `verifySqlDelightMigration` cannot be made to pass with the column declared as the migration leaves it. Any `when` over `OccurrenceState` whose correct treatment of `WITHDRAWN` is not "terminal and uncounted".

**Docs.** `ARCHITECTURE.md` sections 3.2 and 3.3 (the state, the event, the column, the payloads, schema version 6); `docs/phase-3-traceability.md` rows A11, A19, PR 1's part of A23, and its section "PR 1: shared schema version 6" (one row per mutation, S6-1 to S6-18).

**Report.** As the rules say, plus the list of every `when` that gained a `WITHDRAWN` arm and what the arm does.

### PR 2. `phase-3/occurrence-criticality` (runs on Fable)

**Why Fable.** It changes which value the alarm path reads at about ten sites in seven files (`ReconcileCommand`, `ArmingSelection`, `ArmingSupport`, `AlarmFireHandler`, `AndroidDeliveryPort`, `ReliabilityReport`, and callers of `criticalCompletionDays`). A site left on the template is a silent fork that only an edit exposes, and deciding that a given site is display rather than behaviour is a correctness judgment.

**Goal.** Every behaviour that depends on criticality reads `occurrence.criticality`; the template's is read only to materialise and to show the editor. **And a Gentle reminder stops ringing** (ADR 0089): a correction of Phase 2 to match `ARCHITECTURE.md` section 4.2, decided by Claude (technical review) and placed here because this pull request already moves every criticality decision onto the occurrence.

**Scope.** Those sites; `criticalCompletionDays` loses its `criticalityOf` parameter. A structural check, `verifyCriticalityFromOccurrence`, in the style of `verifyRingUiBoundary`: outside `OccurrenceMaterialiser`, the edit command and the UI's editor package, no source may read `.criticality` on a `ScheduleTemplate`; with a fixture self test, wired into `check` and `verify-android-structure`. **Gentle, exactly as ADR 0089 says:** `RungDelivery.NOTIFICATION`; `EscalationPolicy.resolveDelivery` in the order Critical, quiet hours or budget, Gentle, ring, given the occurrence's criticality; `DeliveryPath.choose` sends `NOTIFICATION` to the plain path on any tier and reads no criticality; `recordRing` only for `RING`; `VibrationPattern.defaultFor(GENTLE)` is `NONE` and `AndroidSettings.vibrationFor` returns `NONE` for a Gentle occurrence whatever is stored, deleting nothing; arming unchanged. **The one Phase 2 expectation that changes:** `DeliveryTest` `each criticality rings on its own channel` expects for Gentle a plain notification on the Gentle channel and no ringer start. It is the single authorised exception to this pull request's STOP on changed expectations.

**Out of scope:** the edit command; the editor's vibration control (PR 11); the ring limit (PR 2b).

**Relies on.** ADR 0079 item 6, ADR 0089.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `CriticalitySourceTest` (android, Robolectric, SDK 29 and 36), one case per behaviour: `the ladder`, `grace`, `the channel`, `the default vibration`, `the budget exemption`, `quiet hours` | A CRITICAL template; materialise; then set the template row to GENTLE directly in the test database (no command), so the two values differ; run the real fire path, `Reconcile`, delivery | Each behaviour is the CRITICAL one: the second rung is armed five minutes on; the occurrence is `MISSED` at two hours and not before; the notification is on the Critical channel; the pattern is URGENT; it rings with the budget spent and inside quiet hours | Revert each site to `template.criticality` in turn; the case for that behaviour fails and no other mutation is needed to see it |
| `EventLogReductionTest` `critical completion days read the occurrence` | The same divergence, over 30 days | The count is the one the occurrences' values give | Read the template in the caller |
| `verifyCriticalityFromOccurrence` self test | Fixtures: a read in arming code, in delivery, in the materialiser, in the editor | Flagged, flagged, allowed, allowed | Narrow the pattern; fail closed on an empty source set |
| `GentleDeliveryTest` `Gentle at Tier 3 starts no ringer and posts one notification` (SDK 29, 31, 33, 34, 36) | A Gentle occurrence fired through the real fire path with every capability granted | No ringer start, no ring session, no overlay launch; exactly one notification, with the occurrence's slot as id, on the Gentle channel, with no full screen intent and no delete intent, carrying the occurrence's three actions; `ALARM_FIRED` is written; the telemetry row's path is `PLAIN` | Return `RING` for Gentle in `resolveDelivery`; in `choose`, test the tier before the policy |
| `the budget is unspent after a Gentle fire` | Budget 10; a Gentle fire, then, as the control that the counter works, a Standard fire | The count is 0 after the first and 1 after the second | Call `recordRing` for `NOTIFICATION` |
| `Gentle in quiet hours, with the budget spent, and beyond the catch up window is as before` | Three fires | A silent notification on Quiet notices in each case | Check Gentle before quiet hours in `resolveDelivery` |
| `a Gentle fire during a ringing Critical session leaves the session as it was` | Tier 3, a Critical occurrence ringing; a Gentle occurrence fires | The session's items, its notification and its ringer start count are byte for byte what they were; the Gentle occurrence has its own notification on the Gentle channel; acting on the Gentle one leaves the ring ringing | Let a `NOTIFICATION` rung join the session |
| `the decision reads the occurrence, not the template` | An occurrence whose `criticality` is GENTLE with its template row set to CRITICAL directly in the test database, and the reverse | The first is a notification and the second rings | Pass the template's criticality to the policy |
| `a stored vibration is ignored for Gentle and kept` | Store `URGENT` for a template; fire a Gentle occurrence of it, then a Standard one | No vibration for the first; `URGENT` for the second; the stored value is still there | Delete the stored pattern when the occurrence is Gentle |
| `EscalationPolicyTest` additions (shared) | The table of criticality by quiet hours by budget | `NOTIFICATION` only for Gentle outside both; every other cell as before | Swap the order of the Gentle and budget checks |
| `DeliveryTest` `each criticality rings on its own channel` (changed, authorised) | As before | Critical and Standard start the ringer on their channels; Gentle starts none and posts on the Gentle channel | (the mutations above) |

**Imports.** None new.

**STOP points.** Any read whose right source is unclear. Any Phase 2 test, other than the one authorised above, that needs its expectation changed rather than its fixture.

**Docs.** `ARCHITECTURE.md` sections 3.1, 3.2, 4.2 (Gentle's row is now true, and says where it is decided), 4.3, 4.5, 5.1 (the six paths: when the plain path is taken), 5.5 (vibration), 5.6; `CLAUDE.md`'s testing section gains the precedent (a document and a green test disagreed and nothing compared them); the Open items row is closed with the merge commit. Traceability rows.

### PR 2b. `phase-3/ring-limit` (runs on Fable)

**Why Fable.** It adds a timer to the ringer and a second way for a ring session to end, on the alarm path, and it changes what "Stop the sound" leaves behind. PR 6's state machine is built on its result.

**Goal.** ADR 0090: a ring stops by itself after `MAX_RING`; that is a dismissal; the end of grace ends a ring; the evidence is kept.

**Scope.** `RingPolicy.MAX_RING` (4 minutes, a placeholder, beside the ramp and backup constants); the cap's timer on the ringer's existing scheduler, restarted by every fire that rings; one dismissal path shared by the cap and "Stop the sound", which corrects a Phase 2 behaviour (accepted by Claude (technical review)): stopping the sound must not remove the only notification that carries her actions (sound and vibration stop; the service leaves the foreground; the session's notification is reposted as an ordinary one, not ongoing, with no full screen intent and only alert once, with the occurrence's actions if the session never spread); the ring screen releasing keep screen on; `RingController.dropClosed()`, called after every `Reconcile` android dispatches, which drops occurrences that are no longer open, cancels their notifications and ends the ring if none is left; android store schema version 6 (`migrations/5.sqm`: `fire_telemetry.ring_ended_by_cap INTEGER`), the repository write, the count in `ReliabilityReport`, `ReliabilityText` and the export. **Out of scope:** per occurrence notifications (PR 6); any change to ladders, grace or the budget's rule.

**Relies on.** ADR 0090; PR 2 merged (Gentle never rings, so the cap concerns Critical and Standard).

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `RingLimitTest` `MAX_RING is shorter than every gap between device rungs` (shared constants read from android's test) | `EscalationLadder.forOccurrence` for each criticality, filtered to the device's channels | For every pair of consecutive device rungs, the gap is greater than `MAX_RING`; the test fails closed if no ladder has two device rungs | Set `MAX_RING` to 5 minutes |
| `the sound and the vibration stop at the cap` (SDK 29, 33, 34, 36; Tier 2 and Tier 3) | One Critical occurrence ringing; the ringer's scheduler advanced to one millisecond before `MAX_RING`, then past it | Before: the primary or backup player is playing and vibration is on. After: both stopped, the service is not in the foreground | Never schedule the cap |
| `the backup sound still starts at two minutes` | Advance to 2 minutes, then to the cap | The backup replaces the primary at 2 minutes and both are silent after the cap | Cancel the backup when the cap is scheduled |
| `the cap is a dismissal and writes nothing` | As above, with the event table and the occurrence row read before and after | No event added, the row byte equal, state still `PENDING`; `ensureArmed` then holds the next rung's alarm at five minutes from the first | Write `SKIPPED` at the cap; cancel the armed alarm |
| `her actions stay after the cap` | One occurrence; then two (after PR 6 this case is re run there) | A notification with the session's id is still posted, is not ongoing, has no full screen intent, has only alert once set, and carries exactly the offered actions; pressing Taken writes ADR 0066's delta | Cancel the notification at the cap |
| `Stop the sound leaves the same state as the cap` | Press it instead of waiting | The same assertions as the row above, and no event | Keep the old cancel in `stopSound` |
| `the ring screen keeps the list and lets the display sleep` | The screen showing when the cap ends the sound | The items and their action buttons are still shown, the "sound is off" line is shown, the stop button is gone, and the window no longer has keep screen on | Finish the Activity at the cap |
| `the repeat rung rings again, for a fresh cap` | Critical: the cap ends the first ring at 4 minutes; the repeat fires at 5 | A second ringer start; it is not a continuation; the sound stops again 4 minutes after the repeat's fire, not 4 minutes after the first | Keep one timer from the session's first start |
| `a fire that continues or joins a sounding session restarts the cap` | A second occurrence fires 3 minutes into a ring | The sound is still playing 4 minutes after the first fire and stops 4 minutes after the second | Do not reschedule on a join |
| `a repeat after the sound has stopped rings again and spends again`, run twice: the sound ended by the cap, and by "Stop the sound" | Standard, budget 10. Fire the first rung; end the sound (advance the scheduler past `MAX_RING`, or press "Stop the sound" after one minute); fire the repeat at ten minutes | In both runs: the ringer starts a second time, the repeat is not marked a continuation, and the budget counts two; with the budget set to 1 the repeat is a silent notification on Quiet notices and there is no second start | Treat any fire for an occurrence in the session as a continuation (no second start, count 1, in both runs) |
| `DeliveryTest` `a rung for an occurrence that is ringing continues the ring` (Phase 2; **its fixture changes, its expectations do not**) | Its repeat at ten minutes used to land in a ring with no limit. Now: occurrence `a` fires; the cap ends the sound at four minutes; a second occurrence fires at seven minutes, less than `MAX_RING` before `a`'s repeat, which starts the session's sound again with `a` still in the session and unanswered; so the sound is on when `a`'s repeat fires at ten minutes | Exactly its Phase 2 assertions for `a`: the repeat is a continuation, there is no second ringer start for it, `ALARM_FIRED` is still written, and the budget counts `a` once (the joining occurrence's own ring is counted as its own) | The Phase 2 mutation for this test, re run; and "start a ring for every fire" (a second start) |
| `the end of grace ends the ring` | A Critical occurrence snoozed so that its snooze ends 2 minutes before its grace; it rings; the clock passes grace; the watchdog pass runs with the sound still inside its cap | After the pass: the occurrence is `MISSED`, the session has ended, the sound and vibration have stopped, its notification is cancelled | Do not call `dropClosed` after `Reconcile` |
| `a ring cannot outlive grace by more than the cap with no pass at all` | The same, with no pass run; the scheduler advanced by `MAX_RING` | The sound has stopped | (the cap's own mutation, observed here) |
| `a ring that ran out is recorded, and one she answered is not` | Two fires: one left to the cap, one acknowledged after a minute | `ring_ended_by_cap` is true on the first fire's telemetry row and false on the second's; the report's count is 1; the export carries the count and no timestamp | Set the flag in the dismissal path shared with "Stop the sound" (the pressed case would count) |
| `AndroidStoreMigrationTest` addition | Store versions 1 to 5 with telemetry rows | Migrated to 6; old rows have null in the new column; nothing else changed | Drop the `ALTER TABLE` |

**Imports.** None new.

**Phase 2 expectations.** None changes in this pull request. `DeliveryTest` `a rung for an occurrence that is ringing continues the ring` keeps its subject and every assertion, with the fixture change in the table above (settled by Claude (technical review)). The dismissal that keeps her notification changes no Phase 2 expectation either: the two Phase 2 tests of "Stop the sound" (`DeliveryTest` `stopping the sound writes nothing and the next rung still rings`, `RingerAndScreenTest` `stopping the sound from the screen leaves the log alone`) do not assert that the notification is cancelled, and everything they do assert still holds.

**STOP points.** Any Phase 2 test whose expectation, not its fixture, must change. If a Phase 2 test is found that does assert the notification is cancelled by "Stop the sound", that one expectation is authorised to change (the correction above) and is named in the pull request description; anything else is a STOP.

**Docs.** `ARCHITECTURE.md` sections 4.2 (a repeat is a new ring), 4.3 (what a repeat spends), 4.6, 5.5 (the cap beside the ramp and the backup sound), 5.7 and 5.10 (the evidence); `MANUAL_CHECKS.md` P2-20 and P2-21 (`MAX_RING` joins the placeholders a device judges) and P2-17; traceability.


### PR 3. `phase-3/template-edit` (Sonnet; shared only)

**Goal.** `EditTemplateCommand` and `CreateTemplateCommand`, golden scenario 1, and scenario 12's real interleaved edit.

**Scope.** `shared/.../data/EditTemplateCommand.kt` implementing ADR 0079 rules 1 to 7 and 9 exactly: `dispatch(edited: ScheduleTemplate, now: Instant): EditResult` where `EditResult(withdrawn: List<Occurrence>, moved: Int)`; `CreateTemplateCommand.dispatch(template)` (an insert; validation of title length 1 to 24, `EveryNDays` n at least 1, a non empty weekday set, returning a typed refusal and writing nothing otherwise; the edit command applies the same validation); `ScheduleTemplateRepository.update(template)` (every editable column and the tag rows, in the caller's transaction) replaces `setActive`, which is removed with its query; `TemplateEdit.cameDue(occurrence, firedCount, now)` and `TemplateEdit.wants(template, localDate)` as pure functions in `engine`; the zone change command is unchanged. `Seeder` (debug) switches to `EveryNDays(3650, today)` and calls no `setActive`. **Added in PR 1 (#28), by Claude (technical review):** `OccurrenceRepository.findByTemplateAndDate` expects one row (`executeAsOneOrNull`) and throws once a date holds a withdrawn row and a new one, which this PR's edit command creates. In this PR: delete it if it still has no production caller (it has none at PR 1), or else make it return the row that is not withdrawn. A test with a withdrawn row and a new row for one date; mutation: the current single row expectation. **Out of scope:** anything in `android/src/main`.

**Relies on.** ADR 0079. PR 1 and PR 2 merged.

**Golden scenario 1**, `TemplateEditScenarioTest` `scenario 1 an edit of timeOfDay after some occurrences are terminal`. Setup, with a real database and a fixed clock at 10:00 on day D in Asia/Kolkata: a Daily STANDARD template at 08:00; materialise D-1, D, D+1. D-1 is completed through `OccurrenceActionCommand`; D (08:00, two hours past) has one `ALARM_FIRED`; D+1 is pending. Dispatch an edit to 14:30. Assert, on rows read back and on the event table:

1. D-1: every column equal to its value before the edit, state `COMPLETED`.
2. D: every column equal (it has come due); no event added for it.
3. D+1: `scheduledInstant` is D+1 14:30 in the zone; id, `localDate`, `alarmSlot`, state and `criticality` unchanged.
4. The event table has exactly the rows it had before.
5. Materialising D to D+3 afterwards adds D+2 only, at 14:30, with a slot greater than every earlier slot.
6. **Nothing is missed because of the move, shown by running the step that would miss it.** Dispatch `ReconcileCommand` at 13:00 on D+1. That is after the grace of D+1's old instant would have ended (08:00 plus four hours, 12:00) and before the grace of its new instant ends (14:30 plus four hours, 18:30). Assert: D is `MISSED`, with `effectiveAt` 12:00 on D, the end of its own unmoved grace; D+1 is not `MISSED` and has no `MISSED` event; D-1 is `COMPLETED`; the adherence figures as of 13:00 on D+1 are completed 1, missed 1, skipped 0.

A second case in the same class, `an edit to earlier than now keeps today's time`: at 10:00, a pending occurrence today at 12:00, edit to 09:00; today's instant stays 12:00, tomorrow's becomes 09:00. Mutations, each of which must fail the named assertion and no setup step: (M1) drop the came due check, assertion 2 fails on D's instant and assertion 6 on D's `effectiveAt` (18:30 instead of 12:00); (M5) leave open occurrences that have not come due unmoved, assertion 3 fails on D+1's instant and assertion 6 on D+1, which is `MISSED` with missed 2; (M2) recompute every occurrence of the template including terminal ones, the schema's abort fails the command, which is the intended failure, as in ADR 0068; (M3) remove the "not before now" rule, the second case's today instant fails; (M4) reschedule by deleting and inserting, assertion 3 fails on id and slot.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `scenario 1 ...` and `an edit to earlier than now keeps today's time` | Above | Above | M1 to M4 |
| `deactivation withdraws every open occurrence, due or not` | Pending tomorrow, snoozed today, completed yesterday; deactivate; **then dispatch `ReconcileCommand` at an instant after the grace of both withdrawn occurrences has ended** (the step that would write `MISSED` if a withdrawn occurrence were still open) | Two `WITHDRAWN` events, source USER, no payload; two rows `WITHDRAWN`; yesterday untouched; after the reconcile, no `MISSED` event exists and the figures as of then are completed 1, missed 0, skipped 0 | Skip occurrences that have come due (the snoozed one stays open and the reconcile misses it: missed 1); write `SKIPPED` (skipped 2) |
| `a recurrence change withdraws exactly the dates no longer wanted` | Daily to Weekly on two weekdays, over a window of three days of which one stays wanted; **then dispatch `ReconcileCommand` after the grace of all three** | The unwanted two are withdrawn and are never `MISSED`; the wanted one is untouched or moved by rule 5 and, nothing having been done about it, is the one `MISSED` after the reconcile; figures 0, 1, 0 | Invert `wants` (the wrong ones are withdrawn: the wanted date has no miss and an unwanted one has); withdraw only when inactive (missed 3) |
| `reactivation materialises fresh occurrences and never a past one` | Deactivate at 10:00 with today 09:00 (came due) and today 20:00 and tomorrow; reactivate at 10:05; materialise | New rows for today 20:00 and tomorrow with new ids and slots; none for 09:00; the three withdrawn rows unchanged | Reuse the withdrawn row's id; include past instants |
| `a criticality edit reaches only occurrences that have not come due` | CRITICAL to GENTLE with one ringing (one `ALARM_FIRED`), one overdue and unfired, one tomorrow | The first two keep CRITICAL, tomorrow's is GENTLE; with `ArmingSelection` over the result, the ringing one's next rung is still the CRITICAL repeat | Update every open occurrence's criticality |
| `a criticality edit never ends a grace in the past` | A GENTLE occurrence at 08:00, overdue and unanswered; at 15:00 the template is edited to CRITICAL, whose two hour grace from 08:00 ended at 10:00; **then dispatch `ReconcileCommand` at 15:01**, the step that would miss it at once if the occurrence had taken the new criticality; then again after midnight | At 15:01 it is not `MISSED` and has no `MISSED` event; after midnight it is `MISSED` with `effectiveAt` the end of its local day, Gentle's grace | Update every open occurrence's criticality; read the template's criticality in `Reconcile` (PR 2's mutation, seen here too): `MISSED` at 15:01 with `effectiveAt` 10:00 |
| `an edit of a snoozed occurrence changes nothing of it` | Snoozed, `snoozedUntil` in ten minutes; edit time and criticality | Row and events unchanged; `runningSnoozeEnd` unchanged | Drop `SNOOZED` from came due |
| `the edit is one transaction` (android, `TemplateEditRaceTest`, in PR 4's suite but specified here) | As `TimeZoneRaceTest`: hold the command on a latch inside its transaction, start a materialisation run, release | The run completed only afterwards, and every row it inserted is at the edited time | Remove the transactor |
| `other fields touch no occurrence` | Edit title, notes, dosage, instructions, tags, inventory | No occurrence row and no event changes | (none: a guard test; its positive control is the time edit above) |
| `validation refuses and writes nothing` | Each invalid input | A typed refusal; the template row unchanged | Remove each check |
| `after any edit no open occurrence belongs to an inactive template` | Property over random edits (the generator below) | The invariant, after every step | Skip withdrawal on deactivation |
| `doctor instructions are stored verbatim` | Text with digits, units, line breaks, Devanagari | Byte equal on read | Trim it |

**Scenario 12**, `GoldenScenariosDbTest`, the existing property test rebuilt: seeds 1 to 20, 60 steps each, a fixed span of 20 days and a clock that only moves forward. Each step is one of: materialise a random window; advance the clock by 0 to 36 hours; **edit `timeOfDay`** to a random quarter hour; **edit the recurrence** to a random one of Daily, Weekly with a random non empty weekday set, or EveryNDays with n from 1 to 4 and an anchor in the span; deactivate; reactivate; complete a random open occurrence. After every step assert:

1. no two rows that are not `WITHDRAWN` share a template and date;
2. no two rows ever share an `alarmSlot` or an id, and no slot or id seen once is seen later on a different date;
3. a row first seen terminal, or seen to have come due, is byte equal ever after except for a transition to a terminal state through an event written in the same step;
4. every other row changed only in `scheduledInstant`, `criticality` or by a transition.

After the last step, materialise the whole remaining span and assert **convergence**: the set of open rows whose instant is after `now` equals, date for date and instant for instant, the expansion of the final template's recurrence at its final `timeOfDay` over the window, less dates that hold a terminal row that is not withdrawn. **Then dispatch `ReconcileCommand` at an instant after the grace of every row in the span**, and assert that no row that was `WITHDRAWN` has a `MISSED` event, and that every `MISSED` event's `effectiveAt` is its own occurrence's `scheduledInstant` plus the grace of that occurrence's own criticality: no miss was produced or dated by an edit. The generator must be shown to reach the distinguishing cases: the test counts, per seed, edits that moved at least one row, edits that withdrew at least one, and reactivations that created at least one, and fails if any of the three totals is zero over the 20 seeds. Mutations: remove the materialiser's `filterNot` (assertion 1, by the unique index's throw, the intended failure); make the index non partial (a reactivation step throws); move rows that have come due (assertion 3, and the final `effectiveAt` check); leave `timeOfDay` unapplied to open rows (convergence).

**STOP points.** Any rule of ADR 0079 that two tests here would force to differ. `findOpen` or the per template queries needing a new index.

**Docs.** `ARCHITECTURE.md` sections 3.1, 3.2, 11 ("Template edited mid day"); `IMPLEMENTATION_PLAN.md` scenario 1 and 12 status, with the test names; traceability G1, G12, O-deactivation.

### PR 4. `phase-3/edit-pass` (runs on Fable)

**Why Fable.** It joins the edit to the ring session, the notifications, arming and the reliability reductions, inside the coordinator's exclusion; the cases are few but each is on the alarm path.

**Goal.** `TemplateEditPass` (ADR 0079 item 8), withdrawal reaching the ring session and notifications, and reliability evidence that does not count an edit as a failure (item 10).

**Scope.** `work/` or `arming/` `TemplateEditPass`; `RingController.withdrawn(occurrences)`; `FireTiming` treats `WITHDRAWN` before the rung as it treats `COMPLETED` and `SKIPPED`; `TemplateEditRaceTest`; the debug seed's tests. No UI.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `EditPassTest` `an edit of the armed occurrence's time re arms it` (SDK 29, 31, 33, 36) | One pending occurrence armed for tomorrow 08:00; edit to 09:00 through the pass | `ShadowAlarmManager` holds exactly one alarm, at 09:00, with the occurrence's slot; one new `ALARM_SCHEDULED` | Remove `ensureArmed` from the pass |
| `deactivating a ringing reminder stops the ring` | Tier 3, one occurrence ringing; deactivate through the pass; **then run the watchdog pass (which dispatches `Reconcile`) after the occurrence's grace has ended** | The session has ended, the ringer stopped, `RING_ID` and the slot's notification are cancelled, no alarm is armed, the state is `WITHDRAWN` and the log holds no `COMPLETED`; after the watchdog pass there is no `MISSED` event, no silent notice and no `WATCHDOG_REPAIR` | Remove the call to `withdrawn` (the ringer is still running); leave the occurrence open in the pass (the watchdog pass writes `MISSED`) |
| `deactivating one of two ringing leaves the other` | Two ringing | The other still rings with its actions | Call `sessions.end()` |
| `a template edited from Gentle to Critical rings on its next occurrence and not on one that has come due` (ADR 0089; it needs the edit command, so it is here and not in PR 2) | A Gentle template with today's occurrence already come due (its instant passed, its notification posted) and tomorrow's pending; edit to Critical through the pass; deliver today's late alarm again and then tomorrow's fire | Today's occurrence keeps `criticality` GENTLE and its fire starts no ringer; tomorrow's is CRITICAL and its fire starts the ringer on the Critical channel with a full screen intent | Update every open occurrence's criticality (today rings); read the template in the policy (today rings) |
| `a withdrawn occurrence's late alarm delivers nothing` | Withdraw, then deliver the old alarm's broadcast | No event, no notification, and `ensureArmed` ran | (guards Phase 2's terminal rule for the new state) Remove `WITHDRAWN` from the fire path's terminal check |
| `ReliabilityReaderTest` `an edit raises no never fired rung` | Arm ahead; move by an edit; let the old instant pass by 20 minutes; and the same with a withdrawal | `neverFired` is 0 in both | Remove `WITHDRAWN` from the closing set; the second case reports 1. If the first case needs a change to `FireTiming`'s pairing rule, STOP |
| `TemplateEditRaceTest` | As specified in PR 3 | | |
| `SeederTest` `the test reminder is active, one off in effect, and rings` | Seed; run the daily materialisation for the next three days | One occurrence for the test template; it is armed; no inactive template has an open occurrence | Use `Recurrence.Daily` |

**STOP points.** As marked. Any need to change `ensureArmed`'s selection.

**Docs.** `ARCHITECTURE.md` sections 5.3, 5.7, 5.10; `MANUAL_CHECKS.md` P3-7 (edit and deactivate a ringing reminder on a device).

### PR 5. `phase-3/reductions` (Sonnet)

**Goal.** The two reductions and the water command (ADR 0086, ADR 0083 item 1), in `shared`, pure, mutation checked.

**Scope.** `engine/NutritionReduction.kt`, `engine/WaterReduction.kt`, `data/LogWaterCommand.kt`, `engine/GestationalAge.kt` (`weekAt(dueDate, onDate)`: `(280 - daysUntil(dueDate)) / 7` by floor, or null outside 0 to 42; due date from `dueDateRevisionAsOf`), `EventRepository.findWaterInRange`. `EventLogReduction.outcomes` is made shared between adherence and nutrition, not copied.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `NutritionReductionTest` `counts servings per tag per scheduled date` | Three completions on two dates with overlapping tags | The exact map | Count once per occurrence |
| `editing a template's tags does not change a past count` | Complete through `OccurrenceActionCommand`; edit tags through `EditTemplateCommand`; complete the next day | Day one counts under the old tags, day two under the new | Read `template.nutritionTags` in the reduction |
| `a backfill counts on its scheduled date from when it is entered` | Missed, then `COMPLETED_BACKFILLED` a day later; `asOf` before and after | 0 before, 1 after, on the scheduled date | Attribute to the event's date; ignore `asOf` |
| `mission events, skips, misses and withdrawals count nothing` | One of each beside one completion | Only the completion's tags | Add `MISSION_VERIFIED` to the counted set |
| `nutrition agrees with adherence` (property, seed fixed) | Random logs | For every date, the number of occurrences counted under at least one tag is at most adherence's completed figure, and equal when every completion has a tag | Use a separate outcome rule that prefers the earliest event |
| `WaterReductionTest` `a glass belongs to the day it was drunk in the zone it was logged in` | Glass 1 logged at 02:00 on day D in Asia/Kolkata (which is day D-1 in New York), with zone Asia/Kolkata; glass 2 logged at 01:00 on day D+1 Kolkata time (15:30 on day D in New York), with zone America/New_York | The totals are exactly `{D: 500}`: each glass on the date it had where she was. Attributing both by Kolkata would give `{D: 250, D+1: 250}` and both by New York `{D-1: 250, D: 250}`, so either single zone fails | Attribute by one zone passed by the caller, tried with each of the two zones |
| `a row with no zone is attributed in the fallback zone and only such a row is` | One water row with a null zone and one with a zone, either side of midnight in the fallback zone | The null row lands on the fallback zone's date; the other on its own zone's date | Use the fallback zone for every row |
| `asOf is inclusive and nothing after it counts` | Three events around `asOf` | The boundary event counts | Use a strict bound |
| `LogWaterCommandTest` | Dispatch with a zone and a fixed clock | One `WATER_LOGGED`, source USER, no occurrence, 250 ml, the zone; nothing else written | Omit the zone (decoding fails) |
| `GestationalAgeTest` | Due date 280 days ahead, 0 days, 14 days past, 300 days ahead; a revised due date read as of before and after the revision | Weeks 0, 40, 42, null; the earlier `asOf` gives the earlier revision's week | Read the latest revision always |

**STOP points.** The nutrition and adherence outcome rules cannot share one function without changing adherence's tests.

**Docs.** `ARCHITECTURE.md` sections 3.5, 3.6, 3.8; `IMPLEMENTATION_PLAN.md` (the two reductions built); traceability X-reductions.

### PR 6. `phase-3/notifications` (runs on Fable)

**Why Fable.** ADR 0080 is a small state machine on the ring path, with platform behaviour read from AOSP and four device questions; an implementer must judge transitions the ADR could not enumerate. It is built after PR 2 and PR 2b, because a session can now end by the cap and a Gentle fire never enters one.

**Goal.** ADR 0080: the session spreads at the second occurrence, and the invariant holds through every join, action, refusal, stop and end.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `SpreadTest` `one occurrence keeps its buttons on the session notification` (SDK 29, 33, 34, 36; Tier 2 and Tier 3) | One fire | `RING_ID` has the three actions; no notification with the slot's id | Always spread |
| `a second occurrence spreads the session` | Two fires | `RING_ID` has no actions and only alert once; each slot has a notification on its criticality's channel with exactly its offered actions, each an immutable explicit `PendingIntent` to the unexported receiver; neither has a full screen intent or a delete intent; `RING_ID` keeps the full screen intent in Tier 3 | Skip the per occurrence post; leave the buttons on `RING_ID` |
| `the invariant holds along every path` | A table of event sequences: join; act on each; refuse; stop sound; last one acted on; a third joins after one left; **the cap ends the sound** (PR 2b: nothing is resolved, every notification and its actions stay, the session's notification becomes an ordinary one); **the cap ends the sound, then the next rung fires** (a new ring starts for the same occurrences: a session that had spread is spread again from its first fire, and no second notification appears for any occurrence); **a Gentle fire while the session rings** (ADR 0089: no join, no spread, its own notification); **grace ends for one of two** (PR 2b: it is dropped and its notification cancelled, the other keeps its own) | After each event: every occurrence that is ringing, or was ringing when the sound was dismissed and is still open, has exactly one notification carrying exactly its offered actions; a resolved one has none | Move the buttons back when one remains; on the rung after a cap, post a second notification for an occurrence that still has one |
| `a button on a spread notification writes exactly its event` | Press each | The delta of ADR 0066 | (Phase 2's mutations, re run against the new notification) |
| `a refused action refreshes the buttons where they are` | A fourth snooze on a spread notification | That notification is reposted without snooze | Refresh `RING_ID` only |

**STOP points.** Anything the invariant does not decide.

**Docs.** `ARCHITECTURE.md` section 5.7; `MANUAL_CHECKS.md` P3-2 to P3-5.

### PR 7. `phase-3/ui-foundation` (Sonnet)

**Goal.** Compose and Roborazzi in the build, fonts, colour roles and their tests, the pair components, the appearance store and state, the theme, the screenshot harness and its CI job. No screen yet.

**Scope.** The catalog and `android/build.gradle.kts` entries of ADR 0084, exactly; `ComponentActivity` in the debug manifest; `res/font` files and `docs/THIRD_PARTY.md` with licences (ADR 0085); the 21 icons; `ui/theme/{ColorRoles,Palettes,RolePair,Appearance,AppearanceState,MomTimeTheme,Type,Shapes,FontChain,RingRoleBinder}.kt` with the values of the design spec sections 1 to 4, exactly; the pair components of ADR 0075 item 3 covering every component of design spec section 5, each taking a `RolePair` and no colour; `verifyColourBoundary` and its fixture self test (ADR 0075 item 4), added to `check` and to the `verify-android-structure` job; `AndroidSettings` appearance, palette and locale keys; the splash styles and the `SplashTheme` seam (ADR 0074 item 6); `ScreenshotMatrix` (the nine combinations and six appearances, strict comparison, the paused clock, SDK 36, the size `w360dp-h800dp-mdpi`, and the nine type specimens at xhdpi, ADR 0077 item 6); the fixture resources and their generator, `verifyNoFixtureResources` and its self test (ADR 0077 item 4); the CI job `android-screenshots`; `verifyNoCoroutinesInAndroid`.

**Recording goldens (ADR 0077 item 2; proved in PR #27).** The implementing session is on Windows. Goldens that reach the repo are the ones Linux CI rendered. The job `android-screenshots`, copied from the throwaway branch's `ci.yml`, does this on every pull request: (1) `verifyRoborazziDebug` against the committed goldens, allowed to fail; (2) if it failed, keeps the comparison images; (3) `recordRoborazziDebug`, so the workspace holds what this commit renders on Linux; (4) uploads `android/src/test/snapshots` as the artifact `goldens`, and the comparison images as `screenshot-diff` if step 1 failed; (5) fails if step 1 failed. The steps for the implementer:

1. Write or change the screen and its `ScreenshotTest`. Run `./gradlew :android:recordRoborazziDebug` locally and look at the images to check the screen is right. Do not commit them.
2. Push. `android-screenshots` goes red, because a golden is missing or differs. That is expected exactly when a screen is new or was changed on purpose.
3. When the run has finished, list its artifacts and download `goldens` (GitHub MCP: `actions_list` with `list_workflow_run_artifacts` for the run id, then `actions_get` with `download_workflow_run_artifact`; fetch the returned URL with `curl -L -o goldens.zip` and unzip it over `android/src/test/snapshots`). If the job failed on a changed golden, download `screenshot-diff` too.
4. Look at every new or changed PNG, and at each comparison image. A changed golden is committed only if the change is the one intended; say in the pull request description which goldens changed and why. A golden that changed and was not meant to is a regression: fix the code, not the golden.
5. Commit the downloaded files unchanged and push. `android-screenshots` must now be green. Never commit an image recorded on Windows.
6. A red `android-screenshots` on a pull request that did not mean to change a screen is a failure like any other.

`.gitattributes` marks `*.png` as binary so that no line ending conversion touches a golden.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `ContrastTest` `every pair meets its minimum, unrounded` | Every appearance and palette, every `RolePair` | The exact ratio, as a `Double`, is at or above the pair's minimum; no rounding anywhere in the test or the function | Lighten one palette's `onMuted` to `#9A9A9A` |
| `ContrastTest` `a ratio that only rounds up to its minimum fails` | The fixed control `#777777` on `#FFFFFF`, whose ratio is 4.478, against 4.5; and `#767676` on white, 4.54, as the control that passes | The checker reports the first as failing and the second as passing | Compare the ratio rounded to one decimal: the first control passes and the test fails. (The review asked for a threshold lowered by 0.05 to fail on a real pair within 0.05 of its minimum. No such pair exists, the closest being 4.71 against 4.5, and a lowered threshold can only make a test more lenient, so the fixed control stands in: it is 0.022 under.) |
| `ContrastTest` `every role is in a pair` and `every pair of the design spec is present` | Reflection over `ColorRoles`; the spec's pair list written out in the test | No role unused; no listed pair missing | Add a role with no pair; delete a pair |
| `PalettesTest` `values are the specification's` | The table of design spec section 2, written out in the test | Equal | Change one value |
| `verifyColourBoundary` self test | Fixtures outside the theme package: `Color(0xFF00FF00)`, `Color.Red`, `android.graphics.Color.RED`, a bare `0xFF112233` literal, `colorResource(...)`, `R.color.x`, `MaterialTheme.colorScheme.primary`, `roles.onSurface`; a resource file with a `<color>` and one with `android:textColor="#123456"`; and the same Kotlin inside the theme package, the notification icon and the splash styles file | Each of the first ten is flagged exactly once; the last three pass; an empty source set fails | Drop one pattern from the check: its fixture is no longer flagged |
| `PairComponentTest` `a component draws exactly its pair` | Each pair component composed with each `RolePair` in one palette, captured to a bitmap | The background pixel is the pair's background role and the content colour reaching a child is the pair's foreground role; reflection shows no public composable in the theme package has a parameter of type `Color` or `Int` named like a colour | Give `PairText` a `color` parameter; draw the wrong role |
| `AppearanceStoreTest` `a choice survives process death` (SDK 29, 36) | Choose Pastel and Mint; build a new `AndroidSettings` and `AppearanceState` over the same context | Pastel and Mint | Keep the choice in memory only |
| `the default is System and an unknown name reads as the default` | Fresh; a stored `"NEON"` | System; System | Default to Light |
| `SplashThemesTest` (SDK 33, 36) | Each appearance and palette | The style for it exists under its pinned name, and its background resolves to that palette's `background`; choosing an appearance calls the `SplashTheme` seam with that style's id, and System calls it with `ID_NULL`; below API 33 the seam is not called | Change one style's colour; skip the call on change; pass the Light style for System |
| `BackupRulesTest` addition | The appearance and locale keys | Written to the file the rules include | Write them to another file |
| `FontChainTest` (SDK 29, 36) | Spike S7's measurements, with its positive control | Devanagari as the bundled Noto; Latin as Nunito; weights differ | Build the chain without the fallback |
| `ThirdPartyTest` | `res/font`, the icon list, `THIRD_PARTY.md` | Every file recorded, hashes equal | Alter a hash |
| `ComponentScreenshotTest` | Each component of section 5 in each state, through `ScreenshotMatrix` | Goldens | Change a card's padding by 1 dp: `android-screenshots` fails with a comparison image. A missing golden fails it too |
| `LineHeightTest` | The type table of section 3 written out in the test, per locale | Equal; text she typed uses the hi value in en | Use the en value for her text |
| `verifyNoCoroutinesInAndroid` self test | Fixtures | An import is flagged; a comment is not | Empty the pattern |

**Imports.** `androidx.compose.ui.*` from `androidx.compose.ui:ui`; `androidx.compose.foundation.*` from `foundation`; `androidx.compose.material3.*` from `material3`; `androidx.activity.compose.*` from `activity-compose`; `androidx.compose.ui.test.junit4.*` from `ui-test-junit4`; `com.github.takahirom.roborazzi.*` from `roborazzi` and `roborazzi-compose`. And the eight sub modules, declared by name (section 4): `androidx.compose.runtime.*` from `runtime`, and `androidx.compose.runtime.saveable.*` from `runtime-saveable`; `androidx.compose.ui.graphics.*` from `ui-graphics`; `androidx.compose.ui.text.*` from `ui-text`; `androidx.compose.ui.unit.*` from `ui-unit`; `androidx.compose.foundation.layout.*` from `foundation-layout`; `androidx.compose.ui.test.*` from `ui-test`; `androidx.activity.ComponentActivity` from `androidx.activity:activity` 1.13.0. A test, `DeclaredImportsTest`, reads the imports of every android source file and fails on an androidx package whose declaring artifact, from a table in the test, is not in the build file's declared dependencies; mutation: remove `ui-unit` from the build file (the build still compiles, through the BOM's transitive graph, and the test fails).

**STOP points.** The resource compiler rejects the `hi-rXA` or `mr-rXA` qualifier, or a `hi-rXA` request does not resolve the fixture resources under Robolectric. A golden downloaded from the job does not verify in the next run of the job. Goldens exceed 60 MB. Any artifact resolving to a version other than ADR 0084's. Lint or detekt needing a rule changed for Compose (function naming): report the rule, do not disable broadly. A component of the design spec that cannot be expressed as taking one `RolePair`.

**Docs.** `ARCHITECTURE.md` section 9 and a new section on the UI layer; `README.md` suites table and the recording steps; `IMPLEMENTATION_PLAN.md` Working conventions (approved dependencies, the recording rule); `MANUAL_CHECKS.md` P3-1.

### PR 8. `phase-3/app-shell` (Sonnet)

**Goal.** `MainActivity` as the launcher entry, navigation, the top bar with the appearance control, the sheet, the ring screen taking the appearance, the exported allowlist, `DataChanges`.

**Scope.** `ui/MainActivity`, `ui/nav/{Screen,ScreenRegistry,BackStack}`, `ui/UiGraph`, `UiEntryPoint`; `AppBar`, `AppearanceControl`, `AppearanceSheet`; placeholder bodies for Today, Overview and Settings that show only their title (replaced by later PRs); `shared` `DataChanges` (ADR 0081 item 1); `ring/RingAppearance` binder and the layout of design spec screen 17; the ring screen's idle button opens `MainActivity`; `LocalizedContext`, `attachBaseContext`, every existing string read outside an Activity moved onto `LocalizedContext` (the notifications of `RingNotifications` and the ringer service, the reset notification, `NotificationChannels.ensure`, which is also called when the override changes), and `verifyLocalizedStrings` with its self test (ADR 0082 item 3); the back stack's saver and `android:enableOnBackInvokedCallback` (ADR 0082 item 4); the call to the `SplashTheme` seam on a change and at process start; `verifyExportedComponents` and its self test with the `MainActivity` and library entries; `windowDisablePreview` on `RingActivity`.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `SwitcherPresenceTest` | Every `Screen` of `ScreenRegistry` marked top level or onboarding, composed in turn | A node with the appearance control's tag exists and its content description is the current appearance's sentence; the test fails closed if the registry is empty, and a second test asserts by reflection that every subclass of `Screen` is in the registry | Remove the control from one screen's bar; add a `Screen` without registering it |
| `AppearanceSheetTest` `a choice applies at once, persists, and recreates nothing` (SDK 29, 36) | Push a second screen; open the sheet; choose Dark, then Pastel and Blush | After each tap: the background node's colour is the role's; the stored value changed; the Activity instance and the back stack depth are unchanged; no confirm control exists | Recreate the Activity on change; apply only on dismiss |
| `the content description names the appearance in three locales` | en, hi, mr (fixtures for hi and mr) | The sentence contains the appearance's localized name | Use the enum's name |
| `RingAppearanceTest` `a choice reaches the ring screen at its next show` (SDK 29, 36) | Choose Pastel and Peach in the store; show `RingActivity` with one item | The window background is Peach's `ringBg`, the title's colour its `onRing`, the buttons' its `ringAction` | Read the appearance in `MomTimeApplication` only once into a constant |
| `the ring package still names no data type` | `verifyRingUiBoundary` | Passes; its self test unchanged | Import `AndroidSettings` in the ring package (it must be passed a `ColorRoles` by its host) |
| `ScreenshotTest` for screens 16 and 17 | The matrix | Goldens; at 200 percent in hi and mr every action label is whole | Fix a button's height at 48 dp: the 200 percent captures differ and `NoClipTest` (below) fails |
| `NoClipTest` | Every text node of a captured screen, by semantics: its laid out text has no visual overflow and no ellipsis | True for every node in all nine combinations | Add `maxLines = 1` to a button label |
| `DataChangesTest` (shared, and android under `AndroidSqliteDriver`) | Subscribe; write through a repository, through a command in a transaction, and roll one back | One or more calls after each commit, none after the rollback, none after unsubscribing | Notify inside the transaction before commit |
| `LocaleSurfacesTest` (SDK 29 and 36), one case per surface | The override on Hindi, the device on English, through the fixture locale `hi-rXA` (ADR 0077 item 4) | Each surface shows the fixture Hindi string and not the English one: `MainActivity`'s title; the ring screen's labels; a reminder notification posted by the fire path's receiver (Tier 1) and its three action titles; the ring session's notification posted by the ringer service; a silent notice posted from the watchdog's worker pass; the reset notification posted by the corruption handler; the name and description of each of the four channels after `ensure`, and again after the override changes from Hindi to Marathi without a process restart. Clearing the override shows English everywhere | For each surface in turn, read its string from the plain context: only that case fails. Do not call `ensure` on an override change: the Marathi case fails |
| `verifyLocalizedStrings` self test | Fixtures in `delivery` and `work`: `context.getString(...)`, `resources.getQuantityString(...)`, `localized.getString(...)`; the first two in an Activity package | Flagged, flagged, allowed; allowed | Narrow the package list |
| `StateSurvivalTest` `the back stack survives rotation, a locale change and process death` (SDK 29, 36) | Push two screens, one carrying an id; then each of: recreate with a new orientation; change the override (which recreates); save the instance state, destroy the Activity and build a new one from the saved state | The same three screens in the same order with the same id, each time | Hold the stack in `remember` instead of `rememberSaveable` |
| `an appearance change loses nothing because nothing is recreated` | Two screens deep, a sheet open, a field half typed in a test form; choose Dark | The same Activity instance, the same stack, the sheet still open, the field's text intact | Recreate on change |
| `PredictiveBackTest` (SDK 33, 34, 36) | The merged manifest; `MainActivity` at depth 1, at depth 2, and at depth 1 with the sheet open | `enableOnBackInvokedCallback` is true on `MainActivity`; `onBackPressedDispatcher.hasEnabledCallbacks()` is false, true, true; dispatching back at depth 2 pops one screen and at depth 1 finishes the Activity; no source overrides `onBackPressed` or handles `KEYCODE_BACK` (a scan in the test) | Enable the handler always; remove the manifest attribute |
| `verifyExportedComponents` self test | Fixtures: an extra exported component, a listed one missing, a changed permission, an intent filter with no `exported` attribute, the exact list | Each of the first four fails, the last passes; a missing manifest fails | Compare in one direction only |
| `BackStackTest` | Push, rotate (recreate), back | Depth and top preserved; back at depth 1 finishes | Do not save the stack |

**Imports.** As PR 7; nothing new.

**STOP points.** The ring screen cannot take the roles without naming a forbidden type. A surface whose string cannot be routed through `LocalizedContext`. The system's per app language (API 33) conflicts with the override in a way a test shows.

**Docs.** `ARCHITECTURE.md` sections 5.2, 5.7, 9 and the UI section; `MANUAL_CHECKS.md` P3-1 procedure; the README's seed section (the app now has a launcher entry).

### PR 9. `phase-3/strings-pipeline` (Sonnet)

**Goal.** The export and import tools, the rules check, and the wave 1 export handed to the translator.

**Scope.** `scripts/strings/{ExportStrings,ImportStrings}.java` (single file Java programs, as `GenerateSounds.java` is); a `health_adjacent` and `note` attribute convention as XML comments above each string; `CopyRulesTest`; `NoFixtureInResourcesTest`; `docs/translation/README.md` (how he works in the file) and `docs/translation/wave-1.csv` generated from the 74 strings and 11 plurals Phase 2 shipped, with every health adjacent one marked.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `StringsRoundTripTest` | Export `values/strings.xml`; fill hi and mr from a test table; import | The generated `values-hi` holds every key, plurals with the locale's quantities, placeholders equal to the English | Drop plural quantities on export |
| `import refuses a changed placeholder and a missing quantity` | A row with `%2$d` removed; a plural without `other` | A failure naming the key | Skip the check |
| `CopyRulesTest` | Every string in every locale | No banned phrase or pattern (ADR 0076 item 5); the test's own list is asserted non empty | Add "Great job" to a string |
| `NoFixtureInResourcesTest` (moved here from PR 7 if PR 7 has not already added it; the check is the same) | ADR 0077 item 4 | As stated, failing closed | Put a fixture string in `values-hi` (in a mutation only); move the fixture folder to the main source set |

**Docs.** `IMPLEMENTATION_PLAN.md` (wave 1 exported, date); section 7 below.

### PR 10. `phase-3/onboarding` (Sonnet)

**Goal.** The six steps of ADR 0087 as design spec screens 1 to 6; the three Phase 2 Activities deleted; their assertions kept.

**Scope.** `ui/onboarding/*`; models over `PregnancyRepository`, `CreateTemplateCommand` with the edit pass, `SetupHost`, `SamsungStep`, `ReliabilityHost`; `onboarding_complete`; the gate; deletion of `SetupActivity`, `SamsungStepsActivity`, `ReliabilityCheckActivity`, their layouts and manifest entries; the tests of those three screens moved to the Compose screens with every assertion kept (the PR description maps each old test name to its new one, and any that has no new home is a STOP). **Before this PR ships:** the four A15 screenshots (section 7).

**The three criticality lines on step 3 are health adjacent, are marked so for wave 2, and say what the build does once PR 2 and PR 2b are merged** (Claude (technical review), third review; checked by the planning session against `EscalationLadder.forOccurrence`, `EscalationPolicy.resolveDelivery`, `DeliveryPath.choose`, `RingerService`, `RingController.stopSound` and the dismissal path of ADR 0090, `NotificationChannels` and `VibrationPattern.defaultFor`):

- Critical: "Rings, and rings again 5 minutes later if you have not answered. Quiet hours and the daily ring limit do not hold it back."
- Standard: "Rings, and rings again 10 minutes later if you have not answered. In quiet hours, or past the daily ring limit, it arrives as a silent notification instead."
- Gentle: "A quiet notification. It does not ring."

The minutes are not typed into the strings: each of the first two is a `plurals` resource whose number is the gap between the first and second device rung of `EscalationLadder` for that criticality, so the Hindi and Marathi forms agree with the number and a change to a ladder changes the line. The wording was checked against the build as it will be and none was changed: "rings again" is true because the cap (4 minutes) ends the first ring before the repeat (5 and 10 minutes), and the repeat rings whether the first ring was left to the cap or stopped with "Stop the sound"; "if you have not answered" is true because Taken and Skip end the ladder and Snooze replaces it with the snooze's end. One limit is not said in the Standard line and is left to its second sentence: the repeat is a ring of its own (ADR 0090 item 6), so quiet hours beginning, or the limit being reached, between the two silences the repeat.

Each line is true only where reminders ring at all; on a phone at Tier 1 the permissions step's sentence says reminders arrive as notifications and may be late. `CriticalityCopyTest` ties the lines to the build so they cannot drift: for each criticality it drives real fires on an exact tier and asserts what the line says. Critical and Standard: the ringer starts; with no action the sound has stopped before the repeat's time; at the gap the ladder gives, a second ringer start; the number in the string, read from the resource with that gap, is the ladder's; Critical rings inside quiet hours and with the budget spent, Standard is a silent notification in each. Gentle: one notification on the Gentle channel and no ringer start, and no second rung. The test names the string resource each case vouches for. Mutations: change Critical's repeat to 7 minutes in `EscalationLadder` with the string's number typed as 5 (the number assertion fails, which is why it is not typed); remove the cap (the sound is still playing at the repeat, so the repeat is not a second start); exempt Standard from quiet hours; return `RING` for Gentle.

**`onboarding_complete` and a restored phone** (ADR 0087 item 10). The flag is in `momtime_android_settings`, which is backed up. A phone restored from backup therefore opens Today, not onboarding, with her reminders restored and none of her grants: `RestoredPhoneTest`, in PR 13 with the banners, shows what she sees.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `OnboardingFlowTest` `a fresh install opens step 1 and finishing opens Today` | No flag | Step 1; after the last step the flag is set and Today is shown; a relaunch opens Today | Never set the flag |
| `the due date step writes one pregnancy and one revision` | Pick a date | Exactly those rows | Write no revision |
| `the first reminder goes through the command and is armed` (SDK 29, 33, 36) | Title, 08:00, Critical | One template, `MissionConfig.None`, materialised, and `ShadowAlarmManager` holds its alarm | Insert through the repository without the pass |
| `the permissions step resolves again on every resume` | Resume twice with the capability changed between | `SetupHost.onReturn` called each time; the rows follow | Call it in `onCreate` only |
| `the gate` (the 64 combinations of `DeliveryTable`) | Each capability combination | Continue is disabled and the blocking banner shown exactly when notifications are denied and exact capability is absent; enabled otherwise | Gate on any missing input; never gate |
| `the copy obligations are on screen` | Steps 1 and 4, tier 1 and tier 3 | The four strings of ADR 0087 item 3, each by resource id, each present exactly where required and the Tier 1 sentence absent at tier 3 | Remove each string from its screen |
| `the Samsung step appears only on a Samsung` | Manufacturer seam both ways | Present, absent | Ignore the seam |
| `the check step settles an overdue check when it opens and uses the one runner` | A pending check past its timeout | `settleOverdue` ran; one `CANARY_RESULT`; starting again arms request code 0 | Build a second runner |
| The ported assertions of `SetupActivityTest`, `SamsungStepsTest`, `ReliabilityCheckActivityTest` | As in Phase 2 | As in Phase 2 | Phase 2's mutations Q and P rows, re run |
| `ScreenshotTest` for screens 1 to 6 | The matrix, every listed state | Goldens; `NoClipTest` | A fixed width on the criticality chips |

**Every passage that names one of the three deleted Activities is updated in this pull request.** In `docs/MANUAL_CHECKS.md`: the "Informal now: seed build" paragraph under "Phase 2 device checks outstanding" (it tells the reader to reach the permission and check screens from the ring screen's idle state through "Set up and check my reminders"; they are now reached from the launcher, through onboarding or Settings); P2-32 (names `ReliabilityCheckActivityTest`; the export is now on "How reminders are arriving"); P2-38 (names `SetupActivityTest`); P2-39 (names `SetupActivityTest`); and the procedures of P2-31, P2-33 to P2-37 and P2-40, P2-41 wherever they start from the ring screen's idle link or the check screen. In `README.md`: the paragraph of "The seed screen" that says the app has no launcher entry of its own and that the permission and check screens are reached from the ring screen. In `ARCHITECTURE.md`: section 5.2 ("Until Phase 3's onboarding exists, the ring screen's idle state links to the permission screen"), section 5.7 and section 5.10 (the check "from the ring screen's idle state", `ReliabilityCheckActivity`). In source comments: `RingActivity`, `CanaryRunner`, `SetupController`, `AndroidSettings`. In the manifest: the three entries and their comment. The pull request description lists each with its line before and after; a grep for the three class names and for "Set up and check my reminders" over the repo, excluding ADRs (which are immutable) and the traceability file of Phase 2 (which records history), must come back empty, and the description shows the grep.

**Imports.** As PR 7; nothing new.

**STOP points.** A Phase 2 assertion with no equivalent. The placeholder Samsung drawables still in place at the end of the PR (ship is blocked; say so).

**Docs.** `ARCHITECTURE.md` sections 5.2, 5.10; `MANUAL_CHECKS.md` P2-34 to P2-38 procedures now start from onboarding; README.

### PR 11. `phase-3/today-and-reminders` (Sonnet)

**Goal.** Design spec screens 7 to 10: Today with its actions and late marking, the starter schedule, the template list, the editor.

**Scope.** `TodayModel` (ADR 0087 items 5 to 7), including the group "Missed yesterday"; `RemindersModel`, `EditorModel` over the commands and the edit pass; the editor's vibration choice, shown only while the chosen criticality is Critical or Standard (ADR 0089 item 7), writes `AndroidSettings.setVibration`; in `shared`: `BackfillCommand` (ADR 0087 item 7), the snooze rule in `OccurrenceActions.available` (it gains the occurrence's `scheduledInstant`; ADR 0087 item 6, accepted by Claude (technical review)), and `EditTemplateCommand.preview(edited, now)`, which returns what the edit would withdraw, which occurrences would keep today's time, and the local date of the first occurrence at the new time (the first date after today that the edited recurrence wants, from `RecurrenceExpander`, whether or not it is materialised yet), pure over the same rules as `dispatch`.

**"Missed yesterday".** Under Today's list, a group holding the occurrences whose `localDate` is yesterday in the current zone and whose state is `MISSED` with no `COMPLETED_BACKFILLED` event, each with "Taken late". It is absent when empty. It is what makes late marking reachable for a Gentle reminder, which becomes missed at midnight and so never shows as missed on its own day, and for a late Standard one whose grace crosses midnight. Misses older than yesterday cannot be corrected in Phase 3: history is not built.

**Today's actions, stated once.** A To do row, ringing or not, offers acknowledge, snooze and skip, each only when `OccurrenceActionCommand.available` offers it, and each goes through `RingController.act`, which dispatches `OccurrenceActionCommand` inside the coordinator's exclusion and calls `ensureArmed`. A Missed row offers "Taken late", which dispatches `BackfillCommand` and nothing else. No row writes an event itself.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `BackfillCommandTest` `it writes one COMPLETED_BACKFILLED and changes no state` (shared) | A `MISSED` occurrence of a template with two tags; dispatch | Exactly one new event: `COMPLETED_BACKFILLED`, source `USER`, the occurrence's id, the two tags; the occurrence row is byte equal, state `MISSED`; adherence as of before the dispatch counts it missed, as of after counts it completed, on the scheduled date; nutrition counts its tags after | Write a `COMPLETED` event instead (the event type assertion fails); pass no tags (the tags assertion fails) |
| `it is allowed once` | Dispatch twice | The second returns `NotAvailable` and writes nothing; one backfill event exists | Remove the existing event check |
| `it is refused on every state but MISSED` | An occurrence in each of the other five states, `WITHDRAWN` among them | `NotAvailable`, nothing written | Allow terminal states generally |
| `the tags are the template's when the backfill is entered` | Miss; edit the template's tags; backfill | The event carries the new tags | Read tags from the occurrence's last event |
| `OccurrenceActionsTest` `snooze is not offered before the occurrence's time` (shared) | A `PENDING` occurrence one hour ahead, at its instant, and one minute past | Acknowledge and skip in all three; snooze only in the last two; a snooze dispatched before the time returns `NotAvailable` and writes nothing | Drop the comparison with `scheduledInstant` |
| `TodayModelTest` `four states over the log` | One occurrence in each of the six states, plus a missed one backfilled through `BackfillCommand` | Done for completed; Done with the caption "Taken late" for the backfilled one; Skipped; Missed; To do for pending and for snoozed with its end time; the withdrawn one absent | Map from the `state` column (the backfilled row shows Missed) |
| `rows are today's in the current zone, in order` | Occurrences either side of midnight in two zones | The right set and order | Use UTC dates |
| `an open row that is not ringing offers what the domain offers, through the ring controller` (SDK 29, 33, 36) | Three To do rows: one an hour ahead, one due and not ringing (Tier 1, a plain notification posted), one snoozed three times | Row 1 shows Taken and Skip; row 2 Taken, Snooze, Skip; row 3 Taken and Skip. Tapping each of row 2's in turn (on fresh fixtures) writes exactly ADR 0066's event and state through `OccurrenceActionCommand`, cancels the row's notification, and leaves `ShadowAlarmManager` holding the alarm `ensureArmed` selects (for snooze, the snooze's end) | Dispatch the command from the model without the controller: the notification is still posted and the alarm is stale. Offer snooze from the state alone: row 1 shows it |
| `acting on a ringing row from Today resolves the ring` | Tier 3, one occurrence ringing; tap Taken on Today | The delta of ADR 0066, the session ended, the ringer stopped, the notification cancelled | Call the command directly (the ringer keeps running) |
| `Taken late shows only on a missed row and only once` | Rows of every state; tap it on the missed one | The control exists on the missed row alone; after the tap the row is Done with "Taken late", the control is gone, the three figures on Overview's model move one from missed to done, and one `COMPLETED_BACKFILLED` exists | Show it on skipped rows; keep it after a backfill |
| `a Gentle reminder missed at midnight appears under Missed yesterday and is backfilled once` | A GENTLE occurrence at 21:00 yesterday, unanswered; the clock at 00:10 today; `ReconcileCommand` dispatched (it is `MISSED` with `effectiveAt` midnight) | Today's own list does not contain it; the group "Missed yesterday" contains exactly it, with "Taken late"; tapping writes one `COMPLETED_BACKFILLED` with the template's tags and the occurrence leaves the group; yesterday's adherence as of now is completed 1, missed 0; the group is gone | Build the group from today's date (it is empty); keep a backfilled occurrence in the group (a second tap is possible and is refused, and the row is still shown) |
| `a Standard reminder whose grace crossed midnight appears there too` | STANDARD at 22:00 yesterday; the clock at 02:30 today, after its four hour grace; reconcile | It is in the group | Select by the date of `effectiveAt` instead of the occurrence's `localDate` (it is treated as today's and is absent from the group) |
| `an occurrence missed two days ago does not appear` | `MISSED` occurrences dated yesterday and the day before, and a `SKIPPED`, a `WITHDRAWN` and an already backfilled one dated yesterday | The group holds only yesterday's unbackfilled `MISSED` one | Select every `MISSED` occurrence with no backfill |
| `yesterday follows the current zone` | A missed occurrence whose `localDate` is yesterday in Asia/Kolkata; the model built with the device zone America/New_York at an instant where that date is today there | It is not in the group there, and is in Today's list as Missed with "Taken late" | Compute yesterday in UTC |
| `state is never colour alone` | The four markers composed in every appearance | Each has a distinct content description and a distinct shape tag; each is drawn through one `RolePair` | Give the Done marker its own pair outside the declared list (also fails `ContrastTest`'s completeness check) |
| `the list follows a change made elsewhere` | Complete an occurrence through the notification receiver while Today is shown | The row becomes Done without a resume | Unsubscribe from `DataChanges` |
| `a read that meets corruption ends the process and posts nothing` | The corruption seam of `DatabaseCorruptionTest` under `TodayModel` | `ProcessEnd` called once; no state posted after | Catch the exception in the model |
| `EditorModelTest` `save creates or edits through the commands and runs the pass` | Create; then edit the time | One template; the alarm moved | Write through the repository |
| `the editor never writes a mission and shows no mission control` | Save every form state the generator produces | `MissionConfig.None` always; no node tagged mission | Add a mission chip |
| `the vibration choice is offered only for Critical and Standard, and a stored one survives Gentle` | A reminder with `URGENT` stored; open the editor; choose Gentle; save; reopen; choose Standard | The control is present for Critical and Standard and absent for Gentle; saving as Gentle does not remove the stored pattern; back on Standard the control shows `URGENT` | Show the control always; clear the stored pattern on saving Gentle |
| `doctor instructions and dosage are stored and shown verbatim` | Mixed script text with digits | Byte equal in the row, on Today and on the ring screen | Trim or normalise |
| `the confirm dialog lists exactly what will be withdrawn` | Stop a reminder with two open occurrences; change weekdays | The dialog's rows equal `preview`; cancel writes nothing | Show the dialog after dispatch |
| `an edit to a time already past today says so, and names the date the new time starts` | Three cases, each at 10:00 with today's reminder at 12:00 edited to 09:00: a Daily template; a Weekly template whose next wanted date is three days out; an EveryNDays template with n 5 | After saving, one line is shown, built from `preview` and one string resource with two placeholders: today's reminder stays at 12:00, and the new time starts on the date of the first occurrence at the new time, formatted by the platform's date formatter in the current locale. The date is tomorrow's for Daily, the date three days out for Weekly, and the anchored date for EveryNDays; the word "tomorrow" is in no string. With an edit to 15:00 the line is absent | Always format today plus one day (the Weekly and EveryNDays cases fail); show the line always |
| `preview and dispatch agree` (shared, property, PR 3's generator) | Random edits | The occurrences `preview` names are exactly those `dispatch` then withdraws or leaves at their time | Compute preview with a different `now` |
| `the editor's draft survives process death` (SDK 29, 36) | Open the editor on an existing reminder; change the title, the time, two tags and the instructions; open the weekday choice; save the instance state, destroy the Activity, build a new one from the saved state | The editor is on top for the same template with every field as typed and the weekday choice open; the database is unchanged; saving then writes the edit once | Hold the draft in `remember`; write the draft to the database as she types |
| `StarterScheduleTest` `it names nothing and needs a title for every row` | The screen | The three title fields are empty and have no default text; the resources hold no starter title; the button is disabled until titled; saving creates STANDARD Daily templates at the three times with her titles | Prefill a title |
| `ScreenshotTest` for screens 7 to 10 | The matrix, every listed state | Goldens; `NoClipTest` | A fixed height row |

**Imports.** As PR 7; nothing new.

**STOP points.** Any wish for a field or control the design spec does not list. A Phase 2 test of `OccurrenceActions.available` whose expectation, not its fixture, must change for the snooze rule.

**Docs.** `ARCHITECTURE.md` sections 4.5 (the snooze rule; late marking built) and 4.6, and the UI section; `IMPLEMENTATION_PLAN.md` deliverables status; traceability L3, L19, L20.

### PR 12. `phase-3/water-nutrition-overview` (Sonnet)

**Goal.** Design spec screens 11 to 13.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `WaterModelTest` `a tap logs one glass through the command` | Tap twice | Two `WATER_LOGGED` of 250 ml with the device zone; the figures read 500 | Write the event in the model |
| `today is the current date in the current zone` | The travel fixture of PR 5 | Today's figure is the reduction's value for that date | Sum the last 24 hours |
| `reaching the goal changes only the numbers` | At and above the goal | No node, colour or string appears that is absent below it, other than the figures | Add a "goal reached" line |
| `the goal and nudges are saved, nudges capped at 4` | Set 2000 and 9 | `WaterGoal(2000, 4)` | Remove the cap |
| `NutritionModelTest` `counts per day and per week, nothing else` | The reduction's fixture | Rows equal the reduction; no percent sign, no target, no bar; a tag with no count has no row | Show a fraction |
| `OverviewModelTest` `three figures, never one` | A day with 2 done, 1 missed, 1 skipped | Three cells 2, 1, 1 in that order; no node holds a percentage or a sum | Add `completed / total` |
| `the 30 day figure is the reduction's` | A fixture | Equal to `criticalCompletionDays` with `asOf` now | Count consecutive days |
| `the week card` | Prenatal in range; out of range; postpartum; a due date revised yesterday | Week shown; due date only; due date only; today's week from the latest revision | Show a negative countdown as a week |
| `ScreenshotTest` for screens 11 to 13 | The matrix | Goldens; `NoClipTest` | A fixed width figure cell |

**Imports.** As PR 7; nothing new.

### PR 13. `phase-3/settings-and-reliability` (Sonnet)

**Goal.** Design spec screens 14 and 15, and the banners on Today.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `SettingsModelTest`, one case per row | Change quiet hours (and clear them), the ring limit, the snooze length, the louder sound interval (and off), the language, the opt in | Each is written to its one home: the first three to `AppSettingsRepository`, the rest to `AndroidSettings`; equal start and end quiet hours are refused on screen | Write the opt in to the shared column |
| `a setting changes behaviour, not only storage` | Snooze length 5 then snooze; quiet hours covering now then a STANDARD fire | `snoozedUntil` is five minutes on; the fire is silent | Cache settings in the model |
| `the Appearance row opens the same sheet` | Tap | The sheet's tag, the same composable function (asserted by a shared test tag and by `ScreenRegistry` holding no themes screen) | Add a themes screen |
| `BannerOrderTest` | Every subset of banner states, generated | The first shown is the highest in ADR 0087's order; the reliability view shows all | Reverse two priorities |
| `every missing grant is named on Today` | Each subset of the five inputs of `MissingInput` missing, generated | The below Tier 3 banner on Today names every missing input of the subset, each by its own string, and none that is granted; its action opens the permissions screen | Name only the first missing input |
| `RestoredPhoneTest` `a restored phone opens Today and is told what is missing` (SDK 31, 33, 34, 36) | The state a restore leaves: `momtime_android_settings` with `onboarding_complete` true and her appearance; the shared database with a pregnancy, two active templates and open occurrences; an empty android store; and the platform's grants as a fresh install has them at that API level (notifications denied on 33 and above, full screen intent denied on 34 and above, no battery exemption, exact alarms as the level gives them). Launch `MainActivity` under the real `MomTimeApplication` | Onboarding is not shown; Today lists her occurrences; the banner names each grant that is missing at that level, and on a configuration with notifications denied and no exact capability the blocking banner covers Today instead; `AppStart` has run, so exactly one alarm is armed for the earliest open rung through whatever tier the grants allow; the permissions screen, opened from the banner, shows the same items as needed | Show onboarding when the android store is empty (her data would be asked for again); suppress banners when `onboarding_complete` is true; skip `AppStart` |
| `a banner's action opens its fix step` | Each `FixStep`, Samsung and not | Samsung steps; permissions at the battery row; the unused app flow | Open Settings for all |
| `the blocking banner cannot be dismissed and covers Today` | The blocked capability | No dismiss control; Today's list is not reachable by semantics | Add a close button |
| `the reliability view reads the report and the store failing soft is shown` | `ReliabilityReader` over a store whose seam fails | The view renders; the store trouble banner is present; nothing throws | Let the failure propagate |
| `export writes what Phase 2's export writes` | The document picker result | Bytes equal to `ReliabilityExport` | Build the JSON in the screen |
| `ScreenshotTest` for screens 14 and 15, and Today with each banner | The matrix | Goldens; `NoClipTest` | A one line banner title |

**STOP points.** A setting whose effect cannot be asserted through behaviour.

**Imports.** As PR 7; nothing new.

### PR 14. `phase-3/widget-tile-nudges` (Sonnet)

**Goal.** ADR 0083 items 2 to 5 and design spec screen 18.

**Scope.** `widget/{WaterWidgetProvider,WaterLogReceiver}`, `tile/WaterTileService`, `work/WaterNudgeWorker`, `shared` `WaterNudgePolicy`, the `momtime.water` channel, the allowlist entries for the provider and the tile.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `WaterWidgetTest` `a tap logs one glass through the command` (SDK 29, 31, 36) | Send the widget's `PendingIntent` | One `WATER_LOGGED` by `LogWaterCommand`; the widget's text updated; the intent is explicit, immutable and its receiver unexported | Write the event in the receiver; export the receiver (also fails `verifyExportedComponents`) |
| `the widget follows the appearance` | Each appearance and palette | The `RemoteViews` applied to a view have the roles' colours; for System on API 31 both the day and the night colour are set | Hard code Light |
| `WaterTileTest` | `onClick` | One event by the command; the subtitle is today's total | Log twice |
| `the tile is guarded` | The merged manifest | The service requires `BIND_QUICK_SETTINGS_TILE` | Remove the permission (also fails the allowlist) |
| `WaterNudgePolicyTest` (shared) | A table: times through a day for 0 to 4 nudges, below and at the goal, inside and outside quiet hours, after a long sleep | At most one per run; none at or above the goal, in quiet hours or with 0 configured; never more than the configured number in a day, and never more than 4 | Post for every missed slot; ignore the goal |
| `WaterNudgeTest` `a nudge never arms an alarm and never uses a reminder channel` (SDK 29, 33, 36) | Run the worker when a nudge is due, with a reminder armed | `ShadowAlarmManager`'s alarms are exactly what they were; the notification is on `momtime.water`, importance low; no event is written; the budget is unspent | Post on Gentle; call `AlarmManager` |
| `LocaleSurfacesTest` additions (SDK 29, 36) | The override on Hindi, the device on English, fixture Hindi | The widget's `RemoteViews`, applied to a host view, show the fixture Hindi text; the tile's label and subtitle after `onStartListening` are the fixture Hindi; the nudge notification's title and action are; the water channel's name is | Read each from the plain context in turn |
| `the nudge state is not backed up` | The rules files | The nudge file is in neither | Add it to the rules |
| `ScreenshotTest` for screen 18 | The widget's layout applied to a host view, the matrix | Goldens | |

**Docs.** `ARCHITECTURE.md` sections 3.6, 5.6; `MANUAL_CHECKS.md` P3-6 and P3-8 (a nudge's timing on a device, the widget on a launcher, the tile).

**Imports.** `androidx.work.*` from `work-runtime`, as today; the widget, the tile and the receiver use framework classes only (`android.appwidget`, `android.widget.RemoteViews`, `android.service.quicksettings`). Nothing new.

### PR 15. `phase-3/accessibility-and-freeze` (Sonnet)

**Goal.** The accessibility pass, then the wave 2 string freeze.

**Scope.** `AccessibilityTest` over `ScreenRegistry`: every clickable node has a content description or text, a touch target of at least 48 dp and at least 8 dp from its neighbours; every image has a description or is marked decorative; every screen at 200 percent passes `NoClipTest`; focus order follows reading order on the editor. Then `docs/translation/wave-2.csv`, and a `StringFreezeTest` that fails if `values/strings.xml` differs from the frozen export's English (so a later English change is a deliberate re export).

| Mutation | Must fail |
|---|---|
| Remove a content description from the add action | `AccessibilityTest` on Today |
| Shrink the appearance control to 32 dp | The target size case |
| Change one English string after the freeze | `StringFreezeTest` |

### PR 16. `phase-3/strings-import` (Sonnet; gated on the translator)

**Goal.** `values-hi` and `values-mr` from his file; goldens recorded again for hi and mr from the real resources; the human review recorded.

**Tests.** Import through `ImportStrings`; `CopyRulesTest` and `StringsRoundTripTest` over the real files; `MissingTranslationTest` (every key in all three locales, every plural with the locale's quantities); the full matrix with `NoClipTest`. Mutation: delete one hi string, `MissingTranslationTest` fails. **STOP** if any string clips at 200 percent: the fix is a layout change or his shorter wording, never a truncation.

**Docs.** `MANUAL_CHECKS.md`: the review, by whom, when, at which commit, and which strings are health adjacent.

### PR 17. `phase-3/close` (Sonnet)

**Goal.** The exit criteria reported with evidence; the traceability file complete; `IMPLEMENTATION_PLAN.md` Phase 3 status and deliverables as built; `ARCHITECTURE.md` and `README.md` read for consistency against ADRs 0073 to 0088 as accepted; the Open items rows for the Play account and testers stated as Shubham's. No code except what the consistency read forces.

---

## 6. Every deliverable, exit criterion and owed item, mapped

| Item (source) | PR |
|---|---|
| Onboarding: due date, one medicine, permission walkthrough, the check; inherits `PermissionFlows`, `SetupController`, the Samsung walkthrough, `CanaryRunner`; the four copy obligations; the gate (deliverable) | 10 |
| Starter schedule as an editable suggestion (deliverable, D6) | 11 |
| Today view with four state display (deliverable) | 11 |
| Today offers acknowledge, snooze and skip on an open occurrence, ringing or not, through `OccurrenceActionCommand` (Claude (technical review), review of the first draft) | 11 |
| "Missed yesterday" on Today, so that late marking is reachable for a Gentle reminder and for one whose grace crossed midnight (Claude (technical review), second review) | 11 |
| A restored phone opens Today and is told every missing grant (ADR 0087 item 10) | 13 |
| The onboarding criticality lines checked against the build | 10 |
| A Gentle reminder is a notification only (ADR 0089; Claude (technical review), third review) | 2, with the edit case in 4 and the editor's control in 11 |
| A ring has a maximum length; the end of grace ends a ring; the evidence (ADR 0090; Claude (technical review), third review) | 2b, with its paths in 6 |
| Late marking: "Taken late" on a missed occurrence, `COMPLETED_BACKFILLED` through `BackfillCommand`, once per occurrence, never on a withdrawn one, with nutrition tags (`ARCHITECTURE.md` section 4.5; Claude (technical review)) | 11 |
| Schedule builder: create and edit, criticality, recurrence, tags, dosage, doctor instructions, inventory and refill threshold; no mission (deliverable, D7) | 3, 4, 11 |
| Water: one tap logging, goal, progress, optional nudges (deliverable, D8) | 5, 12, 14 |
| Nutrition aggregation view (deliverable) | 5, 12 |
| Dashboard: three figures, water, gestational week, days to due date, the 30 day figure (deliverable) | 5, 12 |
| Settings: quiet hours, budget, snooze, channels, locale override, opt in, backup sound interval, vibration patterns (deliverable) | 8 (locale mechanism), 11 (vibration, per template), 13 |
| Notification UX for several occurrences due at once (deliverable; owed from PR #20's review) | 6 |
| Reliability view and the fix path banner UI over `Banners` and `ReliabilityReport` (deliverable; owed) | 13 |
| The app's own home: launcher activity; the ring screen's idle link replaced (deliverable; owed) | 8, 10 |
| The debug seed under the deactivation decision (deliverable note) | 3, 4 |
| Roborazzi coverage of Phase 2's screens: permission, Samsung, check (deliverable; owed); the ring screen | 10, 8 |
| Hindi and Marathi for everything Phase 2 added, with human review (deliverable; owed, D9) | 9, 16 |
| Dark mode (deliverable; D2) | 7, 8 |
| Widget and quick settings tile for water (deliverable) | 14 |
| Full localisation: three locales, bundled Noto, per locale line heights, plurals, no concatenation (deliverable) | 7, 9, 16 |
| Roborazzi screenshot tests: every screen, three locales, three scales (deliverable, corrected by D5) | 7 to 14, 16 |
| Accessibility pass (deliverable) | 15 |
| Exit criterion: golden scenario 1, real test | 3 |
| Exit criterion: scenario 12's real interleaved edit | 3 |
| Exit criterion: what deactivation does to open occurrences, decided and built (owed) | 3, 4 (ADR 0079) |
| Exit criterion: screenshot suite green across the nine combinations, no clipping | 16, reported in 17 |
| Exit criterion: human review of health adjacent Hindi and Marathi copy | 16 |
| Exit criterion: nutrition tag aggregation and water totals as pure reductions, mutation checked (owed from Phase 1) | 5 |
| D2 appearance setting, storage, before first frame | 7, 8 |
| D2b the switcher on every top level and onboarding screen; persistence; the ring screen; the sheet in the matrix | 8 (and 10 for the onboarding screens, which `SwitcherPresenceTest` covers as they are registered) |
| D2c the visual spec | this pull request |
| D3 contrast as a test, unrounded; screens can only draw declared pairs (`verifyColourBoundary`, pair components) | 7 |
| The locale override reaches every string the app produces | 8, 14 |
| State survives rotation, a locale change, an appearance change and process death; predictive back | 8, 11 |
| The starting window follows her choice on API 33 and above (`setSplashScreenTheme`) | 7, 8 |
| Goldens recorded on Linux through the `android-screenshots` job | 7 |
| Every passage naming a deleted Phase 2 Activity updated | 10 |
| D4 copy rules | 9 |
| D10 exported components allowlist | 8, 14 |
| Start the Play 12 tester clock | Shubham, at the merge of 17 (section 7) |

Nothing in Phase 3's section of `IMPLEMENTATION_PLAN.md` is unmapped. Each row is a row of `docs/phase-3-traceability.md`.

---

## 7. What Shubham owes, and when

| What | Needed by | Note |
|---|---|---|
| Merge of this pull request | Before PR 1 | The dependency list with its eight sub modules, the fonts and the icons are approved (section 4) |
| Nothing further before PR 1: section 8 is resolved | | |
| `android-screenshots` added to the required checks on `main` | When PR 7 merges | The new Open items row |
| The four Galaxy A15 screenshots in `drawable-nodpi`, at most 1080 px wide and 400 KB each | Before PR 10 ships | The Open items row |
| His brother: wave 1 translation (85 Phase 2 strings and plurals) | Handed over when PR 9 merges; wanted back before PR 16 | Early, on purpose: the health adjacent strings of the ring screen, the notices and the permissions are all in wave 1 |
| His brother: wave 2 translation and the review of all health adjacent copy | Handed over when PR 15 merges; PR 16 is gated on it | The exit criterion is his review, recorded |
| The device rows P3-1 to P3-8, informally with a debug build | Any time after PR 14; formally in Phase 7 | |
| The Play developer account, Play App Signing, the upload key backed up outside the repo, and 12 testers opted in | At the end of Phase 3, so that the 14 days run alongside Phase 4 | Unchanged from the plan |

---

## 8. Open questions for review

**None is open.** Resolved by Claude (technical review):

- In the second review: the eight sub modules are declared by name (C13); snooze is not offered before a reminder's time (C12); an edit to a time already past today leaves today's reminder at its old time, and the editor's line names the date the new time starts; `WITHDRAWN` is on Phase 4's carried list and the missions rule on Phase 5's.
- In the third review: a Gentle reminder does not ring; `ARCHITECTURE.md` section 4.2 was right and the build wrong (C14, ADR 0089, PR 2). A ring has a maximum length, and the end of grace ends a ring (C15, ADR 0090, PR 2b).

**Accepted by Claude (technical review) in the fourth review**, each as the planning session had made it: the test of an edit from Gentle to Critical is in PR 4, and PR 2 keeps `the decision reads the occurrence, not the template`; "Stop the sound" and the cap share one dismissal that keeps her notification and its actions (a correction of Phase 2, ADR 0090 item 3); an unanswered Standard reminder costs two rings (ADR 0090 item 6); a ring that ran out is recorded in the android store, version 6 (ADR 0090 item 8). The same review settled that "ringing", for a continuation, means the session's sound is on, so the Phase 2 continuation test changes its fixture and not its expectations.

**One view was asked for and given, with nothing changed:** the default daily ring limit stays 12 (ADR 0090 item 9). The decision is Claude (technical review)'s.

**Phase 2 tests whose expectations Phase 3 changes:** four. The three of PR 1 (the none payload round trip on `SKIPPED`, and the acknowledgement's payload `Completion` in `OccurrenceActionsTest` and `OccurrenceActionCommandTest`; authorised by Claude (technical review) before PR 1 was coded, listed under PR 1's "Phase 2 expectations"), and `DeliveryTest` `each criticality rings on its own channel`, in PR 2, for Gentle only (authorised by Claude (technical review), ADR 0089 item 9). PR 2b changes none.
