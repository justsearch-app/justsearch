package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.javalin.Javalin;
import io.justsearch.agent.api.conversation.ContextInjector;
import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.ExecutionMode;
import io.justsearch.agent.api.conversation.InjectorResult;
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
import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.app.services.conversation.IterationControllerRegistry;
import io.justsearch.app.services.conversation.PromptContributorRegistry;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import io.justsearch.app.services.conversation.spi.QueryRewriteInjector;
import io.justsearch.ui.api.routes.AiRoutes;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * S2.3 / N2.3 — the reader's Stop on an Ask reaches the Engine, which ends the turn and saves it as
 * stopped, with its question and its "Interpreted as" note, so a reload says what the live turn said.
 *
 * <p>Driven over real HTTP against the production route table ({@link AiRoutes}), a real engine
 * with Engine work admission and the file store a reload reads. The dispatch is a raw socket so the
 * test can leave the stream the way a browser does — closed mid-answer — and the model is held
 * until the turn is either cancelled or released, so "the Engine kept going" and "the Engine
 * stopped" are two different saved histories.
 */
final class ChatStopTurnHttpTest {

  private static final ConversationShapeRef SHAPE = new ConversationShapeRef("core.test-stoppable-ask");
  private static final String NOTE = "Who owns Project Cobalt?";
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @TempDir Path storeDir;

  private Javalin app;
  private HeldAi ai;
  private ChatController chat;
  private final List<HeldStep> held = new java.util.ArrayList<>();
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @BeforeEach
  void setup() {
    ai = new HeldAi();
  }

  @AfterEach
  void teardown() {
    ai.release.countDown();
    held.forEach(step -> step.step.countDown());
    if (app != null) app.stop();
    if (chat != null) chat.shutdown();
  }

  @Test
  void stopRightAfterTheRewriteSavesTheTurnStoppedWithItsNote() throws Exception {
    start(List.of());
    try (Dispatch turn = dispatch("conv-1", "t-1", "And who owns it?")) {
      turn.awaitEvent("rag.rewrite");
      HttpResponse<String> stop = stop("conv-1", "t-1");
      turn.close();

      List<Map<String, Object>> history = awaitOutcome("conv-1");
      assertEquals("And who owns it?", history.get(0).get("content"));
      Map<String, Object> outcome = history.get(1);
      assertEquals(NOTE, outcome.get("standaloneQuestion"), "the note shown live is saved: " + history);
      assertEquals("CANCELLED", outcome.get("disposition"), "the turn is saved as stopped: " + history);
      assertEquals("assistant", outcome.get("role"));
      assertEquals("", outcome.get("content"), "nothing was answered before the Stop");
      assertEquals(2, history.size(), history.toString());
      assertEquals(200, stop.statusCode(), stop.body());
      assertTrue(MAPPER.readTree(stop.body()).get("stopped").asBoolean(), stop.body());
    }
  }

  @Test
  void aStopThatOvertakesItsDispatchStopsTheTurnWhenItStarts() throws Exception {
    start(List.of());
    // Stop pressed in the instant after Send: the Stop reaches the Engine before the dispatch has
    // registered its turn.
    HttpResponse<String> stop = stop("conv-1", "t-1");
    assertEquals(200, stop.statusCode(), stop.body());
    try (Dispatch turn = dispatch("conv-1", "t-1", "And who owns it?")) {
      List<Map<String, Object>> history = awaitOutcome("conv-1");
      assertEquals("And who owns it?", history.get(0).get("content"));
      Map<String, Object> outcome = history.get(1);
      assertEquals("CANCELLED", outcome.get("disposition"), "the turn is saved as stopped: " + history);
      assertEquals("assistant", outcome.get("role"));
      assertEquals("", outcome.get("content"), history.toString());
      assertNull(outcome.get("terminalError"), history.toString());
      assertEquals(2, history.size(), history.toString());
      turn.awaitEnd();
      // Nothing was rewritten or answered: no note live, none saved.
      assertFalse(turn.received().contains("event: rag.rewrite"), turn.received());
      assertNull(outcome.get("standaloneQuestion"), history.toString());
      assertEquals(0, ai.streams.get(), "the Engine never started answering");
    }
  }

