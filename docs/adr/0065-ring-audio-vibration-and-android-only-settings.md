# 0065. The volume ramp, the backup sound, vibration, and where android's own settings live

Date: 2026-10-04
Status: Accepted

Decided by Claude (technical review) in the PR 5b prompt (the ramp on the player only, the backup sound, vibration by criticality, android only settings in a backed up preferences file, the record of a muted stream); the numbers and names below are the implementing session's, for review. It builds on ADR 0061 (the ringer and its audio) and ADR 0048 (device state is android's).

## Decision

**The volume ramp is on the player and nowhere else.** The ringer starts the primary sound at 30 percent of player volume and raises it in 12 steps, every 500 ms, to full player volume (6 seconds). The app never changes the system alarm stream volume: that is hers, and a ramp that raised it could make a ring louder than she set her phone to. `VolumeRamp` is a function of the step count, never of a clock. A muted stream stays muted, and a test shows the stream's volume is the same before, during and after a ramp and a backup sound. The ramp is the onset of one ring, not an escalation: the ladder's rule that escalation widens reach and does not raise intensity (ADR 0011) is about rungs, and nothing here enters the ladder or the engine.

**A louder backup sound after an unacknowledged interval.** If the ring is still going when the interval has passed, the backup sound replaces the primary at full player volume. It is louder in the file (about 4.4 dB, full scale against 60 percent, with five beeps alternating two tones against three of one), because the stream volume is never raised. The interval is 2 minutes by default and can be turned off. It is measured from the start of the ring and is cancelled when the ring stops: the sound is stopped, or the last occurrence in the session is acted on. If the backup cannot be played the primary keeps going.

**The sounds are placeholders** from the same committed generator, `scripts/sounds/GenerateSounds.java`, which now writes `ring_primary.wav` (byte for byte as before) and `ring_backup.wav`: 16 bit mono PCM at 22050 Hz, exactly 2.0 seconds, loopable, synthetic, with no author or licence to credit. Shubham chooses the final sounds.

**Vibration.** A pattern (`VibrationPattern`: `URGENT`, `STEADY`, `LIGHT`, or `NONE`) per template, defaulting by criticality (`CRITICAL` urgent, `STANDARD` steady, `GENTLE` light). It plays with the alarm usage (`AudioAttributes.USAGE_ALARM`, the same attributes as the sound) while the ringer service runs, repeats until the ring stops, and is stopped with the sound. `VIBRATE` is declared and is in `verifyManifestPermissions`'s allowlist with its reason: a normal permission granted at install, not a Play declaration. A ring session vibrates with the pattern of the occurrence that started it. The patterns are placeholders for hardware to judge (`MANUAL_CHECKS.md` P2-21).

**Android only settings live in an android owned `SharedPreferences` file that is included in backup.** The backup interval and the per template vibration patterns are Android only (`ARCHITECTURE.md` sections 5.5 and 12), so they cannot enter the shared schema (invariant 5); and they are her choices, so they cannot live in the android store, which is excluded from backup (ADR 0048). The file is `momtime_android_settings` (`shared_prefs/momtime_android_settings.xml`). The backup rules are an allow list, so both rule files now include `<include domain="sharedpref" path="momtime_android_settings.xml" />` explicitly. A test writes the file through the code and checks that the rules name the file the code wrote, and `BackupRulesTest` pins the exact include list. Their settings screen is Phase 3; until then every value is its default and nothing in the app writes them. A template id is a key in the file and is never logged (invariant 11). An unknown stored value (from a newer version) reads as the default.

**A muted alarm stream is recorded.** At every fire the delivery port reads the alarm stream's volume and records whether it was zero in the android store, in `fire_telemetry.alarm_stream_muted` (android store schema version 4, `migrations/3.sqm`, with forward migration tests from versions 1, 2 and 3). It is recorded for every path, not only the ones with a ringer. Nothing is done about it here; the reliability banner that tells her is PR 7.

## Alternatives considered

- **Raise the alarm stream volume for the ring.** Rejected: the volume is hers, and raising it surprises her and breaks her choices.
- **Keep the interval and the patterns in the android store.** Rejected: the store is excluded from backup, so a restored phone would lose her choices.
- **Keep them in the shared schema.** Rejected by invariant 5 and by the review.
- **Jetpack DataStore.** Rejected: a new dependency (invariant 7) for two small values.
- **A ramp as a function of elapsed time.** Rejected: android code reads no clock outside the DI package (invariant 8), and a step count is deterministic in a test.

## Evidence

`RingSoundTest` (the ramp's levels, the sound rising to full player volume, the stream volume unchanged before, during and after, a muted stream staying muted, the backup only after the interval, the interval honoured and off, stopping cancelling both, a missing backup leaving the primary, the two files compared), `VibrationTest` (the alarm usage as the shadow recorded it at SDK 29, 33 and 36, stopping, no vibrator, the defaults, her choice, an unknown name, the pattern asked for through the fire path and the service, stopping with the sound), `MutedStreamTest`, `BackupRulesTest`, `AndroidStoreMigrationTest`, `verifyManifestPermissions`. Mutations are in `phase-2-traceability.md`. What a speaker and a motor do is `MANUAL_CHECKS.md` P2-20 and P2-21.
