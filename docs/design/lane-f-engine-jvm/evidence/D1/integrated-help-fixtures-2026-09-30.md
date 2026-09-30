# Integrated lane fixture corrections

Scope: nine integrated failures at 860cf0fd0, branch codex/lane-f-d1-help.
No commits, pushes, installed Engine or dev stack. The orchestrator explicitly grants sequential
focused Gradle commands and the final app-engine/app-services/indexer-worker aggregate test command.

Design before edits:

1. Pin Engine test SSOT to an isolated path at explicit priority; keep exact Blue/Green count assertions.
2. Reuse the harness configuration in the four other Engine fixtures that copied cwd-dependent assembly.
3. Pin WorkerBootFixture's help source similarly; its production schema catalog remains a separate fixture input.
4. Supply BootstrapPhysicalInitializationTest's mocked WorkerHost the real resolved fixture help source.
5. Put each lock probe's complete classpath in a UTF-8 Java argfile in its JUnit temp directory.
6. Retain child lifecycle, exclusion, retry and metadata assertions; no production behavior changes.

Original red: orchestrator full build log and XML under lane-f-pr1-verify/tmp/
lane-f-batch2-full-suite.log and lane-f-batch2-full-suite-red-xml. Nine failures: one Engine count,
two Bootstrap help submissions and six Windows child command-line length errors.

Formatting/compilation passed for all three touched modules. Focused regression XML so far:

| Class | Original tests/failures/skipped | Fixed tests/failures/skipped |
| --- | --- | --- |
| EngineMigrationLifecycleTest | 4/1/0 | 4/0/0 |
| BootstrapPhysicalInitializationTest | 10/2/0 | 10/0/0 |
| IndexRootLockExclusionTest | 7/5/0 | 7/0/0 |
| IndexRootLockFailureTest | 5/1/0 | 5/0/0 |

Original red messages: Engine `expected: <1> but was: <6>`; Bootstrap `expected: <true>
but was: <false>` and `Wanted but not invoked: knowledgeClient.submitBatch`; both lock probes
`CreateProcess error=206, The filename or extension is too long`. Original XML copied into
build/integrated-help-fixtures/original-red. Each focused run has its own log and XML snapshot
under build/integrated-help-fixtures, so later suites do not overwrite this evidence.

Additional focused fixture checks all passed (tests/failures/skipped):

| Class | Counts |
| --- | --- |
| EngineIndexingJobsFlowTest | 1/0/0 |
| EngineScanRootFlowTest | 1/0/0 |
| EngineRootInProcessPortsTest | 2/0/0 |
| RichDocumentInProcessTest | 1/0/0 |
| CandidateJournalSnapshotTest | 2/0/0 |
| BrakeExhaustedWorkerServesReadOnlyTest | 2/0/0 |
| DocumentIdentityBootImportTest | 5/0/0 |
| PreOpenSchemaMismatchBootTest | 17/0/0 |
| KnowledgeServerRecordedIngestionTest | 30/0/0 |
| CommittedBootRootReconciliationTest | 2/0/0 |
| ResumedMigrationMismatchBootTest | 1/0/0 |

Initial focused total: 90 tests, zero failures/errors/skips. Shared-wait rerun passed six tests:
EngineMigrationLifecycleTest 4/0/0, EngineSwitchingFenceBufferingTest 1/0/0 and
EngineVduMigrationReplayTest 1/0/0 (tests/failures/skipped), zero errors. The latter two were
each 1/1/0 in the first aggregate. Unique focused total is now 92 tests with no failures/errors/skips.
Their green log/XML live under EngineMigrationAndReplay-green. Final aggregate passed against
the completed Java patch at HEAD 860cf0fd0; all production files remain unchanged.
EngineAdditionalFixtures and WorkerAdditionalFixtures contain preserved logs and XML snapshots.
Final app-engine formatting/compilation also passed after updating the synchronization comment.

First aggregate: app-engine 459/2/0, app-services 3226/0/4, indexer-worker 1052/0/15
(tests/failures/skipped), zero errors. Its only failures are the two extra early-restart races;
all original nine failures are gone. App-services skips three existing disabled composition rules
and one unavailable Windows symbolic-link privilege. Worker skips twelve model-dependent ONNX
integration cases and three unavailable filesystem-capability fixtures.

Final aggregate: BUILD SUCCESSFUL in 21m 42s, exit 0. Engine reran all tests; app-services and
indexer-worker reused the successful first aggregate results (unchanged inputs, Gradle UP-TO-DATE).
Copied final XML under build/integrated-help-fixtures/three-module-suite-final, plus the command's
log at build/integrated-help-fixtures/three-module-suite-final.log. Result XML summary:

```text
app-engine: tests=459 failures=0 errors=0 skipped=0
app-services: tests=3226 failures=0 errors=0 skipped=4
indexer-worker: tests=1052 failures=0 errors=0 skipped=15
total: tests=4737 failures=0 errors=0 skipped=19
```

All requested classes and each additional affected fixture class passed. No unresolved defect.
Existing environment/model/deferred-rule skips remain as identified above. No commits or pushes,
no dev stack or installed Engine, and no subagents. Only focused Gradle commands and the explicitly
authorized three-module aggregate were run, one command at a time.

`git diff --check` is clean. `git diff | grep -P '^\+.*[^\x00-\x7F]'` prints nothing
(grep exit 1, no matches). Current tracked Java diff: 12 files, 48 insertions, 54 deletions;
this evidence note is an additional untracked file. No production files changed.

