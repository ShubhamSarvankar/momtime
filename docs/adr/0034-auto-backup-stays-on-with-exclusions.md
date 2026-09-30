# 0034. Auto Backup stays on, with exclusions — a separate decision from ADR 0031

Date: 2026-09-30
Status: Accepted

## Context

Android's Auto Backup is on by default for any app that doesn't opt out, and it uploads to the user's Google Drive. This app's on-device database holds pregnancy health data, medicine names, doctor instructions and weight — sensitive health data, and a real disclosure question independent of the alarm-state question ADR 0031 addressed. Nobody had raised it until a Phase 1 schema review did.

## Decision

Keep Auto Backup **on**, with narrow exclusions, rather than disabling it for the whole app.

Two reasons converge on "on":
- On API 28+, Auto Backup is encrypted client-side with the device lockscreen secret, so Google cannot read the backed-up content. `minSdk` is 29, so every user gets this without a version check.
- v1 has no account and no server-side mirror of her own data (the server mirrors for the caregiver dead-man switch, not for her own restore path). With backup off, a dead or lost phone means she loses her entire medication schedule and history outright — a worse outcome than encrypted-at-rest disclosure to a channel only she controls.

Exclude `alarm_delivery_telemetry`, `outbox_event`, and `sync_state` from the intent, but see the open question below — Android's `dataExtractionRules.xml` excludes by file, not by table, and all of this currently lives in one database file alongside data that should stay backed up. The practical resolution, absent a second database file: accept that `outbox_event`/`sync_state` get backed up too, since restoring stale sync state is self-healing (one redundant resync, no wrong behavior) by the same reasoning ADR 0031 uses for alarm slots. `alarm_delivery_telemetry` is lower-stakes than that — device-fingerprintable and worthless to restore, but not health data — and is accepted as backed up for the same single-file-simplicity reason.

Both items below go to Open Items now, not discovered at submission:
- Play Data Safety form (Phase 8) must declare that data is backed up.
- Privacy policy needs a line describing the Auto Backup behavior and its encryption.

## Alternatives considered

- Disable Auto Backup entirely — rejected; trades a real, immediate harm (total data loss on device loss, with no server-side restore path in v1) for a disclosure risk that's already mitigated by client-side encryption on every supported OS version.
- A second database file to get true table-level backup exclusion for `outbox_event`/`sync_state`/`alarm_delivery_telemetry` — rejected for now as unjustified structural complexity against a self-healing, low-severity concern; revisit if a concrete harm scenario for restored sync/telemetry state is found.

## Consequences

This is a distinct decision from ADR 0031, not an amendment to it — ADR 0031 was about whether restoring `alarmSlot`/`occurrence` state onto a different device is safe (yes); this is about whether the whole database should leave the device at all (yes, encrypted, because the alternative is worse for v1's no-account, no-restore-path reality).
