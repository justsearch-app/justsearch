# C1 item checkpoints — 2026-09-09

The user authorized pushing every batch/item commit and committing work in progress at least
hourly. This explicitly permits recovery checkpoints before integrated verification. It does
not change stage acceptance, authorize a failed merge, or turn a pending check into a pass.
All existing worker implementation assignments are returned to the orchestrator; subsequent
worker assignments have at most three follow-ups before the orchestrator takes the diff.

## Reorientation at 06:03 UTC

Worktree: `.claude/worktrees/lane-F-A`; branch: `worktree-lane-F-A`;
base checkpoint: `95ac489b229cce52812025ffad4619f42ef83104`.
Stage B is complete. C1 batches 1 and 2 are committed; batch 3 remains partly uncommitted alongside
batch 4. C2 and later stages have not started implementation. The remote branch was still at
Stage B `739d58020`. No dev stack or Gradle task is running. Main's unrelated dirty files are
untouched. This record and per-item commits replace the oversized uncommitted continuation.

These commits preserve current intermediate files, including constructor/API dependencies across
items. They are explicitly WIP, not individually green stage boundaries. Shared composition
changes stay with their primary item; their dependencies are recorded below rather than erased
or reconstructed with temporary production fallbacks. No new implementation is added while this
split is in progress. Existing committed batches are preserved without history rewriting.

## Evidence and practical limits

All recent checks tested dirty work above the base, not these exact isolated commit trees:

- Worker focused run11: 120 tests, zero failures/errors/skips; `tmp/c1-batch4-worker-tests-11.txt`,
  XML `tmp/c1-batch4-worker-green-11/`.
- Core timeout tests13 passed; `tmp/c1-batch4-future-timeout-tests-13.txt`. Removing physical task
  cancellation failed both intended timeout tests; `tmp/c1-batch4-future-timeout-mutant.txt` and
  `tmp/c1-batch4-future-timeout-proof/mutant.xml`. Mutation was restored.
- Inference production/test compilation passed in `tmp/c1-batch4-inference-compile-14.txt` before
  the worker's final regression additions. Those newest tests remain uncompiled/unexecuted.
- Earlier composition run4 passed 185 tests. Build8/9 and composition run10 were red and retained;
  corrections are implemented but no current integrated green result exists.
- Batch-3 live aggregate/fairness and 7048/7048 continuous-search results are in `batch-3.md`.
  They precede executor migration and cannot close final C1 acceptance.

Required next work remains: finish Lucene composition and direct caller/test injection; verify
inference cancellation/overflow and all shutdown corrections; finish agent-history queue-overflow
reconciliation and its regression; handle the running EngineFutures cleanup-failure review finding;
finish OCR, per-source search, index startup/reaper and any remaining executor census sites;
bound ScanProgressRegistry retained history/subscriber state; complete Rule 9/census/pin mutation
proofs, regeneration, full stress-enabled checks, and final installed live admission/pacing.
After C1 passes, continue C2/D/E/F in the governing design's order, with merge placement F/PR1.
Nothing is owner-gated, and no required check is waived by these checkpoints.

## Item split

C1-5 contains the neutral registry, implementation, provider and completion-owning task foundation.
C1-11 contains the exact-work/context, cancellation and complete-turn contract and consumers.
C1-8 contains the admission owner and ingress/refusal projections; its owner also implements the
aggregate decision, whose policy/diagnostic harness is C1-9. C1-10 contains the urgency-based port
pacing producer, including its shared call-executor wiring. C1-15 finishes authorized fetch/wait UI.
C1-6 contains the twelve original bare-async owners and their directly related callers/tests;
root composition in HeadlessApp/KnowledgeServer also injects C1-7 owners. C1-7 contains remaining
registered producer wiring, queue separation and lifecycle changes. C1-14 contains parser boundary
changes, including registration arguments shared with C1-7. C1-16 records docs/evidence and the
remaining sweep; it does not claim the residue sweep is complete.

The exact initial path assignment follows. Later corrective item commits are expected.

### C1-10

- `modules/app-engine/src/main/java/io/justsearch/app/engine/BoundedHandoff.java`
- `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineKnowledgeClient.java`
- `modules/app-engine/src/main/java/io/justsearch/app/engine/ForegroundLoadGate.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineForegroundPacingTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineKnowledgeClientExecutorTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EnginePortForegroundTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/ForegroundGateMatchesSearchOpsTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/ForegroundLoadGateTest.java`