The first isolated migration run exposed three early-restart races: accepted start dispatches
asynchronous Green preparation, whose extraction child probe takes about ten seconds here;
server close intentionally retains the owner after five seconds waiting for runtime replacement.
The restart tests now assert the actual published Green runtime before closing. Count divergence
removes the second source before dispatch, since live enumeration can begin before restart.
All existing count, pause, cutover, rollback and durable restart assertions remain in place.
The additional red XML is preserved in build/integrated-help-fixtures/EngineMigrationLifecycleTest-first/.

The first aggregate run exposed the same async-start/early-close failure in
EngineSwitchingFenceBufferingTest and EngineVduMigrationReplayTest. Both failed at their first
restart with `Active runtime replacement prevented close; server retained for retry`.
The exact published-runtime wait is now shared by EngineTestHarness and asserted before those
restarts too. Their SWITCHING buffer, VDU replay and durable restart assertions are unchanged.
The aggregate red remains under three-module-suite; the rerun gets three-module-suite-final.

## Commands

All commands run sequentially in lane-f-d1-help with the orchestrator's explicit Gradle grant.
Task-local GRADLE_USER_HOME=build/help-gradle-home, read-only dependency cache F:/caches/gradle/caches,
and JAVA_TOOL_OPTIONS points java.io.tmpdir at build/help-java-temp (avoids Windows user-temp ACLs).
Spotless/compile for app-engine was repeated after the migration synchronization adjustment.
The migration class was run once red on restart synchronization, then again green.

```text
./gradlew.bat :modules:app-engine:spotlessApply :modules:app-engine:compileJava :modules:app-engine:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:app-services:spotlessApply :modules:app-services:compileJava :modules:app-services:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:spotlessApply :modules:indexer-worker:compileJava :modules:indexer-worker:compileTestJava -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.util.IndexRootLockExclusionTest -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.util.IndexRootLockFailureTest -PskipWebBuild=true
./gradlew.bat :modules:app-services:test --tests io.justsearch.app.services.worker.BootstrapPhysicalInitializationTest -PskipWebBuild=true
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.EngineMigrationLifecycleTest -PskipWebBuild=true
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.EngineIndexingJobsFlowTest --tests io.justsearch.app.engine.EngineScanRootFlowTest --tests io.justsearch.app.engine.EngineRootInProcessPortsTest --tests io.justsearch.app.engine.RichDocumentInProcessTest -PskipWebBuild=true
./gradlew.bat :modules:indexer-worker:test --tests io.justsearch.indexerworker.server.CandidateJournalSnapshotTest --tests io.justsearch.indexerworker.server.BrakeExhaustedWorkerServesReadOnlyTest --tests io.justsearch.indexerworker.server.DocumentIdentityBootImportTest --tests io.justsearch.indexerworker.server.PreOpenSchemaMismatchBootTest --tests io.justsearch.indexerworker.server.KnowledgeServerRecordedIngestionTest --tests io.justsearch.indexerworker.server.CommittedBootRootReconciliationTest --tests io.justsearch.indexerworker.server.ResumedMigrationMismatchBootTest -PskipWebBuild=true
./gradlew.bat :modules:app-engine:test --tests io.justsearch.app.engine.EngineMigrationLifecycleTest --tests io.justsearch.app.engine.EngineSwitchingFenceBufferingTest --tests io.justsearch.app.engine.EngineVduMigrationReplayTest -PskipWebBuild=true
./gradlew.bat :modules:app-engine:test :modules:app-services:test :modules:indexer-worker:test -PskipWebBuild=true --continue
```

## Cwd discovery audit

EngineTestHarness.publishConfig and four copied Engine test publishers inherited repository help:
EngineIndexingJobsFlowTest, EngineScanRootFlowTest, EngineRootInProcessPortsTest and
RichDocumentInProcessTest. The copies now delegate to the shared publisher, whose explicit
test SSOT has priority 500 and survives restarts. Intentional SSOT extras remain supported.
WorkerBootFixture.publishConfig had the same latent Green coverage issue; it now pins isolated
help while productionCatalog() continues to load the real schema independently.

BootstrapPhysicalInitializationTest's WorkerHost was a mock with a null bundledHelpSource(),
although helpFile() still created SSOT/docs/help. Bootstrap correctly consumes its host's resolved
source, not workingDir. The fixture now derives that source through ResolvedConfig and returns it
from the mock host; retry/marker/physical recovery assertions are unchanged.

modules/ui/build.gradle.kts:2198-2202 gives runHeadlessEval explicit SSOT and eval.mode=true.
scripts/jseval/jseval/backend.py:257-260 launches that task. Both Bootstrap and Green exclude help
in eval mode. EngineIndexBench and EngineVectorIndexBench open IndexSchema directly and never run
Bootstrap or migration enumeration. jseval's ingest benchmark operates on the caller's backend;
an arbitrary non-eval backend retains its normal help corpus, as it already did before discovery.
LauncherEnvironment and ConfigStoreRebuilder use normal production discovery intentionally;
RuntimeSession's config fallback alone does not ingest files.

Two legacy system-test publishers (VduBatchProcessorE2ETest and SummarizationPipelineE2ETest)
still resolve repository SSOT, but neither invokes Bootstrap help ingestion or Green migration.
They currently have a help descriptor without help admission; this is a future isolation consideration,
not a newly observed document-count defect. No code outside the three assigned modules changed.
