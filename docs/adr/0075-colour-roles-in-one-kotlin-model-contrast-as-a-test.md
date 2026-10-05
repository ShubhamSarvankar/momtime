# 0075. Colour roles in one Kotlin model, read by Compose, views and the widget; contrast is a test

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by Claude (technical review) in the Phase 3 planning prompt (D3, and the one source requirement of its section 4 g). The role list, the binding to each toolkit and the colour values are the planning session's, for review. The lavender palette is provisional: the prompt's mockup path was a placeholder, so no mockup was read (plan section 1).

## Decision

1. **`ColorRoles` is a plain Kotlin data class of ARGB integers** in `com.momtime.android.ui.theme`, with no Compose type in it, and `Palettes` holds one instance per appearance and palette (Light, Dark and the five Pastel palettes). It is the only place a colour value is written. `NoColourLiteralTest` scans android main sources and resources and fails on a colour literal anywhere else, except the notification icon and the manifest's starting window.
2. **Three readers.** Compose reads the roles through a `CompositionLocal` set by `MomTimeTheme`, which also derives the Material 3 `ColorScheme` from them. The ring screen applies them programmatically in `onCreate` before `setContentView` (window background, system bars, text and button colours) through one binder in the ring package that takes a `ColorRoles` value and nothing else, so `verifyRingUiBoundary` still holds. The widget's `RemoteViews` are coloured from the roles at each update (ADR 0083).
3. **`RolePairs` lists every foreground and background pair a screen uses, with its minimum**: 4.5:1 for body text; 3:1 for large text, icons, state markers, focus indicators and filled controls against their surroundings; 7:1 for the ring screen's text and action labels, because she may be half asleep. `ContrastTest` walks every appearance, every palette and every pair and asserts WCAG 2.1 contrast. The test fails if a role of `ColorRoles` appears in no pair, as the 64 tier combinations fail when one is missing.
4. **Criticality and occurrence state are never conveyed by colour alone.** Each has a label and an icon shape (design spec section 5). **No good or bad colour** anywhere in adherence, nutrition, weight or water: states differ by marker shape and label, drawn in the palette's neutral roles. `ColorRoles` has no success, warning or error role, so there is nothing to misuse; the blocking banner is distinguished by its icon, its label and its position.

## Alternatives considered

- **Material 3's generated tonal palettes.** Rejected: contrast would be a property of a library's algorithm and not of a table the test reads.
- **XML colour resources with `values-night`.** Rejected: five Pastel palettes are not a night qualifier, and two sources drift.

## Evidence

The contrast figures for the proposed values are in `docs/phase-3-design.md` section 2, computed with the WCAG 2.1 relative luminance formula; every pair meets its minimum.
