# 0082. Navigation by a small back stack, a UI graph instead of koin-android, and the locale override

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by the Phase 3 planning session, for review.

## Decision

1. **Navigation is a back stack of a sealed `Screen` type** held in a `SnapshotStateList`, saved with `rememberSaveable`, with `BackHandler` popping it. Three top level destinations (Today, Dashboard, Settings) are tabs of the bottom navigation, whose central action opens the schedule builder; everything else is pushed. About fifty lines. `ScreenRegistry` enumerates every `Screen`, and marks which are top level and which are onboarding, which is what the switcher test and the screenshot matrix walk.
2. **No navigation library, no `koin-android` and no `koin-compose`.** `MomTimeApplication` builds a `UiGraph` from Koin once and installs it in `UiEntryPoint`, exactly as `RingEntryPoint`, `SetupEntryPoint` and the others are installed; composables receive models from it as parameters. `koin-android` stays declined: nothing here needs it.
3. **The locale override** (English, Hindi, Marathi, or follow the device) is kept in `momtime_android_settings` (key `locale_override`), because it must be read synchronously before an Activity's first frame, as the appearance is. It is applied the same way on every API level from 29 to 36: `MainActivity`, `RingActivity` and the Application wrap their base context in a configuration with that locale (`attachBaseContext`), and every string the app shows outside an Activity (notifications, the widget, the tile) is read through one `LocalizedContext` helper. Changing it recreates `MainActivity`; the back stack survives through `rememberSaveable`. `android.app.LocaleManager` (API 33) is not used, so there is one mechanism and one source: when she has set no override, the device's language, or the system's per app language on Android 13 and above, applies.
4. **`app_settings.locale_override` and `app_settings.telemetry_opt_in` in the shared schema stay unused.** The live opt in has been `AndroidSettings.shareReliabilityOptIn` since Phase 2 (`ARCHITECTURE.md` section 5.10). Removing two columns would be a migration that buys nothing; they are recorded here so that nobody wires a second source.

## Alternatives considered

- **`navigation-compose`.** Rejected: a dependency for three tabs and a stack.
- **`AppCompatDelegate.setApplicationLocales`.** Rejected: it needs `appcompat`.
- **The override in the shared database.** Rejected: a database read on the main thread before the first frame.
