# Phase 3 visual specification

Status: draft, for review, in the Phase 3 planning pull request. Decisions here are recorded in ADR 0074 (appearance), ADR 0075 (colour roles and contrast), ADR 0076 (copy), ADR 0085 (fonts and icons) and ADR 0087 (onboarding, Today, banners).

**Source and its limit.** The direction comes from a mockup that was made by an image generator before any engineering. The planning prompt gave its location as a placeholder, so **the planning session did not see the image**: this document is written from the prompt's written description of what carries over, and the lavender palette is the session's own choice, to be corrected against the mockup in review. The mockup is not in the repo and must not be committed. An implementer never needs it: everything a screen needs is below, and anything not below is a design STOP, not a judgment call.

**What carries over:** white rounded cards on a softly tinted background; circular state markers in the Today list; icon tiles; a bottom navigation with a central add action; the gestational week and due date card; the ring screen as a card with three large labelled actions.

**What does not** (ADR 0076): praise and encouragement lines, any single percentage or score, "Activity", nutrition against a target, health claims, an ad banner, a blank planner, a green tick for done and a red cross for missed, and the weekly grid, insights and caregiver screens, which are not Phase 3 deliverables.

All four appearances share one type, shape and spacing system and differ in colour only.

---

## 1. Colour roles

`ColorRoles` (ADR 0075) has exactly these roles. No other colour exists in the app.

| Role | Used for |
|---|---|
| `background` | The screen behind cards |
| `surface` | Cards, sheets, the top bar, the bottom navigation |
| `tile` | Icon tiles, chips, text field fill, the unselected swatch ring |
| `onSurface` | Body and title text, icons on `surface`, `background` and `tile` |
| `onMuted` | Secondary text, captions, hints |
| `primary` | Filled buttons, the central add action, the progress fill, the selected navigation item's indicator, the focus ring |
| `onPrimary` | Text and icons on `primary` |
| `accent` | Text buttons, links, a selected icon, on `surface`, `background`, `tile` and `bannerBg` |
| `outline` | State marker strokes, card and field outlines, dividers that carry meaning |
| `track` | The progress bar's unfilled part |
| `bannerBg` | A banner's background |
| `onBanner` | A banner's text and icon |
| `ringBg` | The ring screen's background |
| `ringCard` | The ring screen's card |
| `onRing` | All text on the ring screen |
| `ringAction` | The ring screen's action buttons |
| `onRingAction` | The labels on them |

`accent` equals `primary` in every palette below; they are separate roles because their contrast obligations differ.

## 2. Values and contrast

| Role | Light | Dark | Lavender | Blush | Mint | Peach | Sky |
|---|---|---|---|---|---|---|---|
| `background` | `#F4F4F6` | `#121214` | `#F1ECFA` | `#FBEDF1` | `#E9F6F1` | `#FCEFE6` | `#E8F2FB` |
| `surface` | `#FFFFFF` | `#1E1E22` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `tile` | `#E9E9EE` | `#2B2B31` | `#E6DDF6` | `#F6DCE4` | `#D6EEE5` | `#F8DFCF` | `#D5E7F7` |
| `onSurface` | `#1B1B1F` | `#E8E8EC` | `#241B3A` | `#3A1B26` | `#12302A` | `#3A2314` | `#12283A` |
| `onMuted` | `#55555F` | `#B4B4BE` | `#5A4F74` | `#74505C` | `#47645C` | `#735746` | `#465E72` |
| `primary`, `accent` | `#3F4A5A` | `#C2CBD9` | `#5E4B9C` | `#9C4566` | `#2F6E5E` | `#9A5328` | `#2C6594` |
| `onPrimary` | `#FFFFFF` | `#16191E` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `outline` | `#6F6F7A` | `#8E8E99` | `#7A6B9E` | `#A0687C` | `#5A857A` | `#A3714F` | `#5A7F9E` |
| `track` | `#D9D9E0` | `#3A3A42` | `#D9CEF0` | `#F0CCD8` | `#C4E4D9` | `#F2D0BA` | `#C2DBF2` |
| `bannerBg` | `#E4E4EA` | `#2B2B31` | `#E6DDF6` | `#F6DCE4` | `#D6EEE5` | `#F8DFCF` | `#D5E7F7` |
| `onBanner` | `#1B1B1F` | `#E8E8EC` | `#241B3A` | `#3A1B26` | `#12302A` | `#3A2314` | `#12283A` |
| `ringBg` | `#F4F4F6` | `#121214` | `#F1ECFA` | `#FBEDF1` | `#E9F6F1` | `#FCEFE6` | `#E8F2FB` |
| `ringCard` | `#FFFFFF` | `#1E1E22` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `onRing` | `#1B1B1F` | `#F2F2F5` | `#241B3A` | `#3A1B26` | `#12302A` | `#3A2314` | `#12283A` |
| `ringAction` | `#2B3440` | `#DDE3EC` | `#43337A` | `#7A2F4C` | `#1F5446` | `#77401D` | `#1F4D73` |
| `onRingAction` | `#FFFFFF` | `#121417` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |

