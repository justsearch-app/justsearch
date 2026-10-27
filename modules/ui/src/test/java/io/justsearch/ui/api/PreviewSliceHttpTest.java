/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.engine.EngineKnowledgeClient;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.engine.ForegroundLoadGate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.javalin.Javalin;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.agent.tools.ReadDocumentTool;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.RagMetricCatalog;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ui.api.ApiSecurityFilters;
import io.justsearch.ui.api.PreviewController;
import io.justsearch.ui.api.TestRequestContexts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP, Engine client/status translation and Worker slicing over this test's Lucene index. */
@Timeout(30)
final class PreviewSliceHttpTest {
  private static final String CONTENT = "\uD83D\uDE00A\uD834\uDD1EB";
  @TempDir Path temp;
  private ConfigStore previous;
  private String previousDataDir;
  private final TestEngineExecutors executors = new TestEngineExecutors();
  private final LuceneExecutorRegistrations luceneExecutors = new LuceneExecutorRegistrations(executors);
  private RunningRuntime runtime;
  private WorkerSearchService search;
  private final java.util.concurrent.atomic.AtomicReference<WorkerAppServices> activeServices =
      new java.util.concurrent.atomic.AtomicReference<>();
  private EngineKnowledgeClient knowledge;
  private RemoteDocumentService documents;
  private Javalin app;
  private HttpClient http;
  private final AtomicInteger captured = new AtomicInteger();
  private final AtomicInteger released = new AtomicInteger();
  private final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void start() throws Exception {
    previous = ConfigStore.globalOrNull();
    TestResolvedConfigHelper.storeWithDefaults();
    previousDataDir = System.getProperty("justsearch.data.dir");
    System.setProperty("justsearch.data.dir", temp.resolve("data").toString());
    runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(temp)
        .withExecutorRegistrations(luceneExecutors).open();
    seed("/docs/unicode.txt", CONTENT);
    seed("/docs/long.txt", "a".repeat(199_999) + "\uD83D\uDE00z");
    seed("/docs/empty.txt", "");
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    search = spy(new WorkerSearchService(runtime));
    // Only the composition facade is mocked; slicing and index reads are production code.
    var services = mock(WorkerAppServices.class);
    when(services.searchService()).thenReturn(search);
    activeServices.set(services);
    knowledge = new EngineKnowledgeClient(executors, activeServices::get,
        new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop());
    var admissionField = EngineKnowledgeClient.class.getDeclaredField("admission");
    admissionField.setAccessible(true);
    var admission = (EngineAdmissionController) admissionField.get(knowledge);
    documents = new RemoteDocumentService(Runnable::run, Runnable::run, () -> knowledge,
        RagMetricCatalog.noop(), () -> {
          captured.incrementAndGet();
          return new RemoteDocumentService.ClientCapture() {
            @Override public KnowledgeClient client() { return knowledge; }
            @Override public void close() { released.incrementAndGet(); }
          };
        });
    var controller = new PreviewController(documents, Duration.ofSeconds(2));
    app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });
    new ApiSecurityFilters(false, null, null, null, null, admission, admission).install(app);
    app.get("/api/preview", controller::handlePreview);
    app.start("127.0.0.1", 0);
    http = HttpClient.newHttpClient();
  }

  @AfterEach
  void stop() throws Exception {
    try {
      if (app != null) app.stop();
      if (http != null) http.close();
      if (knowledge != null) knowledge.close();
      if (runtime != null) runtime.close();
    } finally {
      luceneExecutors.close();
      executors.close();
      TestResolvedConfigHelper.restoreGlobal(previous);
      if (previousDataDir == null) System.clearProperty("justsearch.data.dir");
      else System.setProperty("justsearch.data.dir", previousDataDir);
    }
  }

  private void seed(String id, String content) throws Exception {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, id, SchemaFields.DOC_UID, id + "#0", SchemaFields.PATH, id,
        SchemaFields.CONTENT, content, SchemaFields.TITLE, "Stored title", SchemaFields.MIME, "text/plain")));
  }

  @Test
  void invalidOffsetsAre400AndReleaseCapturesBeforeValidPaging() throws Exception {
    for (int i = 0; i < 12; i++) {
      JsonNode error = get("?docId=/docs/unicode.txt&offsetChars=1&maxChars=1", 400);
      assertEquals("INVALID_REQUEST", error.path("errorCode").asText());
      assertFalse(error.path("retryable").asBoolean(true));
      assertFalse(error.has("content"));
      assertEquals(i + 1, released.get());
      assertEquals(captured.get(), released.get());
    }
    assertPagedText();
  }

  @Test
  void readToolUsesRealEngineInvalidOffsetAndValidLeadingPair() {
    var tool = new ReadDocumentTool(documents::fetchSlice);
    var invalid = tool.execute("{\"path\":\"/docs/unicode.txt\",\"offset_chars\":1}",
        TestRequestContexts.internal());
    assertFalse(invalid.success());
    assertEquals("INVALID_REQUEST", invalid.errorCode().orElseThrow());
    assertFalse(invalid.retryable().orElseThrow());
    var valid = tool.execute("{\"path\":\"/docs/unicode.txt\",\"offset_chars\":0}",
        TestRequestContexts.internal());
    assertTrue(valid.success(), valid.message());
    assertTrue(valid.message().contains(CONTENT));
    assertEquals(captured.get(), released.get());
  }

  private void assertPagedText() throws Exception {
    StringBuilder rebuilt = new StringBuilder();
    int offset = 0;
    while (offset < CONTENT.length()) {
      var page = get("?docId=/docs/unicode.txt&offsetChars=" + offset + "&maxChars=1", 200);
      String text = page.path("content").asText();
      assertEquals(offset == 0 || offset == 3 ? 2 : 1, text.length());
      assertEquals(offset + text.length(), page.path("nextOffsetChars").asInt());
      assertEquals(CONTENT.length(), page.path("totalChars").asInt());
      rebuilt.append(text);
      offset = page.path("nextOffsetChars").asInt();
      assertEquals(offset < CONTENT.length(), page.path("truncated").asBoolean());
    }
    assertEquals(CONTENT, rebuilt.toString());
    assertEquals(captured.get(), released.get());
  }

  @Test
  void pagingDefaultsCapsAndEndOffsetsKeepUtf16Semantics() throws Exception {
    assertPagedText();
    for (String offset : new String[] {"", "&offsetChars=broken", "&offsetChars=-8"}) {
      var page = get("?docId=/docs/unicode.txt&maxChars=1" + offset, 200);
      assertEquals(0, page.path("offsetChars").asInt());
      assertEquals("\uD83D\uDE00", page.path("content").asText());
      assertEquals(2, page.path("nextOffsetChars").asInt());
    }
    for (int offset : new int[] {CONTENT.length(), CONTENT.length() + 20}) {
      var page = get("?docId=/docs/unicode.txt&offsetChars=" + offset + "&maxChars=1", 200);
      assertEquals("", page.path("content").asText());
      assertEquals(CONTENT.length(), page.path("nextOffsetChars").asInt());
      assertEquals(CONTENT.length(), page.path("totalChars").asInt());
      assertFalse(page.path("truncated").asBoolean());
    }
    for (String max : new String[] {"", "&maxChars=0", "&maxChars=-8", "&maxChars=broken"}) {
      var page = get("?docId=/docs/long.txt" + max, 200);
      assertEquals(20_000, page.path("maxChars").asInt());
      assertEquals(20_000, page.path("content").asText().length());
      assertEquals(20_000, page.path("nextOffsetChars").asInt());
    }
    var capped = get("?docId=/docs/long.txt&maxChars=300000", 200);
    assertEquals(200_000, capped.path("maxChars").asInt());
    assertEquals(200_001, capped.path("content").asText().length());
    assertTrue(capped.path("content").asText().endsWith("\uD83D\uDE00"));
    assertEquals(200_001, capped.path("nextOffsetChars").asInt());
    assertTrue(capped.path("truncated").asBoolean());
  }

  @Test
  void missingUnknownAndEmptyDocumentsRetainTheirResponses() throws Exception {
    assertEquals("NO_DOC_ID", get("", 400).path("errorCode").asText());
    assertEquals("NOT_FOUND", get("?docId=/docs/missing.txt", 404).path("errorCode").asText());
    var empty = get("?docId=/docs/empty.txt", 200);
    assertEquals("", empty.path("content").asText());
    assertEquals("Stored title", empty.path("title").asText());
    assertEquals("text/plain", empty.path("mime").asText());
    assertEquals(0, empty.path("totalChars").asInt());
    assertEquals(0, empty.path("nextOffsetChars").asInt());
    assertFalse(empty.path("truncated").asBoolean());
  }

  @Test
  void otherWorkerFailuresStay503RegardlessOfMessage() throws Exception {
    for (RuntimeException failure : new RuntimeException[] {
        WorkerServiceException.unavailable("index unavailable"),
        new WorkerServiceException(WorkerServiceException.Status.INTERNAL, "surrogate pair failed"),
        new IllegalArgumentException("offset splits a surrogate pair")}) {
      doThrow(failure).when(search).fetchDocumentSlice(any(), any());
      var error = get("?docId=/docs/unicode.txt", 503);
      assertEquals("INDEX_UNAVAILABLE", error.path("errorCode").asText());
      assertTrue(error.path("retryable").asBoolean());
      assertEquals(captured.get(), released.get());
    }
    doCallRealMethod().when(search).fetchDocumentSlice(any(), any());
    assertPagedText();
  }

  @Test
  void genuinelyUnavailableWorkerRemainsRetryable503() throws Exception {
    var services = activeServices.getAndSet(null);
    try {
      var error = get("?docId=/docs/unicode.txt", 503);
      assertEquals("INDEX_UNAVAILABLE", error.path("errorCode").asText());
      assertTrue(error.path("retryable").asBoolean());
      assertEquals(captured.get(), released.get());
    } finally {
      activeServices.set(services);
    }
    assertPagedText();
  }

  @Test
  void admissionAndExecutorRefusalsKeepExistingControllerStatuses() throws Exception {
    for (var reason : io.justsearch.app.api.EngineAdmissionException.Reason.values()) {
      doThrow(new io.justsearch.app.api.EngineAdmissionException(reason, 1))
          .when(search).fetchDocumentSlice(any(), any());
      boolean unavailable = reason == io.justsearch.app.api.EngineAdmissionException.Reason.FROZEN
          || reason == io.justsearch.app.api.EngineAdmissionException.Reason.WORK_FINISHED;
      var error = get("?docId=/docs/unicode.txt", unavailable ? 503 : 500);
      assertEquals(unavailable ? "SERVICE_UNAVAILABLE" : "ADMISSION_" + reason.name(),
          error.path("errorCode").asText());
    }
    for (var reason : io.justsearch.core.execution.EngineExecutorRejectedException.Reason.values()) {
      doThrow(new io.justsearch.core.execution.EngineExecutorRejectedException(reason, "test", 1))
          .when(search).fetchDocumentSlice(any(), any());
      boolean unavailable = reason == io.justsearch.core.execution.EngineExecutorRejectedException.Reason.CLOSED;
      var error = get("?docId=/docs/unicode.txt", unavailable ? 503 : 500);
      assertEquals(unavailable ? "SERVICE_UNAVAILABLE" : "ADMISSION_ENGINE_LIMIT",
          error.path("errorCode").asText());
    }
    assertEquals(captured.get(), released.get());
  }

  @Test
  void controllersOwnWaitTimeoutRemains504() throws Exception {
    DocumentService waiting = new DocumentService() {
      @Override public CompletableFuture<DocumentRecord> fetch(
          String docId, io.justsearch.core.context.EngineContext context) {
        return new CompletableFuture<>();
      }
    };
    var controller = new PreviewController(waiting, Duration.ofMillis(25));
    app.get("/api/preview-timeout", controller::handlePreview);
    var response = http.send(HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + app.port() + "/api/preview-timeout?docId=doc"))
        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(504, response.statusCode(), response.body());
    assertEquals("TIMEOUT", json.readTree(response.body()).path("errorCode").asText());
  }

  private JsonNode get(String query, int expectedStatus) throws Exception {
    var response = http.send(HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + app.port() + "/api/preview" + query))
        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(expectedStatus, response.statusCode(), response.body());
    return json.readTree(response.body());
  }
}
