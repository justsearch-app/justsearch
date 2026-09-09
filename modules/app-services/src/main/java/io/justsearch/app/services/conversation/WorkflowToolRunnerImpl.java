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
    void run(Map<String, Object> body, Audience audience, Consumer<SseEvent> sink, EngineContext engineContext);
  }

  private final WorkflowCatalog workflowCatalog;
  private final WorkflowExecutor executor;

  public WorkflowToolRunnerImpl(WorkflowCatalog workflowCatalog, WorkflowExecutor executor) {
    this.workflowCatalog = Objects.requireNonNull(workflowCatalog, "workflowCatalog");
    this.executor = Objects.requireNonNull(executor, "executor");
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
            }
            case "error" -> {
              Object err = ev.payload().get("error");
              errorMessage[0] = err != null ? err.toString() : "workflow failed";
            }
            default -> {
              // node_started / node_completed / session_started → live progress into the agent stream.
            }
          }
          sink.accept(toProgress(ev, nodeCount[0]));
        };

    try {
      // Workflows take no model-supplied arguments today; the runner sets the body itself. The
      // model's argumentsJson is intentionally not threaded through (an empty-object schema is
      // projected) — see WorkflowOperationProjection.
      executor.run(
          Map.of("workflowId", workflowRef.value()), Audience.AGENT, sseSink, engineContext);
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

  /** Map a workflow {@link SseEvent} onto the agent loop's generic progress event. */
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
