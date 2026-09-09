package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.BackendIntentRouter;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.GatedOperationExecutor;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.IntentDispatchResult;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.api.registry.Workflow;
import io.justsearch.agent.api.registry.WorkflowCatalog;
import io.justsearch.agent.api.registry.WorkflowNode;
import io.justsearch.agent.api.registry.WorkflowRef;
import io.justsearch.core.context.EngineContext;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link WorkflowShapeRunner} executes a {@link Workflow} by delegating each node to
 * its substrate (tempdoc 560 Phase 2). Tool steps are exercised without a live LLM; the LlmStep's
 * engine delegation is covered by the live-stack browser validation per the plan.
 */
class WorkflowShapeRunnerTest {

  private static Operation op(String id, RiskTier risk) {
    OperationRef ref = new OperationRef(id);
    return new Operation(
        ref,
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        Interface.of("{}", "{}"),
        new OperationPolicy(
            risk,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ref),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Workflow workflow(String id, List<WorkflowNode> nodes) {
    return new Workflow(
        new WorkflowRef(id),
        Presentation.of(new I18nKey("k.label"), new I18nKey("k.desc")),
        Provenance.core("1.0"),
        Audience.USER,
        nodes,
        List.of(new ConsumerHook.Realized("workflow-shape-runner", Audience.USER)));
  }

  private static WorkflowShapeRunner runner(Workflow wf, OperationResult dispatchResult) {
    return runner(wf, dispatchResult, ignored -> {});
  }

  private static WorkflowShapeRunner runner(
      Workflow wf, OperationResult dispatchResult, Consumer<EngineContext> contextSink) {
    OperationCatalog agentTools =
        OperationCatalog.of("vendor.x", List.of(op("vendor.x.add", RiskTier.LOW)));
    OperationCatalog coreOps = OperationCatalog.of("core", List.of());
    BackendIntentRouter router =
        (intent, provenance, engineContext) -> {
          contextSink.accept(engineContext);
          return new IntentDispatchResult.Dispatched(dispatchResult);
        };
    GatedOperationExecutor gated =
        new GatedOperationExecutor(() -> router, () -> null, TransportTag.WORKFLOW);
    ConversationEngine engine =
        new ConversationEngine(ConversationShapeCatalog.of("core", List.of()), List.of());
    return new WorkflowShapeRunner(
        () -> engine,
        WorkflowCatalog.of("core", List.of(wf)),
        () -> agentTools,
        () -> coreOps,
        gated,
        new WorkflowGateRegistry());
  }

  private static List<String> names(List<SseEvent> events) {
    return events.stream().map(SseEvent::name).toList();
  }

  @Test
  void runsAToolOnlyWorkflowEndToEnd() {
    Workflow wf =
        workflow(
            "core.t-tool",
            List.of(new WorkflowNode.ToolStep("act", new OperationRef("vendor.x.add"), "{}")));
    List<SseEvent> events = new CopyOnWriteArrayList<>();
    AtomicReference<EngineContext> routedContext = new AtomicReference<>();
    EngineContext incomingContext =
        io.justsearch.app.services.intent.EngineProvenance.context(
            EngineContext.ClientKind.INTERNAL,
            "workflow-parent-agent",
            Optional.of("agent-session"),
            Optional.of("grant-workflow"),
            TransportTag.AGENT_LOOP,
            EngineContext.Survival.DURABLE,
            EngineContext.Urgency.BACKGROUND);

    runner(wf, OperationResult.success("42"), routedContext::set)
        .run(Map.of("workflowId", "core.t-tool"), Audience.USER, events::add, incomingContext);

    List<String> order = names(events);
    assertTrue(order.contains("session_started"), order.toString());
    assertTrue(order.contains("workflow_started"), order.toString());
    assertTrue(order.contains("tool_exec_started"), order.toString());
    assertTrue(order.contains("tool_exec_completed"), order.toString());
    assertEquals("done", order.get(order.size() - 1), order.toString());

    SseEvent completed =
        events.stream().filter(e -> e.name().equals("tool_exec_completed")).findFirst().orElseThrow();
    assertEquals(Boolean.TRUE, completed.payload().get("success"));
    assertEquals("42", completed.payload().get("output"));

    EngineContext childContext = routedContext.get();
    assertEquals(TransportTag.WORKFLOW.name(), childContext.transport());
    assertEquals(SourceTier.UNTRUSTED.name(), childContext.sourceTier());
    assertEquals(incomingContext.clientKind(), childContext.clientKind());
    assertEquals(incomingContext.clientId(), childContext.clientId());
    assertEquals(incomingContext.grantReference(), childContext.grantReference());
    assertEquals(incomingContext.survival(), childContext.survival());
    assertEquals(incomingContext.urgency(), childContext.urgency());
    assertTrue(childContext.sessionId().isPresent());
    assertFalse(childContext.sessionId().equals(incomingContext.sessionId()));
  }

  @Test
  void unknownWorkflowEmitsError() {
    Workflow wf = workflow("core.t-x", List.of());
    List<SseEvent> events = new CopyOnWriteArrayList<>();
    runner(wf, OperationResult.success("x")).run(Map.of("workflowId", "core.nope"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.ui());
    assertEquals(List.of("error"), names(events));
  }

  @Test
  void danglingDelegationFailsValidationBeforeRunning() {
    Workflow wf =
        workflow(
            "core.t-dangling",
            List.of(new WorkflowNode.ToolStep("act", new OperationRef("vendor.x.ghost"), "{}")));
    List<SseEvent> events = new CopyOnWriteArrayList<>();
    runner(wf, OperationResult.success("x"))
        .run(Map.of("workflowId", "core.t-dangling"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.ui());
    assertEquals(List.of("error"), names(events));
    assertTrue(
        events.get(0).payload().get("error").toString().contains("dangling"),
        events.get(0).payload().toString());
  }

  /**
   * Regression for tempdoc 734 round 5 finding 1 / tempdoc 744: the LIVE {@link
   * CoreWorkflowCatalog}, no {@code workflowId} supplied (the exact shape of the round's own
   * repro — {@code shapeId:"core.workflow-run"} with no other selector). Before the fix this
   * silently defaulted to {@code core.demo-compose}, which needs the optional {@code
   * vendor.mcphost.*} reference server no test (and most real installs) has, and failed every
   * time with "has dangling delegations". Uses the real catalog, not a synthetic one, so a
   * regression here is caught even if a future edit reorders or renames the catalog's entries.
   */
  @Test
  void noWorkflowIdDefaultsToResearchBriefNotTheMcpDemo() {
    // "core.free-chat" is what CoreWorkflowCatalog.researchBrief()'s LlmSteps delegate to.
    RecordingRunner freeChat = new RecordingRunner(new ConversationShapeRef("core.free-chat"));
    ConversationShape freeChatShape =
        new ConversationShape(
            freeChat.shapeId(),
            Presentation.of(new I18nKey("k.l"), new I18nKey("k.d")),
            Audience.USER,
            Provenance.core("1.0"),
            ExecutionMode.SHAPE_DRIVEN,
            IterationMode.ONE_SHOT,
            PersistenceMode.EPHEMERAL,
            List.of(),
            List.of(),
            List.of(),
            null,
            EventDescriptor.namesOnly(List.of("chunk", "done")),
            false);
    ConversationEngine engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(freeChatShape)), List.of(freeChat));
    WorkflowShapeRunner r =
        new WorkflowShapeRunner(
            () -> engine,
            CoreWorkflowCatalog.catalog(),
            () -> OperationCatalog.of("vendor.x", List.of()),
            () -> OperationCatalog.of("core", List.of()),
            new GatedOperationExecutor(
                () -> (intent, provenance, engineContext) -> new IntentDispatchResult.Dispatched(OperationResult.success("x")),
                () -> null,
                TransportTag.WORKFLOW),
            new WorkflowGateRegistry());
    List<SseEvent> events = new CopyOnWriteArrayList<>();

    r.run(Map.of(), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.ui());

    List<String> order = names(events);
    assertFalse(order.contains("error"), "must not fail: " + events);
    SseEvent started =
        events.stream().filter(e -> e.name().equals("workflow_started")).findFirst().orElseThrow();
    assertEquals(
        CoreWorkflowCatalog.RESEARCH_BRIEF_ID.value(),
        started.payload().get("workflowId"),
        "an unspecified workflowId must default to the self-contained workflow, not the one that "
            + "needs an optional external MCP server: " + events);
    // Not just "didn't fail" -- the run must actually complete both of research-brief's nodes
    // (matching the rigor of the sibling llmStepEmitsNodeOutputInsideTheBracket/
    // runsAToolOnlyWorkflowEndToEnd tests, which assert the terminal event, not just absence of error).
    assertEquals("done", order.get(order.size() - 1), "must run to completion: " + order);
    List<String> completedNodeIds =
        events.stream()
            .filter(e -> e.name().equals("node_completed"))
            .map(e -> String.valueOf(e.payload().get("nodeId")))
            .toList();
    assertEquals(
        List.of("think", "draft"),
        completedNodeIds,
        "both of research-brief's LlmSteps must complete: " + events);
  }

  @Test
  void gateStepBlocksThenProceedsOnApproval() throws Exception {
    Workflow wf =
        workflow(
            "core.t-gate",
            List.of(new WorkflowNode.GateStep("confirm", ConfirmStrategy.Inline.INSTANCE)));
    WorkflowShapeRunner r = runner(wf, OperationResult.success("x"));
    List<SseEvent> events = Collections.synchronizedList(new java.util.ArrayList<>());

    Thread runThread =
        new Thread(() -> r.run(Map.of("workflowId", "core.t-gate"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.ui()));
    runThread.start();

    String callId = awaitPendingCallId(events);
    assertTrue(r.resolveGate(callId, true), "the gate must accept the approval");
    runThread.join(5_000);

    List<String> order = names(events);
    assertTrue(order.contains("tool_call_approved"), order.toString());
    assertEquals("done", order.get(order.size() - 1), order.toString());
    SseEvent done = events.get(events.size() - 1);
    assertFalse(Boolean.TRUE.equals(done.payload().get("cancelled")), done.payload().toString());
  }

  @Test
  void gateStepCancelsTheWorkflowOnRejection() throws Exception {
    Workflow wf =
        workflow(
            "core.t-gate-no",
            List.of(new WorkflowNode.GateStep("confirm", ConfirmStrategy.Inline.INSTANCE)));
    WorkflowShapeRunner r = runner(wf, OperationResult.success("x"));
    List<SseEvent> events = Collections.synchronizedList(new java.util.ArrayList<>());

    Thread runThread =
        new Thread(() -> r.run(Map.of("workflowId", "core.t-gate-no"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.ui()));
    runThread.start();

    String callId = awaitPendingCallId(events);
    r.resolveGate(callId, false);
    runThread.join(5_000);

    List<String> order = names(events);
    assertTrue(order.contains("tool_call_rejected"), order.toString());
    SseEvent done = events.get(events.size() - 1);
    assertEquals("done", done.name());
    assertEquals(Boolean.TRUE, done.payload().get("cancelled"), done.payload().toString());
  }

  /**
   * Regression for the live-caught LlmStep body-contract bug (tempdoc 560 Phase 2): the runner must
   * hand the conversation shape the {@code prompt} field that single-turn shapes (free-chat / ask /
   * summarize via UserPromptInjector) require — passing only {@code messages} made the demo
   * workflow's LlmStep fail at runtime ("Request body must include a non-empty 'prompt' string
   * field"), which the mock-engine unit tests could not see. This captures the body the LlmStep
   * delegates to the engine and asserts the prompt contract.
   */
  @Test
  void llmStepPassesThePromptContractToTheEngine() {
    RecordingRunner recorder = new RecordingRunner();
    ConversationShape recShape =
        new ConversationShape(
            recorder.shapeId(),
            Presentation.of(new I18nKey("k.l"), new I18nKey("k.d")),
            Audience.USER,
            Provenance.core("1.0"),
            ExecutionMode.SHAPE_DRIVEN,
            IterationMode.ONE_SHOT,
            PersistenceMode.EPHEMERAL,
            List.of(),
            List.of(),
            List.of(),
            null,
            EventDescriptor.namesOnly(List.of("chunk", "done")),
            false);
    ConversationEngine engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(recShape)), List.of(recorder));
    Workflow wf =
        workflow(
            "core.t-llm",
            List.of(new WorkflowNode.LlmStep("think", recorder.shapeId(), "hello-seed")));
    WorkflowShapeRunner r =
        new WorkflowShapeRunner(
            () -> engine,
            WorkflowCatalog.of("core", List.of(wf)),
            () -> OperationCatalog.of("vendor.x", List.of()),
            () -> OperationCatalog.of("core", List.of()),
            new GatedOperationExecutor(
                () -> (intent, provenance, engineContext) -> new IntentDispatchResult.Dispatched(OperationResult.success("x")),
                () -> null,
                TransportTag.WORKFLOW),
            new WorkflowGateRegistry());

    r.run(Map.of("workflowId", "core.t-llm"), Audience.USER, new CopyOnWriteArrayList<>()::add, io.justsearch.app.services.TestEngineContexts.ui());

    assertEquals("hello-seed", recorder.lastBody.get("prompt"), "LlmStep must pass the prompt field");
  }

  /**
   * Tempdoc 565 §26.I (Fix A) — an LlmStep persists its full output as a durable {@code node_output}
   * event emitted INSIDE the node's start/end bracket (node_started < node_output < node_completed), so a
   * RELOADED workflow run brackets the node's real content instead of an empty node.
   */
  @Test
  void llmStepEmitsNodeOutputInsideTheBracket() {
    RecordingRunner recorder = new RecordingRunner();
    ConversationShape recShape =
        new ConversationShape(
            recorder.shapeId(),
            Presentation.of(new I18nKey("k.l"), new I18nKey("k.d")),
            Audience.USER,
            Provenance.core("1.0"),
            ExecutionMode.SHAPE_DRIVEN,
            IterationMode.ONE_SHOT,
            PersistenceMode.EPHEMERAL,
            List.of(),
            List.of(),
            List.of(),
            null,
            EventDescriptor.namesOnly(List.of("chunk", "done")),
            false);
    ConversationEngine engine =
        new ConversationEngine(
            ConversationShapeCatalog.of("core", List.of(recShape)), List.of(recorder));
    Workflow wf =
        workflow(
            "core.t-llm2",
            List.of(new WorkflowNode.LlmStep("think", recorder.shapeId(), "seed")));
    WorkflowShapeRunner r =
        new WorkflowShapeRunner(
            () -> engine,
            WorkflowCatalog.of("core", List.of(wf)),
            () -> OperationCatalog.of("vendor.x", List.of()),
            () -> OperationCatalog.of("core", List.of()),
            new GatedOperationExecutor(
                () -> (intent, provenance, engineContext) -> new IntentDispatchResult.Dispatched(OperationResult.success("x")),
                () -> null,
                TransportTag.WORKFLOW),
            new WorkflowGateRegistry());
    List<SseEvent> events = new CopyOnWriteArrayList<>();

    r.run(Map.of("workflowId", "core.t-llm2"), Audience.USER, events::add, io.justsearch.app.services.TestEngineContexts.ui());

    List<String> order = names(events);
    int started = order.indexOf("node_started");
    int output = order.indexOf("node_output");
    int completed = order.indexOf("node_completed");
    assertTrue(started >= 0 && completed >= 0, order.toString());
    assertTrue(output > started && output < completed, "node_output must sort inside the bracket: " + order);
    SseEvent out =
        events.stream().filter(e -> e.name().equals("node_output")).findFirst().orElseThrow();
    assertEquals("ok", out.payload().get("output"), "carries the node's full captured text");
    assertEquals("think", out.payload().get("nodeId"));
  }

  /** A ShapeRunner that records the body it was handed (for the LlmStep body-contract test). */
  private static final class RecordingRunner implements ShapeRunner {
    private final ConversationShapeRef shapeId;
    private volatile Map<String, Object> lastBody;

    RecordingRunner() {
      this(new ConversationShapeRef("core.t-record"));
    }

    RecordingRunner(ConversationShapeRef shapeId) {
      this.shapeId = shapeId;
    }

    @Override
    public ConversationShapeRef shapeId() {
      return shapeId;
    }

    @Override
    public void run(
        Map<String, Object> body, Audience audience, Consumer<SseEvent> sink,
        EngineContext engineContext) {
      this.lastBody = body;
      sink.accept(new SseEvent("chunk", Map.of("text", "ok")));
      sink.accept(new SseEvent("done", Map.of("finalResponse", "ok")));
    }
  }

  private static String awaitPendingCallId(List<SseEvent> events) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      synchronized (events) {
        for (SseEvent e : events) {
          if (e.name().equals("tool_call_pending")) {
            return e.payload().get("callId").toString();
          }
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("no tool_call_pending event arrived");
  }
}