`System` is Light or Dark by the device. Pastel is always a light appearance.

**The pairs `RolePairs` must hold, with their minimums** (foreground on background):

- 4.5:1: `onSurface` on `surface`, `background`, `tile`; `onMuted` on `surface`, `background`, `tile`; `onPrimary` on `primary`; `accent` on `surface`, `background`, `tile`, `bannerBg`; `onBanner` on `bannerBg`.
- 3:1: `primary` on `surface`, `background`, `track`; `outline` on `surface`, `background`, `tile`; `ringAction` on `ringCard`, `ringBg`.
- 7:1: `onRing` on `ringCard`, `ringBg`; `onRingAction` on `ringAction`.

**Computed contrast of the values above** (WCAG 2.1; each cell lists the pairs in the order named):

| Pairs | Light | Dark | Lavender | Blush | Mint | Peach | Sky |
|---|---|---|---|---|---|---|---|
| `onSurface` on `surface` / `background` | 17.2 / 15.6 | 13.6 / 15.3 | 16.2 / 14.0 | 15.4 / 13.5 | 14.2 / 12.8 | 14.7 / 13.0 | 15.1 / 13.3 |
| `onMuted` on `surface` / `background` / `tile` | 7.4 / 6.7 / 6.1 | 8.1 / 9.1 / 6.8 | 7.5 / 6.5 / 5.7 | 6.9 / 6.1 / 5.3 | 6.5 / 5.8 / 5.3 | 6.6 / 5.9 / 5.2 | 6.8 / 6.0 / 5.3 |
| `onPrimary` on `primary` | 9.0 | 10.8 | 7.1 | 6.1 | 6.0 | 5.8 | 6.2 |
| `accent` on `surface` / `background` / `tile` | 9.0 / 8.2 / 7.4 | 10.2 / 11.4 / 8.6 | 7.1 / 6.1 / 5.4 | 6.1 / 5.3 / 4.7 | 6.0 / 5.4 / 4.9 | 5.8 / 5.1 / 4.5 | 6.2 / 5.5 / 4.9 |
| `outline` on `surface` / `background` / `tile` | 5.0 / 4.5 / 4.1 | 5.1 / 5.8 / 4.3 | 4.7 / 4.1 / 3.6 | 4.4 / 3.9 / 3.4 | 4.1 / 3.7 / 3.4 | 4.2 / 3.7 / 3.3 | 4.2 / 3.7 / 3.3 |
| `primary` on `track` | 6.4 | 6.9 | 4.7 | 4.1 | 4.4 | 4.0 | 4.3 |
| `onRing` on `ringCard` / `ringBg` | 17.2 / 15.6 | 14.9 / 16.7 | 16.2 / 14.0 | 15.4 / 13.5 | 14.2 / 12.8 | 14.7 / 13.0 | 15.1 / 13.3 |
| `onRingAction` on `ringAction` | 12.6 | 14.3 | 10.5 | 9.0 | 8.7 | 8.3 | 8.9 |

