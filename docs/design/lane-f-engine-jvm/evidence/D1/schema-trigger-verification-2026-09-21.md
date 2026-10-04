# D1 schema projection, trigger and tool-binding proof

Source: `93e206213` plus the source inventories below, Windows, 2026-09-21.
This prepares the runtime migration; it does not claim D1 acceptance or schema-2
production wiring. C2 acceptance remains unchanged.

## Implemented and verified

`LifecycleProjection.project` derives schema-2 lifecycle and engine-component views
from one immutable registry snapshot. Its independent essential-state table covers
all 1,296 combinations, checks each component's distinct metadata, required-slot
omissions, cause priority and stable-name ties. Optional ABSENT is not degradation.
Legacy runtime derivation remains until the consumer migration replaces it.

`ReadinessReconciliationTrigger` owns one direct registry subscription and closes
it before its executor. The private single-worker identity suppresses synchronous
self-publication only; a different publisher during sampling still queues one
follow-up. Tests use the production registry, blocked observation, full-snapshot
CAS and FIFO executor barriers; background assertion failures are surfaced.
Actual production registry subscription wiring was subsequently connected and
verified in the [production trigger proof](production-trigger-verification-2026-09-21.md).

`HeadAssembly.connectKnowledgeServer` now resolves late-bound tool registration
after the client and service graph exist, without waiting for sampled READY.
`AgentToolHandlers` no longer takes a capability solely to gate composition.
The real assembly regression covers both PENDING and READY. Request admission
still uses existing capability and condition gates.

## Runs and artifacts

- 2303: initial schema projection, three tests, no failures/skips; affected static
  checks passed. Review found missing proof, not a production defect.
- 2304: revised projection, four tests including 1,296 combinations, no failures/
  errors/skips; affected static checks passed freshly.
- 2305: trigger seam plus existing trigger suites, 19 cases / seven suites, all
  fresh and passing; affected format and PMD passed.
- 2306 negative: removed only the sampler-thread guard. Both production-registry
  tests failed for the intended extra sample (expected one, got two; expected two,
  got three). Source restored byte-exactly, SHA-256
  `f172075eb7f96cdc628a4305fa73bebf272ceb223d4b692909040ce0c6a11232`.
- 2307: restored trigger, schema projection, actual HeadAssembly, tool composition,
  offering and recorded handlers: 55 cases / 12 suites, zero failures/errors/skips,
  both test tasks fresh. App-services format and PMD passed. Four existing
  HeadAssembly Error Prone warnings remain advisory (ignored bridge Future,
  locale-less case conversion, duration spelling and lambda shape); none suppressed.

Exact focused commands:

```powershell
# 2304
.\gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.lifecycle.EngineLifecycleProjectionTest :modules:app-services:spotlessCheck :modules:app-services:pmdTest -PtestParallelism=1
# 2305
.\gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.ComponentReadinessTriggerTest :modules:app-services:test --tests '*ReadinessReconciliationTrigger*Test' :modules:app-engine:spotlessCheck :modules:app-engine:pmdTest :modules:app-services:spotlessCheck :modules:app-services:pmdMain :modules:app-services:pmdTest -PtestParallelism=1 --continue
# 2306
python -X utf8 tmp/2306-trigger-negative.py
# 2307
.\gradlew.bat :modules:app-services:test --tests '*HeadAssemblyTest' --tests '*AgentToolFactoryCompositionTest' --tests '*AgentOfferingIsExecutableTest' --tests '*RecordedHandlerCompositionTest' --tests '*ReadinessReconciliationTrigger*Test' --tests '*EngineLifecycleProjectionTest' :modules:app-engine:test --tests '*ComponentReadinessTriggerTest' :modules:app-services:spotlessCheck :modules:app-services:pmdMain :modules:app-services:pmdTest -PtestParallelism=1 --continue
```

Raw output, source inventories, counts and copied XML are under this worktree's
`tmp/2303-schema2-projection*`, `tmp/2304-schema2-projection-reviewed*`,
`tmp/2305-trigger*`, `tmp/2306-trigger-negative*` and `tmp/2307-tools*`.
Retain through lane acceptance plus 30 days; export before removing the worktree.
Independent review found no concrete trigger or revised projection defect. It did
not execute checks. Pure projection tests do not establish HTTP/manifest parity,
and callback tests do not issue a real model query or prove host consumption.

## Next integration

Complete physical-health-owned bootstrap initialization, replace mutable capability
writers/readers, wire the registry subscription, then connect the sampler,
manifest, schema-2 HTTP contracts and hosts. The bootstrap audit is a finding map;
its suggested extra epoch counters are not an accepted implementation requirement.
