# 0078. Exported components are an exact allowlist; "no receiver is exported" is scoped

Date: 2026-10-05
Status: Accepted (2026-10-05, in the review of PR #26 by Claude (technical review))

Decided by Claude (technical review) in the Phase 3 planning prompt (D10). The finding about library components is the planning session's.

## Context

Phase 2's rule that no receiver is exported is about the app's own receivers of system broadcasts and of its own `PendingIntent`s (ADR 0053, 0066, 0067, 0069). Phase 3 needs exported components. The merged release manifest also already holds exported library components that no check names: WorkManager's `SystemJobService` (guarded by `BIND_JOB_SERVICE`) and `DiagnosticsReceiver` (guarded by `DUMP`). Compose brings a third, `androidx.profileinstaller.ProfileInstallReceiver` (guarded by `DUMP`). All three were read from the spike's merged release manifest.

## Decision

1. **The Phase 2 rule is scoped:** every component of the app that receives an alarm, a system broadcast or one of the app's own `PendingIntent`s stays unexported. The per component tests stay.
2. **`verifyExportedComponents` holds the set of exported components of the merged release manifest as an exact allowlist, in both directions**, each entry as `tag|name|permission`:
   - `activity|com.momtime.android.ui.MainActivity|` (the launcher);
   - `receiver|com.momtime.android.widget.WaterWidgetProvider|` (no permission exists for a widget provider; it acts only on the app widget actions);
   - `service|com.momtime.android.tile.WaterTileService|android.permission.BIND_QUICK_SETTINGS_TILE`;
   - `service|androidx.work.impl.background.systemjob.SystemJobService|android.permission.BIND_JOB_SERVICE`;
   - `receiver|androidx.work.impl.diagnostics.DiagnosticsReceiver|android.permission.DUMP`;
   - `receiver|androidx.profileinstaller.ProfileInstallReceiver|android.permission.DUMP`.

   An exported component missing from the list, a listed one that is absent or no longer exported, or a changed permission fails the build. A component with an intent filter and no `android:exported` fails too. A fixture self test proves each direction, as the other manifest checks do. Both run in the `verify-android-structure` CI job. Entries are added in the pull request that adds the component.
3. **Neither the widget's tap nor the tile hands a command to an exported component:** the tap is an explicit immutable `PendingIntent` to an unexported receiver, and the tile service is bound only by the system.

## Alternatives considered

- **An unexported widget provider**, as the boot receiver is. The system sends app widget broadcasts as the system uid, so it may work; it is unverified and unconventional, and the allowlist makes the exported one explicit.
