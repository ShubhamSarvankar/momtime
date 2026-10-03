# CLAUDE.md

Working rules for MomTime. Everything here is non negotiable. If a task appears to require violating something in this file, stop and raise it rather than working around it.

Read `docs/ARCHITECTURE.md` for technical detail and `docs/IMPLEMENTATION_PLAN.md` for phase scope. This file wins over both if they ever conflict.

---

## What this app is

A medication, supplement, hydration and routine adherence app for pregnant and postpartum women. Android only in v1.

The product is one thing: **a reminder fires within 60 seconds of its scheduled time, on a mid range Android phone, in Doze, with the app killed, offline.** Everything else is an accessory. When a tradeoff arises between delivery reliability and any other concern, reliability wins.

The first user is a real pregnant woman, not a demo. A missed critical medication reminder is a real harm, not a bug report.

---

## Hard architectural invariants

These are mechanically checkable and CI enforces several of them. A pull request violating any of them is wrong regardless of whether it works.

1. **No `android.*` imports, no `Context`, no platform types under `shared/`.** It must compile and test on the JVM with no Android dependency. CI rejects violations.
2. **No schema change without a migration file and a forward migration test from every prior version.** Agent built projects of this size do not fail from bad code; they fail from a schema that quietly mutates across sessions until migrations are impossible.
3. **Occurrence state transitions happen only in the domain layer.** No Activity, Composable, Service, Receiver, Worker, or server route mutates occurrence state directly. They dispatch events; the domain decides.
4. **The event log is append only, at the domain layer.** No domain code path issues an update or delete against an event as part of a state transition. Nothing mutates a counter. Adherence, aggregates and reports are reductions over the log. This governs operational mutation only: retention expiry, caregiver revocation and user-initiated erasure are administrative paths that sit outside the domain layer entirely, and they may hard delete rows. The device log is the record of authority; a server-side row is a mirror, so purging a mirror row loses nothing, and deleting data at the user's request is supposed to remove it from aggregates. No tombstones. Because a hard-deleted event's id was also its idempotency key, the server enforces a retention watermark: an outbox retry for an event older than the watermark is acknowledged and discarded, never re-inserted, and device outbox entries expire at the same boundary.
5. **Platform capability differences live only in delivery and presentation.** They never appear in the schema, the domain vocabulary, the event log, or the escalation engine. If a change adds a column or an event type to support one platform's UI, it is wrong.
6. **The escalation engine emits a declarative list of rungs.** It does not schedule, does not take callbacks, and does not know what a platform will do with the list.
7. **No new dependency without explicit approval.** Ask. Do not add a library to solve a problem that fifty lines of Kotlin solves.
8. **No `Clock.System` call outside the DI module.** Every time dependent code takes an injected `Clock`. This is what makes the test suite deterministic. Mechanically enforced in CI the same way as invariant 1: a Gradle task (`verifyNoClockSystem` in `shared/build.gradle.kts`) scans `shared/` for `Clock.System` outside `com.momtime.shared.di`, wired into `check` with its own fixture-based self-test.
9. **Never store a formatted local time.** Store epoch millis plus an IANA zone id. A wall clock intent and an instant are different things.
10. **`PendingIntent` request codes derive from the `alarmSlot` monotonic column, never from a hash of ids.** A hash collision silently cancels an alarm and will not reproduce on a bench.
11. **No PII in any log**, client or server. No medicine names, no notes, no doctor instructions, no weight, no identifiers.
12. **No production credentials in the repo or in the development environment.** Local Postgres and a fake FCM sender for development.

---

## Product rules that are also safety rules

These are not preferences. They exist because the user is pregnant, because the app touches medication, or because a store will reject the build.

**Never reason about medication.** The app records what the doctor prescribed and what she did. It never calculates, suggests, adjusts, validates or interprets a dose. App Store guideline 1.4.2 restricts drug dosage calculators to manufacturer, hospital, university, insurer or regulatory origin.

**`doctorInstructions` is never parsed.** It is free text, stored and displayed verbatim. No code reads it, extracts from it, matches against it, or branches on its contents.

**No AI or LLM anywhere in this product.** Not for coaching, not for suggestions, not for parsing, not for translation at runtime. This was considered and rejected. Do not reintroduce it as a convenience.

**No nutrient quantities.** Nutrition tracking counts servings. "14 fruit servings this week" is a record. "62% of your daily iron" is a nutritional claim that needs a cited source and is unsupportable anyway, since the app knows neither dosage nor absorption. No targets, no percentages of any recommended intake, no good or bad colour coding.

**The app never derives anything from weight.** No BMI, no gain rate, no target range, no range bands on the chart, no "on track" language, no colour coding, no notification, no badge, no streak, no prompt to weigh herself. The chart plots her data points against gestational week and nothing else. The purpose is that she can hand it to her obstetrician.

**No punitive streaks.** No consecutive day counter that resets to zero. Use "days in the last 30 where all critical tasks were completed". A streak cliff is a guilt mechanism aimed at a woman who missed a dose because she was vomiting.