Every pair in the list was computed and meets its minimum; the tightest is Peach's `accent` on `tile`, which rounds to 4.5 and passes. The table is evidence for review; `ContrastTest` is the gate.

## 3. Type

Families are the chain of ADR 0085: Nunito for Latin, the bundled Noto Sans Devanagari for Devanagari, in one typeface. Two weights: 400 and 600. Sizes are in sp and scale with the system font size without limit.

| Style | Size | Weight | Line height, en | Line height, hi and mr | Used for |
|---|---|---|---|---|---|
| `display` | 28 | 600 | 40 | 46 | The gestational week; the ring screen's title of a single occurrence |
| `title` | 22 | 600 | 32 | 36 | Screen titles, card titles |
| `subtitle` | 18 | 600 | 26 | 30 | Row titles, section headings |
| `body` | 16 | 400 | 23 | 26 | Body text, field text |
| `label` | 16 | 600 | 23 | 26 | Buttons, chips, navigation labels |
| `caption` | 14 | 400 | 20 | 23 | Secondary lines, hints, state labels |

Text she typed (a title, a dosage, notes, doctor's instructions) always uses the hi and mr line height, in every locale. No style caps the number of lines of text that can wrap; nothing is ellipsised except her template title in a navigation or widget context, which is at most 24 characters. Numbers use the locale's digits as the platform formats them; times use the device's 12 or 24 hour setting.

## 4. Shape, spacing, elevation

- **Radii:** cards and sheets 20 dp (a sheet's top corners only); buttons, chips and text fields 16 dp; icon tiles 14 dp; state markers, swatches and the central add action are circles.
- **Spacing unit 4 dp.** Screen edge padding 16; between cards 12; inside a card 16; between a row's elements 12; between a label and its field 4.
- **Touch targets** at least 48 by 48 dp, with at least 8 dp between two.
- **Elevation:** none drawn as shadow. A card is `surface` on `background`; in Dark the two differ by tone. The bottom sheet has a 32 percent black scrim. This keeps captures identical across SDK levels.
- **Width:** a single column. Content is capped at 560 dp wide and centred on wider screens.
- **Motion:** the platform's default sheet and ripple only. Nothing animates because of adherence (ADR 0076).

## 5. Components

- **Card.** `surface`, radius 20, padding 16, no outline in light appearances, a 1 dp `outline` at 40 percent in Dark.
- **Icon tile.** 44 dp square, radius 14, `tile` background, a 24 dp icon in `onSurface`. The task type chooses the icon; it carries no meaning beyond that.
- **State marker** (Today rows; 28 dp circle, 2 dp stroke, drawn in `outline` and `onSurface` only). The shape is the state, and the label beside the row's time says it in words:
  - To do: an empty ring. Snoozed is a To do row whose caption reads "Snoozed until" and the time.
  - Done: a filled disc (`onSurface`) with a tick cut out in `surface`.
  - Skipped: a ring with a horizontal bar across its middle.
  - Missed: a ring drawn dashed (four gaps), empty.
- **Criticality** is a text chip on the row and in the builder ("Critical", "Standard", "Gentle"), with a small shape before the word: a filled triangle, a filled square, a filled circle, all in `onSurface`. Never a colour.
- **Top bar.** `surface`, the screen title in `title`, and at the end the **appearance control**: a 28 dp circle. For Pastel it is filled with the active palette's `primary` inside a 2 dp `outline` ring; otherwise it holds the sun (Light), moon (Dark) or auto (System) icon in `onSurface`. Its content description is the sentence "Appearance: " and the appearance's name, and the palette's name for Pastel.
- **Appearance sheet.** A bottom sheet on `surface`. Title "Appearance". Four rows, each a 48 dp high choice with its icon, its name and a radio mark: System, Light, Dark, Pastel. Under Pastel, when it is chosen, a row of five 40 dp swatches, each filled with that palette's `primary`, the chosen one inside a 3 dp `onSurface` ring, each with its name as content description (Lavender, Blush, Mint, Peach, Sky) and the name as a `caption` beneath. At 150 and 200 percent the swatches wrap to a second line. Choosing applies at once; the sheet stays open until dismissed.
- **Bottom navigation.** `surface`, three items with icon and label (Today, Overview, Settings) and, raised in the centre, the add action: a 56 dp circle in `primary` with a plus in `onPrimary`, content description "Add a reminder". The selected item has its icon and label in `accent` and a 3 dp bar above it; unselected items are `onMuted`. The bar grows in height with the font scale; labels wrap to two lines and are never cut.
- **Buttons.** Filled: `primary` and `onPrimary`, height at least 48 dp, growing with its text, full width in forms. Text button: `accent`, no fill. A destructive choice (deactivate) is a text button with a confirm dialog; it has no colour of its own.
- **Progress bar.** 12 dp high, radius 6, `track` with a `primary` fill, always with its figures in words beside it ("750 of 2000 ml"). It is used for water only. Reaching the goal changes nothing but the numbers.
- **Banner.** A card in `bannerBg` with a 24 dp icon, a title in `subtitle`, a body in `body`, both `onBanner`, and one text button in `accent`. The blocking banner uses the "blocked" icon, sits at the top of Today above everything, and cannot be dismissed. Other banners use the "info" icon.
- **Text field.** `tile` fill, radius 16, label above in `caption`, the hint in `onMuted`. Counters where the model has a limit (title, 24 characters).
- **Three figures.** Wherever adherence is shown: three equal cells in one card, each a number in `display` and its word in `caption` (Done, Missed, Skipped), in that order, in `onSurface`. Never a sum, a ratio or a chart.

## 6. Screens

Each screen is a `Screen` in `ScreenRegistry`. "Bar" means the top bar with the appearance control. Listed states are the ones captured by the screenshot matrix.

1. **Onboarding 1, What MomTime keeps.** Bar. One illustration (section 8), a title, three plain sentences (ADR 0087, obligation one), a filled "Continue". State: default.
2. **Onboarding 2, Due date.** Bar. A sentence, a date field opening the platform date picker, "Continue" disabled until a date is set. States: empty; filled.
3. **Onboarding 3, Your first reminder.** Bar. Title field (hint as ADR 0087), a time field opening the time picker, a criticality choice of three chips with one line under the chosen one saying what it does ("Rings until you answer", "Rings once and repeats once", "A quiet notification"), "Continue". States: empty; filled.
4. **Onboarding 4, Permissions** (also reached from Settings and from banners). Bar. The tier sentence (`SetupText.tier`), the two channel sentences, then one card per `FlowItem`: title, status, and "Allow" where a launch exists. The Tier 1 sentence when the tier is 1. The blocking banner and a disabled "Continue" in the blocked state. States: everything granted; several needed; Tier 1; blocked.
5. **Onboarding 5, Samsung steps** (also from banners). Bar. One card per `SamsungStep`: title, caption, the screenshot at its natural aspect ratio capped at 320 dp high, "Open", and the fallback hint after a fallback. "Continue". States: default; after a fallback.
6. **Onboarding 6, Check your reminders** (the reliability check as a step). Bar. The intro, "Start the check", the status line, "Finish". States: not started; running; fired; missed.
7. **Today.** Bar titled with today's date. The first banner, if any. The **week card**: "Week" and the number in `display`, the due date and the days to it in `body`; in the postpartum phase or outside weeks 0 to 42 the card shows the due date only. Then the list: one card per occurrence, with the state marker, the time, the title, the criticality chip, the dosage and instructions when present, and on a To do row two text buttons, "Taken" and "Skip". Then a water card: the progress bar, its figures, and "Add a glass". Bottom navigation. States: empty (a sentence and the add action); a mixed list with all four states and a snoozed row; with the blocking banner.
8. **Starter schedule** (offered once from an empty Today, and from Settings). Bar. A sentence saying this is a starting point to edit and not a prescription. Three rows, each a time (08:00, 14:00, 21:00), an empty title field and a criticality chip set to Standard; each row can be removed. "Add these reminders" is disabled until every remaining row has a title. Nothing is named (ADR 0076). States: default; one row titled.
9. **Reminders** (the template list, from Settings and after the add action). Bar. Active templates, then inactive ones under a heading, each a row with its tile, title, time, recurrence in words and criticality chip. State: mixed.
10. **Reminder editor** (create and edit). Bar with "Save". Fields in this order: title; time; repeats (Daily, chosen weekdays as seven chips, or every N days with a number and a start date); criticality; type (five chips, each with its tile icon); tags (seven chips, any number); dosage; doctor's instructions (a multi line field labelled "Shown exactly as you type it"); notes; inventory count and refill threshold, shown for the medicine type only; vibration (four choices); and, when editing, "Stop this reminder" or "Start this reminder again". There is no mission control. Saving an edit that withdraws open occurrences shows a confirm dialog that lists them by date and time. States: create, empty; edit, filled; the weekday choice; the confirm dialog.
11. **Water.** Bar. The progress bar and figures, "Add a glass", today's entries as a list of times, the goal field in millilitres, and nudges: a choice of 0 to 4 a day with one sentence saying they are quiet and approximate. States: empty; part way; at the goal.
12. **Nutrition** (from Overview). Bar. A day and week switch; for each tag that has a count, a row with the tag's name and its number of servings. No bars, no targets, no colour. A sentence under the title: "Counts of what you marked as taken." States: day; week; empty.
13. **Overview** (the dashboard). Bar. Cards in this order: the week card; today's three figures; water progress; "Days in the last 30 with every critical reminder taken" and its number; a row to Nutrition; a row to "How reminders are arriving". States: default; empty.
14. **Settings.** Bar. Rows: Appearance (opens the sheet); Language; Quiet hours; Daily ring limit; Snooze length; Louder sound after; Notification channels (opens the system's channel settings); Reminders; Starter schedule; Permissions; How reminders are arriving; Share reliability data, with its copy. States: default.
15. **How reminders are arriving** (the reliability view). Bar. Every banner; the check and its status; the report sentences in a card; the opt in; "Export". States: no fires yet; with fires and two banners.
16. **Appearance sheet** over Today. States: System chosen; Pastel chosen with Lavender.
17. **The ring screen** (views, ADR 0073). No bar and no switcher. `ringBg`; one `ringCard` per due occurrence holding the title in `display` for one occurrence and `title` for several, the dosage and instructions with their labels, and its actions as three full width buttons in `ringAction`, at least 64 dp high, stacked, labelled in `label` at 20 sp: "Taken", "Snooze" with its minutes, "Skip"; the line that says snooze is not available where it is not. Below the cards, "Stop the sound" as a text button, and the "sound is off" line. With nothing due: a sentence and one button that opens the app. States: nothing due; one occurrence; three occurrences; snooze unavailable.
18. **The widget.** A 2 by 1 cell card in `surface`, radius 20: "Water" and today's figures in `caption`, a thin progress bar, and a 48 dp circular button in `primary` with a plus, content description "Add a glass". States: empty; part way.

## 7. Iconography

Material Symbols, Rounded, weight 400, 24 dp, copied in as vector drawables (ADR 0085). The whole list: `today`, `dashboard`, `settings`, `add`, `light_mode`, `dark_mode`, `brightness_auto`, `medication`, `pill`, `restaurant`, `nutrition`, `event_note` (the five task types, in the enum's order), `water_drop`, `info`, `block`, `check`, `chevron_right`, `arrow_back`, `close`, `notifications`, `schedule`. An icon not on this list is a design STOP.

## 8. Illustration

One original vector drawable, for onboarding step 1: a teacup and a small plant on a shelf, drawn from circles and rounded rectangles, in `tile`, `outline` and `primary` only (so it follows the appearance), 160 dp high. It is drawn in the repo by the implementer from this description, is not traced from anything, and is the only illustration in Phase 3. No figure of a woman, a baby or a body.

## 9. Copy

English strings are written in the pull request that adds each screen, plain and short, and are frozen by the string freeze (ADR 0077). `CopyRulesTest` is the floor (ADR 0076). The words for states and actions are fixed here so that screens agree: "To do", "Done", "Skipped", "Missed", "Taken", "Skip", "Snooze", "Snoozed until".
