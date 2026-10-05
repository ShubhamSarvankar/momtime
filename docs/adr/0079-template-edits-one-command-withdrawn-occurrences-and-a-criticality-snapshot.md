# 0079. Template edits: one command, withdrawn occurrences, and criticality kept on the occurrence

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by the Phase 3 planning session, for review. The constraints are Claude (technical review)'s, from the planning prompt (section 4 a): terminal occurrences untouched; the log append only; nothing counted as skipped or missed because of an edit; slots and ids never reused; the whole edit one transaction. It builds on ADR 0036 (atomic materialisation), ADR 0037 and ADR 0068 (terminal occurrences immutable in full; a zone change moves open occurrences in one transaction), ADR 0040 (adherence over the log), ADR 0053 (the count of fired rungs) and ADR 0066 (snooze). It changes shared schema version 5 to 6.

## Context

Phase 1 and 2 have no template edit path: `ScheduleTemplateRepository` offers `insert`, `setActive` and `updateTimeZone`. Golden scenario 1 was counted without ever changing `timeOfDay`, and scenario 12's interleaved edit is an activation toggle. Deactivating a template leaves its open occurrences ringing, which the debug seed relies on. Criticality is read live from the template by `Reconcile`, arming, delivery and `criticalCompletionDays`, so a change of criticality would change a ladder in mid flight, could end an occurrence's grace in the past, and would rewrite the 30 day figure for days already lived.

## Decision

**1. One command.** `EditTemplateCommand` (in `shared`, `data`) is the only way a template changes after it is created, deactivation included. `ScheduleTemplateRepository.setActive` is removed from the interface; `updateTimeZone` stays for the zone change command alone. The command takes the edited template and `now` from the injected clock, and runs in one database transaction (`Transactor`), so a materialisation run or one of her actions lands entirely before it or entirely after it (ADR 0036, ADR 0068).

**2. An occurrence has come due** when its `scheduledInstant` is at or before `now`, or it has at least one `ALARM_FIRED` event, or it is `SNOOZED`. An occurrence that has come due has reached her, or is about to under the rules it was armed with.

**3. Deactivation and removed dates are one question, with one answer: the occurrence is withdrawn.** For every open occurrence (`PENDING` or `SNOOZED`) of the template whose `localDate` the edited template no longer wants (the template is inactive, or its recurrence does not include that date), the command appends a `WITHDRAWN` event (source `USER`, no payload) and sets the state `WITHDRAWN`, through `OccurrenceRepository.transition`, whether or not the occurrence has come due. `WITHDRAWN` is a sixth occurrence state and it is terminal: the schema's trigger refuses any later update to the row. It is not an outcome: the adherence reductions ignore the event, so a withdrawn occurrence is in none of the three figures and in no nutrition count. Nothing is deleted and no event is rewritten.

**4. A wanted open occurrence that has come due is not changed by the edit at all**: not its instant, not its criticality. It finishes under the rules it started with. Its title, dosage and doctor's instructions are read from the template when shown, so those change at once; they are display only.

**5. A wanted open occurrence that has not come due takes the edit:** its criticality becomes the template's, and its instant becomes its local date at the new `timeOfDay` in the template's zone, **unless that instant is before `now`, in which case it keeps the instant it has**. An edit never moves an occurrence into the past, so an edit never makes an occurrence overdue, and none becomes `MISSED` because of one. Moving the time earlier than now therefore takes effect from the next date. A moved occurrence keeps its id, `localDate`, `alarmSlot` and state, as in ADR 0068, and the move writes no event. This is ADR 0068's reschedule without its clamp: travel moves an occurrence to `max(new, now)` because she did not choose the change; an edit she chose never needs to ring now.

**6. Criticality is kept on the occurrence.** `occurrence.criticality` is set from the template when the occurrence is materialised and changed only by rule 5. The escalation ladder, grace, the notification channel, the default vibration and `criticalCompletionDays` read the occurrence's criticality and never the template's. So a ladder never changes in mid flight (the count of `ALARM_FIRED` events can never exceed the ladder it is counted against), grace never ends in the past because of an edit, and a day already lived keeps the criticality it had. This is the same kind of snapshot as `occurrence.timeZoneId`.

