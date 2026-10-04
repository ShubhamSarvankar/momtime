# MomTime Architecture

Status: authoritative for v1. Changes to anything in this document require a new ADR in `docs/adr/`.

---

## 1. What this app is

A medication, supplement, hydration and routine adherence app for pregnant and postpartum women, built Android first, with a caregiver dashboard.

The product is one thing: **a reminder fires within 60 seconds of its scheduled time, on a mid range Android phone, in Doze, with the app killed, offline.** Everything else in this document exists to serve that, or is an accessory to it.

Service level objective for v1: 99.5% of critical occurrences delivered within 60 seconds of `scheduledInstant`, measured by on device telemetry, on devices resolved to capability Tier 3.

### Non goals for v1

No AI or LLM anywhere in the product. No nutrient quantity calculations. No dose calculation or dose suggestion. No monetization, billing, or advertising. No iOS build. No food database. No HealthKit or Health Connect integration. No phone call escalation. No web client.

---

## 2. Module layout

```
momtime/
├── shared/                  Kotlin Multiplatform. jvm target only in v1; android and ios are added later as KMP targets.
│   ├── domain/              Models, value types, policy resolution
│   ├── engine/              Recurrence expansion, occurrence materialisation, escalation
│   ├── data/                SQLDelight schema, queries, migrations, repositories
│   └── capability/          DeliveryCapability enum and resolution contract
├── android/                 Compose UI, alarm subsystem, platform adapters. Depends on shared's jvm artifact as a plain project dependency.
├── server/                  Ktor service. Depends on shared (jvm target).
├── infra/                   Terraform
└── docs/                    This directory
```

`shared` compiles for the JVM with no Android dependency, and in v1 it declares no `android` KMP target at all — `commonMain` and `jvmMain` only. Its test suite runs on CI without an emulator. This is the mechanism by which the later iOS port is additive rather than a rewrite, and it is also why the server cannot disagree with the client about when a dose is due.

`android` consumes `shared`'s `jvm` target output as an ordinary project dependency (`implementation(project(":shared"))`), the same way any Android app consumes a plain Kotlin/JVM library. This was verified with a throwaway spike before Phase 0: Gradle's variant resolution accepts an Android application module requesting a JVM-only Kotlin Multiplatform library without any attribute-matching error, straight through manifest merging, desugaring and dex packaging. Platform-specific code that must live below `shared` (the SQLDelight driver actual, the alarm subsystem, etc.) lives in `android`, not in a `shared/androidMain` source set — there is no such source set. See ADR 27.

The Android data wiring lives in `com.momtime.android.di`. `AndroidDatabaseDriverFactory` builds the one `AndroidSqliteDriver` over `momtime.db`, configured by `MomTimeDatabaseCallback`: foreign keys enforced in `onConfigure` (ADR 43), a corrupt file moved aside instead of deleted (ADR 44), the journal mode set explicitly to a rollback journal, because the platform default depends on a global setting (ADR 47). A second database, the android store (`AndroidStoreDatabase`, file `momtime_android.db`, ADR 48), holds what the shared schema must not: the resolved delivery tier and the state of the device at each fire, and the alarm record. It is configured the same way and has its own driver. The Koin graph exposes the repositories of both databases, the clock and the two driver factories only. `shared` builds the database inside an `internal` holder, so `MomTimeDatabase` is not a Koin definition and cannot be resolved, and no android source can reach a generated `*Queries` type: `verifyNoGeneratedQueries` fails the build if one does, because the generated `updateOccurrenceState` query would change state without appending its event (invariant 3). The check allows `MomTimeDatabase` only inside the `di` package, confines the store's generated types to its own repository package (`com.momtime.android.store`) and the `di` package, and `verifyNoClockSystem` bans `Clock.System` and `System.currentTimeMillis` outside it (invariant 8).

### The capability line

Platform capability differences live entirely in delivery and presentation. They never appear in the schema, the domain vocabulary, the event log, or the escalation engine.

Above that line (`shared`): maximal expressiveness. Below it (`android`, later `ios`): each platform renders and delivers what it can.

This is not a constraint on Android features. Android gets the richest possible ring experience. The rule only prevents Android specifics from leaking upward into shared code.

---

## 3. Domain model

Three layers. Do not collapse them.

### 3.1 ScheduleTemplate

What the user or her doctor configured.

| Field | Notes |
|---|---|
| `id` | UUID, client generated |
| `pregnancyId` | Scopes to a pregnancy record |
| `title` | Max 24 characters. Charted and used as a legend label. |
| `notes` | Optional long text. Display and PDF only. Never charted. |
| `taskType` | `MEDICINE`, `SUPPLEMENT`, `MEAL`, `FOOD`, `CUSTOM`. Sets defaults only. |
| `criticality` | `CRITICAL`, `STANDARD`, `GENTLE` |
| `recurrence` | See 3.4 |
| `timeOfDay` | Local wall clock time |
| `timeZoneId` | IANA zone id |
| `mission` | `MissionConfig`, defaults to `None` |
| `nutritionTags` | Set of `FRUIT`, `VEGETABLE`, `PROTEIN`, `IRON`, `CALCIUM`, `DAIRY`, `SUPPLEMENT` |
| `dosage` | Free text, display only |
| `doctorInstructions` | Free text, display only. **Never parsed by any code.** |
| `inventoryCount` | Nullable. Medicine only. |
| `refillThresholdDays` | Nullable |
| `active` | Soft deactivation. Templates are never hard deleted while occurrences reference them. |

`taskType` is a default source and a display icon. It carries no behaviour. All behaviour reads `criticality`. This is deliberate: the user decides that the iron tablet is critical and the multivitamin is standard, and the code must not hardcode that medicines matter more than food.

### 3.2 Occurrence

A materialised instance of a template on a specific day.

| Field | Notes |
|---|---|
| `id` | UUID |
| `templateId` | |
| `localDate` | |
| `scheduledInstant` | Epoch millis, computed from `timeOfDay` + `timeZoneId` + `localDate` |
| `timeZoneId` | Snapshot at materialisation |
| `state` | `PENDING`, `COMPLETED`, `SNOOZED`, `SKIPPED`, `MISSED` |
| `alarmSlot` | Monotonic integer. Source of `PendingIntent` request codes. |

`COMPLETED`, `SKIPPED` and `MISSED` are terminal. Terminal occurrences are immutable and are never touched by template edits. This is enforced by a trigger on the `occurrence` table that aborts any change of state out of a terminal state (schema version 2, ADR 37), and a state change is only ever made together with its event, in one transaction (`OccurrenceRepository.transition`).

Materialisation window: rolling 48 hours ahead, regenerated by a daily worker and on any template edit. Not weeks. See 5.3.

