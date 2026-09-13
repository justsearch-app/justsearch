/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.WorkflowCatalog;
import io.justsearch.agent.api.registry.WorkflowRef;
import io.justsearch.agent.api.registry.WorkflowToolRunner;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Tempdoc 560 WS5 (the one window) — the concrete bridge from a projected workflow tool call to the
 * {@link WorkflowShapeRunner}, streaming the workflow's node-by-node {@link SseEvent}s into the agent
 * loop's live {@link AgentEvent} sink (as {@link AgentEvent.AgentProgress}) and returning the terminal
 * node output as the {@link OperationResult} the model reads.
 *
 * <p>It depends on a {@link WorkflowExecutor} functional seam rather than the concrete {@code
 * WorkflowShapeRunner} so the SSE→AgentEvent translation is unit-testable with a fake executor (the
 * real one is {@code workflowShapeRunner::run}, wired in {@code LocalApiServer} where that runner is
 * constructed).
 */
public final class WorkflowToolRunnerImpl implements WorkflowToolRunner {

  /** The single behavior this bridge needs from {@code WorkflowShapeRunner} — its {@code run}. */
  @FunctionalInterface
  public interface WorkflowExecutor {
    void run(Map<String, Object> body, Audience audience, Consumer<SseEvent> sink, EngineContext engineContext, boolean background);
  }

  private final WorkflowCatalog workflowCatalog;
  private final WorkflowExecutor executor;
  private final WorkflowGateRegistry gateRegistry;

  public WorkflowToolRunnerImpl(WorkflowCatalog workflowCatalog, WorkflowExecutor executor, WorkflowGateRegistry gateRegistry) {
    this.workflowCatalog = Objects.requireNonNull(workflowCatalog, "workflowCatalog");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.gateRegistry = Objects.requireNonNull(gateRegistry, "gateRegistry");
  }

  @Override
  public boolean handles(OperationRef ref) {
    return WorkflowOperationProjection.workflowRefFor(ref)
        .map(workflowCatalog::findById)
        .map(Optional::isPresent)
        .orElse(false);
  }

  @Override
  public OperationResult run(OperationRef ref, String argumentsJson, Consumer<AgentEvent> sink, EngineContext engineContext) {
    return run(ref, argumentsJson, sink, engineContext, false);
  }

  @Override
  public java.util.List<AgentEvent.PendingApproval> pendingApprovals(String sessionId) {
    return gateRegistry.pendingApprovals(sessionId);
  }

  @Override
  public OperationResult run(OperationRef ref, String argumentsJson, Consumer<AgentEvent> sink,
      EngineContext engineContext, boolean background) {
    WorkflowRef workflowRef = WorkflowOperationProjection.workflowRefFor(ref).orElse(null);
    if (workflowRef == null || workflowCatalog.findById(workflowRef).isEmpty()) {
      return OperationResult.failure("Not a projected workflow tool: " + ref.value());
    }
    // Mutable capture cells for the terminal outcome the workflow streams.
    String[] finalResponse = {""};
    String[] errorMessage = {null};
    int[] nodeCount = {0};

    Consumer<SseEvent> sseSink =
        ev -> {
          switch (ev.name()) {
            case "workflow_started" -> {
              Object count = ev.payload().get("nodeCount");
              if (count instanceof Number n) {
                nodeCount[0] = n.intValue();
              }
            }
            case "done" -> {
              Object fr = ev.payload().get("finalResponse");
              if (fr != null) {
                finalResponse[0] = fr.toString();
              }
              if (Boolean.TRUE.equals(ev.payload().get("cancelled"))) {
                errorMessage[0] = finalResponse[0].isBlank() ? "Workflow cancelled" : finalResponse[0];
              }
            }
            case "error" -> {
              Object err = ev.payload().get("error");
              errorMessage[0] = err != null ? err.toString() : "workflow failed";
            }
            default -> {
              // node_started / node_completed / session_started → live progress into the agent stream.
            }
          }
          sink.accept(toAgentEvent(ev, nodeCount[0]));
        };

    try {
      // Workflows take no model-supplied arguments today; the runner sets the body itself. The
      // model's argumentsJson is intentionally not threaded through (an empty-object schema is
      // projected) — see WorkflowOperationProjection.
      executor.run(
          Map.of("workflowId", workflowRef.value()), Audience.AGENT, sseSink, engineContext, background);
    } catch (RuntimeException e) {
      // Host owns truth (§4.5): a runner failure becomes a result the model can recover from, never
      // an exception that tears down the agent loop.
      return OperationResult.failure(
          "Workflow '" + workflowRef.value() + "' failed: " + e.getMessage());
    }

    if (errorMessage[0] != null) {
      return OperationResult.failure(
          "Workflow '" + workflowRef.value() + "' failed: " + errorMessage[0]);
    }
    return OperationResult.success(
        finalResponse[0], Map.of("workflow", workflowRef.value(), "finalResponse", finalResponse[0]));
  }

