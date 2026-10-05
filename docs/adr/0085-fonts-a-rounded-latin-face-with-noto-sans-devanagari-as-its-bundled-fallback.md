# 0085. Fonts: Nunito for Latin with the bundled Noto Sans Devanagari as its explicit fallback; icons copied in

Date: 2026-10-05
Status: Accepted (2026-10-05, in the review of PR #26 by Claude (technical review)). The two fonts and the 21 icons were approved by Shubham in the review of the first draft (2026-10-05), with licences and provenance recorded as below.

Decided by the Phase 3 planning session, for review. `CLAUDE.md` fixes Noto Sans Devanagari as bundled and forbids relying on system font fallback.

## Decision

1. **Latin is Nunito** (SIL Open Font License 1.1), one variable file, used at weights 400 and 600. **Devanagari is Noto Sans Devanagari** (SIL Open Font License 1.1), static Regular and SemiBold.
2. **One typeface chain per weight, built with the platform's `Typeface.CustomFallbackBuilder`** (API 29, the app's floor): Nunito first, the bundled Noto Sans Devanagari as its custom fallback. Latin runs are drawn in Nunito and Devanagari runs in the bundled Noto, in the same line, whatever the locale, so a medicine name she typed in either script never depends on the device's fonts. Compose reads the chain through a small `AndroidFont` with a typeface loader, and the ring screen sets the same `Typeface` objects on its views. The widget is the one exception: `RemoteViews` cannot be given a `Typeface`, so its layout names the bundled Noto Sans Devanagari font resource for all of its text, Latin included. It still never depends on the device's fonts.
3. **Pairing, checked in one line of mixed text** (spike capture at 18 sp: a Latin name, a Devanagari name and a time): Nunito's x height and stroke weight sit close to Noto Sans Devanagari's at the same size and weight; Nunito is slightly lighter, which is acceptable. Baloo 2, a rounded face with its own Devanagari, was captured beside it and not chosen: its Devanagari is a display design, and Noto is fixed.
4. **Line heights per locale.** English: 1.45 times the font size. Hindi and Marathi: 1.625 times. Any style that shows text she typed uses 1.625 in every locale. Evidence: at 200 percent font scale the bundled Noto renders matras above and below and stacked conjuncts unclipped at 16 sp on 26 sp and at 22 sp on 34 sp; at a Latin tuned 16 sp on 18 sp the lines collide. No style sets a maximum number of lines on text that can wrap, and no container has a fixed height.
5. **Icons are Material Symbols, Rounded, weight 400** (Apache License 2.0), copied in as vector drawables, one file per icon, only those the design spec's icon list names.
6. **Provenance** is recorded in `docs/THIRD_PARTY.md`: for each file its name, its source URL, the upstream version or commit, its SHA-256 and its licence, with the licence texts under `docs/licences/`. `ThirdPartyTest` fails if a file under `res/font` or an icon listed there is missing from the record or its hash differs.

## Alternatives considered

- **Noto Sans Devanagari for everything.** It has Latin glyphs, and it is the fallback if Nunito is not approved: delete the Latin family from the chain and nothing else changes.
- **Compose `FontFamily` listing both fonts.** Rejected: a Compose family selects by weight and style, not by script, so Devanagari in an English locale would fall to the system's font.
- **Variable Noto Sans Devanagari.** Not needed for two weights; static files are the simpler input to the fallback builder.

## Evidence

Spike (plan section 2), at SDK 29 and 36: a Devanagari run measured through the chain has exactly the width the bundled Noto gives alone (473.0 px at 64 px) and not the width the Robolectric system fallback gives (477.0); with another face as the fallback the width differs, so the equality can fail; Latin runs measure as Nunito; the chain renders in Compose at both weights and a strict Roborazzi verify is stable across runs.
