/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.GatedOperationExecutor;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.Workflow;
import io.justsearch.agent.api.registry.WorkflowCatalog;
import io.justsearch.agent.api.registry.WorkflowNode;
import io.justsearch.agent.api.registry.WorkflowRef;
import io.justsearch.agent.api.registry.WorkflowValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ShapeRunner} for the workflow shape — {@link WorkflowRunShape#ID} ({@code
 * core.workflow-run}). Makes the {@link Workflow} Manifest type <em>executable</em> (tempdoc 560
 * Phase 2): it resolves a declared workflow, validates that every node delegates to a live
 * substrate, then runs each node by delegating to the substrate the node references —
 *
 * <ul>
 *   <li>{@code LlmStep} → the {@link ConversationEngine} (runs the referenced conversation shape;
 *       streams its {@code chunk} text through to the workflow view, swallowing the sub-shape's own
 *       lifecycle events so the workflow emits a single terminal {@code done}),
 *   <li>{@code ToolStep} → the {@link GatedOperationExecutor} (consent gate + capsule mint + route
 *       through the intent layer / trust lattice),
 *   <li>{@code GateStep} → the consent gate (emit a pending-approval event, block on the user).
 * </ul>
 *
 * The runner introduces no new mechanism — it only sequences the ones that already exist, which is
 * the whole point of the Workflow tier (§534's "every node delegates").
 */
public final class WorkflowShapeRunner implements ShapeRunner {

  private static final Logger LOG = LoggerFactory.getLogger(WorkflowShapeRunner.class);
  private static final long GATE_TIMEOUT_SECONDS = 300;
  private static final int OUTPUT_PREVIEW_CHARS = 280;

  private final Supplier<ConversationEngine> engineSupplier;
  private final WorkflowCatalog workflowCatalog;
  private final Supplier<OperationCatalog> agentToolsSupplier;
  private final Supplier<OperationCatalog> coreOperationsSupplier;
  private final GatedOperationExecutor gatedExecutor;
  private final WorkflowGateRegistry gateRegistry;
  // Tempdoc 565 §15.C — the shared, shape-agnostic run event log: the workflow run persists + indexes
  // through it (same space as agent runs), so the unified thread projects it as a mode of the one
  // window. noop() on the test-only path (the run still streams; it just isn't persisted).
  private final io.justsearch.agent.RunEventStore runEvents;

  public WorkflowShapeRunner(
      Supplier<ConversationEngine> engineSupplier,
      WorkflowCatalog workflowCatalog,
      Supplier<OperationCatalog> agentToolsSupplier,
      Supplier<OperationCatalog> coreOperationsSupplier,
      GatedOperationExecutor gatedExecutor,
      WorkflowGateRegistry gateRegistry) {
    this(
        engineSupplier,
        workflowCatalog,
        agentToolsSupplier,
        coreOperationsSupplier,
        gatedExecutor,
        gateRegistry,
        io.justsearch.agent.RunEventStore.noop());
  }

  public WorkflowShapeRunner(
      Supplier<ConversationEngine> engineSupplier,
      WorkflowCatalog workflowCatalog,
      Supplier<OperationCatalog> agentToolsSupplier,
      Supplier<OperationCatalog> coreOperationsSupplier,
      GatedOperationExecutor gatedExecutor,
      WorkflowGateRegistry gateRegistry,
      io.justsearch.agent.RunEventStore runEvents) {
    this.engineSupplier = Objects.requireNonNull(engineSupplier, "engineSupplier");
    this.workflowCatalog = Objects.requireNonNull(workflowCatalog, "workflowCatalog");
    this.agentToolsSupplier = Objects.requireNonNull(agentToolsSupplier, "agentToolsSupplier");
    this.coreOperationsSupplier =
        Objects.requireNonNull(coreOperationsSupplier, "coreOperationsSupplier");
    this.gatedExecutor = Objects.requireNonNull(gatedExecutor, "gatedExecutor");
    this.gateRegistry = Objects.requireNonNull(gateRegistry, "gateRegistry");
    this.runEvents = Objects.requireNonNull(runEvents, "runEvents");
  }

  @Override
  public ConversationShapeRef shapeId() {
    return WorkflowRunShape.ID;
  }

  @Override
  public void run(Map<String, Object> body, Audience audience, Consumer<SseEvent> sink, EngineContext incomingContext) {
    WorkflowRef workflowId = resolveWorkflowId(body);
    Workflow workflow = workflowCatalog.findById(workflowId).orElse(null);
    if (workflow == null) {
      emitError(sink, "Unknown workflow: " + workflowId.value(), "BAD_REQUEST");
      return;
    }

    // Validate every node delegates to a live substrate (fail-loud, not silent skip).
    List<String> validationErrors =
        WorkflowValidator.validate(workflow, knownShapeIds(), knownOperationIds());
    if (!validationErrors.isEmpty()) {
      emitError(
          sink,
          "Workflow " + workflowId.value() + " has dangling delegations: " + validationErrors,
          "BAD_REQUEST");
      return;
    }

    String sessionId = UUID.randomUUID().toString();
    EngineContext engineContext = io.justsearch.app.services.intent.EngineProvenance.rebase(
        incomingContext, Optional.of(sessionId), io.justsearch.agent.api.registry.TransportTag.WORKFLOW,
        incomingContext.survival(), incomingContext.urgency());
    // Tempdoc 565 §15.C — persist + index this workflow run in the shared run-event space so the
    // unified thread projects it as a mode of the one window (not a bespoke surface). `psink` mirrors
    // every streamed event to the durable log; the meta carries the conversation link the thread scans
    // by. The agent tool vocabulary (tool_call_*) is already shared, so the projection + the approval
    // ceremony reuse the agent path unchanged.
    String conversationId =
        body.get("conversationId") instanceof String cid && !cid.isBlank() ? cid : sessionId;
    String startedAt = java.time.Instant.now().toString();
    var runMeta = new LinkedHashMap<String, Object>();
    runMeta.put("sessionId", sessionId);
    runMeta.put("conversationId", conversationId);
    runMeta.put("shapeId", WorkflowRunShape.ID.value());
    runMeta.put("startedAt", startedAt);
    runMeta.put("updatedAt", startedAt);
    runMeta.put("state", "RUNNING");
    runMeta.put("background", false);
    // Tempdoc 565 §15.C fix — a synthetic opening "user" turn so the RECORD-side thread projection
    // (AgentLoopService.firstUserMessage) yields the trigger row, matching the live FE turn.
    runMeta.put(
        "messages",
        List.of(Map.of("role", "user", "content", "Run workflow: " + workflowId.value())));
    runEvents.writeRunMeta(sessionId, runMeta);
    // Tempdoc 565 §15.C fix — finalize the run's state on the terminal event so presence/state
    // consumers don't see it stuck "RUNNING" (the agent path's updateCheckpoint does this).
    final Consumer<SseEvent> psink =
        ev -> {
          sink.accept(ev);
          runEvents.appendEvent(sessionId, WorkflowRunShape.ID.value(), ev.name(), ev.payload());
          if ("done".equals(ev.name()) || "error".equals(ev.name())) {
            runMeta.put("state", "error".equals(ev.name()) ? "ERROR" : "DONE");
            runMeta.put("updatedAt", java.time.Instant.now().toString());
            runEvents.writeRunMeta(sessionId, runMeta);
          }
        };
    psink.accept(new SseEvent("session_started", Map.of("sessionId", sessionId)));
    psink.accept(
        new SseEvent(
            "workflow_started",
            Map.of("workflowId", workflowId.value(), "nodeCount", workflow.nodes().size())));

    String lastOutput = "";
    int index = 0;
    for (WorkflowNode node : workflow.nodes()) {
      psink.accept(
          new SseEvent(
              "node_started",
              Map.of("nodeId", node.nodeId(), "kind", kindOf(node), "index", index)));
      try {
        switch (node) {
          case WorkflowNode.LlmStep step -> lastOutput = runLlmStep(step, lastOutput, audience, psink, engineContext);
          case WorkflowNode.GateStep step -> {
            if (!runGateStep(step, psink)) {
              // User declined at the gate — terminate the workflow cleanly.
              psink.accept(
                  new SseEvent(
                      "done",
                      Map.of(
                          "finalResponse",
                          "Workflow cancelled by the user at gate '" + step.nodeId() + "'.",
                          "nodesExecuted",
                          index,
                          "cancelled",
                          true)));
              return;
            }
          }
          case WorkflowNode.ToolStep step -> {
            ToolOutcome outcome = runToolStep(step, psink, engineContext);
            if (outcome.cancelled()) {
              psink.accept(
                  new SseEvent(
                      "done",
                      Map.of(
                          "finalResponse",
                          "Workflow cancelled by the user at tool step '" + step.nodeId() + "'.",
                          "nodesExecuted",
                          index,
                          "cancelled",
                          true)));
              return;
            }
            lastOutput = outcome.output();
          }
        }
      } catch (WorkflowAbortedException aborted) {
        emitError(psink, aborted.getMessage(), "WORKFLOW_FAILED");
        return;
      }
      // Tempdoc 565 §26.I (Fix A) — an LlmStep's text output is streamed live (chunks) but otherwise
      // persisted nowhere durable, so a RELOADED workflow run bracketed empty nodes. Emit the node's
      // FULL output as a durable `node_output` event INSIDE the start/end bracket (before node_completed,
      // so its timestamp sorts within); the record-side mapper projects it as the node's ASSISTANT_MESSAGE,
      // making reload identical to live. ToolStep output already persists (tool_exec_completed); GateStep
      // has none. The live view ignores `node_output` (no dispatch handler) — it already shows the chunks.
      if (node instanceof WorkflowNode.LlmStep && !lastOutput.isEmpty()) {
        psink.accept(
            new SseEvent(
                "node_output",
                Map.of(
                    "nodeId", node.nodeId(),
                    "kind", kindOf(node),
                    "index", index,
                    "output", lastOutput)));
      }
      psink.accept(
          new SseEvent(
              "node_completed",
              Map.of(
                  "nodeId", node.nodeId(),
                  "kind", kindOf(node),
                  "index", index,
                  "output", preview(lastOutput))));
      index++;
    }

    psink.accept(
        new SseEvent(
            "done", Map.of("finalResponse", lastOutput, "nodesExecuted", workflow.nodes().size())));
  }

  // ---- node executors ----

  /**
   * LlmStep — run the referenced conversation shape through the engine, streaming its {@code chunk}
   * text to the workflow view while capturing it for the next node's input. The sub-shape's own
   * lifecycle events ({@code done} / {@code session_started}) are swallowed so the workflow emits a
   * single terminal {@code done}; a sub-shape {@code error} aborts the workflow.
   */
  private String runLlmStep(
      WorkflowNode.LlmStep step, String priorOutput, Audience audience, Consumer<SseEvent> sink, EngineContext engineContext) {
    String seed = step.prompt() != null ? step.prompt() : priorOutput;
    // Pass the seed under both body contracts a conversation shape may read: `prompt` (single-turn
    // shapes — free-chat / ask / summarize via UserPromptInjector) and `messages` (the agent /
    // message-based shapes). A shape reads whichever field it declares; the other is ignored.
    Map<String, Object> subBody =
        Map.of(
            "prompt", seed,
            "messages", List.of(Map.of("role", "user", "content", seed)));

    StringBuilder captured = new StringBuilder();
    String[] errorMessage = {null};
    Consumer<SseEvent> filtered =
        ev -> {
          switch (ev.name()) {
            case "chunk" -> {
              Object text = ev.payload().get("text");
              if (text != null) {
                captured.append(text);
              }
              sink.accept(ev);
            }
            case "reasoning_chunk" -> sink.accept(ev);
            case "error" -> {
              Object err = ev.payload().get("error");
              errorMessage[0] = err == null ? "LLM step failed" : err.toString();
            }
            case "done", "session_started", "progress", "budget_update" -> {
              // Sub-shape lifecycle — not the workflow's terminal events. Swallow.
            }
            default -> sink.accept(ev);
          }
        };

    engineSupplier.get().run(step.shape(), subBody, audience, filtered, engineContext);
    if (errorMessage[0] != null) {
      throw new WorkflowAbortedException(
          "LLM step '" + step.nodeId() + "' failed: " + errorMessage[0]);
    }
    return captured.toString();
  }

  /** GateStep — surface a consent prompt and block on the user. Returns true iff approved. */
  private boolean runGateStep(WorkflowNode.GateStep step, Consumer<SseEvent> sink) {
    if (step.confirm() instanceof ConfirmStrategy.None) {
      return true; // no confirmation required
    }
    RiskTier risk = riskOf(step.confirm());
    return awaitApproval(sink, UUID.randomUUID().toString(), "(confirm)", "{}", risk);
  }

  /** ToolStep — resolve the op, gate it if required, then route the approved call. */
  private ToolOutcome runToolStep(WorkflowNode.ToolStep step, Consumer<SseEvent> sink, EngineContext engineContext) {
    Operation op = resolveOperation(step.operation().value());
    if (op == null) {
      // Validation passed against the live catalog, so absence here is a race (server disconnected
      // mid-run); fail-loud rather than silently skip.
      throw new WorkflowAbortedException(
          "Tool step '" + step.nodeId() + "' references operation '"
              + step.operation().value()
              + "' which is no longer available");
    }
    String callId = UUID.randomUUID().toString();
    String toolName = op.id().value();
    String args = step.argumentsJson();

    if (gatedExecutor.requiresApproval(op)) {
      if (!awaitApproval(sink, callId, toolName, args, op.policy().risk())) {
        return ToolOutcome.cancelledOutcome();
      }
    }

    sink.accept(new SseEvent("tool_exec_started", Map.of("callId", callId, "toolName", toolName)));
    OperationResult result;
    try {
      result = gatedExecutor.routeApproved(op, args, engineContext);
    } catch (RuntimeException e) {
      LOG.warn("Workflow tool step '{}' dispatch failed", step.nodeId(), e);
      result = OperationResult.failure("Execution error: " + e.getMessage());
    }

    Map<String, Object> completed = new LinkedHashMap<>();
    completed.put("callId", callId);
    completed.put("success", result.success());
    completed.put("output", result.message());
    if (!result.structuredData().isEmpty()) {
      completed.put("structuredData", result.structuredData());
    }
    sink.accept(new SseEvent("tool_exec_completed", completed));
    return ToolOutcome.of(result.message());
  }

  // ---- gate helper (shared by GateStep + ToolStep) ----

  /**
   * Register a gate, emit {@code tool_call_pending}, and block until the user decides (or
   * the timeout elapses → declined). Emits {@code tool_call_approved} / {@code tool_call_rejected}
   * to mirror the agent surface's authorization vocabulary so the FE can reuse its approval flow.
   */
  private boolean awaitApproval(
      Consumer<SseEvent> sink, String callId, String toolName, String argsJson, RiskTier risk) {
    CompletableFuture<Boolean> gate = gateRegistry.create(callId);
    boolean approved;
    try {
      // Announcing the call can synchronously deliver its reply; the future must already exist.
      sink.accept(
          new SseEvent(
              "tool_call_pending",
              Map.of(
                  "callId", callId,
                  "toolName", toolName,
                  "arguments", argsJson,
                  "risk", risk.name().toLowerCase(java.util.Locale.ROOT))));
      try {
        approved = gate.get(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (Exception e) {
        LOG.warn("Workflow approval gate timed out/failed for call {}", callId, e);
        approved = false;
      }
    } finally {
      // Also retire an announced gate if the sink throws; never swallow that announcement failure.
      gateRegistry.discard(callId);
    }
    if (approved) {
      sink.accept(new SseEvent("tool_call_approved", Map.of("callId", callId)));
    } else {
      sink.accept(
          new SseEvent(
              "tool_call_rejected",
              Map.of("callId", callId, "reason", "User declined or approval timed out")));
    }
    return approved;
  }

  /** Completes a pending workflow gate (called by the approve/reject HTTP endpoint). */
  public boolean resolveGate(String callId, boolean approved) {
    return gateRegistry.complete(callId, approved);
  }

  // ---- resolution helpers ----

  private WorkflowRef resolveWorkflowId(Map<String, Object> body) {
    Object raw = body == null ? null : body.get("workflowId");
    if (raw != null && !raw.toString().isBlank()) {
      return new WorkflowRef(raw.toString().trim());
    }
    // Tempdoc 734 round 5 finding 1 / tempdoc 744 — the demo workflow needs the optional
    // vendor.mcphost.* reference server most installs don't have (see CoreWorkflowCatalog's
    // demoCompose() javadoc: "unlike demo-compose ... research-brief runs in any stack with the
    // chat model loaded"). Silently defaulting an unspecified request to the demo meant every
    // caller who didn't know to pass workflowId explicitly got a guaranteed BAD_REQUEST
    // ("has dangling delegations") instead of a working run. Default to the one workflow that
    // is guaranteed to work.
    return CoreWorkflowCatalog.RESEARCH_BRIEF_ID;
  }

  private Operation resolveOperation(String idValue) {
    Optional<Operation> fromTools = agentToolsSupplier.get().findByIdValue(idValue);
    if (fromTools.isPresent()) {
      return fromTools.get();
    }
    return coreOperationsSupplier.get().findByIdValue(idValue).orElse(null);
  }

  private Set<String> knownShapeIds() {
    return engineSupplier.get().catalog().definitions().stream()
        .map(s -> s.id().value())
        .collect(Collectors.toSet());
  }

  private Set<String> knownOperationIds() {
    Set<String> ids =
        agentToolsSupplier.get().definitions().stream()
            .map(o -> o.id().value())
            .collect(Collectors.toSet());
    coreOperationsSupplier.get().definitions().forEach(o -> ids.add(o.id().value()));
    return ids;
  }

  private static RiskTier riskOf(ConfirmStrategy confirm) {
    return switch (confirm) {
      case ConfirmStrategy.Typed ignored -> RiskTier.HIGH;
      case ConfirmStrategy.Inline ignored -> RiskTier.MEDIUM;
      case ConfirmStrategy.None ignored -> RiskTier.LOW;
    };
  }

  private static String kindOf(WorkflowNode node) {
    return switch (node) {
      case WorkflowNode.LlmStep ignored -> "llm";
      case WorkflowNode.ToolStep ignored -> "tool";
      case WorkflowNode.GateStep ignored -> "gate";
    };
  }

  private static String preview(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= OUTPUT_PREVIEW_CHARS
        ? text
        : text.substring(0, OUTPUT_PREVIEW_CHARS) + "…";
  }

  private static void emitError(Consumer<SseEvent> sink, String message, String code) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("error", message);
    payload.put("errorCode", code);
    sink.accept(new SseEvent("error", payload));
  }

  /** Result of a ToolStep: either the op's output text, or a user cancellation. */
  private record ToolOutcome(String output, boolean cancelled) {
    static ToolOutcome of(String output) {
      return new ToolOutcome(output, false);
    }

    static ToolOutcome cancelledOutcome() {
      return new ToolOutcome("", true);
    }
  }

  /** Internal signal that a node failed in a way that should terminate the whole workflow. */
  private static final class WorkflowAbortedException extends RuntimeException {
    WorkflowAbortedException(String message) {
      super(message);
    }
  }
}
