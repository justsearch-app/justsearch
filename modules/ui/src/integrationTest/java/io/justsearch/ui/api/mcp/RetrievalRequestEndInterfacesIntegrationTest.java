/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.agent.api.conversation.*;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.*;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.conversation.ContextInjectorRegistry;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.conversation.IterationControllerRegistry;
import io.justsearch.app.services.conversation.PromptContributorRegistry;
import io.justsearch.app.services.conversation.StreamConsumerRegistry;
import io.justsearch.app.services.conversation.FileConversationStore;
import io.justsearch.app.services.conversation.spi.RAGContext;
import io.justsearch.app.services.worker.HeadSearchDeadline;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.ui.api.RetrieveContextController;
import io.justsearch.ui.api.TestRequestContexts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;

/** D1/D2/D7-D9/D12 and P1-P3/P7/P10 on a private embedded Engine, with no LLM. */
@Timeout(300)
final class RetrievalRequestEndInterfacesIntegrationTest extends MmrEngineFixture {
  private static final ConversationShapeRef SHAPE = new ConversationShapeRef("core.request-end");

  @Test
  void realEngineBudgetExpiresThroughHttpAndSavedConversation() throws Exception {
    start(null);
    try (var interfaces = new Interfaces()) {
      interfaces.mode = Mode.DEADLINE;
      var rest = interfaces.request("rest", false, QUESTION);
      assertEquals(504, rest.statusCode(), rest.body());
      assertError(JSON.readTree(rest.body()), "TIMEOUT");
      var mcp = interfaces.mcp(QUESTION);
      assertTrue(mcp.path("isError").asBoolean(), mcp.toString());
      assertError(mcp.path("structuredContent"), "TIMEOUT");
      assertTrue(mcp.path("content").toString().contains("TIMEOUT"));
      interfaces.assertTerminalConversation(false, "RETRIEVAL_TIMEOUT", null);
    }
  }

  @Test
  void chosenFileDeadlineKeepsWholeDocumentFallbackThroughInterfaces() throws Exception {
    start(null);
    try (var interfaces = new Interfaces()) {
      interfaces.mode = Mode.DEADLINE;
      // Chosen-file retrieval expiry remains an uncited whole-document answer (D2b/P10).
      var scoped = JSON.readTree(interfaces.request("rest", true, QUESTION).body());
      assertTrue(scoped.path("ok").asBoolean(), scoped.toString());
      assertEquals("FULLTEXT_FALLBACK", scoped.path("quality").path("retrieval_mode").asText());
      assertEquals(0, scoped.path("chunks").size());
      interfaces.assertSuccessfulConversation(true, false);
      assertTrue(interfaces.realDeadline.get() instanceof KnowledgeClientException);
      assertEquals(KnowledgeClientException.Status.DEADLINE_EXCEEDED,
          ((KnowledgeClientException) interfaces.realDeadline.get()).status());

      // A local wait expiring has the same chosen-file fallback, with a still-live parent.
      interfaces.mode = Mode.LOCAL_WAIT;
      interfaces.assertSuccessfulConversation(true, false);
      interfaces.assertTerminalConversation(false, "RETRIEVAL_TIMEOUT", null);
    }
  }

  @Test
  void realCancellationIsReportedAndPersistsAcrossNextTurn() throws Exception {
    start(null);
    try (var interfaces = new Interfaces()) {
      interfaces.mode = Mode.CANCEL;
      var rest = interfaces.request("rest", false, QUESTION);
      assertEquals(503, rest.statusCode(), rest.body());
      var body = JSON.readTree(rest.body());
      assertError(body, "SERVICE_UNAVAILABLE");
      assertEquals("CONTEXT_CLOSED", body.path("reasonCode").asText());
      var mcp = interfaces.mcp(QUESTION);
      assertTrue(mcp.path("isError").asBoolean(), mcp.toString());
      assertError(mcp.path("structuredContent"), "SERVICE_UNAVAILABLE");
      assertTrue(mcp.path("content").toString().contains("SERVICE_UNAVAILABLE"));
      interfaces.assertTerminalConversation(false, "SERVICE_UNAVAILABLE", "CONTEXT_CLOSED");
      interfaces.assertTerminalConversation(true, "SERVICE_UNAVAILABLE", "CONTEXT_CLOSED");
    }
  }

  @Test
  void readersStopStaysStoppedAcrossNextTurn() throws Exception {
    start(null);
    try (var interfaces = new Interfaces()) {
      interfaces.mode = Mode.STOP;
      interfaces.assertTerminalConversation(false, "CANCELLED", null);
      interfaces.assertTerminalConversation(true, "CANCELLED", null);
    }
  }

