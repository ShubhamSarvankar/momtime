# Phase 3 plan: Android UI and localisation

Status: draft, for review. Written by the Phase 3 planning session; decisions marked D1 to D10 are Claude (technical review)'s, from the planning prompt, and decisions a to i are the planning session's, for review. No production code and no dependency entered the build in this pull request. `CLAUDE.md` wins over this file, then `ARCHITECTURE.md`, then `IMPLEMENTATION_PLAN.md`.

The visual specification is `docs/phase-3-design.md`. The evidence table is `docs/phase-3-traceability.md`.

---

## 1. Repo state, and where the prompt and the repo disagree

**State.** PR #25 is merged; `main` is at `84a4c2a`; all 10 CI jobs of run 37338212323 on that commit succeeded (read through the GitHub MCP). This plan was branched from it as `docs/phase-3-plan`.

**Disagreements and findings.** None changed a decision of D1 to D10. Each is followed here as stated.

| # | Finding | What the plan does |
|---|---|---|
| C1 | **The mockup's path in the prompt was a placeholder, so the mockup was not read.** | The design spec is written from the prompt's written description. The lavender palette is the session's own choice and is marked provisional (ADR 0075). Shubham supplies the path or corrects the palette in review. Nothing else depends on the image. |
| C2 | The newest Compose (BOM 2026.09.00, Compose 1.12.1) needs compileSdk 37; the project is on 36. | Compose is pinned to BOM 2026.06.01 (1.11.4), which builds on 36 (ADR 0073). A spike failure that became a pin, not a change of decision. |
| C3 | "No receiver is exported" is not true of the merged manifest today: WorkManager's `SystemJobService` and `DiagnosticsReceiver` are exported, each behind a system permission, and Compose adds `ProfileInstallReceiver`. | The allowlist of D10 has six entries, not three (ADR 0078). |
| C4 | The schedule builder's deliverable line lists "mission config"; D7 says no mission control. | The line is corrected in `IMPLEMENTATION_PLAN.md` (ADR 0088). |
| C5 | Criticality has the problem the prompt names for nutrition tags: it is read live from the template, so an edit would rewrite `criticalCompletionDays` for days already lived, change a ladder in mid flight and could end a grace in the past. | Criticality is kept on the occurrence (ADR 0079). This is the largest consequence of decision a. |
| C6 | The shared schema has `app_settings.locale_override` and `telemetry_opt_in`; the live opt in is android's since Phase 2, and the override must be readable before the first frame. | Both shared columns stay unused and are recorded (ADR 0082). No migration. |
| C7 | The plan says the template edit path calls `Work.enqueueMaterialisation`. | The edit runs its pass inline inside the coordinator's exclusion, as the zone change does, so the alarm is right when the screen returns; the function stays for other callers (ADR 0079 item 8). |
| C8 | The prompt speaks of five occurrence states. | The model adds a sixth, `WITHDRAWN` (ADR 0079). The Today view still shows four (ADR 0087). |
| C9 | The roborazzi plugin and version are in the catalog and the README already names the record and verify tasks, but nothing applies the plugin. | PR 7 applies it. |
| C10 | "It applies before the first frame of every Activity ... with no flash" cannot cover the system's starting window, which is drawn from the manifest theme before app code. | Met for every frame the app draws; the limit is stated (ADR 0074 item 6) and is a device row. |
| C11 | Phase 3's deliverables do not name marking a missed dose as taken late, though `COMPLETED_BACKFILLED` exists and the Today view makes the gap visible. | Not built. Raised in section 8 for a decision. |

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

**Not spiked, and so a STOP in PR 7:** whether a golden recorded on Windows verifies on Linux CI. The plan assumes it does not and records goldens on Linux only (ADR 0077).

**Timing.** Warm captures took about half a second each for a small screen. The matrix is 44 screen states (design spec section 6): 44 by 9 in Light is 396, and 44 by 6 other appearances at English 100 percent is 264, **660 captures**. At half a second to one second each that is 6 to 11 minutes on one SDK level (36), so screenshots run as their own CI job, `android-screenshots`, which Shubham adds to the required checks when PR 7 merges. Goldens are PNG files in the repo, estimated at 20 to 40 MB in total; PR 7 measures and reports the real figure, and a figure above 60 MB is a STOP.

---

## 3. Decisions

| | Decision | ADR |
|---|---|---|
| D1 | Compose for new screens; the ring screen stays views; the permission, Samsung and check screens are ported | 0073 |
| D2, D2b | One appearance setting, four values, five Pastel palettes, one switcher sheet, stored in `momtime_android_settings` | 0074 |
| D2c | The visual spec | `phase-3-design.md` |
| D3 | Colour roles in one Kotlin model; contrast is a test; no state by colour alone; no good or bad colour | 0075 |
| D4, D6 | Cutesy is visual, never verbal; the starter schedule is structure only | 0076 |
| D5, D9 | Nine locale and scale combinations in Light plus six appearances at English 100 percent; fixtures until the human strings arrive; a CSV workflow | 0077 |
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
| i | Onboarding in six steps with one gate; Today's four states over the log; banners; the reliability view | 0087 |

