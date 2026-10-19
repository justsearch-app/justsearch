# 966 D6 - classification of app-launcher's test-only method exemptions

Evidence for tempdoc 966 D6 (S11), worktree base `325dfa5da`, 2026-10-05.

## Method

1. **What was exempt.** A scratch ArchUnit test on app-launcher's test runtime classpath (the same
   import as `UnreferencedCodeTest`: package `io.justsearch`, tests excluded) evaluated the method
   rule with the `KNOWN_UNREFERENCED` map and the name predicates (`*ForTest*`, `*ForTesting`,
   `install*`, `reset*`) switched off and recorded which exemption each violation had used.
   Result: 84 methods, 45 exempted by the map and 39 by the predicates (none by both).
   14 of the map's 59 entries exempted nothing (table 2).
2. **Production callers, bytecode.** A scratch test in `modules/dead-code-audit`, whose test
   classpath holds every production module, imported all `io.justsearch` production classes and
   listed `JavaMethod.getAccessesToSelf()` (calls and method references) for each of the 84
   methods. Result: no caller for any of them.
3. **Production callers, source.** A search of every `modules/*/src/main/**` Java/Kotlin/Groovy file
   (all modules, including `system-tests` and `test-support`) for `name(`, `::name` and `"name"`,
   with the hits read by hand for overloads, delegates and reflection. Result: no production caller
   for any of the methods. Where a production call with the same name exists, it targets a
   different overload or the delegate's target (column Note).
4. **Test callers.** The same search over test sources (`src/test`, `src/integrationTest`,
   `src/testFixtures`, and `src/main` of `system-tests` / `test-support`), keeping files that also
   name the owner class or share its package. `(pkg)` marks a same-package file that does not
   name the owner (weaker evidence); `NONE` means no test caller was found either.

Both scratch tests were deleted afterwards. The seeded store
(`modules/app-launcher/archunit_store/fbfdda09-fb1d-494e-a2a6-e593ce75d5a6`) holds exactly the
84 methods of table 1 (compared line by line after seeding).

## Counts

