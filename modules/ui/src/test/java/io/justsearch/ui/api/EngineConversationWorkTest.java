/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ConversationStore;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.IterationMode;
import io.justsearch.agent.api.conversation.PersistenceMode;
import io.justsearch.agent.api.conversation.SingleHopController;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.conversation.StreamConsumer;
import io.justsearch.agent.api.conversation.StreamConsumerResult;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.ConversationShapeCatalog;
import io.justsearch.agent.api.registry.ConversationShapeRef;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.IterationControllerRegistry;
import io.justsearch.app.services.conversation.PromptContributorRegistry;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Pins ConversationEngine's ownership of admitted streaming work. */
final class EngineConversationWorkTest {
  private static final ConversationShapeRef SHAPE_ID =
      new ConversationShapeRef("core.engine-work-test");
  private static final long AWAIT_SECONDS = 5;

  @Test
  void cancellationWaitsForBlockedStreamCallbackAndProducerTerminal() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var ai = new ControlledAi(true);
    var engine = engine(ephemeralShape(List.of()), List.of(), ai, ConversationStore.noop(), admission);
    var events = Collections.synchronizedList(new ArrayList<SseEvent>());
    var sinkEntered = new CountDownLatch(1);
    var releaseSink = new CountDownLatch(1);
    ExecutorService caller = Executors.newSingleThreadExecutor();

