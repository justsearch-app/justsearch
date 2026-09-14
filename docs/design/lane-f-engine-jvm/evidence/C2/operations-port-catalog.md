# Operations port catalog correction — September 13

At 4d1e49bef the engine-port register still classified operations as register-governed
and said no interface existed. C2 had already implemented two distinct interfaces:
the producer lifecycle (`OperationAttemptRunner`) and durable persistence/lifetime
(`OperationStore`), both in app-api. The passing gate did not scan either binding.

Replace the stale operations entry with the runner interface and add an
operations-store entry for the store. Their implementations remain
OperationAttemptRunnerImpl and SqliteOperationStore in app-observability. This
catalog correction introduces no new port, store, writer or runtime binding.
HeadlessApp.java:1079-1081 and LauncherEnvironment.java:100,175 construct them;
EngineRoot carries the same instances into both halves. The store closes after index
drain. The operation-surface register remains the sibling-record/cardinality authority,
and OperationStoreArchitectureTest remains the lifecycle-call guard.

The implementation scan floor rises from three to five direct port declarations:
KnowledgeClient declares both SearchPort and IndexingService; NoopSearchPort declares
SearchPort; the runner and store each add one. EngineKnowledgeClient is an indirect
implementation checked separately through its declared extends relationship. The
former floor note inaccurately counted that indirect class instead of both facade
declarations; its explanation is corrected without weakening the floor.

Verification at 4d1e49bef plus this register change:

- `node scripts/governance/gates/engine-port/enforcer.test.mjs`: all seven cases pass.
- `node scripts/governance/run.mjs --gate engine-port --mode gate`: pass, five scanned
  declarations, no failures.
- Two negative register projections independently omit the runner and store binding.
  Both fail specifically with `engine-port/undeclared-implementation`, while source
  files and the production register remain unchanged.

Artifacts are worktree `tmp/c2-2-engine-port-before.txt`, `-after.txt`,
`tmp/c2-2-engine-port-negative.mjs`, and `-negative-results.json`. Retain through
lane acceptance plus 30 days and export before worktree removal. This is catalog
coverage, not proof of recorded producer activation or committed scan completion.
