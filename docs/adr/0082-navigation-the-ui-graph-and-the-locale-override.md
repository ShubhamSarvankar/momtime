# 0082. Navigation by a small back stack, a UI graph instead of koin-android, and the locale override

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by the Phase 3 planning session, for review.

## Decision

1. **Navigation is a back stack of a sealed `Screen` type** held in a `SnapshotStateList`, saved with `rememberSaveable`, with `BackHandler` popping it. Three top level destinations (Today, Dashboard, Settings) are tabs of the bottom navigation, whose central action opens the schedule builder; everything else is pushed. About fifty lines. `ScreenRegistry` enumerates every `Screen`, and marks which are top level and which are onboarding, which is what the switcher test and the screenshot matrix walk.
2. **No navigation library, no `koin-android` and no `koin-compose`.** `MomTimeApplication` builds a `UiGraph` from Koin once and installs it in `UiEntryPoint`, exactly as `RingEntryPoint`, `SetupEntryPoint` and the others are installed; composables receive models from it as parameters. `koin-android` stays declined: nothing here needs it.
3. **The locale override** (English, Hindi, Marathi, or follow the device) is kept in `momtime_android_settings` (key `locale_override`), because it must be read synchronously before an Activity's first frame, as the appearance is. API 29 to 32 have no framework per app locale, so the app applies it itself, the same way on every API level from 29 to 36, and **it reaches every string the app produces**. `android.app.LocaleManager` (API 33) is not used, so there is one mechanism and one source: when she has set no override, the device's language, or the system's per app language on Android 13 and above, applies. The mechanism for each surface:
   - **Activities** (`MainActivity`, `RingActivity`): `attachBaseContext` wraps the base context in a configuration with the override's locale. Changing the override recreates `MainActivity`; the ring screen reads it when next shown.
   - **Everything outside an Activity** reads strings through one helper, `LocalizedContext.of(context)`, which returns `context.createConfigurationContext` with the override's locale (or the context itself when there is none): every notification, whoever posts it (the fire path's receiver, the ringer service, the action receiver, the workers, the corruption handler's reset notification, the water nudge); the widget, whose `RemoteViews` text is set from it at each update and never from a layout's string reference; and the tile, whose label and subtitle are set from it in `onStartListening` and after a tap.
   - **Channel names and descriptions** are created through `LocalizedContext` by `NotificationChannels.ensure`, which is called at every process start, as today, and again when the override changes; creating a channel that exists updates its name and description. A change of the device's language with no override shows in the channel names at the next process start.
   - **What the app cannot reach:** the launcher label, the widget's name in the widget picker and the tile's name in the quick settings editor come from the manifest and follow the device.
   - **`verifyLocalizedStrings`**, a structural check with a fixture self test: in the packages that run outside an Activity (`delivery`, `ringer`, `arming`, `work`, `widget`, `tile`, `di`, `system`), a `getString`, `getQuantityString` or `getText` call whose receiver is not a value obtained from `LocalizedContext` fails the build.
4. **State survives without a `ViewModel`** (ADR 0081), by what each thing is:
   - **The back stack** is a list of `Screen` values, each a name and at most one id, saved by `rememberSaveable` with a saver that writes strings. It survives rotation, the recreation a locale change causes, and process death, because the saved instance state does. An appearance change recreates nothing, so there is nothing to survive.
   - **A screen's data** is never saved: a model re reads the database when it is built.
   - **What she is typing** is saved. The editor's whole draft (which template, every field, which dialog is open) is one `EditorDraft` value of strings, numbers and booleans, held with `rememberSaveable`; the other forms (due date, first reminder, goal, quiet hours) do the same with their one or two fields. After process death in the middle of an edit she returns to the editor with every field as she left it, and nothing has been written to the database.
   - **Predictive back** (API 34 and above; the system back gesture from API 33): `MainActivity` declares `android:enableOnBackInvokedCallback="true"`, and back is handled only through `BackHandler`, which registers on the Activity's `OnBackPressedDispatcher`; the dispatcher registers the platform's back callback while any handler is enabled. The handler is enabled only while the stack is deeper than one or a sheet or dialog is open, so at the root the system's own back to home animation runs. Nothing overrides `onBackPressed` or handles the back key.
5. **`app_settings.locale_override` and `app_settings.telemetry_opt_in` in the shared schema stay unused.** The live opt in has been `AndroidSettings.shareReliabilityOptIn` since Phase 2 (`ARCHITECTURE.md` section 5.10). Removing two columns would be a migration that buys nothing; they are recorded here so that nobody wires a second source.

## Alternatives considered

- **`navigation-compose`.** Rejected: a dependency for three tabs and a stack.
- **`AppCompatDelegate.setApplicationLocales`.** Rejected: it needs `appcompat`.
- **The override in the shared database.** Rejected: a database read on the main thread before the first frame.
