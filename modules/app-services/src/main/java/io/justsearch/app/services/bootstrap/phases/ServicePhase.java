/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.tools.BrowseTool;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.agent.tools.FileOperationsTool;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.DebugStateProvider;
import io.justsearch.app.api.DiagnosticsService;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.PackImportService;
import io.justsearch.app.api.PolicyService;
import io.justsearch.app.api.RuntimeVariantService;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.StatusSnapshotProvider;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.ai.pack.AiPackImportService;
import io.justsearch.app.services.ai.pack.PackAllowlistService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.services.bootstrap.BootstrapLateBindings;
import io.justsearch.app.services.braininstall.BrainInstallServiceImpl;
import io.justsearch.app.services.brainruntime.BrainRuntimeServiceImpl;
import io.justsearch.app.services.diagnostics.DiagnosticsServiceImpl;
import io.justsearch.app.services.excludes.ExcludesServiceImpl;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.packimport.PackImportServiceImpl;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.policy.PolicyServiceImpl;
import io.justsearch.app.services.runtimestate.RuntimeGpuLease;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.runtimevariant.RuntimeVariantServiceImpl;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WorkerFeatureCache;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.telemetry.Telemetry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Tempdoc 519 §4 Phase 3 — service construction. After §31 Phase 3, this phase constructs ALL
 * production services (the prerequisites + the 7 controller-services that previously lived in
 * LateBoundServices). LocalApiServer no longer constructs services; it only constructs
 * controllers that consume the services from the ServiceGraph.
 *
 * <p>3 bindings have controller back-refs that don't resolve until LocalApiServer constructs
 * its controllers: SettingsController::resetToDefaults, DebugStateController (as
 * DebugStateProvider SPI), StatusLifecycleHandler (as StatusSnapshotProvider SPI). These are
 * threaded via the {@link BootstrapLateBindings} holder — ServicePhase constructs services with
 * supplier/callable wrappers that read from the holder at use-time; LocalApiServer publishes
 * the concrete refs into the holder after constructing the controllers.
 */
public final class ServicePhase {

  private ServicePhase() {}

  /** Bundled inputs (record keeps the parameter surface manageable). */
  public record Input(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      io.justsearch.app.api.EngineAdmissionService engineAdmission,
      KnowledgeServerBootstrap knowledgeServer,
      KnowledgeClient knowledgeClient,
      IndexingService indexingService,
      Supplier<IndexingService> indexingServiceSupplier,
      DocumentService documentService,
      LambdaMartReranker lambdaMartReranker,
      Telemetry telemetry,
      java.nio.file.Path dataDir,
      InferenceLifecycleManager inferenceManager,
      InferenceCapability inferenceCapability,
      UiSettingsStore settingsStore,
      BootstrapLateBindings lateBindings,
      // Tempdoc 672: live supplier for the VDU offline coordinator, mirroring
      // indexingServiceSupplier — the Worker client is null at bootstrap (async connect) and
      // must be re-read at use-time, not captured by value.
      Supplier<KnowledgeClient> knowledgeClientSupplier,
      // Tempdoc 672 follow-up: live supplier for the Head's own activity/energy signals (used to
      // abort an in-progress VDU batch if the user becomes active mid-run) — same live-reference
      // rationale as knowledgeClientSupplier above.
      Supplier<KnowledgeServerBootstrap> knowledgeServerBootstrapSupplier,
      OperationLeaseService operationLeases) {}

  /**
   * Inference-manager teardown handles (tempdoc 737 Phase 1). Bundles the GPU-broadcast listener
   * and the single-writer runtime reconciler — both closed together with the manager on teardown —
   * into one Output component, keeping the composition-root god-record ceiling (CompositionRoot
   * GuardrailsTest 4a) flat rather than growing the Output record.
   */
  public record InferenceRuntimeHandles(
      io.justsearch.app.api.ModeChangeListener gpuBroadcastListener,
      RuntimeReconciler runtimeReconciler,
      // Tempdoc 737 §12b: the spec writer, exposed so the core.set-chat-enabled /
      // core.switch-inference-mode(alias) operation handlers can record the chat-enabled intent
      // through the one authority (write via this store, then reconciler.specChanged()). Nullable
      // when inference is not configured.
      RuntimeSpecStore runtimeSpecStore) {}