---

## 4. The dependency approval list

Nothing enters the build until Shubham approves this list in review. The full table, with what each costs to do without and what was reconsidered and declined, is ADR 0084. In short:

- **Build:** the Kotlin Compose compiler plugin 2.4.20; the Roborazzi plugin 1.76.0 (in the catalog already).
- **Main:** `androidx.compose:compose-bom` 2026.06.01 with `ui`, `foundation`, `material3`; `androidx.activity:activity-compose` 1.13.0.
- **Test:** `androidx.compose.ui:ui-test-junit4` (by BOM); `roborazzi` and `roborazzi-compose` 1.76.0.
- **Files, not artifacts:** Nunito and Noto Sans Devanagari (SIL OFL 1.1); 21 Material Symbols icons (Apache 2.0).
- **Still declined:** koin-android, koin-compose, direct androidx.test, ui-test-manifest, coroutines in android, a direct androidx.core, navigation-compose, viewmodel-compose, Glance, appcompat, an icon artifact, ML Kit.

---

## 5. The PR sequence

Rules for every PR: one branch from current `main`, never stacked, only after the previous one is merged. The Working conventions of `IMPLEMENTATION_PLAN.md` apply in full: green means every check on the pull request head read through the GitHub MCP; mutations run on the final code commit, with the diff shown first and the failure confirmed to be the intended test, on an assertion, for the intended reason; no conversation or session link anywhere; every string in `values/strings.xml`. Every PR updates `docs/phase-3-traceability.md` (test names, mutation, observed failure, SHA) and reports: the head SHA and check states, test counts with their SHA, the mutation table, anything it stopped on, and the no links check.

"STOP" means: do not choose; write what was found and hand back.

Test tables give, for each test, its subject and setup, its assertion, and the mutation that must fail it. Every test named here reaches its subject through the production entry point named in its row; a test that asserts a helper's output where the row names a command or a screen does not count.

### PR 1. `phase-3/schema-v6` (Sonnet)

**Goal.** Shared schema version 6 and the vocabulary it carries, with no change of behaviour.

**Scope.** `migrations/5.sqm` exactly as ADR 0079 lists; `Occurrence.sq` (the column, the partial index, the trigger), `Event.sq` (two columns); `OccurrenceState.WITHDRAWN`, `EventType.WITHDRAWN`; `Occurrence.criticality`; `EventPayload.Completion(nutritionTags)` and `EventPayload.Water(waterMl, zone)`; `EventColumn` allowances (`nutrition_tags` on `COMPLETED` and `COMPLETED_BACKFILLED`; `zone_id` on `WATER_LOGGED`); mappers; `OccurrenceMaterialiser` sets `criticality` from the template and `datesAlreadyMaterialisedForTemplate` ignores withdrawn rows; `OccurrenceActions.acknowledged` takes the tags and `OccurrenceActionCommand` passes the template's; every exhaustive `when` over the two enums compiles with the new value treated as terminal and uncounted. **Out of scope:** any reader switching to `occurrence.criticality` (PR 2), the edit command (PR 3), any writer of `WITHDRAWN` or `WATER_LOGGED`.

**Files.** `shared/src/commonMain/sqldelight/**`, `shared/.../domain/{Enums,Occurrence,Event}.kt`, `shared/.../data/{EventColumn,Mappers,OccurrenceRepository,OccurrenceActionCommand}.kt`, `shared/.../engine/{OccurrenceMaterialiser,OccurrenceActions,EventLogReduction}.kt`, their tests, `android` test fixtures that construct an `Occurrence`.