  @Test
  void successfulEmptyAndOrdinaryFailureOutcomesStayDifferentThroughInterfaces() throws Exception {
    start(null);
    try (var interfaces = new Interfaces()) {
      interfaces.mode = Mode.HEALTHY;
      var expected = retrieve();
      assertPassages(expected);
      assertOutward(expected);
      var rest = JSON.readTree(interfaces.request("rest", false, QUESTION).body());
      assertTrue(rest.path("ok").asBoolean(), rest.toString());
      assertTrue(rest.path("chunks").size() > 0, rest.toString());
      assertFalse(interfaces.mcp(QUESTION).path("isError").asBoolean());
      interfaces.assertSuccessfulConversation(false, true);

      String missing = "zzzznomatchingpassagezzzz";
      var empty = JSON.readTree(interfaces.request("rest", false, missing).body());
      assertTrue(empty.path("ok").asBoolean(), empty.toString());
      assertEquals(0, empty.path("chunks").size());
      assertTrue(interfaces.mcp(missing).path("content").toString().contains("No relevant passages found"));
      interfaces.assertTerminalConversation(false, "NO_CONTENT", null, missing);
      interfaces.assertSuccessfulConversation(true, false, missing);

      interfaces.mode = Mode.ORDINARY;
      var fallback = JSON.readTree(interfaces.request("rest", false, QUESTION).body());
      assertTrue(fallback.path("ok").asBoolean(), fallback.toString());
      assertEquals("FULLTEXT_FALLBACK", fallback.path("quality").path("retrieval_mode").asText());
      assertFalse(interfaces.mcp(QUESTION).path("isError").asBoolean());
      interfaces.assertTerminalConversation(false, "NO_CONTENT", null);
      interfaces.assertSuccessfulConversation(true, false);
    }
  }

  private static void assertError(JsonNode body, String code) {
    assertEquals(code, body.path("errorCode").asText(), body.toString());
    if (body.has("ok")) assertFalse(body.path("ok").asBoolean());
    assertFalse(body.toString().contains("No relevant passages found"));
  }

  private enum Mode { HEALTHY, DEADLINE, LOCAL_WAIT, CANCEL, STOP, ORDINARY }

  private final class Interfaces implements AutoCloseable {
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final EngineAdmissionService admission = (EngineAdmissionService) field(client, "admission");
    private final AtomicReference<Gate> active = new AtomicReference<>();
    private final AtomicReference<Throwable> realDeadline = new AtomicReference<>();
    private final KnowledgeClient adapter = mock(KnowledgeClient.class, delegatesTo(client));
    private final RemoteDocumentService remote = new RemoteDocumentService(tasks, tasks, () -> adapter);
    private final OnlineAiService ai = mock(OnlineAiService.class);
    private final Javalin http;
    private ConversationEngine engine;
    private int sequence;
    private Mode mode = Mode.HEALTHY;

    Interfaces() throws Exception {
      worker.setEmbeddingCompatController(null);
      worker.setEmbeddingProvider(new MmrFailureInterfacesIntegrationTest.ScriptedProvider(
          MmrFailureInterfacesIntegrationTest.Failure.NONE) {
        @Override public float[] embedQuery(String text, InferenceRequest request) {
          Gate gate = active.get();
          if (gate != null && gate.owner.get() != null
              && gate.mode != Mode.HEALTHY && gate.mode != Mode.ORDINARY) {
            gate.entered.countDown();
            try {
              if (!gate.release.await(30, TimeUnit.SECONDS)) throw new AssertionError("unreleased worker");
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError(e);
            } finally {
              gate.exited.countDown();
            }
          }
          return super.embedQuery(text, request);
        }
      });
      doAnswer(call -> {
        var params = call.getArgument(0, RetrieveContextParams.class);
        var context = call.getArgument(1, EngineContext.class);
        Gate gate = active.get();
        if (gate.mode == Mode.ORDINARY) throw new IllegalStateException("ordinary retrieval failure");
        try (var owner = admission.attach(context)) {
          gate.owner.set(owner);
          owner.onCompletion(gate.completed::countDown);
          try {
            if (gate.mode == Mode.DEADLINE) {
              // This narrows the real EngineKnowledgeClient.Budget; no synthetic timeout is thrown.
              return HeadSearchDeadline.within(System.nanoTime() + TimeUnit.SECONDS.toNanos(2),
                  () -> client.retrieveContext(params, owner.context()));
            }
            return client.retrieveContext(params, owner.context());
          } catch (KnowledgeClientException e) {
            realDeadline.set(e);
            throw e;
          }
        }
      }).when(adapter).retrieveContext(any(RetrieveContextParams.class), any());
      when(ai.isAvailable()).thenReturn(true);
      when(ai.llmContextTokens()).thenReturn(8192);
      doAnswer(call -> {
        var sink = call.getArgument(1, OnlineAiService.StreamSink.class);
        sink.onContent().accept("test answer");
        sink.onComplete().accept("stop");
        return null;
      }).when(ai).stream(any(), any());
      var controller = new RetrieveContextController(null, remote, OnlineAiService.unavailable(), () -> "", store);
      HeadAssembly facade = mock(HeadAssembly.class);
      McpAnswerCaptureFixture.bind(facade, remote);
      var surface = new McpToolSurface(List.of(OperationCatalog.of("core", List.of())),
          mock(OperationDispatcher.class), () -> null, () -> facade, Clock.systemUTC());
      var protocol = new McpProtocolHandler(surface, List.of(), Clock.systemUTC());
      http = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      http.post("/rest", controller::handleRetrieveContext);
      http.post("/mcp", protocol::handlePost);
      http.start("127.0.0.1", 0);
    }