Materialising one template is a single atomic repository operation (read existing dates, compute the missing ones, allocate `alarmSlot`s, insert), so a failed batch rolls back whole and overlapping runs cannot leave a duplicate or a partial batch. Whether a contending run waits or is refused depends on the driver (ADR 36). Shown for the Android driver, by `MaterialiseRaceTest` under Robolectric's native SQLite at SDK 29 and 36 (not on a device): with one driver on the file (the rollback journal; enabling WAL did not change the result in the experiment, and it stays off for ADR 44's reasons), the second run waits in the framework's in-process connection pool (`SQLiteConnectionPool.waitForConnection`) until the first commits, then sees its rows and adds only the missing dates. The wait is a property of the pool, not of `BEGIN IMMEDIATE` as ADR 36 expected. A second driver on the same file, or a second process, is refused with a locked database error instead (ADR 45). So the app builds exactly one driver per database file, the journal mode is set explicitly to a rollback journal (ADR 47), and the app stays in one process: no component may declare `android:process`, which `verifySingleProcess` enforces on the merged manifest, library components included (ADR 46). The serialisation claim holds only inside one process. The occurrence insert is a plain `INSERT`: a duplicate fails loudly rather than being ignored. Correctness does not depend on how workers are scheduled. See ADR 36.

### 3.3 Event log

Append only. The single source of truth for everything derived.

| Field | Notes |
|---|---|
| `id` | UUID, client generated. Doubles as the idempotency key for server ingest. |
| `occurrenceId` | Nullable. Null for water and weight events. |
| `eventType` | See below |
| `deviceTimestamp` | Device clock at the moment of the event |
| `serverReceivedAt` | Set by the server only. Null on device. |
| `source` | `USER`, `SYSTEM`. Not `CAREGIVER`: caregivers are read only and no caregiver authors an event (ADR 59) |
| `payload` | Typed, event specific. Platform neutral only: nothing about the delivery tier or the state of the device is in the log (ADR 48) |

Event types: `OCCURRENCE_MATERIALISED`, `ALARM_SCHEDULED`, `ALARM_FIRED`, `COMPLETED`, `COMPLETED_BACKFILLED`, `SNOOZED`, `SNOOZE_ENDED`, `SKIPPED`, `MISSED`, `MISSION_VERIFIED`, `MISSION_BYPASSED`, `WATER_LOGGED`, `WEIGHT_LOGGED`, `CANARY_RESULT`, `WATCHDOG_REPAIR`, `CAREGIVER_LINKED`, `CAREGIVER_REVOKED`, `SHARING_PAUSED`, `SHARING_RESUMED`, `CAREGIVER_NOTIFIED`.

Each event type has its own set of allowed type dependent columns (`EventColumn.kt`), and decoding fails loudly if a row sets any other: `effective_at` belongs to `MISSED` alone, `snooze_number` and `snoozed_until` to `SNOOZED`, `mission_result_type` to `MISSION_VERIFIED` and `MISSION_BYPASSED`, `water_ml` to `WATER_LOGGED`, `weight_grams` to `WEIGHT_LOGGED`, `caregiver_link_id` to the five caregiver events, the canary columns to `CANARY_RESULT`, and nothing to any other type. The table ties no column to a type, so this check is the only thing that does (ADR 52).

`SNOOZE_ENDED` is a system event with no payload, written when a snooze's alarm fires (ADR 66). It is not `ALARM_FIRED`: the count of `ALARM_FIRED` events is the record of ladder rungs that have fired, and a snooze is not a rung. A snooze is running while the occurrence is `SNOOZED` and fewer `SNOOZE_ENDED` than `SNOOZED` events exist, so the two counts are the record. It changes no state and needed no schema change (`event_type` is plain text with no `CHECK`). A `SNOOZED` event carries its number and `snoozedUntil`, the instant the snooze ends, computed once when she snoozes from the duration then in force and recorded with the event, as `MISSED` records `effectiveAt`: a later change to her setting moves no snooze already taken, and a server that mirrors events but not settings knows when it ends. That column is schema version 4 (`3.sqm`, ADR 66).

`CANARY_RESULT` carries `Canary(scheduledAt, actualAt?)`: when the canary was due, and when it was seen to fire, or null if it never was. Per fire timing is not stored at all: it is the rung's instant against `ALARM_FIRED`'s `deviceTimestamp`. What the device did when the alarm fired (the resolved tier, whether the screen turned on, audio focus, battery and Doze state, the watchdog repair flag, the boot count) is kept by `android` in its own store, keyed by the event id with no foreign key across the two files (ADR 48, CLAUDE.md invariant 5).

`MISSED` is a derivation, not an observation: a pure function of an occurrence, its grace window, the absence of a terminal event, and the current instant. Its payload carries two timestamps, not one. `deviceTimestamp` is the usual write time — when the event happened to get recorded, which can lag the real miss if the app was closed. `effectiveAt` is the computed grace-expiry instant the occurrence actually became `MISSED`. Adherence figures and reports read `effectiveAt`; audit and sync read `deviceTimestamp` as with every other event. This makes reconciliation timing irrelevant to adherence accuracy: whether the transition is discovered by the watchdog, on app foreground, or on boot, the reported instant is the same. All three call sites dispatch the same idempotent `Reconcile` command to the domain layer, which derives and emits the event — none of them mutate occurrence state directly, so invariant 3 holds. See ADR 30.

Adherence figures are computed from events and an explicit `asOf` instant, never from the occurrence `state` column. Each event is attributed to its occurrence's scheduled `localDate`; its effect time is `effectiveAt` for derived events (`MISSED`) and `deviceTimestamp` for user events; it counts only if effect time is at or before `asOf` (inclusive). `COMPLETED_BACKFILLED` counts as completed on the scheduled date and never as missed, and takes effect when it is entered; the occurrence state stays `MISSED`. An occurrence with no counted terminal event as of `asOf` is in none of the three figures. See ADR 40.

Nothing mutates a counter. Adherence percentages, streak equivalents, nutrition tag aggregates and compliance reports are all reductions over this log. This is what makes the record defensible to a doctor and what eliminates the class of bugs where a reinstall double counts a streak.

`MISSION_VERIFIED` and `MISSION_BYPASSED` are distinct from `COMPLETED` so the caregiver view can distinguish a verified completion from a self reported tap from a bypass. That distinction is the only reason the mission feature is worth building.

### 3.4 Recurrence

A narrow custom model, not RFC 5545. No RRULE parser anywhere in the codebase.

```kotlin
sealed interface Recurrence {
    data object Daily : Recurrence
    data class Weekly(val daysOfWeek: Set<DayOfWeek>) : Recurrence
    data class EveryNDays(val n: Int, val anchorDate: LocalDate) : Recurrence
}
```

The engine expands any of these into concrete instants. `EveryNDays` is supported on Android and will also work on iOS later, because the engine emits concrete one time alarms rather than handing a rule to the platform.

