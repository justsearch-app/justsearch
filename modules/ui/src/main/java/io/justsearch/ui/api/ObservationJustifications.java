/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import java.util.Map;

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
      Map.entry("GET /api/action-ledger", "CONSUMER: ui-web/src/shell-v0/operations/ActionLedgerClient.ts hydrateLedger on bootstrap and reconnect"),
      Map.entry("GET /api/action-ledger/stream", "CONSUMER: ui-web/src/shell-v0/operations/ActionLedgerClient.ts continuous ledger subscription"),
      Map.entry("GET /api/advisory/authorization-pending/stream", "CONSUMER: ui-web/src/shell-v0/streaming/shellEventStreamIds.ts ongoing advisory subscription"),
      Map.entry("GET /api/advisory/health-recoverable/stream", "CONSUMER: ui-web/src/shell-v0/streaming/shellEventStreamIds.ts ongoing advisory subscription"),
      Map.entry("GET /api/advisory/operation-completed/stream", "CONSUMER: ui-web/src/shell-v0/streaming/shellEventStreamIds.ts ongoing advisory subscription"),
      Map.entry("GET /api/agent/hard-stop", "STATUS: current agent hard-stop state (HardStopController.handleGet)"),
      Map.entry("GET /api/ai/install/manifest", "CONSUMER: ui-web/src/shell-v0/views/BrainSurface.ts bootstrap install catalog hydration"),
      Map.entry("GET /api/ai/install/plan-preview", "CONSUMER: ui-web/src/shell-v0/state/installComponents.ts and utils/aiInstallPoll.ts automatic plan hydration/poll"),
      Map.entry("GET /api/ai/install/status", "STATUS: AI installation progress; ui-web/src/shell-v0/state/installComponents.ts poll"),
      Map.entry("GET /api/ai/models/status", "STATUS: current AI model availability (AiModelsController.handleGetStatus)"),
      Map.entry("GET /api/ai/packs/installed", "STATUS: installed AI pack inventory; ui-web/src/api/domains/packs.ts automatic install hydration"),
      Map.entry("GET /api/ai/packs/status", "STATUS: AI pack availability; ui-web/src/shell-v0/utils/aiInstallPoll.ts"),
      Map.entry("GET /api/ai/runtime/status", "STATUS: current AI runtime state; ui-web/src/shell-v0/utils/aiInstallPoll.ts"),
      Map.entry("GET /api/authorizations/grants", "CONSUMER: ui-web/src/shell-v0/views/SettingsSurface.ts grant-list mount hydration"),
      Map.entry("GET /api/authorizations/pending/{id}", "STATUS: current pending authorization; ui-web/src/shell-v0/operations/OperationClient.ts automatically resolves a 428 response"),
      Map.entry("GET /api/boot/phases", "READINESS: boot phase progress; ui-web/src/shell-v0/components/BootPhasesPanel.ts polling"),
      Map.entry("GET /api/chat/agent/tools", "CONSUMER: ui-web/src/shell-v0/controllers/AgentSessionController.ts startPolling/checkAvailability hydrates available tools"),
      Map.entry("GET /api/chat/approval", "STATUS: pending approval state; AgentSessionController.ts approval lookup after stream events"),
      Map.entry("GET /api/chat/conversations", "CONSUMER: ui-web/src/shell-v0/state/conversationListStore.ts bootstrap conversation-list hydration"),
      Map.entry("GET /api/chat/conversations/{sessionId}/history", "CONSUMER: ui-web/src/shell-v0/state/conversationListStore.ts fetchMessageIds automatically refreshes stored message ids after a streamed send"),
      Map.entry("GET /api/chat/runs/live", "STATUS: current live run roster; ui-web/src/shell-v0/controllers/liveRuns.ts discovers and reattaches background runs"),
      Map.entry("GET /api/chat/sessions", "CONSUMER: ui-web/src/shell-v0/components/RetrospectivePanel.ts loadActive hydrates the mounted sessions drawer"),
      Map.entry("GET /api/condition-recovery-index", "HEALTH: current recoverable conditions; ui-web/src/shell-v0/views/HealthSurface.ts mount hydration"),
      Map.entry("GET /api/condition-recovery-index/stream", "CONSUMER: ui-web/src/shell-v0/views/HealthSurface.ts ongoing recovery index subscription"),
      Map.entry("GET /api/conversations/encryption", "STATUS: conversation encryption/lock state; ui-web/src/shell-v0/utils/autoLock.ts bootstrap"),
      Map.entry("GET /api/debug/commit-metadata", "STATUS: current running build identity; scripts/jseval/jseval/index_identity.py runtime provenance sampling"),
      Map.entry("GET /api/debug/dashboard", "STATUS: current engine/worker status dashboard; debug/dashboard.html refreshes its status snapshot every five seconds"),
      Map.entry("GET /api/debug/effective-config", "STATUS: currently effective runtime configuration; scripts/dev/dev-runner.cjs readiness/provenance probes"),
      Map.entry("GET /api/debug/session-policies", "STATUS: current session policy projection; scripts/jseval/jseval/manifest.py automatic evaluation manifest collection"),
      Map.entry("GET /api/debug/state", "STATUS: current engine debug snapshot; ui/src/main/resources/debug/dashboard.html refreshes every five seconds"),
      Map.entry("GET /api/diagnostic-channels/engine-log/stream", "CONSUMER: ui-web/src/shell-v0/views/LogSurface.ts subscribes via the core.engine-log DiagnosticChannel catalog endpoint"),
      Map.entry("GET /api/diagnostics/ingestion/summary", "CONSUMER: ui-web/src/shell-v0/components/IngestionSummary.ts periodic summary poll"),
      Map.entry("GET /api/diagnostics/traces", "CONSUMER: ui-web/src/shell-v0/views/BrainSurface.ts automatic diagnostics polling"),
      Map.entry("GET /api/feedback/capture", "CONSUMER: ui-web/src/shell-v0/views/SettingsSurface.ts feedback settings mount hydration"),
      Map.entry("GET /api/gpu/capabilities", "STATUS: current hardware accelerator capabilities (gpuCapabilitiesHandler)"),
      Map.entry("GET /api/health", "HEALTH: app health probe; scripts/dev/dev-runner.cjs readiness polling"),
      Map.entry("GET /api/health/events/stream", "CONSUMER: ui-web/src/shell-v0/streaming/MultiplexedStream.ts and api/domains/health.ts consume ongoing health events"),
      Map.entry("GET /api/indexing-jobs/failed", "CONSUMER: ui-web/src/shell-v0/components/FailedJobsDrawer.ts initial hydration and refresh subscription"),
      Map.entry("GET /api/indexing-jobs/failed/by-prefix", "CONSUMER: ui-web/src/shell-v0/components/FailedJobsDrawer.ts folder-scoped mount hydration"),
      Map.entry("GET /api/indexing-jobs/stream", "CONSUMER: ui-web/src/shell-v0/substrates/tasks/indexingJobsBridge.ts ongoing job subscription"),
      Map.entry("GET /api/indexing-roots/substrate", "CONSUMER: ui-web/src/shell-v0/state/otherSources.ts automatic roots refresh"),
      Map.entry("GET /api/indexing/failed-jobs", "CONSUMER: ui-web/src/shell-v0/views/HealthSurface.ts failed-job mount hydration"),
      Map.entry("GET /api/indexing/roots", "CONSUMER: ui-web/src/shell-v0/views/BrowseSurface.ts mount hydration via api/domains/indexing.ts"),
      Map.entry("GET /api/inference/encoders", "STATUS: currently loaded encoder runtime and device state (encoderRuntimeHandler)"),
      Map.entry("GET /api/inference/status", "STATUS: inference readiness/state; ui-web/src/shell-v0/utils/inferencePoll.ts"),
      Map.entry("GET /api/inference/transitions", "CONSUMER: ui-web/src/shell-v0/views/BrainSurface.ts automatic transition timeline poll"),
      Map.entry("GET /api/intent/stream", "CONSUMER: ui-web/src/api/intent/bootIntentStreamBridge.ts boot intent subscription"),
      Map.entry("GET /api/knowledge/status", "STATUS: current knowledge/index state; ui-web/src/shell-v0/state/enrichmentCoverage.ts refresh"),
      Map.entry("GET /api/mcp/token", "CONSUMER: scripts/jseval/lane-f/e-start-ready.mjs and MCP clients fetch the session token during automatic protocol bootstrap"),
      Map.entry("GET /api/memory", "CONSUMER: ui-web/src/shell-v0/state/readinessNotice.ts automatically loads memory before offering readiness advice"),
      Map.entry("GET /api/messages/errors/{locale}", "CONSUMER: ui-web/src/i18n.ts, i18n/errorCatalog.ts and i18n/resourceCatalog.ts bootstrap/render message catalog hydration"),
      Map.entry("GET /api/messages/health-events/{locale}", "CONSUMER: ui-web/src/i18n.ts, i18n/errorCatalog.ts and i18n/resourceCatalog.ts bootstrap/render message catalog hydration"),
      Map.entry("GET /api/messages/registry-operation/{locale}", "CONSUMER: ui-web/src/i18n.ts, i18n/errorCatalog.ts and i18n/resourceCatalog.ts bootstrap/render message catalog hydration"),
      Map.entry("GET /api/messages/registry-resource/{locale}", "CONSUMER: ui-web/src/i18n.ts, i18n/errorCatalog.ts and i18n/resourceCatalog.ts bootstrap/render message catalog hydration"),
      Map.entry("GET /api/messages/registry-surface/{locale}", "CONSUMER: ui-web/src/i18n.ts, i18n/errorCatalog.ts and i18n/resourceCatalog.ts bootstrap/render message catalog hydration"),
      Map.entry("GET /api/messages/registry-workflow/{locale}", "CONSUMER: ui-web/src/i18n.ts, i18n/errorCatalog.ts and i18n/resourceCatalog.ts bootstrap/render message catalog hydration"),
      Map.entry("GET /api/meta/routes", "CONSUMER: ui-web/src/shell-v0/plugin-api/CorePlugin.ts hydrates core API-explorer route metadata"),
      Map.entry("GET /api/metrics/gpu.memory.utilization.percent", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts hydrates metric snapshots and subscriptionStrategy.ts follows the matching metric Resource catalog stream"),
      Map.entry("GET /api/metrics/gpu.memory.utilization.percent/stream", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts hydrates metric snapshots and subscriptionStrategy.ts follows the matching metric Resource catalog stream"),
      Map.entry("GET /api/metrics/gpu.utilization.percent", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts hydrates metric snapshots and subscriptionStrategy.ts follows the matching metric Resource catalog stream"),
      Map.entry("GET /api/metrics/gpu.utilization.percent/stream", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts hydrates metric snapshots and subscriptionStrategy.ts follows the matching metric Resource catalog stream"),
      Map.entry("GET /api/metrics/worker.documents.indexed.rate_per_sec", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts hydrates metric snapshots and subscriptionStrategy.ts follows the matching metric Resource catalog stream"),
      Map.entry("GET /api/metrics/worker.documents.indexed.rate_per_sec/stream", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts hydrates metric snapshots and subscriptionStrategy.ts follows the matching metric Resource catalog stream"),
      Map.entry("GET /api/metrics/worker.job_queue.depth", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts hydrates metric snapshots and subscriptionStrategy.ts follows the matching metric Resource catalog stream"),
      Map.entry("GET /api/metrics/worker.job_queue.depth/stream", "CONSUMER: ui-web/src/shell-v0/views/HealthLitView.ts hydrates metric snapshots and subscriptionStrategy.ts follows the matching metric Resource catalog stream"),
      Map.entry("GET /api/operation-history/stream", "CONSUMER: ui-web/src/shell-v0/views/ActivitySurface.ts mounts core.operation-history; subscriptionStrategy.ts subscribes to its catalog endpoint"),
      Map.entry("GET /api/operation-history/{operationKey}", "STATUS: current recorded operation outcome; LibrarySurface.ts loadGapDecision polls every four seconds"),
      Map.entry("GET /api/plugins/allowlist", "CONSUMER: ui-web/src/shell-v0/views/SettingsSurface.ts hydrates plugin allowlist on mount"),
      Map.entry("GET /api/policy/effective", "STATUS: current resolved policy; ui-web/src/shell-v0/views/BrainSurface.ts automatic policy hydration"),
      Map.entry("GET /api/policy/validate", "STATUS: current policy validation status (PolicyController.handleValidatePolicy)"),
      Map.entry("GET /api/presence", "STATUS: background-completion presence; ui-web/src/shell-v0/controllers/AgentSessionController.ts presence timer"),
      Map.entry("GET /api/registry/diagnostic-channels", "CONSUMER: ui-web/src/api/registry/DiagnosticChannelCatalogClient.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/operations", "CONSUMER: ui-web/src/api/registry/OperationCatalogClient.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/resources", "CONSUMER: ui-web/src/api/registry/ResourceCatalogClient.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/shapes", "CONSUMER: ui-web/src/api/registry/ConversationShapeCatalogClient.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/surfaces", "CONSUMER: ui-web/src/api/registry/SurfaceCatalogClient.ts bootstrap catalog hydration"),
      Map.entry("GET /api/registry/witness", "STATUS: registry integrity witness; ui-web/src/shell-v0/views/SettingsSurface.ts hydration"),
      Map.entry("GET /api/registry/workflows", "CONSUMER: ui-web/src/api/registry/WorkflowCatalogClient.ts bootstrap/recovery catalog hydration"),
      Map.entry("GET /api/runtime-context", "STATUS: current runtime context snapshot (RuntimeContextController.handleGet)"),
      Map.entry("GET /api/runtime-context/stream", "STATUS: current runtime context updates and heartbeat (RuntimeContextController.handleStream)"),
      Map.entry("GET /api/runtime/live", "HEALTH: runtime liveness probe (RuntimeProbeController)"),
      Map.entry("GET /api/runtime/manifest", "STATUS: current runtime manifest; scripts/dev/dev-runner.cjs and ui-web/src/api/http.ts probes"),
      Map.entry("GET /api/runtime/manifest/stream", "STATUS: ongoing current runtime manifest/readiness updates (RuntimeManifestStreamController)"),
      Map.entry("GET /api/runtime/ready", "READINESS: runtime ready probe (RuntimeProbeController)"),
      Map.entry("GET /api/schemas/{name}", "CONSUMER: ui-web/src/api/registry/schemaFetcher.ts loads schemas during catalog/render hydration"),
      Map.entry("GET /api/settings/v2", "CONSUMER: ui-web/src/shell-v0/state/themeState.ts and uiModeState.ts settings bootstrap hydration"),
      Map.entry("GET /api/shell-events/stream", "CONSUMER: ui-web/src/shell-v0/chrome/Shell.ts and api/intent/bootIntentStreamBridge.ts continuous shell event subscription"),
      Map.entry("GET /api/status", "STATUS: current app/engine/worker state; ui-web/src/shell-v0/utils/statusPoll.ts"),
      Map.entry("GET /api/telemetry/health", "HEALTH: telemetry pipeline health (TelemetryHealthController)"),
      Map.entry("GET /api/thread/{id}", "CONSUMER: ui-web/src/shell-v0/state/conversationListStore.ts thread hydration after conversation discovery"),
      Map.entry("GET /api/ui/ready", "READINESS: ui-web/src/api/domains/status.ts automatic UI readiness report and probe"),
      Map.entry("GET /infra/capabilities", "READINESS: runtime contract capability negotiation; ui-web/src/shell-v0/handshake/CapabilitiesHandshake.ts bootstrap"),
      Map.entry("GET /infra/capabilities/stream", "CONSUMER: ui-web/src/api/contract/contractEventsBridge.ts ongoing capability update subscription"),
      Map.entry("GET /v1/models", "STATUS: currently available OpenAI-compatible models; scripts/jseval/jseval/backend.py readiness discovery"),
      Map.entry("HEAD /api/runtime/live", "HEALTH: runtime liveness probe (RuntimeProbeController)"),
      Map.entry("HEAD /api/runtime/ready", "READINESS: runtime ready probe (RuntimeProbeController)"),
      Map.entry("MCP justsearch_operation_outcome", "STATUS: current recorded operation outcome for MCP recovery polling after a lost response"),
      Map.entry("MCP justsearch_runtime_manifest", "STATUS: current runtime topology, readiness and capability manifest for MCP discovery clients"),
      Map.entry("MCP justsearch_status", "STATUS: current knowledge/indexing status; scripts/prod/justsearch-mcp/server.mjs and MCP monitoring clients"),
      Map.entry("OPTIONS /*", "CONSUMER: browser CORS preflight, generated automatically before cross-origin UI requests (ApiSecurityFilters.installCors)"),
      Map.entry("POST /api/action-ledger/events", "CONSUMER: ui-web/src/shell-v0/operations/ActionLedgerClient.ts automatic interaction/effect journal synchronization"),
      Map.entry("POST /api/chat/agent/tool-result", "CONSUMER: ui-web/src/shell-v0/commands/VirtualToolDispatcher.ts automatically posts the dispatched tool result"),
      Map.entry("POST /api/chat/agent/virtual-operations", "CONSUMER: ui-web/src/main.jsx catalog change listener publishes the virtual-operation catalog automatically"),
      Map.entry("POST /api/chat/runs/{runId}/observe", "CONSUMER: ui-web/src/shell-v0/controllers/AgentSessionController.ts attachToRun automatically reattaches background runs discovered during render"),
      Map.entry("POST /api/conversations/encryption/auto-lock", "CONSUMER: ui-web/src/shell-v0/utils/autoLock.ts idle timeout automatically locks conversations"),
      Map.entry("POST /api/ui/ready", "READINESS: ui-web/src/api/domains/status.ts automatic UI readiness report and probe"));

  /** Also used by the production MCP tool table; protocol envelopes remain separately declared. */
  public static void require(String operation) {
    String reason = REASONS.get(operation);
    if (reason == null || !reason.matches("(?:CONSUMER|STATUS|HEALTH|READINESS): \\S.*")) {
      throw new IllegalStateException("Observation needs an unattended consumer or status/health/readiness justification: " + operation);
    }
  }

  static void validate(Javalin app) {
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
