/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.DiagnosticsService;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.PackImportService;
import io.justsearch.app.api.PolicyService;
import io.justsearch.app.api.RuntimeVariantService;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.ActivateInstalledModelsHandler;
import io.justsearch.app.services.registry.operations.handlers.ActivateRuntimeVariantHandler;
import io.justsearch.app.services.registry.operations.handlers.AddWatchedRootHandler;
import io.justsearch.app.services.registry.operations.handlers.AllowlistAddDigestHandler;
import io.justsearch.app.services.registry.operations.handlers.ApplyExcludesHandler;
import io.justsearch.app.services.registry.operations.handlers.AcceptGapsHandler;
import io.justsearch.app.services.registry.operations.handlers.BulkReindexHandler;
import io.justsearch.app.services.registry.operations.handlers.CancelAiInstallHandler;
import io.justsearch.app.services.registry.operations.handlers.CancelIndexingJobHandler;
import io.justsearch.app.services.registry.operations.handlers.ClearFailedJobsHandler;
import io.justsearch.app.services.registry.operations.handlers.CopyDiagnosticSummaryHandler;
import io.justsearch.app.services.registry.operations.handlers.CreateUserPolicyHandler;
import io.justsearch.app.services.registry.operations.handlers.DeactivateRuntimeVariantHandler;
import io.justsearch.app.services.registry.operations.handlers.ExportDiagnosticsHandler;
import io.justsearch.app.services.registry.operations.handlers.ImportAiPackHandler;
import io.justsearch.app.services.registry.operations.handlers.IndexGcHandler;
import io.justsearch.app.services.registry.operations.handlers.PingBackendHandler;
import io.justsearch.app.services.registry.operations.handlers.PreflightAiPackHandler;
import io.justsearch.app.services.registry.operations.handlers.PreviewExcludesHandler;
import io.justsearch.app.services.registry.operations.handlers.ReconcileRootHandler;
import io.justsearch.app.services.registry.operations.handlers.ReconfigureHandler;
import io.justsearch.app.services.registry.operations.handlers.ReindexHandler;
import io.justsearch.app.services.registry.operations.handlers.RemoveWatchedRootHandler;
import io.justsearch.app.services.registry.operations.handlers.RepairAiInstallHandler;
import io.justsearch.app.services.registry.operations.handlers.ResetSettingsHandler;
import io.justsearch.app.services.registry.operations.handlers.ResolvePathHashHandler;
import io.justsearch.app.services.registry.operations.handlers.RestartWorkerHandler;
import io.justsearch.app.services.registry.operations.handlers.RetryIndexingJobHandler;
import io.justsearch.app.services.registry.operations.handlers.SetChatEnabledHandler;
import io.justsearch.app.services.registry.operations.handlers.SettleIndexHandler;
import io.justsearch.app.services.registry.operations.handlers.StartAiInstallHandler;
import io.justsearch.app.services.registry.operations.handlers.SwitchInferenceModeHandler;
import io.justsearch.app.services.registry.operations.handlers.TriggerOfflineProcessingHandler;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.WorkerServiceImpl;
import java.util.function.Supplier;

/**
 * §31 Phase 3: ALL Operation handlers register at boot. Previously the 14
 * controller-service-dependent handlers were registered via a separate
 * {@code registerLateBound} call after LocalApiServer constructed the services. Now that
 * ServicePhase constructs all services, handlers can register against suppliers pulling from
 * the bootstrap's held ServiceGraph — no late-bind step needed.
 *
 * <p>The 14 controller-service suppliers are passed as parameters; they pull from
 * {@code bootstrap.workers().X()} / {@code bootstrap.core().X()} / {@code bootstrap.inference().X()}.
 * By handler-dispatch time, the held graph is fully populated.
 */
public final class OperationHandlerRegistrations {

  private OperationHandlerRegistrations() {}

