/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.registry.*;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.WorkflowGateRegistry;
import io.justsearch.app.services.conversation.WorkflowShapeRunner;
import io.justsearch.app.services.intent.*;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual agent/workflow forwarding into real admission and storage, with an inert effect owner. */
class DeclaredSurvivalCallerIntegrationTest {
  private static final String ARGS = "{\"paths\":[]}";
  @TempDir Path directory;

  @Test
  void agentDispatcherForwardsApprovedInteractiveCallToDetachedDurableOwner() throws Exception {
    try (var fixture = new Fixture(directory);
        var caller = fixture.admission.admit(fixture.origin, false)) {
      var dispatcher = new AgentToolDispatcher(fixture.executor, AgentTelemetry.noop(),
          () -> fixture.router, () -> fixture.capsules, () -> null);
      var call = new ToolCallRequest("call-1", fixture.operation.id().value(), ARGS);
      var session = new AgentSession(List.of(), 1000, null, caller.context(), caller);
      var approved = dispatcher.prepareAndApprove(session, call, fixture.operation,
          event -> assertTrue(session.approve(call.id())), false);
      assertTrue(approved.approved());
      assertInstanceOf(OperationDispatchPlan.Ready.class, approved.plan());
      var result = dispatcher.executeOperationWithPolicy(fixture.operation,
          call, "agent-session", caller.context(), approved.plan());
      assertTrue(result.success(), result.toString());
      fixture.assertAcceptedAndDetach(caller, TransportTag.AGENT_LOOP, "agent-session");
    }
  }

  @Test
  void workflowToolStepForwardsInteractiveRunToDetachedDurableOwner() throws Exception {
    try (var fixture = new Fixture(directory);
        var caller = fixture.admission.admit(fixture.origin, false)) {
      var workflow = new Workflow(new WorkflowRef("core.durable-forwarding"),
          Presentation.of(new I18nKey("test.workflow"), new I18nKey("test.workflow.desc")),
          Provenance.core("1.0"), Audience.USER,
          List.of(new WorkflowNode.ToolStep("ingest", fixture.operation.id(), ARGS)),
          List.of(new ConsumerHook.Realized("workflow-shape-runner", Audience.USER)));
      var gates = new WorkflowGateRegistry();
      var engine = new ConversationEngine(ConversationShapeCatalog.of("core", List.of()), List.of());
      var runner = new WorkflowShapeRunner(() -> engine, WorkflowCatalog.of("core", List.of(workflow)),
          () -> fixture.catalog, () -> OperationCatalog.of("core", List.of()),
          new GatedOperationExecutor(() -> fixture.router, () -> fixture.capsules, TransportTag.WORKFLOW),
          gates, new IntentGateEvaluator(new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog()));
      var names = new ArrayList<String>();
      var workflowSession = new AtomicReference<String>();
      runner.run(Map.of("workflowId", workflow.id().value()), Audience.USER, event -> {
        names.add(event.name());
        if ("session_started".equals(event.name())) workflowSession.set((String) event.payload().get("sessionId"));
        if ("tool_call_pending".equals(event.name())) {
          assertTrue(gates.complete((String) event.payload().get("callId"), true));
        }
        assertNotEquals("error", event.name(), event.payload().toString());
      }, caller.context());
      assertTrue(names.contains("tool_exec_completed"), names.toString());
      assertEquals("done", names.getLast());
      assertNotNull(workflowSession.get());
      fixture.assertAcceptedAndDetach(caller, TransportTag.WORKFLOW, workflowSession.get());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final EngineAdmissionController admission = new EngineAdmissionController(2, 2, 1);
    final EngineContext origin = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "nested-agent",
        Optional.of("agent-session"), Optional.of("caller-marker"), TransportTag.AGENT_LOOP,
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    final OperationCatalog catalog = new AgentToolsOperationCatalog();
    final Operation operation = catalog.findById(AgentToolsOperationCatalog.INGEST_FILES).orElseThrow();
    final ConsentCapsuleService capsules = new ConsentCapsuleService();
    final AtomicReference<EngineContext> handlerContext = new AtomicReference<>();
    final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    final SqliteOperationStore store;
    final OperationExecutorImpl executor;
    final BackendIntentRouterImpl router;

    Fixture(Path directory) throws Exception {
      var clock = Clock.systemUTC();
      store = new SqliteOperationStore(directory.resolve("operations.db"));
      var attempts = new OperationAttemptRunnerImpl(store, clock, Set.of(OperationKind.INGEST));
      var handlers = new HandlerRegistry();
      handlers.register(operation.id(), new OperationHandler() {
        @Override public OperationResult execute(String args, EngineContext context) {
          throw new AssertionError("Only prepared dispatch is permitted");
        }
        @Override public OperationPreparation prepare(String args, InvocationProvenance provenance, EngineContext context) {
          return new OperationPreparation(args, "caller-forwarding.v1", "{}");
        }
        @Override public void validatePreparation(OperationPreparation preparation) {
          if (!"caller-forwarding.v1".equals(preparation.replaySchema())) throw new IllegalArgumentException("schema");
        }
        @Override public OperationApprovalPreview approvalPreview(OperationPreparation preparation) {
          return new OperationApprovalPreview("Inert forwarding fixture");
        }
        @Override public OperationExecution executePrepared(OperationPreparation preparation,
            InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
          assertTrue(handlerContext.compareAndSet(null, context), "only one effect may start");
          return new OperationExecution(OperationResult.success("accepted"), completion);
        }
      });
      executor = new OperationExecutorImpl(attempts, admission, handlers, null, Map.of(), clock,
          new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog(), null, capsules);
      router = new BackendIntentRouterImpl(catalog, executor, CoreIntentSourceCatalog.catalog(),
          new IntentEnvelopeChangeRegistry());
    }

    void assertAcceptedAndDetach(EngineWorkHandle caller, TransportTag transport, String sessionId) {
      EngineContext child = handlerContext.get();
      assertNotNull(child, "the actual orchestration route must reach the prepared handler");
      assertEquals(origin.clientKind(), child.clientKind());
      assertEquals(origin.clientId(), child.clientId());
      assertEquals(Optional.of(sessionId), child.sessionId());
      assertEquals(transport.name(), child.transport());
      assertEquals(EngineContext.Survival.DURABLE, child.survival());
      assertTrue(child.workId().isPresent());
      assertNotEquals(caller.context().workId(), child.workId());
      assertEquals(Optional.of(new OperationAuthorizationBasis.EphemeralCapsule().encode()), child.grantReference());
      assertEquals(2, admission.activeWorkCount());
      var rows = store.openRecords();
      assertEquals(1, rows.size());
      var row = rows.getFirst();
      assertEquals(OperationState.RUNNING, row.state());
      assertEquals(EngineContext.Survival.DURABLE, row.context().survival());
      assertEquals(child.grantReference(), row.context().grantReference());
      assertEquals(child.clientId(), row.context().clientId());
      assertTrue(row.context().workId().isEmpty());
      caller.cancel("caller cancelled after acceptance");
      caller.close();
      try (var owner = admission.attach(child)) {
        assertEquals(EngineContext.Urgency.BACKGROUND, owner.context().urgency());
        assertTrue(owner.cancellationReason().isEmpty());
      }
      assertEquals(1, admission.activeWorkCount());
      completion.complete(OperationResult.success("producer complete"));
      assertEquals(0, admission.activeWorkCount());
      assertEquals(OperationState.COMPLETE, store.find(row.key()).orElseThrow().state());
    }

    @Override public void close() throws Exception {
      completion.complete(OperationResult.success("fixture cleanup"));
      store.close();
    }
  }
}
