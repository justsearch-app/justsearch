/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.agent.api.registry.Operation;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 585 §B.5 (Hybrid C, the tools-axis cut): the agent capability's tool inventory + the
 * tempdoc 508 FE-published virtual-operations sidecar, lifted out of {@link AgentController}. This
 * cluster is about the agent's tool catalog and the {@code VirtualOperationStore} (a single FE→agent
 * tool bridge) — **not** the run loop — so it lifts cleanly into its own file. A new tool-inventory /
 * virtual-operation concern attaches here, never on the run/control core.
 *
 * <p>Behaviour-preserving: the four handlers + the {@code hasAgentAudience}/{@code operationToToolMap}/
 * {@code toolKind} helpers moved verbatim. {@code completeVirtualToolCall} (delivering a virtual tool
 * result back to the blocking loop) is a control method on {@link AgentService}, so this controller
 * holds the full service supplier (the read narrowing is realized in the sibling
 * {@code AgentSessionController}).
 */
final class AgentToolsController {
  private static final Logger LOG = LoggerFactory.getLogger(AgentToolsController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Supplier<AgentService> agentServiceSupplier;
  /** Tempdoc 508 §11.5 / §13.5 — FE-projected virtual operations store (nullable). */
  private final io.justsearch.app.services.registry.emitter.VirtualOperationStore virtualOperationStore;

  AgentToolsController(
      Supplier<AgentService> agentServiceSupplier,
      io.justsearch.app.services.registry.emitter.VirtualOperationStore virtualOperationStore) {
    this.agentServiceSupplier = agentServiceSupplier;
    this.virtualOperationStore = virtualOperationStore;
  }

  /** Resolves the live agent service. Always re-fetches so late-bound updates surface. */
  private AgentService agentService() {
    return agentServiceSupplier.get();
  }

  /**
   * GET /api/chat/agent/tools — the tools the model is OFFERED.
   *
   * <p>Tempdoc 876 §B.1: the source is {@code offeredOperations()} — the emitter's own filter chain
   * (executor / audience / evaluated availability) — not the raw {@code availableOperations()}
   * catalog this read used before. The FE calls this the agent's authority space, so a tool the
   * emitter withholds must be absent here; showing a wider set made the panel a second, disagreeing
   * account of the same fact.
   */
  void handleListTools(Context ctx) {
    List<Map<String, Object>> coreTools = agentService().offeredOperations().stream()
        .map(AgentToolsController::operationToToolMap)
        .toList();
    // §11.5 / §13.5 — append FE-published virtual operations. Conflicts (same wire-name) resolve in
    // favor of core (silent drop) — the same rule the emit path applies, now the same code.
    List<Map<String, Object>> tools;
    if (virtualOperationStore != null) {
      List<Map<String, Object>> virtual =
          io.justsearch.app.services.registry.emitter.VirtualOperationStore.withoutCollisions(
              virtualOperationStore.snapshot(), coreWireNames(coreTools));
      if (virtual.isEmpty()) {
        tools = coreTools;
      } else {
        List<Map<String, Object>> merged = new java.util.ArrayList<>(coreTools);
        merged.addAll(virtual);
        tools = merged;
      }
    } else {
      tools = coreTools;
    }
    ctx.json(Map.of("tools", tools, "available", agentService().isAvailable()));
  }

  /**
   * The wire names the projected core tools occupy. This projection's name field is {@code name}
   * (flat), not the OpenAI {@code function.name} envelope the virtual store uses, so the shared
   * {@code VirtualOperationStore.wireNames} helper does not apply to it.
   */
  private static Set<String> coreWireNames(List<Map<String, Object>> coreTools) {
    Set<String> names = new java.util.HashSet<>();
    for (Map<String, Object> t : coreTools) {
      Object name = t.get("name");
      if (name != null) names.add(name.toString());
    }
    return names;
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 — POST /api/chat/agent/virtual-operations
   * — FE publishes its projected virtual operations (TRUSTED+/CORE
   * shell commands as agent tools). Body: {tools: [...]} where each
   * entry matches the OpenAI tools envelope.
   */
  void handleVirtualOperationsPublish(Context ctx) {
    if (virtualOperationStore == null) {
      ctx.status(503).json(Map.of("error", "virtual-operations store not configured"));
      return;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
      Object rawTools = body.get("tools");
      if (!(rawTools instanceof List<?> rawList)) {
        ctx.status(400).json(Map.of("error", "body.tools must be an array"));
        return;
      }
      // Tempdoc 508 §13 critical-analysis Phase C — backend audience
      // validation. The FE's serializer filters to entries with
      // Audience.AGENT, but the backend must not trust that. Each
      // entry must carry an explicit `audience` array including
      // "AGENT" (case-insensitive). Reject entries that lack it.
      List<Map<String, Object>> typed = new java.util.ArrayList<>(rawList.size());
      for (int i = 0; i < rawList.size(); i++) {
        Object entry = rawList.get(i);
        if (!(entry instanceof Map<?, ?> m)) {
          ctx.status(400).json(Map.of("error", "body.tools[" + i + "] must be an object"));
          return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> casted = (Map<String, Object>) m;
        if (!hasAgentAudience(casted)) {
          ctx.status(400).json(Map.of(
              "error",
              "body.tools[" + i + "] must declare audience: [\"AGENT\"] "
                  + "(possibly with USER) to be agent-visible. "
                  + "Use FE-side filtering before publishing."));
          return;
        }
        typed.add(casted);
      }
      virtualOperationStore.publish(typed);
      ctx.json(Map.of("ok", true, "count", typed.size()));
    } catch (Exception e) {
      LOG.error("Failed to publish virtual operations", e);
      ctx.status(400).json(Map.of("error", e.getMessage()));
    }
  }

  /**
   * Tempdoc 508 §13 critical-analysis Phase C — verify a published
   * tool entry declares {@code audience: ["AGENT"]} (or includes
   * AGENT among multiple audiences). Case-insensitive on the
   * values; the field name is exact ("audience"). Missing field or
   * audience array not containing AGENT → return false; caller
   * rejects with 400. Package-private for direct unit-test access.
   */
  static boolean hasAgentAudience(Map<String, Object> tool) {
    Object audience = tool.get("audience");
    if (!(audience instanceof List<?> list)) {
      return false;
    }
    for (Object v : list) {
      if (v == null) continue;
      if ("AGENT".equalsIgnoreCase(v.toString())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — POST /api/chat/agent/tool-result
   * — the FE delivers the result of a {@code vop_*} virtual tool back
   * to the blocking agent loop. Body: {@code {sessionId, callId,
   * success, output?, errorDetail?}}. Returns 404 if no pending call
   * exists for that (session, callId) tuple — the agent timed out,
   * was cancelled, or the callId is stale.
   */
  void handleVirtualToolResult(Context ctx) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = MAPPER.readValue(ctx.body(), Map.class);
      if (StoreIdRequest.rejectBody(ctx, body)) return;
      Object sessionIdRaw = body.get("sessionId");
      Object callIdRaw = body.get("callId");
      Object successRaw = body.get("success");
      if (!(sessionIdRaw instanceof String sessionId) || sessionId.isBlank()) {
        ctx.status(400).json(Map.of("error", "body.sessionId is required"));
        return;
      }
      if (!(callIdRaw instanceof String callId) || callId.isBlank()) {
        ctx.status(400).json(Map.of("error", "body.callId is required"));
        return;
      }
      if (!(successRaw instanceof Boolean success)) {
        ctx.status(400).json(Map.of("error", "body.success must be a boolean"));
        return;
      }
      String output = body.get("output") instanceof String s ? s : null;
      String errorDetail = body.get("errorDetail") instanceof String s ? s : null;
      boolean delivered =
          agentService().completeVirtualToolCall(sessionId, callId, success, output, errorDetail);
      if (!delivered) {
        ctx.status(404).json(Map.of(
            "error",
            "no pending virtual tool call for session/" + sessionId + "/" + callId));
        return;
      }
      ctx.json(Map.of("ok", true));
    } catch (Exception e) {
      LOG.error("Failed to deliver virtual tool result", e);
      ctx.status(400).json(Map.of("error", e.getMessage()));
    }
  }

  /**
   * GET /api/chat/agent/virtual-operations — read the currently-
   * stored virtual operations. Useful for debugging and tests.
   */
  void handleVirtualOperationsRead(Context ctx) {
    if (virtualOperationStore == null) {
      ctx.json(Map.of("tools", List.of()));
      return;
    }
    ctx.json(Map.of("tools", virtualOperationStore.snapshot()));
  }

  /**
   * Projects an {@link Operation} onto the {@code GET /api/chat/agent/tools} JSON shape so
   * existing FE consumers (and contract tests) see no wire change post-Phase-10. Per
   * tempdoc 429 §C.G: name uses the {@code wireName} when present (e.g.,
   * {@code search_index}); description emits the i18n key (the FE resolves it per-locale — via
   * {@code /api/messages/registry-operation/{locale}} for {@code ops.*} keys and the sibling
   * {@code registry-workflow} catalog for a projected workflow's {@code registry-workflow.*} key,
   * both of which the FE resource catalog merges; the EMITTER resolves server-side instead, because
   * a model cannot resolve anything — tempdoc 876 §B.3); safetyLevel translates from
   * the new {@link io.justsearch.agent.api.registry.RiskTier} to the legacy enum string.
   */
  private static Map<String, Object> operationToToolMap(Operation op) {
    String name = io.justsearch.agent.api.registry.OperationCatalog.toWireName(op.id());
    String description = op.presentation().descriptionKey().value();
    return Map.of(
        "name", name,
        "description", description,
        "risk", op.policy().risk().name().toLowerCase(Locale.ROOT),
        "supportsUndo", op.policy().undoSupported(),
        "parameterSchema", op.intf().inputs(),
        // Tempdoc 560 WS5 (the one window): attribute each tool in the agent's single inventory by
        // its provenance — tier (CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN), the contributor id, and
        // the kind (agent operation vs. external MCP tool vs. projected workflow). Corrected in 876
        // §A.0: the agent view is NOT "core + agent-tools + MCP" — OperationCatalogComposition
        // .deriveAndPartition routes CORE-owner operations to the OTHER catalog, so what reaches
        // here is agent-tools + MCP + projected workflows. And since 876 §B.1 the set is the
        // emitter's OFFERING (availability-filtered), not the raw catalog partition — so this is
        // where a consumer sees "where did this tool the model can actually call come from".
        "tier", op.provenance().tier().name(),
        "provenance", op.provenance().contributorId(),
        "kind", toolKind(op));
  }

  /**
   * Tempdoc 560 WS5 — classify a merged-catalog tool by its source for the one-window inventory.
   * MCP-host tools carry the {@code vendor.mcphost.*} ref namespace; projected workflows carry the
   * {@code core.workflow-*} convention; everything else is a core/agent operation.
   */
  private static String toolKind(Operation op) {
    String id = op.id().value();
    if (id.startsWith("vendor.mcphost.")) {
      return "mcp";
    }
    if (id.startsWith("core.workflow-")) {
      return "workflow";
    }
    return "operation";
  }
}