  public static WorkerServiceImpl registerWorker(
      HandlerRegistry handlers,
      Supplier<KnowledgeServerBootstrap> knowledgeServerBootstrapSupplier,
      Supplier<IndexingService> indexingServiceSupplier,
      Supplier<ExcludesService> excludesServiceSupplier,
      Supplier<SettingsService> settingsServiceSupplier,
      Supplier<DiagnosticsService> diagnosticsServiceSupplier,
      Supplier<BrainRuntimeService> brainRuntimeServiceSupplier,
      Supplier<RuntimeVariantService> runtimeVariantServiceSupplier,
      Supplier<PackImportService> packImportServiceSupplier,
      Supplier<BrainInstallService> brainInstallServiceSupplier,
      Supplier<PolicyService> policyServiceSupplier,
      // Tempdoc 737 §12b — the runtime-authority handles for the chat-enabled intent write
      // (core.set-chat-enabled + the core.switch-inference-mode alias). Both pull from the held
      // ServiceGraph at dispatch time.
      Supplier<RuntimeSpecStore> runtimeSpecStoreSupplier,
      Supplier<RuntimeReconciler> runtimeReconcilerSupplier,
      // Tempdoc 542 Phase 3 — long-op handlers register op-leases via this SPI.
      io.justsearch.app.api.OperationLeaseService operationLeaseService,
      io.justsearch.app.api.operations.RecordedIngestionService recordedIngestion, io.justsearch.app.services.worker.WatchedRootsState recordedRoots) {
    final WorkerServiceImpl workerService = new WorkerServiceImpl(knowledgeServerBootstrapSupplier);
    handlers.register(
        CoreOperationCatalog.RESTART_WORKER, new RestartWorkerHandler(() -> workerService));
    handlers.register(
        CoreOperationCatalog.BULK_REINDEX,
        new BulkReindexHandler(io.justsearch.app.api.operations.RecordedBulkPlan.Profile.USER_BULK,
            recordedIngestion, context -> recordedRoots.snapshotBindings(), indexingServiceSupplier,
            io.justsearch.app.services.worker.KnowledgeClient::captureRecordedExcludePatterns));
    handlers.register(CoreOperationCatalog.ACCEPT_GAPS, new AcceptGapsHandler(recordedIngestion));
    handlers.register(
        CoreOperationCatalog.REBUILD_INDEX,
        new BulkReindexHandler(io.justsearch.app.api.operations.RecordedBulkPlan.Profile.RECOVERY_REBUILD,
            recordedIngestion, context -> recordedRoots.snapshotBindings(), indexingServiceSupplier,
            io.justsearch.app.services.worker.KnowledgeClient::captureRecordedExcludePatterns));
    handlers.register(CoreOperationCatalog.PING_BACKEND, new PingBackendHandler());
    handlers.register(
        CoreOperationCatalog.CLEAR_FAILED_JOBS, new ClearFailedJobsHandler(indexingServiceSupplier));
    handlers.register(
        CoreOperationCatalog.INDEX_GC,
        new IndexGcHandler(indexingServiceSupplier, operationLeaseService));
    handlers.register(
        CoreOperationCatalog.SETTLE_INDEX,
        new SettleIndexHandler(indexingServiceSupplier, operationLeaseService));
    handlers.register(
        CoreOperationCatalog.CANCEL_INDEXING_JOB,
        new CancelIndexingJobHandler(indexingServiceSupplier));
    handlers.register(
        CoreOperationCatalog.RETRY_INDEXING_JOB,
        new RetryIndexingJobHandler(indexingServiceSupplier));
    handlers.register(
        CoreOperationCatalog.RESOLVE_PATH_HASH,
        new ResolvePathHashHandler(indexingServiceSupplier));
    handlers.register(CoreOperationCatalog.REINDEX, new ReindexHandler(recordedIngestion,
        context -> recordedRoots.snapshotBindings(),
        context -> java.util.Objects.requireNonNull(indexingServiceSupplier.get(), "Indexing service unavailable")
            .captureServingGeneration(context),
        io.justsearch.app.services.worker.KnowledgeClient::captureRecordedExcludePatterns));
    handlers.register(
        CoreOperationCatalog.RECONCILE_ROOT, new ReconcileRootHandler(indexingServiceSupplier));
    handlers.register(
        CoreOperationCatalog.ADD_WATCHED_ROOT, new AddWatchedRootHandler(indexingServiceSupplier));
    handlers.register(
        CoreOperationCatalog.REMOVE_WATCHED_ROOT,
        new RemoveWatchedRootHandler(indexingServiceSupplier));
    handlers.register(
        CoreOperationCatalog.PREVIEW_EXCLUDES, new PreviewExcludesHandler(excludesServiceSupplier));
    handlers.register(
        CoreOperationCatalog.APPLY_EXCLUDES, new ApplyExcludesHandler(excludesServiceSupplier));
    // §31 Phase 3: 14 formerly-late-bound handlers register at boot using suppliers from the
    // held ServiceGraph.
    handlers.register(
        CoreOperationCatalog.EXPORT_DIAGNOSTICS,
        new ExportDiagnosticsHandler(diagnosticsServiceSupplier));
    handlers.register(
        CoreOperationCatalog.COPY_DIAGNOSTIC_SUMMARY,
        new CopyDiagnosticSummaryHandler(diagnosticsServiceSupplier));
    // Tempdoc 737 §12b: the intent-write op (no preconditions) and the superseded
    // switch-inference-mode alias both converge on the one spec-write path.
    handlers.register(
        CoreOperationCatalog.SET_CHAT_ENABLED,
        new SetChatEnabledHandler(runtimeSpecStoreSupplier, runtimeReconcilerSupplier));
    handlers.register(
        CoreOperationCatalog.SWITCH_INFERENCE_MODE,
        new SwitchInferenceModeHandler(runtimeSpecStoreSupplier, runtimeReconcilerSupplier));
    handlers.register(
        CoreOperationCatalog.TRIGGER_OFFLINE_PROCESSING,
        new TriggerOfflineProcessingHandler(brainRuntimeServiceSupplier));
    handlers.register(
        CoreOperationCatalog.ACTIVATE_RUNTIME_VARIANT,
        new ActivateRuntimeVariantHandler(runtimeVariantServiceSupplier));
    handlers.register(
        CoreOperationCatalog.DEACTIVATE_RUNTIME_VARIANT,
        new DeactivateRuntimeVariantHandler(runtimeVariantServiceSupplier));
    handlers.register(
        CoreOperationCatalog.PREFLIGHT_AI_PACK,
        new PreflightAiPackHandler(packImportServiceSupplier));
    handlers.register(
        CoreOperationCatalog.IMPORT_AI_PACK,
        new ImportAiPackHandler(packImportServiceSupplier));
    handlers.register(
        CoreOperationCatalog.START_AI_INSTALL,
        new StartAiInstallHandler(brainInstallServiceSupplier));
    handlers.register(
        CoreOperationCatalog.ACTIVATE_INSTALLED_MODELS,
        new ActivateInstalledModelsHandler(
            brainInstallServiceSupplier,
            recordedIngestion,
            context -> recordedRoots.snapshotBindings(),
            indexingServiceSupplier,
            io.justsearch.app.services.worker.KnowledgeClient::captureRecordedExcludePatterns));
    handlers.register(
        CoreOperationCatalog.CANCEL_AI_INSTALL,
        new CancelAiInstallHandler(brainInstallServiceSupplier));
    handlers.register(
        CoreOperationCatalog.REPAIR_AI_INSTALL,
        new RepairAiInstallHandler(brainInstallServiceSupplier));
    handlers.register(
        CoreOperationCatalog.CREATE_USER_POLICY,
        new CreateUserPolicyHandler(policyServiceSupplier));
    handlers.register(
        CoreOperationCatalog.ALLOWLIST_ADD_DIGEST,
        new AllowlistAddDigestHandler(policyServiceSupplier));
    handlers.register(
        CoreOperationCatalog.RESET_SETTINGS, new ResetSettingsHandler(settingsServiceSupplier));
    handlers.register(
        CoreOperationCatalog.RECONFIGURE, new ReconfigureHandler(settingsServiceSupplier));
    return workerService;
  }
}
