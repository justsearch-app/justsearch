/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.DeleteByPathResponse;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

final class PrefixDeletionOutcomeTest {
  @Test void WX1PrefixOutcomeDistinguishesZeroJobsFromErrorsWithoutChangingLegacyCallers() throws Exception {
    var context = TestEngineContexts.internal();
    Path path = Path.of("excluded").toAbsolutePath();
    for (String kind : java.util.List.of("empty", "error", "refusal", "transport", "circuit")) {
      var ops = ops((prefix, ctx) -> {
        return switch (kind) {
          case "empty" -> DeleteByPathResponse.newBuilder().build();
          case "error" -> DeleteByPathResponse.newBuilder().setError("disk failure").build();
          case "refusal" -> throw new EngineAdmissionException(EngineAdmissionException.Reason.ENGINE_LIMIT, 3);
          case "circuit" -> throw new io.justsearch.ipc.CircuitBreakerOpenException("open");
          default -> throw new IllegalStateException("transport down");
        };
      });
      // Reflection keeps this contract test runnable on the before-state, where the variant is absent.
      var method = java.util.Arrays.stream(RootLifecycleOps.class.getDeclaredMethods())
          .filter(m -> m.getName().equals("deleteDocsByPathPrefixWithOutcome")).findFirst();
      assertTrue(method.isPresent(), "WX1 requires an outcome-carrying prefix deletion");
      var outcome = new ObjectMapper().valueToTree(assertDoesNotThrow(() -> method.orElseThrow().invoke(ops, path, context)));
      assertEquals(kind.equals("empty"), outcome.path("confirmed").asBoolean());
      assertEquals(0, outcome.path("deletedJobs").asInt());
      if (!kind.equals("empty")) assertFalse(outcome.path("error").asText().isBlank());
      if (kind.equals("refusal")) {
        assertThrows(EngineAdmissionException.class, () -> ops.deleteDocsByPathPrefix(path, context));
      } else {
        assertEquals(0, ops.deleteDocsByPathPrefix(path, context), "legacy best-effort return stays zero");
      }
    }
  }
  private static RootLifecycleOps ops(java.util.function.BiFunction<Path, EngineContext, DeleteByPathResponse> delete) {
    var roots = new ConcurrentHashMap<Path, java.time.Instant>();
    return new RootLifecycleOps(roots, new WatchedRootsState(roots, new WatchedRootsStore(null, null)),
        ExcludeMatcher::empty, (path, collection, mode, globs, progress, context) -> null,
        new RootLifecycleOps.WorkerWatchFn() {
          @Override public void watch(String path, String collection, EngineContext context) {}
          @Override public void unwatch(String path, EngineContext context) {}
        }, delete, (id, context) -> null, mock(SyncOps.class), mock(ExecutorService.class), (body, context) -> {});
  }
}