  @Test
  void aStopForAFinishedTurnIsNotKeptForALaterDispatch() throws Exception {
    start(List.of("Ada owns"));
    ai.release.countDown();
    try (Dispatch turn = dispatch("conv-1", "t-1", "And who owns it?")) {
      turn.awaitEnd();
    }
    HttpResponse<String> late = stop("conv-1", "t-1");
    assertEquals(200, late.statusCode(), late.body());
    assertFalse(MAPPER.readTree(late.body()).get("stopped").asBoolean(), late.body());
    // A later dispatch that names the same turn is not stopped by the Stop that came too late.
    try (Dispatch again = dispatch("conv-1", "t-1", "And who owns it now?")) {
      again.awaitEnd();
    }
    List<Map<String, Object>> history = new FileConversationStore(storeDir).loadHistory("conv-1");
    assertEquals(4, history.size(), history.toString());
    assertEquals("Ada owns it.", history.get(3).get("content"), history.toString());
    assertNull(history.get(3).get("disposition"), history.toString());
  }

  @Test
  void stopWhileTheDocumentsAreRetrievedSavesTheTurnStoppedNotFailed() throws Exception {
    var retrieval = heldStep();
    start(List.of(), List.of(retrieval.injector()), List.of());
    try (Dispatch turn = dispatch("conv-1", "t-1", "And who owns it?")) {
      turn.awaitEvent("rag.rewrite");
      assertTrue(retrieval.reached.await(5, TimeUnit.SECONDS));
      stop("conv-1", "t-1");
      turn.close();
      // A retrieval cut off by the cancellation ends the way a failed one does.
      retrieval.step.countDown();

      List<Map<String, Object>> history = awaitOutcome("conv-1");
      assertEquals("And who owns it?", history.get(0).get("content"));
      Map<String, Object> outcome = history.get(1);
      assertEquals("CANCELLED", outcome.get("disposition"), history.toString());
      assertNull(outcome.get("terminalError"), "a Stop is not a failure: " + history);
      assertEquals(NOTE, outcome.get("standaloneQuestion"), history.toString());
      assertEquals(2, history.size(), history.toString());
    }
  }

  @Test
  void stopWhileTheAnswerIsCheckedSavesTheWholeTextAsStopped() throws Exception {
    var check = heldStep();
    start(List.of("Ada owns"), List.of(), List.of(check.consumer()));
    ai.release.countDown();
    try (Dispatch turn = dispatch("conv-1", "t-1", "And who owns it?")) {
      assertTrue(check.reached.await(5, TimeUnit.SECONDS));
      stop("conv-1", "t-1");
      turn.close();
      check.step.countDown();

      List<Map<String, Object>> history = awaitOutcome("conv-1");
      Map<String, Object> outcome = history.get(1);
      assertEquals("Ada owns it.", outcome.get("content"), history.toString());
      assertEquals("CANCELLED", outcome.get("disposition"), history.toString());
      assertNull(outcome.get("terminalError"), history.toString());
      assertEquals(NOTE, outcome.get("standaloneQuestion"), history.toString());
      assertEquals(2, history.size(), history.toString());
    }
  }

  @Test
  void lateStopSavesWhatStreamedAsStopped() throws Exception {
    start(List.of("Half an ans"));
    try (Dispatch turn = dispatch("conv-1", "t-1", "And who owns it?")) {
      turn.awaitEvent("chunk");
      stop("conv-1", "t-1");
      turn.close();

      List<Map<String, Object>> history = awaitOutcome("conv-1");
      Map<String, Object> outcome = history.get(1);
      assertEquals("Half an ans", outcome.get("content"), history.toString());
      assertEquals("CANCELLED", outcome.get("disposition"), history.toString());
      assertEquals(NOTE, outcome.get("standaloneQuestion"), history.toString());
      assertEquals(2, history.size(), history.toString());
    }
  }

  @Test
  void aClosedConnectionWithoutStopKeepsTheTurnRunningToItsAnswer() throws Exception {
    start(List.of("Ada owns"));
    try (Dispatch turn = dispatch("conv-1", "t-1", "And who owns it?")) {
      turn.awaitEvent("chunk");
      turn.close();
      assertFalse(ai.cancelled.await(500, TimeUnit.MILLISECONDS), "leaving is not stopping");
      ai.release.countDown();

      List<Map<String, Object>> history = awaitOutcome("conv-1");
      Map<String, Object> outcome = history.get(1);
      assertEquals("Ada owns it.", outcome.get("content"), history.toString());
      assertNull(outcome.get("disposition"), history.toString());
      assertEquals(NOTE, outcome.get("standaloneQuestion"), history.toString());
    }
  }

  @Test
  void aStopAfterTheTurnFinishedChangesNothing() throws Exception {
    start(List.of("Ada owns"));
    ai.release.countDown();
    try (Dispatch turn = dispatch("conv-1", "t-1", "And who owns it?")) {
      turn.awaitEnd();
    }
    List<Map<String, Object>> before = awaitOutcome("conv-1");
    assertEquals("Ada owns it.", before.get(1).get("content"));
    assertNull(before.get(1).get("disposition"));

    HttpResponse<String> stop = stop("conv-1", "t-1");
    assertEquals(200, stop.statusCode(), stop.body());
    assertFalse(MAPPER.readTree(stop.body()).get("stopped").asBoolean(), stop.body());
    assertEquals(before, new FileConversationStore(storeDir).loadHistory("conv-1"));
  }

