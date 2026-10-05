# Phase 3 visual specification

Status: draft, for review, in the Phase 3 planning pull request. Decisions here are recorded in ADR 0074 (appearance), ADR 0075 (colour roles and contrast), ADR 0076 (copy), ADR 0085 (fonts and icons) and ADR 0087 (onboarding, Today, banners).

**Source.** The direction comes from a mockup made by an image generator before any engineering. The planning session read it for this revision (the first draft was written without it) and sampled its colours. Its look is intent; its content is unvetted. The mockup is not in the repo and must not be committed. An implementer never needs it: everything a screen needs is below, and anything not below is a design STOP, not a judgment call.

**What carries over from the mockup:** white rounded cards on a very pale tinted background; circular state markers at the left of each row of the Today list; icon tiles; a bottom navigation with a central add action; the gestational week and due date card, tinted, at the top of the dashboard; the ring screen as a white card on a deep background with a bell above it and three large labelled actions; deep purple headings on near white in the lavender palette.

**What was read in the mockup and not followed, and why:**

| In the mockup | Not followed because |
|---|---|
| "Great job! Keep it up", "You're doing amazing", "Consistency is the key to a healthy pregnancy", "Never Miss What Matters" | D4: no praise, no encouragement that depends on adherence |
| "Today's Progress 85%", "Weekly Score 90%", per category percentages, "Schedule 8/10 Done" | Adherence is three figures, never one number (`CLAUDE.md`); a count of done over total hides skips |
| "Nutrition 4/5 Done", "Activity 80%" | Nutrition is counts with no target; nothing tracks activity |
| A green tick for done, a red cross or red ring for missed, an orange ring | D3: no good or bad colour; state is a marker shape and a label |
| "Iron Tablet", "Milk + Protein Powder", "Coconut Water", "1 Banana" as content | D6: the app names no medicine, food or dose; her own titles are shown verbatim |
| "Time to Take" before the title on the ring screen | Strings are never built by concatenation, and not every reminder is a medicine; the title stands alone |
| "Remind me again in 5 minutes" on the ring screen | The ring screen shows only what the ring session holds (ADR 0062); the next rung is not in it |
| "Hi Ananya" and a profile tab | The app collects no name |
| The ad banner; "Doctor Recommended", "Healthy Baby", "Stronger Pregnancy"; the blank printable planner | Permanently excluded |
| The weekly grid, insights, history and reports tabs, the caregiver view, "Upcoming Task" with a countdown | Not Phase 3 deliverables; the navigation has three destinations, not five |
| The date arrows on Today | Today shows today; history is not in Phase 3 |
| The illustrations of a foetus, a pregnant woman and a couple | Generated images with no licence, and no figure of a body or a baby is drawn (section 8) |
| The pink to purple gradient on the progress bar and the pink bell | One accent role per palette keeps the contrast table closed; pink is offered as the Blush palette |
| Three ring actions side by side | Stacked, so that each label wraps whole at 200 percent in Hindi and Marathi and each target is as wide as the card |
| The add action as one of five equal tabs | Kept central and circular as drawn, but with three destinations around it |

All four appearances share one type, shape and spacing system and differ in colour only.

---

## 1. Colour roles

`ColorRoles` (ADR 0075) has exactly these roles. No other colour exists in the app.

| Role | Used for |
|---|---|
| `background` | The screen behind cards |
| `surface` | Cards, sheets, the top bar, the bottom navigation |
| `tile` | Icon tiles, chips, text field fill, the week card |
| `onSurface` | Body and title text and icons |
| `onMuted` | Secondary text, captions, hints |
| `primary` | Filled buttons, the add action, the progress fill, a selected item, text buttons and links, the focus ring |
| `onPrimary` | Text and icons on `primary` |
| `outline` | State marker strokes, field outlines |
| `track` | The progress bar's unfilled part |
| `bannerBg` | A banner's background |
| `onBanner` | A banner's text and icon |
| `ringBg` | The ring screen's background |
| `onRingBg` | Text and icons drawn directly on `ringBg` |
| `ringCard` | The ring screen's card and the bell's circle |
| `onRing` | Text on `ringCard` |
| `ringAction` | The ring screen's action buttons and the bell |
| `onRingAction` | The labels and icons on them |

## 2. Values and contrast

