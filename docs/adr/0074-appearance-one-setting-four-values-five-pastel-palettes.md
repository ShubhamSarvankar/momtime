# 0074. Appearance: one setting, four values, five Pastel palettes, and one switcher

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by Claude (technical review) in the Phase 3 planning prompt (D2, D2b). The storage keys, the state holder and the limit on the system starting window are the planning session's, for review.

## Decision

1. **One setting, `Appearance`: `SYSTEM` (the default on a fresh install), `LIGHT`, `DARK`, `PASTEL`.** `SYSTEM` follows the device's light or dark. Light and Dark are plain and neutral. Pastel is a light appearance with a palette she picks: `LAVENDER` (the default), `BLUSH`, `MINT`, `PEACH`, `SKY`.
2. **No Material You dynamic colour.** The palettes are fixed, contrast tested (ADR 0075) and deterministic in screenshots.
3. **It lives in `momtime_android_settings`** (keys `appearance` and `pastel_palette`; an unknown stored name reads as the default). It is hers and Android only, so it is backed up with that file, which the backup rules already include by name; `BackupRulesTest` is extended to assert that the appearance is written to that file.
4. **It applies before the first frame the app draws in every Activity**, the ring screen included. The choice is read synchronously in `Application.onCreate` into one in memory holder (`AppearanceState`), and each Activity reads the holder before `setContentView` or `setContent`. A change updates the holder and persists. Compose screens recompose in place, with no Activity recreation, so the screen behind the sheet changes at once and the back stack is untouched.
5. **The switcher.** Every top level screen and every onboarding screen shows one control in its top bar: a swatch of the active palette for Pastel, and a sun, moon or auto icon otherwise, with a content description that names the current appearance in words. It opens one bottom sheet with the four appearances and, when Pastel is chosen, the five palettes as swatches. A choice applies at once and persists; there is no confirm step. Settings has an Appearance row that opens the same sheet. There is no themes page and no onboarding step for it. The ring screen has no switcher and reads the appearance when it is next shown.
6. **The system's starting window.** The system draws a starting window from a theme before any app code runs, so the app's own code cannot colour it at launch. What each API level shows:
   - **API 33 and above: the next launch follows her choice.** `android.window.SplashScreen.setSplashScreenTheme` overrides the theme used for the app's splash screen and persists it for later launches (read in AOSP at `android-13.0.0_r1`: the package manager stores the theme's name in the package's user state; passing `Resources.ID_NULL` resets it to the manifest's). The app has one static splash style per appearance and palette (`Splash.Light`, `Splash.Dark` and five `Splash.Pastel.*`), whose only content is the starting window's background, and calls `setSplashScreenTheme` with the matching style whenever the appearance changes and at every process start; for System it passes `ID_NULL`, so the manifest's DayNight theme applies. Because the name is persisted, the style names are fixed and a test pins them. These styles are the one place outside `Palettes` where a colour value is written, in one resource file, and `SplashThemesTest` asserts each equals the palette's `background`. The call is made through a seam so that a test can assert it. The first launch after an install, and the first after her very first choice if the process never ran since, show the manifest's theme.
   - **API 31 and 32:** the system splash screen always shows on a cold start from the launcher, from the manifest theme, which is the framework's DayNight theme: the device's light or dark background, whatever she chose.
   - **API 29 and 30:** there is no splash screen; the starting window is a blank window with the manifest theme's background, again the device's light or dark.
   - On every level the app's own first frame is the chosen appearance. `RingActivity` sets `windowDisablePreview`, so no starting window precedes the ring screen.
   - "No flash of the wrong appearance" is therefore accepted as a device row for the starting window (Claude (technical review)): `MANUAL_CHECKS.md` P3-1 records what is seen on each of the three ranges.

## Alternatives considered

- **An Activity recreation on change.** Rejected: a visible flash and a lost sheet.
- **A per palette XML theme chosen with `setTheme`.** Spiked, and it works for a view Activity, but it would be a second source of colours beside the Kotlin roles (ADR 0075).
- **The appearance in the shared schema.** Rejected: invariant 5.

## Evidence

Spikes (plan section 2): a view Activity picks up a choice made at runtime at SDK 29 and 36; a choice made from a bottom sheet applies to the open Compose screen with the same Activity instance, no second `onCreate` and the back stack depth unchanged.
