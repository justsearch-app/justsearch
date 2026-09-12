/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.BackgroundRunService;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.engine.DefaultEngineExecutorRegistry;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class BackgroundWorkLifetimeTest {

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path operationDirectory;
  private io.justsearch.app.observability.operations.SqliteOperationStore operationStore;
  private io.justsearch.app.api.operations.OperationAttemptRunner attempts;
  @org.junit.jupiter.api.BeforeEach
  void openOperationRunner() throws Exception {
    operationStore = new io.justsearch.app.observability.operations.SqliteOperationStore(operationDirectory.resolve("operations.db"));
    attempts = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operationStore,
        java.time.Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SCHEDULED_RUN));
  }
  @org.junit.jupiter.api.AfterEach
  void closeOperationRunner() throws Exception {
    if (operationStore != null) operationStore.close();
  }

  @Test
  void failedAcceptanceDoesNotSubmitATimer() throws Exception {
    var agent = mock(AgentService.class);
    try (var executors = new DefaultEngineExecutorRegistry()) {
      var background = new BackgroundRunService(attempts, agent, executors);
      try {
        operationStore.close();
        assertThrows(io.justsearch.app.api.operations.OperationStoreException.class,
            () -> background.schedule(AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "probe"))),
                Duration.ofDays(1), TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND)));
        assertEquals(0, executors.snapshot().registrations().stream().mapToInt(row -> row.queuedTasks()).sum());
        verifyNoInteractions(agent);
      } finally { background.shutdown(); }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"DONE,COMPLETE,SUCCESS", "ERROR,FAILED,AGENT_RUN_FAILED", "CANCELLED,CANCELLED,cancelled"})
  void bootReconcilesTheDurableAgentOutcomeWithoutRerunning(String agentState, String operationState, String code)
      throws Exception {
    var context = TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND);
    var prepared = attempts.accept(new io.justsearch.app.api.operations.OperationAttemptRunner.Request(null,
        io.justsearch.app.api.operations.OperationDescriptor.invocation(
            io.justsearch.agent.api.registry.OperationKind.SCHEDULED_RUN, null, "{}", false), context, null));
    attempts.start(prepared, handle -> {
      handle.checkpoint("previous-agent-run", 0, 0);
      return new io.justsearch.agent.api.registry.OperationExecution(
          io.justsearch.agent.api.registry.OperationResult.success("Started"), new CompletableFuture<>());
    });
    operationStore.close();
    operationStore = new io.justsearch.app.observability.operations.SqliteOperationStore(operationDirectory.resolve("operations.db"));
    attempts = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(operationStore,
        java.time.Clock.systemUTC(), java.util.Set.of(io.justsearch.agent.api.registry.OperationKind.SCHEDULED_RUN));
    var agent = mock(AgentService.class);
    when(agent.sessionSnapshot("previous-agent-run")).thenReturn(Map.of("state", agentState));
    try (var executors = new DefaultEngineExecutorRegistry()) {
      var background = new BackgroundRunService(attempts, agent, executors);
      try {
        var recovered = operationStore.find(prepared.accepted().key()).orElseThrow();
        assertEquals(operationState, recovered.state().name());
        assertEquals(code, recovered.receipt().code());
        assertEquals("previous-agent-run", recovered.receipt().executionId());
        verify(agent, never()).runAgent(any(), any(), anyBoolean(), any());
      } finally { background.shutdown(); }
    }
  }

  @Test
  void scheduleAcceptsBeforeTimerAndShutdownRecordsNoEffectRefusal() throws Exception {
    var agent = mock(AgentService.class);
    try (var executors = new DefaultEngineExecutorRegistry()) {
      var background = new BackgroundRunService(attempts, agent, executors);
      var prepared = background.schedule(AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "private prompt"))),
          Duration.ofDays(1), TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND));
      try {
        assertEquals(io.justsearch.app.api.operations.OperationState.ACCEPTED,
            operationStore.find(prepared.accepted().key()).orElseThrow().state());
        assertFalse(prepared.accepted().descriptor().identityJson().contains("private prompt"));
        verifyNoInteractions(agent);
        background.shutdown();
        var terminal = prepared.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals("ENGINE_SHUTDOWN", terminal.failureReason());
        assertEquals(io.justsearch.app.api.operations.OperationState.FAILED, terminal.state());
        assertNull(terminal.startedAt());
        verifyNoInteractions(agent);
      } finally { background.shutdown(); }
    }
  }

  @Test
  void fireTimeAdmissionRefusalFailsAcceptedRowWithoutStartingAgent() throws Exception {
    var admission = mock(io.justsearch.app.api.EngineAdmissionService.class);
    when(admission.admit(any(), eq(false))).thenThrow(new io.justsearch.app.api.EngineAdmissionException(
        io.justsearch.app.api.EngineAdmissionException.Reason.FROZEN, 1));
    var agent = mock(AgentService.class);
    try (var executors = new DefaultEngineExecutorRegistry()) {
      var background = new BackgroundRunService(attempts, agent, executors, admission);
      try {
        var prepared = background.schedule(AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "probe"))),
            Duration.ZERO, TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND));
        var terminal = prepared.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals("ADMISSION_FROZEN", terminal.failureReason());
        assertEquals(io.justsearch.app.api.operations.OperationState.FAILED, terminal.state());
        assertNull(terminal.startedAt());
        verifyNoInteractions(agent);
      } finally { background.shutdown(); }
    }
  }

  @Test
  void agentReturnCannotReplaceTheDurableFailedRun() throws Exception {
    var agent = mock(AgentService.class);
    doAnswer(call -> {
      java.util.function.Consumer<io.justsearch.agent.api.AgentEvent> sink = call.getArgument(1);
      sink.accept(new io.justsearch.agent.api.AgentEvent.SessionStarted("agent-run-1"));
      return null;
    }).when(agent).runAgent(any(), any(), eq(true), any());
    when(agent.sessionSnapshot("agent-run-1")).thenReturn(Map.of("state", "ERROR"));
    try (var executors = new DefaultEngineExecutorRegistry()) {
      var background = new BackgroundRunService(attempts, agent, executors);
      try {
        var prepared = background.schedule(AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "probe"))),
            Duration.ZERO, TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND));
        var terminal = prepared.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
        assertEquals("AGENT_RUN_FAILED", terminal.failureReason());
        assertEquals(io.justsearch.app.api.operations.OperationState.FAILED, terminal.state());
        assertEquals("agent-run-1", terminal.checkpointCursor());
        assertEquals(0, terminal.unitsCompleted());
      } finally { background.shutdown(); }
    }
  }

  @Test
  void scheduledChildUsesFreshIdentityAndClosesOnlyAfterActualRunUnwinds() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var caller = TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND);
    EngineContext stale;
    try (var original = admission.admit(caller, false)) { stale = original.context(); }
    var started = new CompletableFuture<EngineContext>();
    var completed = new CompletableFuture<Void>();
    var release = new CountDownLatch(1);
    var agent = mock(AgentService.class);
    doAnswer(call -> {
      EngineContext context = call.getArgument(3);
      try (var observed = admission.attach(context)) {
        observed.onCompletion(() -> completed.complete(null));
        started.complete(context);
        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release missing");
      }
      throw new IllegalStateException("deliberate run failure still releases its child");
    }).when(agent).runAgent(any(), any(), eq(true), any());
    var executors = new DefaultEngineExecutorRegistry();
    var background = new BackgroundRunService(attempts, agent, executors, admission);
    try {
      background.schedule(AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "probe"))),
          Duration.ZERO, stale);
      var child = started.get(3, TimeUnit.SECONDS);
      assertNotEquals(stale.workId(), child.workId());
      assertEquals(caller.withWorkId(child.workId().orElseThrow()), child);
      assertThrows(io.justsearch.app.api.EngineAdmissionException.class, () -> admission.attach(caller));
      release.countDown();
      completed.get(3, TimeUnit.SECONDS);
      assertThrows(io.justsearch.app.api.EngineAdmissionException.class, () -> admission.attach(child));
      try (var next = admission.attach(caller)) { assertTrue(next.context().workId().isPresent()); }
    } finally {
      release.countDown();
      background.shutdown();
      executors.close();
    }
  }

  @Test
  void productionRegistryRefusesBackgroundTimersPastCanonicalQueueBound() {
    var agent = mock(AgentService.class);
    var executors = new DefaultEngineExecutorRegistry();
    var background = new BackgroundRunService(attempts, agent, executors);
    try {
      int queueCapacity = executors.limits(EngineExecutorSpec.Kind.BACKGROUND).maxQueue();
      var registration = executors.snapshot().registrations().stream()
          .filter(row -> row.spec().name().equals("head.background-agent-run"))
          .findFirst()
          .orElseThrow();
      assertEquals(EngineExecutorSpec.Kind.BACKGROUND, registration.spec().kind());
      assertEquals(EngineExecutorSpec.Mode.SCHEDULED, registration.spec().mode());
      assertEquals(1, registration.spec().threadCount());
      assertEquals(queueCapacity, registration.spec().queueCapacity());
      assertEquals(1, registration.spec().maxInstances());
      for (int i = 0; i < queueCapacity; i++) {
        background.schedule(
            AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "queued"))),
            Duration.ofDays(1),
            TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND));
      }

      EngineExecutorRejectedException refusal =
          assertThrows(
              EngineExecutorRejectedException.class,
              () ->
                  background.schedule(
                      AgentRequest.singleTurn(
                          List.of(Map.of("role", "user", "content", "refused"))),
                      Duration.ofDays(1),
                      TestRequestContexts.browser()
                          .withUrgency(EngineContext.Urgency.BACKGROUND)));
      assertEquals(EngineExecutorRejectedException.Reason.QUEUE_LIMIT, refusal.reason());
      verifyNoInteractions(agent);
    } finally {
      background.shutdown();
      executors.close();
    }
  }

  @Test
  void shutdownCancelsPendingBackgroundTimerWithoutRunningItAndIsIdempotent() {
    var agent = mock(AgentService.class);
    var executors = new DefaultEngineExecutorRegistry();
    var background = new BackgroundRunService(attempts, agent, executors);
    try {
      background.schedule(
          AgentRequest.singleTurn(List.of(Map.of("role", "user", "content", "pending"))),
          Duration.ofDays(1),
          TestRequestContexts.browser().withUrgency(EngineContext.Urgency.BACKGROUND));
      background.shutdown();
      background.shutdown();

      verifyNoInteractions(agent);
      assertTrue(executors.snapshot().registrations().isEmpty());
    } finally {
      background.shutdown();
      executors.close();
    }
  }
}
