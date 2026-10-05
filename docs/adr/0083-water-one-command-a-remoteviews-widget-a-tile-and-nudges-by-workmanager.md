# 0083. Water: one command, a RemoteViews widget, a tile, and nudges by WorkManager only

Date: 2026-10-05
Status: Accepted (2026-10-05, in the review of PR #26 by Claude (technical review))

Decided by Claude (technical review) in the Phase 3 planning prompt (D8: a water nudge never arms an alarm; the widget and the tile write through a domain command only). The rest is the planning session's, for review.

## Decision

1. **`LogWaterCommand` (in `shared`) is the only writer of `WATER_LOGGED`.** It appends one event with the amount and the zone it was logged in (ADR 0086). The water screen, the widget and the tile all dispatch it. One tap logs one glass, `LogWaterCommand.GLASS_ML = 250`; the glass size is a constant, not a setting, in v1.
2. **The widget is framework `RemoteViews`, not Glance.** One size, one layout: today's total against her goal as text and a progress bar, and one button. The button is an explicit immutable `PendingIntent` to an unexported receiver, `WaterLogReceiver`, which dispatches the command off the main thread and updates the widget. The provider is exported, as a widget provider conventionally is, and is on the exported allowlist (ADR 0078). It follows the appearance: colours are set from `ColorRoles` at each update, and for the System appearance on API 31 and above with the platform's day and night colour setters, so it follows the device without the app running. On API 29 and 30 a System appearance widget changes at its next update; that is recorded, with a device row (P3-6).
3. **The tile is a `TileService`** guarded by `BIND_QUICK_SETTINGS_TILE`. A tap dispatches the command and refreshes the tile's subtitle with today's total.
4. **Nudges are WorkManager work, and never an alarm.** The one alarm invariant stands: reminders, and the check she starts. `WaterNudgeWorker` is unique periodic work, hourly, `KEEP`, a plain `Worker` under ADR 0057's rules. `WaterNudgePolicy` (pure, in `shared`) decides: the day's nudge times are spread evenly between 09:00 and 21:00 local for `nudgeTimesPerDay`, which is capped at 4; a nudge is posted if a nudge time has passed since the last one posted today, today's total is below the goal, and quiet hours do not cover now. At most one per run, so a phone that slept does not burst. Timing is approximate by design.
5. **A nudge is a plain notification on its own channel, `momtime.water`** (importance low, no sound), never on Critical, Standard, Gentle or Quiet notices. It has one action, which logs a glass through `WaterLogReceiver`. It spends no interruption budget, never escalates and is never a ring. The last nudge time is kept in a `SharedPreferences` file of its own that the backup rules do not include, with a test.
6. **Water is not adherence.** Nothing here writes an occurrence or reads one.

## Alternatives considered

- **Glance.** Rejected: a dependency, and it runs on coroutines, for one button.
- **An inexact alarm for nudges.** Rejected by D8.
- **Nudges on the Gentle channel.** Rejected: Gentle is for occurrences she made gentle (ADR 0064).