  @Test
  void aStopNamingAnotherTurnLeavesTheRunningTurnAlone() throws Exception {
    start(List.of("Ada owns"));
    try (Dispatch turn = dispatch("conv-1", "t-2", "And who owns it?")) {
      turn.awaitEvent("chunk");
      HttpResponse<String> stop = stop("conv-1", "t-1");
      assertEquals(200, stop.statusCode(), stop.body());
      assertFalse(MAPPER.readTree(stop.body()).get("stopped").asBoolean(), stop.body());
      ai.release.countDown();
      turn.awaitEnd();
    }
    List<Map<String, Object>> history = awaitOutcome("conv-1");
    assertEquals("Ada owns it.", history.get(1).get("content"));
    assertNull(history.get(1).get("disposition"));
  }

  // ── harness ──────────────────────────────────────────────────────────────────────────────────

  private HeldStep heldStep() {
    var step = new HeldStep();
    held.add(step);
    return step;
  }

  private void start(List<String> chunksBeforeHold) {
    start(chunksBeforeHold, List.of(), List.of());
  }

  private void start(
      List<String> chunksBeforeHold, List<ContextInjector> retrieval, List<StreamConsumer> consumers) {
    ai.chunks = chunksBeforeHold;
    var injectors = new java.util.ArrayList<ContextInjector>(List.of(new RewriteInjector()));
    injectors.addAll(retrieval);
    var store = new FileConversationStore(storeDir);
    var engine = new ConversationEngine(
        ConversationShapeCatalog.of("core", List.of(shape(
            injectors.stream().map(ContextInjector::id).toList(),
            consumers.stream().map(StreamConsumer::id).toList()))),
        List.of(),
        PromptContributorRegistry.of(List.of()),
        ContextInjectorRegistry.of(injectors),
        StreamConsumerRegistry.of(consumers),
        IterationControllerRegistry.of(List.of()),
        () -> ai,
        store,
        new EngineAdmissionController(4, 8, 1));
    chat = new ChatController(
        new io.justsearch.core.execution.TestEngineExecutors(), engine, new SseWriter(null), null, store);
    app = Javalin.create(cfg -> {
          cfg.showJavalinBanner = false;
          cfg.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
        });
    AiRoutes.register(app, mock(PreviewController.class), mock(AiInstallController.class),
        mock(AiPackController.class), mock(AiRuntimeController.class), mock(AiModelsController.class), chat);
    app.start("127.0.0.1", 0);
  }

  private static ConversationShape shape(List<String> injectorIds, List<String> consumerIds) {
    return new ConversationShape(
        SHAPE,
        new Presentation(new I18nKey("test.label"), new I18nKey("test.desc"), Optional.empty(), Optional.empty()),
        Audience.USER,
        Provenance.core("v1"),
        ExecutionMode.SUBSTRATE_DRIVEN,
        IterationMode.ONE_SHOT,
        PersistenceMode.EPHEMERAL,
        List.of(),
        injectorIds,
        consumerIds,
        SingleHopController.ID,
        List.of(),
        true);
  }

  private HttpResponse<String> stop(String conversationId, String turnId) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + app.port() + "/api/chat/conversations/" + conversationId + "/stop"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                MAPPER.writeValueAsString(Map.of("turnId", turnId))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** The saved history once the turn has its outcome row (the question alone is not an outcome). */
  private List<Map<String, Object>> awaitOutcome(String conversationId) throws InterruptedException {
    var store = new FileConversationStore(storeDir);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    List<Map<String, Object>> history = store.loadHistory(conversationId);
    while (history.size() < 2 && System.nanoTime() < deadline) {
      Thread.sleep(25);
      history = store.loadHistory(conversationId);
    }
    assertTrue(history.size() >= 2,
        "after the Stop only the question was saved: no note and no stopped outcome — " + history);
    return history;
  }

