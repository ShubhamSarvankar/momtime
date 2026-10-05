# 0076. Cutesy is visual, never verbal; the starter schedule offers structure only

Date: 2026-10-05
Status: Proposed (draft in the Phase 3 planning pull request; revisable until that pull request is merged)

Decided by Claude (technical review) in the Phase 3 planning prompt (D4, D6). The wording check is the planning session's, for review.

## Decision

1. **Shapes, colours, rounded type and original illustration may be soft. Copy stays plain**, most of all health adjacent copy, late and missed notices, the reset notification and the permission explanations.
2. **No animation, reward, praise or celebration that depends on adherence**: its absence becomes a guilt mechanism (the no punitive streaks rule). No single headline percentage or score; adherence is three figures. No "Activity" (nothing is tracked). Nutrition is counts, never a fraction of a target. No claims such as "Doctor Recommended" or "Healthy Baby". No advertising. No blank printable planner.
3. **Illustration is original vector art made in the repo**, never traced from the mockup, whose images are generated and carry no licence. Anything third party needs a recorded licence and Shubham's approval.
4. **The starter schedule names no medicine, supplement, food or dose.** It offers times, a criticality and empty titles she writes, with copy saying it is a starting point to edit. A named item would be health guidance that needs a cited source (`CLAUDE.md`, never reason about medication).
5. **A check as well as a review:** `CopyRulesTest` reads every string resource in every locale and fails on a list of banned phrases and patterns (praise words, a percent sign in an adherence or nutrition string, the mockup's ruled out phrases). The list is in the test. It is a floor; the human review of copy is still required.

## Alternatives considered

- **A starter schedule that names common prenatal supplements.** Not planned. If it is ever wanted it needs a cited source and the content table's citation fields, which is Phase 9's health content library.
