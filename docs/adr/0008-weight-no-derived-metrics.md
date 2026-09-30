# 0008. Weight: no derived metrics, ever

Date: 2026-09-29
Status: Accepted

## Context

Weight tracking during pregnancy is an obvious candidate for BMI, gain-rate calculations, IOM range bands, and "on track" feedback — the features every generic pregnancy-tracking app ships. Those features turn a record into a verdict, and a verdict about weight aimed at a pregnant woman is precisely the kind of feature that causes real harm (pressure, disordered eating triggers, anxiety) disproportionate to any benefit, and none of it is store-approvable without a cited source under health-content policy anyway.

## Decision

The app never derives anything from weight: no BMI, no gain rate, no target range, no range bands, no "on track" language, no colour coding, no notification, no badge, no streak, no prompt to weigh herself. The chart plots her data points against gestational week and nothing else. Logging is user-initiated only.

## Alternatives considered

- IOM-recommended range bands on the chart with a citation — technically satisfiable under App Store guideline 1.4.1 with a source, but rejected anyway: a range band is a judgment about her body regardless of how well-cited it is, and the product's stance is that this isn't the app's place to make that judgment.
- A private (not caregiver-visible) "on track" indicator, reasoning that privacy mitigates the harm — rejected; the harm is the feedback loop itself, not who else sees it.

## Consequences

The weight feature is deliberately less "smart" than competing apps, by design. The chart's entire value proposition is that she can hand it to her obstetrician as a plain record — building it as feedback instead would undermine that purpose, not enhance it.
