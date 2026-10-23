/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import io.justsearch.app.api.operations.OperationKeys;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 561 P-A/P-B — projects the agent loop's tool activity from the ONE durable record
 * ({@code AgentRunStore}) into the unified action ledger, so the ledger's agent rows (the agent
 * History + workspace Timeline source) DERIVE FROM the same record the unified thread reads. This
 * replaces the former independent operation-path write of agent rows (now suppressed at the
 * {@code OperationHistoryStore} fan-in for {@code TransportTag.AGENT_LOOP}), eliminating the
 * two-authorities divergence: the thread, History, and Timeline can no longer disagree about a run's
 * tool activity because they all derive from {@code AgentRunStore}.
 *
 * <p>Listener seam (wired in app-services, which depends on both modules):
 * {@code agentRunStore.addEventListener(projector::onEvent)}. The projector correlates each tool's
 * {@code callId} to its {@code toolName} (carried on the earlier {@code tool_call_proposed} /
 * {@code tool_exec_started} events) and, on {@code tool_exec_completed}, emits the
 * {@link ActionEvent.Operation} row via the one projection authority ({@link ActionLedgerProjection}).
 */
public final class AgentRunLedgerProjector {

  private static final Logger LOG = LoggerFactory.getLogger(AgentRunLedgerProjector.class);

  private final ActionLedgerChangeRegistry registry;
  // sessionId -> (callId -> toolName); populated by proposed/started before the completion arrives.
  private final Map<String, Map<String, String>> toolNamesBySession = new ConcurrentHashMap<>();

  public AgentRunLedgerProjector(ActionLedgerChangeRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /** Listener: receives one persisted run event record {@code {timestamp, shapeId, eventType, payload}}. */
  public void onEvent(String sessionId, Map<String, Object> record) {
    try {
      // Tempdoc 565 §15.C fix — the run-event store is now multi-shape (agent + workflow share it). The
      // agent-run ledger is agent-run-only: ignore any non-agent event so a workflow tool-call does not
      // project a spurious agent-tool ledger row. (Records pre-dating the shapeId tag are agent runs.)
      Object shapeId = record.get("shapeId");
      if (shapeId != null && !"core.agent-run".equals(shapeId)) {
        return;
      }
      if (!(record.get("eventType") instanceof String eventType)) {
        return;
      }
      Map<String, Object> payload =
          record.get("payload") instanceof Map<?, ?> m ? castMap(m) : Map.of();
      switch (eventType) {
        case "tool_call_proposed", "tool_exec_started" -> rememberToolName(sessionId, payload);
        case "tool_exec_completed" -> projectCompletion(sessionId, record, payload);
        case "done", "error" -> toolNamesBySession.remove(sessionId); // run ended; free the map
        default -> {
          // not a thread-ledger event
        }
      }
    } catch (Exception e) {
      LOG.warn("AgentRunLedgerProjector failed for session {}", sessionId, e);
    }
  }

  private void rememberToolName(String sessionId, Map<String, Object> payload) {
    if (payload.get("callId") instanceof String callId
        && payload.get("toolName") instanceof String toolName
        && !callId.isBlank()
        && !toolName.isBlank()) {
      toolNamesBySession
          .computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>())
          .put(callId, toolName);
    }
  }

  private void projectCompletion(
      String sessionId, Map<String, Object> record, Map<String, Object> payload) {
    String callId = payload.get("callId") instanceof String s ? s : "";
    Map<String, String> byCall = toolNamesBySession.get(sessionId);
    // Fall back to the callId if the name was not seen (degenerate; keeps the row non-empty).
    String toolName = byCall != null ? byCall.getOrDefault(callId, callId) : callId;
    // These catalog wire names are transliterations of the canonical operation refs.
    String operationRef = switch (toolName) {
      case "core_bulk_reindex" -> "core.bulk-reindex";
      case "core_rebuild_index" -> "core.rebuild-index";
      case "core_activate_installed_models" -> "core.activate-installed-models";
      default -> toolName;
    };
    boolean success = Boolean.TRUE.equals(payload.get("success"));
    String executionId = payload.get("executionId") instanceof String s ? s : "";
    Instant at = parseTs(record.get("timestamp"));
    Optional<String> key = switch (operationRef) {
      case "core.bulk-reindex", "core.rebuild-index", "core.activate-installed-models" -> operationKey(payload);
      default -> Optional.empty();
    };
    registry.broadcastActionEvent(
        ActionLedgerProjection.projectAgentToolCompletion(
            at, operationRef, success, executionId, sessionId, key));
  }

  private static Optional<String> operationKey(Map<String, Object> payload) {
    if (payload.get("structuredData") instanceof Map<?, ?> data
        && data.get("operationKey") instanceof String key) {
      try {
        OperationKeys.timestampMillis(key);
        return Optional.of(key);
      } catch (IllegalArgumentException malformed) {
        // Legacy or unrecorded tool results retain their execution-based ledger identity.
      }
    }
    return Optional.empty();
  }

  private static Instant parseTs(Object raw) {
    if (raw instanceof String s && !s.isBlank()) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
    }
    return Instant.now();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }
}
