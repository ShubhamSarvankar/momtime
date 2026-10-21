# Review handoff

The working state of the review chat that directs this project. A new review chat reads this first, then the documents it names. Kept current by the implementing session: **every pull request updates "Current state", "Next" and the PR table here, in the same pull request.** Where this file and the code or another document disagree, the code and the other document win.

---

## The product

An Android medication, supplement, hydration and routine adherence app for pregnant and postpartum women. The product is one thing: **a reminder fires within 60 seconds of its scheduled time, on a mid range Android phone, in Doze, with the app killed, offline.** SLO for v1: 99.5% of critical occurrences delivered within 60 seconds, measured on device, on Tier 3 devices.

## How the project is run

- The **review chat** gives technical direction and reviews: architecture, scope, schema, product semantics, reviewing every agent report, and writing the prompt for each Claude Code session. It does not decide anything that needs a real device, or the production release.
- The **implementing session** (Claude Code) builds one pull request per session from `main`, never stacked, never pushes to `main`, never merges. The owner merges with a merge commit.
- The loop: the agent reports, the review chat finds what is wrong or missing, the review chat writes the next prompt as one paste ready block.
- Decisions of the review chat are recorded in the repo as "by Claude (technical review)". The agent's own judgments are labelled "for review"; a judgment that reaches `main` in a merged pull request was accepted in its review.
- **One stage at a time:** the next prompt is written only after the current run is confirmed complete and merged.

## Where the detail lives

- `CLAUDE.md`: invariants, alarm rules, testing and mutation hygiene, precedents. Wins over everything below.
- `docs/ARCHITECTURE.md`; `docs/IMPLEMENTATION_PLAN.md` (Open items, Working conventions); `docs/MANUAL_CHECKS.md` (device rows P2-1 to P2-41, P3-1 to P3-8).
- `docs/phase-3-plan.md`: the accepted Phase 3 plan, one section per pull request (scope, files, tests with subject, assertion and mutation, STOP points, imports, docs), plus the audit of absence assertions and the dependency list. **Each pull request corrects its own section to match what was built, with a one line note.**
- `docs/phase-3-design.md`: the visual spec. Anything not in it is a design STOP.
- `docs/phase-3-traceability.md`: every Phase 3 requirement mapped to its test, mutation, observed failure and SHA.
- `docs/adr/`: through 0090, immutable once merged. PR 6 wrote none: its readings of ADR 0080 are recorded in the plan's PR 6 section and in `ARCHITECTURE.md` 5.7, for review.

## Current state

