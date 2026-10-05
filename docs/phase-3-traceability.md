# Phase 3 traceability

Status: scaffold, written by the Phase 3 planning session. No row has evidence yet. Every row's test and mutation come from `docs/phase-3-plan.md`; the pull request that delivers a row fills in the observed failure and the two SHAs, and changes the status.

Columns, as in `phase-2-traceability.md`: **ID**; **Requirement** and its source; **PR**; **Test** (class and method, as named in the plan); **Mutation planned** (the change that must make the test fail); **Observed failure** (the test that failed and its message, filled in when run); **Mutated at** (the SHA the mutation was run on); **Merged at** (the merge commit); **Status** (`PLANNED`, then `PASS`, or `DEFERRED` with an owner); **Device remainder** (a `MANUAL_CHECKS.md` row).

A mutation is recorded only after its diff was shown and was exactly the intended change, and after the intended test failed on an assertion for the intended reason (`CLAUDE.md`, mutation hygiene). A compile error counts only where the plan says it is the intended result.

## Golden scenarios owed by Phase 3

| ID | Requirement | PR | Test | Mutation planned | Observed failure | Mutated at | Merged at | Status | Device remainder |
|---|---|---|---|---|---|---|---|---|---|
| G1 | Golden scenario 1: an edit of `timeOfDay` after some occurrences are terminal leaves every terminal occurrence untouched and moves only open ones that have not come due, asserted on the occurrences | 3 | `TemplateEditScenarioTest`: `scenario 1 an edit of timeOfDay after some occurrences are terminal`, `an edit to earlier than now keeps today's time` | M1 drop the came due check; M2 recompute terminal rows too; M3 remove the not before now rule; M4 delete and insert instead of reschedule | | | | PLANNED | P3-7 |
| G12 | Golden scenario 12: materialisation idempotent with a real interleaved edit of `timeOfDay` and of the recurrence; no duplicate dates or slots, no change to a row that is terminal or has come due, convergence to the edited schedule | 3 | `GoldenScenariosDbTest`: the rebuilt property test, with its three reach counters | Remove the materialiser's `filterNot`; make the unique index non partial; move rows that have come due; leave `timeOfDay` unapplied to open rows | | | | PLANNED | |

## Exit criteria (`IMPLEMENTATION_PLAN.md`, Phase 3)

| ID | Requirement | PR | Test | Mutation planned | Observed failure | Mutated at | Merged at | Status | Device remainder |
|---|---|---|---|---|---|---|---|---|---|
| X1 | The screenshot suite is green across all nine locale and scale combinations with no clipping, truncation or overflow | 7 to 14, 16; reported in 17 | Every `ScreenshotTest`, `NoClipTest`, over the real hi and mr resources | A fixed height or `maxLines = 1` on a label, per screen PR | | | | PLANNED | |
| X2 | Human review of all health adjacent copy in Hindi and Marathi | 16 | Not a test: the review recorded in `MANUAL_CHECKS.md` with the reviewer, the date and the commit; `MissingTranslationTest` | Delete one hi string | | | | PLANNED | |
| X3 | Nutrition tag aggregation as a pure reduction in `shared`, mutation checked | 5 | `NutritionReductionTest` (five tests named in the plan) | Read the template's tags in the reduction; count once per occurrence; attribute to the event's date; count `MISSION_VERIFIED` | | | | PLANNED | |
| X4 | Water totals as a pure reduction in `shared`, mutation checked | 5 | `WaterReductionTest`, `LogWaterCommandTest` | Attribute by one zone passed by the caller; a strict `asOf` bound; omit the zone | | | | PLANNED | |
| X5 | What deactivating a template does to its open occurrences, decided and built | 3, 4 | `TemplateEditScenarioTest` `deactivation withdraws every open occurrence, due or not`; `EditPassTest` `deactivating a ringing reminder stops the ring` | Skip occurrences that have come due; write `SKIPPED`; remove the call to `withdrawn` | | | | PLANNED | P3-7 |

## Deliverables