**No physical exertion missions, ever.** No squats, steps, shaking, walking. She may be in her third trimester. Also no math and no typing missions. Missions are barcode scan or photo match only, and every mission has a bypass path.

**Missions default to off.** `MissionConfig.None` is the default on every template. Missions are an opt in per template setting for users who want them, not a mode and not a default. Most templates will never have one.

**Adherence is never a single number that hides skips.** Report completed, missed and skipped as three separate figures. A doctor can use three numbers; a percentage that conceals skips is worse than nothing.

**The interruption budget is a product requirement.** Twelve occurrences a day with an unbounded escalation ladder is how this app gets uninstalled in a week. `CRITICAL` is never budget limited; everything else is.

**Caregivers are read only, always.** There is no caregiver write path anywhere in the codebase. No caregiver marks a task done on her behalf. Revocation hard deletes that link's mirrored server data; it is never a soft flag.

**Caregiver safety notifications are never paywalled.** There is no paywall in v1. If one is ever added, missed dose alerts stay outside it.

**No advertising anywhere.** Not in the app, and emphatically not on the alarm screen. Out of context ad placement and ads that interfere with system notifications are named Play violations with escalating enforcement, and an ad on a medication alert undermines the only thing the screen exists to do.

**No monetization in v1 at all.** No billing code, no paywall, no premium tier, no upsell.

**Health content carries citations.** Any table holding health or wellness guidance has non null `sourceName` and `sourceUrl` from schema version 1, even though v1 ships no such content. App Store guideline 1.4.1 requires cited, user reachable sources for health information, and this is what makes it satisfiable later without a migration.

---

## Alarm subsystem rules

The critical path. Be conservative here.

- `setAlarmClock` is the primary mechanism. `setExactAndAllowWhileIdle` is a Tier 2 fallback only; it is throttled to roughly one fire per app per nine minutes in deep Doze and cannot sustain a five minute ladder.
- **One alarm armed at a time**, re armed on each fire, plus a `WorkManager` watchdog at the 15 minute floor that verifies and repairs it. Never pre arm a week of alarms. Samsung is reported to cap scheduled alarms per app around 500, and twelve occurrences with a four rung ladder is 48 a day.
- **Boot never rings.** A `BOOT_COMPLETED` receiver on Android 15+ cannot start a `mediaPlayback` foreground service and throws `ForegroundServiceStartNotAllowedException`. Boot reschedules via WorkManager only. A missed occurrence fires late only inside the 30 minute catch up window, then goes to `MISSED` silently.
- **Always check `canUseFullScreenIntent()` before relying on it.** On Android 14+ it is granted by default only to calling and alarm apps. Degrade to the Tier 2 path, never crash. A `SecurityException` here is the most common failure mode in this app category.
- **Always handle the absence of exact alarm permission.** Declare `USE_EXACT_ALARM`, keep `SCHEDULE_EXACT_ALARM` as fallback, and handle neither being available.
- `SYSTEM_ALERT_WINDOW` is a secondary route for background activity start, not the ringing mechanism. Android 15 restricts starting foreground services while holding it.
- **Capability is resolved at runtime, never inferred from OS version.** An Android 15 device with full screen intent revoked behaves worse than an Android 11 device with everything granted.
- **Dismissing the alert is not completion.** Completion is an event written by the app. If the alert is stopped without acknowledgement, the next rung fires as scheduled.
- Audio uses `USAGE_ALARM` and `CATEGORY_ALARM`. Do not request notification policy access.
- Every alarm fire records telemetry: scheduled versus actual, resolved tier, screen on, audio focus, battery and Doze state, watchdog repairs. This is both the reliability feature and the test harness.

---

## Android is maximal

Do not withhold an Android feature because iOS cannot match it. The iOS constraints in `ARCHITECTURE.md` section 12 are about keeping `shared` portable, not about limiting Android.

Build freely on Android: a rich ring screen with mission gated dismissal, volume ramp, backup louder sound, widgets, quick settings tiles, per template vibration patterns, OEM setting deep links, the reliability score. None of it touches `shared`.

The leak to guard against runs the other way: Android specifics must not reach the schema or the domain vocabulary.

---

## Localisation

- English, Hindi, Marathi. Every user facing string externalised from the first commit.
- Noto Sans Devanagari bundled. Never rely on system font fallback.
- `plurals` resources always. Hindi and Marathi have their own plural categories. Never build strings by concatenation.
- Never translate user entered data. Medicine names and notes stay verbatim in whatever script she typed.
- Every screen must render correctly at 200% font scale in all three locales. Buttons and the ring screen wrap; they never truncate. No fixed width label containers. Screenshot tests are a release gate.
- Health adjacent copy in Hindi and Marathi requires human review before release. Do not machine translate medical phrasing.

---

## Testing

**Understand what you can and cannot verify, and never blur the line.**

You can fully verify: the shared module, the server, the alarm scheduling adapter via Robolectric and `ShadowAlarmManager`, and UI rendering via Roborazzi screenshots. Own these end to end.