### 3.5 Pregnancy and phase

`PregnancyPhase` is `PRENATAL` or `POSTPARTUM`, present from the first schema version, alongside a `phaseChangedAt` instant recording when the transition happened — a dated event she will want to see, not just a current-state flag.

The pregnancy ends. Around week 40 the week counter runs out and the due date countdown goes negative. Postpartum medication and hydration reminders for a breastfeeding mother are plausibly a longer use window than the pregnancy itself, so this is a state transition, not an end of life.

One user may have several pregnancy records over time. Adherence history, templates and occurrences are scoped by `pregnancyId`.

Due date is the single source of truth for gestational week. Doctors revise due dates, so edits are appended with history rather than overwriting, otherwise the week number changing retroactively silently rewrites past dashboards.

This means "the due date" is never a single value read without a point in time. The current view (today's gestational week, the countdown) reads the *latest* revision. A historical report or any past-dated view reads the revision *in effect at that date* — the latest revision recorded on or before the date being viewed, not the latest revision overall. The gestational-week function takes an explicit `asOf` instant; there is no overload that omits it, because omitting it is exactly the bug this table exists to prevent (a due date revised today silently rewriting the gestational week shown on last month's report).

### 3.6 Water

Water is **not** an occurrence. Modelling glasses as scheduled tasks pollutes adherence maths and produces a false sense of missed doses.

Separate table: a daily goal in millilitres, plus `WATER_LOGGED` events. Optional nudge schedule, capped at a small number per day, never alarm grade, never escalating. Excluded from the adherence percentage entirely.

### 3.7 Weight

Stored as integer grams, displayed in kilograms to one decimal.

Hard rules, enforced by review:

- The app never derives anything from weight. No BMI, no gain rate, no target range, no "on track" language, no colour coding, no notification, no badge, no streak.
- The chart plots her data points against gestational week and nothing else. No IOM range bands, no target line, no shaded zones. Any target band is a health claim requiring a cited source under App Store guideline 1.4.1 and Play health content policy, and turns a record into a verdict.
- Logging is user initiated only. The app never prompts her to weigh herself.
- Off the caregiver dashboard by default, behind its own consent toggle.
- Individual entries and the entire log are deletable, and deleting weight must not touch adherence history.

The purpose is that she can hand the chart to her obstetrician. Built for that it is useful. Built as feedback it becomes a pressure device.

### 3.8 Nutrition aggregation

Derived, not stored. Reduce completed occurrences in a window, grouped by the `nutritionTags` of their template. A template may carry several tags; milk is both `DAIRY` and `CALCIUM`.

**Count servings, never nutrient quantities.** "14 fruit servings this week" is a record of what she did and needs no citation. "62% of your daily iron" is a nutritional claim that is also factually unsupportable, since the app does not know dosage or absorption. No target lines, no good or bad colour coding.

---

## 4. Escalation

### 4.1 Declarative ladder

The engine emits the full escalation ladder for an occurrence as an ordered list of instants and rung types. It does not schedule anything and does not take a callback.

```kotlin
data class EscalationRung(val instant: Instant, val channel: Channel)
enum class Channel { RING, RING_REPEAT, CAREGIVER_INFO, CAREGIVER_URGENT, PHONE_CALL }
```

Android consumes only the head of the list, arming one alarm at a time. iOS will later pre schedule the whole list and cancel the tail on completion. Neither behaviour is encoded in the engine. `PHONE_CALL` exists in the enum from day one and is unimplemented in v1.

`EscalationRung` carries no slot of its own — `alarmSlot` is per-occurrence (§5.4), not per-rung. One request code is shared and re-armed across every rung of a given occurrence's ladder; the platform reads `occurrence.alarmSlot` when it needs a request code, rather than the rung carrying one. See ADR 0031's narrowing note.

### 4.2 Policy

Escalation increases **channel reach**, not intensity of the same channel. A `CRITICAL` occurrence rings full screen at t+0, because that is the entire point of the feature. It does not build up to it.

Default ladders, all per criticality and user adjustable:

| Criticality | Ladder |
|---|---|
| `CRITICAL` | t+0 RING, t+5m RING_REPEAT, t+10m CAREGIVER_INFO, t+20m CAREGIVER_URGENT |
| `STANDARD` | t+0 RING, t+10m RING_REPEAT |
| `GENTLE` | t+0 notification only, no repeat |

### 4.3 Interruption budget

A daily cap on ring grade interruptions. When exceeded, `STANDARD` and `GENTLE` downgrade to silent notifications for the rest of the day. `CRITICAL` is never budget limited: it is exempt from being limited, not from being counted. Every ring grade interruption, `CRITICAL` included, spends the budget once (ADR 60), because the budget measures how often the app interrupts her. A fire for an occurrence that is already ringing is not another interruption and spends nothing (ADR 62), and a snooze's end that rings is one (ADR 66). Silent presentations spend nothing.

Twelve occurrences a day with an unbounded ladder is how this app gets uninstalled in a week. The budget is a product requirement, not an optimisation.

"Day" is the local calendar date in the current zone, stored from schema v1 as `budgetDate` (a `LocalDate`) plus a count. A timezone change mid-day may shorten or lengthen that day's budget window; this is accepted as correct, since pinning the window to a zone she has already left is worse. See ADR 32.

### 4.4 Quiet hours

A user configured window suppresses ring grade delivery. It is a `QuietHours(start, end)` value object, nullable as a whole; start and end must differ (equal times are rejected, not read as a full day or as no window), enforced by the constructor and by a CHECK on `app_settings`. Start is inclusive and end is exclusive; start later than end wraps midnight. See ADR 37. `CRITICAL` occurrences override quiet hours; everything else defers to a silent notification.

Quiet hours evaluate against current-zone wall clock, the same as the interruption budget's `budgetDate` (§4.3), so the window travels with her across timezones rather than staying pinned to a zone she has left. See ADR 32.

### 4.5 Snooze and grace

