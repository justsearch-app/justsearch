/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * Reviewable exceptions to deliberate client activity. Each observer names an actual unattended
 * consumer or a specific status/health/readiness purpose. Keys use registered templates, not concrete
 * request paths; SSE uses its underlying GET. Adding an observer requires adding its evidence here.
 * This guard does not choose roles or replace ActivityRole's exactly-one-declaration validation.
 */
public final class ObservationJustifications {
  private ObservationJustifications() {}

  private static final Map<String, String> REASONS = Map.ofEntries(
      Map.entry("GET /.well-known/justsearch/manifest.json", "STATUS: canonical current runtime manifest discovery mirror (RuntimeManifestController.handleGet)"),
      Map.entry("GET /api/action-ledger", "CONSUMER: ui-web/src/shell-v0/operations/ActionLedgerClient.ts via ui-web/src/shell-v0/components/ActionLedgerView.ts ledger snapshot seed on Activity mount"),
      Map.entry("GET /api/action-ledger/stream", "CONSUMER: ui-web/src/shell-v0/operations/ActionLedgerClient.ts via ui-web/src/shell-v0/components/ActionLedgerView.ts continuous ledger subscription on Activity mount"),
      Map.entry("GET /api/advisory/authorization-pending/stream", "CONSUMER: ui-web/src/shell-v0/components/ResourceView.ts [resource.endpoint=app-observability/src/main/java/io/justsearch/app/observability/advisory/AdvisoryResourceCatalog.java#AUTHORIZATION_PENDING_ENDPOINT] continuous advisory Resource subscription"),
      Map.entry("GET /api/advisory/health-recoverable/stream", "CONSUMER: ui-web/src/shell-v0/components/ResourceView.ts [resource.endpoint=app-observability/src/main/java/io/justsearch/app/observability/advisory/AdvisoryResourceCatalog.java#HEALTH_RECOVERABLE_ENDPOINT] continuous advisory Resource subscription"),
      Map.entry("GET /api/advisory/operation-completed/stream", "CONSUMER: ui-web/src/shell-v0/components/ResourceView.ts [resource.endpoint=app-observability/src/main/java/io/justsearch/app/observability/advisory/AdvisoryResourceCatalog.java#OPERATION_COMPLETED_ENDPOINT] continuous advisory Resource subscription"),
      Map.entry("GET /api/agent/hard-stop", "STATUS: current agent hard-stop state (HardStopController.handleGet)"),
      Map.entry("GET /api/ai/install/manifest", "CONSUMER: ui-web/src/api/domains/packs.ts via ui-web/src/shell-v0/views/BrainSurface.ts automatic Brain catalog hydration on mount"),
      Map.entry("GET /api/ai/install/plan-preview", "CONSUMER: ui-web/src/shell-v0/views/BrainSurface.ts automatic plan hydration on mount"),
      Map.entry("GET /api/ai/install/status", "STATUS: AI installation progress"),
      Map.entry("GET /api/ai/models/status", "STATUS: current AI model availability (AiModelsController.handleGetStatus)"),
      Map.entry("GET /api/ai/packs/installed", "STATUS: installed AI pack inventory"),
      Map.entry("GET /api/ai/packs/status", "STATUS: AI pack availability"),
      Map.entry("GET /api/ai/runtime/status", "STATUS: current AI runtime state"),
      Map.entry("GET /api/authorizations/grants", "CONSUMER: ui-web/src/shell-v0/views/SettingsSurface.ts grant-list mount hydration"),
      Map.entry("GET /api/authorizations/pending/{id}", "STATUS: current pending authorization"),
      Map.entry("GET /api/boot/phases", "READINESS: boot phase progress"),
      Map.entry("GET /api/chat/agent/tools", "CONSUMER: ui-web/src/shell-v0/controllers/AgentSessionController.ts startPolling/checkAvailability hydrates available tools"),
      Map.entry("GET /api/chat/approval", "STATUS: pending approval state"),
      Map.entry("GET /api/chat/conversations", "CONSUMER: ui-web/src/shell-v0/state/conversationListStore.ts via ui-web/src/shell-v0/views/UnifiedChatView.ts conversation-list hydration on connect"),
      Map.entry("GET /api/chat/conversations/{sessionId}/history", "CONSUMER: ui-web/src/shell-v0/state/conversationListStore.ts via ui-web/src/shell-v0/views/UnifiedChatView.ts fetchMessageIds automatically refreshes stored message ids after a streamed send"),
      Map.entry("GET /api/chat/runs/live", "STATUS: current live run roster"),
      Map.entry("GET /api/condition-recovery-index", "HEALTH: current recoverable conditions"),
      Map.entry("GET /api/condition-recovery-index/stream", "CONSUMER: ui-web/src/shell-v0/views/HealthSurface.ts ongoing recovery index subscription"),
      Map.entry("GET /api/conversations/encryption", "STATUS: conversation encryption/lock state"),
      Map.entry("GET /api/debug/commit-metadata", "STATUS: current running build identity"),
      Map.entry("GET /api/debug/dashboard", "STATUS: current engine/worker status dashboard"),
      Map.entry("GET /api/debug/effective-config", "STATUS: currently effective runtime configuration"),
      Map.entry("GET /api/debug/session-policies", "STATUS: current session policy projection"),
      Map.entry("GET /api/debug/state", "STATUS: current engine debug snapshot"),
      Map.entry("GET /api/diagnostic-channels/engine-log/stream", "CONSUMER: ui-web/src/shell-v0/views/LogSurface.ts [channel.endpoint=app-observability/src/main/java/io/justsearch/app/observability/diagnostic/EngineLogDiagnosticChannelCatalog.java#ENDPOINT] continuous DiagnosticChannel subscription"),
      Map.entry("GET /api/diagnostics/ingestion/summary", "CONSUMER: ui-web/src/shell-v0/components/IngestionSummary.ts periodic summary poll"),
      Map.entry("GET /api/diagnostics/traces", "CONSUMER: ui-web/src/shell-v0/views/BrainSurface.ts automatic diagnostics polling"),
      Map.entry("GET /api/feedback/capture", "CONSUMER: ui-web/src/shell-v0/views/SettingsSurface.ts feedback settings mount hydration"),
      Map.entry("GET /api/gpu/capabilities", "STATUS: current hardware accelerator capabilities (gpuCapabilitiesHandler)"),
      Map.entry("GET /api/health", "HEALTH: app health probe"),
      Map.entry("GET /api/health/events/stream", "CONSUMER: ui-web/src/shell-v0/views/HealthSurface.ts continuous health subscription"),
      Map.entry("GET /api/indexing-jobs/failed", "CONSUMER: ui-web/src/shell-v0/strategies/subscriptionStrategy.ts [endpoint=app-observability/src/main/java/io/justsearch/app/observability/indexing/FailedIndexingJobsResourceCatalog.java#ENDPOINT] via ui-web/src/shell-v0/components/ResourceView.ts automatic ONE_SHOT Resource hydration on mount"),
      Map.entry("GET /api/indexing-jobs/stream", "CONSUMER: ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.ts via ui-web/src/shell-v0/chrome/Shell.ts ongoing job subscription started on shell connect"),
      Map.entry("GET /api/indexing-roots/substrate", "CONSUMER: ui-web/src/shell-v0/views/LibrarySurface.ts automatic roots refresh on mount and status updates"),
      Map.entry("GET /api/indexing/failed-jobs", "CONSUMER: ui-web/src/shell-v0/views/HealthSurface.ts failed-job mount hydration"),
      Map.entry("GET /api/indexing/roots", "CONSUMER: ui-web/src/shell-v0/views/BrowseSurface.ts mount hydration via api/domains/indexing.ts"),
      Map.entry("GET /api/inference/encoders", "STATUS: currently loaded encoder runtime and device state (encoderRuntimeHandler)"),
      Map.entry("GET /api/inference/status", "STATUS: inference readiness/state"),
      Map.entry("GET /api/inference/transitions", "CONSUMER: ui-web/src/shell-v0/views/BrainSurface.ts automatic transition timeline poll"),
      Map.entry("GET /api/intent/stream", "STATUS: current backend navigation intent stream; the web shell now uses the multiplexed shell-events stream"),
      Map.entry("GET /api/knowledge/status", "STATUS: current knowledge/index state"),
      Map.entry("GET /api/mcp/token", "CONSUMER: scripts/jseval/lane-f/e-start-ready.mjs via scripts/jseval/lane-f/e-acquire.mjs via scripts/jseval/lane-f/e-run.mjs automatic MCP protocol bootstrap of an evaluation run"),
      Map.entry("GET /api/memory", "CONSUMER: ui-web/src/shell-v0/views/MemorySurface.ts automatic memory hydration on mount"),
      Map.entry("GET /api/messages/errors/{locale}", "CONSUMER: ui-web/src/i18n/errorCatalog.ts via ui-web/src/i18n.ts automatic bootstrap catalog hydration"),
      Map.entry("GET /api/messages/health-events/{locale}", "CONSUMER: ui-web/src/i18n/resourceCatalog.ts via ui-web/src/i18n.ts automatic bootstrap catalog hydration"),
      Map.entry("GET /api/messages/registry-operation/{locale}", "CONSUMER: ui-web/src/i18n/resourceCatalog.ts via ui-web/src/i18n.ts automatic bootstrap catalog hydration"),
      Map.entry("GET /api/messages/registry-resource/{locale}", "CONSUMER: ui-web/src/i18n/resourceCatalog.ts via ui-web/src/i18n.ts automatic bootstrap catalog hydration"),
      Map.entry("GET /api/messages/registry-surface/{locale}", "CONSUMER: ui-web/src/i18n/resourceCatalog.ts via ui-web/src/i18n.ts automatic bootstrap catalog hydration"),
      Map.entry("GET /api/messages/registry-workflow/{locale}", "CONSUMER: ui-web/src/i18n/resourceCatalog.ts via ui-web/src/i18n.ts automatic bootstrap catalog hydration"),
      Map.entry("GET /api/meta/routes", "CONSUMER: ui-web/src/shell-v0/views/ApiExplorerView.ts automatic explorer hydration on mount"),
      Map.entry("GET /api/metrics/gpu.memory.utilization.percent", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts automatic metric hydration during render"),
      Map.entry("GET /api/metrics/gpu.memory.utilization.percent/stream", "CONSUMER: ui-web/src/shell-v0/components/ResourceView.ts [resource.endpoint=app-observability/src/main/java/io/justsearch/app/observability/metrics/GpuMemoryUtilizationMetricResourceCatalog.java#ENDPOINT] continuous metric Resource subscription"),
      Map.entry("GET /api/metrics/gpu.utilization.percent", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts automatic metric hydration during render"),
      Map.entry("GET /api/metrics/gpu.utilization.percent/stream", "CONSUMER: ui-web/src/shell-v0/components/ResourceView.ts [resource.endpoint=app-observability/src/main/java/io/justsearch/app/observability/metrics/GpuUtilizationMetricResourceCatalog.java#ENDPOINT] continuous metric Resource subscription"),
      Map.entry("GET /api/metrics/worker.documents.indexed.rate_per_sec", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts automatic metric hydration during render"),
      Map.entry("GET /api/metrics/worker.documents.indexed.rate_per_sec/stream", "CONSUMER: ui-web/src/shell-v0/components/ResourceView.ts [resource.endpoint=app-observability/src/main/java/io/justsearch/app/observability/metrics/DocumentsIndexedRateMetricResourceCatalog.java#ENDPOINT] continuous metric Resource subscription"),
      Map.entry("GET /api/metrics/worker.job_queue.depth", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts automatic metric hydration during render"),
      Map.entry("GET /api/metrics/worker.job_queue.depth/stream", "CONSUMER: ui-web/src/shell-v0/components/ResourceView.ts [resource.endpoint=app-observability/src/main/java/io/justsearch/app/observability/metrics/JobQueueDepthMetricResourceCatalog.java#ENDPOINT] continuous metric Resource subscription"),
      Map.entry("GET /api/operation-history/stream", "CONSUMER: ui-web/src/shell-v0/components/ResourceView.ts [resource.endpoint=app-observability/src/main/java/io/justsearch/app/observability/operations/OperationHistoryResourceCatalog.java#ENDPOINT] continuous Activity Resource subscription"),
      Map.entry("GET /api/operation-history/{operationKey}", "STATUS: current recorded operation outcome"),
      Map.entry("GET /api/plugins/allowlist", "STATUS: current plugin trust allowlist snapshot (PluginVerificationController.handleList)"),
      Map.entry("GET /api/policy/effective", "STATUS: current resolved policy"),
      Map.entry("GET /api/policy/validate", "STATUS: current policy validation status (PolicyController.handleValidatePolicy)"),
      Map.entry("GET /api/presence", "STATUS: background-completion presence"),
      Map.entry("GET /api/registry/diagnostic-channels", "CONSUMER: ui-web/src/api/registry/DiagnosticChannelCatalogClient.ts via ui-web/src/i18n.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/operations", "CONSUMER: ui-web/src/api/registry/OperationCatalogClient.ts via ui-web/src/i18n.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/resources", "CONSUMER: ui-web/src/api/registry/ResourceCatalogClient.ts via ui-web/src/i18n.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/shapes", "CONSUMER: ui-web/src/api/registry/ConversationShapeCatalogClient.ts via ui-web/src/i18n.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/surfaces", "CONSUMER: ui-web/src/api/registry/SurfaceCatalogClient.ts via ui-web/src/i18n.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/witness", "STATUS: registry integrity witness"),
      Map.entry("GET /api/registry/workflows", "CONSUMER: ui-web/src/api/registry/WorkflowCatalogClient.ts via ui-web/src/shell-v0/views/UnifiedChatView.ts workflow picker catalog hydration on connect"),
      Map.entry("GET /api/runtime-context", "STATUS: current runtime context snapshot (RuntimeContextController.handleGet)"),
      Map.entry("GET /api/runtime-context/stream", "STATUS: current runtime context updates and heartbeat (RuntimeContextController.handleStream)"),
      Map.entry("GET /api/runtime/live", "HEALTH: runtime liveness probe (RuntimeProbeController)"),
      Map.entry("GET /api/runtime/manifest", "STATUS: current runtime manifest"),
      Map.entry("GET /api/runtime/manifest/stream", "STATUS: ongoing current runtime manifest/readiness updates (RuntimeManifestStreamController)"),
      Map.entry("GET /api/runtime/ready", "READINESS: runtime ready probe (RuntimeProbeController)"),
      Map.entry("GET /api/schemas/{name}", "CONSUMER: ui-web/src/api/registry/schemaFetcher.ts via ui-web/src/shell-v0/components/ResourceView.ts loads schemas during Resource mount hydration"),
      Map.entry("GET /api/settings/v2", "CONSUMER: ui-web/src/shell-v0/state/themeState.ts via ui-web/src/main.jsx automatic appearance bootstrap"),
      Map.entry("GET /api/shell-events/stream", "CONSUMER: ui-web/src/shell-v0/chrome/Shell.ts continuous multiplexed event subscription"),
      Map.entry("GET /api/status", "STATUS: current app/engine/worker state"),
      Map.entry("GET /api/telemetry/health", "HEALTH: telemetry pipeline health (TelemetryHealthController)"),
      Map.entry("GET /api/thread/{id}", "CONSUMER: ui-web/src/shell-v0/views/unifiedThreadClient.ts via ui-web/src/shell-v0/views/UnifiedChatView.ts automatic thread hydration on connect"),
      Map.entry("GET /api/ui/ready", "READINESS: current UI readiness report and probe"),
      Map.entry("GET /infra/capabilities", "READINESS: runtime contract capability negotiation"),
      Map.entry("GET /infra/capabilities/stream", "READINESS: ongoing runtime contract capability negotiation updates"),
      Map.entry("GET /v1/models", "STATUS: currently available OpenAI-compatible models"),
      Map.entry("HEAD /api/runtime/live", "HEALTH: runtime liveness probe (RuntimeProbeController)"),
      Map.entry("HEAD /api/runtime/ready", "READINESS: runtime ready probe (RuntimeProbeController)"),
      Map.entry("MCP justsearch_operation_outcome", "STATUS: current recorded operation outcome for MCP recovery polling after a lost response"),
      Map.entry("MCP justsearch_runtime_manifest", "STATUS: current runtime topology, readiness and capability manifest for MCP discovery clients"),
      Map.entry("MCP justsearch_status", "STATUS: current knowledge/indexing status"),
      Map.entry("OPTIONS /*", "READINESS: browser CORS preflight, generated automatically before cross-origin UI requests (ApiSecurityFilters.installCors)"),
      Map.entry("POST /api/action-ledger/events", "CONSUMER: ui-web/src/shell-v0/operations/ActionLedgerClient.ts automatic interaction/effect journal synchronization"),
      Map.entry("POST /api/chat/agent/tool-result", "CONSUMER: ui-web/src/shell-v0/commands/VirtualToolDispatcher.ts via ui-web/src/shell-v0/controllers/AgentSessionController.ts automatically posts the result of a tool call delivered by the agent stream"),
      Map.entry("POST /api/chat/agent/virtual-operations", "CONSUMER: ui-web/src/main.jsx catalog change listener publishes the virtual-operation catalog automatically"),
      Map.entry("POST /api/chat/runs/{runId}/observe", "CONSUMER: ui-web/src/shell-v0/controllers/AgentSessionController.ts via ui-web/src/shell-v0/views/UnifiedChatView.ts reattachActiveRunOnLoad automatically reattaches a background run discovered on load"),
      Map.entry("POST /api/conversations/encryption/auto-lock", "CONSUMER: ui-web/src/shell-v0/utils/autoLock.ts idle timeout automatically locks conversations"),
      Map.entry("POST /api/knowledge/collection-facet", "CONSUMER: ui-web/src/shell-v0/state/searchState.ts via ui-web/src/shell-v0/views/LibrarySurface.ts automatic Library facet hydration on mount and corpus growth"),
      Map.entry("POST /api/library/path-label", "CONSUMER: ui-web/src/shell-v0/hooks/resolvePathLazy.ts via ui-web/src/shell-v0/views/LibrarySurface.ts automatic row-label hydration on mount and streamed updates"),
      Map.entry("POST /api/ui/ready", "READINESS: current UI readiness report and probe"));

  /** Also used by the production MCP tool table; protocol envelopes remain separately declared. */
  public static void require(String operation) {
    String reason = REASONS.get(operation);
    if (reason == null || !reason.matches("(?:CONSUMER|STATUS|HEALTH|READINESS): \\S.*")) {
      throw new IllegalStateException("Observation needs an unattended consumer or status/health/readiness justification: " + operation);
    }
  }

  /** MCP has a separate registry; check both directions against the production tool declarations. */
  public static void validateMcp(Map<String, Boolean> userActivity) {
    userActivity.forEach((name, action) -> {
      String operation = "MCP " + name;
      if (!action) require(operation);
      else if (REASONS.containsKey(operation)) {
        throw new IllegalStateException("Remove obsolete observation justification: " + operation);
      }
    });
    for (String operation : REASONS.keySet()) {
      if (operation.startsWith("MCP ") && !userActivity.containsKey(operation.substring(4))) {
        throw new IllegalStateException("Remove justification for unregistered observation: " + operation);
      }
    }
  }

  enum Group { SUBSTRATE, RUNTIME, KNOWLEDGE, TELEMETRY }

  private static final Map<Group, Set<String>> OPTIONAL_GROUPS = Map.ofEntries(
      Map.entry(Group.SUBSTRATE, Set.of(
          "GET /api/boot/phases",
          "GET /api/action-ledger",
          "GET /api/action-ledger/stream",
          "GET /api/advisory/authorization-pending/stream",
          "GET /api/advisory/health-recoverable/stream",
          "GET /api/advisory/operation-completed/stream",
          "GET /api/agent/hard-stop",
          "GET /api/authorizations/grants",
          "GET /api/authorizations/pending/{id}",
          "GET /api/condition-recovery-index",
          "GET /api/condition-recovery-index/stream",
          "GET /api/diagnostic-channels/engine-log/stream",
          "GET /api/feedback/capture",
          "GET /api/health/events/stream",
          "GET /api/indexing-jobs/stream",
          "GET /api/intent/stream",
          "GET /api/memory",
          "GET /api/metrics/gpu.memory.utilization.percent",
          "GET /api/metrics/gpu.memory.utilization.percent/stream",
          "GET /api/metrics/gpu.utilization.percent",
          "GET /api/metrics/gpu.utilization.percent/stream",
          "GET /api/metrics/worker.documents.indexed.rate_per_sec",
          "GET /api/metrics/worker.documents.indexed.rate_per_sec/stream",
          "GET /api/metrics/worker.job_queue.depth",
          "GET /api/metrics/worker.job_queue.depth/stream",
          "GET /api/operation-history/stream",
          "GET /api/operation-history/{operationKey}",
          "GET /api/presence",
          "GET /api/registry/diagnostic-channels",
          "GET /api/registry/operations",
          "GET /api/registry/resources",
          "GET /api/registry/shapes",
          "GET /api/registry/surfaces",
          "GET /api/registry/witness",
          "GET /api/registry/workflows",
          "GET /api/runtime-context",
          "GET /api/runtime-context/stream",
          "GET /api/shell-events/stream",
          "GET /api/thread/{id}",
          "GET /infra/capabilities",
          "GET /infra/capabilities/stream",
          "POST /api/action-ledger/events")),
      Map.entry(Group.RUNTIME, Set.of(
          "GET /.well-known/justsearch/manifest.json",
          "GET /api/runtime/live",
          "GET /api/runtime/manifest",
          "GET /api/runtime/manifest/stream",
          "GET /api/runtime/ready",
          "HEAD /api/runtime/live",
          "HEAD /api/runtime/ready")),
      Map.entry(Group.KNOWLEDGE, Set.of(
          "GET /api/knowledge/status",
          "POST /api/knowledge/collection-facet")),
      Map.entry(Group.TELEMETRY, Set.of("GET /api/telemetry/health")));

  static void validate(Javalin app) {
    validate(app, Set.of(Group.values()));
  }

  static void validate(Javalin app, Set<Group> enabledGroups) {
    validateDeclaredRoutes(app);
    Set<String> registered = new HashSet<>();
    for (var entry : app.unsafeConfig().pvt.internalRouter.allHttpHandlers()) {
      var endpoint = entry.getEndpoint();
      if (endpoint.getMethod().isHttpMethod()) registered.add(endpoint.getMethod() + " " + endpoint.getPath());
    }
    for (String operation : REASONS.keySet()) {
      boolean enabled = OPTIONAL_GROUPS.entrySet().stream()
          .noneMatch(group -> group.getValue().contains(operation) && !enabledGroups.contains(group.getKey()));
      if (enabled && !operation.startsWith("MCP ") && !registered.contains(operation)) {
        throw new IllegalStateException("Remove justification for unregistered observation: " + operation);
      }
    }
  }

  /** Individual route-family fixtures use this half; production always checks full closure. */
  static void validateDeclaredRoutes(Javalin app) {
    for (var entry : app.unsafeConfig().pvt.internalRouter.allHttpHandlers()) {
      var endpoint = entry.getEndpoint();
      if (!endpoint.getMethod().isHttpMethod()) continue;
      String operation = endpoint.getMethod() + " " + endpoint.getPath();
      if (endpoint.getRoles().contains(ActivityRole.OBSERVATION)) {
        require(operation);
      } else if (REASONS.containsKey(operation)) {
        throw new IllegalStateException("Remove obsolete observation justification: " + operation);
      }
    }
  }
}
