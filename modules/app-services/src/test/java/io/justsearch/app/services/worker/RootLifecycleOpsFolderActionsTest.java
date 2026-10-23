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

final class RootLifecycleOpsFolderActionsTest {
  @TempDir Path tempDir;

  @Test
  void refusesNestedAddsInBothDirectionsWithoutChangingState() throws Exception {
    for (boolean addingParent : new boolean[] {false, true}) {
      Path parent = Files.createDirectories(tempDir.resolve("parent-" + addingParent));
      Path child = Files.createDirectories(parent.resolve("child"));
      Path watched = addingParent ? child : parent;
      Path requested = addingParent ? parent : child;
      Map<Path, Instant> roots = new ConcurrentHashMap<>();
      var state = new WatchedRootsState(roots, new WatchedRootsStore(null, null));
      state.register(watched, "original", false);
      var calls = new AtomicInteger();
      var ops = ops(roots, state, calls, ConcurrentHashMap.newKeySet());
      var refusal = assertThrows(IllegalArgumentException.class,
          () -> ops.addWatchedRoot("new", requested, TestEngineContexts.internal()));
      assertTrue(refusal.getMessage().contains("overlaps an already watched folder"));
      assertEquals(Set.of(watched), roots.keySet());
      assertEquals(0, calls.get());
      ops.addWatchedRoot("new", watched, TestEngineContexts.internal());
      assertEquals("original", state.getCollection(watched));
      assertEquals(0, calls.get(), "exact re-add remains idempotent");
    }
  }

  @Test
  void refusesDisappearedAndNonDirectoryPathsInsteadOfReportingAnAdd() throws Exception {
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(null, null));
    var ops = ops(roots, state, new AtomicInteger(), ConcurrentHashMap.newKeySet());
    Path missing = tempDir.resolve("missing");
    Path file = Files.writeString(tempDir.resolve("file.txt"), "text");
    for (Path path : java.util.List.of(missing, file)) {
      assertThrows(IllegalArgumentException.class,
          () -> ops.addWatchedRoot("default", path, TestEngineContexts.internal()));
    }
    assertTrue(roots.isEmpty());
  }

  @Test
  void legacyChildRemovalDropsRegistrationButPreservesAncestorOwnedDocuments() throws Exception {
    Path parent = Files.createDirectories(tempDir.resolve("legacy"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path rootsFile = tempDir.resolve("roots.json");
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null));
    state.register(parent, "parent", false);
    state.register(child, "child", false);
    state.persist();
    Set<String> watchers = ConcurrentHashMap.newKeySet();
    watchers.addAll(Set.of(parent.toString(), child.toString()));
    var deletes = new AtomicInteger();
    var ops = ops(roots, state, deletes, watchers);
    assertEquals(0, ops.removeWatchedPath(child, TestEngineContexts.internal()));
    assertEquals(0, deletes.get(), "ancestor still owns searchable documents");
    assertEquals(Set.of(parent), roots.keySet());
    assertEquals(Set.of(parent.toString()), watchers);
    var reopened = new ConcurrentHashMap<Path, Instant>();
    new WatchedRootsState(reopened, new WatchedRootsStore(rootsFile, null)).loadPersistedRoots();
    assertEquals(Set.of(parent), reopened.keySet());
    assertEquals(0, ops.removeWatchedPath(child, TestEngineContexts.internal()), "repeat is harmless");
    assertEquals(0, deletes.get());
    assertEquals(1, ops.removeWatchedPath(parent, TestEngineContexts.internal()));
    assertFalse(roots.containsKey(parent));
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
