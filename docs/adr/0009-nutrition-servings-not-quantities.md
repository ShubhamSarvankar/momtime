# 0009. Nutrition: servings counted, never nutrient quantities

Date: 2026-09-29
Status: Accepted

## Context

"14 fruit servings this week" and "62% of your daily iron" look like the same kind of feature — nutrition tracking — but they are not. The first is a record of what she did. The second is a nutritional claim, and one this app cannot actually support: it doesn't know dosage, absorption, or her individual needs, and App Store guideline 1.4.1 requires cited sources for health/wellness guidance the app doesn't have.

## Decision

Nutrition aggregation counts servings by tag (`FRUIT`, `VEGETABLE`, `PROTEIN`, `IRON`, `CALCIUM`, `DAIRY`, `SUPPLEMENT`), derived from completed occurrences, never stored separately. No nutrient quantities, no percentage-of-recommended-intake figures, no target lines, no good/bad colour coding.

## Alternatives considered

- Percentage-of-RDA figures with a disclaimer — rejected; a disclaimer doesn't change that the app doesn't actually know dosage or absorption, so the number would be fabricated precision dressed up as fact.
- Not tracking nutrition at all — rejected as underserving a real, low-risk use case (counting servings is genuinely useful and genuinely safe); the point is drawing the line at quantities, not eliminating the feature.

## Consequences

The nutrition feature stays honest about what it actually knows (what she logged) versus what it doesn't (nutritional adequacy). This is a permanent constraint (see `CLAUDE.md`, "No nutrient quantities"), not a v1 limitation to relax later.
