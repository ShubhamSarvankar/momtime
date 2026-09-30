# Manual checks

This file records what was checked, on what device, on what date, with what result. It exists because six things about the alarm subsystem cannot be proven without real hardware and real time (`IMPLEMENTATION_PLAN.md` Phase 7). Everything else is verified by automated suites — see `IMPLEMENTATION_PLAN.md`'s testing strategy summary.

Do not mark the alarm subsystem complete based on automated coverage alone. Report it as passing all automated layers with device checks outstanding until every row below has a result.

## The six items

| # | Item | Scripted procedure | Status |
|---|---|---|---|
| 1 | One UI's Sleeping apps / Deep sleeping apps behaviour killing alarms after days of low use | Soak script on a Galaxy A15 (Phase 7); read the reliability report after 72+ hours | Not yet run |
| 2 | Real Doze maintenance window cadence over multi-hour spans | A15 soak, real Doze, not `dumpsys deviceidle force-idle` simulation | Not yet run |
| 3 | Audio actually audible at correct volume through the real speaker, over Do Not Disturb | A15 soak + manual listen check | Not yet run |
| 4 | Full screen intent actually turning the screen on from locked, on real hardware | A15 soak, canary telemetry (`screen on` field) cross-checked against a manual observation | Not yet run |
| 5 | Timing drift under real thermal and battery conditions | A15 soak, canary telemetry (`scheduledInstant` vs `actualFiredAt`) | Not yet run |
| 6 | Samsung's scheduled alarm count behaviour, if ever exceeded | Only relevant if the one-alarm-at-a-time design (ADR 0017) is ever violated; not expected to trigger | Not yet run |

## Honesty constraint on claims

With one Samsung device (Galaxy A15) plus Firebase Test Lab's clean-state fleet, the defensible claim after Phase 7 is delivery measured on a specific device family under specific conditions — not validation across hostile OEM skins. No MIUI or ColorOS device will have been tested in its default aggressive configuration unless one is separately acquired. The A15 with One UI is moderately aggressive, not worst case.
