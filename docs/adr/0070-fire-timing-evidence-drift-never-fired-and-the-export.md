# 0070. Fire timing evidence: drift, never fired rungs, persisted counts, banner state and the export

Date: 2026-10-05
Status: Accepted

Decided by Claude (technical review) in the review of the PR 7 design report: drift as a pure reduction in `shared` with an explicit `asOf`, the boot count and clock change exclusions passed in as inputs, the definition of a never fired rung, persisted failure counts, the banner thresholds as constants to be tuned from the A15 soak, banner state computed in the data layer, the export, the device model in it, and the opt in. The join, the arming context, the schema and the wording of the report are the implementing session's, for review. It builds on ADR 0069 (real fires are the measurement), ADR 0040 (`asOf`), ADR 0048 (what android keeps), ADR 0054 (store failures) and ADR 0035 and ADR 0042 (migrations and the SQLite floor).

## Context

With real fires as the only passive evidence (ADR 0069), "how late did reminders arrive" and "did any reminder never arrive" have to be defined so that they mean something. A latency measured across a restart, a clock change or a catch up measures the phone being off, or the user, or the system's repair, and a banner raised on it would cry wolf on a night the phone was charging off. The store failure counts of ADR 0054 were held in memory, and so were lost with the process that saw them. The export is a document she hands to someone, so what it may carry is a rule, not a convenience.

## Decision

**Fire timing (`FireTiming`, in `shared`, pure, no clock).**

1. A **sample** is one `ALARM_FIRED` paired with the last `ALARM_SCHEDULED` written for the occurrence before it (stored order breaks a tie in timestamps). Its latency is `ALARM_FIRED.deviceTimestamp` minus the rung's instant.
2. The rung's instant is **what was armed**, not the occurrence's current instant: it is kept, keyed by the `ALARM_SCHEDULED` event's id, when the rung is armed (`arming_context` in the android store). A time zone change moves an open occurrence (ADR 0068) and must not rewrite a fire already measured. A fire whose arming has no context (an event from before this version) is `UNVERIFIABLE`.
3. A sample is **counted only if the rung was armed ahead of time**: strictly before its own instant. A rung armed at or after its instant (an overdue rung armed for now, by boot, the watchdog or a clock change) is a **catch up**, counted apart and never drift.
4. **Exclusions are an input.** Whether the phone restarted, or its clock was set, between arming and the fire is a fact the platform holds and the shared log does not, so the caller says it through a predicate that returns a reason (`BOOT_CHANGED`, `CLOCK_CHANGED`, `UNVERIFIABLE`). The reduction counts what it is told to exclude, by reason, and leaves it out of the samples.
5. A **never fired rung** is an occurrence's first device rung that was armed ahead of time, whose instant plus a tolerance (15 minutes, the inexact tolerance) had passed by `asOf`, with no `ALARM_FIRED` for the occurrence at or before `asOf`. An occurrence she completed or skipped before its first rung is not a failure (it has its own test); a completion after the rung does not excuse it. The first rung only, because it is the one that depends on nothing she did. It is held against the platform only if no restart and no clock change has intervened since the rung was armed (a phone off across a reminder is not an alarm failure); otherwise it is counted apart by reason.
6. Everything is **as of an explicit instant** (`asOf`, ADR 0040) over the last seven days. Days of the window with no reminder fire are not observed, and the report says how many (`daysWithFires`).

**What android keeps for the join (android store, version 5, `4.sqm`).** `arming_context` (the rung's instant, the boot count and the clock change count when it was armed, keyed by the `ALARM_SCHEDULED` event id), `clock_change` (one row each time the clock was set, written by the system pass before it arms), `fire_telemetry.clock_changes` (the count when the alarm fired), `reliability_check` (the check's history, ADR 0069) and `store_failure`. None of it is in the shared schema (invariant 5). A fire is excluded when the boot count or the clock change count at the fire differs from the one at arming, and when either is unknown.

**Persisted store failure counts.** `CountingStoreFailures` writes each failure through to `store_failure`, so the count survives the process that saw it; a failure that could not be written (the store is what is failing) is kept in memory until it can be, so the count is the persisted plus the unpersisted. The repository that holds the counts logs its own failures and never counts them back into itself. The upsert is two statements (`INSERT OR IGNORE`, then `UPDATE`): `ON CONFLICT ... DO UPDATE` needs SQLite 3.24 and the floor is 3.22 (ADR 0042), which the 3.18 dialect refuses to compile.

**Banner state is computed in the data layer, with tests at each boundary; the banner is Phase 3.** The thresholds are constants, to be tuned from the A15 soak (`MANUAL_CHECKS.md` item 5): a median latency of exact tier fires above 2 minutes; any exact tier fire above 5 minutes; one never fired rung; a failed check; a tier below 3, naming the input that is missing (exact alarm, notifications, the Critical channel, full screen intent, battery exemption); an alarm stream muted at a fire; a store failure or a corruption marker. A boundary itself raises nothing for the two latency limits; the counts raise from their first.

**The export** is one versioned JSON document, written through the system's document picker (`ACTION_CREATE_DOCUMENT`), so she chooses where it goes and nothing is sent anywhere. It carries the build, the phone model (a type of phone, not a person: Claude (technical review)), the figures, one row per counted fire (its tier, its latency and what the device was doing) and the check's history. It carries **no identifier** (an event, an occurrence or a template id) and **no text she typed**, and no time of day: a fire is dated by its day number alone, so the times she takes her medicine are not in the file (the implementing session's judgment). A test plants a medicine name, a dose, instructions, three kinds of id and the fire instant in the database and fails if any is in the file. It is written with the platform's `org.json`, which is part of Android: `kotlinx-serialization` is neither approved nor in the build, and fifty lines do not need a dependency (invariant 7).

**The opt in** is a boolean in her Android only settings, off by default. It gates upload only: v1 has no upload, local records are always kept, and a test shows the same fire leaves the same records either way. The copy says what the export carries, including the phone model, and that reminders work the same if it is off.

## Alternatives considered

- **Latency from the occurrence's instant.** Rejected: a time zone change moves it, and a measured fire would be rewritten after the fact.
- **Count catch up fires as drift.** Rejected: a rung armed for now after a restart measures the restart.
- **Exclude fires by comparing the boot count with the armed record at fire time.** Rejected: the armed record is replaced at every pass, so after a restart it already holds the new boot count. The count at the arming of that rung is what is needed.
- **Hold a never fired rung against the platform even across a restart.** Rejected: a phone that was off over a reminder would raise the headline banner every time.
- **Failure counts in a file or in memory.** Memory is what failed (a count lost with the process); a separate file was not chosen because the store already has the migration and test machinery (ADR 0035).
- **`kotlinx-serialization` for the export.** Not approved and not present (invariant 7); `org.json` is the platform's.
- **The time of each fire in the export.** Rejected: it would put the times she takes her medicine in a file she hands on.

## Limits, said plainly

- The clock change is counted when the system pass runs, not when the broadcast arrives, so a fire in the seconds between the two is not excluded. `MANUAL_CHECKS.md` P2-28 holds the device half.
- A fire whose telemetry row failed to store is unverifiable, not counted.
- A rung armed before this version has no arming context and is unverifiable, so the first days after an update show fewer fires than happened.
- Only the first rung is checked for never firing.
- None of this has been run on a device.