### C1-11

- `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEvent.java`
- `modules/app-agent-api/src/main/java/io/justsearch/agent/api/AgentEventPayloads.java`
- `modules/app-agent-api/src/main/java/io/justsearch/agent/api/lifecycle/LifecycleState.java`
- `modules/app-agent-api/src/test/java/io/justsearch/agent/api/lifecycle/LifecycleStateTest.java`
- `modules/app-agent/build.gradle.kts`
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentEventTracing.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentLlmCaller.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentLoopService.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentSession.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentStepRunner.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/BackgroundRunService.java`
- `modules/app-agent/src/main/java/io/justsearch/agent/CancelTrigger.java`
- `modules/app-agent/src/test/java/io/justsearch/agent/AgentLoopServiceTest.java`
- `modules/app-agent/src/test/java/io/justsearch/agent/AgentMetricWireFormatRegressionTest.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/EngineWorkCancelledException.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/EngineWorkHandle.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/OnlineAiService.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/OperationLeaseService.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineWorkCancellationTest.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/StreamWorkOwner.java`
- `modules/app-observability/src/main/java/io/justsearch/app/observability/stream/run/AbstractRunChannel.java`
- `modules/app-observability/src/test/java/io/justsearch/app/observability/stream/run/RunRetirementRegistrationTest.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/AgentLoopWiring.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/AgentRunShape.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/ConversationEngine.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/HierarchicalShapeRunner.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/ToolIteratingShapeRunner.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/WorkflowShapeRunner.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/QueryRewriteInjector.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/URLExtractor.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/intent/EngineProvenance.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/lease/OperationLeaseServiceImpl.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/conversation/HierarchicalShapeRunnerTest.java`
- `modules/core/src/main/java/io/justsearch/core/context/EngineContext.java`
- `modules/core/src/test/java/io/justsearch/core/context/EngineContextTest.java`
- `modules/ui-web/src/api/generated/shape-handlers/core-agent-run.ts`
- `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/ChatController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/RunStreamWriter.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/AgentControllerApprovalDispatchTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/AgentControllerShutdownTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/AgentSseContractTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/BackgroundWorkLifetimeTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerConversationJoinTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerDelegateHistoryTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerHeartbeatTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerLockedDispatchTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ChatControllerTitleTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/EngineAgentWorkTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/EngineConversationWorkTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/EngineDurableSseWorkTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/EngineSseAdmissionLifetimeTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/RunStreamWriterTest.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerIngestService.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIndexingFeedCancellationTest.java`
- `scripts/codegen/shapes.fixture.json`

### C1-14

- `modules/app-launcher/src/test/java/io/justsearch/app/launcher/ExtractionParserConfinementTest.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactory.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/PersistentExtractionSandbox.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/TimeboxedContentExtractor.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/server/DefaultWorkerAppServices.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/AdversarialCorpusIngestionTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionRoutingTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxFactoryTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/ExtractionSandboxLatencyBenchmarkTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PersistentExtractionSandboxTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/PolicyDrivenTikaExtractorTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/extract/TimeboxedContentExtractorTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/server/DefaultWorkerAppServicesSandboxProbeTest.java`

### C1-15

- `modules/ui-web/src/shell-v0/api/authorizedFetch.ts`
- `modules/ui-web/src/shell-v0/state/admissionWaitNotice.ts`
- `scripts/jseval/jseval/ui_check.py`
- `scripts/jseval/jseval/ui_step_index.json`

### C1-16

- `docs/design/lane-f-engine-jvm/design.md`
- `docs/design/lane-f-engine-jvm/evidence/C1/batch-3.md`
- `docs/design/lane-f-engine-jvm/evidence/C1/batch-4.md`
- `docs/design/lane-f-engine-jvm/handoff.md`
- `docs/design/lane-f-engine-jvm/stages/C1.md`
- `docs/design/lane-f-engine-jvm/stages/C2.md`
- `docs/explanation/03-knowledge-server.md`
- `docs/reference/api-contract-map.md`
- `docs/reference/architecture/module-deps.md`

### C1-5

- `modules/app-engine/build.gradle.kts`
- `modules/app-engine/src/main/java/io/justsearch/app/engine/DefaultEngineExecutorRegistry.java`
- `modules/app-engine/src/main/resources/META-INF/services/io.justsearch.core.execution.EngineExecutorRegistry`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/DefaultEngineExecutorRegistryTest.java`
- `modules/core/build.gradle.kts`
- `modules/core/gradle.lockfile`
- `modules/core/src/main/java/io/justsearch/core/execution/EngineExecutorRegistry.java`
- `modules/core/src/main/java/io/justsearch/core/execution/EngineExecutorRejectedException.java`
- `modules/core/src/main/java/io/justsearch/core/execution/EngineExecutorSnapshot.java`
- `modules/core/src/main/java/io/justsearch/core/execution/EngineExecutorSpec.java`
- `modules/core/src/main/java/io/justsearch/core/execution/EngineFutures.java`
- `modules/core/src/test/java/io/justsearch/core/execution/EngineFuturesTest.java`
- `modules/core/src/testFixtures/java/io/justsearch/core/execution/TestEngineExecutors.java`

