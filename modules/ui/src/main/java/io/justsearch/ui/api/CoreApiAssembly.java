/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.ai.pack.AiPackImportService;
import io.justsearch.app.services.ai.pack.PackAllowlistService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.app.services.observability.HeadGpuMetricCatalog;
import io.justsearch.app.services.observability.HeadHttpInflightMetricCatalog;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.telemetry.JvmRuntimeGauges;
import io.justsearch.telemetry.Telemetry;
import java.time.Instant;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Tempdoc 583 Stage 5: the core HTTP-controller cohort + status/observability wiring, lifted out of
 * {@link LocalApiServer}'s constructor (see tempdoc 583 section B.7).
 *
 * <p>This is the ~280-LOC run of controller construction the constructor accumulated: preview /
 * chunk-info / debug-state / inference / status-lifecycle (plus its GPU-saturation, worker-metrics,
 * rule-engine and tap wiring) / the AI install/pack/runtime + policy/diagnostics/effective-config /
 * session-policies / encoder / log-level / time-series controllers, the saturation gauges, and the
 * knowledge-search controller. Behaviour is identical: the block moved verbatim, with the
 * {@code this.<field>} writes turned into the returned {@link Result} and the handful of
 * LocalApiServer cross-references (telemetry / apiCatalog / settingsController / HeadAssemblyRef,
 * plus the getPort / resolveLlamaServerPort / getCachedGpuSnapshot / inflight-gauge hooks) turned
 * into parameters. The lateBindKnowledgeServer / stop paths in LocalApiServer mutate the returned
 * fields exactly as before; only the construction site moved.
 */
final class CoreApiAssembly {
  private CoreApiAssembly() {}

  /** The controllers + handlers LocalApiServer holds as fields after assembly. */
  record Result(
      ChunkInfoController chunkInfoController,
      PreviewController previewController,
      DebugStateController debugStateController,
      InferenceHandlers inferenceHandlers,
      StatusLifecycleHandler statusLifecycleHandler,
      io.justsearch.ui.observability.GpuSaturationMonitor gpuSaturationMonitor,
      io.justsearch.ui.observability.GpuSaturationSampler gpuSaturationSampler,
      io.justsearch.app.services.vdu.VduOfflineTriggerSampler vduOfflineTriggerSampler,
      AiInstallController aiInstallController,
      // The concrete install helper, not just its controller: the worker reference late-binds into
      // the SERVICE, and routing that through the HTTP controller made a composition-root concern a
      // controller method and forced the controller to hold the impl type instead of the app-api
      // interface.
      io.justsearch.app.services.ai.install.AiInstallService aiInstallService,
      OpenAiCompatController openAiCompatController,
      PolicyController policyController,
      DiagnosticsController diagnosticsController,
      EffectiveConfigController effectiveConfigController,
      SessionPoliciesController sessionPoliciesController,
      EncoderRuntimeController encoderRuntimeController,
      LogLevelController logLevelController,
      TimeSeriesController timeSeriesController,
      AiRuntimeController aiRuntimeController,
      AiPackController aiPackController,
      AiModelsController aiModelsController,
      HeadHttpInflightMetricCatalog inflightCatalog,
      HeadGpuMetricCatalog gpuCatalog,
      KnowledgeSearchController knowledgeSearchController) {}

