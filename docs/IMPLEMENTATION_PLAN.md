# MomTime Implementation Plan

Read `ARCHITECTURE.md` for the technical detail behind every item here. Read `CLAUDE.md` before writing any code.

Scope: Android only. Backend included. iOS deliberately excluded and deliberately unblocked.

---

## Sequencing rationale

Phases are ordered by two competing pressures, resolved as follows.

**Verifiability.** The shared module and the server can be fully verified by running tests, with no device. Those phases can be built and confirmed autonomously.

**Risk.** The alarm subsystem is where the product succeeds or fails, so it cannot be left to the end.

These are resolvable because the Robolectric layer (`ShadowAlarmManager`) makes most of the alarm subsystem verifiable on the JVM. So the alarm subsystem lands early, at Phase 2, with its device gate deferred to Phase 7. That is the specific reason Phase 2 sits where it does.

Phase 0 must complete before anything else. Phases 5 and 6 may run in either order. The Play 12 tester clock starts at the end of Phase 3 and runs in parallel with everything after it.

---

## Open items

Outstanding blockers that don't belong to any single code change, tracked here so they aren't lost between sessions.

| Item | Needed at | Owner | Status |
|---|---|---|---|
| GCP project id | Phase 4, first Terraform apply | User | Not yet provided. `infra/` stays `.gitkeep`-only until then; Terraform reads `TF_VAR_gcp_project_id` as a required variable with no default. Do not ask before Phase 4 actually needs it. |
| Firebase project id | Phase 4, Auth/FCM wiring (Phase 2/3 work that touches Firebase at runtime must be structured to build without it, or stop and ask) | User | Not yet provided. `google-services.json` stays gitignored and absent. Terraform reads `TF_VAR_firebase_project_id` as a required variable with no default; docs and Terraform allow it to differ from the GCP project id even though one project is expected to serve both. |
| Exact dependency versions (Kotlin, AGP, Gradle, SQLDelight, Koin, kotlinx-datetime, Detekt, ktlint, Ktor, Robolectric, Roborazzi) | Phase 0 scaffolding | Resolved live against Maven Central and the current AGP/Kotlin compatibility matrix, not guessed | Done. Pinned in `gradle/libs.versions.toml`. Kotlin 2.4.20 / AGP 9.4.1 / Gradle 9.8.0 verified with a real build, not just metadata. |
| Migration-test CI job | Phase 1, as an explicit exit criterion (not discovered later) — see Phase 1 below | Model | Done. Wired as its own named CI job (`migration-test`, `.github/workflows/ci.yml`) running `:shared:verifySqlDelightMigration`. Proven locally: passes at v1 with no migrations, fails on a deliberately mismatched migration with an exact column-level diff, passes again once corrected. |
| Green GitHub Actions run + bad-commit PR check on the real remote | Phase 0, before the phase is considered fully closed | Shubham (repo creation, push, and the PR-based bad-commit demonstration; a local Gradle run proves the task works, not that the workflow YAML is correct on the actual remote) | Not yet done. Phase 1 work may proceed in parallel — see `docs/adr` and this file's Phase 0 section for what's already verified locally. |

---

## Phase 0: Foundation

Nothing else begins until this is done.

**Deliverables**

- Repo with the module layout from `ARCHITECTURE.md` section 2. `shared`, `android`, `server`, `infra`, `docs`.
- `shared` configured for KMP with a `jvm` target only, `commonMain` + `jvmMain` source sets. No `android` target — `android` consumes `shared`'s `jvm` artifact as a plain project dependency (verified by a Phase 0 spike; see `ARCHITECTURE.md` section 2 and ADR 27). An `ios` target is not added yet but the source set layout must not preclude it.
- `README.md` at repo root: what the app is, the reliability SLO, module layout, how to run each test suite, links to `CLAUDE.md`/`ARCHITECTURE.md`/`IMPLEMENTATION_PLAN.md`.
- `docs/adr/` with one short file per decision already made in `ARCHITECTURE.md`, each recording the decision, the alternatives considered, and the rationale. Numbered, dated, never edited once accepted; superseded by new ADRs.
- `CLAUDE.md` at repo root.
- `docs/MANUAL_CHECKS.md`, initially listing the six automation ceiling items with their scripted procedures, marked not yet run.
- Gradle version catalog. Every dependency pinned.
- Detekt and ktlint configured, failing the build on violation.
- GitHub Actions workflow: `shared` JVM tests, migration tests, server tests, Android assemble, lint, Detekt. Runs on every push.
- CI check rejecting any commit that adds an `android.*`/`androidx.*` reference under `shared/`: a custom Gradle task (`verifyNoAndroidImports`) scanning `commonMain`/`jvmMain` by regex, wired into `check` with its own fixture-based test and its own named CI status check, plus Detekt's `ForbiddenImport` rule on a `shared`-scoped config as defense-in-depth. Not a git hook — bypassable with `--no-verify` and doesn't run on forked-repo PRs.
- `.gitignore` covering keystores, `google-services.json`, Terraform state, and local env files.