### C1-6

- `modules/app-api/src/main/java/io/justsearch/app/api/DocumentService.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/LlamaServerOps.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineModeOps.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerOpsCrashTelemetryTest.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineModeOpsTest.java`
- `modules/app-launcher/src/test/java/io/justsearch/app/launcher/LayeringEnforcementTest.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/GplOrchestration.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/gpl/GplJobCoordinator.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteDocumentService.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/GplOrchestrationExecutorTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplFetchDocumentsByteBudgetTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplJobCoordinatorExecutorTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplJobCoordinatorTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceCollectionScopeTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceContextBudgetTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceExecutorTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServiceExportSeamTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteDocumentServicePreSearchPipelineTest.java`
- `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java`
- `modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java`

### C1-7

- `config/profiles/smoke.yaml`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ChunkSearchOps.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/CommitOps.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/DeferredRuntime.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/HybridSearchOps.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/LuceneExecutorRegistrations.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/LuceneRuntime.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/LuceneRuntimeBuilder.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/ReadOnlyRuntime.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RunningRuntime.java`
- `modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RuntimeSession.java`
- `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/ChunkSearchIntegrationTest.java`
- `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/HybridSearchIntegrationTest.java`
- `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/LuceneExecutorRegistrationsTest.java`
- `modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/RuntimeTestBase.java`
- `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineRoot.java`
- `modules/app-engine/src/main/java/io/justsearch/app/engine/ShutdownRequestWatcher.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineContextPortPropagationTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExecutorLifetimeTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineIndexingJobsFlowTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineMigrationRestartDispatchTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineReadWhileWriteTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootInProcessPortsTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineRootTerminalWriterFailureTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineScanRootFlowTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/RichDocumentInProcessTest.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/ShutdownRequestWatcherTest.java`
- `modules/app-inference/build.gradle.kts`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/AsyncInferenceTransitionLog.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceExecutorRegistrations.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/InferenceLifecycleManager.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/OnlineAiServiceImpl.java`
- `modules/app-inference/src/main/java/io/justsearch/app/inference/StreamCallbackPump.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerExternalServerTest.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerIdentityTest.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerPropsInsightsTest.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/InferenceLifecycleManagerShutdownTest.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/LlamaServerLogRetentionTest.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/ManagedLlamaAdoptionTest.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/OnlineAiServiceImplTest.java`
- `modules/app-inference/src/test/java/io/justsearch/app/inference/OwnedStreamCancellationTest.java`
- `modules/app-launcher/build.gradle.kts`
- `modules/app-launcher/src/main/java/io/justsearch/applauncher/LauncherEnvironment.java`
- `modules/app-launcher/src/test/java/io/justsearch/app/launcher/BoundaryRulesTest.java`
- `modules/app-launcher/src/test/java/io/justsearch/applauncher/LauncherEnvironmentCloseTest.java`
- `modules/app-launcher/src/test/java/io/justsearch/applauncher/LauncherExecutorProviderTest.java`
- `modules/app-launcher/src/test/java/io/justsearch/applauncher/SmokeDriverTest.java`
- `modules/app-observability/build.gradle.kts`
- `modules/app-observability/src/main/java/io/justsearch/app/observability/ledger/ScanRollupLedger.java`
- `modules/app-observability/src/test/java/io/justsearch/app/observability/ledger/ActionLedgerDuplicateDeliveryTest.java`
- `modules/app-observability/src/test/java/io/justsearch/app/observability/ledger/ScanRollupLedgerTest.java`
- `modules/app-services/build.gradle.kts`
- `modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/agenthistory/AgentHistoryIndexer.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/BootstrapInferenceFactory.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/OrchestrationHandles.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/BootstrapDocumentService.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/HealthSubstrateInit.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/IndexingJobsBridgeWiring.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/InferenceDecision.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/MetricSubstrateInit.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OperationSubstrateInit.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OrchestrationAssembly.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OrchestrationPhase.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/RuleRunnerBuilder.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/ServicePhase.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/SubstratePhase.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/encryption/UnlockDeferredScan.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/observability/health/ReadinessReconciliationTrigger.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/observability/metrics/DocumentsIndexedRateMetricProducer.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/observability/metrics/GpuMemoryUtilizationMetricProducer.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/observability/metrics/GpuUtilizationMetricProducer.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/observability/metrics/JobQueueDepthMetricProducer.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/observability/rules/RuleRunner.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/power/EnergyStatePoller.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduBatchProcessor.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduOfflineTriggerSampler.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduProcessor.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/ContextSufficiencyService.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/FilterNormalizationService.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeClient.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeSearchEngine.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerHealthMonitor.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/QueryUnderstandingService.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridge.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/ScanProgressRegistry.java`
- `modules/app-services/src/main/java/io/justsearch/app/services/worker/SyncOps.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/AppServicesMetricWireFormatRegressionTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/DefaultAppFacadeTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/HeadAssemblyTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/agenthistory/AgentHistoryIndexerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceChatProfileTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceExecutorTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/ai/runtime/RuntimeActivationServiceVariantsRootTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/AgentToolFactoryScanWiringTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/HealthSubstrateInitTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/OperationSubstrateInitTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/encryption/UnlockDeferredScanTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/inference/InferenceWireFormatRegressionTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/observability/health/ReadinessReconciledWithoutRequestTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/observability/health/ReadinessReconciliationTriggerExecutorTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/observability/health/ReadinessReconciliationTriggerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/observability/metrics/DocumentsIndexedRateMetricProducerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/observability/metrics/GpuMemoryUtilizationMetricProducerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/observability/metrics/GpuUtilizationMetricProducerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/observability/metrics/JobQueueDepthMetricProducerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/observability/rules/RuleRunnerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerExecutorTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/power/EnergyStatePollerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorAbstentionTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduBatchProcessorModeScopingTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduOfflineTriggerSamplerTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/vdu/VduProcessorAbstentionTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/FilterNormalizationServiceTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/HelpIngestMarkerRecoveryTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/IndexingJobsBridgeResubscribeTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerBootRecoveryTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerBootstrapEvalModeTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerBootstrapFaultInjectionTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerBootstrapLifecycleSignalsTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerBootstrapRestartabilityTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerHealthMonitorTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/KnowledgeServerWorkerDownCodeTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/MigrationOutcomeProjectionTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridgeTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/ScanProgressRegistryTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/SchemaMismatchFatalArcTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/SyncOpsReconcileVerificationTest.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/TestKnowledgeClient.java`
- `modules/app-services/src/test/java/io/justsearch/app/services/worker/WatchedRootScanCollectionTest.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/resolved/ConfigStore.java`
- `modules/indexer-worker/build.gradle.kts`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/BrakeExhaustedWorkerServesReadOnlyTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DevReloadManagerTriggerTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/DocumentIdentityBootImportTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerCloseCompletionTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/KnowledgeServerTerminalWriterBindingTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/PreOpenSchemaMismatchBootTest.java`
- `modules/indexer-worker/src/test/java/io/justsearch/indexerworker/server/ResumedMigrationMismatchBootTest.java`
- `modules/telemetry/build.gradle.kts`
- `modules/telemetry/gradle.lockfile`
- `modules/telemetry/src/main/java/io/justsearch/telemetry/LocalTelemetry.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/BaselineMetricCatalogSmokeTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/HistogramBucketsTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/JvmMetricCatalogSmokeTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/JvmRuntimeGaugesTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/LocalMetricsExporterTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/LocalTelemetryExecutorWiringTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/LocalTelemetryTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/catalog/MetricCatalogSmokeTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/catalog/NamespacePrefixRuleTest.java`
- `modules/telemetry/src/test/java/io/justsearch/telemetry/catalog/TagOrderTest.java`
- `modules/ui/build.gradle.kts`
- `modules/ui/gradle.lockfile`
- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/SchemaMismatchStatusContractTest.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/ActionLedgerController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/AdvisoryStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/AiRuntimeController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/AuthorizationController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/CapabilitiesStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/ConditionRecoveryIndexController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/ConversationApiAssembly.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/CoreApiAssembly.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/DiagnosticChannelStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/DocumentsIndexedRateMetricController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/EffectiveConfigController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/GpuMemoryUtilizationMetricController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/GpuUtilizationMetricController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/HealthEventStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/IndexingJobsStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/IntentStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/InteractionThreadController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/JobQueueDepthMetricController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/OpenAiCompatController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/OperationHistoryController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/ResourceApiModule.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/RetrieveContextController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/RunStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/RuntimeContextController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/ShellEventsStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/SseHeartbeat.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/routes/RuntimeApiRoutes.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/runtime/RuntimeManifestStreamController.java`
- `modules/ui/src/main/java/io/justsearch/ui/observability/GpuSaturationSampler.java`
- `modules/ui/src/main/java/io/justsearch/ui/runtime/ManagedChildReconciler.java`
- `modules/ui/src/test/java/io/justsearch/ui/HeadlessAppManagedChildStartupOrderingTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/HeadlessAppShutdownWiringTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/HeadlessAppStartErrorTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/HeadlessAppUpgradeShutdownWiringTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ActionLedgerControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/AdvisoryStreamControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/AuthorizationControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/CapabilitiesStreamControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/DiagnosticChannelStreamControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/HealthEventStreamControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/IndexingJobsSubstrateIntegrationTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleContractTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/LifecycleShutdownContractTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/LocalApiHostValidationTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/LocalApiServerFailClosedTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/OpenAiCompatControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/OperationHistoryControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ReadinessTriggerCompositionTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ResourceApiModuleShutdownTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/RunStreamControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/RuntimeContextControllerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/SdkOpenApiFixture.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/UiModeIntentHttpContractTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/UpgradeLifecycleContractTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/VduProcedureExecutorLifetimeTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/runtime/RuntimeClientHttpContractTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/observability/GpuSaturationSamplerTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/runtime/ManagedChildReconcilerTest.java`
- `modules/worker-services/build.gradle.kts`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/IndexingLoop.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/server/WorkerExecutorRegistrations.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/RagContextOps.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerMethvinWatcher.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerSearchService.java`
- `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/execute/SearchExecutor.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/TestWorkerExecutorRegistrations.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/embed/EmbeddingMetricWireFormatRegressionTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopErrorResilienceTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopRestartTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingLoopUnloadTelemetryEmitTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/IndexingPipelineWireFormatRegressionTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/observability/OrtSessionMetricWireFormatRegressionTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/server/WorkerExecutorRegistrationsTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/IndexRuntimeWireFormatRegressionTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WatcherTransitionMatrixGuardTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerOpsPacingWireFormatRegressionTest.java`
- `modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerOpsQueueMetricWireFormatTest.java`

### C1-8

- `SSOT/messages/errors.en.json`
- `modules/app-api/src/main/java/io/justsearch/app/api/ApiErrorCode.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/EngineAdmissionException.java`
- `modules/app-api/src/main/java/io/justsearch/app/api/EngineAdmissionService.java`
- `modules/app-api/src/main/resources/messages/errors.en.properties`
- `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineAdmissionController.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineAdmissionControllerTest.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/ApiErrorHandler.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/ApiSecurityFilters.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/RequestEngineContext.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/RequestEngineWork.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpProtocolHandler.java`
- `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ApiErrorHandlerCachingTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/ApiErrorHandlerResolveTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/EngineAdmissionTransportTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/EngineUpgradeLifecycleTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchControllerExecutorRefusalTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/KnowledgeSearchControllerIngestCollectionTest.java`
- `modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpProtocolHandlerTest.java`

### C1-9

- `docs/reference/configuration/environment-variables.md`
- `docs/reference/configuration/runtime-config-ownership-matrix.md`
- `modules/app-engine/src/main/java/io/justsearch/app/engine/EngineResourcePolicy.java`
- `modules/app-engine/src/test/java/io/justsearch/app/engine/EngineResourcePolicyTest.java`
- `modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java`
- `scripts/jseval/lane-f/admission-loop.mjs`