| Role | Light | Dark | Lavender | Blush | Mint | Peach | Sky |
|---|---|---|---|---|---|---|---|
| `background` | `#F4F4F6` | `#121214` | `#FBF5FC` | `#FBEDF1` | `#E9F6F1` | `#FCEFE6` | `#E8F2FB` |
| `surface` | `#FFFFFF` | `#1E1E22` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `tile` | `#E9E9EE` | `#2B2B31` | `#F1E3F7` | `#F6DCE4` | `#D6EEE5` | `#F8DFCF` | `#D5E7F7` |
| `onSurface` | `#1B1B1F` | `#E8E8EC` | `#2A1845` | `#3A1B26` | `#12302A` | `#3A2314` | `#12283A` |
| `onMuted` | `#55555F` | `#B4B4BE` | `#63557A` | `#74505C` | `#47645C` | `#735746` | `#465E72` |
| `primary` | `#3F4A5A` | `#C2CBD9` | `#7840A0` | `#9C4566` | `#2F6E5E` | `#935026` | `#2C6594` |
| `onPrimary` | `#FFFFFF` | `#16191E` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `outline` | `#6F6F7A` | `#8E8E99` | `#8A6FA8` | `#A0687C` | `#5A857A` | `#A3714F` | `#5A7F9E` |
| `track` | `#D9D9E0` | `#3A3A42` | `#EAD9F2` | `#F0CCD8` | `#C4E4D9` | `#F2D0BA` | `#C2DBF2` |
| `bannerBg` | `#E4E4EA` | `#2B2B31` | `#F1E3F7` | `#F6DCE4` | `#D6EEE5` | `#F8DFCF` | `#D5E7F7` |
| `onBanner` | `#1B1B1F` | `#E8E8EC` | `#2A1845` | `#3A1B26` | `#12302A` | `#3A2314` | `#12283A` |
| `ringBg` | `#2B3440` | `#0C0C0E` | `#544887` | `#7A2F4C` | `#1F5446` | `#77401D` | `#1F4D73` |
| `onRingBg` | `#FFFFFF` | `#F2F2F5` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `ringCard` | `#FFFFFF` | `#1E1E22` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |
| `onRing` | `#1B1B1F` | `#F2F2F5` | `#2A1845` | `#3A1B26` | `#12302A` | `#3A2314` | `#12283A` |
| `ringAction` | `#2B3440` | `#DDE3EC` | `#5A2E86` | `#7A2F4C` | `#1F5446` | `#77401D` | `#1F4D73` |
| `onRingAction` | `#FFFFFF` | `#121417` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` | `#FFFFFF` |

`System` is Light or Dark by the device. Pastel is always a light appearance.

**Lavender against the mockup's samples:** the page is `#FEF9FD` in the mockup and `#FBF5FC` here, a little more tinted so that a white card still reads as a card; the brand purple of the logo samples at `#7840A0`, which is `primary` exactly; headings sample at about `#40186A` and `onSurface` is the darker `#2A1845` so that body text is also covered; the ring screen's background samples at `#65599B`, on which white text reaches only about 6:1, so `ringBg` is the darker `#544887` of the same hue.

**The pairs.** `RolePair` (ADR 0075) declares every foreground and background pair a component may draw, with its minimum. The table is the computed WCAG 2.1 ratio of each, to two decimals; the test compares the unrounded ratio.

