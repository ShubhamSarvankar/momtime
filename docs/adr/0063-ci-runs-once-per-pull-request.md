# 0063. CI runs once per pull request

Date: 2026-10-04
Status: Accepted

Decided by Claude (technical review) in the PR 5 prompt; implemented in `phase-2/delivery`.

## Context

The workflow ran on every push and on every pull request. A branch with an open pull request therefore ran the whole suite twice for each commit, once as a `push` run and once as a `pull_request` run. The required checks attach to the pull request run, so the push run added cost and a second thing to read, and in PR 4 a handover quoted the push run while the pull request run was the one that mattered.

## Decision

The workflow's `push` trigger is restricted to `main`; `pull_request` stays. A pull request gets one run, the one the required checks attach to. `main` still gets its own run when a merge lands. Nothing changes for branch protection: the check names are unchanged and attach to the pull request run as before. A push to a branch with no pull request now runs nothing until a pull request is opened, which is the behaviour wanted.

## Consequences

Shubham is asked to confirm in the pull request that the required checks still attach and report (the check names are the ten job names, unchanged). A direct push of a branch is no longer tested before a pull request exists.

## Alternatives considered

- **Keep both and read both.** The rule "every run on the head" is then twice the reading for no added assurance.
- **`concurrency` cancelling the older run.** It would cancel one of the two, not remove the duplication, and it can cancel a run on `main`.