| ID | Requirement | PR | Test | Mutation planned | Observed failure | Mutated at | Merged at | Status | Device remainder |
|---|---|---|---|---|---|---|---|---|---|
| L1 | Onboarding: due date, one reminder, permissions, Samsung steps, the check; inherits Phase 2's parts; the copy obligations; the gate | 10 | `OnboardingFlowTest` and the tests of plan PR 10; the ported Phase 2 assertions | Never set the flag; gate on any missing input; remove each obligation string; build a second runner | | | | PLANNED | P2-34 to P2-38 |
| L2 | Starter schedule as an editable suggestion that names nothing | 11 | `StarterScheduleTest` | Prefill a title | | | | PLANNED | |
| L3 | Today view with four states | 11 | `TodayModelTest` | Map from the `state` column; call the command directly; unsubscribe from `DataChanges`; catch corruption in the model | | | | PLANNED | |
| L4 | Schedule builder: create and edit; no mission | 3, 4, 11 | `EditorModelTest`; PR 3's command tests; `EditPassTest` | Write through the repository; add a mission chip; show the dialog after dispatch | | | | PLANNED | P3-7 |
| L5 | Water: one tap logging, goal, progress, nudges | 5, 12, 14 | `WaterModelTest`, `WaterNudgePolicyTest`, `WaterNudgeTest` | Write the event in the model; remove the cap; post for every missed slot; post on Gentle; call `AlarmManager` | | | | PLANNED | P3-8 |
| L6 | Nutrition aggregation view: counts only | 5, 12 | `NutritionModelTest` | Show a fraction | | | | PLANNED | |
| L7 | Dashboard: three figures, water, gestational week, days to due date, the 30 day figure | 5, 12 | `OverviewModelTest`, `GestationalAgeTest` | Add `completed / total`; count consecutive days; read the latest revision always | | | | PLANNED | |
| L8 | Settings | 8, 11, 13 | `SettingsModelTest`, `LocaleOverrideTest` | Write the opt in to the shared column; cache settings in the model; wrap the Activity only | | | | PLANNED | |
| L9 | Each occurrence has its own notification actions when several are due | 6 | `SpreadTest` | Always spread; skip the per occurrence post; move the buttons back when one remains; refresh `RING_ID` only | | | | PLANNED | P3-2 to P3-5 |
| L10 | Reliability view and the banner UI | 13 | `BannerOrderTest` and the tests of plan PR 13 | Reverse two priorities; open Settings for all; add a close button; let the store failure propagate | | | | PLANNED | |
| L11 | The app's own home: the launcher activity; the ring screen's idle link replaced | 8, 10 | `BackStackTest`, `verifyExportedComponents`, `OnboardingFlowTest` | Do not save the stack; compare the allowlist in one direction | | | | PLANNED | |
| L12 | The debug seed under the deactivation decision | 3, 4 | `SeederTest` `the test reminder is active, one off in effect, and rings` | Use `Recurrence.Daily` | | | | PLANNED | P2-41 |
| L13 | Roborazzi coverage of Phase 2's screens (permission, Samsung, check, ring) | 8, 10 | `ScreenshotTest` for screens 4, 5, 6 and 17 | A fixed width on a chip; a fixed button height | | | | PLANNED | |
| L14 | Hindi and Marathi strings for everything, human supplied | 9, 16 | `StringsRoundTripTest`, `MissingTranslationTest`, `NoFixtureInResourcesTest`, `CopyRulesTest` | Drop plural quantities; skip the placeholder check; put a fixture string in `values-hi`; add a banned phrase | | | | PLANNED | |
| L15 | Dark mode, as one of four appearances | 7, 8 | `ContrastTest`, `AppearanceStoreTest`, `AppearanceSheetTest` | Lighten a role; keep the choice in memory only; recreate the Activity on change | | | | PLANNED | P3-1 |
| L16 | Widget and quick settings tile for water | 14 | `WaterWidgetTest`, `WaterTileTest` | Write the event in the receiver; export the receiver; hard code Light; remove the tile's permission | | | | PLANNED | P3-6, P3-8 |
| L17 | Full localisation: bundled fonts, per locale line heights, plurals, no concatenation | 7, 9, 16 | `FontChainTest`, `LineHeightTest`, `ThirdPartyTest` | Build the chain without the fallback; use the en line height for her text; alter a hash | | | | PLANNED | |
| L18 | Accessibility pass | 15 | `AccessibilityTest`, `StringFreezeTest` | Remove a content description; shrink a target; change a frozen string | | | | PLANNED | |

## Decisions of the planning prompt that are tests

| ID | Requirement | PR | Test | Mutation planned | Observed failure | Mutated at | Merged at | Status | Device remainder |
|---|---|---|---|---|---|---|---|---|---|
| A1 | D2: one appearance setting, System by default, stored in `momtime_android_settings`, applied before the app's first frame | 7, 8 | `AppearanceStoreTest`, `BackupRulesTest` addition | Default to Light; write the keys to another file | | | | PLANNED | P3-1 |
| A2 | D2b: the control on every top level and onboarding screen, found by enumeration | 8, 10 | `SwitcherPresenceTest` and its reflection twin | Remove the control from one bar; add an unregistered `Screen` | | | | PLANNED | |
| A3 | D2b: a choice persists across process death | 7 | `AppearanceStoreTest` `a choice survives process death` | Keep the choice in memory only | | | | PLANNED | |
| A4 | D2b: a choice reaches the ring screen | 8 | `RingAppearanceTest` | Read the appearance once into a constant | | | | PLANNED | |
| A5 | D2b: the sheet is in the full matrix | 8 | `ScreenshotTest` for screen 16 | A padding change of 1 dp | | | | PLANNED | |
| A6 | D2c: the values are the specification's | 7 | `PalettesTest`, `NoColourLiteralTest` | Change one value; add a literal to a composable | | | | PLANNED | |
| A7 | D3: contrast for every appearance, palette and pair; a missing pair fails | 7 | `ContrastTest` and its two completeness tests | Lighten `onMuted`; add a role with no pair; delete a pair | | | | PLANNED | |
| A8 | D3: state and criticality never by colour alone | 11 | `TodayModelTest` `state is never colour alone` | Tint the Done marker | | | | PLANNED | |
| A9 | D10: exported components are an exact allowlist | 8, 14 | `verifyExportedComponents` and its self test | Compare in one direction | | | | PLANNED | |
| A10 | Decision a: criticality is read from the occurrence | 2 | `CriticalitySourceTest` (six cases), `verifyCriticalityFromOccurrence` | Revert each site to the template in turn | | | | PLANNED | |
| A11 | Schema version 6, forward from every prior version, on both drivers | 1 | `SchemaV6Test`, `AndroidSchemaTest` twins | Remove the criticality fill; drop `WITHDRAWN` from the trigger; delete the trigger's recreation; make the index non partial | | | | PLANNED | P2-4 |
| A12 | Decision c: a screen follows a change made elsewhere, and corruption ends the process | 8, 11 | `DataChangesTest`, `TodayModelTest` | Notify before commit; catch the exception in the model | | | | PLANNED | |
| A13 | Decision a: an edit is never counted as a reliability failure | 4 | `ReliabilityReaderTest` `an edit raises no never fired rung` | Remove `WITHDRAWN` from the closing set | | | | PLANNED | |