    try (EngineWorkHandle requestWork = admission.admit(TestRequestContexts.browser(), false)) {
      var expectedWorkId = requestWork.context().workId().orElseThrow();
      Future<?> run =
          caller.submit(
              () ->
                  engine.run(
                      SHAPE_ID,
                      Map.of(),
                      Audience.USER,
                      event -> {
                        events.add(event);
                        if ("chunk".equals(event.name())) {
                          sinkEntered.countDown();
                          awaitUninterruptibly(releaseSink);
                        }
                      },
                      requestWork.context()));

      assertTrue(sinkEntered.await(AWAIT_SECONDS, TimeUnit.SECONDS), "producer reached the sink");
      OnlineAiService.StreamRequest streamRequest = ai.request.get();
      assertNotNull(streamRequest);
      assertNotNull(streamRequest.work());
      assertEquals(expectedWorkId, streamRequest.work().context().workId().orElseThrow());

      requestWork.close();
      admission.cancelInteractive("shutdown_requested");
      admission.cancelInteractive("later_reason_must_lose");

      assertEquals("shutdown_requested", ai.ownerCancellation.get());
      assertFalse(run.isDone(), "the blocked callback still owns the turn");
      assertEquals(0, terminalCount(events), "cancellation cannot overtake the callback");
      assertCapacityOccupied(admission);

      releaseSink.countDown();
      run.get(AWAIT_SECONDS, TimeUnit.SECONDS);
      assertFalse(ai.producerExited.await(0, TimeUnit.SECONDS));
      assertCapacityOccupied(admission);
      ai.releaseProducer.countDown();
      assertTrue(ai.producerExited.await(AWAIT_SECONDS, TimeUnit.SECONDS), "producer reference closed");

      assertEquals(1, named(events, "error").size());
      assertEquals("shutdown_requested", named(events, "error").get(0).payload().get("errorCode"));
      assertEquals(0, named(events, "done").size());
      assertCapacityReleased(admission);
    } finally {
      releaseSink.countDown();
      caller.shutdownNow();
      ai.close();
    }
  }

  @Test
  void cancellationAfterModelCompletionSuppressesDoneAndAssistantPersistence() throws Exception {
    var admission = new EngineAdmissionController(1, 1, 1);
    var ai = new ControlledAi(false);
    var consumer = new BlockingDoneConsumer();
    ConversationStore store = mock(ConversationStore.class);
    when(store.loadEffectiveContext("session-1")).thenReturn(List.of());
    when(store.excludedSourceIds("session-1")).thenReturn(List.of());
    var engine = engine(persistentShape(List.of(consumer.id())), List.of(consumer), ai, store, admission);
    var events = Collections.synchronizedList(new ArrayList<SseEvent>());
    ExecutorService caller = Executors.newSingleThreadExecutor();

    try (EngineWorkHandle requestWork = admission.admit(TestRequestContexts.browser(), false)) {
      Future<?> run =
          caller.submit(
              () ->
                  engine.run(
                      SHAPE_ID,
                      Map.of("sessionId", "session-1"),
                      Audience.USER,
                      events::add,
                      requestWork.context()));

      assertTrue(
          consumer.entered.await(AWAIT_SECONDS, TimeUnit.SECONDS),
          "model completed and onDone began");
      assertTrue(ai.producerExited.await(AWAIT_SECONDS, TimeUnit.SECONDS), "model owner exited");
      requestWork.close();

      admission.cancelInteractive("shutdown_requested");
      admission.cancelInteractive("later_reason_must_lose");
      assertFalse(run.isDone(), "blocked onDone still owns the conversation run");
      assertEquals(0, terminalCount(events));
      assertCapacityOccupied(admission);

      consumer.release.countDown();
      ExecutionException failure =
          assertThrows(
              ExecutionException.class,
              () -> run.get(AWAIT_SECONDS, TimeUnit.SECONDS));
      assertTrue(failure.getCause() instanceof EngineWorkCancelledException);
      assertEquals(
          "shutdown_requested",
          ((EngineWorkCancelledException) failure.getCause()).reasonCode());

      assertEquals(0, named(events, "done").size());
      verify(store, never()).appendMessage(anyString(), anyString(), any());
      assertCapacityReleased(admission);
    } finally {
      consumer.release.countDown();
      caller.shutdownNow();
      ai.close();
    }
  }

  private static ConversationEngine engine(
      ConversationShape shape,
      List<StreamConsumer> consumers,
      OnlineAiService ai,
      ConversationStore store,
      EngineAdmissionController admission) {
    return new ConversationEngine(
        ConversationShapeCatalog.of("core", List.of(shape)),
        List.of(),
        PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(List.<ContextInjector>of()),
        StreamConsumerRegistry.of(consumers),
        IterationControllerRegistry.of(List.of(SingleHopController.INSTANCE)),
        () -> ai,
        store,
        admission);
  }

  private static ConversationShape ephemeralShape(List<String> consumerIds) {
    return shape(PersistenceMode.EPHEMERAL, consumerIds);
  }

  private static ConversationShape persistentShape(List<String> consumerIds) {
    return shape(PersistenceMode.PERSISTENT, consumerIds);
  }

  private static ConversationShape shape(PersistenceMode persistence, List<String> consumerIds) {
    return new ConversationShape(
        SHAPE_ID,
        new Presentation(
            new I18nKey("test.label"),
            new I18nKey("test.description"),
            Optional.empty(),
            Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        persistence,
        List.of(),
        List.of(),
        consumerIds,
        SingleHopController.ID,
        List.of(),
        true);
  }

  private static void assertCapacityOccupied(EngineAdmissionController admission) {
    EngineAdmissionException refused =
        assertThrows(
            EngineAdmissionException.class,
            () -> admission.admit(TestRequestContexts.mcp("other-client"), false));
    assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT, refused.reason());
  }

  private static void assertCapacityReleased(EngineAdmissionController admission) {
    try (EngineWorkHandle ignored = admission.admit(TestRequestContexts.mcp("other-client"), false)) {
      assertNotNull(ignored.context().workId().orElse(null));
    }
  }

  private static long terminalCount(List<SseEvent> events) {
    synchronized (events) {
      return events.stream()
          .filter(event -> "done".equals(event.name()) || "error".equals(event.name()))
          .count();
    }
  }

  private static List<SseEvent> named(List<SseEvent> events, String name) {
    synchronized (events) {
      return events.stream().filter(event -> name.equals(event.name())).toList();
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

  private static final class BlockingDoneConsumer implements StreamConsumer {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public String id() {
      return "core.blocking-done";
    }

    @Override
    public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
      return StreamConsumerResult.empty();
    }

    @Override
    public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
      entered.countDown();
      awaitUninterruptibly(release);
      return StreamConsumerResult.empty();
    }
  }

  /** Producer fake that takes its own real work reference before crossing the async boundary. */
  private static final class ControlledAi implements OnlineAiService, AutoCloseable {
    private final ExecutorService producer = Executors.newSingleThreadExecutor();
    private final AtomicReference<StreamRequest> request = new AtomicReference<>();
    private final AtomicReference<String> ownerCancellation = new AtomicReference<>();
    private final CountDownLatch releaseProducer;
    private final CountDownLatch producerExited = new CountDownLatch(1);

    private ControlledAi(boolean holdAfterTerminal) {
      releaseProducer = new CountDownLatch(holdAfterTerminal ? 1 : 0);
    }

    @Override
    public void stream(StreamRequest streamRequest, StreamSink sink) {
      EngineWorkHandle producerWork = streamRequest.work().retain();
      request.set(streamRequest);
      producer.execute(
          () -> {
            try (producerWork;
                EngineWorkHandle.Registration ignored =
                    producerWork.onCancel(reason -> ownerCancellation.compareAndSet(null, reason))) {
              sink.onContent().accept("model text");
              Optional<String> cancelled = Optional.ofNullable(ownerCancellation.get());
              if (cancelled.isPresent()) {
                sink.onError().accept(new EngineWorkCancelledException(cancelled.orElseThrow()));
              } else {
                sink.onComplete().accept("stop");
              }
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
      return CompletableFuture.completedFuture("model text");
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
    public void close() {
      releaseProducer.countDown();
      producer.shutdownNow();
    }
  }
}
