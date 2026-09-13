# C1-10 child pacing lifetime — 2026-09-09

ForegroundLoadGate.callOwned extends the existing interactive call's single pacing increment
through explicit child task exits. Calls with no children balance at body return as before;
durable work retains the existing work-level completion/background-transition handling. A CAS
prevents an escaped factory from retaining a completed interactive pacing increment.

This checkpoint is part of the combined fanout candidate above 58b2eed9d. Six pacing tests and
the integrated Engine fanout/port tests passed in run55 (14 Engine tests total). The production
registry live-instance test passed in run56. Removing the child increment failed both refusal
and interruption tests with in-flight count 0 instead of 1. See
[fanout ownership evidence](fanout-ownership.md) for exact logs, preserved XML, mutation results,
and the final build's scope. The gate checkpoint alone does not yet connect the lower consumers;
the accompanying C1-7 item supplies the explicit port and runtime ownership path.
