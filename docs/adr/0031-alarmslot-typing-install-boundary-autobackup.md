# 0031. alarmSlot typing, the install boundary, and Auto Backup exclusion

Date: 2026-09-29
Status: Accepted

## Context

`alarmSlot` is the monotonic column `PendingIntent` request codes derive from (ADR 0018), but neither its concrete type nor its behaviour across an uninstall/reinstall or a cross-device backup restore was specified. A Phase 0 planning review raised the concern that a reset counter could, in principle, collide with a `PendingIntent` request code the OS still associates with a previous install on the same device.

## Decision

`alarmSlot` is `Int`, since `PendingIntent` request codes are `Int`. It's monotonic per install, allocated from a counter row. The collision concern doesn't actually materialise: uninstalling an app cancels its alarms outright, and `MY_PACKAGE_REPLACED` handling (already required, `ARCHITECTURE.md` §5.2) exists precisely to re-materialise and re-arm after an app update — so there is no scenario where a stale `alarmSlot` value outlives the alarm it referred to. Two additions close the remaining gap: alarm-state tables and the slot counter are excluded from Android Auto Backup, so a restore onto a *different* device can't import slot values that refer to nothing there; and with only one alarm ever armed at a time (ADR 0017), a single fixed request code would technically be sufficient for correctness — the slot is kept anyway because it lets the `WorkManager` watchdog verify the armed alarm is the *correct* one (matching the expected next rung), not merely that *some* alarm exists.

## Alternatives considered

- `Long` for `alarmSlot`, to future-proof against exhausting `Int` range — rejected; `PendingIntent` request codes are `Int` by platform contract, so a `Long` column would need truncation at the point of use anyway, which reintroduces a collision surface rather than removing one.
- Persisting `alarmSlot` values through Auto Backup for continuity across a device migration — rejected; the alarms those slots refer to don't survive a device migration either (they're OS-level state on the old device), so restoring stale slot values on a new device would create confusion with no benefit.

## Consequences

The watchdog's correctness-verification role (not just existence-verification) is the actual reason `alarmSlot` exists given single-alarm-at-a-time scheduling, and this ADR is where that reasoning is recorded so a future simplification pass doesn't remove it thinking it's redundant.

## Narrowing note (2026-09-30)

The Auto Backup file-exclusion mechanism described above and in the schema review that followed doesn't work as stated: Android excludes whole files from Auto Backup, not individual tables within one SQLite database file, so "alarm-state tables are excluded" was never mechanically achievable without a second database file this project doesn't otherwise need.

Re-examined instead of worked around: the original collision concern (a restored/reset counter colliding with a `PendingIntent` the OS still holds) doesn't reproduce under closer scrutiny. `PendingIntent`s are OS-level state that Auto Backup never restores in the first place — only the SQLite rows describing what the app *believes* it armed come back. `Reconcile` (ADR 0030) treats a restored `alarm_slot` value as an identifier to verify against, not a claim about OS state, and repairs it exactly as it would repair any other missing alarm. No harmful scenario could be constructed.

**Narrowed decision:** drop the file-exclusion mechanism for `occurrence` and `alarm_slot_counter`. They are backed up along with the rest of the database under ADR 0034's Auto Backup decision, with no special handling. The "Alternatives considered" entry above rejecting backup persistence on collision grounds is superseded by this note; the underlying `Int`, monotonic-per-install, watchdog-verification reasoning is unchanged.

Separately: `ARCHITECTURE.md` §4.1's `EscalationRung` data class carries its own `slot: Int` field, which reads as *per-rung* slot allocation — inconsistent with this ADR's *per-occurrence* decision (one `alarmSlot` shared and re-armed across all rungs of that occurrence's ladder, since only one alarm is ever armed at a time regardless of which rung it represents). The per-occurrence reading is correct: reusing one request code per occurrence is what makes "re-arm the next rung" a natural replace-in-place rather than requiring explicit cancellation of a prior rung's distinct code. `EscalationRung.slot` is removed from the type; the engine reads `occurrence.alarmSlot` directly when arming. `ARCHITECTURE.md` §4.1 and §5.4 are corrected in the same commit as the Phase 1 schema.
