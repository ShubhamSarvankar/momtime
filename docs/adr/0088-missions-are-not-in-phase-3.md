# 0088. Missions are not in Phase 3

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by Claude (technical review) in the Phase 3 planning prompt (D7). The recommendation of an owning phase is the planning session's, for review.

## Decision

1. **Phase 3 builds no mission.** The schedule builder writes `MissionConfig.None` and shows no mission control; the "mission config" item is removed from the schedule builder's deliverable line. The ring screen's empty mission container stays empty (ADR 0062).
2. **Recommendation, recorded in the Open items row: Phase 5 owns missions, and the approval of ML Kit's bundled barcode model goes with Phase 5.** The only stated reason for missions is that the caregiver view can tell a verified completion from a self reported one and from a bypass (`ARCHITECTURE.md` section 3.3), and that view is Phase 5's. Building them earlier ships a camera permission, a Play declaration and a model of several megabytes with nobody to read the result.
3. **A constraint on whoever builds them** (ADR 0086): a mission that is verified or bypassed also writes `COMPLETED`, which is the event adherence and nutrition read.

## Alternatives considered

- **Missions in Phase 3 with the schedule builder.** Rejected by D7: an unapproved dependency and a camera permission in the phase that starts the Play tester clock.
- **Phase 9.** Possible if the caregiver phase is to stay small; the cost is that Phase 5's "verified, self reported and bypassed displayed distinctly" deliverable then shows only self reported completions.