  /** Bundled output — all services every downstream phase consumes. */
  public record Output(
      InferenceLifecycleManager inferenceManager,
      OnlineAiService onlineAiService,
      InferenceRuntimeHandles inferenceRuntimeHandles,
      OfflineCoordinator offlineCoordinator,
      /**
       * Tempdoc 868 §B.2 — the agent-tool bundle, carried as {@link AgentToolFactory.Output} itself
       * rather than as its six members spread across this record. Same move as {@link
       * InferenceRuntimeHandles} above and for the same reason: the god-record ceiling
       * (CompositionRootGuardrailsTest 4a, MAX_OUTPUT_FIELDS) was already reached at 26 components,
       * and the six were literally that record's fields re-listed. Bundling keeps the ceiling flat
       * while {@code readDocumentTool} — the bundle's seventh COMPONENT and fifth tool (the other
       * two components are the adapter and the file-operation log) — joins it.
       */
      AgentToolFactory.Output agentTools,
      WorkerFeatureCache workerFeatureCache,
      // §31 Phase 3: 7 controller-services + 1 Worker-dependent ExcludesService.
      ExcludesService excludes,
      EnterprisePolicyService enterprisePolicy,
      SettingsService settings,
      DiagnosticsService diagnostics,
      BrainInstallService brainInstall,
      BrainRuntimeService brainRuntime,
      RuntimeVariantService runtimeVariant,
      PackImportService packImport,
      PolicyService policy,
      // Helpers exposed because LocalApiServer's controllers consume them directly.
      AiInstallService aiInstallHelper,
      AiPackImportService aiPackImportHelper,
      RuntimeActivationService runtimeActivationHelper,
      PackAllowlistService packAllowlistService,
      GpuCapabilitiesService gpuCapabilitiesService,
      // Tempdoc 542 §B Layer 3 — op-lease SPI (no-op when not running under dev-runner).
      OperationLeaseService operationLeaseService) {}

  /**
   * Tempdoc 541 §5.3 + fix-pass Tier 5: sealed-sum entry point. Returns {@link
   * PhaseOutcome.Degraded} with reason {@code "inference.not_configured"} when ILM is absent
   * (lite-mode / AI-disabled paths); {@link PhaseOutcome.Failed} if construction throws;
   * {@link PhaseOutcome.Ready} otherwise.
   */
  public static io.justsearch.app.services.bootstrap.PhaseOutcome<Output> runWithOutcome(Input in) {
    try {
      Output out = runInternal(in);
      if (in.inferenceManager() == null) {
        return new io.justsearch.app.services.bootstrap.PhaseOutcome.Degraded<>(
            out, java.util.Set.of("inference.not_configured"));
      }
      return new io.justsearch.app.services.bootstrap.PhaseOutcome.Ready<>(out);
    } catch (RuntimeException e) {
      return io.justsearch.app.services.bootstrap.PhaseOutcome.Failed.of(e);
    }
  }