- Snooze: 10 minutes default (her setting, `snoozeDurationMinutes`), maximum 3 per occurrence, and never past the next occurrence of the same template. `SnoozePolicy` is the one source of these rules and `OccurrenceActions.available` applies it: a snooze that is not allowed is not offered and, if requested, is refused and writes nothing. A snoozed occurrence is armed through `ensureArmed` by the end of its snooze, the `snoozedUntil` recorded when she snoozed, never her current setting (so the watchdog repairs a lost snooze alarm like any other), and while it runs the ladder waits: a rung that comes due during the snooze is armed after it, from the count of rungs that really fired. The snooze's own fire writes `SNOOZE_ENDED`, never `ALARM_FIRED`, so it consumes no rung (ADR 66). Grace still applies while snoozed: `Reconcile` reads snoozed occurrences.
- Grace window, after which a pending occurrence transitions to `MISSED`: `CRITICAL` 2 hours, `STANDARD` 4 hours, `GENTLE` same local day.
- After the grace window an occurrence is terminal. Late marking is still allowed but is written as `COMPLETED_BACKFILLED`, not `COMPLETED`.
- A rung that fires late is presented as it would have been (a ring, or silent under quiet hours or the budget) only while it is within 30 minutes of its own instant. Beyond that, and still within grace, it is presented as a silent notice: no ring, no vibration, no heads up. Nobody wants a 7 AM alarm at 11 AM, and nobody wants the reminder to vanish either. The window decides how a late rung is presented, never whether it is delivered, and it is decided at fire time only (ADR 56). The occurrence goes to `MISSED` at the end of its grace, derived by `Reconcile` (ADR 30), and not before: beyond the window is not an early `MISSED`.

### 4.6 Dismissal is not completion

This invariant holds on both platforms and is what makes the iOS port viable.

Stopping the alert does not write a `COMPLETED` event. Completion is written when the user acknowledges in the app or taps a notification action. If the alert is stopped without acknowledgement, the next rung fires as scheduled.

Her three actions, acknowledge, snooze and skip, are a command to the domain (`OccurrenceActionCommand`, ADR 66). Each writes exactly its own event (`COMPLETED`, `SNOOZED` carrying its number, `SKIPPED`) and the state change that goes with it, in one transaction, and nothing else; then `ensureArmed` runs. No Activity, receiver or service changes occurrence state. Swiping a notification away is a dismissal and writes nothing: no notification sets a delete intent, so the occurrence keeps its state and the next rung still fires.

---

## 5. Android alarm subsystem

The critical path. Nothing else in the codebase deserves this much care.

### 5.1 Delivery stack

1. `AlarmManager.setAlarmClock()` for the wake. The system does not adjust delivery time for these and delivers them in low power modes. It is the only exact mechanism. On API 31 and above it needs the same exact alarm capability as every other exact API (`setExact`, `setExactAndAllowWhileIdle`), so there is no exact fallback that could rescue a missing capability, and `setExactAndAllowWhileIdle`, which is also throttled to roughly one fire per app per nine minutes in deep Doze, has no role. Exact capability is what `canScheduleExactAlarms()` reports (taken as true on API 29 and 30, where the method does not exist and is not called), never a permission check, because a battery optimisation exemption also grants it. Without it the app arms an inexact `setAndAllowWhileIdle` alarm and delivers Tier 1 (ADR 50).
2. A `BroadcastReceiver` that immediately starts a foreground service typed `mediaPlayback` as the ringer.
3. A notification with `setFullScreenIntent`, and a ring `Activity` with `showWhenLocked` and `turnScreenOn`.

The overlay path (`SYSTEM_ALERT_WINDOW`) is a **secondary** route, used as an exemption from Android 10+ background activity start restrictions to open the ring screen when a full screen intent is not available, and only then: on the heads up and audio only paths, never when full screen intent is effective, never as the way the alarm rings (ADR 61). Note that Android 15 restricts starting foreground services while holding it, so it is not a free pass, and the ringer does not rely on it: it starts from the alarm's broadcast under the exact alarm exemption.