- Cross-module exemptions (production caller outside app-launcher's classpath): **0**;
  `CROSS_MODULE_PRODUCTION_CALLERS` is empty. The design's examples do not hold today:
  `RagContextOps.executeRetrieval` is no longer a violation (table 2), and the non-public
  `SearchTraceProjector.project` is a 3-arg test-convenience overload (production calls the 4-arg
  one). worker-services is on app-launcher's test runtime classpath (through indexer-worker).
- Test-only, frozen: **78**.
- No caller at all, test or production, frozen (dead code): **6**.
- Frozen store total: **84** = 45 former map entries + 39 former name-predicate matches.
- Stale map entries dropped: **14**.

The predicates also matched methods that have production callers (`install*` / `reset*`, for example
`MutableManagedChildRegistry.installWriter`); those are not violations, so dropping the predicates
does not affect them.

## Table 1 - exempt before, frozen now

| Method (`JavaMethod.getFullName()`) | Exempted by | Production callers found | Test callers found | Class | Note |
|---|---|---|---|---|---|
| `io.justsearch.adapters.lucene.runtime.DeferredRuntime.session()` | KNOWN_UNREFERENCED `DeferredRuntime.session` | none | LifecycleTestAccessor, LifecycleIntegrationTest | test-only, frozen |  |
| `io.justsearch.adapters.lucene.runtime.ReadOnlyRuntime.session()` | KNOWN_UNREFERENCED `ReadOnlyRuntime.session` | none | LifecycleTestAccessor | test-only, frozen |  |
| `io.justsearch.adapters.lucene.runtime.RunningRuntime.session()` | KNOWN_UNREFERENCED `RunningRuntime.session` | none | LifecycleTestAccessor, VectorSearchIntegrationTest and other adapters-lucene / worker tests | test-only, frozen |  |
| `io.justsearch.agent.AgentLoopService.forTesting(io.justsearch.app.api.OnlineAiService, io.justsearch.agent.api.registry.OperationCatalog, io.justsearch.agent.api.registry.OperationDispatcher, io.justsearch.agent.api.registry.AgentToolEmitter, io.justsearch.agent.tools.FileOperationLog, java.util.function.Function, io.justsearch.agent.AgentRunStore, io.justsearch.agent.AgentTelemetry)` | KNOWN_UNREFERENCED `AgentLoopService.forTesting` | none | AgentLoopServiceTest | test-only, frozen |  |
| `io.justsearch.agent.AgentRetryPolicy$RetryDecision.action()` | KNOWN_UNREFERENCED `RetryDecision.action` | none | AgentRetryPolicyTest | test-only, frozen |  |
| `io.justsearch.app.inference.InferenceLifecycleManager.asIntOrNull(tools.jackson.databind.JsonNode)` | KNOWN_UNREFERENCED `InferenceLifecycleManager.asIntOrNull` | none | InferenceLifecycleManagerUtilsTest | test-only, frozen | delegate; production calls InferenceHttpHelpers.asIntOrNull |
| `io.justsearch.app.inference.InferenceLifecycleManager.asPositiveInt(tools.jackson.databind.JsonNode)` | KNOWN_UNREFERENCED `InferenceLifecycleManager.asPositiveInt` | none | InferenceLifecycleManagerUtilsTest | test-only, frozen | delegate; production calls ServerPropsOps.asPositiveInt |
| `io.justsearch.app.inference.InferenceLifecycleManager.extractContextTokensFromProps(tools.jackson.databind.JsonNode)` | KNOWN_UNREFERENCED `InferenceLifecycleManager.extractContextTokensFromProps` | none | LlamaServerPropsParsingTest | test-only, frozen | delegate; production calls ServerPropsOps |
| `io.justsearch.app.inference.InferenceLifecycleManager.extractUsageFromChatChunk(tools.jackson.databind.JsonNode)` | KNOWN_UNREFERENCED `InferenceLifecycleManager.extractUsageFromChatChunk` | none | LlamaServerUsageParsingTest, OnlineModeOpsTest(pkg) | test-only, frozen | delegate; production calls OnlineModeOps |
| `io.justsearch.app.inference.InferenceLifecycleManager.handlePeriodicHealthFailure(java.lang.String, boolean)` | KNOWN_UNREFERENCED `InferenceLifecycleManager.handlePeriodicHealthFailure` | none | InferenceLifecycleManagerExternalServerTest, LlamaServerOpsCrashTelemetryTest(pkg) | test-only, frozen | delegate; production calls LlamaServerOps |
| `io.justsearch.app.inference.InferenceLifecycleManager.isUsingExternalServer()` | KNOWN_UNREFERENCED `InferenceLifecycleManager.isUsingExternalServer` | none | InferenceLifecycleManagerExternalServerTest | test-only, frozen |  |
| `io.justsearch.app.inference.InferenceLifecycleManager.startLlamaServer()` | KNOWN_UNREFERENCED `InferenceLifecycleManager.startLlamaServer` | none | InferenceLifecycleManagerApplyConfigTest, InferenceLifecycleManagerExternalServerTest, LlamaServerCandidateContextTest, ManagedLlamaAdoptionTest(pkg), VerifiedLlamaTestServer(pkg) | test-only, frozen | no-arg wrapper; production calls serverOps.startLlamaServer(request) |
| `io.justsearch.app.inference.InferenceLifecycleManager.updateFromPropsBestEffort(tools.jackson.databind.JsonNode)` | KNOWN_UNREFERENCED `InferenceLifecycleManager.updateFromPropsBestEffort` | none | InferenceLifecycleManagerPropsInsightsTest, ServerPropsOpsTest(pkg) | test-only, frozen | delegate; production calls LlamaServerOps / ServerPropsOps |
| `io.justsearch.app.services.HeadAssembly.chooseFirstNonBlank([Ljava.lang.String;)` | KNOWN_UNREFERENCED `HeadAssembly.chooseFirstNonBlank` | none | HeadAssemblyTest | test-only, frozen | test calls it via reflection; production uses BootstrapFlagResolver.chooseFirstNonBlank |
| `io.justsearch.app.services.feedback.NdjsonAppendStore.storeFile()` | KNOWN_UNREFERENCED `NdjsonAppendStore.storeFile` | none | AgentDispositionWiringTest, LabelProjectionTest(pkg) | test-only, frozen | old entry claimed a GplJobCoordinator caller; that call is to GplTrainingTripleStore.storeFile() (public, unrelated class) |
| `io.justsearch.app.services.intent.ConsentCapsuleService.liveGrantCount()` | KNOWN_UNREFERENCED `ConsentCapsuleService.liveGrantCount` | none | ConsentCapsuleServiceTest | test-only, frozen |  |
| `io.justsearch.app.services.worker.KnowledgeSearchEngine.isLambdaMartEligible(io.justsearch.app.api.knowledge.PipelineConfig, io.justsearch.app.api.gpl.RerankerService, int)` | KNOWN_UNREFERENCED `KnowledgeSearchEngine.isLambdaMartEligible` | none | KnowledgeHttpApiAdapterHarmfulCombinationsTest | test-only, frozen |  |
| `io.justsearch.app.services.worker.SearchPipelinePresets.toProtoPipelineConfig(io.justsearch.app.api.knowledge.PipelineConfig)` | KNOWN_UNREFERENCED `SearchPipelinePresets.toProtoPipelineConfig` | none | PipelineConfigPresetExpansionTest | test-only, frozen | 1-arg overload; production uses the 2-arg form |
| `io.justsearch.applauncher.LauncherEnvironment.configManager()` | KNOWN_UNREFERENCED `LauncherEnvironment.configManager` | none | LauncherEnvironmentCloseTest, SmokeDriverTest | test-only, frozen |  |
| `io.justsearch.applauncher.LauncherEnvironment.telemetry()` | KNOWN_UNREFERENCED `LauncherEnvironment.telemetry` | none | LauncherEnvironmentCloseTest, SmokeDriverTest | test-only, frozen |  |
| `io.justsearch.configuration.resolved.ConfigStore.clearGlobal()` | KNOWN_UNREFERENCED `ConfigStore.clearGlobal` | none | EngineRootIndexRecoveryTest, OnnxModelDiscoveryTest, TestResolvedConfigHelper | test-only, frozen |  |
| `io.justsearch.indexerworker.extract.PersistentExtractionSandbox.firstChildPid()` | KNOWN_UNREFERENCED `PersistentExtractionSandbox.firstChildPid` | none | PersistentExtractionSandboxTest | test-only, frozen |  |
| `io.justsearch.indexerworker.extract.PersistentExtractionSandbox.restartCount()` | KNOWN_UNREFERENCED `PersistentExtractionSandbox.restartCount` | none | PersistentExtractionSandboxTest | test-only, frozen |  |
| `io.justsearch.indexerworker.extract.PersistentExtractionSandbox.spawnCount()` | KNOWN_UNREFERENCED `PersistentExtractionSandbox.spawnCount` | none | PersistentExtractionSandboxTest | test-only, frozen |  |
| `io.justsearch.indexerworker.extract.VisualExtractionEvidence.from(java.lang.String, io.justsearch.indexerworker.extract.StructuredDocumentSummary, java.lang.String, io.justsearch.indexerworker.extract.OcrRoutingConfig, boolean)` | KNOWN_UNREFERENCED `VisualExtractionEvidence.from` | none | OcrRoutingConfigTest(pkg), PolicyDrivenTikaExtractorTest | test-only, frozen | 5-arg overload; production PolicyDrivenTikaExtractor calls the 8-arg public overload |
| `io.justsearch.indexerworker.loop.EmbeddingProviderLifecycle.unloadEmbeddingService()` | KNOWN_UNREFERENCED `EmbeddingProviderLifecycle.unloadEmbeddingService` | none | IndexingLoopUnloadTelemetryEmitTest | test-only, frozen | test calls it via reflection |
| `io.justsearch.indexerworker.loop.IndexingLoop.getExtractor()` | KNOWN_UNREFERENCED `IndexingLoop.getExtractor` | none | AdversarialCorpusIngestionTest, IndexingLoopTest | test-only, frozen |  |
| `io.justsearch.indexerworker.loop.IndexingLoop.getJournal()` | KNOWN_UNREFERENCED `IndexingLoop.getJournal` | none | AdversarialCorpusIngestionTest, IndexingLoopRestartTest, IndexingLoopTest | test-only, frozen |  |
| `io.justsearch.indexerworker.loop.IndexingLoop.getWriter()` | KNOWN_UNREFERENCED `IndexingLoop.getWriter` | none | AdversarialCorpusIngestionTest, IndexingLoopTest | test-only, frozen |  |
| `io.justsearch.indexerworker.metrics.OperationalMetrics.deregisterEncoder(java.lang.String)` | KNOWN_UNREFERENCED `OperationalMetrics.deregisterEncoder` | none | EncoderProfileAccumulatorTest | test-only, frozen |  |
| `io.justsearch.indexerworker.services.RagContextOps$ChunkRerankResult.wasReranked()` | KNOWN_UNREFERENCED `ChunkRerankResult.wasReranked` | none | NONE | no caller, frozen | old entry claimed a RagContextOps caller; none found |
| `io.justsearch.indexerworker.services.RagContextOps.searchChunksWithMeta(java.lang.String, java.util.Set, int, int, boolean, java.lang.String, io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes$RuntimeSearchFilters, java.util.List, io.justsearch.core.context.EngineContext$Urgency, io.justsearch.core.execution.EngineTaskLifetime)` | KNOWN_UNREFERENCED `RagContextOps.searchChunksWithMeta` | none | NONE | no caller, frozen | old entry: "called from executeRetrieval overload"; production calls the private overload instead |
| `io.justsearch.indexerworker.services.RootWatcherRegistry.watchedRoots()` | KNOWN_UNREFERENCED `RootWatcherRegistry.watchedRoots` | none | EngineRootRetirementTest, RootWatcherRegistryTest | test-only, frozen |  |
| `io.justsearch.indexerworker.services.SearchOrchestrator.deriveActualMode(boolean, boolean, boolean)` | KNOWN_UNREFERENCED `SearchOrchestrator.deriveActualMode` | none | SearchOrchestratorPipelineDispatchTest | test-only, frozen | delegate; production calls SearchPlanner.deriveActualMode |
| `io.justsearch.indexerworker.services.SearchOrchestrator.deriveEffectiveMode(io.justsearch.ipc.PipelineConfig)` | KNOWN_UNREFERENCED `SearchOrchestrator.deriveEffectiveMode` | none | SearchOrchestratorPipelineDispatchTest | test-only, frozen |  |
| `io.justsearch.indexerworker.services.SearchOrchestrator.modeToDefaultPipeline(io.justsearch.ipc.SearchMode)` | KNOWN_UNREFERENCED `SearchOrchestrator.modeToDefaultPipeline` | none | SearchOrchestratorPipelineDispatchTest | test-only, frozen | delegate; production calls SearchPlanner.modeToDefaultPipeline |
| `io.justsearch.indexerworker.services.respond.SearchTraceProjector.project(io.justsearch.indexerworker.services.plan.SearchDecision, io.justsearch.indexerworker.services.SearchOutcome, io.justsearch.indexerworker.services.input.SearchInputs)` | KNOWN_UNREFERENCED `SearchTraceProjector.project` | none | SearchTraceProjectorTest | test-only, frozen | 3-arg test-convenience overload; production SearchResponseBuilder calls the 4-arg overload |
| `io.justsearch.indexerworker.splade.SpladeEncoder.postProcess([[[F, [[J)` | KNOWN_UNREFERENCED `SpladeEncoder.postProcess` | none | SpladePostProcessTest | test-only, frozen |  |
| `io.justsearch.ort.NativeSessionHandle.peekCpuSession()` | KNOWN_UNREFERENCED `NativeSessionHandle.peekCpuSession` | none | NativeSessionHandleTest | test-only, frozen |  |
| `io.justsearch.telemetry.JvmRuntimeGauges.getJvmGaugeErrorCount()` | KNOWN_UNREFERENCED `JvmRuntimeGauges.getJvmGaugeErrorCount` | none | JvmRuntimeGaugesTest | test-only, frozen |  |
| `io.justsearch.ui.api.AgentController.isHeartbeatSchedulerShutdown()` | KNOWN_UNREFERENCED `AgentController.isHeartbeatSchedulerShutdown` | none | AgentControllerShutdownTest, ChatControllerHeartbeatTest | test-only, frozen |  |
| `io.justsearch.ui.api.ChatController.isHeartbeatSchedulerShutdown()` | KNOWN_UNREFERENCED `ChatController.isHeartbeatSchedulerShutdown` | none | AgentControllerShutdownTest(pkg), ChatControllerHeartbeatTest | test-only, frozen |  |
| `io.justsearch.ui.api.LocalApiServer$Builder.operationLeaseService(io.justsearch.app.api.OperationLeaseService)` | KNOWN_UNREFERENCED `Builder.operationLeaseService` | none | EngineUpgradeLifecycleTest, UpgradeLifecycleContractTest | test-only, frozen |  |
| `io.justsearch.ui.api.RouteContractPolicy.declaredSchemaFiles()` | KNOWN_UNREFERENCED `RouteContractPolicy.declaredSchemaFiles` | none | RouteContractPolicyCoverageTest, SchemaControllerTest | test-only, frozen |  |
| `io.justsearch.ui.api.RouteContractPolicy.validateSdkRoutes(java.util.Collection, java.util.Collection)` | KNOWN_UNREFERENCED `RouteContractPolicy.validateSdkRoutes` | none | SdkOpenApiProjection, SdkOpenApiProjectionTest | test-only, frozen |  |
| `io.justsearch.adapters.lucene.runtime.LuceneRuntimeBuilder.withPrebuiltComponentsForTests(io.justsearch.adapters.lucene.runtime.Components)` | name predicate ForTest | none | ComponentsInjectionTest(pkg) | test-only, frozen |  |
| `io.justsearch.app.engine.EngineRoot.liveMigrationStartCompletionForTests(java.lang.String)` | name predicate ForTest | none | EngineMigrationRestartDispatchTest, RecordedBulkEngineRestartTest | test-only, frozen |  |
| `io.justsearch.app.inference.InferenceLifecycleManager.setUsingExternalServerForTest(boolean)` | name predicate ForTest | none | NONE | no caller, frozen |  |
| `io.justsearch.app.services.ai.install.AiInstallService.knowledgeServerForTest()` | name predicate ForTest | none | AiInstallServiceLateBindTest | test-only, frozen |  |
| `io.justsearch.app.services.ai.install.AiInstallService.setFreeSpaceProbeForTest(io.justsearch.app.services.ai.install.FreeSpaceCheck$Probe)` | name predicate ForTest | none | InstallResumeAdjustedBytesTest | test-only, frozen |  |
| `io.justsearch.app.services.ai.install.AiInstallService.setNanoClockForTest(java.util.function.LongSupplier)` | name predicate ForTest | none | AiInstallServiceFunctionalStatusCacheTest, AiInstallServiceRunStatusTruthTest | test-only, frozen |  |
| `io.justsearch.app.services.ai.runtime.RuntimeActivationService.setSelfTestOverrideForTest(java.util.function.BiFunction)` | name predicate ForTest | none | RuntimeActivationServiceChatProfileTest | test-only, frozen |  |
| `io.justsearch.app.services.worker.KnowledgeServerHealthMonitor.recoveryAttemptRunningForTest()` | name predicate ForTest | none | ComponentRecoveryMonitorTest | test-only, frozen |  |
| `io.justsearch.indexerworker.embed.EmbeddingService.getCacheSizeForTesting()` | name predicate ForTest | none | EmbeddingServiceCacheTest | test-only, frozen |  |
| `io.justsearch.indexerworker.embed.EmbeddingService.putCacheEntryForTesting(java.lang.String, io.justsearch.indexerworker.embed.EmbeddingService$ChunkedEmbedding, long)` | name predicate ForTest | none | EmbeddingServiceCacheTest, EngineKnowledgeClientExecutorTest | test-only, frozen |  |
| `io.justsearch.indexerworker.embed.EmbeddingService.setLastEvictionTimeForTesting(long)` | name predicate ForTest | none | EmbeddingServiceCacheTest | test-only, frozen |  |
| `io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor.evaluateOcrAttemptForTesting(java.nio.file.Path, java.lang.String, java.lang.String, io.justsearch.indexerworker.extract.StructuredDocumentSummary, long)` | name predicate ForTest | none | ExtractionDropoutFallbackChainTest | test-only, frozen |  |
| `io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor.shouldAttemptOcrForTesting(java.nio.file.Path, java.lang.String, java.lang.String, io.justsearch.indexerworker.extract.StructuredDocumentSummary)` | name predicate ForTest | none | PolicyDrivenTikaExtractorTest | test-only, frozen |  |
| `io.justsearch.indexerworker.loop.IngestionOutcomeJournal.pendingTransitionsForTest()` | name predicate ForTest | none | IndexingLoopRestartTest(pkg), IndexingLoopTest, IngestionOutcomeJournalClaimTest | test-only, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.awaitMigrationCutoverExitForTests(long, java.util.concurrent.TimeUnit)` | name predicate ForTest | none | DocumentIdentityBootImportTest, EngineNativePointerBootMutationTest, KnowledgeServerCloseCompletionTest | test-only, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.indexGenerationManagerForTests()` | name predicate ForTest | none | DocumentIdentityBootImportTest, KnowledgeServerCloseCompletionTest, KnowledgeServerLiveMigrationTest | test-only, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.jobQueueForTests()` | name predicate ForTest | none | CommittedBootRootReconciliationTest, DocumentIdentityBootImportTest, EngineNativePointerBootMutationTest, KnowledgeServerLiveInstallerMigrationTest, KnowledgeServerRecordedIngestionTest | test-only, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.lifecycleManagerForTests()` | name predicate ForTest | none | CommittedBootRootReconciliationTest, DocumentIdentityBootImportTest | test-only, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.readOnlyOpensForTest()` | name predicate ForTest | none | ResumedMigrationMismatchBootTest | test-only, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.rebuildBrakeExhaustedForTest()` | name predicate ForTest | none | BrakeExhaustedWorkerServesReadOnlyTest, ResumedMigrationMismatchBootTest | test-only, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.releaseModelReadyLatchForTests()` | name predicate ForTest | none | DocumentIdentityBootImportTest | test-only, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.signalBusForTests()` | name predicate ForTest | none | NONE | no caller, frozen |  |
| `io.justsearch.telemetry.RrdMetricStore.curatedMetricsForTest()` | name predicate ForTest | none | RrdMetricStoreCatalogDeriveTest | test-only, frozen |  |
| `io.justsearch.ui.api.ApiErrorHandler.clearCachesForTest()` | name predicate ForTest | none | ApiErrorHandlerCachingTest | test-only, frozen |  |
| `io.justsearch.ui.api.StatusLifecycleHandler.setClockForTesting(java.util.function.LongSupplier)` | name predicate ForTest | none | IndexReadinessPublicationTest, WorkerStatusSamplerTest | test-only, frozen |  |
| `io.justsearch.app.inference.TransitionRunner.installViewForTest(io.justsearch.app.inference.InferenceRuntimeView)` | name predicate ForTest + install | none | NONE | no caller, frozen |  |
| `io.justsearch.indexerworker.server.KnowledgeServer.installMigrationBarrierForTests(io.justsearch.indexerworker.server.MigrationTransitionBarrier$Hook)` | name predicate ForTest + install | none | DocumentIdentityBootImportTest, EngineNativePointerBootMutationTest, EngineTestHarness, KnowledgeServerCloseCompletionTest | test-only, frozen |  |
| `io.justsearch.indexerworker.extract.TikaOcrRuntime.resetLanguageCacheForTests()` | name predicate ForTest + reset | none | TikaOcrRuntimeTest | test-only, frozen |  |
| `io.justsearch.applauncher.Launcher.installFactories(io.justsearch.applauncher.Launcher$EnvironmentFactory, io.justsearch.applauncher.Launcher$CommandRunnerFactory, io.justsearch.applauncher.Launcher$SmokeDriverFactory)` | name predicate install | none | LauncherDataVersionMarkerTest, LauncherEnvironmentCloseTest | test-only, frozen |  |
| `io.justsearch.applauncher.LauncherEnvironment.installFactories(io.justsearch.applauncher.LauncherEnvironment$ConfigManagerFactory, io.justsearch.applauncher.LauncherEnvironment$ProcessResourcesFactory, io.justsearch.applauncher.LauncherEnvironment$TelemetryFactory, io.justsearch.applauncher.LauncherEnvironment$AppFacadeFactory)` | name predicate install | none | LauncherDataVersionMarkerTest, LauncherEnvironmentCloseTest | test-only, frozen |  |
| `io.justsearch.applauncher.SmokeDriver.installCommandRunnerFactory(io.justsearch.applauncher.Launcher$CommandRunnerFactory)` | name predicate install | none | LauncherDataVersionMarkerTest, SmokeDriverTest | test-only, frozen |  |
| `io.justsearch.ui.api.ApiSecurityFilters.install(io.javalin.Javalin)` | name predicate install | none | ApiSecurityFiltersTest and about 20 ui HTTP tests | test-only, frozen | 1-arg overload; production LocalApiServer calls the 2-arg install |
| `io.justsearch.app.services.ai.install.TransportFaultInjector.resetWarningState()` | name predicate reset | none | TransportFaultInjectorTest | test-only, frozen |  |
| `io.justsearch.app.services.observability.rules.DwellTimeScheduler.reset(java.lang.String)` | name predicate reset | none | NONE | no caller, frozen |  |
| `io.justsearch.applauncher.Launcher.resetFactories()` | name predicate reset | none | LauncherDataVersionMarkerTest, LauncherEnvironmentCloseTest, SmokeDriverTest | test-only, frozen |  |
| `io.justsearch.applauncher.LauncherEnvironment.resetFactories()` | name predicate reset | none | LauncherDataVersionMarkerTest, LauncherEnvironmentCloseTest, SmokeDriverTest | test-only, frozen |  |
| `io.justsearch.applauncher.SmokeDriver.resetFactories()` | name predicate reset | none | LauncherDataVersionMarkerTest, LauncherEnvironmentCloseTest(pkg), SmokeDriverTest | test-only, frozen |  |
| `io.justsearch.contracts.BootContractRegistry.reset()` | name predicate reset | none | BootContractTest, ContractSamplerTest(pkg) | test-only, frozen |  |
| `io.justsearch.contracts.ContractSampler.reset()` | name predicate reset | none | BootContractTest(pkg), ContractSamplerTest | test-only, frozen |  |

## Table 2 - map entries that exempted nothing (dropped)

| Entry | Why it exempted nothing |
|---|---|
| `AgentController.shutdown` | non-public, has a caller on the classpath (not a violation) |
| `AgentSession.budgetGateHeld` | non-public, has a caller on the classpath (not a violation) |
| `AgentSession.contextGateHeld` | non-public, has a caller on the classpath (not a violation) |
| `ConsentCapsuleService.liveNonceCount` | no such method any more |
| `EngineConversationContext.mutableMessages` | no such method any more |
| `ExcludeMatcher.isExcluded` | no such method any more |
| `IndexingLoop.getBackfillScheduler` | no such method any more |
| `IndexingLoop.getEmbeddingLifecycle` | method is public now (outside the rule) |
| `InferenceLifecycleManager.formatContextAsNumberedPassages` | method is public now (outside the rule) |
| `LauncherEnvironment.HeadAssembly` | non-public method `HeadAssembly()`, has a caller on the classpath (not a violation) |
| `RagContextOps.executeRetrieval` | non-public, has a caller on the classpath (not a violation) |
| `SseWriter.writeSseComment` | no such method any more |
| `SyncOps.getScheduler` | no such method any more |
| `WritePathOps.readModifyWriteBatch` | non-public, every overload has a caller on the classpath (not a violation) |
