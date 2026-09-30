/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.DiagnosticChannelCatalog;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.PromptCatalog;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SurfaceCatalog;
import io.justsearch.app.observability.CapabilitiesChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryChangeRegistry;
import io.justsearch.app.observability.advisory.AdvisoryClassId;
import io.justsearch.app.observability.advisory.AdvisoryClassRegistry;
import io.justsearch.app.observability.advisory.AdvisoryLog;
import io.justsearch.app.observability.advisory.AdvisoryResourceCatalog;
import io.justsearch.app.observability.diagnostic.DiagnosticChannelStreamRegistry;
import io.justsearch.app.observability.health.ConditionRecoveryIndexChangeRegistry;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.OccurrenceLog;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.observability.indexing.IndexingJobsChangeRegistry;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import io.justsearch.app.observability.metrics.DocumentsIndexedRateMetricChangeRegistry;
import io.justsearch.app.observability.metrics.GpuMemoryUtilizationMetricChangeRegistry;
import io.justsearch.app.observability.metrics.GpuUtilizationMetricChangeRegistry;
import io.justsearch.app.observability.metrics.JobQueueDepthMetricChangeRegistry;
import io.justsearch.app.observability.metrics.TimeseriesSnapshotHolder;
import io.justsearch.app.observability.operations.OperationHistoryChangeRegistry;
import io.justsearch.app.observability.operations.OperationHistoryStore;
import io.justsearch.app.observability.runtime.RuntimeContextChangeRegistry;
import io.justsearch.app.observability.runtime.RuntimeContextHolder;
import java.util.Map;
import java.util.function.Function;

/**
 * Tempdoc 519 §6 / Step 5: typed substrate graph holding the application's catalogs, change
 * registries, projectors, advisory cluster, intent router, metric catalogs, and health/rule
 * substrate. Replaces ~50 individual accessors on {@code HeadAssembly} with one
 * {@link SubstrateGraph} record that callers can consume as a single unit.
 *
 * <p>Sub-records group fields by phase-consumer affinity so each consumer reaches into only the
 * sub-record it needs (e.g., the SSE controller cluster reads {@code resources()} and {@code
 * advisory()}; the agent loop reads {@code operations()}; status handlers read {@code health()}).
 *
 * <p>The bootstrap still exposes legacy 1-liner accessors that delegate into the record fields —
 * existing callsites keep working while new callers can use the typed records directly.
 *
 * <p>Lives in {@code app-services} (not {@code app-api}) because several fields reference
 * {@code app-observability} types that are not visible to the API layer.
 */
