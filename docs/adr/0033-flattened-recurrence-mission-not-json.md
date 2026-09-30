# 0033. Recurrence and mission are flattened columns, not a JSON payload

Date: 2026-09-30
Status: Accepted

## Context

`Recurrence` and `MissionConfig` are sealed types with variant-specific fields (`Weekly.daysOfWeek`, `EveryNDays.n`/`anchorDate`, `Barcode.expectedPayload`, `PhotoMatch.referenceHash`). The two obvious schema encodings are a single JSON/serialized-blob column, or flattening each variant's fields into nullable columns on the owning row.

## Decision

Flattened nullable columns on `schedule_template`, with `CHECK` constraints enforcing the variant shape in both directions (required fields non-null for the active variant, inapplicable fields null for every other variant) — see the columns and constraints on `schedule_template` in the schema.

The deciding reason is invariant 2, not dependency avoidance. SQLDelight migrations are `.sqm` SQL files, and there is no way to write a testable SQL migration that reshapes the internal structure of a JSON blob stored in a single TEXT column — a JSON payload lets the effective schema mutate with no migration file and no migration test, which is exactly the drift failure mode invariant 2 exists to prevent. Flattened columns keep every shape change a normal, testable `ALTER TABLE` migration. That kotlinx-serialization is also not currently an approved dependency (invariant 7) is a real secondary point, but not the deciding one.

## Alternatives considered

- A JSON/serialized blob column, with `kotlinx-serialization` — rejected on invariant 2 grounds above, independent of the dependency question.
- Flattened columns with no `CHECK` constraints, relying on application-layer validation — rejected; nothing would prevent `recurrence_type='DAILY'` with a populated `recurrence_n`, and the same reasoning that put a `UNIQUE` index on `(template_id, local_date)` for golden scenario 12 (a schema-level guarantee instead of application discipline) applies here.

## Consequences

Adding a new `Recurrence` or `MissionConfig` variant later is a normal migration: new nullable columns, an updated `CHECK` constraint, a migration test. The `CHECK` constraints mean a bug that would otherwise silently write an inconsistent row (e.g., a `WEEKLY` template with no days set) fails at insert time instead of being discovered later by a confused query.