| Pair | Minimum | Light | Dark | Lavender | Blush | Mint | Peach | Sky |
|---|---|---|---|---|---|---|---|---|
| `onSurface` on `surface` | 4.5 | 17.17 | 13.60 | 15.97 | 15.39 | 14.17 | 14.68 | 15.10 |
| `onSurface` on `background` | 4.5 | 15.63 | 15.31 | 14.88 | 13.55 | 12.77 | 13.02 | 13.32 |
| `onSurface` on `tile` | 4.5 | 14.19 | 11.51 | 12.98 | 11.93 | 11.62 | 11.50 | 11.94 |
| `onMuted` on `surface` | 4.5 | 7.37 | 8.08 | 6.75 | 6.90 | 6.47 | 6.60 | 6.76 |
| `onMuted` on `background` | 4.5 | 6.71 | 9.10 | 6.29 | 6.07 | 5.83 | 5.86 | 5.97 |
| `onMuted` on `tile` | 4.5 | 6.09 | 6.84 | 5.49 | 5.35 | 5.31 | 5.17 | 5.35 |
| `onPrimary` on `primary` | 4.5 | 8.98 | 10.77 | 6.91 | 6.08 | 5.98 | 6.15 | 6.18 |
| `primary` on `surface` | 4.5 | 8.98 | 10.16 | 6.91 | 6.08 | 5.98 | 6.15 | 6.18 |
| `primary` on `background` | 4.5 | 8.18 | 11.44 | 6.44 | 5.35 | 5.39 | 5.45 | 5.45 |
| `primary` on `tile` | 4.5 | 7.42 | 8.60 | 5.62 | 4.71 | 4.90 | 4.81 | 4.89 |
| `primary` on `bannerBg` | 4.5 | 7.09 | 8.60 | 5.62 | 4.71 | 4.90 | 4.81 | 4.89 |
| `primary` on `track` | 3 | 6.39 | 6.89 | 5.17 | 4.15 | 4.40 | 4.25 | 4.33 |
| `outline` on `surface` | 3 | 4.96 | 5.13 | 4.27 | 4.41 | 4.14 | 4.17 | 4.23 |
| `outline` on `background` | 3 | 4.52 | 5.77 | 3.98 | 3.88 | 3.73 | 3.70 | 3.73 |
| `outline` on `tile` | 3 | 4.10 | 4.34 | 3.47 | 3.42 | 3.40 | 3.27 | 3.34 |
| `onBanner` on `bannerBg` | 4.5 | 13.56 | 11.51 | 12.98 | 11.93 | 11.62 | 11.50 | 11.94 |
| `onRing` on `ringCard` | 7 | 17.17 | 14.87 | 15.97 | 15.39 | 14.17 | 14.68 | 15.10 |
| `onRingBg` on `ringBg` | 7 | 12.59 | 17.49 | 7.91 | 8.98 | 8.70 | 8.28 | 8.87 |
| `onRingAction` on `ringAction` | 7 | 12.59 | 14.30 | 9.68 | 8.98 | 8.70 | 8.28 | 8.87 |
| `ringAction` on `ringCard` | 3 | 12.59 | 12.87 | 9.68 | 8.98 | 8.70 | 8.28 | 8.87 |

Every pair is above its minimum unrounded. The closest are Blush's `primary` on `tile` and on `bannerBg` at 4.71, and Peach's `outline` on `tile` at 3.27; no pair sits within 0.05 of its minimum. The ring card's edge against the ring background is not a declared pair: in Dark the two are close in tone by design, and the card's content, not its edge, carries the information. The table is evidence for review; `ContrastTest` is the gate.

## 3. Type

Families are the chain of ADR 0085: Nunito for Latin, the bundled Noto Sans Devanagari for Devanagari, in one typeface. Two weights: 400 and 600. Sizes are in sp and scale with the system font size without limit.

| Style | Size | Weight | Line height, en | Line height, hi and mr | Used for |
|---|---|---|---|---|---|
| `display` | 28 | 600 | 40 | 46 | The gestational week; a figure; the ring screen's title of a single occurrence |
| `title` | 22 | 600 | 32 | 36 | Screen titles, card titles |
| `subtitle` | 18 | 600 | 26 | 30 | Row titles, section headings |
| `body` | 16 | 400 | 23 | 26 | Body text, field text |
| `label` | 16 | 600 | 23 | 26 | Buttons, chips, navigation labels |
| `caption` | 14 | 400 | 20 | 23 | Secondary lines, hints, state labels |

