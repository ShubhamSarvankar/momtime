# 0054. A store failure is counted and logged in production and thrown in tests

Date: 2026-10-03
Status: Accepted

Decided by Claude (technical review) in the review of PR #16; implemented in `phase-2/arming`. ADR 0051 is not edited. It decided that no call on the alarm path throws; this refines how that is done.

## Context

ADR 0051 made the android store's repositories non fatal: a failure returns a fallback so that a lost telemetry row or armed record never stops an alarm. Swallowing every failure hides bugs. A broken armed record write would show up only as a repair on every watchdog pass, and nobody would notice.

## Decision

1. **Production:** a failure stays non fatal and is counted per operation and logged (`CountingStoreFailures`). The counts are what the reliability view reads in PR 7, so a store that fails every time is visible. The log line carries the operation and the exception's class and nothing else: a database exception's message can carry SQL text or values, and no log may hold PII (invariant 11).
2. **Tests:** the store runs strict. A failure is thrown as an `Error`, so nothing on the alarm path catches it and a store bug fails the suite. The test graph is strict by default; a test of the production path passes the counting policy.
3. **The policy is one seam,** `StoreFailures`, passed to the repositories, so the repositories do not know which they run under.
4. **The lenient path keeps its own tests,** including the one that closes the store underneath the repositories and shows that none of the calls throws, and now also what was counted.

## Consequence found on adoption

Making the tests strict failed three existing tests that had relied on a failure being swallowed: the schema's CHECK rejections returned `false`, the corruption read returned "nothing read", and the closed store test. Each now says it is a test of the production path, and asserts the count. That is the point of the change: before it, no test could tell a rejection from a bug.

## Alternatives considered

- **Throw in production too.** Rejected: ADR 0051, and the project's first rule, that nothing may stop an alarm.
- **Count without logging, or log without counting.** Rejected: the count is the signal for the reliability view, and the log is what someone reads on a device.
- **A test only flag in production code.** Rejected: production code would carry a switch whose only purpose is tests, and a test that forgot the switch would pass.
