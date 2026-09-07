/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.AgentRunStore;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.ServiceGraph;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.CapabilityGraph;
import io.justsearch.app.services.bootstrap.OrchestrationHandles;
import io.justsearch.app.services.gpl.GplJobCoordinator;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.search.SearchServiceImpl;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.search.SearchPort;
import io.justsearch.telemetry.Telemetry;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import io.grpc.Server;

/**
 * Tempdoc 519 §4 Phase 5 — orchestration. Composes AgentLoopWiring + GplOrchestration +
 * LambdaMartTraining + CapabilityHealthBridge + initial ServiceGraph assembly into one phase.
 * Outputs the orchestration handles + initial ServiceGraph + the GPL/LambdaMART artifacts the
 * bootstrap holds for late-bind teardown.
 *
 * <p>{@code agentRootPaths} is supplied by the bootstrap because it captures
 * {@code this.services.worker().indexing()} which only exists after the held ServiceGraph
 * is set. The supplier is invoked at agent-run-time (late).
 *
 * <p>{@code startLambdaMartTrainingAsync} is supplied as a Runnable because it captures
 * mutable bootstrap state (gplJobCoordinator + lambdaMartReranker + lambdaMartModelFile).
 */
public final class OrchestrationPhase {

  private OrchestrationPhase() {}

  /** Bundled inputs. */
  public record Input(
      Path dataDir,
      Telemetry telemetry,
      Supplier<SearchPort> searchPortSupplier,
      InferenceLifecycleManager inferenceManager,
      OnlineAiService onlineAiService,
      Supplier<KnowledgeClient> knowledgeClientSupplier,
      KnowledgeHttpApiAdapter agentSearchAdapter,
      LambdaMartReranker lambdaMartReranker,
      IndexingService indexingService,
      DocumentService documentService,
      io.justsearch.app.api.ModeChangeListener gpuBroadcastListener,
      SubstratePhase.Output substrateOut,
      CapabilityGraph capabilities,
      Server infraHealthGrpcServer,
      Function<String, String> operationMessageResolver,
      FileOperationLog fileOperationLog,
      AgentRunStore agentRunStore,
      Supplier<List<String>> agentRootPaths,
      Runnable startLambdaMartTrainingAsync,
      io.justsearch.app.api.ExcludesService excludes,
      io.justsearch.app.api.SettingsService settings,
      io.justsearch.app.api.PolicyService policy,
      io.justsearch.app.api.DiagnosticsService diagnostics,
      io.justsearch.app.api.BrainRuntimeService brainRuntime,
      io.justsearch.app.api.RuntimeVariantService runtimeVariant,
      io.justsearch.app.api.PackImportService packImport,
      io.justsearch.app.api.BrainInstallService brainInstall,
      // Tempdoc 737 Phase 1 — closed alongside the inference manager on teardown (nullable).
      io.justsearch.app.services.runtimestate.RuntimeReconciler runtimeReconciler,
      // Tempdoc 778 — the AUTHORED feedback-store cipher, so LambdaMART real-label rebuild reads the
      // sealed disposition/snapshot streams with the right key (passthrough when encryption is off).
      io.justsearch.agent.api.encryption.StoreCipher feedbackCipher) {}

  /** Bundled outputs. */
  public record Output(
      AgentService agentService,
      ServiceGraph initialServices,
      OrchestrationHandles orchestrationHandles,
      GplJobCoordinator gplJobCoordinator,
      Thread gplAutoTriggerThread,
      Path gplSnapshotFile,
      Path lambdaMartModelFile) {}

  /**
   * Tempdoc 541 §5.3 + fix-pass Tier 5: sealed-sum entry. Returns {@link
   * io.justsearch.app.services.bootstrap.PhaseOutcome.Degraded} when LambdaMART model is
   * absent (an explicit non-fatal degraded state — orchestration still works without
   * reranking); {@link io.justsearch.app.services.bootstrap.PhaseOutcome.Failed} on
   * construction throw; Ready otherwise.
   */
  public static io.justsearch.app.services.bootstrap.PhaseOutcome<Output> runWithOutcome(Input in) {
    try {
      Output out = runInternal(in);
      if (out.lambdaMartModelFile() == null) {
        return new io.justsearch.app.services.bootstrap.PhaseOutcome.Degraded<>(
            out, java.util.Set.of("lambdamart.not_configured"));
      }
      return new io.justsearch.app.services.bootstrap.PhaseOutcome.Ready<>(out);
    } catch (RuntimeException e) {
      return io.justsearch.app.services.bootstrap.PhaseOutcome.Failed.of(e);
    }
  }