**Exit criteria.** CI green on an empty project. The import ban check demonstrably fails a deliberately bad commit.

---

## Phase 1: Shared domain and engine

The correctness core. Entirely JVM verifiable. Expect this to be the largest test suite in the project.

**Deliverables**

- Domain models: `ScheduleTemplate`, `Occurrence`, event log types, `Recurrence`, `Criticality`, `TaskType`, `NutritionTag`, `MissionConfig`, `PregnancyPhase`, `DeliveryCapability`, `EscalationRung`, `Channel`.
- SQLDelight schema version 1, including citation fields on the content table and `PregnancyPhase` on the pregnancy table, even though neither is used in v1.
- Migration harness and the rule that every schema change ships a migration plus a forward migration test from every prior version.
- Recurrence expansion engine.
- Occurrence materialisation over a rolling 48 hour window, idempotent, with `alarmSlot` allocation from a monotonic counter.
- Escalation ladder generation as an ordered list of rungs.
- Escalation policy resolution: criticality defaults, quiet hours, interruption budget.
- `Reconcile` domain command: derives `MISSED` for any occurrence whose grace window has expired with no terminal event, writing the event with `effectiveAt` (the computed grace-expiry instant) separate from `deviceTimestamp` (write time). Idempotent — safe to dispatch repeatedly from the watchdog, app foreground, and boot without changing the result. No caller mutates occurrence state directly; they all dispatch this command and the domain decides (invariant 3).
- Event log reduction: adherence figures (read from `effectiveAt`, not `deviceTimestamp`, for any derived event), nutrition tag aggregation, water totals, the 30 day critical completion metric, refill countdown from inventory decrement.
- Repository interfaces plus SQLDelight implementations.
- Injected `Clock` throughout. No call to `Clock.System` outside the DI module.
- Koin modules for `shared`.

**Golden test scenarios.** These are mandatory and must exist as named tests. They are the scenarios most likely to be skipped if left to invention.

1. Recurrence expansion across a template edit that changes `timeOfDay` after some occurrences are already terminal.
2. Reboot part way through an escalation ladder, with the remaining rungs correctly derived.
3. Exact alarm permission revoked mid schedule, with tier downgrade and ladder adjustment.
4. A snooze that would collide with the next occurrence of the same template.
5. A grace window expiring while the app is closed, producing `MISSED` without a ring, where `deviceTimestamp` reflects whenever `Reconcile` runs but `effectiveAt` reflects the true grace-expiry instant, and adherence figures come from `effectiveAt` — so a second case asserting adherence is identical whether reconciliation happens at the exact expiry instant, an hour later, or a day later is part of this scenario, not optional.
6. A backfilled completion arriving after a confirmed miss.
7. A caregiver revocation arriving mid sweep.
8. Timezone travel where the local scheduled time has already passed.
9. `EveryNDays` expansion across a month boundary and across a leap day.
10. Interruption budget exhaustion downgrading `STANDARD` while leaving `CRITICAL` untouched.
11. Two templates producing occurrences at the same instant, confirming distinct `alarmSlot` values.
12. Materialisation run twice over the same window, producing no duplicates.

Seven additional scenarios, added during Phase 0 planning review because they surface edge cases the original twelve don't reach:

13. Uninstall and reinstall re-materialises and re-arms from a fresh `alarmSlot` counter; no stale slot value or `PendingIntent` from the previous install remains to collide with, because uninstall and `MY_PACKAGE_REPLACED` both invalidate prior alarms (see `ARCHITECTURE.md` section 5.4, ADR 31 — this asserts the documented behaviour, it does not probe for a collision that can't occur).
14. Device clock set backward by the user while a ladder is armed does not cause already-fired rungs to re-fire or the next rung to compute a negative delay.
15. Two occurrences' ladders interleave — the one-alarm-at-a-time selection always arms the chronologically next rung across all occurrences, not just the next rung of whichever occurrence is currently being processed.
16. Quiet hours starting partway through an armed `CRITICAL` ladder still rings; a `STANDARD` ladder armed the same way defers the in-window rung to a silent notification.
17. `WorkManager` watchdog finds the correct alarm already armed — a no-op pass that emits no spurious `WATCHDOG_REPAIR` event.
18. App force-stopped by the OS (not the user) mid-ladder — the next watchdog pass within the 15 minute floor repairs the alarm rather than the chain staying silently dead until next app open.
19. Pregnancy phase transition (`PRENATAL` to `POSTPARTUM`) while occurrences are materialised against the old phase does not retroactively rescope or delete history scoped to the ending `pregnancyId`.

**Exit criteria.** All nineteen golden scenarios pass. Migration test passes from v1 to v1 (trivially) and the harness is proven by adding and reverting a throwaway migration. The migration test is wired into the CI workflow as its own job during this phase — not deferred again, per the Open Items entry above. No `android.*` anywhere in `shared`.

---

## Phase 2: Android alarm subsystem

The critical path. Built and verified on the JVM via Robolectric. Device verification is Phase 7.

**Deliverables**

- `AlarmScheduler` interface in `shared`, Android implementation in `android`.
- `setAlarmClock` path, one alarm at a time, re armed on each fire.
- `WorkManager` watchdog at the 15 minute floor, verifying and repairing the next alarm, logging `WATCHDOG_REPAIR`, and dispatching the `Reconcile` command to the domain layer (see Phase 1) so any occurrence whose grace window has expired while the app was closed is derived to `MISSED`.
- `WorkManager` daily materialisation worker, regenerating the rolling 48 hour occurrence window (`ARCHITECTURE.md` section 3.2). Runs daily and on any template edit.
- `BroadcastReceiver` to ringer foreground service (`mediaPlayback`) to full screen intent notification to ring `Activity` with `showWhenLocked` and `turnScreenOn`.
- Ring `Activity` dispatching events to the domain and never mutating occurrence state.
- Overlay (`SYSTEM_ALERT_WINDOW`) secondary path for background activity start, with the Android 15 foreground service restriction handled.
- `DeliveryCapability` resolution: exact alarm availability, `canUseFullScreenIntent()`, battery optimisation status.
- Tier 2 and Tier 1 delivery paths.
- Permission request flows: `USE_EXACT_ALARM` declared, `SCHEDULE_EXACT_ALARM` fallback, full screen intent settings intent, battery optimisation exemption.
- Broadcast handlers: `BOOT_COMPLETED` (reschedule only, never ring, via WorkManager), `MY_PACKAGE_REPLACED`, `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`.
- 30 minute boot catch up window.
- Audio: `USAGE_ALARM`, `CATEGORY_ALARM`, `.wav` assets under 30 seconds, volume ramp, backup louder sound after an unacknowledged interval.
- Notification channels split by criticality.
- Per template vibration patterns.
- Canary: onboarding 60 second test, daily silent canary, per fire telemetry recording scheduled versus actual, tier, screen on, audio focus, battery and Doze state.
- Local telemetry storage, in app reliability view, export. No upload yet.
- Samsung One UI onboarding walkthrough: battery optimisation, Sleeping apps, Deep sleeping apps, Put unused apps to sleep, with deep link attempts and screenshot fallbacks.

**Robolectric test coverage, mandatory**

- Correct alarm scheduled with correct trigger time, type, and request code.
- Request code uniqueness across occurrences and rungs. Deliberate attempt to force a collision must fail to collide.
- Re arm on fire produces exactly the next pending rung.
- Watchdog detects a manually cleared alarm and repairs it.
- Boot reschedules without starting a ringer service.
- Boot outside the catch up window transitions to `MISSED` without ringing.
- `SecurityException` on `setAlarmClock` degrades to Tier 2 rather than crashing.
- `canUseFullScreenIntent()` false routes to the Tier 2 path.
- Tier resolution for each permission combination.
- Acknowledge, snooze and skip from the ring screen each write the correct event and nothing else.

**Exit criteria.** Full Robolectric suite green. `MANUAL_CHECKS.md` updated with what remains unverifiable and why. **Claude Code does not mark this phase complete in any human facing sense; it is complete when Phase 7 device checks pass.**

---

## Phase 3: Android UI and localisation

**Deliverables**

- Onboarding: due date, one medicine, permission walkthrough, canary test. Nothing else. Everything else is deferred to first use.
- Starter schedule offered as an editable suggestion, clearly labelled as a suggestion to edit and not a prescription.
- Today view with four state display.
- Schedule builder: create and edit templates, criticality, recurrence, nutrition tags, mission config, dosage, doctor instructions, inventory and refill threshold.
- Water: one tap glass logging, goal configuration, progress display, optional nudge schedule.
- Nutrition aggregation view: tag counts per day and per week. Counts only. No targets, no colour coding for good or bad.
- Dashboard: today's completed, missed, skipped as three figures, water progress, gestational week, days to due date, 30 day critical completion metric.
- Settings: quiet hours, interruption budget, snooze duration, per channel preferences, locale override, telemetry opt in.
- Reliability view surfacing canary results and the device fix path banner.
- Dark mode.
- Home screen widget and quick settings tile for one tap water logging.
- Full localisation: English, Hindi, Marathi. Noto Sans Devanagari bundled, per locale line heights, `plurals` resources, no string concatenation.
- Roborazzi screenshot tests: every screen, three locales, 100% and 200% font scale, diffed against goldens.
- Accessibility pass: content descriptions, touch target sizes, large text throughout.

**Exit criteria.** Screenshot suite green across all nine locale and scale combinations with no clipping, truncation or overflow. Human review of all health adjacent copy in Hindi and Marathi complete.

**Start the Play 12 tester clock here.** Register the developer account, set up Play App Signing, back up the upload key outside the repo, and get 12 testers opted in. Fourteen continuous days of wall clock time that cannot be compressed and should not be serialised after the build.

---

## Phase 4: Backend

Fully JVM verifiable.

**Deliverables**

- Ktor service depending on the `shared` jvm target. It uses the same recurrence and escalation code as the app. No reimplementation.
- Postgres schema (Cloud SQL) for users, pregnancies, expected occurrences (including `sweep_status`: `AWAITING`, `SATISFIED`, `MISSED_CONFIRMED`, `SUPERSEDED`), events, caregiver links, and scopes.
- Outbox ingest endpoint: idempotent on client generated event UUID, unique index enforced at the database level, at least once delivery tolerated. Rejects/discards writes older than the retention watermark (`ARCHITECTURE.md` section 6.5) instead of re-inserting them.
- Expected occurrence upload for a rolling 48 hour horizon.
- Sweep endpoint, invoked every 5 minutes by Cloud Scheduler. Durable table scan, no in memory timers. Transitions overdue occurrences from `sweep_status = AWAITING` to `MISSED_CONFIRMED` and writes `CAREGIVER_NOTIFIED` back to the device's event stream once the caregiver is actually notified.
- FCM dispatch with per caregiver notification policy: per event for `CRITICAL`, digest otherwise, or off.
- Clock authority: `serverReceivedAt` set server side, server clock authoritative for the dead man switch, skew detection reported back to the device.
- 90 day retention job with hard delete, plus the retention watermark that makes late outbox retries against purged events a no-op instead of a re-insert.
- Firebase Auth token verification.
- Structured logging with no PII, OpenTelemetry traces.
- Connection pooling for Cloud Run's scale-to-zero compute against Cloud SQL's fixed `max_connections`: pool sized to `max_connections / max_instances`, `max_instances` capped at 3, via the Cloud SQL connector. Its own ADR (28).
- Terraform for the whole stack: Cloud Run in `asia-south1` with min instances 0, Cloud SQL for PostgreSQL, Cloud Scheduler, Artifact Registry, IAM. Reads `TF_VAR_gcp_project_id` and `TF_VAR_firebase_project_id` as required variables with no default — see Open Items below.
- GitHub Actions deploy via Workload Identity Federation. No long lived service account keys anywhere.
- Local development: containerised Postgres, fake FCM sender, Firebase Local Emulator Suite's Auth emulator (server points at it via `FIREBASE_AUTH_EMULATOR_HOST` and verifies emulator-issued tokens). No production credentials reachable by the development environment.

**Test coverage**

- Duplicate event ingest with the same UUID is a no op.
- Out of order event arrival resolves correctly.
- Sweep with a completion event that arrived after the deadline but before the sweep.
- Sweep with total device silence produces `missed_confirmed`.
- Sweep idempotency: running twice produces one notification.
- Device clock skew in both directions.
- Retention job boundary behaviour at exactly 90 days.

**Exit criteria.** Full server suite green. `terraform apply` from a bare GCP project produces a working environment. Deploy pipeline runs keylessly.

---

## Phase 5: Caregiver

**Deliverables**

- Account creation, required only at this point and not before.
- Invite code generation, short lived, single use.
- Caregiver join flow and link establishment, maximum 3 links.
- Per link field scope toggles. Default visible: title, scheduled time, state. Default hidden: weight, notes, doctor instructions.
- Per link notification policy controlled by her: per event `CRITICAL`, digest, or off.
- Pause sharing for a configurable period.
- Revocation: immediate, hard deleting that link's server side mirrored data.
- Caregiver dashboard as a screen in the same app behind a role flag. Read only, with no write path at all.
- Verified, self reported and bypassed completions displayed distinctly. This distinction is the entire justification for the mission feature.
- Outbox sync wiring from device to server.
- Telemetry upload, gated on the opt in from Phase 2.

**Exit criteria.** Revocation verified to remove server side data, not flag it. Caregiver role demonstrably has no write path. Sync survives extended offline periods and replays without duplication.

---

## Phase 6: Reports, weight and export

**Deliverables**

- Weight logging: integer grams, kilogram display to one decimal, user initiated only, never prompted.
- Weight chart against gestational week. Data points only. No target line, no range bands, no colour zones, no commentary anywhere in the app.
- Per entry and full log deletion, not touching adherence history.
- PDF report generation, landscape A4, printable from the phone, populated from real collected data: adherence figures, nutrition tag counts, water history, weight chart, event detail.
- CSV export of the event log.
- Postpartum phase transition UI.
- Multiple pregnancy record support and history scoping.
- Refill reminders from inventory decrement.

**Exit criteria.** PDF renders correctly in all three locales. Export round trips. Phase transition does not corrupt or rescope historical data.

---

## Phase 7: Device verification

The gate Phase 2 defers to. Structured so that most of it is passive.

### The automation ceiling

Six things cannot be proven without real hardware and real time. These are the only items that require a device, and each has a scripted procedure recorded in `docs/MANUAL_CHECKS.md`.

1. Whether One UI's Sleeping apps and Deep sleeping apps behaviour kills alarms after days of low use.
2. Real Doze maintenance window cadence over multi hour spans. `dumpsys deviceidle force-idle` is a simulation, not the real cadence.
3. Audio actually audible at correct volume through the real speaker, over Do Not Disturb.
4. Full screen intent actually turning the screen on from locked, on real hardware.
5. Timing drift under real thermal and battery conditions.
6. Samsung's scheduled alarm count behaviour, if ever exceeded.

### Making them passive

The canary from Phase 2 already records the evidence for items 1 through 5 during normal use. So the device check is reading a report, not performing a procedure. The app is the test harness.

**Deliverables**

- Emulator instrumented suite in CI via Gradle managed devices, scripted through adb: Doze (`dumpsys deviceidle force-idle`, `step`, `unforce`), boot broadcast, permission revocation (`appops set`, `pm revoke`), app kill (`am force-stop`), timezone (`persist.sys.timezone`), locale switching, font scale, notification and ring screen assertions via UiAutomator.
- Firebase Test Lab runs of the same instrumented suite across a physical device matrix including Pixel, Samsung and Xiaomi models, driven from `gcloud` in CI. Known limits: per test time limits preclude multi hour soaks, and devices arrive clean so OEM battery managers are not in hostile configuration.
- Soak script for the Galaxy A15: one command that installs, seeds a schedule, starts a soak, and pulls a report. Involvement is plugging in a cable and running one command, then reading output a day later.
- `docs/MANUAL_CHECKS.md` completed, with recorded results and dates.
- Reliability report from real canary data on the A15.

**Exit criteria.** Emulator suite green in CI. Test Lab suite green across the matrix. A minimum 72 hour A15 soak with delivery inside SLO. All six ceiling items recorded as checked with results.

**Honesty constraint on claims.** With one Samsung device plus Test Lab's clean state fleet, the defensible claim is delivery measured on a specific device family under specific conditions. It is **not** validation across hostile OEM skins, because no MIUI or ColorOS device will have been tested in its default aggressive configuration. Either soften the claim or acquire one secondhand Redmi as a hostile target. The A15 with One UI is moderately aggressive, not worst case.

---

## Phase 8: Compliance and launch

**Deliverables**

- Privacy policy hosted at a public URL on GitHub Pages, versioned in this repo, in all three languages. Reachable without installing the app and not behind a login. Also available in app.
- Medical disclaimer in app and in the store listing, in all three languages, human reviewed.
- Play Health apps declaration.
- Play Data Safety form.
- Store listing localised into three languages.
- Deletion paths verified: per record, per category, full account.
- Confirmation that no PII appears in any log, client or server.
- Re verification of DPDP cross border status, which is moving and expected to become operative around 13 May 2027.
- Closed testing track satisfied: 12 testers, 14 continuous days.
- Production release.

---

## Phase 9: Deferred

Not in v1. Recorded here so they are not reinvented.

- iOS build. Constraints in `ARCHITECTURE.md` section 12 keep it additive. Realistically three to four weeks of new work whenever it starts, not three days. It requires a Mac, a physical iPhone on iOS 26 or later, and the Apple Developer Program at 99 USD per year. None of that is avoidable.
- `PHONE_CALL` escalation rung, already present in the enum and unimplemented. Requires a telephony provider and costs real money per call.
- Web caregiver dashboard.
- Monetization of any kind. If ever added, caregiver safety notifications stay outside any paywall.
- Health content library. The citation fields exist in schema version 1 so this needs no migration.
- Doctor sharing as a first class feature, which implies a consent and sharing posture that is real work.

Permanently excluded: AI or LLM features, nutrient quantity calculations, dose calculation or suggestion, physical exertion missions, advertising anywhere.

---

## Testing strategy summary

| Layer | Runs on | Covers |
|---|---|---|
| 1. JVM unit tests, `shared` | CI, no device | All domain correctness. Most real bugs live here. |
| 2. Robolectric + `ShadowAlarmManager` | CI, no device | Alarm scheduling adapter, request codes, watchdog, boot, tier resolution, permission degradation. |
| 3. Roborazzi screenshots | CI, no device | Devanagari clipping, overflow, wrapping, 200% font scale, three locales. |
| 4. Emulator instrumented, adb scripted | CI | Doze simulation, boot, permission revocation, force stop, timezone, locale, UI assertions. |
| 5. Firebase Test Lab | CI | Real hardware across OEMs, clean state. |
| 6. A15 soak, automated | One command, read later | Real Doze cadence, real timing drift, One UI sleeping apps, audio, screen on. |

Layers 1 through 5 are fully automated and verifiable without human time. Layer 6 is a script plus reading a report. The six ceiling items are the only genuinely manual surface, and the canary converts most of them into telemetry.

The standard to aim at is that a device run confirms what the automated suites already proved, rather than discovering new bugs. That is reachable for roughly the whole surface except the six enumerated items, which is why they are enumerated rather than waved at.