    HttpResponse<String> request(String path, boolean chosen, String question) throws Exception {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("query", question);
      body.put("top_k", K);
      body.put("max_tokens", 6000);
      body.put("auto_entity_extract", false);
      body.put("filters", Map.of("path_prefix", article.getParent().toString()));
      if (chosen) body.put("doc_ids", List.of(article.toString()));
      if (path.equals("mcp")) body = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
          "params", Map.of("name", "justsearch_answer", "arguments", body));
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + http.port() + "/" + path))
          .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
      try (var connection = HttpClient.newHttpClient()) {
        return run(() -> connection.send(request, HttpResponse.BodyHandlers.ofString()), null);
      }
    }

    JsonNode mcp(String question) throws Exception {
      var response = request("mcp", false, question);
      assertEquals(200, response.statusCode(), response.body());
      var envelope = JSON.readTree(response.body());
      assertFalse(envelope.has("error"), envelope.toString());
      return envelope.path("result");
    }

    private ConversationEngine conversation(FileConversationStore history) {
      var shape = new ConversationShape(SHAPE, new Presentation(new I18nKey("test.label"),
          new I18nKey("test.desc"), Optional.empty(), Optional.empty()), Audience.USER,
          Provenance.core("v1"), ExecutionMode.SUBSTRATE_DRIVEN, IterationMode.ONE_SHOT,
          PersistenceMode.EPHEMERAL, List.of(), List.of(RAGContext.ID), List.of(),
          SingleHopController.ID, List.of(), true);
      Duration wait = mode == Mode.LOCAL_WAIT ? Duration.ofMillis(200) : Duration.ofSeconds(10);
      return new ConversationEngine(ConversationShapeCatalog.of("core", List.of(shape)), List.of(),
          PromptContributorRegistry.of(List.of()), ContextInjectorRegistry.of(List.of(new RAGContext(remote, wait))),
          StreamConsumerRegistry.of(List.of()), IterationControllerRegistry.of(List.of(SingleHopController.INSTANCE)),
          () -> ai, history, admission);
    }

    private List<SseEvent> turn(boolean chosen, String question, String thread, String turn) throws Exception {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("question", question);
      body.put("conversationId", thread);
      body.put("turnId", turn);
      if (chosen) body.put("docIds", List.of(article.toString()));
      var events = new ArrayList<SseEvent>();
      run(() -> {
        engine.run(SHAPE, body, Audience.USER, events::add, TestRequestContexts.browser());
        return null;
      }, () -> assertEquals(ConversationEngine.TurnStop.STOPPED, engine.stopTurn(thread, turn)));
      assertEquals(1, events.stream().filter(e -> e.name().equals("error") || e.name().equals("done")).count(),
          events.toString());
      return events;
    }

    void assertTerminalConversation(boolean chosen, String code, String reason) throws Exception {
      assertTerminalConversation(chosen, code, reason, QUESTION);
    }

    void assertTerminalConversation(boolean chosen, String code, String reason, String question) throws Exception {
      var folder = temp.resolve("history-" + ++sequence);
      engine = conversation(new FileConversationStore(folder));
      clearInvocations(ai, adapter);
      var events = turn(chosen, question, "thread", "first");
      verify(ai, never()).stream(any(), any());
      if (mode != Mode.STOP) {
        verify(adapter, times(mode == Mode.ORDINARY ? 1 : 0)).fetchDocuments(any(), any());
      }
      var terminal = events.stream().filter(e -> e.name().equals("error")).findFirst().orElseThrow();
      assertEquals(code, terminal.payload().get("errorCode"), events.toString());
      if (reason != null) assertEquals(reason, terminal.payload().get("reasonCode"));
      assertFalse(events.stream().anyMatch(e -> e.name().equals("rag.citations")));
      var saved = new FileConversationStore(folder).loadHistory("thread");
      assertEquals(2, saved.size(), saved.toString());
      var assistant = saved.get(1);
      if (code.equals("CANCELLED")) {
        assertEquals("Stopped by the user", terminal.payload().get("error"));
        assertEquals("CANCELLED", assistant.get("disposition"));
        assertNotEquals(true, assistant.get("terminalError"));
      } else {
        assertEquals(true, assistant.get("terminalError"));
        assertEquals(code, assistant.get("errorCode"));
        assertEquals(terminal.payload().get("error"), assistant.get("content"));
        assertFalse(assistant.containsKey("disposition"));
      }
      // Recompose with the reloaded store, run the next turn, and check the first outcome survives.
      Mode previousMode = mode;
      mode = Mode.HEALTHY;
      try {
        engine = conversation(new FileConversationStore(folder));
        assertTrue(turn(true, QUESTION, "thread", "next").stream().anyMatch(e -> e.name().equals("done")));
        var after = new FileConversationStore(folder).loadHistory("thread");
        assertEquals(4, after.size(), after.toString());
        assertEquals(saved, after.subList(0, 2));
      } finally {
        mode = previousMode;
      }
    }

    void assertSuccessfulConversation(boolean chosen, boolean cited) throws Exception {
      assertSuccessfulConversation(chosen, cited, QUESTION);
    }

    void assertSuccessfulConversation(boolean chosen, boolean cited, String question) throws Exception {
      var folder = temp.resolve("history-" + ++sequence);
      engine = conversation(new FileConversationStore(folder));
      clearInvocations(ai);
      var events = turn(chosen, question, "thread", "first");
      assertTrue(events.stream().anyMatch(e -> e.name().equals("done")), events.toString());
      assertEquals(cited, events.stream().anyMatch(e -> e.name().equals("rag.citations")), events.toString());
      verify(ai).stream(any(), any());
      if (chosen && (mode == Mode.DEADLINE || mode == Mode.ORDINARY)) {
        var meta = events.stream().filter(e -> e.name().equals("rag.meta")).findFirst();
        assertTrue(meta.isPresent(), events.toString());
        assertEquals("FULLTEXT_FALLBACK", meta.orElseThrow().payload().get("retrieval_mode"));
        assertEquals("GRPC_FAILED", meta.orElseThrow().payload().get("retrieval_mode_reason"));
      }
      var saved = new FileConversationStore(folder).loadHistory("thread");
      assertEquals(2, saved.size());
      assertNotEquals(true, saved.get(1).get("terminalError"));
    }

    private <T> T run(Callable<T> operation, Runnable stop) throws Exception {
      var gate = new Gate(mode);
      active.set(gate);
      Future<T> result = tasks.submit(operation);
      try {
        if (mode == Mode.CANCEL || mode == Mode.STOP) {
          assertTrue(gate.entered.await(10, TimeUnit.SECONDS), "real worker did not reach embedding");
          if (mode == Mode.STOP) {
            assertNotNull(stop);
            stop.run();
          } else gate.owner.get().cancel("CONTEXT_CLOSED");
        }
        return result.get(20, TimeUnit.SECONDS);
      } finally {
        gate.release.countDown();
        if (gate.entered.getCount() == 0) assertTrue(gate.exited.await(10, TimeUnit.SECONDS));
        if (gate.owner.get() != null) assertTrue(gate.completed.await(10, TimeUnit.SECONDS),
            "the real Engine must release this request before the next one starts");
        active.set(null);
      }
    }

    @Override public void close() {
      http.stop();
      tasks.close();
    }
  }

  private static final class Gate {
    final Mode mode;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch exited = new CountDownLatch(1);
    final CountDownLatch completed = new CountDownLatch(1);
    final AtomicReference<EngineWorkHandle> owner = new AtomicReference<>();

    Gate(Mode mode) { this.mode = mode; }
  }
}
