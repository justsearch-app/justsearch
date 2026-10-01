# F-1 / F-5 light verification and root build handoff — 2026-10-01

Tested base: a84757815 with the uncommitted lane-f-residue changes listed below. Branch codex/lane-f-residue. No Gradle, npm typecheck/test, Engine/dev stack, subagents, commit or push was run. Required compilation and Java/TS suites remain unperformed for root.

Counts: a=246, b=116, c=953, d=58 (1,373 original matching lines); 391 reviewed allowlist entries. Complete classifications and all internal rename reference sets are in [residue-dispositions-2026-10-01.md](residue-dispositions-2026-10-01.md).

| Check | Result / evidence |
| --- | --- |
| node --check scripts/ci/check-lane-f-residue.mjs | exit 0 |
| node --check scripts/ci/test-check-lane-f-residue.mjs | exit 0 |
| node --test scripts/ci/test-check-lane-f-residue.mjs | exit 0; one Node test file passed, zero failures; fixtures independently assert red unlabelled hits, green allowlisted hits, stale-entry reds, path/term precision, same-line uncovered terms, and scoped stale checks |
| node scripts/ci/check-lane-f-residue.mjs | exit 0; empty stdout in [residue-grep.txt](residue-grep.txt) |
| node scripts/codegen/gen-wire-schema-types.mjs | exit 0; 29 outputs regenerated, generated schemas byte-identical to base |
| git diff --check | exit 0 |

No unresolved implementation choice remains. gRPC metadata pruning belongs to root's dependency workflow. The build files changed only comments; no resolveAndLockAll/lockfile regeneration is required. No future skills/regeneration work is hidden behind the holder rename.

Root commands:

```text
./gradlew.bat build -PskipWebBuild=true --continue
cd modules/ui-web && npm run typecheck && npm run test:unit:run
node scripts/ci/run-ui-web-gates.mjs
```

Run the UI gate from the repository root (after returning from modules/ui-web). The regular Gradle build includes the Java tests added here; preserve its integrated result before focused reruns.

Every Java file touched (140):

- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/CommitOps.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/CommitReason.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/IndexingCoordinator.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RunningRuntime.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/RuntimeSession.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/SwapReason.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/TextQueryOps.java
- modules/adapters-lucene/src/main/java/io/justsearch/adapters/lucene/runtime/WritePathOps.java
- modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/HybridSearchIntegrationTest.java
- modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/DiagnosticChannel.java
- modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/RequiredCapability.java
- modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/Resource.java
- modules/app-agent-api/src/main/java/io/justsearch/agent/api/registry/SubCategory.java
- modules/app-agent/src/main/java/io/justsearch/agent/tools/AgentToolErrors.java
- modules/app-agent/src/main/java/io/justsearch/agent/tools/ReadDocumentTool.java
- modules/app-api/src/main/java/io/justsearch/app/api/AiInstallStatus.java
- modules/app-api/src/main/java/io/justsearch/app/api/CoreServices.java
- modules/app-api/src/main/java/io/justsearch/app/api/ExcludesService.java
- modules/app-api/src/main/java/io/justsearch/app/api/IndexingService.java
- modules/app-api/src/main/java/io/justsearch/app/api/ModeChangeListener.java
- modules/app-api/src/main/java/io/justsearch/app/api/SearchService.java
- modules/app-api/src/main/java/io/justsearch/app/api/ServiceGraph.java
- modules/app-api/src/main/java/io/justsearch/app/api/WorkerQuiescenceSnapshot.java
- modules/app-api/src/main/java/io/justsearch/app/api/WorkerServices.java
- modules/app-api/src/main/java/io/justsearch/app/api/knowledge/KnowledgeStatusView.java
- modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/ReadinessDimension.java
- modules/app-api/src/main/java/io/justsearch/app/api/runtime/RuntimeManifest.java
- modules/app-api/src/main/java/io/justsearch/app/api/status/HealthNodeView.java
- modules/app-api/src/main/java/io/justsearch/app/api/status/InferenceRuntimeView.java
- modules/app-api/src/main/java/io/justsearch/app/api/status/StatusMeta.java
- modules/app-engine/src/test/java/io/justsearch/app/engine/EngineExtractionSandboxChaosTest.java
- modules/app-launcher/src/test/java/io/justsearch/app/launcher/IndexWriterOwnershipTest.java
- modules/app-launcher/src/test/java/io/justsearch/app/launcher/LibraryResolveHashOnlyCallerPin.java
- modules/app-observability/src/main/java/io/justsearch/app/observability/health/Source.java
- modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java
- modules/app-services/src/main/java/io/justsearch/app/services/ai/install/AiInstallService.java
- modules/app-services/src/main/java/io/justsearch/app/services/ai/install/ConfigurationStage.java
- modules/app-services/src/main/java/io/justsearch/app/services/ai/install/InstallStage.java
- modules/app-services/src/main/java/io/justsearch/app/services/ai/install/StagedAcquisition.java
- modules/app-services/src/main/java/io/justsearch/app/services/ai/runtime/RuntimeActivationService.java
- modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/BootstrapDocumentService.java
- modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/GplOrchestration.java
- modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/IndexingJobsBridgeWiring.java
- modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/OrchestrationAssembly.java
- modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/BatchDocAccess.java
- modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/DocAccess.java
- modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/RAGContext.java
- modules/app-services/src/main/java/io/justsearch/app/services/gpl/GplJobCoordinator.java
- modules/app-services/src/main/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTap.java
- modules/app-services/src/main/java/io/justsearch/app/services/observability/health/ReadinessReconciliationTrigger.java
- modules/app-services/src/main/java/io/justsearch/app/services/observability/health/WorkerSnapshotTap.java
- modules/app-services/src/main/java/io/justsearch/app/services/observability/metrics/DocumentsIndexedRateMetricProducer.java
- modules/app-services/src/main/java/io/justsearch/app/services/observability/metrics/JobQueueDepthMetricProducer.java
- modules/app-services/src/main/java/io/justsearch/app/services/runtimestate/RuntimeGpuLease.java
- modules/app-services/src/main/java/io/justsearch/app/services/vdu/VduBatchProcessor.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeHttpApiAdapter.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeSearchEngine.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerBootstrap.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerConfig.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/KnowledgeServerHealthMonitor.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/RemoteIndexingJobsBridge.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/RootLifecycleOps.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/SearchRpcOps.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/WatchedRootsStore.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerFeatureCache.java
- modules/app-services/src/main/java/io/justsearch/app/services/worker/WorkerStatusMapper.java
- modules/app-services/src/test/java/io/justsearch/app/services/ai/install/AiInstallServiceStageReadinessTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ConfigurationStageTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/ai/install/StagedAcquisitionTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/bootstrap/phases/BootstrapProjectionsTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/conversation/spi/RAGContextTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/feedback/FeedbackEgressGuardrailsTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/feedback/LabelStoreRegenerationKeepsUidKeysTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/gpl/GplFetchDocumentsByteBudgetTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/observability/health/LifecycleSnapshotTapTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/runtimestate/RuntimeStatusTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/worker/CancelTokenTest.java
- modules/app-services/src/test/java/io/justsearch/app/services/worker/RpcDeadlineCategoryTest.java
- modules/configuration/src/main/java/io/justsearch/configuration/EnvRegistry.java
- modules/configuration/src/main/java/io/justsearch/configuration/resolved/ResolvedConfig.java
- modules/core-contracts/src/main/java/io/justsearch/contracts/BootContract.java
- modules/core-contracts/src/main/java/io/justsearch/contracts/BuildContract.java
- modules/dead-code-audit/src/test/java/io/justsearch/deadcode/WholeProgramDeadCodeTest.java
- modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/KnowledgeServer.java
- modules/indexer-worker/src/test/java/io/justsearch/indexerworker/IndexerWorkerGuardrailsTest.java
- modules/indexer-worker/src/test/java/io/justsearch/indexerworker/embed/EmbeddingCompatibilityBootOrderingTest.java
- modules/ipc-common/src/main/java/io/justsearch/ipc/PipelineConfigs.java
- modules/ipc-common/src/main/java/io/justsearch/ipc/logging/MdcContext.java
- modules/ort-common/src/main/java/io/justsearch/ort/NativeSessionHandle.java
- modules/reranker/src/main/java/io/justsearch/reranker/RerankSkipCause.java
- modules/reranker/src/main/java/io/justsearch/reranker/WorkerModelDiscovery.java
- modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/api/IngestionDiagnosticsContractTest.java
- modules/system-tests/src/integrationTest/java/io/justsearch/systemtests/harness/IsolatedBackendFixture.java
- modules/ui/src/main/java/io/justsearch/ui/api/ApiErrorHandler.java
- modules/ui/src/main/java/io/justsearch/ui/api/CoreApiAssembly.java
- modules/ui/src/main/java/io/justsearch/ui/api/IndexingController.java
- modules/ui/src/main/java/io/justsearch/ui/api/InferenceHandlers.java
- modules/ui/src/main/java/io/justsearch/ui/api/KnowledgeSearchController.java
- modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java
- modules/ui/src/main/java/io/justsearch/ui/api/PreviewController.java
- modules/ui/src/main/java/io/justsearch/ui/api/StatusLifecycleHandler.java
- modules/ui/src/main/java/io/justsearch/ui/api/routes/BootRoutes.java
- modules/ui/src/main/java/io/justsearch/ui/api/routes/DebugRoutes.java
- modules/ui/src/test/java/io/justsearch/ui/api/IndexingJobsSubstrateIntegrationTest.java
- modules/ui/src/test/java/io/justsearch/ui/api/MultiplexedSseWriterTest.java
- modules/ui/src/test/java/io/justsearch/ui/api/ReadinessTriggerCompositionTest.java
- modules/ui/src/test/java/io/justsearch/ui/api/StatusReadinessStalenessTest.java
- modules/ui/src/test/java/io/justsearch/ui/api/UiApiGuardrailsTest.java
- modules/ui/src/test/java/io/justsearch/ui/api/mcp/McpAnswerLegibilityTest.java
- modules/worker-core/src/main/java/io/justsearch/indexerworker/embed/EmbeddingFingerprint.java
- modules/worker-core/src/main/java/io/justsearch/indexerworker/index/IndexGenerationManager.java
- modules/worker-core/src/main/java/io/justsearch/indexerworker/metrics/OperationalMetrics.java
- modules/worker-core/src/main/java/io/justsearch/indexerworker/path/PathResolutionStore.java
- modules/worker-core/src/main/java/io/justsearch/indexerworker/queue/IndexingJobChangeFeed.java
- modules/worker-core/src/main/java/io/justsearch/indexerworker/queue/JobQueue.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/ExtractorContributionRegistry.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/IndexingLoop.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/IngestionOutcomeJournal.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/server/DefaultWorkerAppServices.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/server/EncoderBindings.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/server/WorkerAppServices.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/CitationMatchOps.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/ConfirmedDeletionMarker.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/IndexRuntimeMetricCatalog.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/PassageWindows.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/SearchOrchestrator.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerIngestService.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerMethvinWatcher.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerSearchService.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/WorkerWatcherMetricCatalog.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/services/respond/SearchResponseBuilder.java
- modules/worker-services/src/main/java/io/justsearch/indexerworker/util/ProtoConverters.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/LoopPacingPolicyTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/services/AnswerSegmentationTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchExecutorLegSetMatrixTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/services/SearchOrchestratorVirtualThreadContextRegressionTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerIngestServiceSettleIndexTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerScanOpsTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceFetchEndpointsTest.java
- modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerSearchServiceSearchPayloadTest.java

Every TypeScript file touched (10):

- modules/ui-web/src/api/contract/contractEvents.ts
- modules/ui-web/src/api/domains/indexing.ts
- modules/ui-web/src/api/generated/schema-types/status-response.test.ts
- modules/ui-web/src/shell-v0/components/StatusCard.render.test.ts
- modules/ui-web/src/shell-v0/components/StatusCard.ts
- modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.anchoring.test.ts
- modules/ui-web/src/shell-v0/demo/shell-demo.ts
- modules/ui-web/src/shell-v0/state/aiStateStore.test.ts
- modules/ui-web/src/shell-v0/state/indexingProgress.ts
- modules/ui-web/src/shell-v0/state/verdict.ts

Other source/contracts/build changes:

- build-logic/src/main/kotlin/conventions/ErrorProneConventionsPlugin.kt
- config/spotbugs/exclude-filter.xml
- contracts/wire/contract_events.proto
- contracts/wire/status.proto
- modules/app-observability/build.gradle.kts
- modules/core-contracts/build.gradle.kts
- modules/ipc-common/src/main/proto/indexing.proto
- modules/ui/build.gradle.kts
- modules/worker-services/build.gradle.kts
- scripts/ci/check-lane-f-residue.mjs
- scripts/ci/test-check-lane-f-residue.mjs
- scripts/jseval/jseval/ui_check.py
- scripts/jseval/jseval/ui_fixtures.py