  static Result assemble(
      LocalApiServer.Builder b,
      Telemetry telemetry,
      HeadApiMetricCatalog apiCatalog,
      GpuCapabilitiesService gpuCapabilitiesService,
      EventBuffer eventBuffer,
      Instant startTime,
      HeadAssembly headAssemblyRef,
      SettingsController settingsController,
      Supplier<Integer> portSupplier,
      IntSupplier llamaServerPortSupplier,
      Supplier<GpuCapabilities> gpuSnapshotSupplier,
      Supplier<Double> inflightSupplier) {
    // §31 + 526 F2: DocumentService late-binds via HeadAssembly.connectKnowledgeServer;
    // supplier-capture lets controllers see the swap without setters.
    Supplier<DocumentService> docSvcSupplier =
        b.HeadAssembly != null
            ? () -> b.HeadAssembly.workers().documents() != null
                ? b.HeadAssembly.workers().documents()
                : DocumentService.unavailable()
            : DocumentService::unavailable;
    ChunkInfoController chunkInfoController = new ChunkInfoController(docSvcSupplier, telemetry);
    // Foreground responsiveness: preview counts as user activity; recorded for the Head's own
    // idle checks (VDU pacing). The Worker is not signalled — it paces on its own foreground gauge.
    PreviewController previewController =
        b.knowledgeServer == null
            ? new PreviewController(
                docSvcSupplier,
                java.time.Duration.ofSeconds(5),
                null,
                telemetry,
                apiCatalog)
            : new PreviewController(
                docSvcSupplier,
                java.time.Duration.ofSeconds(5),
                () -> {
                  try {
                    b.knowledgeServer.recordUserActivity();
                  } catch (Exception ignored) {
                    // best-effort
                  }
                },
                telemetry,
                apiCatalog);
    // Tempdoc 412 Phase 3: engineMonitorSupplier removed (Phase 0 finding 2: EngineMonitor was
    // dead code; setters never called in production). Inference status now flows through
    // HeadAssembly.inferenceSnapshot() in StatusLifecycleHandler.
    DebugStateController debugStateController = new DebugStateController(b.configRoot, b.knowledgeServer, eventBuffer, telemetry);
    if (b.gplJobCoordinator != null) {
      debugStateController.setGplJobCoordinator(b.gplJobCoordinator);
    }
    if (b.gplEvalSnapshotSupplier != null) {
      debugStateController.setGplEvalSnapshotSupplier(b.gplEvalSnapshotSupplier);
    }
    if (b.lambdaMartReranker != null) {
      debugStateController.setLambdaMartReranker(b.lambdaMartReranker);
    }
    // §31 Phase 4: read from bootstrap to avoid duplicate construction. Fallback for legacy
    // test seams where HeadAssembly is null.
    EnterprisePolicyService enterprisePolicyService =
        b.HeadAssembly != null && b.HeadAssembly.serviceOut() != null
            ? b.HeadAssembly.serviceOut().enterprisePolicy()
            : new EnterprisePolicyServiceImpl();
    // Warm policy on startup — snapshot() writes sysprops as a v3 bridge to app-services.
    try {
      enterprisePolicyService.snapshot();
    } catch (Exception ignored) {
      // best-effort
    }
    InferenceHandlers inferenceHandlers =
        new InferenceHandlers(
            b.HeadAssembly != null && b.HeadAssembly.inference().onlineAi() != null
                ? b.HeadAssembly.inference().onlineAi()
                : (b.onlineAiService != null ? b.onlineAiService : OnlineAiService.unavailable()),
            b.knowledgeServer,
            gpuCapabilitiesService,
            enterprisePolicyService,
            b.settingsStore,
            telemetry,
            resolveInferenceCapability(b.HeadAssembly, b.inferenceCapability),
            // Tempdoc 737 fix pack (fix 4): the ONE runtime-intent authority for /api/inference/mode.
            // Null for legacy test seams (HeadAssembly absent) — those keep the raw fallback path.
            b.HeadAssembly != null && b.HeadAssembly.serviceOut() != null
                ? b.HeadAssembly.serviceOut().brainRuntime()
                : null);
    Supplier<String> diskPressureSupplier = null;
    if (telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt) {
      diskPressureSupplier = () -> lt.getHealthState().getDiskPressureLevel().name();
    }
    OnlineAiService onlineAi =
        b.HeadAssembly != null && b.HeadAssembly.inference().onlineAi() != null
            ? b.HeadAssembly.inference().onlineAi()
            : (b.onlineAiService != null ? b.onlineAiService : OnlineAiService.unavailable());
    io.justsearch.agent.api.AgentService agentService =
        b.HeadAssembly != null && b.HeadAssembly.core().agent() != null ? b.HeadAssembly.core().agent() : io.justsearch.agent.api.AgentService.unavailable();
    Supplier<io.justsearch.app.api.status.InferenceRuntimeView> inferenceSnapshotSupplier =
        b.inferenceSnapshotSupplier != null
            ? b.inferenceSnapshotSupplier
            : (b.HeadAssembly != null ? b.HeadAssembly::inferenceSnapshot : null);
    StatusLifecycleHandler statusLifecycleHandler =
        new StatusLifecycleHandler(
            onlineAi,
            agentService,
            inferenceSnapshotSupplier,
            b.knowledgeServer,
            b.knowledgeServerStartError,
            b.indexBasePath,
            startTime,
            diskPressureSupplier,
            b.lambdaMartReranker != null ? () -> b.lambdaMartReranker : null,
            b.gplJobCoordinator != null ? () -> b.gplJobCoordinator : null,
            () -> gpuCapabilitiesService,
            resolveWorkerCapability(headAssemblyRef, b.knowledgeServer, b.knowledgeServerStartError),
            resolveInferenceCapability(headAssemblyRef, b.inferenceCapability));
    // 419 C3 V1: wire head-side time-series + telemetry-health suppliers.
    if (telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt) {
      statusLifecycleHandler.setRrdStoreSupplier(lt::getRrdStore);
      statusLifecycleHandler.setTelemetryHealthSupplier(lt::getHealthState);
    }
    // Tempdoc 501 Phase 26 (§13.7 Q5): thread the runtime manifest publisher
    // into the status handler so it reads the overall lifecycle projection
    // from one canonical source instead of re-deriving on every request.
    if (b.runtimeManifestPublisher != null) {
      statusLifecycleHandler.setRuntimeManifestPublisher(b.runtimeManifestPublisher);
    }
    if (b.HeadAssembly != null) {
      var coordinator = b.HeadAssembly.headInfraRegistry().offlineCoordinator();
      if (coordinator != null) {
        statusLifecycleHandler.setVduCapabilitySnapshotSupplier(
            () -> coordinator.vduCapabilityState().snapshot());
        // Tempdoc 672 follow-up: live "is a batch running right now" fact for the progress
        // indicator — same coordinator instance, no new wiring beyond this one supplier.
        statusLifecycleHandler.setVduProcessingSupplier(coordinator::isProcessing);
      }
      // 630: request-time supplier of the connected knowledge-server bootstrap, for the energy +
      // post-resume status fields (lazy — the bootstrap is set after the worker connects).
      final HeadAssembly head = b.HeadAssembly;
      statusLifecycleHandler.setKnowledgeServerLifecycleSupplier(head::currentKnowledgeServer);
    }
    // 419 C3 V2 P3: GPU saturation monitor + scheduled sampler. Sampler short-circuits when
    // NVML is unavailable; monitor stays null in that case and case GPU defaults to READY.
    io.justsearch.ui.observability.GpuSaturationMonitor gpuSaturationMonitor = new io.justsearch.ui.observability.GpuSaturationMonitor();
    io.justsearch.ui.observability.GpuSaturationSampler gpuSaturationSampler =
        new io.justsearch.ui.observability.GpuSaturationSampler(
            b.executors,
            () -> gpuCapabilitiesService, gpuSaturationMonitor);
    statusLifecycleHandler.setGpuSaturationMonitor(gpuSaturationMonitor);
    // Tempdoc 672 follow-up: idle/energy-aware VDU auto-trigger sampler, same shape as
    // GpuSaturationSampler above. Every supplier is null-safe since b.HeadAssembly (and its
    // serviceOut) may be absent in minimal/test constructions.
    final HeadAssembly headForVduSampler = b.HeadAssembly;
    io.justsearch.app.services.vdu.VduOfflineTriggerSampler vduOfflineTriggerSampler =
        new io.justsearch.app.services.vdu.VduOfflineTriggerSampler(
            b.executors,
            () ->
                headForVduSampler != null
                    ? headForVduSampler.headInfraRegistry().offlineCoordinator()
                    : null,
            () -> headForVduSampler != null ? headForVduSampler.currentKnowledgeServer() : null,
            // Tempdoc 737 R4: keep this the REALIZED-state read — inferenceManager().isOnline() is
            // the FSM phase (mode==ONLINE), never spec/chatEnabled. The VDU exclusivity mutex
            // depends on realized state; feeding it spec would break self-interrupt-avoidance.
            () ->
                headForVduSampler != null
                    && headForVduSampler.serviceOut() != null
                    && headForVduSampler.serviceOut().inferenceManager() != null
                    && headForVduSampler.serviceOut().inferenceManager().isOnline());
    // Tempdoc 430 Phase 4: wire the HealthEvent substrate tap so each /api/status call
    // feeds the readiness envelope into ConditionStore + HealthEventChangeRegistry.
    if (b.HeadAssembly != null) {
      statusLifecycleHandler.setLifecycleSnapshotTap(
          b.HeadAssembly.substrate().health().lifecycleSnapshotTap());
      // Tempdoc 430 Phase 6: wire the worker-view tap alongside lifecycleSnapshotTap.
      statusLifecycleHandler.setWorkerSnapshotTap(
          b.HeadAssembly.substrate().health().workerSnapshotTap());
      // Tempdoc 626 §Axis-C: wire the per-root index-drift condition tap. It pulls the watched roots
      // (with the delete-detection-unverified flag) from the knowledge client each snapshot.
      var healthSub = b.HeadAssembly.substrate().health();
      statusLifecycleHandler.setIndexDriftTap(
          new io.justsearch.app.services.observability.health.IndexDriftHealthTap(
              healthSub.conditionStore(),
              healthSub.occurrenceLog(),
              healthSub.changes(),
              healthSub.headSource(),
              java.time.Clock.systemUTC(),
              () ->
                  b.knowledgeServer != null
                      ? b.knowledgeServer.client().getWatchedRoots(
                          io.justsearch.app.services.intent.EngineProvenance.internal(
                              "index-drift-health-tap", io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
                              io.justsearch.core.context.EngineContext.Urgency.BACKGROUND))
                      : java.util.List.of()));
      // Tempdoc 629 (FLOOR): wire the at-rest-protection condition tap + the shared disk-encryption
      // probe (one PowerShell shell-property read of the data-dir volume, cached 5s, fed to both the
      // /api/status View and the at-rest.unprotected condition).
      var atRestProbe =
          new io.justsearch.app.services.atrest.DiskEncryptionProbe(b.indexBasePath);
      statusLifecycleHandler.setAtRestTap(
          new io.justsearch.app.services.observability.health.AtRestHealthTap(
              healthSub.conditionStore(),
              healthSub.changes(),
              healthSub.headSource(),
              java.time.Clock.systemUTC(),
              atRestProbe::current),
          atRestProbe);
      // Tempdoc 629 (#2): the reactive conversation-encryption status reads the one DataKeyManager.
      statusLifecycleHandler.setConversationProtectionSupplier(
          () ->
              b.HeadAssembly == null
                  ? "unknown"
                  : switch (b.HeadAssembly.dataKeyManager().state()) {
                    case NOT_CONFIGURED -> "not_configured";
                    case LOCKED -> "locked";
                    case UNLOCKED -> "unlocked";
                  });
      // Tempdoc 629 (#2): the LAYER legibility tap — sibling of AtRestHealthTap. Asserts the
      // at-rest.authored condition (Recent events) when the AUTHORED stores have NO at-rest protection at
      // all — app-encryption not configured AND the disk not OS-encrypted — reading the DataKeyManager
      // state + the SAME DiskEncryptionProbe the FLOOR tap uses. It clears the moment app-encryption is set
      // up (even with FDE off), surfacing the LAYER's value. UNKNOWN FDE never warns ("unknown ≠ unhealthy").
      if (b.HeadAssembly != null) {
        statusLifecycleHandler.setConversationProtectionTap(
            new io.justsearch.app.services.observability.health.ConversationProtectionHealthTap(
                healthSub.conditionStore(),
                healthSub.changes(),
                healthSub.headSource(),
                java.time.Clock.systemUTC(),
                () ->
                    b.HeadAssembly.dataKeyManager().state()
                            == io.justsearch.app.services.encryption.DataKeyManager.State.NOT_CONFIGURED
                        && atRestProbe.current().state()
                            == io.justsearch.app.services.atrest.AtRestProtection.State.NOT_ENCRYPTED));
      }
      // observations.md inbox item #1 (2026-05-08): publish recent worker
      // metric arrays from the gRPC view into the TIMESERIES metric holders.
      // Bypasses the broken worker→head RRD replication so /api/metrics/worker.*
      // returns non-empty values arrays after ingest.
      var jqProducer = b.HeadAssembly.metricsOut().jobQueueDepthMetricProducer();
      var rateProducer = b.HeadAssembly.metricsOut().documentsIndexedRateMetricProducer();
      if (jqProducer != null || rateProducer != null) {
        statusLifecycleHandler.setWorkerMetricsPublisher(
            (workerView, stale) -> {
              if (workerView == null || Boolean.TRUE.equals(stale)) return;
              var core = workerView.core();
              if (core == null) return;
              if (jqProducer != null && core.recentJobQueueDepth() != null) {
                long[] longs = core.recentJobQueueDepth();
                double[] doubles = new double[longs.length];
                for (int i = 0; i < longs.length; i++) doubles[i] = longs[i];
                jqProducer.publishFromValues(doubles);
              }
              if (rateProducer != null && core.recentDocsPerSec() != null) {
                rateProducer.publishFromValues(core.recentDocsPerSec());
              }
            });
      }
      // Tempdoc 430 Phase 8: start the rule engine. RuleRunner is null when the rule
      // catalog is empty or telemetry doesn't expose RrdMetricStore (per
      // HeadAssembly.buildRuleRunner). Start/stop are symmetric per rev 3.11 §B.X.6.
      var rr = b.HeadAssembly.substrate().health().ruleRunner();
      if (rr != null) {
        rr.start();
      }
    }
    // §31 Phase 4: helpers read from bootstrap's ServicePhase output (single instance per
    // process). The bootstrap.workers()/core()/inference() ServiceGraph accessors give the
    // wrapper services; bootstrap.serviceOut() gives access to the underlying helpers that
    // ui controllers consume directly.
    PackAllowlistService packAllowlistService =
        b.HeadAssembly != null && b.HeadAssembly.serviceOut() != null
            ? b.HeadAssembly.serviceOut().packAllowlistService()
            : new PackAllowlistService();
    io.justsearch.app.services.ai.install.AiInstallService aiInstallHelper =
        b.HeadAssembly != null && b.HeadAssembly.serviceOut() != null
            ? b.HeadAssembly.serviceOut().aiInstallHelper()
            : new io.justsearch.app.services.ai.install.AiInstallService(
                onlineAi, b.settingsStore, b.knowledgeServer, enterprisePolicyService);
    AiInstallController aiInstallController = new AiInstallController(aiInstallHelper, telemetry);
    // Tempdoc 374 alpha.17 R5: read the resolved llama-server port live from
    // ConfigStore so a runtime change (rare, but supported via UI settings or
    // env override) is reflected without restart. Defaulting mirrors
    // InferenceConfig.resolvePort(): when serverPort is unset, prefer 8081
    // unless the head's own apiPort already holds it (then 8082). Pre-alpha.13
    // both defaulted to 8080 and collided.
    OpenAiCompatController openAiCompatController =
        new OpenAiCompatController(b.executors, llamaServerPortSupplier, telemetry);
    PolicyController policyController = new PolicyController(enterprisePolicyService, telemetry);
    // §31 Phase 4: DiagnosticsService read from bootstrap (its SPI providers resolve through
    // BootstrapLateBindings, which LocalApiServer publishes below).
    io.justsearch.app.api.DiagnosticsService diagnosticsService =
        b.HeadAssembly != null && b.HeadAssembly.core().diagnostics() != null
            ? b.HeadAssembly.core().diagnostics()
            : new io.justsearch.app.services.diagnostics.DiagnosticsServiceImpl(
                enterprisePolicyService,
                gpuCapabilitiesService,
                () -> debugStateController,
                () -> statusLifecycleHandler);
    DiagnosticsController diagnosticsController = new DiagnosticsController(diagnosticsService, telemetry);
    EffectiveConfigController effectiveConfigController =
        new EffectiveConfigController(portSupplier, b.settingsStore, enterprisePolicyService,
            b.HeadAssembly != null && b.HeadAssembly.inference().onlineAi() != null ? b.HeadAssembly.inference().onlineAi() : OnlineAiService.unavailable(), b.indexBasePath,
            ConfigStore.globalOrNull(), b.engineAdmission);
    SessionPoliciesController sessionPoliciesController =
        b.knowledgeServer != null
            ? new SessionPoliciesController(b.knowledgeServer.client())
            : new SessionPoliciesController(null);
    EncoderRuntimeController encoderRuntimeController =
        b.knowledgeServer != null
            ? new EncoderRuntimeController(b.knowledgeServer.client())
            : new EncoderRuntimeController(null);
    LogLevelController logLevelController = new LogLevelController(telemetry);
    TimeSeriesController timeSeriesController = new TimeSeriesController(() -> {
      if (telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt) {
        return lt.getRrdStore();
      }
      return null;
    });
    // §31 Phase 4: helpers + wrapper services read from bootstrap (single instance per process).
    RuntimeActivationService runtimeActivationHelper;
    if (b.HeadAssembly != null && b.HeadAssembly.serviceOut() != null) {
      // Bootstrap-owned instance: ServicePhase already late-bound its observed-EP cache.
      runtimeActivationHelper = b.HeadAssembly.serviceOut().runtimeActivationHelper();
    } else {
      runtimeActivationHelper =
          new RuntimeActivationService(
              b.executors,
              onlineAi,
              b.settingsStore,
              gpuCapabilitiesService,
              enterprisePolicyService,
              b.workerFeatureCache,
              resolveInferenceCapability(b.HeadAssembly, b.inferenceCapability),
              aiInstallHelper);
      if (b.knowledgeServer != null) {
        // Tempdoc 805 G.3: observed ONNX execution provider beside the intent fields on
        // /api/ai/runtime/status, from the same explainer that backs /api/inference/encoders.
        runtimeActivationHelper.setEncoderRuntimeCache(
            new io.justsearch.app.services.observability.WorkerEncoderRuntimeCache(
                b.knowledgeServer::client));
      }
      // Tempdoc 824 §3.3c: same binding ServicePhase makes for the bootstrap-owned instance —
      // without it this branch's install status would report every capability "unknown" and the
      // reconciliation would silently never fire (the wrong-gate shape).
      if (aiInstallHelper != null) {
        aiInstallHelper.setFunctionalStatusSource(
            runtimeActivationHelper::functionalStatusByPackage);
      }
    }
    AiRuntimeController aiRuntimeController =
        new AiRuntimeController(runtimeActivationHelper, telemetry,
            b.HeadAssembly == null || b.HeadAssembly.serviceOut() == null);
    // Tempdoc 656 Task 4: read-only reconciliation of the model registry against on-disk
    // presence — reuses aiInstallHelper + runtimeActivationHelper, no new resolution logic.
    AiModelsController aiModelsController =
        new AiModelsController(
            new io.justsearch.app.services.ai.preflight.AiPreflightService(
                aiInstallHelper, runtimeActivationHelper));
    AiPackImportService aiPackImportHelper =
        b.HeadAssembly != null && b.HeadAssembly.serviceOut() != null
            ? b.HeadAssembly.serviceOut().aiPackImportHelper()
            : new AiPackImportService(
                onlineAi,
                b.settingsStore,
                b.knowledgeServer,
                enterprisePolicyService,
                packAllowlistService);
    AiPackController aiPackController = new AiPackController(aiPackImportHelper, telemetry);
    // §31 Phase 3: services are constructed by ServicePhase from boot. LocalApiServer no
    // longer constructs services or calls registerLateBoundHandlers. Only 3 controller
    // back-refs need publishing: settings reset callback (used by SettingsServiceImpl),
    // DebugStateProvider + StatusSnapshotProvider SPIs (used by DiagnosticsServiceImpl).
    if (headAssemblyRef != null) {
      var lateBindings = headAssemblyRef.lateBindings();
      lateBindings.setSettingsResetFn(settingsController::resetToDefaults);
      lateBindings.setDebugStateProvider(debugStateController);
      lateBindings.setStatusSnapshotProvider(statusLifecycleHandler);
      // Tempdoc 876 §B.2a: give the readiness-reconciliation trigger its thunk. This is the first
      // point at which the handler exists AND its taps are wired (the tap block above), so it is
      // also the earliest safe self-seed. Not a second envelope authority — the trigger only
      // re-runs buildStatusSnapshot, which is buildStatusMap, which is the one place
      // buildReadinessEnvelope is called.
      if (b.HeadAssembly != null
          && b.HeadAssembly.substrate() != null
          && b.HeadAssembly.substrate().health() != null) {
        var readinessTrigger =
            b.HeadAssembly.substrate().health().readinessReconciliationTrigger();
        if (readinessTrigger != null) {
          // Tempdoc 885 item 6: the trigger's thunk IS the health sampler — it performs the one
          // IndexStatus unary off the request thread and feeds every tap. attach() self-seeds one
          // reconcile, so the first sample lands here, not at the first monitor tick.
          readinessTrigger.attach(statusLifecycleHandler::sampleAndBuildStatusSnapshot);
        }
      }
    }

    HeadHttpInflightMetricCatalog inflightCatalog;
    HeadGpuMetricCatalog gpuCatalog;
    // ---------------- Saturation gauges (low-cardinality, cheap) ----------------
    // Tempdoc 417 Phase 2c: gauges go through typed catalogs constructed against
    // LocalTelemetry's registry. Bootstrap (LauncherEnvironment / HeadlessApp) registered the
    // catalog DEFINITIONS at LocalTelemetry construction; here we instantiate the typed
    // catalogs and wire their suppliers. F1 follow-up: catalogs now live in
    // {@code app-services/observability} so LauncherEnvironment can register them too.
    if (telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt) {
      inflightCatalog =
          new HeadHttpInflightMetricCatalog(
              lt.registry(), inflightSupplier);
      gpuCatalog =
          new HeadGpuMetricCatalog(
              lt.registry(),
              () -> {
                var snap = gpuSnapshotSupplier.get();
                Integer pct = snap.nvml().gpuUtilizationPercent();
                return pct != null ? (double) pct : Double.NaN;
              },
              () -> {
                var snap = gpuSnapshotSupplier.get();
                Integer pct = snap.nvml().memoryUtilizationPercent();
                return pct != null ? (double) pct : Double.NaN;
              });
    } else {
      inflightCatalog = HeadHttpInflightMetricCatalog.noop();
      gpuCatalog = HeadGpuMetricCatalog.noop();
    }
    if (telemetry != null) {
      JvmRuntimeGauges.register(telemetry, "head");
    }

    // Log server start event
    eventBuffer.info("LocalApiServer", "API Server starting");
    KnowledgeSearchController knowledgeSearchController = b.knowledgeServer != null
        ? new KnowledgeSearchController(
            b.knowledgeServer,
            telemetry,
            b.HeadAssembly != null && b.HeadAssembly.inference().onlineAi() != null ? b.HeadAssembly.inference().onlineAi() : OnlineAiService.unavailable(),
            b.lambdaMartReranker,
            apiCatalog)
        : null;
    if (knowledgeSearchController != null && headAssemblyRef != null) {
      knowledgeSearchController.setWorkerCapability(headAssemblyRef.capabilities().worker());
      knowledgeSearchController.getAdapter().setWorkerCapability(headAssemblyRef.capabilities().worker());
      // Tempdoc 778 — seal the search-interaction disposition + feature-snapshot streams with the
      // AUTHORED feedback-store key (passthrough when at-rest encryption is off).
      knowledgeSearchController.setFeedbackCipher(
          headAssemblyRef.storeCipher(
              io.justsearch.agent.api.encryption.StoreCatalog.FEEDBACK.recoverability()));
      knowledgeSearchController.setFeedbackCaptureSettings(
          headAssemblyRef.feedbackCaptureSettings());
    }
    return new Result(
        chunkInfoController,
        previewController,
        debugStateController,
        inferenceHandlers,
        statusLifecycleHandler,
        gpuSaturationMonitor,
        gpuSaturationSampler,
        vduOfflineTriggerSampler,
        aiInstallController,
        aiInstallHelper,
        openAiCompatController,
        policyController,
        diagnosticsController,
        effectiveConfigController,
        sessionPoliciesController,
        encoderRuntimeController,
        logLevelController,
        timeSeriesController,
        aiRuntimeController,
        aiPackController,
        aiModelsController,
        inflightCatalog,
        gpuCatalog,
        knowledgeSearchController);
  }