- **Phase 2:** automated layers closed (#25). Complete only when the Phase 7 device checks pass.
- **Phase 3 plan:** #26, merged. ADRs 0073 to 0090 accepted.
- **PR 1, `phase-3/schema-v6`:** #28, merged. Schema version 6.
- **PR 2, `phase-3/occurrence-criticality`:** #29, merged. Every behaviour reads `occurrence.criticality`; `verifyCriticalityFromOccurrence` with nine fixtures and its name rule's blind spot stated; Gentle is a notification only (ADR 0089). Twelve Phase 3 expectation changes so far (PR 1 three, PR 2 five, PR 3 two, PR 6 two), each authorised.
- **PR 2b, `phase-3/ring-limit`:** #31, merged. `RingPolicy.MAX_RING` 4 minutes, a placeholder; the cap and "Stop the sound" are one dismissal that keeps her notification with its actions and writes nothing; the ring session keeps an occurrence listed after its sound stops, and a repeat after the sound stopped rings again and spends again; `dropClosed` after each of the four `Reconcile` sites; `fire_telemetry.ring_ended_by_cap`, android store version 6, a count in the report and the export. No expectation change (still eight); the store migration tests' version literal moved to 6 as the section specified, a fixture change, not counted. Close out: the ring screen opened from the kept notification on a silent session is tested (traceability R-22); every reader of the session's `isActive` needs "the sound is on" (listed in the pull request). `ReliabilityExport.SCHEMA_VERSION` stays 1, decided in the close out (an added field, no reader outside the device before Phase 5); when the version changes in general is an Open items row in `IMPLEMENTATION_PLAN.md` for Phase 5. One STOP in PR 2b's close out (when `ReliabilityExport.SCHEMA_VERSION` changes), answered there by Claude (technical review).
- **PR 3, `phase-3/template-edit`:** #32, merged. `EditTemplateCommand` (ADR 0079 rules 1 to 7 and 9) and `CreateTemplateCommand` in `shared`: the only way a template changes after it is created, in one transaction; `setActive` and `findByTemplateAndDate` are gone; open occurrences the template no longer wants become `WITHDRAWN`, a wanted one that has come due is untouched, one that has not takes the new time and criticality unless the new instant is before now. Golden scenarios 1 and 12 are real (`TemplateEditScenarioTest`, the rebuilt property test). Not registered in Koin: PR 4 registers them in `ArmingModule` beside `TimeZoneChangeCommand`. Two Phase 1 or Phase 2 expectations changed (the Seeder's, and scenario 12's assertion that a materialised row's instant never changes, replaced by the plan's assertions 1 to 4 when the property test was rebuilt; both authorised), so ten in all; the tests deleted with `findByTemplateAndDate` are tests removed with their subject, not expectation changes. Nothing in `android/src/main` changed.

- **PR 4, `phase-3/edit-pass`:** #33, merged. **Built on Sonnet** (in a session continued from PR 3, although the plan assigned it to Fable); **its alarm path judgment table was reviewed cell by cell and completed on Fable in its close out.** `TemplateEditPass`: the edit command, the window's materialisation, `Reconcile`, the ring session and notifications dropping what was withdrawn (`SessionSweep.withdrawn`: `dropClosed` plus each withdrawn occurrence's own slot notification, which `dropClosed` cannot reach outside the session), the session's text following the edited template at once (`SessionSweep.edited`, added in the close out: `ARCHITECTURE.md` 3.2 says title, dosage and instructions change at once, and the built table had them showing from the next post), then `ensureArmed`, inside the coordinator's exclusion; `EditTemplateCommand`, `CreateTemplateCommand` and the pass are registered in `ArmingModule` on the id source `ReconcileCommand` uses; `FireTiming` treats `WITHDRAWN` before the rung as it treats `COMPLETED` and `SKIPPED`, and the section's STOP (a change to the pairing rule) did not arise. Tests: `EditPassTest` (twelve, among them the close out's `deactivating a snoozed reminder cancels its snooze alarm` and the two runs of `an edit of the dosage ... reaches the ring screen and the notification at once`), `ReliabilityReaderTest` `an edit raises no never fired rung`, `TemplateEditRaceTest`, `SeederTest` `the test reminder is active, one off in effect, and rings`, each with its mutation. The Gentle to Critical test was rebuilt so that its mutations can fail (today's occurrence is come due and unfired). No expectation change (still ten). Nothing is a device check yet: `MANUAL_CHECKS.md` P3-7 needs PR 11's editor. One STOP from the close out, answered by Claude (technical review): a notification in no session (Tier 1 plain, Gentle, a silent notice) takes the edited text too, as `ARCHITECTURE.md` 3.2 says; deferred to PR 6, which owns those notifications' shape (ADR 0080), as an Open items row in `IMPLEMENTATION_PLAN.md` (needed before PR 11's editor, the first thing that can edit a template). The close out also folded `RingController`'s private `dismiss` into `stopSound` (detekt's function limit); the PR 2b mutations whose site that was were run again on the folded code (traceability, PR 4 (h)).

- **PR 5, `phase-3/reductions`:** #34, merged. Built on Sonnet, in `shared` only. `NutritionReduction.servings` (servings per tag per scheduled date, from the tags each completion event carries, on the outcome rule adherence uses: `EventLogReduction.deciding`, shared and not copied, no adherence test changed), `WaterReduction.totals` (each glass on the date it had in the zone it was logged in, a caller supplied fallback zone for a row with none), `LogWaterCommand` (the only writer of `WATER_LOGGED`, 250 ml, the zone recorded), `GestationalAge.weekAt(dueDate, onDate)` (floors; pure, so `ARCHITECTURE.md` 3.5 now says the `asOf` belongs to `dueDateRevisionAsOf`, which the caller reads the due date with: an unwritten ADR for review) and `EventRepository.findWaterInRange(from, to)` (local dates, widened by 14 hours each side, because a glass's date is decided by its own zone). 19 new tests, 35 mutation runs, each failing its test on an assertion (traceability RD-1 to RD-21, X3, X4). Differences from the section, all for review and in the pull request: the section's mutation "read `template.nutritionTags`" cannot be written (the reduction takes no template), so its analogue was used; the "latest revision always" mutation is made in `dueDateRevisionAsOf`; a first form of `findWaterInRange` sorted its result and no test could fail the sort, so the sort was removed. No Koin registration: the water screen (PR 12) and the widget (PR 14) are the first callers. No expectation change (still ten). Nothing is a device check.

- **PR 6, `phase-3/notifications`:** #PRNUMBER, merged. Built on Fable. ADR 0080: the ring session spreads when a second occurrence is listed (`RingSessions.isSpread`, cleared when nothing is listed, so the buttons never move back and a ring that starts again after the cap finds the per occurrence notifications where they were); each listed occurrence has a notification of its own under its slot (`RingNotifications.occurrence`: its criticality's channel, exactly its offered actions, no full screen intent, no delete intent, not ongoing, not auto cancelled, only alert once) and the session's notification carries no buttons and is only alert once; `AndroidDeliveryPort.present` decides what a fire leaves under the slots (a lone ring cancels the slot's earlier notification, so a silent one under quiet hours and the session's never both carry the actions; the spread posts every listed occurrence's own; a joiner gets its own; a listed occurrence that rings again gets its own back only if she swiped it); `SlotNotifications` reposts a notification under a slot in the shape it has and only if the platform still lists it, for a refused action and for an edit. **The Open items row deferred from PR 4 is closed:** an edit reaches every notification that is still showing (a per occurrence one, Tier 1 plain, Gentle, a silent notice) and brings back none she dismissed. Tests: `SpreadTest`, `SpreadRefreshTest` (over `SpreadSupport`), the two occurrence case of `RingLimitTest` `her actions stay after the cap` run again, `EditPassTest` `deactivating one of two ringing leaves the other` changed; 29 mutation runs (traceability N-1 to N-15, A25's PR 6 part, L9). **Two Phase 3 expectation changes, so twelve in all:** PR 2b's two occurrence case (decision 2, authorised) and PR 4's `deactivating one of two ringing leaves the other` (decision 3's category, which named Phase 2 tests; for review). Readings marked for review rather than raised as STOPs, each with its decision: (a) a per occurrence notification's first post alerts as its channel says while the ringer plays, which ADR 0080 item 5 decides is not prevented (P3-4); (b) a swiped per occurrence notification is accepted as gone until its next ring (ADR 0066's no delete intent, decision 2's not ongoing, decision 1's not brought back). The judgment table (the notification set through every transition) is in the pull request's description. Nothing is a device check yet: P3-2 to P3-5 are written in full and wait for the seed build (two reminders by hand) and PR 11's editor.

## Next

PR 7, `phase-3/ui-foundation`, on Sonnet. Confirm `main` is green first. Shubham makes `android-screenshots` a required check on `main` when PR 7 merges (the Open items row); until then green means every check on the pull request head, that job included.

## PR sequence

| PR | Branch | Model | Status |
|---|---|---|---|
| 1 | `phase-3/schema-v6` | Sonnet | Merged (#28) |
| 2 | `phase-3/occurrence-criticality` | Fable | Merged (#29) |
| 2b | `phase-3/ring-limit` | Fable | Merged (#31) |
| 3 | `phase-3/template-edit` | Sonnet | Merged (#32) |
| 4 | `phase-3/edit-pass` | Planned Fable; built on Sonnet, judgment reviewed and completed on Fable in the close out | Merged (#33) |
| 5 | `phase-3/reductions` | Sonnet | Merged (#34) |
| 6 | `phase-3/notifications` | Fable | Merged (#PRNUMBER). Closed the Open items row deferred from PR 4's close out |
| 7 | `phase-3/ui-foundation` | Sonnet | Next |
| 8 | `phase-3/app-shell` | Sonnet | |
| 9 | `phase-3/strings-pipeline` | Sonnet | |
| 10 | `phase-3/onboarding` | Sonnet | |
| 11 | `phase-3/today-and-reminders` | Sonnet | |
| 12 | `phase-3/water-nutrition-overview` | Sonnet | |
| 13 | `phase-3/settings-and-reliability` | Sonnet | |
| 14 | `phase-3/widget-tile-nudges` | Sonnet | |
| 15 | `phase-3/accessibility-and-freeze` | Sonnet | |
| 16 | `phase-3/strings-import` | Sonnet | Gated on the human translations |
| 17 | `phase-3/close` | Sonnet | |

Each pull request runs in a fresh Claude Code session on the model shown. A short close out session on the same model may follow a pull request to answer the review before merge.

## Phase 3 decisions, do not reopen

The ADRs and `phase-3-plan.md` hold the detail. In brief:

- **Appearance:** System (default), Light, Dark, Pastel (light only, five palettes). A top bar control opens one sheet that applies a choice at once. No themes page, no dynamic colour. Appearances differ in colour only.
- **Colour:** roles in one Kotlin model, drawn only as declared `RolePair`s (`verifyColourBoundary`); `ContrastTest` on unrounded ratios (4.5, 3, 7 on the ring screen); no state by colour alone; nothing coloured good or bad.
- **Copy:** cute is visual, never verbal. No praise tied to adherence, no single percentage, no targets, no ads, no health claims. The starter schedule names nothing.
- **Toolkit:** Compose for new screens, the ring screen stays views, a hand rolled back stack, no ViewModel, no coroutines in android.
- **Dependencies:** the list in `phase-3-plan.md` section 4, including the eight Compose and Activity sub modules declared by name. Nothing else without approval.
- **Screenshots:** nine locale and scale combinations in Light plus every other appearance at English 100 percent, recorded at mdpi, only by the `android-screenshots` CI job, downloaded through the MCP. Never commit an image recorded on Windows.
- **Strings:** nothing machine translated; test only Devanagari fixtures in `hi-rXA` and `mr-rXA`; two waves of human translation by CSV.
- **Template edits (ADR 0079):** one command in one transaction; unwanted open occurrences become `WITHDRAWN`; a come due occurrence that is still wanted is untouched; an edit to a time already past keeps today's time and names the date the new time starts; criticality lives on the occurrence.
- **Gentle (ADR 0089):** a notification only.
- **Ring limit (ADR 0090):** `MAX_RING` placeholder 4 minutes; the cap and "Stop the sound" share one dismissal that keeps her notification; a fresh cap per ringing fire; grace ends the ring; a repeat after the sound stopped spends the budget again; default budget 12.
- **Today:** four states from the log; actions through `RingController.act`; snooze not before the reminder's time; "Taken late" through `BackfillCommand`; a "Missed yesterday" group.
- **Per occurrence notifications (ADR 0080, PR 6):** the session spreads at the second occurrence and stays spread until nothing is listed; each occurrence's own notification carries its actions, the session's carries none; a swiped one is accepted as gone until its next ring; an edit reaches every notification that is still showing and brings back none she dismissed; a per occurrence notification's first post alerts as its channel says (P3-4).
- **Elsewhere:** a restored phone opens Today and banners name every missing grant; water nudges are WorkManager work, at most four a day; missions belong to Phase 5; exported components are an exact allowlist.

## Process rules

- Every specified test has a mutation that must fail it: diff shown, run on the final code commit, failing on an assertion (or the throw the plan names), reverted, recorded with its SHA.
- Independent guards each get their own test and mutation.
- An assertion that something is absent includes the step that would produce it if the code were wrong (usually Reconcile after grace).
- A mutation of a shared site fails every case that reads that site, and no other.
- Changing an existing test's expectation, not its fixture, needs explicit authorisation, named in the PR under "Phase N expectations changed".
- Before coding, the agent compares its section against the code and stops on anything that changes behaviour or an assertion.
- Checks are read individually through the MCP after they finish, never inferred from a run's overall result.
- A job cancelled because no runner picked it up: at most two empty commits, then ask the owner to re-run it.
- No conversation or session links anywhere in the repository.

## The per pull request prompt

1. **Before coding:** confirm the previous pull request is merged and `main` is green, each check read after it finished; branch from `main`; read `CLAUDE.md`, `phase-3-plan.md` section 4, section 5's rules and the audit table, the pull request's section, the ADRs it names, and the Working conventions; compare the section against the code and STOP on any difference that changes behaviour or an assertion.
2. **How to work:** the section is the scope; tests as specified (add, never weaken, merge or drop); mutations as above, naming any other test that also failed; one line per specified test on its subject and why the test reaches it; counts read from tool output; STOP means hand back; imports only from the approved list; docs change with code, **including this file**; the cancelled job rule.
3. **Handover:** stop when every check is green. Report the head SHA and every check, test counts with their SHA, the mutation table, one line per test, the before coding comparison, expectation changes, STOPs, and the no links check.
4. **Standing rules:** stay in scope; docs move with code; stop on contradictions; no new dependency; attribute reasoning; never push to `main`, never merge; no conversation or session links; one wait timer at a time.

For a Fable pull request (2, 2b, 4, 6), name the judgment it is Fable's for and ask for it shown in full as a table in the PR description, rather than a STOP per case.

## What the owner provides

Merges; `android-screenshots` as a required check when PR 7 merges; the four Galaxy A15 screenshots before PR 10 ships; the two waves of Hindi and Marathi translation and the review of health adjacent copy (wave 1 at PR 9, wave 2 at PR 15; PR 16 is gated on it); the Play account, App Signing, the upload key backup and 12 testers at the end of Phase 3; the final sounds and the Play policy rows before Phase 7 and Phase 8.

## Constraints

One device, a Galaxy A15 5G on Android 16, One UI 8.5. No Mac or iPhone. Implementation runs on Windows. Claims stay limited to what was measured.