public record SubstrateGraph(
    OperationSubstrate operations,
    ResourceSubstrate resources,
    AdvisorySubstrate advisory,
    IntentSubstrate intent,
    MetricSubstrate metrics,
    HealthSubstrate health,
    ChannelSubstrate channels,
    ContextSubstrate context,
    ConversationSubstrate conversation) {

  /** Operation + agent-tool catalogs, dispatcher, handler registry, prompt catalog. */
  public record OperationSubstrate(
      OperationCatalog operations,
      OperationCatalog agentTools,
      // Tempdoc 560 §28 Phase 2: the SERVED Prompt catalog = core + composed plugin Prompts (the
      // assembler merges them; this field is read only by the /api/registry/prompts endpoint).
      PromptCatalog prompts,
      OperationDispatcher executor,
      HandlerRegistry handlers,
      Function<String, String> messageResolver) {}

  /** ResourceCatalogs that back the registry-resources SSE surface. */
  public record ResourceSubstrate(
      ResourceCatalog resources,
      ResourceCatalog runtimeContext,
      ResourceCatalog serverCapabilities,
      ResourceCatalog coreInference,
      ResourceCatalog operationHistory,
      ResourceCatalog indexingJobs,
      ResourceCatalog failedIndexingJobs,
      ResourceCatalog indexedRoots,
      ResourceCatalog conditionRecoveryIndex,
      // Tempdoc 571 §4c: the action-ledger Resource (TRUST role) — Activity's derived-altitude source.
      ResourceCatalog actionLedger,
      // Tempdoc 575 §17 Face C: Brain install/pack OBSERVABLE polled-state Resources — projected to the
      // LIVE /api/registry/resources so the registration is actually served, not just governance-declared.
      ResourceCatalog aiInstall,
      ResourceCatalog aiPackImport,
      // Tempdoc 560 §28 Phase 2: composed plugin-contributed Resources (empty unless a plugin contributed
      // one), served alongside the core catalogs at /api/registry/resources.
      ResourceCatalog pluginResources) {}

  /** Advisory cluster — Resource catalog, change registry, class registry, per-class logs. */
  public record AdvisorySubstrate(
      AdvisoryResourceCatalog resources,
      AdvisoryChangeRegistry changes,
      AdvisoryClassRegistry classes,
      Map<AdvisoryClassId, AdvisoryLog> logs) {}

  /** Intent source catalog + backend router + envelope change registry. */
  public record IntentSubstrate(
      IntentSourceCatalog sources, BackendIntentRouter router, IntentEnvelopeChangeRegistry changes) {}

  /** Metric catalogs + change registries + snapshot holders (4 metric families). */
  public record MetricSubstrate(
      ResourceCatalog jobQueueDepthCatalog,
      JobQueueDepthMetricChangeRegistry jobQueueDepthChanges,
      TimeseriesSnapshotHolder jobQueueDepthHolder,
      ResourceCatalog documentsIndexedRateCatalog,
      DocumentsIndexedRateMetricChangeRegistry documentsIndexedRateChanges,
      TimeseriesSnapshotHolder documentsIndexedRateHolder,
      ResourceCatalog gpuUtilizationCatalog,
      GpuUtilizationMetricChangeRegistry gpuUtilizationChanges,
      TimeseriesSnapshotHolder gpuUtilizationHolder,
      ResourceCatalog gpuMemoryUtilizationCatalog,
      GpuMemoryUtilizationMetricChangeRegistry gpuMemoryUtilizationChanges,
      TimeseriesSnapshotHolder gpuMemoryUtilizationHolder) {}

  /** Health substrate — condition store, occurrence log, change registry, taps, emitter, source. */
  public record HealthSubstrate(
      ConditionStore conditionStore,
      OccurrenceLog occurrenceLog,
      HealthEventChangeRegistry changes,
      Source headSource,
      ConditionRecoveryIndexChangeRegistry recoveryIndexChanges,
      io.justsearch.app.services.observability.health.LifecycleSnapshotTap lifecycleSnapshotTap,
      io.justsearch.app.services.observability.health.WorkerSnapshotTap workerSnapshotTap,
      io.justsearch.app.services.observability.health.HeadHealthEventsEmitter headHealthEventsEmitter,
      io.justsearch.app.services.observability.rules.RuleRunner ruleRunner,
      // Tempdoc 876 §B.2a: the non-request trigger that re-runs the readiness snapshot (and hence
      // every tap above) on a capability transition instead of only on GET /api/status.
      io.justsearch.app.services.observability.health.ReadinessReconciliationTrigger
              readinessReconciliationTrigger) {}

  /**
   * Diagnostic channel catalogs (core engine-log + the composed plugin-contributed channels, tempdoc
   * 560 §10.4) + SSE stream registry.
   */
  public record ChannelSubstrate(
      DiagnosticChannelCatalog engineLogCatalog,
      DiagnosticChannelCatalog pluginChannelCatalog,
      DiagnosticChannelStreamRegistry streams) {}

  /** Runtime context holder + change registry + config bridge. */
  public record ContextSubstrate(
      RuntimeContextHolder holder,
      RuntimeContextChangeRegistry changes,
      io.justsearch.app.services.observability.runtime.RuntimeContextConfigBridge configBridge) {}

  /**
   * Conversation surface + shape catalogs, indexing-jobs change registry, operation history,
   * capabilities change registry.
   */
  public record ConversationSubstrate(
      SurfaceCatalog coreSurfaces,
      ConversationShapeCatalog shapes,
      // Tempdoc 560 §10.4: the composed plugin-contributed surfaces + conversation shapes (empty unless
      // a plugin contributed one), served alongside the core catalogs at /api/registry/{surfaces,shapes}.
      SurfaceCatalog pluginSurfaceCatalog,
      ConversationShapeCatalog pluginShapeCatalog,
      IndexingJobsChangeRegistry indexingJobsChanges,
      OperationHistoryStore operationHistoryStore,
      OperationHistoryChangeRegistry operationHistoryChanges,
      CapabilitiesChangeRegistry capabilitiesChanges,
      // Tempdoc 550 Slice F1 (Outcome face): the Navigation sibling ledger.
      io.justsearch.app.observability.navigation.NavigationHistoryStore navigationHistoryStore,
      // Tempdoc 550 Slice A1/C2 (Authorize face): consent-capsule mint/verify authority.
      io.justsearch.agent.api.registry.ConsentCapsuleAuthority consentCapsuleService,
      // Tempdoc 550 Outcome face: the gate-decision ledger sibling (trust-gate firings).
      io.justsearch.app.observability.operations.AuthorizationOutcomeStore
          authorizationOutcomeStore,
      // Tempdoc 550 G3/G4/G5: the unified live action-ledger change-stream.
      io.justsearch.app.observability.ledger.ActionLedgerChangeRegistry
          actionLedgerChangeRegistry,
      // Tempdoc 812 D2: the scan-rollup aggregator over that log — the ingest adapter hands it each
      // scan's identity/root/admitted count; it counts the REAL terminal job outcomes and emits one
      // durable scan-completion row.
      io.justsearch.app.observability.ledger.ScanRollupLedger scanRollupLedger,
      // Tempdoc 550 E2: the process-wide emergency stop (lattice deny-all-non-user).
      io.justsearch.app.services.registry.executor.GlobalHardStop globalHardStop,
      // Tempdoc 550 thesis III: the ONE intent-gate evaluator shared by enforcement + Preview.
      io.justsearch.app.services.intent.IntentGateEvaluator intentGateEvaluator,
      // Tempdoc 550 thesis IV: durable allow-always grants (issued by the approve endpoint).
      io.justsearch.app.services.intent.DurableGrantStore durableGrantStore,
      // Tempdoc 655: shared between REST and MCP gate paths so either can create/consume a
      // pending authorization for the other's approve endpoint to satisfy.
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingAuthorizationStore,
      // Tempdoc 655: live announcement of new pending records for the always-on shell.
      io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry
          pendingAuthorizationChanges) {}
}