  /**
   * §12.F: internal body (formerly public {@code run(Input)}). Visibility narrowed to
   * private; the single entry point is {@link #runWithOutcome(Input)}.
   */
  private static Output runInternal(Input in) {
    // CapabilityHealthBridge — push capability transitions to condition store.
    CapabilityHealthBridge.wireListeners(
        in.capabilities().worker(),
        in.capabilities().inference(),
        in.substrateOut().healthOut().conditionStore(),
        in.substrateOut().healthOut().healthEventChangeRegistry(),
        in.substrateOut().healthOut().headSource(),
        in.substrateOut().healthOut().occurrenceLog());

    // Tempdoc 876 §B.2a: the same transitions also re-run the readiness snapshot, so the taps that
    // own index.unavailable et al. reconcile on an event rather than only on GET /api/status. The
    // thunk itself is attached later, by CoreApiAssembly; until then request() is a no-op and
    // attach() self-seeds.
    in.substrateOut()
        .healthOut()
        .readinessReconciliationTrigger()
        .wireTo(in.capabilities().worker(), in.capabilities().inference());

    // Tempdoc 561 P-D: a read-only previewer over the ONE intent-gate authority — the backend
    // ISSUANCE policy. The agent loop's pending-approval event carries the GateBehavior the backend
    // computes from (risk × the user's autonomy dial), so the FE OBEYS it (auto-approve iff AUTO)
    // rather than re-deriving from risk (the collapsed second authority). Null evaluator (test
    // wiring) → null previewer → FE falls back to its dial-derived explanation.
    var gateEvaluator = in.substrateOut().operationOut().intentGateEvaluator();
    io.justsearch.agent.api.registry.IntentPreviewer intentPreviewer =
        gateEvaluator == null ? null : gateEvaluator::agentGate;

    // AgentLoopService construction.
    AgentService agentService =
        AgentLoopWiring.wire(
            in.inferenceManager() != null,
            in.onlineAiService(),
            in.substrateOut().agentToolsCatalog(),
            in.substrateOut().operationOut().operationExecutor(),
            in.operationMessageResolver(),
            in.fileOperationLog(),
            in.agentRootPaths(),
            in.agentRunStore(),
            in.telemetry(),
            in.substrateOut().healthOut().headHealthEventsEmitter(),
            () ->
                BootstrapProjections.renderConditionContext(
                    in.substrateOut().healthOut().conditionStore()),
            in.substrateOut().operationOut().backendIntentRouter(),
            in.substrateOut().operationOut().consentCapsuleService(),
            intentPreviewer,
            // Tempdoc 550 Preview face: the agent-tool emitter filters by evaluated availability
            // over the live condition store (the same Preview evaluation the preview endpoint uses).
            new io.justsearch.app.services.registry.preview.ConditionAvailabilityProbe(
                    in.substrateOut().healthOut().conditionStore())
                .asPredicate(),
            // Tempdoc 565 §3.A: back the answer↔source citation matcher with the document service.
            in.documentService());

    // Initial ServiceGraph (LateBoundServices = null at this point).
    io.justsearch.app.api.SearchService initialSearch =
        in.searchPortSupplier() != null ? new SearchServiceImpl(in.searchPortSupplier()) : null;
    ServiceGraph initialServices =
        ServiceGraphAssembler.assemble(
            agentService,
            in.onlineAiService(),
            initialSearch,
            in.indexingService(),
            in.documentService(),
            in.excludes(),
            in.settings(),
            in.policy(),
            in.diagnostics(),
            in.brainRuntime(),
            in.runtimeVariant(),
            in.packImport(),
            in.brainInstall());

    // GPL training + auto-trigger.
    var gplWired =
        GplOrchestration.wire(
            in.dataDir(),
            in.knowledgeClientSupplier(),
            in.onlineAiService(),
            in.agentSearchAdapter(),
            () -> {
              if (ConfigStore.global().get().search().lambdamartEnabled()) {
                in.startLambdaMartTrainingAsync().run();
              }
            });

    // LambdaMART model load (retrains async if model file missing).
    Path lambdaMartModelFile =
        LambdaMartTraining.loadOrTrain(
            in.dataDir(),
            in.lambdaMartReranker(),
            ConfigStore.global().get().search().lambdamartEnabled(),
            in.feedbackCipher());

    // OrchestrationHandles — LIFO teardown bundle.
    OrchestrationHandles orchestrationHandles =
        OrchestrationAssembly.build(
            gplWired.autoTriggerThread(),
            in.lambdaMartReranker(),
            in.substrateOut().metricsOut() == null
                ? null
                : in.substrateOut().metricsOut().jobQueueDepthMetricProducer(),
            in.substrateOut().metricsOut() == null
                ? null
                : in.substrateOut().metricsOut().documentsIndexedRateMetricProducer(),
            in.substrateOut().metricsOut() == null
                ? null
                : in.substrateOut().metricsOut().gpuUtilizationMetricProducer(),
            in.substrateOut().metricsOut() == null
                ? null
                : in.substrateOut().metricsOut().gpuMemoryUtilizationMetricProducer(),
            in.infraHealthGrpcServer(),
            in.inferenceManager(),
            in.gpuBroadcastListener(),
            in.runtimeReconciler(),
            initialServices.worker().indexing(),
            initialServices.worker().documents(),
            in.substrateOut().resourceOut() == null
                ? null
                : in.substrateOut().resourceOut().diagnosticChannelAppenderInstaller(),
            in.substrateOut().indexingJobsBridgeRegistrySubscription(),
            in.agentSearchAdapter(),
            null,
            null);

    return new Output(
        agentService,
        initialServices,
        orchestrationHandles,
        gplWired.coordinator(),
        gplWired.autoTriggerThread(),
        gplWired.snapshotFile(),
        lambdaMartModelFile);
  }
}
