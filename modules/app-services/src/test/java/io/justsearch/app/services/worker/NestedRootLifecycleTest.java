/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.ipc.DeleteByPathResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NestedRootLifecycleTest {
  @TempDir Path tempDir;

  @Test
  void removingLegacyParentKeepsNestedDeclarationWatcherAndCollection() throws Exception {
    Path parent = Files.createDirectories(tempDir.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path rootsFile = tempDir.resolve("nested-roots.json");
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null));
    state.register(parent, "parent", false);
    state.register(child, "child", true);
    state.persist();
    Set<String> watchers = ConcurrentHashMap.newKeySet();
    watchers.addAll(Set.of(parent.toString(), child.toString()));
    var deletes = new AtomicInteger();
    var ops = ops(roots, state, deletes, watchers);
    state.markInitializationPending(child);
    var producer = state.captureRootProducer(child, true);

    assertEquals(1, ops.removeWatchedPath(parent, TestEngineContexts.internal()));
    assertEquals(Set.of(child), roots.keySet());
    assertEquals(Set.of(child.toString()), watchers);
    assertEquals("child", state.getCollection(child));
    assertTrue(state.isInitializationPending(child));
    assertEquals("completed", producer.run(() -> "completed", () -> "retired"));
    var reopened = new ConcurrentHashMap<Path, Instant>();
    new WatchedRootsState(reopened, new WatchedRootsStore(rootsFile, null)).loadPersistedRoots();
    assertEquals(Set.of(child), reopened.keySet());
    assertEquals(0, ops.removeWatchedPath(parent, TestEngineContexts.internal()));
    assertEquals(1, deletes.get());
    assertEquals(1, ops.removeWatchedPath(child, TestEngineContexts.internal()));
    assertTrue(roots.isEmpty());
    assertTrue(watchers.isEmpty());
  }

  private static RootLifecycleOps ops(Map<Path, Instant> roots, WatchedRootsState state,
      AtomicInteger calls, Set<String> watchers) {
    return new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
        (path, collection, mode, globs, progress, context) -> null,
        new RootLifecycleOps.WorkerWatchFn() {
          @Override public void watch(String path, String collection,
              io.justsearch.core.context.EngineContext context) {
            calls.incrementAndGet(); watchers.add(path);
          }
          @Override public void unwatch(String path, io.justsearch.core.context.EngineContext context) {
            watchers.remove(path);
          }
        }, (path, context) -> {
          calls.incrementAndGet();
          return DeleteByPathResponse.newBuilder().setDeletedJobs(1).build();
        }, (id, context) -> null, mock(SyncOps.class), mock(ExecutorService.class),
        (body, context) -> calls.incrementAndGet());
  }
}
