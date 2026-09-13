# C2-2 VDU client control errors

September12, code base c950e8c72 plus the bounded VduOps/test diff; Windows/Java25.

VduOps update/mark caught every executor/transport exception and returned false/-1,
making cancellation/refusal look like an application-level rejection or retry exhaustion.
They now propagate the original exception, matching the strict count/query/recovery
boundary. Explicit response success=false remains false/-1. No request/context/deadline
mapping changes, new authority, schema or wire fields.

The existing RecordingRpc test boundary throws each of transport failure, open circuit,
EngineWorkCancelledException and EngineExecutorRejectedException. Both methods must
propagate the same object and retain exact context/category. Success and application
failure witnesses inspect the emitted request fields and preserve the normal result.

Negative692 runs old c950 production with the new tests:25 cases/5 suites,8 intended
failures (all four control failures through both methods),0 errors/skips. Existing backlog
and explicit application outcome cases pass. Positive693 executes70 cases/20 suites,
0 failures/errors/skips, plus app-services main/test PMD.

```text
# Negative692
gradlew.bat :modules:app-services:test --tests '*VduBacklogReadTest' -PtestParallelism=1 --max-workers=4 --console=plain
# Positive693
gradlew.bat :modules:app-services:test --tests '*VduBacklogReadTest' --tests '*VduBatchProcessor*' --tests '*OfflineCoordinator*' :modules:app-services:pmdMain :modules:app-services:pmdTest -PtestParallelism=1 --max-workers=4 --console=plain
```

Raw logs, XML and counts are `tmp/c2-2-vdu-control-negative-692{.txt,-xml,-counts.json}`
and `tmp/c2-2-vdu-control-693{.txt,-xml,-counts.json}` in the lane worktree, retained until
lane acceptance plus30 days and exported before worktree release. Negative metadata
identifies old c950 production with new tests; no production mutant was applied.

Independent read-only review found no blockers and parsed the retained failure/pass XML.
Docs index/skill regeneration and checks, canonical links, module graph, runtime config
matrix, surface-altitude and diff whitespace pass.

Limits: this is client-layer propagation. The batch still catches generic exceptions;
C2-2's planned context/cancellation/actual-owner completion work must preserve the control
outcome end to end. It does not establish generation-boundary, installed/model or hosted
proof. Integrated/stress coverage remains required at the coherent owner boundary.
