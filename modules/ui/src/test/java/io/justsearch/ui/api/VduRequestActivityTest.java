/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.javalin.Javalin;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.engine.EngineRoot;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.phases.OfflineCoordinatorBuilder;
import io.justsearch.app.services.bootstrap.phases.ServicePhase;
import io.justsearch.app.services.conversation.ConversationEngine;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.vdu.*;
import io.justsearch.app.services.worker.*;
import io.justsearch.core.context.EngineContext;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.ui.api.mcp.McpProtocolHandler;
import io.justsearch.ui.api.mcp.McpToolSurface;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real HTTP admission and controllers share the activity source consumed by production VDU. */
final class VduRequestActivityTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"ui-chat", "api-chat", "mcp-chat", "openai-chat", "search",
      "typing-suggest", "native-mcp-answer", "native-mcp-search", "native-mcp-resource",
      "native-mcp-prompt", "preview", "folders", "folder-files", "future-action"})
  void requestDuringAcceptedPassInterruptsAtNextUnitAndResumesPending(String kind) throws Exception {
    try (var fixture = new Fixture(directory)) {
      Path document = Files.writeString(directory.resolve("pending.png"), "fixture");
      var client = mock(KnowledgeClient.class);
      var processor = mock(VduProcessor.class);
      var gpu = mock(GpuCapabilitiesService.class, RETURNS_DEEP_STUBS);
      when(gpu.snapshot().effective().totalVramBytes()).thenReturn(24_000_000_000L);
      when(processor.hasVisionCapability()).thenReturn(true);
      when(client.queryPendingVduDocIds(any())).thenReturn(List.of(document.toString()));
      when(client.markVduProcessing(anyString(), anyInt(), anyString(), any())).thenReturn(0);
      when(processor.process(eq(document), any(), any()))
          .thenReturn(new VduProcessor.VduResult("page text", "{}", 1,
            GateVerdict.passed(), true, "a".repeat(64)));
      when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean(), any())).thenReturn(true);
      // Mode entry is after the pass captures its activity boundary and before the first unit.
      doAnswer(call -> { fixture.request(kind); return null; }).when(processor).enterVduMode();
      var batch = new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(),
          new VduCapabilityState(), fixture.interruptFactory);
      var context = io.justsearch.app.services.intent.EngineProvenance.internal("activity-proof",
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);

      var interrupted = batch.processPendingFiles(context, ignored -> {});

      assertEquals(BlockReason.ACTIVITY_OR_ENERGY, interrupted.blockedReason(), kind);
      assertEquals(1, interrupted.remaining(), "interrupted document remains pending: " + kind);
      verify(client, never()).markVduProcessing(anyString(), anyInt(), anyString(), any());
      verify(client, never()).updateVduResult(anyString(), any(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean(), any());
      assertTrue(fixture.bootstrap.msSinceLastUserActivity(System.currentTimeMillis()) < 10_000,
          "accepted " + kind + " must register activity in the shared source");

      doNothing().when(processor).enterVduMode();
      var later = batch.processPendingFiles(context, ignored -> {});
      assertEquals(1, later.processed(), "a later pass picks up the pending document: " + kind);
      assertEquals(0, later.remaining());
    }
  }

  @Test
  void earlierRequestDoesNotInterruptAndObserversDoNotResetIdle() throws Exception {
    try (var fixture = new Fixture(directory)) {
      fixture.request("ui-chat");
      var pass = fixture.interruptFactory.get();
      assertFalse(pass.getAsBoolean(), "prior activity must not interrupt the accepted pass");
      for (String path : List.of("/api/status", "/api/inference/status", "/api/knowledge/status")) {
        assertEquals(200, fixture.send(path, null, null).statusCode());
        assertFalse(pass.getAsBoolean(), "status observer must not interrupt: " + path);
      }
      fixture.send("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null);
      assertFalse(pass.getAsBoolean(), "MCP keepalive must not interrupt");
      // An unattended MCP client polling status-like resources (counts, coverage, health,
      // readiness, the indexed roots) and the protocol's discovery calls is observing.
      Thread.sleep(200);
      long idleBefore = fixture.bootstrap.msSinceLastUserActivity(System.currentTimeMillis());
      for (String message : List.of(
          "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/read\",\"params\":{\"uri\":\"justsearch://index/summary\"}}",
          "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/read\",\"params\":{\"uri\":\"justsearch://index/roots\"}}",
          "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\"justsearch://resource/core.index-status\"}}",
          "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}",
          "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/list\"}",
          "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"prompts/list\"}",
          "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"justsearch://index/summary\"}}")) {
        fixture.send("/mcp", message, null);
        assertFalse(pass.getAsBoolean(), "MCP observation must not interrupt: " + message);
      }
      assertTrue(fixture.bootstrap.msSinceLastUserActivity(System.currentTimeMillis()) >= idleBefore,
          "MCP observation must not reset the idle clock");
      fixture.request("typing-suggest");
      assertTrue(pass.getAsBoolean(), "new typing activity interrupts even after prior activity");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"during", "prior", "quiet"})
  void activityBetweenDocumentsLeavesNextUnitPendingWhileQuietPassesFinish(String activity) throws Exception {
    try (var fixture = new Fixture(directory)) {
      Path first = Files.writeString(directory.resolve("first.png"), "first");
      Path second = Files.writeString(directory.resolve("second.png"), "second");
      var client = mock(KnowledgeClient.class);
      var processor = mock(VduProcessor.class);
      var gpu = mock(GpuCapabilitiesService.class, RETURNS_DEEP_STUBS);
      when(gpu.snapshot().effective().totalVramBytes()).thenReturn(24_000_000_000L);
      when(processor.hasVisionCapability()).thenReturn(true);
      when(client.queryPendingVduDocIds(any())).thenReturn(List.of(first.toString(), second.toString()));
      when(client.markVduProcessing(anyString(), anyInt(), anyString(), any())).thenReturn(0);
      when(client.updateVduResult(anyString(), any(), any(), any(), anyInt(), anyString(), anyString(), anyBoolean(), any())).thenReturn(true);
      when(processor.process(any(), any(), any())).thenAnswer(call -> {
        if (activity.equals("during") && call.getArgument(0).equals(first)) fixture.request("ui-chat");
        return new VduProcessor.VduResult("page text", "{}", 1,
            GateVerdict.passed(), true, "a".repeat(64));
      });
      if (activity.equals("prior")) fixture.request("ui-chat");
      var batch = new VduBatchProcessor(processor, gpu, () -> client, VduMetricCatalog.noop(),
          new VduCapabilityState(), fixture.interruptFactory);
      var context = io.justsearch.app.services.intent.EngineProvenance.internal("unit-boundary-proof",
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      var outcome = batch.processPendingFiles(context, ignored -> {});
      assertEquals(activity.equals("during") ? 1 : 2, outcome.processed(), activity);
      assertEquals(activity.equals("during") ? 1 : 0, outcome.remaining(), activity);
      if (activity.equals("during")) {
        verify(client, never()).markVduProcessing(eq(second.toString()), anyInt(), anyString(), any());
        verify(processor, never()).process(eq(second), any(), any());
      }
    }
  }

  /**
   * The web UI reports the last input of a burst when its three-second presence interval ends, so
   * that report can arrive just after a pass was accepted while describing input made before it
   * (the ask whose reply the pass waited for). It carries how long ago the input happened.
   */
  @Test
  void endOfIntervalReportOfInputBeforeAcceptanceDoesNotInterruptThePass() throws Exception {
    try (var fixture = new Fixture(directory)) {
      var pass = fixture.interruptFactory.get();
      // A key press 2.5 s before acceptance, reported 0.4 s after it.
      Thread.sleep(400);
      assertEquals(204, fixture.presence("{\"inputAgeMs\":2900}").statusCode());
      assertFalse(pass.getAsBoolean(), "input made before the pass was accepted must not interrupt it");
      long idle = fixture.bootstrap.msSinceLastUserActivity(System.currentTimeMillis());
      assertTrue(idle >= 2_900 && idle < 10_000, "the idle clock starts at the reported input: " + idle);
    }
  }

  @Test
  void reportOfInputAfterAcceptanceInterruptsThePass() throws Exception {
    try (var fixture = new Fixture(directory)) {
      // The end-of-interval report of a key press made after acceptance.
      var pass = fixture.interruptFactory.get();
      Thread.sleep(400);
      assertEquals(204, fixture.presence("{\"inputAgeMs\":100}").statusCode());
      assertTrue(pass.getAsBoolean(), "input made after acceptance interrupts at the next unit");
      // The report sent at once for the first input after a quiet interval.
      var next = fixture.interruptFactory.get();
      assertEquals(204, fixture.presence("{\"inputAgeMs\":0}").statusCode());
      assertTrue(next.getAsBoolean(), "the first input's report interrupts at once");
      // A report without an age, or with an unreadable one, is input happening now.
      for (String body : new String[] {null, "", "not json", "{\"inputAgeMs\":\"soon\"}"}) {
        var later = fixture.interruptFactory.get();
        Thread.sleep(5);
        assertEquals(204, fixture.presence(body).statusCode());
        assertTrue(later.getAsBoolean(), "a report without a usable age interrupts: " + body);
      }
    }
  }

  @Test
  void reportedInputAgeIsBoundedSoNoClientPlacesInputInTheFutureOrFarInThePast() throws Exception {
    try (var fixture = new Fixture(directory)) {
      // A claim of input ten minutes ago is placed at most five seconds back.
      assertEquals(204, fixture.presence("{\"inputAgeMs\":600000}").statusCode());
      long idle = fixture.bootstrap.msSinceLastUserActivity(System.currentTimeMillis());
      assertTrue(idle >= 5_000 && idle < 7_000, "a far-past claim is placed five seconds back: " + idle);
    }
    try (var fixture = new Fixture(directory)) {
      // A claim of input a minute in the future is placed at arrival, so a pass accepted after it
      // is not interrupted by a later report of input made before acceptance.
      assertEquals(204, fixture.presence("{\"inputAgeMs\":-60000}").statusCode());
      Thread.sleep(50);
      var pass = fixture.interruptFactory.get();
      Thread.sleep(50);
      assertEquals(204, fixture.presence("{\"inputAgeMs\":2000}").statusCode());
      assertFalse(pass.getAsBoolean(), "input claimed in the future is not placed after acceptance");
    }
  }

  private static final class Fixture implements AutoCloseable {
    final EngineRoot root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class), 30_000, 100);
    final KnowledgeServerBootstrap bootstrap;
    final Supplier<BooleanSupplier> interruptFactory;
    final ExecutorService events = Executors.newSingleThreadExecutor();
    final HttpClient http = HttpClient.newHttpClient();
    final Javalin app;
    final HttpServer upstream;
    final ChatController chat;
    final ConversationEngine conversation = mock(ConversationEngine.class);
    final McpToolSurface surface = mock(McpToolSurface.class);

    @SuppressWarnings("unchecked")
    Fixture(Path directory) throws Exception {
      bootstrap = new KnowledgeServerBootstrap(root.executors(),
          new KnowledgeServerConfig(false, directory, directory, directory, 1000, 1000, 1,
              2000, 1000, 300_000, 100, 2000, 0), null,
          root.components(), root.indexComponent(), root, false, root.publicationLock());
      var input = mock(ServicePhase.Input.class);
      when(input.inferenceManager()).thenReturn(mock(InferenceLifecycleManager.class));
      when(input.engineAdmission()).thenReturn(root.admission());
      when(input.knowledgeServerBootstrapSupplier()).thenReturn(() -> bootstrap);
      var captured = new AtomicReference<Supplier<BooleanSupplier>>();
      try (var reconciler = mockConstruction(RuntimeReconciler.class);
           var builder = mockStatic(OfflineCoordinatorBuilder.class, call -> {
             captured.set(call.getArgument(6));
             throw new IllegalStateException("captured production checkpoint factory");
           })) {
        assertFalse(reconciler.isClosed(), "the reconciler mock must remain active during bootstrap");
        assertFalse(builder.isClosed(), "the checkpoint factory capture must remain active during bootstrap");
        ServicePhase.runWithOutcome(input);
      }
      interruptFactory = Objects.requireNonNull(captured.get());
      upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      upstream.createContext("/v1/chat/completions", exchange -> {
        try (exchange) {
          exchange.getRequestBody().readAllBytes();
          byte[] body = "{\"choices\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
      });
      upstream.start();
      chat = new ChatController(root.executors(), conversation, new SseWriter(null), null);
      var proxy = new OpenAiCompatController(http, () -> new io.justsearch.app.inference.LlamaServerEndpoint(upstream.getAddress().getPort(), null), null);
      when(surface.callTool(anyString(), anyMap(), any(), any(), any()))
          .thenReturn(Map.of("content", List.of()));
      var protocol = new McpProtocolHandler(surface, List.of(), Clock.systemUTC(), root.admission());
      app = Javalin.create(config -> {
        config.showJavalinBanner = false;
        config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
      });
      new ApiSecurityFilters(false, null, new EventBuffer(), events, null, root.operationLeases(), root.admission())
          .install(app, protocol::clientIdentity);
      app.post("/api/chat/dispatch", chat.dynamicHandler("/api/chat/dispatch"), ActivityRole.USER_ACTION);
      app.post("/api/chat/free", chat.handler(new io.justsearch.agent.api.registry.ConversationShapeRef("core.free-chat"), "/api/chat/free"), ActivityRole.USER_ACTION);
      app.post("/v1/chat/completions", proxy::handleChatCompletions, ActivityRole.USER_ACTION);
      app.post("/mcp", protocol::handlePost, ActivityRole.PROTOCOL);
      app.post("/api/future-action", ctx -> ctx.json(Map.of("accepted", true)), ActivityRole.USER_ACTION);
      // As StatusRoutes declares the web UI's presence signal.
      app.post("/api/ui/activity", ctx -> ctx.status(204), ActivityRole.USER_ACTION);
      // The real search/suggest/preview controllers own their downstream ports, stubbed here.
      var readyBootstrap = spy(bootstrap);
      doReturn(true).when(readyBootstrap).isReady();
      try (var adapter = mockConstruction(KnowledgeHttpApiAdapter.class, (mock, context) -> {
        when(mock.suggest(anyString(), anyInt(), any())).thenReturn(List.of("suggestion"));
        when(mock.listFolders(any(), any())).thenReturn(
            new io.justsearch.app.api.knowledge.FolderBrowseResponse(List.of(), 0, false));
        when(mock.listFolderFiles(any(), any())).thenReturn(
            new io.justsearch.app.api.knowledge.FolderFilesResponse(List.of(), 0, 0));
        when(mock.search(any(), any())).thenReturn(new io.justsearch.app.api.knowledge.KnowledgeSearchResponse(
            0, 0, 0, List.of(), null, Map.of(), false, Map.of(), null, null, null, null, null));
      })) {
        var search = new KnowledgeSearchController(readyBootstrap, null);
        assertEquals(List.of(search.getAdapter()), adapter.constructed(),
            "the real controller must use the constructed adapter fixture");
        app.post("/api/knowledge/search", search::handleSearch, ActivityRole.USER_ACTION);
        app.get("/api/knowledge/suggest", search::handleSuggest, ActivityRole.USER_ACTION);
        app.post("/api/knowledge/folders", search::handleListFolders, ActivityRole.USER_ACTION);
        app.post("/api/knowledge/folder-files", search::handleListFolderFiles, ActivityRole.USER_ACTION);
      }
      var documents = mock(io.justsearch.app.api.DocumentService.class);
      when(documents.fetchSlice(anyString(), anyInt(), anyInt(), any()))
          .thenReturn(CompletableFuture.completedFuture(null));
      when(documents.checkPresence(anyList(), any())).thenReturn(CompletableFuture.completedFuture(
          Map.of("pending.png", io.justsearch.app.api.DocumentService.Presence.ABSENT)));
      var preview = new PreviewController(documents);
      app.get("/api/knowledge/preview", preview::handlePreview, ActivityRole.USER_ACTION);
      for (String path : List.of("/api/status", "/api/inference/status", "/api/knowledge/status")) {
        app.get(path, ctx -> ctx.json(Map.of()), ActivityRole.OBSERVATION);
      }
      app.start("127.0.0.1", 0);
    }

    void request(String kind) throws Exception {
      var response = switch (kind) {
        case "ui-chat" -> send("/api/chat/dispatch", "{\"shapeId\":\"core.free-chat\",\"prompt\":\"ready\"}", null);
        case "api-chat" -> send("/api/chat/free", "{\"prompt\":\"ready\"}", null);
        case "mcp-chat" -> send("/api/chat/free", "{\"prompt\":\"ready\"}", "MCP");
        case "openai-chat" -> send("/v1/chat/completions", "{\"messages\":[]}", null);
        case "search" -> send("/api/knowledge/search", "{\"query\":\"ready\",\"mode\":\"lexical\"}", null);
        case "typing-suggest" -> send("/api/knowledge/suggest?query=r", null, null);
        case "folders" -> send("/api/knowledge/folders", "{\"parentPath\":\"C:/documents\"}", null);
        case "folder-files" -> send("/api/knowledge/folder-files", "{\"folderPath\":\"C:/documents\"}", null);
        case "preview" -> send("/api/knowledge/preview?docId=pending.png", null, null);
        case "native-mcp-resource" -> send("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/read\",\"params\":{\"uri\":\"justsearch://document/pending\"}}", null);
        case "native-mcp-prompt" -> send("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/get\",\"params\":{\"name\":\"answer\"}}", null);
        case "future-action" -> send("/api/future-action", "{}", null);
        default -> send("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
            + (kind.equals("native-mcp-answer") ? "justsearch_answer" : "justsearch_search") + "\",\"arguments\":{\"query\":\"ready\"}}}", null);
      };
      assertTrue(response.statusCode() == 200 || kind.equals("preview") && response.statusCode() == 404,
          kind + ": " + response.statusCode() + " " + response.body());
    }

    HttpResponse<String> presence(String body) throws Exception {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + "/api/ui/activity"))
          .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
          .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> send(String path, String body, String transport) throws Exception {
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path))
          .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
      if (transport != null) request.header("X-JustSearch-Transport", transport);
      if (body == null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofString(body));
      return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override public void close() {
      app.stop(); upstream.stop(0); chat.shutdown(); http.close(); events.shutdownNow();
      bootstrap.close(); root.close(); root.processResources().close();
    }
  }
}