**7. A date wanted again gets a new occurrence.** The unique index on `(template_id, local_date)` becomes partial, `WHERE state <> 'WITHDRAWN'`, and the materialiser ignores withdrawn rows when it reads the dates already materialised. So reactivating a template, or adding a date back, materialises a fresh occurrence with a new id and a new slot for any instant still inside the window and not before `now`. A slot or an id is never reused, and a mistaken deactivation is repaired by reactivating: only instants already past are not recreated.

**8. After the command, android runs one pass inside the coordinator's exclusion** (`TemplateEditPass`, as `TimeZonePass` does): the command, `MaterialiseCommand`, `ReconcileCommand`, then for each withdrawn occurrence the ring session drops it and its notification is cancelled, then `ensureArmed`. The alarm is correct when the edit screen returns. `Work.enqueueMaterialisation` is not called by the edit path; it stays, tested, for a caller that cannot run the pass inline.

**9. The other fields** (title, notes, task type, dosage, doctor's instructions, nutrition tags, inventory, refill threshold) are written to the template and touch no occurrence. `mission` stays `MissionConfig.None` (ADR 0088). `pregnancyId` and `id` cannot be edited. Nutrition tags of days already completed do not change (ADR 0086).

**10. Reliability evidence.** A rung armed ahead for an occurrence that was then withdrawn, or moved by an edit, is not a never fired rung: `FireTiming` treats `WITHDRAWN` as it treats `COMPLETED` and `SKIPPED` before the rung, and a re arming at a new instant as it treats a zone change.

**11. The debug seed.** Its test reminder was an inactive template with one open occurrence, a state this model does not allow. The seed now creates its one off as an active template with `Recurrence.EveryNDays(n = 3650, anchorDate = today)`: no `Recurrence` value expresses a one off and none is added, and a ten year interval is one for a debug tool.

**Schema version 6** (`migrations/5.sqm`): drop the terminal trigger; add `occurrence.criticality TEXT NOT NULL DEFAULT 'STANDARD'` and fill it from each occurrence's template (an administrative update inside the migration, which is why the trigger is dropped first); replace the unique index with the partial one; recreate the trigger with `WITHDRAWN` among the terminal states; add the two event columns of ADR 0086. Every statement runs on the SQLite 3.22 floor. Forward migration tests run from versions 1 to 5 with foreign keys on.

## Where ADR 0068 applies and where it does not

Applies: one transaction; id, slot, local date and state kept on a move; no event for a move; terminal rows untouched and refused by the schema; fired rungs stay consumed. Does not apply: the clamp (rule 5); "every template, active or not" (an edit is to one template); and an inactive template no longer has open occurrences, so the zone change command's handling of them becomes a case that cannot arise, which a test of this ADR asserts.

## Alternatives considered

- **Leave open occurrences of an inactive template alone** (today's behaviour). Rejected: they keep ringing, and at the end of grace they are `MISSED` because of an edit.
- **Delete unwanted open occurrences.** Rejected: events reference them, and deleting events breaks the append only log.
- **Mark them `SKIPPED`.** Rejected by the constraint: an edit must not count as a skip.
- **A derived "dormant" condition with no state** (readers ignore open occurrences the template does not want). Rejected: every reader must remember the filter, the rows stay open for ever, and reactivating after the instant has passed makes them `MISSED`.
- **Read criticality live and guard the edge cases.** Rejected: there is no guard for a grace that the new criticality ends in the past, short of a per occurrence value, which is the snapshot.
- **Clamp a moved occurrence to `now`** as travel does. Rejected: she would be rung the moment she saves an edit.
- **A one off recurrence value** for the seed. Rejected: a schema and vocabulary change for a debug tool.

## Evidence

To be recorded in `docs/phase-3-traceability.md`: golden scenario 1 and scenario 12's interleaved edit, specified at assertion level in `docs/phase-3-plan.md` (PR 3).
