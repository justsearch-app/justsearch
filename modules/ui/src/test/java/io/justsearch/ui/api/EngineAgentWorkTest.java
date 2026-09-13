/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.justsearch.agent.AgentLoopService;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentRequest;
import io.justsearch.agent.api.registry.AgentToolEmitter;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.engine.EngineAdmissionController;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Pins AgentLoopService's cancellation handoff to the real engine work owner. */
final class EngineAgentWorkTest {
  private static final long AWAIT_SECONDS = 5;

  @Test
  void userStopCancelsActiveModelWithoutRetryOrToolDispatch() throws Exception {
    runCancellationScenario(false, "user_stop");
  }

  @Test
  void engineCancellationWinsOverLaterUserStopAndTerminatesSession() throws Exception {
    runCancellationScenario(true, "upgrade_shutdown");
  }

  private static void runCancellationScenario(boolean engineCancelsFirst, String expectedReason)
      throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var ai = new CancellationOwnedAi();
    OperationDispatcher dispatcher = mock(OperationDispatcher.class);
    var service =
        new AgentLoopService(
            ai,
            OperationCatalog.of("core", List.of()),
            dispatcher,
            new OneToolEmitter(),
            null,
            null);
    service.setEngineAdmission(admission);

    var events = new CopyOnWriteArrayList<AgentEvent>();
    var sessionId = new CompletableFuture<String>();
    ExecutorService caller = Executors.newSingleThreadExecutor();

    try (EngineWorkHandle requestWork = admission.admit(TestRequestContexts.browser(), false)) {
      var expectedWorkId = requestWork.context().workId().orElseThrow();
      Future<?> run =
          caller.submit(
              () ->
                  service.runAgent(
                      new AgentRequest(
                          List.of(Map.of("role", "user", "content", "stop this run")),
                          List.of(),
                          3),
                      event -> {
                        events.add(event);
                        if (event instanceof AgentEvent.SessionStarted started) {
                          sessionId.complete(started.sessionId());
                        }
                      },
                      requestWork.context()));

      String activeSession = sessionId.get(AWAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(ai.producerStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS), "model owner started");
      assertNotNull(ai.request.get());
      assertNotNull(ai.request.get().work());
      assertEquals(expectedWorkId, ai.request.get().work().context().workId().orElseThrow());
      requestWork.close();

      if (engineCancelsFirst) {
        admission.cancelInteractive("upgrade_shutdown");
        service.cancelSession(activeSession);
      } else {
        service.cancelSession(activeSession);
        admission.cancelInteractive("later_system_reason");
      }

      assertEquals(expectedReason, ai.ownerCancellation.get());
      assertTrue(ai.terminalDelivered.await(AWAIT_SECONDS, TimeUnit.SECONDS));
      run.get(AWAIT_SECONDS, TimeUnit.SECONDS);

      assertFalse(ai.producerExited.await(0, TimeUnit.SECONDS));
      assertCapacityOccupied(admission);
      assertEquals(1, ai.streamCalls.get(), "cancellation is not retried");
      verifyNoInteractions(dispatcher);

      List<AgentEvent.AgentError> errors =
          events.stream()
              .filter(AgentEvent.AgentError.class::isInstance)
              .map(AgentEvent.AgentError.class::cast)
              .toList();
      assertEquals(1, errors.size());
      assertEquals("CANCELLED", errors.get(0).errorCode());
      assertEquals("CANCELLED", errors.get(0).errorClass());
      assertEquals("ABORT", errors.get(0).retryAction());
      assertEquals(expectedReason, errors.get(0).reasonCode());
      assertEquals(0, events.stream().filter(AgentEvent.AgentDone.class::isInstance).count());

      ai.releaseProducer.countDown();
      assertTrue(ai.producerExited.await(AWAIT_SECONDS, TimeUnit.SECONDS));
      assertCapacityReleased(admission);
    } finally {
      ai.releaseProducer.countDown();
      caller.shutdownNow();
      ai.close();
    }
  }

  private static void assertCapacityOccupied(EngineAdmissionController admission) {
    EngineAdmissionException refused =
        assertThrows(
            EngineAdmissionException.class,
            () -> admission.admit(TestRequestContexts.mcp("other-client"), false));
    assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, refused.reason());
  }

  private static void assertCapacityReleased(EngineAdmissionController admission) {
    try (EngineWorkHandle next = admission.admit(TestRequestContexts.mcp("other-client"), false)) {
      assertTrue(next.context().workId().isPresent());
    }
  }

  /** Supplies one harmless model-visible tool so the real loop reaches its LLM step. */
  private static final class OneToolEmitter implements AgentToolEmitter {
    private static final Map<String, Object> TOOL =
        Map.of(
            "type",
            "function",
            "function",
            Map.of(
                "name",
                "core_probe",
                "description",
                "test probe",
                "parameters",
                Map.of("type", "object", "properties", Map.of())));

    @Override
    public List<Operation> offer(OperationCatalog catalog, Collection<String> selectedNames) {
      return List.of();
    }

    @Override
    public List<Map<String, Object>> emit(
        OperationCatalog catalog, Collection<String> selectedNames) {
      return List.of(TOOL);
    }
  }

  /** A model producer that owns a retained reference until its actual async run exits. */
  private static final class CancellationOwnedAi implements OnlineAiService, AutoCloseable {
    private final ExecutorService producer = Executors.newSingleThreadExecutor();
    private final AtomicReference<StreamRequest> request = new AtomicReference<>();
    private final AtomicReference<String> ownerCancellation = new AtomicReference<>();
    private final AtomicInteger streamCalls = new AtomicInteger();
    private final CountDownLatch producerStarted = new CountDownLatch(1);
    private final CountDownLatch cancellationRequested = new CountDownLatch(1);
    private final CountDownLatch terminalDelivered = new CountDownLatch(1);
    private final CountDownLatch releaseProducer = new CountDownLatch(1);
    private final CountDownLatch producerExited = new CountDownLatch(1);

    @Override
    public void stream(StreamRequest streamRequest, StreamSink sink) {
      streamCalls.incrementAndGet();
      EngineWorkHandle producerWork = streamRequest.work().retain();
      request.set(streamRequest);
      producer.execute(
          () -> {
            try (producerWork;
                EngineWorkHandle.Registration ignored =
                    producerWork.onCancel(
                        reason -> {
                          ownerCancellation.compareAndSet(null, reason);
                          cancellationRequested.countDown();
                        })) {
              producerStarted.countDown();
              awaitUninterruptibly(cancellationRequested);
              sink.onError()
                  .accept(new EngineWorkCancelledException(ownerCancellation.get()));
              terminalDelivered.countDown();
              awaitUninterruptibly(releaseProducer);
            } finally {
              producerExited.countDown();
            }
          });
    }

    @Override
    public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.completedFuture(content);
    }

    @Override
    public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.completedFuture("unused");
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public boolean isStartingUp() {
      return false;
    }

    @Override
    public Integer llmContextTokens() {
      return 32_768;
    }

    @Override
    public void close() {
      releaseProducer.countDown();
      producer.shutdownNow();
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