  /** Preserve reply-bearing controls; their call id is owned by the existing workflow registry. */
  private static AgentEvent toAgentEvent(SseEvent event, int nodeCount) {
    return switch (event.name()) {
      case "tool_call_pending" -> {
        var risk = io.justsearch.agent.api.registry.RiskTier.valueOf(
            requiredText(event, "risk").toUpperCase(java.util.Locale.ROOT));
        // The workflow explicitly waits for confirmation. An outer AUTO dial cannot remove it.
        var declaredGate = event.payload().get("gateBehavior");
        var gate = declaredGate instanceof String value
            ? io.justsearch.agent.api.registry.GateBehavior.valueOf(value.toUpperCase(java.util.Locale.ROOT))
            : risk == io.justsearch.agent.api.registry.RiskTier.HIGH
            ? io.justsearch.agent.api.registry.GateBehavior.TYPED_CONFIRM
            : io.justsearch.agent.api.registry.GateBehavior.INLINE_CONFIRM;
        yield new AgentEvent.ToolCallPendingApproval(requiredText(event, "callId"),
            requiredText(event, "toolName"), requiredText(event, "arguments"), risk, gate);
      }
      case "tool_call_approved" -> new AgentEvent.ToolCallApproved(requiredText(event, "callId"));
      case "tool_call_rejected" -> new AgentEvent.ToolCallRejected(
          requiredText(event, "callId"), requiredText(event, "reason"));
      default -> toProgress(event, nodeCount);
    };
  }

  private static String requiredText(SseEvent event, String field) {
    if (!(event.payload().get(field) instanceof String value) || value.isBlank()) {
      throw new IllegalArgumentException("Workflow approval event requires " + field);
    }
    return value;
  }

  /** Map other workflow {@link SseEvent}s onto the agent loop's generic progress event. */
  private static AgentEvent toProgress(SseEvent ev, int nodeCount) {
    int index = 0;
    Object idx = ev.payload().get("index");
    if (idx instanceof Number n) {
      index = n.intValue();
    }
    String message =
        switch (ev.name()) {
          case "node_started" -> "Running node " + ev.payload().getOrDefault("nodeId", "");
          case "node_completed" -> "Completed node " + ev.payload().getOrDefault("nodeId", "");
          case "done" -> "Workflow complete";
          case "error" -> "Workflow error: " + ev.payload().getOrDefault("error", "");
          default -> ev.name();
        };
    // Tempdoc 561 #5: only a genuine failure carries ERROR severity; node progress is routine (INFO).
    String severity =
        "error".equals(ev.name()) ? AgentEvent.AgentProgress.ERROR : AgentEvent.AgentProgress.INFO;
    return new AgentEvent.AgentProgress(
        "workflow:" + ev.name(), message, index, nodeCount, severity);
  }
}
