# 0075. Colour roles in one Kotlin model, drawn only as declared pairs; contrast is a test

Date: 2026-10-05
Status: Accepted (2026-10-05, in the review of PR #26 by Claude (technical review))

Decided by Claude (technical review) in the Phase 3 planning prompt (D3, and the one source requirement of its section 4 g) and in the review of the first draft (the ratio is compared unrounded; screens can only use declared pairs, enforced by a check and not by review). The role list, the mechanism and the colour values are the planning session's, for review. The lavender palette was revised against the mockup once it was read.

## Decision

1. **`ColorRoles` is a plain Kotlin data class of ARGB integers** in `com.momtime.android.ui.theme`, with no Compose type in it, and `Palettes` holds one instance per appearance and palette (Light, Dark and the five Pastel palettes). It is the only place a colour value is written, with one named exception, the splash styles of ADR 0074, which a test ties to it.
2. **`RolePair` declares every foreground and background pair that may be drawn, each with its minimum**: 4.5:1 for body text and text buttons; 3:1 for large text, icons, state markers, focus indicators and filled controls against their surroundings; 7:1 for the ring screen's text and action labels, because she may be half asleep. The list is in the design spec, section 2.
3. **Components take a pair, never two colours.** The theme package's components (`PairSurface`, `PairText`, `PairIcon`, the buttons, chips, markers, banners and the rest of the design spec's section 5) each take a `RolePair`, draw the pair's background and set the content colour to its foreground. No component of the app accepts a colour as a parameter. The ring screen's binder (in the theme package, taking a `ColorRoles` value and view references and nothing else, so `verifyRingUiBoundary` still holds) and the widget's `RemoteViews` builder apply pairs the same way. So the pairs that `ContrastTest` walks are, by construction, the pairs that are drawn.
4. **A structural check closes the rest of the gap: `verifyColourBoundary`**, in the style of `verifyRingUiBoundary`, wired into `check` and the `verify-android-structure` CI job, with a fixture self test. Outside `com.momtime.android.ui.theme` it fails on: a Compose `Color(` construction or `Color.` constant; `android.graphics.Color`; a hexadecimal colour literal; `colorResource` and `R.color`; `MaterialTheme.colorScheme`; any read of a `ColorRoles` property or of the roles' composition local. In resources it fails on any `<color>` element and any `#RGB`, `#ARGB`, `#RRGGBB` or `#AARRGGBB` value, in any file but the notification icon and the splash styles file. It fails closed if it scans no source or no resource file.
5. **`ContrastTest` walks every appearance, every palette and every `RolePair` and compares the exact WCAG 2.1 ratio with the minimum.** Nothing is rounded: a ratio of 4.478 fails a minimum of 4.5. The test carries a positive control for that, a fixed pair outside the palettes (`#777777` on white, 4.478) that must be reported as failing. It also fails if a role of `ColorRoles` appears in no pair, or if a pair of the design spec's list is missing, as the 64 tier combinations fail when one is missing. The design spec's table shows two decimals for reading; the test is the gate.
6. **Criticality and occurrence state are never conveyed by colour alone.** Each has a label and an icon shape (design spec section 5). **No good or bad colour** anywhere in adherence, nutrition, weight or water: states differ by marker shape and label, drawn in the palette's neutral roles. `ColorRoles` has no success, warning or error role, so there is nothing to misuse; the blocking banner is distinguished by its icon, its label and its position.

## Alternatives considered

- **A test that scans for colour literals** (the first draft). Replaced: a Gradle check with fixtures is the project's form for a structural rule, and literals were only half the gap; reading two roles separately was the other half.
- **Lint.** A custom lint rule needs its own module and artifact.
- **Material 3's generated tonal palettes.** Rejected: contrast would be a property of a library's algorithm and not of a table the test reads.
- **XML colour resources with `values-night`.** Rejected: five Pastel palettes are not a night qualifier, and two sources drift.

## Evidence

The ratios of the proposed values are in `docs/phase-3-design.md` section 2, to two decimals. Every pair is above its minimum unrounded; the closest is 4.71 against 4.5, so no pair sits within 0.05 of its minimum and the unrounded comparison is shown by the fixed control instead.
