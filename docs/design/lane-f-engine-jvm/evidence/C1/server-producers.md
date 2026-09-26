# C1-7 KnowledgeServer background producers — 2026-09-09

KnowledgeServer's periodic stuck-job reaper and deferred model initializer now open executors
through WorkerExecutorRegistrations. Their names are `index.stuck-job-reaper` (scheduled) and
`index.deferred-model-init` (platform); both are background, one thread, one live instance, with
the existing background queue limit. The watcher/extraction registrations remain separate and
retain their two-instance replacement/service-generation policies.

The reaper's periodic future is explicitly canceled at shutdown, then its executor is stopped and
awaited before queue closure. Model initialization uses EngineFutures; shutdown waits for the
dedicated executor's actual termination before inspecting its future and closing model/runtime
fields. A canceled future alone cannot prove native initialization has exited. Direct
ExecutorService.close supplies the required actual-exit wait without a new persistent marker,
state machine or lifetime registry. Waiting may outlast a task that ignores interruption; closing
its resources anyway would be unsafe. The initializer already had an unbounded completion wait,
which remains covered by the inherited regression.

Local Windows/Temurin 25 proof above `e9024f85d`:

- Run62 passed worker registration and index-server producer/close tests.
- Run63 removed only the model executor's actual-exit wait. The canceled-running-initializer test
  failed because close completed while its supplier was still held.
- Run64 removed only the reaper executor's actual-exit wait. The reaper test failed because close
  completed before its callback exited and before safe queue retirement.
- Both mutations were restored in finally blocks. Run65 passed 3 worker-services and 11 indexer-worker
  tests (including automatic architecture checks) and PMD main/test
  checks for worker-services and indexer-worker. The producer tests verify the actual named
  registration open calls, model/queue close ordering, and the unchanged two-minute reaper cadence.
  The reaper fixture advances only the first scheduled tick; it does not change production policy.

Logs: `tmp/c1-batch4-server-producers-62.txt`,
`tmp/c1-batch4-server-producer-mutant-63.txt`,
`tmp/c1-batch4-server-producer-mutant-64.txt`,
`tmp/c1-batch4-server-producers-restored-65.txt`.
Preserved XML: `tmp/c1-batch4-server-producers-green-62`,
`tmp/c1-batch4-server-producers-green-65`, and matching mutant directories.
Retain these artifacts through lane completion plus 30 days. These are component ownership
proofs with injected initializer/queue operations; final installed real-model and full/stress
proof remains required for C1.

The canonical recovery description now covers periodic reclamation and distinguishes it from
restarting a failed loop. Documentation regeneration and link/config checks passed. Module graph
verification exposed the earlier C1 inference/telemetry dependencies on core missing from its
generated projection; regeneration restored those two edges, and the graph check passed.

Independent fanout review of the preceding checkpoint `e9024f85d` found no remaining concrete
defect after the real service catch correction. The reviewer did not run tests. Direct runtime
close mutations currently cover Hybrid; equivalent Chunk/three-way closure-path proof remains
in the C1 final verification work, alongside the remaining producers, retained-state bounds,
commit-timer safety, executable census, architecture/gate mutations and live/hosted obligations.
