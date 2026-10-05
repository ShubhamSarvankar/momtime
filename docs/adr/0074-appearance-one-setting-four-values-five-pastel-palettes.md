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
6. **A limit, stated plainly.** The system draws a starting window from the manifest theme before any app code runs, and on Android 12 and above a cold start from the launcher always shows the system splash screen. Its background cannot follow a choice made at runtime. The manifest theme is the framework's DayNight theme, so the starting window follows the device's light or dark; with Light chosen on a dark device, or with Pastel, the starting window is the device's neutral background and the app's own first frame is the chosen appearance. `RingActivity` sets `windowDisablePreview`, so no starting window precedes the ring screen. What this looks like is a device row in `MANUAL_CHECKS.md` (P3-1).

## Alternatives considered

- **An Activity recreation on change.** Rejected: a visible flash and a lost sheet.
- **A per palette XML theme chosen with `setTheme`.** Spiked, and it works for a view Activity, but it would be a second source of colours beside the Kotlin roles (ADR 0075).
- **The appearance in the shared schema.** Rejected: invariant 5.

## Evidence

Spikes (plan section 2): a view Activity picks up a choice made at runtime at SDK 29 and 36; a choice made from a bottom sheet applies to the open Compose screen with the same Activity instance, no second `onCreate` and the back stack depth unchanged.