  private static io.justsearch.app.services.lifecycle.WorkerCapability resolveWorkerCapability(
      HeadAssembly bootstrap,
      KnowledgeServerBootstrap ks,
      String startError) {
    if (bootstrap != null) return bootstrap.capabilities().worker();
    if (ks != null) return ks.workerCapability();
    if (startError != null && !startError.isBlank()) return createFailedWorkerCapability(startError);
    return createOfflineWorkerCapability();
  }

  private static io.justsearch.app.services.lifecycle.WorkerCapability createOfflineWorkerCapability() {
    var cap = new io.justsearch.app.services.lifecycle.WorkerCapability();
    cap.transition(
        io.justsearch.app.api.lifecycle.CapabilityHealth.OFFLINE,
        io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_NOT_CONFIGURED.code(),
        "Worker not configured");
    return cap;
  }

  private static io.justsearch.app.services.lifecycle.WorkerCapability createFailedWorkerCapability(String error) {
    var cap = new io.justsearch.app.services.lifecycle.WorkerCapability();
    cap.transition(
        io.justsearch.app.api.lifecycle.CapabilityHealth.DEGRADED,
        io.justsearch.app.api.lifecycle.LifecycleReasonCode.WORKER_SPAWN_FAILED.code(),
        "Worker spawn failed: " + error);
    return cap;
  }

  private static io.justsearch.app.services.lifecycle.InferenceCapability resolveInferenceCapability(
      HeadAssembly bootstrap,
      io.justsearch.app.services.lifecycle.InferenceCapability explicit) {
    if (bootstrap != null) return bootstrap.capabilities().inference();
    if (explicit != null) return explicit;
    return new io.justsearch.app.services.lifecycle.InferenceCapability(false);
  }
}