**Relies on.** ADR 0079 (schema version 6), ADR 0086 items 1 and 4, ADR 0042, 0043, 0052.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `SchemaV6Test` `migrates from every prior version` | Databases at versions 1 to 5, each with a template of each criticality, an occurrence in every state and one event of each type that has a payload, foreign keys on | After migrating: `foreign_key_check` is empty; every occurrence's `criticality` equals its template's; every old row survives unchanged in its other columns | Remove the `UPDATE` that fills `criticality`: the CRITICAL and GENTLE rows read STANDARD |
| `SchemaV6Test` `a withdrawn row is immutable in full` | A `WITHDRAWN` row at version 6 | An update of each updatable column aborts with the trigger's message | Recreate the trigger without `WITHDRAWN` |
| `SchemaV6Test` `the other terminal states are still immutable after the migration` | Rows `COMPLETED`, `SKIPPED`, `MISSED` in a migrated database | Each update aborts | Delete the `CREATE TRIGGER` from `5.sqm` (the Android twin must fail too, as PR 6b's lesson says) |
| `SchemaV6Test` `a date can be materialised again after a withdrawal and not otherwise` | Insert an occurrence, set it `WITHDRAWN` with its event, insert another for the same template and date; then a third | The second insert succeeds; the third throws on the unique index | Make the index non partial: the second insert throws. Drop the index: the third succeeds |
| `AndroidSchemaTest` twins of the three above | The same under `AndroidSqliteDriver` at SDK 29 and 36 | The same | The same mutations, observed in the Android test |
| `EventColumnGuardTest` additions | A row of every other type with `nutrition_tags` or `zone_id` set; a `WATER_LOGGED` row without `zone_id` | Decoding fails loudly, naming the column | Add the column to another type's allowance; make the zone optional |
| `EventPayloadRoundTripTest` additions | `Completion` with zero, one and seven tags; `Water` with a zone | Round trip equality; tags in enum order in the column | Join the tags in set order (use a set whose iteration order differs) |
| `OccurrenceActionCommandTest` `acknowledge records the template's tags as they are now` | A template with two tags; acknowledge; replace the template's tags directly in the test database; acknowledge the next occurrence | The first event carries the two old tags, the second the new ones | Pass `emptySet()` to `acknowledged` |
| `MaterialiserTest` `an occurrence takes its template's criticality` and `withdrawn dates are materialised again` | Materialise templates of each criticality; withdraw one row in the test database and materialise again | Criticality copied; one new row for the withdrawn date with a new id and a slot greater than every earlier slot | Hard code STANDARD; include withdrawn rows in the dates already materialised |
| `EventLogReductionTest` `a withdrawn occurrence is in none of the three figures` | An occurrence with a `WITHDRAWN` event beside one completed, one skipped, one missed | Figures 1, 1, 1 | Map `WITHDRAWN` to `SKIPPED` in `outcome()` |

**STOP points.** The 3.18 dialect or `verifySqliteFloor` rejects the partial index or any statement of `5.sqm`. `verifySqlDelightMigration` cannot be made to pass with the column declared as the migration leaves it. Any `when` over `OccurrenceState` whose correct treatment of `WITHDRAWN` is not "terminal and uncounted".

**Docs.** `ARCHITECTURE.md` sections 3.2 and 3.3 (the state, the event, the column, the payloads, schema version 6); traceability rows G and H.

**Report.** As the rules say, plus the list of every `when` that gained a `WITHDRAWN` arm and what the arm does.

### PR 2. `phase-3/occurrence-criticality` (runs on Fable)

**Why Fable.** It changes which value the alarm path reads at about ten sites in seven files (`ReconcileCommand`, `ArmingSelection`, `ArmingSupport`, `AlarmFireHandler`, `AndroidDeliveryPort`, `ReliabilityReport`, and callers of `criticalCompletionDays`). A site left on the template is a silent fork that only an edit exposes, and deciding that a given site is display rather than behaviour is a correctness judgment.

**Goal.** Every behaviour that depends on criticality reads `occurrence.criticality`; the template's is read only to materialise and to show the editor.

**Scope.** Those sites; `criticalCompletionDays` loses its `criticalityOf` parameter. A structural check, `verifyCriticalityFromOccurrence`, in the style of `verifyRingUiBoundary`: outside `OccurrenceMaterialiser`, the edit command and the UI's editor package, no source may read `.criticality` on a `ScheduleTemplate`; with a fixture self test, wired into `check` and `verify-android-structure`. **Out of scope:** the edit command.

**Relies on.** ADR 0079 item 6.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `CriticalitySourceTest` (android, Robolectric, SDK 29 and 36), one case per behaviour: `the ladder`, `grace`, `the channel`, `the default vibration`, `the budget exemption`, `quiet hours` | A CRITICAL template; materialise; then set the template row to GENTLE directly in the test database (no command), so the two values differ; run the real fire path, `Reconcile`, delivery | Each behaviour is the CRITICAL one: the second rung is armed five minutes on; the occurrence is `MISSED` at two hours and not before; the notification is on the Critical channel; the pattern is URGENT; it rings with the budget spent and inside quiet hours | Revert each site to `template.criticality` in turn; the case for that behaviour fails and no other mutation is needed to see it |
| `EventLogReductionTest` `critical completion days read the occurrence` | The same divergence, over 30 days | The count is the one the occurrences' values give | Read the template in the caller |
| `verifyCriticalityFromOccurrence` self test | Fixtures: a read in arming code, in delivery, in the materialiser, in the editor | Flagged, flagged, allowed, allowed | Narrow the pattern; fail closed on an empty source set |

**STOP points.** Any read whose right source is unclear. Any Phase 2 test that needs its expectation changed rather than its fixture.

**Docs.** `ARCHITECTURE.md` sections 3.1, 3.2, 4.2, 4.5; `CLAUDE.md` gains nothing. Traceability rows.

### PR 3. `phase-3/template-edit` (Sonnet; shared only)

**Goal.** `EditTemplateCommand` and `CreateTemplateCommand`, golden scenario 1, and scenario 12's real interleaved edit.

**Scope.** `shared/.../data/EditTemplateCommand.kt` implementing ADR 0079 rules 1 to 7 and 9 exactly: `dispatch(edited: ScheduleTemplate, now: Instant): EditResult` where `EditResult(withdrawn: List<Occurrence>, moved: Int)`; `CreateTemplateCommand.dispatch(template)` (an insert; validation of title length 1 to 24, `EveryNDays` n at least 1, a non empty weekday set, returning a typed refusal and writing nothing otherwise; the edit command applies the same validation); `ScheduleTemplateRepository.update(template)` (every editable column and the tag rows, in the caller's transaction) replaces `setActive`, which is removed with its query; `TemplateEdit.cameDue(occurrence, firedCount, now)` and `TemplateEdit.wants(template, localDate)` as pure functions in `engine`; the zone change command is unchanged. `Seeder` (debug) switches to `EveryNDays(3650, today)` and calls no `setActive`. **Out of scope:** anything in `android/src/main`.

**Relies on.** ADR 0079. PR 1 and PR 2 merged.

**Golden scenario 1**, `TemplateEditScenarioTest` `scenario 1 an edit of timeOfDay after some occurrences are terminal`. Setup, with a real database and a fixed clock at 10:00 on day D in Asia/Kolkata: a Daily STANDARD template at 08:00; materialise D-1, D, D+1. D-1 is completed through `OccurrenceActionCommand`; D (08:00, two hours past) has one `ALARM_FIRED`; D+1 is pending. Dispatch an edit to 14:30. Assert, on rows read back and on the event table:

1. D-1: every column equal to its value before the edit, state `COMPLETED`.
2. D: every column equal (it has come due); no event added for it.
3. D+1: `scheduledInstant` is D+1 14:30 in the zone; id, `localDate`, `alarmSlot`, state and `criticality` unchanged.
4. The event table has exactly the rows it had before.
5. Materialising D to D+3 afterwards adds D+2 only, at 14:30, with a slot greater than every earlier slot.
6. The adherence figures as of the end of D+3 with nothing else done are what they were before the edit plus nothing: no skip and no miss appears for D-1 or because of the move.

A second case in the same class, `an edit to earlier than now keeps today's time`: at 10:00, a pending occurrence today at 12:00, edit to 09:00; today's instant stays 12:00, tomorrow's becomes 09:00. Mutations, each of which must fail the named assertion and no setup step: (M1) drop the came due check, assertion 2 fails on the instant; (M2) recompute every occurrence of the template including terminal ones, the schema's abort fails the command, which is the intended failure, as in ADR 0068; (M3) remove the "not before now" rule, the second case's today instant fails; (M4) reschedule by deleting and inserting, assertion 3 fails on id and slot.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `scenario 1 ...` and `an edit to earlier than now keeps today's time` | Above | Above | M1 to M4 |
| `deactivation withdraws every open occurrence, due or not` | Pending tomorrow, snoozed today, completed yesterday; deactivate | Two `WITHDRAWN` events, source USER, no payload; two rows `WITHDRAWN`; yesterday untouched; figures as of later show completed 1, missed 0, skipped 0 | Skip occurrences that have come due; write `SKIPPED` |
| `a recurrence change withdraws exactly the dates no longer wanted` | Daily to Weekly on two weekdays, over a window of three days of which one stays wanted | The unwanted two are withdrawn; the wanted one is untouched or moved by rule 5 | Invert `wants`; withdraw only when inactive |
| `reactivation materialises fresh occurrences and never a past one` | Deactivate at 10:00 with today 09:00 (came due) and today 20:00 and tomorrow; reactivate at 10:05; materialise | New rows for today 20:00 and tomorrow with new ids and slots; none for 09:00; the three withdrawn rows unchanged | Reuse the withdrawn row's id; include past instants |
| `a criticality edit reaches only occurrences that have not come due` | CRITICAL to GENTLE with one ringing (one `ALARM_FIRED`), one overdue and unfired, one tomorrow | The first two keep CRITICAL, tomorrow's is GENTLE; with `ArmingSelection` over the result, the ringing one's next rung is still the CRITICAL repeat | Update every open occurrence's criticality |
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

After the last step, materialise the whole remaining span and assert **convergence**: the set of open rows whose instant is after `now` equals, date for date and instant for instant, the expansion of the final template's recurrence at its final `timeOfDay` over the window, less dates that hold a terminal row that is not withdrawn. The generator must be shown to reach the distinguishing cases: the test counts, per seed, edits that moved at least one row, edits that withdrew at least one, and reactivations that created at least one, and fails if any of the three totals is zero over the 20 seeds. Mutations: remove the materialiser's `filterNot` (assertion 1, by the unique index's throw, the intended failure); make the index non partial (a reactivation step throws); move rows that have come due (assertion 3); leave `timeOfDay` unapplied to open rows (convergence).

**STOP points.** Any rule of ADR 0079 that two tests here would force to differ. `findOpen` or the per template queries needing a new index.

**Docs.** `ARCHITECTURE.md` sections 3.1, 3.2, 11 ("Template edited mid day"); `IMPLEMENTATION_PLAN.md` scenario 1 and 12 status, with the test names; traceability G1, G12, O-deactivation.

### PR 4. `phase-3/edit-pass` (runs on Fable)

**Why Fable.** It joins the edit to the ring session, the notifications, arming and the reliability reductions, inside the coordinator's exclusion; the cases are few but each is on the alarm path.

**Goal.** `TemplateEditPass` (ADR 0079 item 8), withdrawal reaching the ring session and notifications, and reliability evidence that does not count an edit as a failure (item 10).

**Scope.** `work/` or `arming/` `TemplateEditPass`; `RingController.withdrawn(occurrences)`; `FireTiming` treats `WITHDRAWN` before the rung as it treats `COMPLETED` and `SKIPPED`; `TemplateEditRaceTest`; the debug seed's tests. No UI.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `EditPassTest` `an edit of the armed occurrence's time re arms it` (SDK 29, 31, 33, 36) | One pending occurrence armed for tomorrow 08:00; edit to 09:00 through the pass | `ShadowAlarmManager` holds exactly one alarm, at 09:00, with the occurrence's slot; one new `ALARM_SCHEDULED` | Remove `ensureArmed` from the pass |
| `deactivating a ringing reminder stops the ring` | Tier 3, one occurrence ringing; deactivate through the pass | The session has ended, the ringer stopped, `RING_ID` and the slot's notification are cancelled, no alarm is armed, the state is `WITHDRAWN` and the log holds no `COMPLETED` | Remove the call to `withdrawn`; the ringer is still running |
| `deactivating one of two ringing leaves the other` | Two ringing | The other still rings with its actions | Call `sessions.end()` |
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
| `asOf is inclusive and nothing after it counts` | Three events around `asOf` | The boundary event counts | Use a strict bound |
| `LogWaterCommandTest` | Dispatch with a zone and a fixed clock | One `WATER_LOGGED`, source USER, no occurrence, 250 ml, the zone; nothing else written | Omit the zone (decoding fails) |
| `GestationalAgeTest` | Due date 280 days ahead, 0 days, 14 days past, 300 days ahead; a revised due date read as of before and after the revision | Weeks 0, 40, 42, null; the earlier `asOf` gives the earlier revision's week | Read the latest revision always |

**STOP points.** The nutrition and adherence outcome rules cannot share one function without changing adherence's tests.

**Docs.** `ARCHITECTURE.md` sections 3.5, 3.6, 3.8; `IMPLEMENTATION_PLAN.md` (the two reductions built); traceability X-reductions.

### PR 6. `phase-3/notifications` (runs on Fable)

**Why Fable.** ADR 0080 is a small state machine on the ring path, with platform behaviour read from AOSP and four device questions; an implementer must judge transitions the ADR could not enumerate.

**Goal.** ADR 0080: the session spreads at the second occurrence, and the invariant holds through every join, action, refusal, stop and end.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `SpreadTest` `one occurrence keeps its buttons on the session notification` (SDK 29, 33, 34, 36; Tier 2 and Tier 3) | One fire | `RING_ID` has the three actions; no notification with the slot's id | Always spread |
| `a second occurrence spreads the session` | Two fires | `RING_ID` has no actions and only alert once; each slot has a notification on its criticality's channel with exactly its offered actions, each an immutable explicit `PendingIntent` to the unexported receiver; neither has a full screen intent or a delete intent; `RING_ID` keeps the full screen intent in Tier 3 | Skip the per occurrence post; leave the buttons on `RING_ID` |
| `the invariant holds along every path` | A table of event sequences (join, act on each, refuse, stop sound, last one acted on, a third joins after one left) | After each event: every ringing occurrence has exactly one notification carrying exactly its offered actions; a resolved one has none | Move the buttons back when one remains |
| `a button on a spread notification writes exactly its event` | Press each | The delta of ADR 0066 | (Phase 2's mutations, re run against the new notification) |
| `a refused action refreshes the buttons where they are` | A fourth snooze on a spread notification | That notification is reposted without snooze | Refresh `RING_ID` only |

**STOP points.** Anything the invariant does not decide.

**Docs.** `ARCHITECTURE.md` section 5.7; `MANUAL_CHECKS.md` P3-2 to P3-5.

### PR 7. `phase-3/ui-foundation` (Sonnet; starts only after the dependency list is approved)

**Goal.** Compose and Roborazzi in the build, fonts, colour roles and their tests, the appearance store and state, the theme, the screenshot harness. No screen yet.

**Scope.** The catalog and `android/build.gradle.kts` entries of ADR 0084, exactly; `ComponentActivity` in the debug manifest; `res/font` files and `docs/THIRD_PARTY.md` with licences (ADR 0085); the 21 icons; `ui/theme/{ColorRoles,Palettes,RolePairs,Appearance,AppearanceState,MomTimeTheme,Type,Shapes,FontChain}.kt` with the values of the design spec sections 1 to 4, exactly; `AndroidSettings` appearance, palette and locale keys; the component set of design spec section 5 as composables with previews in tests only; `ScreenshotMatrix` (the nine combinations and six appearances, strict comparison, the paused clock, SDK 36, the size `w360dp-h800dp-xhdpi`); the fixture string source (ADR 0077 item 4); the CI job `android-screenshots` and the manually dispatched `record-screenshots` workflow; `verifyNoCoroutinesInAndroid`.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `ContrastTest` | Every appearance and palette, every pair of `RolePairs` | Ratio at or above the pair's minimum | Lighten one palette's `onMuted` to `#9A9A9A`; lower a minimum is not a mutation |
| `ContrastTest` `every role is in a pair` and `every pair of the design spec is present` | Reflection over `ColorRoles`; the spec's list written out in the test | No role unused; no listed pair missing | Add a role with no pair; delete a pair |
| `PalettesTest` `values are the specification's` | The table of design spec section 2, written out in the test | Equal | Change one value |
| `NoColourLiteralTest` | Scan of android main sources and resources | No literal outside `Palettes.kt` and the two allowed files; fails closed if it scans no file | Add `Color(0xFF00FF00)` to a composable |
| `AppearanceStoreTest` `a choice survives process death` (SDK 29, 36) | Choose Pastel and Mint; build a new `AndroidSettings` and `AppearanceState` over the same context | Pastel and Mint | Keep the choice in memory only |
| `the default is System and an unknown name reads as the default` | Fresh; a stored `"NEON"` | System; System | Default to Light |
| `BackupRulesTest` addition | The appearance and locale keys | Written to the file the rules include | Write them to another file |
| `FontChainTest` (SDK 29, 36) | Spike S7's measurements, with its positive control | Devanagari as the bundled Noto; Latin as Nunito; weights differ | Build the chain without the fallback |
| `ThirdPartyTest` | `res/font`, the icon list, `THIRD_PARTY.md` | Every file recorded, hashes equal | Alter a hash |
| `ComponentScreenshotTest` | Each component of section 5 in each state, through `ScreenshotMatrix` | Goldens | Change a card's padding by 1 dp: verify fails with a diff image. A golden that is missing fails the verify task |
| `LineHeightTest` | The type table of section 3 written out in the test, per locale | Equal; text she typed uses the hi value in en | Use the en value for her text |
| `verifyNoCoroutinesInAndroid` self test | Fixtures | An import is flagged; a comment is not | Empty the pattern |

**STOP points.** A golden recorded by the CI workflow does not verify in the CI job, or goldens exceed 60 MB. Any artifact resolving to a version other than ADR 0084's. Lint or detekt needing a rule changed for Compose (function naming): report the rule, do not disable broadly.

**Docs.** `ARCHITECTURE.md` section 9 and a new section on the UI layer; `README.md` suites table; `IMPLEMENTATION_PLAN.md` Working conventions (approved dependencies); `MANUAL_CHECKS.md` P3-1.

### PR 8. `phase-3/app-shell` (Sonnet)

**Goal.** `MainActivity` as the launcher entry, navigation, the top bar with the appearance control, the sheet, the ring screen taking the appearance, the exported allowlist, `DataChanges`.

**Scope.** `ui/MainActivity`, `ui/nav/{Screen,ScreenRegistry,BackStack}`, `ui/UiGraph`, `UiEntryPoint`; `AppBar`, `AppearanceControl`, `AppearanceSheet`; placeholder bodies for Today, Overview and Settings that show only their title (replaced by later PRs); `shared` `DataChanges` (ADR 0081 item 1); `ring/RingAppearance` binder and the layout of design spec screen 17; the ring screen's idle button opens `MainActivity`; `LocalizedContext` and `attachBaseContext` (ADR 0082 item 3); `verifyExportedComponents` and its self test with the `MainActivity` and library entries; `windowDisablePreview` on `RingActivity`.

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
| `LocaleOverrideTest` (SDK 29, 33, 36) | Override hi with the device in en | `MainActivity`'s, `RingActivity`'s and `LocalizedContext`'s resources resolve hi; clearing it resolves en | Wrap the Activity only |
| `verifyExportedComponents` self test | Fixtures: an extra exported component, a listed one missing, a changed permission, an intent filter with no `exported` attribute, the exact list | Each of the first four fails, the last passes; a missing manifest fails | Compare in one direction only |
| `BackStackTest` | Push, rotate (recreate), back | Depth and top preserved; back at depth 1 finishes | Do not save the stack |

**STOP points.** The ring screen cannot take the roles without naming a forbidden type. The system's per app language (API 33) conflicts with the override in a way a test shows.

**Docs.** `ARCHITECTURE.md` sections 5.2, 5.7, 9 and the UI section; `MANUAL_CHECKS.md` P3-1 procedure; the README's seed section (the app now has a launcher entry).

### PR 9. `phase-3/strings-pipeline` (Sonnet)

**Goal.** The export and import tools, the rules check, and the wave 1 export handed to the translator.

**Scope.** `scripts/strings/{ExportStrings,ImportStrings}.java` (single file Java programs, as `GenerateSounds.java` is); a `health_adjacent` and `note` attribute convention as XML comments above each string; `CopyRulesTest`; `NoFixtureInResourcesTest`; `docs/translation/README.md` (how he works in the file) and `docs/translation/wave-1.csv` generated from the 74 strings and 11 plurals Phase 2 shipped, with every health adjacent one marked.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `StringsRoundTripTest` | Export `values/strings.xml`; fill hi and mr from a test table; import | The generated `values-hi` holds every key, plurals with the locale's quantities, placeholders equal to the English | Drop plural quantities on export |
| `import refuses a changed placeholder and a missing quantity` | A row with `%2$d` removed; a plural without `other` | A failure naming the key | Skip the check |
| `CopyRulesTest` | Every string in every locale | No banned phrase or pattern (ADR 0076 item 5); the test's own list is asserted non empty | Add "Great job" to a string |
| `NoFixtureInResourcesTest` | ADR 0077 item 4 | As stated, failing closed | Put a fixture string in `values-hi` (in a mutation only) |

**Docs.** `IMPLEMENTATION_PLAN.md` (wave 1 exported, date); section 7 below.

### PR 10. `phase-3/onboarding` (Sonnet)

**Goal.** The six steps of ADR 0087 as design spec screens 1 to 6; the three Phase 2 Activities deleted; their assertions kept.

**Scope.** `ui/onboarding/*`; models over `PregnancyRepository`, `CreateTemplateCommand` with the edit pass, `SetupHost`, `SamsungStep`, `ReliabilityHost`; `onboarding_complete`; the gate; deletion of `SetupActivity`, `SamsungStepsActivity`, `ReliabilityCheckActivity`, their layouts and manifest entries; the tests of those three screens moved to the Compose screens with every assertion kept (the PR description maps each old test name to its new one, and any that has no new home is a STOP). **Before this PR ships:** the four A15 screenshots (section 7).

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

**STOP points.** A Phase 2 assertion with no equivalent. The placeholder Samsung drawables still in place at the end of the PR (ship is blocked; say so).

**Docs.** `ARCHITECTURE.md` sections 5.2, 5.10; `MANUAL_CHECKS.md` P2-34 to P2-38 procedures now start from onboarding; README.

### PR 11. `phase-3/today-and-reminders` (Sonnet)

**Goal.** Design spec screens 7 to 10: Today, the starter schedule, the template list, the editor.

**Scope.** `TodayModel` (ADR 0087 items 5 and 6), `RemindersModel`, `EditorModel` over the commands and the edit pass; the editor's vibration choice writes `AndroidSettings.setVibration`; the confirm dialog built from a dry run (`EditTemplateCommand.preview(edited, now): List<Occurrence>` that would be withdrawn, pure over the same rules, added in `shared` with a test that preview and dispatch agree on random edits).

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `TodayModelTest` `four states over the log` | One occurrence in each of the six states, plus a missed one with a backfill event inserted by the test | Done for completed and for the backfilled one; Skipped; Missed; To do for pending and for snoozed with its end time; the withdrawn one absent | Map from the `state` column (the backfilled row shows Missed) |
| `rows are today's in the current zone, in order` | Occurrences either side of midnight in two zones | The right set and order | Use UTC dates |
| `Taken and Skip go through the ring controller` | A ringing occurrence; tap Taken on Today | The delta of ADR 0066, the session resolved, the notification cancelled | Call the command directly (the ringer keeps running) |
| `state is never colour alone` | The four markers composed in every appearance | Each has a distinct content description and a distinct shape tag; the four markers' pixels use only `outline`, `onSurface`, `surface` | Tint the Done marker with a new colour (also fails `NoColourLiteralTest`) |
| `the list follows a change made elsewhere` | Complete an occurrence through the notification receiver while Today is shown | The row becomes Done without a resume | Unsubscribe from `DataChanges` |
| `a read that meets corruption ends the process and posts nothing` | The corruption seam of `DatabaseCorruptionTest` under `TodayModel` | `ProcessEnd` called once; no state posted after | Catch the exception in the model |
| `EditorModelTest` `save creates or edits through the commands and runs the pass` | Create; then edit the time | One template; the alarm moved | Write through the repository |
| `the editor never writes a mission and shows no mission control` | Save every form state the generator produces | `MissionConfig.None` always; no node tagged mission | Add a mission chip |
| `doctor instructions and dosage are stored and shown verbatim` | Mixed script text with digits | Byte equal in the row, on Today and on the ring screen | Trim or normalise |
| `the confirm dialog lists exactly what will be withdrawn` | Deactivate with two open occurrences; change weekdays | The dialog's rows equal `preview`; cancel writes nothing | Show the dialog after dispatch |
| `StarterScheduleTest` `it names nothing and needs a title for every row` | The screen | The three title fields are empty and have no default text; the resources hold no starter title; the button is disabled until titled; saving creates STANDARD Daily templates at the three times with her titles | Prefill a title |
| `ScreenshotTest` for screens 7 to 10 | The matrix | Goldens; `NoClipTest` | A fixed height row |

**STOP points.** Any wish for a field or control the design spec does not list.

**Docs.** `ARCHITECTURE.md` UI section; `IMPLEMENTATION_PLAN.md` deliverables status.

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

### PR 13. `phase-3/settings-and-reliability` (Sonnet)

**Goal.** Design spec screens 14 and 15, and the banners on Today.

| Test | Subject and setup | Assertion | Mutation that must fail it |
|---|---|---|---|
| `SettingsModelTest`, one case per row | Change quiet hours (and clear them), the ring limit, the snooze length, the louder sound interval (and off), the language, the opt in | Each is written to its one home: the first three to `AppSettingsRepository`, the rest to `AndroidSettings`; equal start and end quiet hours are refused on screen | Write the opt in to the shared column |
| `a setting changes behaviour, not only storage` | Snooze length 5 then snooze; quiet hours covering now then a STANDARD fire | `snoozedUntil` is five minutes on; the fire is silent | Cache settings in the model |
| `the Appearance row opens the same sheet` | Tap | The sheet's tag, the same composable function (asserted by a shared test tag and by `ScreenRegistry` holding no themes screen) | Add a themes screen |
| `BannerOrderTest` | Every subset of banner states, generated | The first shown is the highest in ADR 0087's order; the reliability view shows all | Reverse two priorities |
| `a banner's action opens its fix step` | Each `FixStep`, Samsung and not | Samsung steps; permissions at the battery row; the unused app flow | Open Settings for all |
| `the blocking banner cannot be dismissed and covers Today` | The blocked capability | No dismiss control; Today's list is not reachable by semantics | Add a close button |
| `the reliability view reads the report and the store failing soft is shown` | `ReliabilityReader` over a store whose seam fails | The view renders; the store trouble banner is present; nothing throws | Let the failure propagate |
| `export writes what Phase 2's export writes` | The document picker result | Bytes equal to `ReliabilityExport` | Build the JSON in the screen |
| `ScreenshotTest` for screens 14 and 15, and Today with each banner | The matrix | Goldens; `NoClipTest` | A one line banner title |

**STOP points.** A setting whose effect cannot be asserted through behaviour.

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
| `the nudge state is not backed up` | The rules files | The nudge file is in neither | Add it to the rules |
| `ScreenshotTest` for screen 18 | The widget's layout applied to a host view, the matrix | Goldens | |

**Docs.** `ARCHITECTURE.md` sections 3.6, 5.6; `MANUAL_CHECKS.md` P3-6 and P3-8 (a nudge's timing on a device, the widget on a launcher, the tile).

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
| D3 contrast as a test | 7 |
| D4 copy rules | 9 |
| D10 exported components allowlist | 8, 14 |
| Start the Play 12 tester clock | Shubham, at the merge of 17 (section 7) |

Nothing in Phase 3's section of `IMPLEMENTATION_PLAN.md` is unmapped. Each row is a row of `docs/phase-3-traceability.md`.

---

## 7. What Shubham owes, and when

| What | Needed by | Note |
|---|---|---|
| Review of this pull request; approval of the dependency list (ADR 0084) and of the two fonts and the icon set (ADR 0085) | Before PR 7 starts. PRs 1 to 6 need no new dependency and can start as soon as this plan is merged | |
| The mockup's path, or a correction of the lavender palette | In this review | C1 |
| A decision on marking a missed dose as taken late | Before PR 11 | Section 8 |
| `android-screenshots` added to the required checks on `main` | When PR 7 merges | |
| The four Galaxy A15 screenshots in `drawable-nodpi`, at most 1080 px wide and 400 KB each | Before PR 10 ships | The Open items row |
| His brother: wave 1 translation (85 Phase 2 strings and plurals) | Handed over when PR 9 merges; wanted back before PR 16 | Early, on purpose: the health adjacent strings of the ring screen, the notices and the permissions are all in wave 1 |
| His brother: wave 2 translation and the review of all health adjacent copy | Handed over when PR 15 merges; PR 16 is gated on it | The exit criterion is his review, recorded |
| The device rows P3-1 to P3-8, informally with a debug build | Any time after PR 14; formally in Phase 7 | |
| The Play developer account, Play App Signing, the upload key backed up outside the repo, and 12 testers opted in | At the end of Phase 3, so that the 14 days run alongside Phase 4 | Unchanged from the plan |

---

## 8. Open questions for review

1. **The mockup** (C1).
2. **Late marking.** Today will show a Missed row with nothing to do about it. `COMPLETED_BACKFILLED` exists in the vocabulary and in the reductions, but no command writes it and no deliverable names its UI. Recommendation: add "Taken late" on a Missed row to PR 11, with a `BackfillCommand` in `shared` (a user event on a terminal occurrence, no state change, as ADR 0040 describes). It is one small command and one button, and without it the record she hands her doctor shows a miss she cannot correct. It is not planned until approved, because it is a deliverable the plan does not list.
3. **Criticality on the occurrence** (C5) is the heaviest part of decision a: a migration and about ten changed read sites on the alarm path. The alternative is to accept that changing a template's criticality relabels the last 30 days and acts on a ladder in mid flight, with a guard that cannot be written for grace. The plan takes the snapshot.
4. **`WITHDRAWN` reaches Phase 4.** The server's `sweep_status` already has `SUPERSEDED`; the upload must send a withdrawal, or the dead man switch will report a withdrawn dose as missed to a caregiver. This is carried to Phase 4's list in `IMPLEMENTATION_PLAN.md`.
