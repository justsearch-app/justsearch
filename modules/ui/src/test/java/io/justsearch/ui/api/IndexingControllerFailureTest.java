/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.lifecycle.Capability;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

final class IndexingControllerFailureTest {
  @TempDir Path root;

  @Test
  void negativeRemovalCountReportsIncompleteCleanup() {
    var indexing = mock(IndexingService.class);
    when(indexing.removeWatchedRoot(eq("notes"), eq(root), any(EngineContext.class)))
        .thenReturn(-1);
    var ctx = context();

    controller(indexing, mock(ExcludesService.class)).handleRemoveRoot(ctx);

    verify(ctx).status(500);
    var body = response(ctx);
    assertEquals("INDEX_ERROR", body.get("errorCode"));
    assertTrue(body.get("error").toString().contains("incomplete"));
    assertFalse(body.containsKey("status"), "incomplete removal must not claim status ok");
    assertEquals(false, body.get("retrySafe"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 7})
  void successfulRemovalPreservesDeletedCount(int deletedJobs) {
    var indexing = mock(IndexingService.class);
    when(indexing.removeWatchedRoot(eq("notes"), eq(root), any(EngineContext.class)))
        .thenReturn(deletedJobs);
    var ctx = context();

    controller(indexing, mock(ExcludesService.class)).handleRemoveRoot(ctx);

    verify(ctx).status(200);
    var body = response(ctx);
    assertEquals("ok", body.get("status"));
    assertEquals(deletedJobs, body.get("deletedJobs"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("indexingHandlers")
  void indexingHandlersPreserveCapacityRefusals(
      String name, BiConsumer<IndexingController, Context> handler) {
    for (RuntimeException refusal : capacityRefusals()) {
      var indexing = mock(IndexingService.class, invocation -> { throw refusal; });
      var excludes = mock(ExcludesService.class, invocation -> { throw refusal; });
      var ctx = context();

      handler.accept(controller(indexing, excludes), ctx);

      assertCapacityRefusal(ctx);
    }
  }

  @Test
  void nestedJobCountRefusalIsNotReportedAsZeroJobs() {
    for (RuntimeException refusal : capacityRefusals()) {
      var indexing = mock(IndexingService.class);
      when(indexing.getWatchedRoots(any(EngineContext.class)))
          .thenReturn(List.of(new IndexingService.WatchedRoot("notes", root)));
      when(indexing.countJobsByPathPrefix(eq(root), any(EngineContext.class))).thenThrow(refusal);
      var ctx = context();

      controller(indexing, mock(ExcludesService.class)).handleListRootsSubstrate(ctx);

      assertCapacityRefusal(ctx);
    }
  }

  private IndexingController controller(IndexingService indexing, ExcludesService excludes) {
    var controller = new IndexingController(() -> indexing, excludes, root, null);
    var capability = mock(Capability.class);
    when(capability.available()).thenReturn(true);
    controller.setWorkerCapability(capability);
    return controller;
  }

  private Context context() {
    var ctx = mock(Context.class);
    when(ctx.attribute(RequestEngineContext.ATTRIBUTE)).thenReturn(TestRequestContexts.browser());
    when(ctx.path()).thenReturn("/api/indexing/test");
    when(ctx.bodyAsClass(Map.class)).thenReturn(Map.of(
        "path", root.toString(), "collection", "notes", "pathHash", "test-hash"));
    when(ctx.queryParam("pathHash")).thenReturn("test-hash");
    when(ctx.status(anyInt())).thenReturn(ctx);
    return ctx;
  }

  private static void assertCapacityRefusal(Context ctx) {
    verify(ctx).status(429);
    verify(ctx).header("Retry-After", "3");
    var body = response(ctx);
    assertEquals("ADMISSION_ENGINE_LIMIT", body.get("errorCode"));
    assertEquals(false, body.get("retrySafe"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> response(Context ctx) {
    var response = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(response.capture());
    return (Map<String, Object>) response.getValue();
  }

  private static List<RuntimeException> capacityRefusals() {
    return List.of(
        new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 3),
        new CompletionException(new ExecutionException(
            new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 3))),
        new EngineExecutorRejectedException(
            EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "engine.calls", 3),
        new CompletionException(new EngineExecutorRejectedException(
            EngineExecutorRejectedException.Reason.QUEUE_LIMIT, "engine.calls", 3)));
  }

  private static Stream<Arguments> indexingHandlers() {
    return Stream.of(
        handler("list roots", IndexingController::handleListRoots),
        handler("add root", IndexingController::handleAddRoot),
        handler("preview root", IndexingController::handlePreviewRoot),
        handler("remove root", IndexingController::handleRemoveRoot),
        handler("delete collection", IndexingController::handleDeleteCollection),
        handler("apply excludes", IndexingController::handleApplyExcludes),
        handler("migration cutover", IndexingController::handleMigrationCutover),
        handler("migration rollback", IndexingController::handleMigrationRollback),
        handler("migration pause", IndexingController::handleMigrationPause),
        handler("migration resume", IndexingController::handleMigrationResume),
        handler("index GC", IndexingController::handleIndexGc),
        handler("settle index", IndexingController::handleSettleIndex),
        handler("suggested roots", IndexingController::handleSuggestedRoots),
        handler("list failed jobs", IndexingController::handleListFailedJobs),
        handler("failed jobs substrate", IndexingController::handleListFailedJobsSubstrate),
        handler("failed jobs by prefix", IndexingController::handleListFailedJobsByPathPrefix),
        handler("roots substrate", IndexingController::handleListRootsSubstrate),
        handler("clear failed jobs", IndexingController::handleClearFailedJobs),
        handler("recent ingestion events", IndexingController::handleRecentIngestionEvents),
        handler("ingestion outcome summary", IndexingController::handleIngestionOutcomeSummary),
        handler("resolve path hash", IndexingController::handleResolvePathHash));
  }

  private static Arguments handler(String name, BiConsumer<IndexingController, Context> handler) {
    return Arguments.of(name, handler);
  }
}
