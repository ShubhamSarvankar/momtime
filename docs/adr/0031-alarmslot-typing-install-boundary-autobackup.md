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
