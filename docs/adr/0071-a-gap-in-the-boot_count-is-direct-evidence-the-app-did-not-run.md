# 0071. A gap in the boot count is direct evidence that the app did not run after a restart

Date: 2026-10-05
Status: Accepted

Decided by Claude (technical review) in the review of PR #23 and the PR 8 prompt: the gap as evidence, the narrowing of the excuse, the banner state and its fix path, and its place in the report and the export as a count with no timestamp. That the gap is derived from the boot counts already stored, with no schema change, is the implementing session's judgment. It narrows ADR 0070's excuse for a never fired rung (ADR 0070 is merged and is not edited) and records the limit that ADR 0070 listed and left open: a boot the app never saw.

## Context

ADR 0070 excuses a never fired rung only if the phone stayed off past the end of its occurrence's grace, judged from the first recorded boot after the rung. It listed one limit: a boot the app never saw has no `boot_instant`, so a phone that ran through grace and was restarted after it, with the app never run in the earlier boot, was excused wrongly. That is exactly One UI's deep sleep: the phone restarts at 3 AM, the app is asleep, no boot pass runs, nothing re arms, and the 7 AM rung never fires; a second restart after grace then makes the first recorded boot after the rung look like the phone had been off all along.

`BOOT_COUNT` goes up by one with each boot. Every boot the app sees (the boot pass, or any process start, through `AppStart`) is recorded with its count (`boot_instant`, ADR 0070). So two recorded boots whose counts differ by more than one have at least one unseen boot between them: the app did not run in it.

## Decision

1. **A gap is read from the records, with no new column.** For a recorded boot, the unseen boots before it are its count minus the count of the recorded boot before it, minus one. The first record after an install has no predecessor and no gap: the app cannot know about boots before it was there, so a phone whose count is already 40 at install has not missed 39 boots. `BootGaps` is pure over the records. No schema change was needed, so there is no new android store migration step (`4.sqm` is merged).
2. **The excuse narrows.** A never fired rung is **never** excused across a gap between the rung and the first recorded boot after it (the boot that would have excused it), and **never** excused when the boot count is unknown now (a platform that does not report it cannot show there was no gap). Otherwise ADR 0070's rule stands: the first boot after the rung began after the end of grace.
3. **A gap raises its own banner state, "the app did not run after a restart"** (`Banner.NotRunAfterRestart`), from the first unseen boot in the window (seven days). It names a fix path as data: the **Sleeping apps and Deep sleeping apps steps on a Samsung** (the walkthrough, ADR 0072), the **battery step elsewhere**. It is direct evidence of One UI's deep sleep failure, not an inference from a missing fire.
4. **It is in the reliability report and the export as a count and nothing else.** `unseenBoots` is the number of boots in the window that the app did not run in. No instant of any boot is exported, and no boot count either (invariant 11; a count of unseen boots is not an identifier).

## Alternatives considered

- **Store the gap as a column on each boot record.** Rejected: it is derivable from the records exactly, and a stored copy could disagree with them. Reading it keeps one source.
- **Treat the first record after an install as a gap from zero.** Rejected: it would count every boot the phone had before the install, and raise the banner on every fresh install of a phone that has been up for months.
- **Excuse a rung across a gap if the boot after it began after grace anyway.** Rejected by Claude (technical review): that is the case being closed. With an unseen boot the phone may have been running through grace.
- **Count the gap only when the boot pass failed to run.** Rejected: a process start also records a boot, and what matters is that nothing ran in the boot, whichever of the two would have.

## Limits, said plainly

- A phone whose boot count never moves between records (it was not restarted) has no gap, however long the app slept. That is not a failure of this rule: nothing was lost to a restart.
- A boot count that is reset (a factory reset wipes the store with it) starts a new history.
- A restart that leaves the app asleep for good, with no later record at all, shows no gap until the app next runs.
- None of this has been run on a device (`MANUAL_CHECKS.md` P2-40).
