/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.google.protobuf.Message;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathResponse;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class DeletionCompletionOutcomeTest {
  @Test void WX2ApplyRetainsBufferedFileAndFolderScopesAndRetryConfirmsThem(
      @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
    java.nio.file.Files.writeString(root.resolve("a.log"), "a");
    java.nio.file.Files.writeString(java.nio.file.Files.createDirectory(root.resolve("private")).resolve("b.log"), "b");
    var pending = new java.util.concurrent.atomic.AtomicBoolean(true);
    var ops = ops((path, ctx) -> pending.get()
        ? (DeleteByPathResponse) buffered(DeleteByPathResponse.newBuilder())
        : DeleteByPathResponse.newBuilder().build(), (id, ctx) -> pending.get()
        ? (DeleteByIdResponse) buffered(DeleteByIdResponse.newBuilder().setSuccess(true))
        : DeleteByIdResponse.newBuilder().setSuccess(true).build());
    var indexing = (io.justsearch.app.api.IndexingService) java.lang.reflect.Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class<?>[] {io.justsearch.app.api.IndexingService.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getWatchedRoots")) {
            return java.util.List.of(new io.justsearch.app.api.IndexingService.WatchedRoot("docs", root));
          }
          return RootLifecycleOps.class.getDeclaredMethod(method.getName(), method.getParameterTypes()).invoke(ops, args);
        });
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.configuration.resolved.ResolvedConfig.builder()
            .putDefault("justsearch.ui.exclude_patterns", "[\"*.log\",\"private/\"]").build()));
    try {
      var service = new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing);
      var first = new ObjectMapper().valueToTree(service.applyExcludes(false, TestEngineContexts.durableInternal()));
      assertEquals(0, first.path("confirmedRemovals").asInt(-1));
      assertEquals(2, first.path("uncheckedScopes").size());
      var paths = new java.util.HashSet<String>();
      for (var scope : first.path("uncheckedScopes")) {
        paths.add(scope.path("path").asText());
        assertEquals("not_removed", scope.path("status").asText());
        assertEquals("Removal completes when the index rebuild finishes", scope.path("reason").asText());
      }
      assertEquals(java.util.Set.of(root.resolve("a.log").toString(), root.resolve("private").toString()), paths);
      pending.set(false);
      var retry = new ObjectMapper().valueToTree(service.applyExcludes(false, TestEngineContexts.durableInternal()));
      assertEquals(2, retry.path("confirmedRemovals").asInt(-1));
      assertEquals(0, retry.path("uncheckedScopes").size());
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test void WX2BufferedPrefixAndFileArePendingWhileLegacyAcceptanceStaysUnchanged() throws Exception {
    var prefix = (DeleteByPathResponse) buffered(DeleteByPathResponse.newBuilder());
    var file = (DeleteByIdResponse) buffered(DeleteByIdResponse.newBuilder().setSuccess(true));
    var ops = ops((path, ctx) -> prefix, (id, ctx) -> file);
    for (String method : java.util.List.of("deleteDocsByPathPrefixWithOutcome", "deleteDocByIdWithOutcome")) {
      var outcome = outcome(ops, method);
      assertFalse(outcome.path("confirmed").asBoolean(), "buffered work is not complete");
      assertEquals("Removal completes when the index rebuild finishes", outcome.path("error").asText());
    }
    assertEquals(0, ops.deleteDocsByPathPrefix(Path.of("excluded"), TestEngineContexts.internal()));
    assertTrue(ops.deleteDocById("excluded", TestEngineContexts.internal()));
  }

  @Test void WX2NegativeOrUnrepresentablePrefixCountIsUnknown() throws Exception {
    for (long count : new long[] {-1, (long) Integer.MAX_VALUE + 1}) {
      var ops = ops((path, ctx) -> DeleteByPathResponse.newBuilder().setDeletedJobs(count).build(),
          (id, ctx) -> null);
      var result = outcome(ops, "deleteDocsByPathPrefixWithOutcome");
      assertFalse(result.path("confirmed").asBoolean(), "invalid count must never confirm removal");
      assertEquals(0, result.path("deletedJobs").asInt());
      assertFalse(result.path("error").asText().isBlank());
    }
  }

  @Test void WX2FileErrorsUnknownAndCompletedReceiptsRemainDistinct() throws Exception {
    for (String kind : java.util.List.of("complete", "failed", "error", "null", "transport", "refusal", "circuit")) {
      var ops = ops((path, ctx) -> null, (id, ctx) -> switch (kind) {
        case "complete" -> DeleteByIdResponse.newBuilder().setSuccess(true).build();
        case "failed" -> DeleteByIdResponse.newBuilder().build();
        case "error" -> DeleteByIdResponse.newBuilder().setSuccess(true).setError("disk failure").build();
        case "null" -> null;
        case "refusal" -> throw new io.justsearch.app.api.EngineAdmissionException(
            io.justsearch.app.api.EngineAdmissionException.Reason.ENGINE_LIMIT, 3);
        case "circuit" -> throw new io.justsearch.ipc.CircuitBreakerOpenException("open");
        default -> throw new IllegalStateException("transport down");
      });
      var result = outcome(ops, "deleteDocByIdWithOutcome");
      assertEquals(kind.equals("complete"), result.path("confirmed").asBoolean());
      if (!kind.equals("complete")) assertFalse(result.path("error").asText().isBlank());
    }
  }

  private static Message buffered(Message.Builder builder) {
    var field = builder.getDescriptorForType().findFieldByName("buffered");
    assertNotNull(field, "WX2 requires an atomic buffered receipt from the Worker");
    return builder.setField(field, true).build();
  }

  private static JsonNode outcome(RootLifecycleOps ops, String name) throws Exception {
    var method = java.util.Arrays.stream(RootLifecycleOps.class.getDeclaredMethods())
        .filter(m -> m.getName().equals(name)).findFirst();
    assertTrue(method.isPresent(), "Apply requires completion-aware deletion: " + name);
    Object path = name.startsWith("deleteDocs") ? Path.of("excluded") : "excluded";
    return new ObjectMapper().valueToTree(method.orElseThrow().invoke(ops, path, TestEngineContexts.internal()));
  }

  private static RootLifecycleOps ops(BiFunction<Path, EngineContext, DeleteByPathResponse> prefix,
      BiFunction<String, EngineContext, DeleteByIdResponse> file) {
    var roots = new ConcurrentHashMap<Path, java.time.Instant>();
    return new RootLifecycleOps(roots, new WatchedRootsState(roots, new WatchedRootsStore(null, null)),
        ExcludeMatcher::empty, (path, collection, mode, globs, progress, context) -> null,
        new RootLifecycleOps.WorkerWatchFn() {
          @Override public void watch(String path, String collection, EngineContext context) {}
          @Override public void unwatch(String path, EngineContext context) {}
        }, prefix, file, mock(SyncOps.class), mock(ExecutorService.class), (body, context) -> {});
  }
}
