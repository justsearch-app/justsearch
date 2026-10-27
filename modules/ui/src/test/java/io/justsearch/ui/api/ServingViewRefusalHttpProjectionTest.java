/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.RagMetricCatalog;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.app.services.worker.SearchPerSourceExecutor;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * A capture refused because the index has no serving view (a runtime reload has retired it or
 * not yet published its successor) answers 503 SERVICE_UNAVAILABLE with retrySafe and
 * Retry-After on every knowledge route that captures, never 500. The refusal comes from a real
 * Knowledge Server that has not published a serving view.
 */
final class ServingViewRefusalHttpProjectionTest {
  private static final String MESSAGE = "Index serving view is unavailable";

  @TempDir Path directory;
  private ConfigStore previousConfigStore;
  private ConfigStore store;
  private IllegalStateException refusal;
  private final KnowledgeServerBootstrap bootstrap = mock(KnowledgeServerBootstrap.class);

  @BeforeEach
  void refuseEveryCapture() throws Exception {
    previousConfigStore = ConfigStore.globalOrNull();
    store = TestResolvedConfigHelper.storeWithDefaults();
    refusal = servingViewRefusal(directory);
    when(bootstrap.publicationLock()).thenReturn(store.publicationLock());
    when(bootstrap.isReady()).thenReturn(true);
    when(bootstrap.acquireClientLease()).thenThrow(refusal);
    when(bootstrap.captureClient()).thenThrow(refusal);
  }

  @AfterEach
  void restore() {
    TestResolvedConfigHelper.restoreGlobal(previousConfigStore);
  }

  /** The exact refusal a Knowledge Server without a published serving view throws. */
  private static IllegalStateException servingViewRefusal(Path dataDir) throws Exception {
    try (var executors = new TestEngineExecutors()) {
      var server = new KnowledgeServer(executors,
          new WorkerConfig(dataDir, 60_000L, "0.0.0-test", Map.of(), "test", 500L));
      try {
        var refused = assertThrows(IllegalStateException.class, server::captureServingView);
        assertEquals(MESSAGE, refused.getMessage());
        return refused;
      } finally {
        server.close();
      }
    }
  }

  @Test
  void searchRefusedAtCaptureIsRetryableUnavailable() {
    var ctx = request("/api/knowledge/search");
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("query", "quarterly report"));
    controller().handleSearch(ctx);
    assertUnavailable(ctx);
  }

  @Test
  void collectionFacetRefusedAtCaptureIsRetryableUnavailable() {
    var ctx = request("/api/knowledge/collection-facet");
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("maxDocsScanned", 10));
    controller().handleCollectionFacet(ctx);
    assertUnavailable(ctx);
  }

  @Test
  void suggestRefusedAtCaptureIsRetryableUnavailable() {
    var ctx = request("/api/knowledge/suggest");
    when(ctx.queryParam("query")).thenReturn("quar");
    controller().handleSuggest(ctx);
    assertUnavailable(ctx);
  }

  @Test
  void folderBrowseRefusedAtCaptureIsRetryableUnavailable() {
    var ctx = request("/api/knowledge/folders");
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("parentPath", directory.toString()));
    controller().handleListFolders(ctx);
    assertUnavailable(ctx);
  }

  @Test
  void folderFilesRefusedAtCaptureIsRetryableUnavailable() {
    var ctx = request("/api/knowledge/folder-files");
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("folderPath", directory.toString()));
    controller().handleListFolderFiles(ctx);
    assertUnavailable(ctx);
  }

  @Test
  void retrieveContextWithDocIdsRefusedAtCaptureIsRetryableUnavailable() throws Exception {
    retrieveContext(Map.of("query", "quarterly report", "doc_ids", List.of("doc-1")));
  }

  @Test
  void openRetrieveContextRefusedAtCaptureIsRetryableUnavailable() throws Exception {
    retrieveContext(Map.of("query", "quarterly report"));
  }

  @Test
  void refusalIsClassifiedUnavailableAlsoWhenAsynchronouslyWrapped() {
    assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE, ApiErrorHandler.resolve(refusal));
    assertEquals(ApiErrorCode.SERVICE_UNAVAILABLE,
        ApiErrorHandler.resolve(new CompletionException(refusal)));
  }

  @Test
  void otherIllegalStateStaysInvalidState() {
    var unrelated = new IllegalStateException("Head serving references are not coherently published");
    assertEquals(ApiErrorCode.INVALID_STATE, ApiErrorHandler.resolve(unrelated));
    var other = mock(KnowledgeServerBootstrap.class);
    when(other.publicationLock()).thenReturn(store.publicationLock());
    when(other.acquireClientLease()).thenThrow(unrelated);
    var ctx = request("/api/knowledge/search");
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of("query", "quarterly report"));
    new KnowledgeSearchController(other, mock(SearchPerSourceExecutor.class), null,
        OnlineAiService.unavailable(), null, null, store).handleSearch(ctx);
    verify(ctx).status(500);
    verify(ctx, never()).header(any(), any());
    assertEquals("INVALID_STATE", body(ctx).get("errorCode"));
  }

  private void retrieveContext(Map<String, Object> requestBody) throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      var client = mock(KnowledgeClient.class);
      var documents = new RemoteDocumentService(executor, executor, () -> client,
          RagMetricCatalog.noop(), () -> { throw refusal; });
      var controller = new RetrieveContextController(bootstrap, () -> documents,
          OnlineAiService.unavailable(), () -> "", store);
      var ctx = request("/api/knowledge/retrieve-context");
      when(ctx.bodyAsClass(Map.class)).thenReturn(requestBody);
      controller.handleRetrieveContext(ctx);
      assertUnavailable(ctx);
      verify(client, never()).retrieveContext(any(), any());
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private KnowledgeSearchController controller() {
    return new KnowledgeSearchController(bootstrap, mock(SearchPerSourceExecutor.class), null,
        OnlineAiService.unavailable(), null, null, store);
  }

  private static Context request(String path) {
    var ctx = mock(Context.class);
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(ctx.path()).thenReturn(path);
    when(ctx.status(anyInt())).thenReturn(ctx);
    return ctx;
  }

  private static void assertUnavailable(Context ctx) {
    verify(ctx).status(503);
    verify(ctx).header("Retry-After", "1");
    var body = body(ctx);
    assertEquals("SERVICE_UNAVAILABLE", body.get("errorCode"), body.toString());
    assertEquals("TRANSIENT", body.get("errorClass"));
    assertEquals(true, body.get("retryable"));
    assertEquals(true, body.get("retrySafe"));
    assertEquals(MESSAGE, body.get("error"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> body(Context ctx) {
    var response = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(response.capture());
    return (Map<String, Object>) response.getValue();
  }
}