  /**
   * §12.F: internal body (formerly public {@code run(Input)}). Visibility narrowed to
   * private; the single entry point is {@link #runWithOutcome(Input)}. No external callers
   * remain since the Tier-5 sealed-sum migration moved every HeadAssembly call site to the
   * sealed-sum entry.
   */
  private static Output runInternal(Input in) {
    OnlineAiService onlineAiService;
    io.justsearch.app.api.ModeChangeListener gpuListener = null;
    OfflineCoordinator offlineCoordinator = null;
    RuntimeReconciler runtimeReconciler = null;
    RuntimeSpecStore runtimeSpecStore = null;
    // §31 Phase 1.A: EnterprisePolicyService impl in app-services. Tempdoc 737: constructed up-front
    // (moved from below) so the runtime reconciler can read the online-AI policy ceiling.
    EnterprisePolicyService enterprisePolicy = new EnterprisePolicyServiceImpl();
    if (in.inferenceManager() != null) {
      onlineAiService = new OnlineAiServiceImpl(in.engineAdmission(), in.inferenceManager());
      gpuListener = InferenceWiring.wireGpuStatusBroadcast(in.inferenceManager(), in.knowledgeServer());
      // Tempdoc 672 follow-up: composed once here and threaded down as a single BooleanSupplier —
      // VduBatchProcessor doesn't need to know about KnowledgeServerBootstrap/EnergyState itself,
      // only "should I stop now". Deliberately does NOT include inferenceManager.isOnline() — see
      // VduPacingPolicy.shouldInterrupt's javadoc: the LLM is legitimately Online for the whole
      // batch (this batch itself put it there), so that signal would self-interrupt immediately.
      java.util.function.BooleanSupplier shouldInterruptVduBatch =
          () -> {
            var ks = in.knowledgeServerBootstrapSupplier().get();
            long now = System.currentTimeMillis();
            long msSinceActivity = ks != null ? ks.msSinceLastUserActivity(now) : Long.MAX_VALUE;
            boolean energyReduced = ks != null && ks.energyState().reduced();
            return io.justsearch.app.services.vdu.VduPacingPolicy.shouldInterrupt(
                msSinceActivity, energyReduced);
          };
      // Tempdoc 737 Phase 1/2: the single-writer runtime authority. Constructed BEFORE the offline
      // coordinator (which now routes procedure-scoped engine control through it) and pre-start so
      // its mode listener attaches (mirror-initial-then-forward) before the first boot convergence.
      // The env autostart flag seeds the persisted spec (item 1); the reconciler then converges the
      // engine toward spec — replacing the former direct InferenceWiring.tryStartOnlineMode switch.
      runtimeSpecStore = new RuntimeSpecStore(in.settingsStore());
      RuntimeGpuLease runtimeGpuLease = new RuntimeGpuLease();
      InferenceLifecycleManager manager = in.inferenceManager();
      runtimeReconciler =
          new RuntimeReconciler(
              manager, // OnlineAiLifecycleControl (ILM implements it)
              manager::getCurrentMode,
              () -> manager.view().usingExternalLlamaServer(),
              manager::detachExternalServer, // DetachAction — throws ModeTransitionException
              enterprisePolicy,
              runtimeSpecStore,
              runtimeGpuLease,
              // Tempdoc 737 (task 5): reason-bearing switches — reconciler/procedure transitions
              // carry AUTO_START/VDU_* reasons into TransitionRunner.run instead of USER_SWITCH.
              // Wired here at the composition root because the app-api OnlineAiLifecycleControl
              // interface cannot reference the app-inference TransitionReason type.
              manager::switchToOnlineMode,
              manager::switchToIndexingMode);

      offlineCoordinator =
          OfflineCoordinatorBuilder.build(
              in.inferenceManager(),
              runtimeReconciler,
              onlineAiService,
              in.knowledgeClientSupplier(),
              in.telemetry(),
              shouldInterruptVduBatch);
      InferenceCapabilityWiring.attachInferenceModeListener(
          in.inferenceManager(), in.inferenceCapability(), runtimeSpecStore, runtimeReconciler);

      runtimeReconciler.start();
      InferenceWiring.seedAutostartSpec(runtimeSpecStore);
      runtimeReconciler.requestBootConvergence();
    } else {
      onlineAiService = OnlineAiService.unavailable();
    }

    // §31 Phase 3: GpuCapabilitiesService constructed here (was in LocalApiServer). No deps.
    GpuCapabilitiesService gpuCapabilitiesService = new GpuCapabilitiesService();

    // §31 Phase 3: offlineProcessingTrigger derived from offlineCoordinator (computed above).
    Runnable offlineProcessingTrigger =
        offlineCoordinator != null ? offlineCoordinator::startOfflineProcessing : null;

    AgentToolFactory.Output agentTools =
        AgentToolFactory.build(
            in.dataDir(),
            in.knowledgeServer(),
            in.knowledgeClient(),
            in.indexingService(),
            onlineAiService,
            in.lambdaMartReranker(),
            in.documentService());

    // §31 Step 1.1: ExcludesService constructed via supplier-aware IndexingService.
    ExcludesService excludes = new ExcludesServiceImpl(in.indexingServiceSupplier());

    // §31 Phase 1.B-D: helper impls in app-services.
    AiInstallService aiInstallHelper =
        new AiInstallService(
            onlineAiService,
            in.settingsStore(),
            in.knowledgeServer(),
            enterprisePolicy,
            // Tempdoc 737 fix pack (fix 3): the post-install smoke test brackets its engine use in an
            // INSTALL_SMOKE_TEST procedure via this reconciler (null in the no-inference branch).
            runtimeReconciler);
    PackAllowlistService packAllowlistService = new PackAllowlistService();
    AiPackImportService aiPackImportHelper =
        new AiPackImportService(
            onlineAiService,
            in.settingsStore(),
            in.knowledgeServer(),
            enterprisePolicy,
            packAllowlistService);
    // Tempdoc 672 follow-up: live supplier, not a value captured at bootstrap (client is null then,
    // async Worker connect) — mirrors the same fix already shipped for the VDU offline coordinator.
    WorkerFeatureCache workerFeatureCache =
        () -> {
          KnowledgeClient client = in.knowledgeClientSupplier().get();
          return client != null ? client.getLastKnownOnnxModels() : List.of();
        };
    RuntimeActivationService runtimeActivationHelper =
        new RuntimeActivationService(
            in.executors(),
            onlineAiService,
            in.settingsStore(),
            gpuCapabilitiesService,
            enterprisePolicy,
            workerFeatureCache,
            in.inferenceCapability(),
            aiInstallHelper,
            // Tempdoc 737 fix pack (fix 2): brackets the activation engine-online + intent-write
            // window in an ACTIVATION procedure and nudges specChanged (null in the no-inference
            // branch).
            runtimeReconciler);
    // Tempdoc 805 G.3: observed ONNX execution provider beside the intent fields on
    // /api/ai/runtime/status. Same live-supplier shape as workerFeatureCache above — the RPC client
    // is null at bootstrap, so it must not be captured by value.
    runtimeActivationHelper.setEncoderRuntimeCache(
        new io.justsearch.app.services.observability.WorkerEncoderRuntimeCache(
            in.knowledgeClientSupplier()));
    // Tempdoc 842 §2.5: the REALIZED chat identity beside the requested runtime pointers on
    // /api/ai/runtime/status. A supplier, not a captured value — the identity changes with every
    // engine restart and profile switch, and is null whenever the engine is down.
    runtimeActivationHelper.setRealizedChatIdentitySource(
        () -> BootstrapProjections.projectRealizedChatIdentity(in.inferenceManager()));
    // Tempdoc 824 §3.3c: the install status reconciles its bookkeeping ("a file is missing")
    // against what the runtime observes ("the capability is running"). Bound here, after the
    // activation service exists — it takes aiInstallHelper as a constructor argument, so the
    // dependency can only run in this direction. A method reference, not a captured value: the
    // observation changes with every activation and must be read at status time.
    aiInstallHelper.setFunctionalStatusSource(runtimeActivationHelper::functionalStatusByPackage);

    // §31 Phase 3: 7 controller-services constructed here.
    // SettingsService: callable wraps the late-bound resetFn (set by LocalApiServer after
    // SettingsController exists).
    Callable<Map<String, Object>> deferredResetFn =
        () -> {
          Callable<Map<String, Object>> resetFn = in.lateBindings().settingsResetFn();
          if (resetFn == null) {
            throw new IllegalStateException(
                "Settings reset callback not yet bound (LocalApiServer must publish after"
                    + " constructing SettingsController)");
          }
          return resetFn.call();
        };
    SettingsService settings = new SettingsServiceImpl(deferredResetFn);

    // DiagnosticsService: SPI suppliers read from the late-bindings holder.
    Supplier<DebugStateProvider> debugProviderSupplier = in.lateBindings()::debugStateProvider;
    Supplier<StatusSnapshotProvider> statusProviderSupplier =
        in.lateBindings()::statusSnapshotProvider;
    DiagnosticsService diagnostics =
        new DiagnosticsServiceImpl(
            enterprisePolicy,
            gpuCapabilitiesService,
            debugProviderSupplier,
            statusProviderSupplier);

    // One precomposed process-local admission owner; only its dev-runner file projection is optional.
    OperationLeaseService operationLeaseService = java.util.Objects.requireNonNull(in.operationLeases());

    // Tempdoc 617: both services run their work on background threads that outlive the HTTP
    // request, so the request-scoped mutation lease is released while multi-GB asset writes are
    // still in flight. Late-bound here because the lease service is created after they are built.
    aiInstallHelper.setOperationLeaseService(operationLeaseService);
    aiPackImportHelper.setOperationLeaseService(operationLeaseService);
    runtimeActivationHelper.setOperationLeaseService(operationLeaseService);

    BrainInstallService brainInstall = new BrainInstallServiceImpl(aiInstallHelper);
    BrainRuntimeService brainRuntime =
        new BrainRuntimeServiceImpl(
            onlineAiService,
            in.settingsStore(),
            enterprisePolicy,
            offlineProcessingTrigger,
            // Tempdoc 737 fix pack (fix 4): switchInferenceMode records the chat-enabled intent
            // through the one authority (spec write + reconciler nudge); null in the no-inference
            // branch keeps the graceful-degradation IllegalStateException path.
            runtimeSpecStore,
            runtimeReconciler);
    // Tempdoc 737 (task 3): RuntimeVariantServiceImpl no longer takes its own
    // EnterprisePolicyService — policy enforcement is now solely on runtimeActivationHelper.
    RuntimeVariantService runtimeVariant = new RuntimeVariantServiceImpl(runtimeActivationHelper);
    PackImportService packImport = new PackImportServiceImpl(aiPackImportHelper);
    PolicyService policy = new PolicyServiceImpl(enterprisePolicy);

    return new Output(
        in.inferenceManager(),
        onlineAiService,
        new InferenceRuntimeHandles(gpuListener, runtimeReconciler, runtimeSpecStore),
        offlineCoordinator,
        agentTools,
        workerFeatureCache,
        excludes,
        enterprisePolicy,
        settings,
        diagnostics,
        brainInstall,
        brainRuntime,
        runtimeVariant,
        packImport,
        policy,
        aiInstallHelper,
        aiPackImportHelper,
        runtimeActivationHelper,
        packAllowlistService,
        gpuCapabilitiesService,
        operationLeaseService);
  }
}