**Six delivery paths (ADR 60).** After the fire path writes `ALARM_FIRED` it asks the domain (`DeliveryPolicyCommand`, over Phase 1's quiet hours and budget resolution, spending the budget through the domain) what the rung should do, and the delivery port takes one path, a function of the presentation, the domain's decision and the capability resolution: **ring** (Tier 3: a full screen intent notification and the ringer service), **heads up** (Tier 2: a heads up and the ringer, with the overlay route if granted), **audio only** (notifications denied: the ringer, and the overlay route if granted), **plain notification** (Tier 1, no ringer), **silent notice** (a rung beyond the catch up window, ADR 56) and **silent** (quiet hours or the budget). `setFullScreenIntent` is called only while the full screen intent is effective. A refusal to start the ringer degrades to the notification path, is recorded, and never crashes.

### 5.2 Permissions

- Declare `USE_EXACT_ALARM`, and `SCHEDULE_EXACT_ALARM` with `android:maxSdkVersion="32"` alongside it, as Android's manifest guidance says. `USE_EXACT_ALARM` is a normal permission: it is granted at install to any app that declares it and the user cannot revoke it. What restricts it is Google Play policy, which limits it to alarm clock and calendar apps. Whether a medication reminder qualifies is not settled and is an Open Item (`IMPLEMENTATION_PLAN.md`); if Play rules it out, the fix touches the manifest only. The code never depends on which permission is held. Capability is what `canScheduleExactAlarms()` reports, and the case where neither permission is available, so that it reports false, is a normal state that resolves to Tier 1.
- Always call `canUseFullScreenIntent()` before relying on it. On Android 14+ this permission is granted by default only to calling and alarm apps and otherwise requires sending the user to `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`. Degrade, never crash. A `SecurityException` here is the single most common failure mode in this category of app.
- Request battery optimisation exemption via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- Handle `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, `ACTION_BOOT_COMPLETED`, `ACTION_TIMEZONE_CHANGED`, `ACTION_TIME_CHANGED`, and `MY_PACKAGE_REPLACED`.
- A `BOOT_COMPLETED` receiver on Android 15+ cannot start `dataSync`, `mediaPlayback`, `phoneCall`, `camera`, `mediaProjection` or `microphone` foreground services; it throws `ForegroundServiceStartNotAllowedException`. **Boot does rescheduling only, via WorkManager. Boot never rings.**
- One UI specifically: battery optimisation exemption is not sufficient. The app must also be removed from Sleeping apps and Deep sleeping apps, and excluded from Put unused apps to sleep. Onboarding needs a Samsung specific walkthrough with deep link attempts and screenshot fallbacks.

### 5.3 One alarm at a time, plus a watchdog

This is the most important reliability decision in the document.

Do not pre arm a week of alarms. Twelve occurrences with a four rung ladder is 48 alarms a day, and Samsung is reported to cap scheduled alarms per app in the region of 500. Instead:

- Arm exactly one alarm: the next pending escalation rung across all occurrences.
- On each fire, re arm the next one.
- A `WorkManager` periodic job at the 15 minute floor verifies that the correct next alarm exists and re arms if it does not.

The watchdog is what converts a lost alarm into a late alarm instead of a silently dead chain. Without it, one dropped fire ends all future reminders.

**One entry point (ADR 53).** Every caller that needs the next alarm to exist, which is a fired alarm, the watchdog, boot and the foreground, calls `ArmingCoordinator.ensureArmed()` and nothing else. In one pass it resolves capability (read at the start of every pass, never remembered), selects the next rung, arms it through the `AlarmScheduler` port, and keeps the armed record in the android store. Selection is `ArmingSelection` in `shared`: only the channels the device delivers, `RING` and `RING_REPEAT`, are eligible, the filter runs before the earliest rung is chosen and before fired rungs are counted, and the rung that is next comes from the count of `ALARM_FIRED` events, never from comparing a rung with the time. Rungs at the same instant are ordered by occurrence id, and when the first has fired the second is armed at once. Request codes are per occurrence, so arming another occurrence's rung does not replace the alarm already armed: when the head moves, the previous alarm is cancelled by its slot, from the armed record, and after every pass the platform holds exactly one alarm. `ALARM_SCHEDULED` is appended when a rung is first armed or the expected rung changes, never on a refresh. Selection reads the open occurrences, pending and snoozed: a snoozed occurrence has one thing armed for it, the end of its snooze, until the snooze has ended (ADR 66).

**The fire path (ADR 53).** The receiver is not exported and is reached only by an explicit component intent. It takes `goAsync()`, works on a bounded executor and always calls `finish()`. It resolves the fired slot to an occurrence. An unknown slot (after a reset or a restore), a terminal occurrence, or a rung that is not the one expected next writes nothing and delivers nothing. The expected rung appends `ALARM_FIRED` and goes to a delivery port; while a snooze is running the expected "rung" is the snooze's end, which appends `SNOOZE_ENDED` instead and goes to the delivery port the same way (ADR 66). Every case ends with `ensureArmed()`, so a stale fire cannot end the chain. If `setAlarmClock` throws `SecurityException` the scheduler resolves capability again and arms through the resulting tier.

**A late rung is presented quietly, never dropped (ADR 56).** The watchdog and boot make no catch up decision: they arm the earliest rung that has not fired, for now if it is overdue, through `ensureArmed`. The fire path decides. When a rung fires and its occurrence is not terminal, `ALARM_FIRED` is written and the rung is delivered as policy says if it is within 30 minutes of its own instant (inclusive), or as a silent notice if it is beyond that and the occurrence is still within grace. `Reconcile` is dispatched first, so an alarm that fires after the end of grace finds the occurrence `MISSED` and delivers nothing. Several overdue occurrences each get their own silent notice. A worker never delivers anything itself.

**The watchdog pass (ADR 58).** The 15 minute job (unique periodic work, `KEEP`, so enqueueing it at every start never resets its timer; ADR 57) dispatches `Reconcile`, resolves capability, and looks for positive evidence that the armed alarm was lost: the armed record is missing or is for another rung than the one the domain expects; the `PendingIntent` probe (`FLAG_NO_CREATE`) finds nothing; the boot count differs from the one recorded when the alarm was armed (read through a seam, never from uptime, which misses a restart after which the device has been up longer than it had been at arm); exact capability has changed; the app's version code differs from the one recorded when the alarm was armed (an update clears the app's alarms; the version code does not depend on the wall clock); or the expected rung is overdue by more than 2 minutes for an exact alarm or 15 minutes for an inexact one, which may legitimately run late in Doze. `WATCHDOG_REPAIR` is written once, only on that evidence, and then `ensureArmed` runs. On a correct state a pass writes no event, changes no state, and leaves the armed alarm unchanged in type, trigger time and request code (golden scenario 17). The watchdog and the fire path run exclusive of each other, so a fire that has written `ALARM_FIRED` and not yet armed the next rung is not read as a lost alarm. A daily job materialises 48 hours ahead and then calls `ensureArmed`; a one time run is enqueued on a template edit. Unique work is an efficiency measure only: the transaction in `materialiseWindow` is the correctness mechanism (ADR 36). When the app's process starts, `AppStart` dispatches `Reconcile` and calls `ensureArmed`, which is the first code to run after an Auto Backup restore. WorkManager is a dependency whose own components, permissions and (below API 30) one alarm of its own are recorded in ADR 57; none of its components runs in another process, and none is direct boot aware, so no job runs between a restart and the first unlock.

What the watchdog does not cover is a force-stop. A true force-stop (Settings, `am force-stop`, or an OEM cleaner that force-stops) clears the app's alarms, and `JobSchedulerService` cancels the app's jobs and removes them from its store on `ACTION_PACKAGE_RESTARTED` (read from AOSP at android-13 through android-16), which includes WorkManager's periodic job. Nothing the system scheduled for the app then runs, so a force-stopped app recovers on the next launch or the next boot. The watchdog covers process death, kills that are not force-stops (a low-memory kill, and the kill the platform makes when exact alarm access is revoked, which retains the jobs), and a lost or cleared alarm in a live app. Whether a given OEM's "clean" is a force-stop or a plain kill is not known without a device; it is a row in `MANUAL_CHECKS.md`.

### 5.4 Request codes

`PendingIntent` request codes derive from the `alarmSlot` monotonic integer column, never from a hash of ids. A hash collision here means one alarm silently cancels another, and it will not reproduce on a bench. This is covered by Robolectric tests at SDK 29, 31, 33 and 36, against `ShadowAlarmManager`: every request code observed is some occurrence's slot, two occurrence ids with the same hash code still get their own codes, and the schema refuses two occurrences with one slot. The `PendingIntent` is `FLAG_IMMUTABLE` and `FLAG_UPDATE_CURRENT`, and its intent names the receiver as its component. A receiver cannot see the request code, so the slot and the rung's instant also travel as extras.

`alarmSlot` lives on `Occurrence`, one value per occurrence, not one per escalation rung. Every rung in that occurrence's ladder re-arms using the same request code as the ladder progresses, which is what makes "re-arm on each fire" (§5.3) a plain replace-in-place rather than requiring the platform to track and cancel a distinct code per rung.

`alarmSlot` is `Int` (request codes are `Int`), monotonic per install from a counter row. There is no cross-install collision risk to design against: uninstall cancels the app's alarms outright, and `MY_PACKAGE_REPLACED` handling exists precisely to re-materialise and re-arm after an update, so a stale slot value never outlives the alarms it referred to. Alarm state tables and the slot counter are backed up with the rest of the database, not excluded: backup rules exclude files, not tables, and the database is one file (ADR 31's narrowing note, ADR 34). That is safe because the occurrences and the counter restore together from one snapshot, and because alarms and `PendingIntent`s are system state that a restore never brings back, so a restored `alarmSlot` is an identifier to verify against, never a claim about what is armed. A restored phone arms nothing until the app next runs, since a restored app is in the stopped state and receives no broadcast. The backup rules are an allow-list of the shared database and its rollback journal, so the android store (`momtime_android.db`: the armed alarm record and the per fire device state) is never backed up, which suits it: a restored phone has armed nothing, and the quarantined `.corrupt` copy of either database never leaves the device (ADR 44, ADR 48). With only one alarm ever armed at a time (§5.3), a single fixed request code would technically be sufficient for correctness; the slot is kept anyway because it lets the watchdog verify the armed alarm is the *correct* one, not merely that *some* alarm exists. See ADR 31.

### 5.5 Audio

`AudioAttributes` with `USAGE_ALARM` for the ringer's sound, and `CATEGORY_ALARM` on its notification, which is importance high. This carries through default Do Not Disturb without requesting notification policy access, which would invite review scrutiny for no benefit. The ringer is a foreground service of type `mediaPlayback` that requests transient audio focus, records the outcome, plays whatever the answer, and loops the sound until it is stopped (ADR 61).

Ship `.wav` assets, under 30 seconds, designed to loop, so the same files work on iOS later. The current ones are placeholders generated by `scripts/sounds/GenerateSounds.java` (ADR 61); Shubham chooses the final ones. Android additionally gets a gradual volume ramp and a louder backup sound after a configurable unacknowledged interval (ADR 65). iOS will not have those, and that is fine.

**The ramp** starts the sound at 30 percent of player volume and reaches full player volume in 6 seconds, in steps. It is the player's volume only: the app never changes the system alarm stream volume, which is hers. It is a function of a step count and reads no clock. **The backup sound** (`ring_backup.wav`, louder in the file by about 4.4 dB) replaces the primary at full player volume if the ring is still going after the interval, 2 minutes by default or off, measured from the start of the ring and cancelled when it stops. Both sounds are placeholders from `scripts/sounds/GenerateSounds.java`. If the alarm stream's volume is zero at a fire, that is recorded in the android store (`fire_telemetry.alarm_stream_muted`) for the reliability banner (PR 7); nothing is done about it.

**Vibration** is a pattern per template, defaulting by criticality (`CRITICAL` urgent, `STANDARD` steady, `GENTLE` light, or none), played with the alarm usage while the ringer runs and stopped with the sound. `VIBRATE` is declared.

**Android only settings** (the backup interval and the per template vibration patterns) cannot be in the shared schema (invariant 5) and, being hers, cannot be in the android store, which is not backed up. They are in an android owned `SharedPreferences` file, `momtime_android_settings`, which the backup rules include by name (an allow list, so it is added explicitly, and a test checks the name against the file the code writes). Their settings screen is Phase 3; until then every value is its default.

### 5.6 Notification channels

Split by criticality, not by task type, plus one for what arrives silently: Critical, Standard, Gentle and Quiet notices. Four channels she can tune (ADR 64, which supersedes ADR 60's three). Onboarding explains that the Critical channel is the one not to mute, and that Quiet notices is the one to keep on, because a late reminder for a medicine she must not miss can arrive there. Critical and Standard are importance high (a heads up and a full screen intent need it) and Critical carries the alarm tone on the alarm stream; Gentle is importance low and is for occurrences she made gentle; Quiet notices is importance low with no sound and carries every silent presentation (a rung beyond the catch up window, quiet hours, the spent budget), whatever the criticality, so that a late critical dose never arrives on the channel she is most likely to have muted. The platform's per notification silence is not used, because its effect shows only on a device and a channel's importance is testable. A user can block one channel while notifications stay on, so the Critical channel's importance is a capability input: blocked, it fails Tier 3 for critical delivery (ADR 50). The shared database's reset notification is posted on the Critical channel (ADR 51).

### 5.7 Ring screen

Android specific and as rich as it needs to be. One ring session at a time covers every occurrence that is due, and the screen lists each with its title, its dosage and the doctor's instructions exactly as typed, never parsed; it shows only fields the model has (ADR 62). A fire for an occurrence already ringing continues the ring, and a fire for another joins the session. The mission UI has a seam and nothing behind it. Actions: each occurrence in the session has its own acknowledge, snooze and skip, offered only if the domain says she may (a fourth snooze, or one that would reach the next occurrence of the same template, is not offered, and a line says so), and each dispatches to the domain (ADR 66). Acting on one occurrence leaves the others ringing; when nothing in the session is left unacknowledged it ends and the sound and the vibration stop. The same actions are buttons on a reminder notification, and on the ring notification while one occurrence is ringing: immutable, explicit `PendingIntent`s to a receiver that is not exported, named by slot. The screen also has a button that only stops the sound, which writes nothing.

The ring `Activity` never mutates occurrence state, and it cannot even read it: it shows the ring session and nothing else, and `verifyRingUiBoundary` fails the build if its package names a repository, a store type or a database. It dispatches an action to its host, which dispatches it to the domain layer, and the domain decides. Opening it with no session starts no sound and writes nothing, whoever opens it (the alarm clock's show intent, a notification, the overlay route). This is what keeps the Android UI from leaking into shared code.

### 5.8 DeliveryCapability

Resolved at runtime on each platform, not inferred from OS version. An Android 15 device with full screen intent revoked behaves worse than an Android 11 device with everything granted.

| Tier | Android | iOS (later) |
|---|---|---|
| `TIER_3` | Exact capability, plus effective full screen intent (`canUseFullScreenIntent()`, and notifications enabled, and the Critical channel not blocked), plus battery exemption | AlarmKit authorised, iOS 26+ |
| `TIER_2` | Exact capability, short of Tier 3: heads up and the ringer service where notifications are delivered, the overlay where it is granted, audio only otherwise | UserNotifications time sensitive + Live Activity |
| `TIER_1` | Not exact: an inexact alarm and plain notifications. Honest about it in the UI. With notifications denied as well, nothing is delivered visibly and the app shows a blocking banner. | |

The UI and the escalation policy read the tier. The canary reports which tier the device **actually achieves**, not which one it claims.

Android resolves the tier with a pure function over six inputs: exact capability, full screen intent, notifications enabled, battery exemption, overlay permission, and whether the Critical channel is blocked. The result is android's own type (a tier, the mechanism that arms the alarm, and presentation flags for full screen intent, heads up, overlay and audio only), and the tier maps to the shared `DeliveryCapability`, which keeps its three values (ADR 50). A user can block one channel while notifications stay on, and Tier 3 must then fail for critical delivery, so that is an input; channels arrive in PR 5 and until then it is read as not blocked. The expected result for every one of the 64 combinations is written out as data in the tests, and the test fails if a combination is missing from it.

### 5.9 Missions

Opt in per template. Default is `MissionConfig.None`. This is a setting, not a mode, and most templates will never have one.

```kotlin
sealed interface MissionConfig {
    data object None : MissionConfig
    data class Barcode(val expectedPayload: String) : MissionConfig
    data class PhotoMatch(val referenceHash: String) : MissionConfig
}
```

- Barcode: ML Kit barcode scanning with the **bundled** model, not the Play Services dependent one, so a scan never fails because Play Services is updating. Scan the blister strip to dismiss. One second, and real evidence the medicine was in her hand.
- Photo match: perceptual hash with a Hamming distance threshold, on device, no ML model. Reference image lives in app private storage and is never uploaded.
- Every mission has a bypass path for when the reference is unavailable, logged as `MISSION_BYPASSED`.
- Never any physical exertion mission. No squats, no steps, no shaking, no walking. The user may be in her third trimester. This is a safety rule, not a UX preference.
- No math or typing missions.

Mission config is a neutral typed structure in shared code. It is never an Android intent blob.

### 5.10 Canary and telemetry

Opt in during onboarding, clearly explained as helping fix reminder reliability, fully functional if declined.

- Onboarding canary: schedule a test alarm 60 seconds out, ask the user to lock the phone, verify it fired on time. This is the single most valuable onboarding step in the app.
- Daily silent canary at a quiet hour, recording scheduled versus actual fire time.
- Every real fire records: `scheduledInstant` and `actualFiredAt` (the rung against `ALARM_FIRED`'s `deviceTimestamp`, in the shared log), and, in the android store (ADR 48), the resolved tier, whether the screen turned on, whether audio focus was obtained, battery and Doze state at fire, whether the watchdog had to repair a missing alarm, the boot count, which delivery path the fire took and whether the ringer started (ADR 60).
- If drift exceeds threshold or canaries are missed, surface a banner with the device specific fix path.

This is also the test harness. It makes the six items in the automation ceiling (see `IMPLEMENTATION_PLAN.md` section on testing) observable passively during normal use instead of requiring manual device procedures.

In v1, telemetry is stored locally and displayed in app with an export. Local records are always kept: the reliability view needs them, and they stay on the device. The opt-in gates upload only, which begins once the backend and privacy policy exist. The store is not backed up (ADR 48).

---

## 6. Backend

### 6.1 Why there is a server at all

A device only caregiver alert cannot work. If her phone is dead, in a drawer, or out of battery, the device cannot tell anyone she missed a dose. That is precisely the case the caregiver feature exists for.

So missed dose detection is a server side dead man switch. Silence from the device is itself the signal.

### 6.2 Design

- Device uploads the next 48 hours of expected occurrences with deadlines, plus events as they happen, through an **outbox** table. Client generated UUID primary keys, unique index server side, at least once delivery, idempotent writes.
- Server stores expected occurrences with a `sweep_status` column: `AWAITING`, `SATISFIED`, `MISSED_CONFIRMED`, `SUPERSEDED`. A sweep runs every 5 minutes; any occurrence past `deadline + grace` still `AWAITING` transitions to `MISSED_CONFIRMED` and notifies the caregiver. `sweep_status` is server-only sweep bookkeeping — it is not part of the shared event vocabulary and has no representation in `shared`. It is a distinct concept from the device's own `MISSED` state (§3.2): `MISSED` is the device's belief, computed locally; `MISSED_CONFIRMED` is the server's independent corroboration that the device went silent.
- The one new event type this produces in the shared vocabulary is `CAREGIVER_NOTIFIED` (§3.3), written server-side when a caregiver is actually notified and synced back down so the app can show her that it happened. This is not a platform capability difference, so it does not violate invariant 5 — every platform that talks to the server gets the same event.
- **No in memory timers.** A durable sweep over a table is correct, testable, and survives restarts.
- Five minute granularity is sufficient because grace windows are measured in hours. This lets Cloud Run scale to zero and accept JVM cold starts instead of paying to keep a container warm.

### 6.3 Clock authority

The device schedules against its own clock; the server evaluates deadlines against its own. A user who changes their clock, or a phone with drifted time, otherwise produces phantom missed doses.

- Every event records both `deviceTimestamp` and `serverReceivedAt`.
- The server trusts its own clock for the dead man switch.
- Skew above a threshold surfaces a warning in the app.

### 6.4 Stack

| Concern | Choice |
|---|---|
| Service | Ktor, Kotlin, depending on `shared` jvm target |
| Database | Cloud SQL for PostgreSQL, smallest shared-core tier, `asia-south1` |
| Runtime | Cloud Run, `asia-south1`, min instances 0 |
| Scheduler | Cloud Scheduler, 5 minute sweep endpoint |
| Registry | Artifact Registry |
| IaC | Terraform for the entire stack |
| CI/CD | GitHub Actions with Workload Identity Federation. No long lived service account keys. |
| Auth | Firebase Auth, Google sign in and email. Not phone, since SMS costs money per message in India and buys nothing. |
| Push | FCM |
| Crash | Crashlytics |

No Firestore. No Cloud Functions. Domain data lives in Postgres behind Ktor, because the server must reuse the exact same recurrence and escalation code the app uses. Reimplementing deadline logic in a second language is precisely the class of bug this architecture exists to prevent.

Region is a latency and cost decision, not a legal one. India's DPDP framework uses a negative list: Rule 15 of the DPDP Rules 2025 permits transfer outside India unless the Central Government restricts it, with no adequacy decisions or contractual clauses required, and no blanket localisation mandate. The cross border provisions sit in the eighteen month commencement phase from the 13 November 2025 notification and are expected to become operative around 13 May 2027. Re verify before launch; this is moving.

Cloud SQL is not itself scale-to-zero — the real problem is Cloud Run's instance churn against Cloud SQL's fixed `max_connections` ceiling. A scale-to-zero *compute* runtime opening and closing connections against a small shared-core instance is a real problem, not a checkbox, and it is solved by sizing the pool to `max_connections / max_instances` and capping `max_instances` low (3), through the Cloud SQL connector rather than a raw TCP connection string. It gets its own ADR (28), which is where this reasoning is recorded in full — one vendor, one Terraform provider, one IAM story, private connectivity, no second cloud account for a solo project.

### 6.5 Retention

Server keeps a rolling 90 days of mirrored occurrences and events, then hard deletes. The device keeps everything — the device log is the record of authority, a server row is a mirror, so purging a mirror row loses nothing.

Because a hard-deleted event's id was also its outbox idempotency key, a late retry of an event older than the retention boundary would otherwise be treated as new and re-inserted. The server keeps a **retention watermark**: an outbox write for an event with `deviceTimestamp` older than the watermark is acknowledged and discarded, never re-inserted. Device outbox entries expire at the same boundary so they stop retrying. See invariant 4 and ADR 26 for how this fits the append-only rule.

---

## 7. Caregiver

- Up to 3 caregiver links, each with independent field scope.
- She owns the account and the data. A caregiver joins via a short lived invite code she generates and has no independent account beyond the link.
- **Read only, always.** No caregiver can mark a task done on her behalf. There is no caregiver write path, so there is no conflict resolution to design.
- Sync is one way. Device is the source of truth; server is a mirror plus a detector.
- Field level scope, not all or nothing. Default visible: task title, scheduled time, state. Default hidden, each behind its own toggle: weight, notes, doctor instructions.
- Notification policy per caregiver, controlled by her: per event for `CRITICAL` misses, digest for everything else, or fully off so the caregiver checks in when they want. She can switch everything to digest or disable notifications entirely.
- Pause sharing: suspends visibility for a configurable period without revoking the link. Without this the only exit is revocation, which is a confrontation.
- Revocation is immediate and destructive: hard deletes that link's access and its mirrored data server side, not a soft flag.
- Caregiver notifications are never paywalled. There is no paywall in v1 at all, and if one is ever added, safety notifications stay outside it.
- The caregiver view is a screen in the same Android app behind a role flag, not a separate client.

---

## 8. Reports and export

- PDF reports generated from real collected data, not blank templates. A blank printable planner competes with the app.
- Contents: adherence over a period, nutrition tag counts, water history, weight chart, event detail. Landscape A4, printable from the phone.
- Adherence is reported as three separate figures: completed, missed, skipped. Never rolled into one percentage that hides skips. A doctor can use three numbers; a single percentage is worse than nothing.
- Where a single figure is needed, adherence is `completed / scheduled`, with skips shown adjacent.
- **No punitive streaks.** A consecutive day counter that resets to zero is a guilt mechanism aimed at a woman who missed a dose because she was vomiting. Replace with "days in the last 30 where all critical tasks were completed". Same information, no cliff.
- Export: CSV of the event log plus the PDF summary. Required for DPDP and both stores.

---

## 9. Localisation and accessibility

English, Hindi, Marathi in v1. All strings externalised from the first commit.

- Bundle Noto Sans Devanagari. Do not rely on system font fallback.
- Line heights set per locale. Heights tuned for Latin clip matras in Devanagari.
- Expect 20 to 30% text expansion. Buttons and the ring screen wrap; they never truncate. No fixed width label containers.
- Use `plurals` resources. Hindi and Marathi have their own plural categories. Never build strings by concatenation.
- Never translate user entered data. Medicine names and doctor instructions stay verbatim in whatever script she typed.
- Locale follows device by default, with an explicit in app override. A phone set to English by a shopkeeper is common.
- Health adjacent copy in all three languages requires human review before release. Machine translated medical phrasing is both a store review risk and a real safety risk.
- Every screen must render correctly at 200% font scale in all three locales. This is a release gate, verified by screenshot tests.

---

## 10. Compliance

### Both stores

- Medical disclaimer in app and in both store listings, in all three languages: not a medical device, does not diagnose, treat, cure or prevent any condition, consult a healthcare professional.
- Privacy policy at a publicly accessible URL, reachable without installing the app and not behind a login. Hosted on GitHub Pages, versioned in this repo so policy and code cannot drift. Also available in app.
- Never compute or suggest a dose. App Store guideline 1.4.2 requires drug dosage calculators to originate from the manufacturer, a hospital, university, insurer or approved regulatory body. The app records what the doctor prescribed and what she did. It does not reason about medication.
- Any health or wellness guidance requires a cited, user reachable source in app under App Store guideline 1.4.1. Content tables therefore carry non null `sourceName` and `sourceUrl` from schema version 1, even though v1 ships no such content. That is what makes the requirement satisfiable later without a migration.

### Play specific

- Health apps declaration in Console.
- Data Safety form.
- New personal developer accounts must run a closed test with at least 12 testers opted in continuously for 14 days before applying for production. The counter resets if the number drops. Start this clock as soon as the app is installable.

### Data protection

- Local first. No account required for core use. Sign in only when creating a caregiver invite.
- Telemetry opt in, explained, fully functional if declined.
- Real deletion paths: per record, per category, and full account.
- No PII in logs anywhere, client or server.

---

## 11. Technical choices, briefly justified

| Choice | Reason |
|---|---|
| Native Kotlin and Compose, not Flutter | Every hard requirement is platform specific and would be written in Kotlin behind a plugin anyway. Flutter's payoff is iOS, which needs a different delivery mechanism regardless. |
| SQLDelight in `shared`, not Room | Mature KMP path. The iOS port inherits the data layer instead of reimplementing it. Schema, queries and migrations live in shared code. |
| kotlinx-datetime with an injected `Clock` | The injected clock is what makes the entire time dependent test suite deterministic. |
| Koin | Hilt cannot reach into the shared module. |
| `applicationId` `com.momtime.android` | Decided at Phase 0. GCP and Firebase project ids are deferred separately — see `docs/IMPLEMENTATION_PLAN.md` Open Items. |
| `minSdk` 29 | Android 10 is where background activity start restrictions began, which is the behaviour model being designed against. Removes substantial legacy notification branching. |
| `targetSdk` latest stable | Required for Play, and the permission behaviours above assume it. |
| No RRULE | The engine expands to concrete instants. A parser would be a swamp with no benefit. |
| Epoch millis plus IANA zone id | Never store a formatted local time. A wall clock intent and an instant are different things. |

### Time handling

`timeOfDay` plus `timeZoneId` is a local wall clock intent. `AlarmManager` needs an instant. Recompute instants on timezone and time change broadcasts.

Explicit decisions, tested:

- Template edited mid day: terminal occurrences are untouched. Only pending and future occurrences change.
- User travels and the local time is already past the scheduled time: a late rung is a silent notice beyond the 30 minute window, and `MISSED` is derived at the end of grace.
- Asia/Kolkata has no DST. Test priority order is therefore: reboot, permission revocation mid schedule, OEM kill, template edit against terminal occurrences, then timezone and DST.

---

## 12. Constraints that keep the iOS port additive

These cost Android nothing. They are the whole mechanism by which iOS later becomes weeks of new work rather than a rewrite.

1. No `android.*` types, no `Context`, nothing platform specific in `shared`. It must compile and test on the JVM. This holds with no exception in v1: `shared` declares no `android` KMP target, so there is no `androidMain` source set for a platform actual to live in — the ban is on the whole module, literally, not a scoped rule (§2, ADR 27).
2. Schema and event vocabulary are platform neutral.
3. The escalation engine emits the full ladder declaratively. Android consumes the head; iOS will pre schedule the list and cancel the tail.
4. Occurrence state transitions happen only in the domain layer. No UI surface mutates state directly.
5. Mission config is a neutral typed structure.
6. Alarm sounds are `.wav`, under 30 seconds, loopable.
7. Health content tables carry citation fields from schema version 1.
8. All strings externalised.
9. Platform exclusive features are labelled from an enum, so the iOS build renders an honest degraded variant rather than a missing button.

What these do **not** constrain: the Android ring screen, mission gated dismissal inside it, volume ramp, backup sound, widgets, quick settings tiles, per template vibration patterns, OEM deep links, reliability score. Build all of that as richly as you want. Android is maximal above the capability line.