  private Dispatch dispatch(String conversationId, String turnId, String question) throws Exception {
    String body = MAPPER.writeValueAsString(Map.of(
        "shapeId", SHAPE.value(), "conversationId", conversationId, "turnId", turnId,
        "question", question));
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    var socket = new Socket("127.0.0.1", app.port());
    OutputStream out = socket.getOutputStream();
    out.write(("POST /api/chat/dispatch HTTP/1.1\r\nHost: 127.0.0.1\r\n"
        + "Content-Type: application/json\r\nContent-Length: " + bytes.length
        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(bytes);
    out.flush();
    return new Dispatch(socket);
  }

  /** One dispatch stream, read on its own thread so the test can wait for a named event. */
  private static final class Dispatch implements AutoCloseable {
    private final Socket socket;
    private final StringBuffer received = new StringBuffer();
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private final Thread reader;

    Dispatch(Socket socket) {
      this.socket = socket;
      this.reader = Thread.ofPlatform().daemon().start(() -> {
        byte[] buf = new byte[4096];
        try (InputStream in = socket.getInputStream()) {
          for (int n; (n = in.read(buf)) > 0; ) {
            received.append(new String(buf, 0, n, StandardCharsets.UTF_8));
          }
        } catch (Exception closed) {
          // The test closed the socket, as a browser's abort does.
        }
        ended.complete(null);
      });
    }

    void awaitEvent(String name) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!received.toString().contains("event: " + name + "\n")) {
        if (System.nanoTime() > deadline) throw new AssertionError("no " + name + " in " + received);
        Thread.sleep(10);
      }
    }

    void awaitEnd() throws Exception {
      ended.get(5, TimeUnit.SECONDS);
    }

    String received() {
      return received.toString();
    }

    @Override
    public void close() throws Exception {
      socket.close();
      reader.join(5_000);
    }
  }

  /** Records a rewrite the way {@code QueryRewriteInjector} does and announces it live. */
  private static final class RewriteInjector implements ContextInjector {
    static final String ID = "core.test-stoppable-rewrite";

    @Override public String id() { return ID; }

    @Override public InjectorResult inject(ConversationContext ctx) {
      ctx.attributes().put(QueryRewriteInjector.ATTR_STANDALONE_QUESTION, NOTE);
      return new InjectorResult(
          List.of(Map.of("role", "user", "content", "And who owns it?")),
          List.of(new SseEvent("rag.rewrite", Map.of("standalone", NOTE))),
          Optional.empty());
    }
  }

  /**
   * A step of the turn that holds until the test lets it go: document retrieval (which then ends
   * the way a retrieval cut off by cancellation does, as RETRIEVAL_FAILED) or the answer's check
   * (which then throws the cancellation).
   */
  private static final class HeldStep {
    final CountDownLatch reached = new CountDownLatch(1);
    final CountDownLatch step = new CountDownLatch(1);

    private void hold() {
      reached.countDown();
      try {
        step.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    ContextInjector injector() {
      return new ContextInjector() {
        @Override public String id() { return "core.test-held-retrieval"; }

        @Override public InjectorResult inject(ConversationContext ctx) {
          hold();
          return new InjectorResult(List.of(), List.of(), Optional.of(new SseEvent("error",
              Map.of("error", "Retrieval failed", "errorCode", "RETRIEVAL_FAILED"))));
        }
      };
    }

    StreamConsumer consumer() {
      return new StreamConsumer() {
        @Override public String id() { return "core.test-held-check"; }

        @Override public StreamConsumerResult onChunk(String chunkText, ConversationContext ctx) {
          return StreamConsumerResult.empty();
        }

        @Override public StreamConsumerResult onDone(String fullText, ConversationContext ctx) {
          hold();
          throw new java.util.concurrent.CompletionException(new EngineWorkCancelledException("user_stop"));
        }
      };
    }
  }

  /**
   * A model that streams its first chunks, then holds until its work is cancelled (and ends the way
   * the inference lane does, with the cancellation) or the test releases it (and finishes).
   */
  private static final class HeldAi implements OnlineAiService {
    volatile List<String> chunks = List.of();
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch cancelled = new CountDownLatch(1);
    final AtomicInteger streams = new AtomicInteger();

    @Override public boolean isAvailable() { return true; }

    @Override public boolean isStartingUp() { return false; }

    @Override public CompletableFuture<String> summarize(String content) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override public CompletableFuture<String> askQuestion(String question, String context) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override public void stream(StreamRequest request, StreamSink sink) {
      streams.incrementAndGet();
      chunks.forEach(sink.onContent());
      var ended = new AtomicBoolean();
      if (request.work() != null) {
        request.work().onCancel(reason -> {
          cancelled.countDown();
          if (ended.compareAndSet(false, true)) sink.onError().accept(new EngineWorkCancelledException(reason));
          release.countDown();
        });
      }
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (ended.compareAndSet(false, true)) {
        sink.onContent().accept(chunks.isEmpty() ? "Ada owns it." : " it.");
        sink.onComplete().accept("stop");
      }
    }
  }
}
