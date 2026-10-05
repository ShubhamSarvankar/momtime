# 0077. The screenshot matrix, test only Devanagari fixtures, and the translation workflow

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by Claude (technical review) in the Phase 3 planning prompt (D5, D9). The file format, the fixture mechanism and the recording rule are the planning session's, for review.

## Decision

1. **The matrix is three locales (en, hi, mr) by three font scales (100, 150 and 200 percent): nine combinations, run on every screen state once, in Light.** Appearances differ in colour only, so each screen state is also captured in Dark and in each of the five Pastel palettes at English 100 percent. Palette coverage beyond that is `ContrastTest`'s (ADR 0075).
2. **Comparison is strict** (`changeThreshold = 0`). Captures are of settled states, with the Compose test clock paused and advanced past any indication animation. **Goldens are recorded on Linux only**, by a manually dispatched CI workflow whose artifact is committed, because text rasterisation is not assumed identical across operating systems (unverified; plan section 2).
3. **Nothing is machine translated.** Shubham's brother supplies and reviews the Hindi and Marathi strings. English is frozen in two waves: wave 1 is every string Phase 2 shipped, exported as soon as the pipeline exists; wave 2 is every Phase 3 string, exported when the last screen's pull request merges. The export is one UTF-8 CSV that a spreadsheet opens (`key, kind, quantity, health_adjacent, note, en, hi, mr`), written by `scripts/strings/ExportStrings.java`. `ImportStrings.java` writes `values-hi` and `values-mr` from it, and refuses a row whose placeholders differ from the English or whose plural quantities are not the locale's.
4. **Until his strings exist, screenshot tests in hi and mr use test only fixture strings**: Devanagari text about 30 percent longer than the English, dense in matras and conjuncts, generated under `android/src/test` and supplied through the screen's string source. They are never a shipped resource: `NoFixtureInResourcesTest` fails if any string in `values-hi` or `values-mr` contains the fixture marker, and fails closed if it finds no fixture marker in the fixture source itself.
5. **When his strings are imported, the hi and mr goldens are recorded again from the real resources.** The fixture path stays for strings added later. The human review exit criterion is recorded in `MANUAL_CHECKS.md` with the reviewer, the date and the commit reviewed.

## Alternatives considered

- **Placeholder Hindi in `values-hi`.** Rejected: it would ship.
- **XLIFF.** Rejected: he works in a spreadsheet.