You cannot verify without a device: real Doze cadence over hours, One UI Sleeping apps behaviour, audio through a real speaker over Do Not Disturb, the screen actually turning on from locked, timing drift under real thermal and battery conditions, Samsung's alarm count behaviour. These six are enumerated in `docs/MANUAL_CHECKS.md`.

**Do not mark the alarm subsystem complete.** Report it as passing all automated layers with device checks outstanding. Never state or imply that device behaviour has been validated. Do not write an instrumented test against a mock and present it as device verification.

**The standard to aim at:** a device run should confirm what the automated suites already proved, rather than discover new bugs. Push automated coverage until that is true. When something cannot be covered, say so explicitly and add it to `MANUAL_CHECKS.md` rather than writing a test that appears to cover it.

**Write the hard scenarios, not the easy ones.** The golden scenarios listed in `IMPLEMENTATION_PLAN.md` Phase 1 are mandatory and named, and any deferral must be a named exit criterion of its owning phase. The list lives there only. Test the edges: terminal occurrence immutability under template edits, permission revocation mid schedule, snooze collisions, grace expiry while closed, backfill after confirmed miss, double materialisation, alarm slot collisions.

**Every test that asserts an invariant must be mutation-checked.** Before relying on such a test, deliberately break the code it guards and confirm the test fails, then restore the code. A green test that cannot fail verifies nothing, and coverage numbers will not reveal it. This has already happened three times in this project, each caught only by asking whether the test could fail: the migration check that was a tautology because it regenerated its own baseline (ADR 0035); the scenario 12 property test whose interleaved edit was `setActive(true)`, a no-op; and the same test again, which stayed green with the engine's `filterNot` removed because `INSERT OR IGNORE` silently absorbed the duplicate. State in the PR which mutation was tried. The same failure has also happened outside tests, where a check or a report looked done and applied to nothing: the coverage exclusion for generated code named the wrong package, matched no class, and inflated line coverage for all of Phase 1 (ADR 0039); the Phase 1 status text reported scenarios 7, 14 and 17 as passing when they were deferred, until it was rewritten to 16 of 19; and stacked PRs were reported merged when #7 and #8 had merged only into their base branches and never reached `main`. Two more, found in Phase 1 close-out (ADR 0040). The required property "adherence figures are invariant to reconciliation timing" held by construction: the reduction counted the occurrence state column and read neither `effectiveAt` nor `deviceTimestamp`, so a property that varies reconciliation timing could not fail on it. The only test carrying the name asserted `Reconcile`'s output and never called a reduction. Scenario 5 had then been counted among the passing scenarios with its second case (identical figures after reconciling at expiry, an hour later and a day later) never asserted. Mutate the code that consumes the variable you are varying, not only the code that produces it, and check that the generator can actually reach the case that distinguishes the two behaviours (here, reconcile delays that cross `asOf`). This applies with most force to the alarm adapter: a mocked `AlarmManager` will let a test pass against a scheduler that never schedules, so each Robolectric test must fail when the scheduling call it claims to cover is removed.

Coverage targets: `shared` is gated per package on branch coverage, enforced in CI by `verifyBranchCoverage` (ADR 0038, ADR 0039): engine 95%, domain 85%, data 85%. Line coverage over `shared` stays above 90% as a secondary gate. Server above 85%. The alarm adapter is measured by scenario coverage against the Robolectric list, not by percentage. Coverage is the floor, not the evidence: the mutation checks are the evidence. A gate must fail closed: it fails if its report is missing, if a gated package is absent, and if any exclusion matches no class.

---

## Documentation discipline

`docs/` is public and must be accurate at every commit, because the GitHub repo is part of why this project exists.

- Every decision that closes off an alternative gets an ADR in `docs/adr/`. Numbered, dated, recording the decision, alternatives considered, and rationale. ADRs are never edited once accepted; supersede them with new ones.
- Update `ARCHITECTURE.md` in the same commit as the code that changes it, never after.
- Update `IMPLEMENTATION_PLAN.md` phase status as phases complete.
- `MANUAL_CHECKS.md` records what was checked, on what device, on what date, with what result.
- If you discover something that contradicts this file or `ARCHITECTURE.md`, do not silently work around it. Raise it, and write an ADR if the decision changes.

---

## Working style

- **Scope work to a phase, never to the app.** Read the phase in `IMPLEMENTATION_PLAN.md` and stay inside it.
- **Stop and ask when a decision is genuinely open.** A wrong guess encoded in the schema is expensive. A question is cheap.
- **Do not expand scope.** Everything in Phase 9 is deferred deliberately. If a feature seems obviously missing, it is probably in Phase 9 or permanently excluded, and both are recorded.
- **Prefer boring.** This project's value is in reliability engineering, not in novel technique. A durable table sweep beats a clever in memory scheduler. Fifty lines of Kotlin beat a new dependency.
- **Be accurate about what you have done.** Do not describe a phase as complete when its device gate is outstanding. Do not describe tests as proving something they do not reach. The person reading your report is going to hand this app to a pregnant woman who will rely on it.