Text she typed (a title, a dosage, notes, doctor's instructions) always uses the hi and mr line height, in every locale. No style caps the number of lines of text that can wrap; nothing is ellipsised except her template title in the widget, which is at most 24 characters. Numbers use the locale's digits as the platform formats them; times use the device's 12 or 24 hour setting.

## 4. Shape, spacing, elevation

- **Radii:** cards and sheets 20 dp (a sheet's top corners only); buttons, chips and text fields 16 dp; icon tiles 14 dp; state markers, swatches, the bell's circle and the add action are circles.
- **Spacing unit 4 dp.** Screen edge padding 16; between cards 12; inside a card 16; between a row's elements 12; between a label and its field 4.
- **Touch targets** at least 48 by 48 dp, with at least 8 dp between two.
- **Elevation:** none drawn as shadow. A card is `surface` on `background`; in Dark the two differ by tone. The bottom sheet has a 32 percent black scrim. This keeps captures identical across SDK levels.
- **Width:** a single column. Content is capped at 560 dp wide and centred on wider screens.
- **Motion:** the platform's default sheet and ripple only. Nothing animates because of adherence (ADR 0076).

## 5. Components

Every component takes its colours as a declared `RolePair` and never as two separate colours (ADR 0075).

- **Card.** `surface`, radius 20, padding 16, no outline.
- **Icon tile.** 44 dp square, radius 14, `tile` background, a 24 dp icon in `onSurface`. The task type chooses the icon; it carries no meaning beyond that.
- **Shortcut tile** (Overview). A card one third of the row wide: an icon tile above a `label`, as the mockup's three tiles are drawn, with no number on it.
- **State marker** (Today rows; 28 dp circle, 2 dp stroke, drawn in `outline` and `onSurface` only). The shape is the state, and the row's caption says it in words:
  - To do: an empty ring. Snoozed is a To do row whose caption reads "Snoozed until" and the time.
  - Done: a filled disc (`onSurface`) with a tick cut out in `surface`. A dose marked late is Done with the caption "Taken late".
  - Skipped: a ring with a horizontal bar across its middle.
  - Missed: a ring drawn dashed (four gaps), empty.
- **Criticality** is a text chip on the row and in the editor ("Critical", "Standard", "Gentle"), with a small shape before the word: a filled triangle, a filled square, a filled circle, all in `onSurface`. Never a colour.
- **Top bar.** `surface`, the screen title in `title`, and at the end the **appearance control**: a 28 dp circle inside a 48 dp target. For Pastel it is filled with the active palette's `primary` inside a 2 dp `outline` ring; otherwise it holds the sun (Light), moon (Dark) or auto (System) icon in `onSurface`. Its content description is the sentence "Appearance: " and the appearance's name, and the palette's name for Pastel.
- **Appearance sheet.** A bottom sheet on `surface`. Title "Appearance". Four rows, each a 48 dp high choice with its icon, its name and a radio mark: System, Light, Dark, Pastel. Under Pastel, when it is chosen, a row of five 40 dp swatches, each filled with that palette's `primary`, the chosen one inside a 3 dp `onSurface` ring, each with its name as content description (Lavender, Blush, Mint, Peach, Sky) and the name as a `caption` beneath. At 150 and 200 percent the swatches wrap to a second line. Choosing applies at once; the sheet stays open until dismissed.
- **Bottom navigation.** `surface`. Today and Overview on the left, Settings on the right, each an icon above a label; in the centre the add action, a 48 dp circle in `primary` with a plus in `onPrimary`, level with the icons as in the mockup, content description "Add a reminder". The selected item's icon and label are `primary`; the others are `onMuted`. The bar grows in height with the font scale; labels wrap to two lines and are never cut.
- **Buttons.** Filled: `primary` and `onPrimary`, height at least 48 dp, growing with its text, full width in forms. Text button: `primary` text, no fill. A destructive choice (stop a reminder) is a text button with a confirm dialog; it has no colour of its own.
- **Progress bar.** 12 dp high, radius 6, `track` with a `primary` fill, always with its figures in words beside it ("750 of 2000 ml"). It is used for water only. Reaching the goal changes nothing but the numbers.
- **Banner.** A card in `bannerBg` with a 24 dp icon, a title in `subtitle`, a body in `body`, both `onBanner`, and one text button in `primary`. The blocking banner uses the "blocked" icon, sits at the top of Today above everything, and cannot be dismissed. Other banners use the "info" icon.
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
7. **Today.** Bar titled "Today" with the date beneath in `caption`. The first banner, if any. Then **one card holding the whole list**, as the mockup draws it: each row has the state marker at the left, the time in `label`, then her title in `subtitle` with the criticality chip, the dosage and instructions beneath when present, and the state's caption. Rows are separated by 12 dp of space and no line. A To do row, when opened by a tap, shows three text buttons beneath it: "Taken", "Snooze" (only once the reminder has come due, and only when the domain offers it) and "Skip". A Missed row shows one: "Taken late". Then a water card: the progress bar, its figures, and "Add a glass". Bottom navigation. States: empty (a sentence and the add action); a mixed list with all four states, a snoozed row and a row taken late; a To do row opened; with the blocking banner.
8. **Starter schedule** (offered once from an empty Today, and from Settings). Bar. A sentence saying this is a starting point to edit and not a prescription. Three rows, each a time (08:00, 14:00, 21:00), an empty title field and a criticality chip set to Standard; each row can be removed. "Add these reminders" is disabled until every remaining row has a title. Nothing is named (ADR 0076). States: default; one row titled.
9. **Reminders** (the template list, from Overview and Settings). Bar. Active templates, then stopped ones under a heading, each a row with its tile, title, time, recurrence in words and criticality chip. State: mixed.
10. **Reminder editor** (create and edit). Bar with "Save". Fields in this order: title; time; repeats (Daily, chosen weekdays as seven chips, or every N days with a number and a start date); criticality; type (five chips, each with its tile icon); tags (seven chips, any number); dosage; doctor's instructions (a multi line field labelled "Shown exactly as you type it"); notes; inventory count and refill threshold, shown for the medicine type only; vibration (four choices); and, when editing, "Stop this reminder" or "Start this reminder again". There is no mission control. Saving an edit that withdraws open occurrences shows a confirm dialog that lists them by date and time; saving an edit whose new time has already passed today shows one line after saving: "Today's reminder stays at" and its time, "The new time starts tomorrow." States: create, empty; edit, filled; the weekday choice; the confirm dialog; the stays today line.
11. **Water.** Bar. The progress bar and figures, "Add a glass", today's entries as a list of times, the goal field in millilitres, and nudges: a choice of 0 to 4 a day with one sentence saying they are quiet and approximate. States: empty; part way; at the goal.
12. **Nutrition** (from Overview). Bar. A day and week switch; for each tag that has a count, a row with the tag's name and its number of servings. No bars, no targets, no colour. A sentence under the title: "Counts of what you marked as taken." States: day; week; empty.
13. **Overview** (the dashboard). Bar. In this order: the **week card**, on `tile` as the mockup tints it, with "Week" and the number in `display` and the due date and the days to it in `body` (in the postpartum phase or outside weeks 0 to 42, the due date only); today's three figures; water progress; "Days in the last 30 with every critical reminder taken" and its number; a row of three shortcut tiles (Reminders, Nutrition, Water); a row to "How reminders are arriving". States: default; empty.
14. **Settings.** Bar. Rows: Appearance (opens the sheet); Language; Quiet hours; Daily ring limit; Snooze length; Louder sound after; Notification channels (opens the system's channel settings); Reminders; Starter schedule; Permissions; How reminders are arriving; Share reliability data, with its copy. State: default.
15. **How reminders are arriving** (the reliability view). Bar. Every banner; the check and its status; the report sentences in a card; the opt in; "Export". States: no fires yet; with fires and two banners.
16. **Appearance sheet** over Today. States: System chosen; Pastel chosen with Lavender.
17. **The ring screen** (views, ADR 0073). No bar and no switcher. The whole screen is `ringBg`. At the top, a 72 dp circle in `ringCard` holding the bell icon in `ringAction`, overlapping the first card's top edge by half its height, as in the mockup. Then one `ringCard` (radius 20, padding 20) per due occurrence: her title, centred, in `display` for one occurrence and `title` for several; the dosage and instructions with their labels; and its actions as three full width buttons in `ringAction`, at least 64 dp high, stacked, each with its icon before its label in `label` at 20 sp: a tick and "Taken", a clock and "Snooze" with its minutes, a cross and "Skip"; the line that says snooze is not available where it is not. Below the cards, on `ringBg` in `onRingBg`: "Stop the sound" as a text button, and the "sound is off" line. With nothing due: the bell's circle, one sentence in `onRingBg`, and one filled button that opens the app. States: nothing due; one occurrence; three occurrences; snooze unavailable.
18. **The widget.** A 2 by 1 cell card in `surface`, radius 20: "Water" and today's figures in `caption`, a thin progress bar, and a 48 dp circular button in `primary` with a plus, content description "Add a glass". States: empty; part way.

## 7. Iconography

Material Symbols, Rounded, weight 400, 24 dp, copied in as vector drawables (ADR 0085). The whole list, 21 icons: `today`, `dashboard`, `settings`, `add`, `light_mode`, `dark_mode`, `brightness_auto`, `medication`, `pill`, `restaurant`, `nutrition`, `event_note` (the five task types, in the enum's order), `water_drop`, `info`, `block`, `check`, `chevron_right`, `arrow_back`, `close`, `notifications`, `schedule`. The ring screen uses `notifications` for the bell, `check`, `schedule` and `close` for its three actions. An icon not on this list is a design STOP.

## 8. Illustration

One original vector drawable, for onboarding step 1: a teacup and a small plant on a shelf, drawn from circles and rounded rectangles, in `tile`, `outline` and `primary` only (so it follows the appearance), 160 dp high. It is drawn in the repo by the implementer from this description, is not traced from anything, and is the only illustration in Phase 3. No figure of a woman, a baby or a body.

## 9. Copy

English strings are written in the pull request that adds each screen, plain and short, and are frozen by the string freeze (ADR 0077). `CopyRulesTest` is the floor (ADR 0076). The words for states and actions are fixed here so that screens agree: "To do", "Done", "Skipped", "Missed", "Taken", "Taken late", "Skip", "Snooze", "Snoozed until".
