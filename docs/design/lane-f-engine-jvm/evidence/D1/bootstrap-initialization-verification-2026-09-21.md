# D1 physical bootstrap initialization proof

Source: `93e206213` plus the source inventories below; Windows, 2026-09-21.
This is a readiness-migration prerequisite, not D1 acceptance.

## Ownership and behavior

Bootstrap start, direct health observation, required auxiliary initialization and
ordered close share the existing blocking `initLock`. The old initialization
generation counter and monitor capability-edge callback are retired. Two private
flags track direct physical health and successful initialization for that healthy
period. Readiness/API state cannot drive setup. Healthy polls initialize once;
false or throwing direct observations rearm setup and report `worker.lost` only
when the physical client had been healthy. Required setup failures remain retryable.
A partial failure can repeat already-issued catch-up work: this is not exactly-once
side-effect accounting. Help ingestion retains its existing best-effort marker
behavior and retries after physical recovery without blocking essential readiness.

The fatal-index latch now clears only on direct healthy observation. Merely
publishing capability READY cannot erase it. Temporary compatibility READY writes
remain until registry sampler migration; they no longer cause initialization.
Shutdown waits for an in-flight synchronous bootstrap action before closing its
client. Existing client shutdown owns asynchronous root-walk draining.

## Verification

- 2308: 43 cases / 11 suites, zero failures/errors/skips, fresh; affected format and
  PMD passed. Covers real bootstrap over a mocked physical client, healthy periods,
  reindex/sync setup failure and retry, close/restart, help retry, monitor and boot
  recovery. Does not claim real-index or live-model proof.
- 2309: expanded fatal-index suite, 58 cases / 13 suites, zero failures/errors/skips,
  fresh. Replaced the superseded projection-READY clearing test with the physical
  ownership invariant and a direct healthy-client/later-generic-loss regression.
- 2310: corrected close-contention proof, 13 cases / five suites, zero failures/
  errors/skips, fresh (seven physical initialization cases and six automatic module
  guardrails). It waits for the actual close thread to queue on the held initLock
  before releasing initialization; a mere “close started” latch was insufficient.
- 2311 negative: removed only close lock acquisition. The intended assertion failed:
  close did not contend on initialization. Source restored byte-exactly.
- 2312 negative: marked initialization complete before required setup. Both injected
  failure cases failed because the second reindex was never called. Source restored
  byte-exactly. Both restores have SHA-256
  `2bd3dc0b39573a73c908a80281110ae1f5d482d01a7e1420a05eef66a5c88c0c`.
- 2313: combined focused preparation tests pass 108 cases / 21 suites, zero
  failures/errors/skips, both test tasks fresh. Full `spotlessCheck pmdAll --continue`
  passes in the same 2m46s run. Also adds direct-client health-throw/rearm proof;
  no new production change. Production source SHA matches the negative restores.

Independent review found no remaining concrete seam defect after the physical
latch, contention witness and direct health-exception proof corrections. Review
ran no builds; the root owns all executed evidence. Required production registry
subscription, sampler publication, HTTP/manifest/schema/host migration and live
D1 model proof remain open.

Commands (from this worktree):

```powershell
# 2309
.\gradlew.bat :modules:app-services:test --tests '*BootstrapPhysicalInitializationTest' --tests '*KnowledgeServerHealthMonitorTest' --tests '*KnowledgeServerBootstrap*Test' --tests '*KnowledgeServerBootRecoveryTest' --tests '*SchemaMismatchFatalArcTest' --tests '*KnowledgeServerWorkerDownCodeTest' :modules:app-services:spotlessCheck :modules:app-services:pmdMain :modules:app-services:pmdTest -PtestParallelism=1 --continue
# 2310
.\gradlew.bat :modules:app-services:test --tests '*BootstrapPhysicalInitializationTest' :modules:app-services:spotlessCheck :modules:app-services:pmdTest -PtestParallelism=1 --continue
# 2311 and 2312; each mutation is restored in finally, and XML copied before the next run
python -X utf8 tmp/2311-2312-bootstrap-negatives.py
# 2313
.\gradlew.bat :modules:app-services:test --tests '*BootstrapPhysicalInitializationTest' --tests '*KnowledgeServerHealthMonitorTest' --tests '*KnowledgeServerBootstrap*Test' --tests '*KnowledgeServerBootRecoveryTest' --tests '*SchemaMismatchFatalArcTest' --tests '*KnowledgeServerWorkerDownCodeTest' --tests '*HeadAssemblyTest' --tests '*AgentToolFactoryCompositionTest' --tests '*AgentOfferingIsExecutableTest' --tests '*RecordedHandlerCompositionTest' --tests '*ReadinessReconciliationTrigger*Test' --tests '*EngineLifecycleProjectionTest' :modules:app-engine:test --tests '*ComponentReadinessTriggerTest' spotlessCheck pmdAll -PtestParallelism=1 --continue
```

Raw logs, copied XML, counts and source inventories: worktree `tmp/2308-bootstrap*`,
`tmp/2309-bootstrap-reviewed*`, `tmp/2310-bootstrap-contention*`,
`tmp/2311-close-negative*`, `tmp/2312-init-retry-negative*`,
`tmp/2313-readiness-preparation*`. Retain through lane acceptance plus 30 days;
export before removing this worktree. Existing Error Prone advisory warnings are
preserved in logs and not suppressed; required PMD/format checks remain binding.
