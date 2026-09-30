# 0020. Missions: opt-in, non-physical, barcode/photo only, bundled ML Kit model

Date: 2026-09-29
Status: Accepted

## Context

A gamified "verify you actually took the medicine" mission is a plausible engagement feature, but the obvious versions (a squat, a step count, a math problem to prove alertness) are unsafe or inappropriate for a third-trimester pregnant user, and a Play-Services-dependent barcode scanner fails exactly when Play Services is mid-update — an unacceptable failure mode for something gating dismissal of a medication alert.

## Decision

Missions are opt-in per template, defaulting to `MissionConfig.None`. Only two mission types exist: `Barcode` (ML Kit with the bundled model, not the Play-Services-dependent one) and `PhotoMatch` (on-device perceptual hash, no ML model, reference image never uploaded). No physical exertion, no math, no typing. Every mission has a bypass path, logged as `MISSION_BYPASSED`.

## Alternatives considered

- Play-Services-dependent ML Kit barcode scanning — rejected; a scan failing because Play Services is updating is an unacceptable failure mode for a gate on medication-alert dismissal.
- A "shake to confirm alertness" or step-count mission, common in generic alarm apps — rejected outright as a physical exertion mission; unsafe for a third-trimester user and explicitly permanently excluded.
- No bypass path, to make the mission "real" verification — rejected; a missing or damaged blister strip / reference photo is a real scenario, and forcing a dead end there is worse than the mission being occasionally skippable, which is why bypass is logged distinctly rather than silently allowed.

## Consequences

The `MISSION_VERIFIED` / `MISSION_BYPASSED` distinction is what makes the caregiver dashboard able to show a verified completion differently from a self-reported tap — which is the entire justification for building the mission feature at all, per `ARCHITECTURE.md` §3.3.
