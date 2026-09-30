# 0019. DeliveryCapability resolved at runtime, never inferred from OS version

Date: 2026-09-29
Status: Accepted

## Context

It's tempting to branch alarm-delivery behaviour on `Build.VERSION.SDK_INT` — "Android 14+ gets full-screen intent, older versions don't." That's wrong in practice: permissions can be revoked by the user or OEM regardless of OS version, and a modern device with a revoked permission behaves worse than an old device with everything granted.

## Decision

`DeliveryCapability` (Tier 1/2/3) is resolved at runtime by actually checking exact-alarm availability, `canUseFullScreenIntent()`, and battery-optimisation status — never inferred from SDK version. The UI and escalation policy read the resolved tier; the canary reports the tier the device *actually achieves*, not the one it claims to support.

## Alternatives considered

- Branching on SDK version with documented "known good" OS versions — rejected; it produces confident-but-wrong tier assignments whenever a user or OEM has revoked a permission the OS version would normally grant, which is common enough on this class of device to matter.
- Checking capability once at app install/first-run and caching it — rejected; permissions can be revoked later (mid-schedule), which is explicitly one of the golden test scenarios (§3), so capability has to be re-checked, not cached indefinitely.

## Consequences

Every capability-dependent code path must call the actual runtime check rather than a version guard, which is slightly more verbose but is what makes the tier-downgrade golden scenarios (permission revoked mid-schedule) testable and correct rather than hopeful.
