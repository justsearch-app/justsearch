/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.DeleteByPathResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** WP3.5 S35.1–S35.4: a failed removal keeps a durable, truthful retry entry. */
final class RootRemovalIncompleteTest {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"watcher", "delete", "save", "buffered", "invalid-count"})
  void eachFailureKeepsAnIncompleteEntryAndRetry(String step) throws Exception {
    Path root = Files.createDirectory(temp.resolve("folder"));
    Path file = temp.resolve("watched_roots.json");
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var store = spy(new WatchedRootsStore(file, null));
    var state = new WatchedRootsState(roots, store);
    state.register(root, "corpus", false);
    state.markIndexed(root);
    state.persist();
    var failing = new AtomicBoolean(true);
    Set<String> live = ConcurrentHashMap.newKeySet();
    live.add(root.toString());
    var watcher = new RootLifecycleOps.WorkerWatchFn() {
      @Override public void watch(String path, String collection, EngineContext context) { live.add(path); }
      @Override public void unwatch(String path, EngineContext context) {
        live.remove(path); // A failed stop can have partially succeeded.
        if (step.equals("watcher") && failing.get()) throw new IllegalStateException("forced watcher failure");
      }
    };
    doAnswer(call -> {
      Map<?, ?> remaining = call.getArgument(0);
      if (step.equals("save") && failing.get() && !remaining.containsKey(root)) {
        throw new java.io.UncheckedIOException(new java.io.IOException("forced save failure"));
      }
      return call.callRealMethod();
    }).when(store).persistRoots(anyMap(), anyMap(), anySet(), anyMap());
    var ops = ops(roots, state, watcher, (path, context) -> {
      if (failing.get() && step.equals("delete")) {
        return DeleteByPathResponse.newBuilder().setDeletedJobs(-1).setError("forced delete failure").build();
      }
      if (failing.get() && step.endsWith("buffered")) {
        return DeleteByPathResponse.newBuilder().setBuffered(true).build();
      }
      if (failing.get() && step.equals("invalid-count")) {
        return DeleteByPathResponse.newBuilder().setDeletedJobs(Integer.MAX_VALUE + 1L).build();
      }
      return deleted();
    });
    if (step.equals("save")) {
      assertThrows(java.io.UncheckedIOException.class,
          () -> ops.removeWatchedPath(root, TestEngineContexts.internal()));
    } else if (step.equals("delete") || step.equals("buffered")) {
      var refusal = assertThrows(io.justsearch.app.api.knowledge.KnowledgeClientException.class,
          () -> ops.removeWatchedPath(root, TestEngineContexts.internal()));
      assertTrue(refusal.getMessage().contains("Removal did not complete"));
    } else {
      assertEquals(-1, assertDoesNotThrow(() -> ops.removeWatchedPath(root, TestEngineContexts.internal())));
    }
    assertEquals(Set.of(root), roots.keySet(), "failed removal must remain listed");
    assertIncomplete(ops, root);
    var restoredRoots = new ConcurrentHashMap<Path, Instant>();
    var restored = new WatchedRootsState(restoredRoots, new WatchedRootsStore(file, null));
    restored.loadPersistedRoots();
    assertIncomplete(ops(restoredRoots, restored, watcher, (path, context) -> deleted()), root);
    failing.set(false);
    assertEquals(1, ops.removeWatchedPath(root, TestEngineContexts.internal()));
    assertTrue(roots.isEmpty());
    assertTrue(live.isEmpty());
    assertTrue(new WatchedRootsStore(file, null).loadPersistedRoots().isEmpty());
  }

  @Test
  void crashAfterStoppingWatcherRestartsAsIncompleteAndCanBeRetried() throws Exception {
    Path root = Files.createDirectory(temp.resolve("folder"));
    Path file = temp.resolve("watched_roots.json");
    var roots = new ConcurrentHashMap<Path, Instant>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(file, null));
    state.register(root, "corpus", false);
    state.markIndexed(root);
    state.persist();
    var watcher = mock(RootLifecycleOps.WorkerWatchFn.class);
    var ops = ops(roots, state, watcher, (path, context) -> { throw new AssertionError("simulated process stop"); });
    assertThrows(AssertionError.class, () -> ops.removeWatchedPath(root, TestEngineContexts.internal()));
    var restoredRoots = new ConcurrentHashMap<Path, Instant>();
    var restored = new WatchedRootsState(restoredRoots, new WatchedRootsStore(file, null));
    restored.loadPersistedRoots();
    var retry = ops(restoredRoots, restored, watcher, (path, context) -> deleted());
    assertIncomplete(retry, root);
    retry.reindexPersistedRoots(TestEngineContexts.internal());
    verify(watcher, never()).watch(anyString(), anyString(), any(), any());
    assertEquals(1, retry.removeWatchedPath(root, TestEngineContexts.internal()));
    assertTrue(restoredRoots.isEmpty());
  }

  @Test
  void concurrentDuplicateAddCannotRecreateTheRootItWasRemoving() throws Exception {
    Path root = Files.createDirectory(temp.resolve("folder"));
    var roots = new ConcurrentHashMap<Path, Instant>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(temp.resolve("watched_roots.json"), null));
    state.register(root, "corpus", false);
    state.persist();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var ops = ops(roots, state, mock(RootLifecycleOps.WorkerWatchFn.class), (path, context) -> {
      entered.countDown();
      try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
      catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
      return deleted();
    });
    var remove = new java.util.concurrent.FutureTask<Integer>(() -> ops.removeWatchedPath(root, TestEngineContexts.internal()));
    var add = new java.util.concurrent.FutureTask<Void>(() -> {
      ops.addWatchedRoot("corpus", root, TestEngineContexts.internal());
      return null;
    });
    var removing = new Thread(remove);
    var adding = new Thread(add);
    try {
      removing.start();
      assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
      adding.start();
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
      while (adding.getState() != Thread.State.BLOCKED && adding.isAlive() && System.nanoTime() < deadline) {
        Thread.sleep(1);
      }
      assertEquals(Thread.State.BLOCKED, adding.getState(), "duplicate Add must overlap the removal");
      release.countDown();
      assertEquals(1, remove.get(2, java.util.concurrent.TimeUnit.SECONDS));
      add.get(2, java.util.concurrent.TimeUnit.SECONDS);
      assertTrue(roots.isEmpty(), "a duplicate Add already in flight must not recreate a removed root");
    } finally {
      release.countDown();
      removing.join(2_000);
      adding.join(2_000);
    }
    ops.addWatchedRoot("corpus", root, TestEngineContexts.internal());
    assertEquals(Set.of(root), roots.keySet(), "a deliberate later Add still works");
  }

  @Test
  void incompleteRemovalFencesOldAndNewRootProducersAndKeepsItsReason() throws Exception {
    Path root = Files.createDirectory(temp.resolve("folder"));
    var roots = new ConcurrentHashMap<Path, Instant>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(temp.resolve("watched_roots.json"), null));
    state.register(root, "corpus", false);
    state.markIndexed(root);
    state.persist();
    var before = state.captureRootProducer(root, true);
    var ops = ops(roots, state, mock(RootLifecycleOps.WorkerWatchFn.class),
        (path, context) -> DeleteByPathResponse.newBuilder().setError("forced failure").build());
    assertThrows(io.justsearch.app.api.knowledge.KnowledgeClientException.class,
        () -> ops.removeWatchedPath(root, TestEngineContexts.internal()));
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    for (var fence : List.of(before, state.captureRootProducer(root, true))) {
      fence.run(calls::incrementAndGet, () -> -1);
    }
    assertEquals(0, calls.get(), "queued or later reindex/check must leave incomplete removal alone");
    ops.addWatchedRoot("replacement", root, TestEngineContexts.internal());
    ops.reindexWatchedRoots(true, TestEngineContexts.internal());
    assertIncomplete(ops, root);
    assertEquals("corpus", state.getCollection(root));
    ops.addWatchedPath(root, TestEngineContexts.internal());
    assertIncomplete(ops, root);
    // A synchronous Worker can finish before removal, with its caller publishing status later.
    state.markIndexed(root);
    state.markRootUnavailable(root, RootLifecycleOps.ROOT_MISSING_REASON);
    state.recordRecordedWalk(root, false, RootLifecycleOps.ROOT_MISSING_REASON);
    assertIncomplete(ops, root);
  }

  @Test
  void unavailableServingReasonReachesCallerAndKeepsRetryEntry() throws Exception {
    Path root = Files.createDirectory(temp.resolve("read-only-folder"));
    Path file = temp.resolve("read-only-roots.json");
    var roots = new ConcurrentHashMap<Path, Instant>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(file, null));
    state.register(root, "corpus", false);
    state.persist();
    var refusal = new io.justsearch.app.api.knowledge.KnowledgeClientException(
        io.justsearch.app.api.knowledge.KnowledgeClientException.Status.UNAVAILABLE,
        "Removal did not complete: the search index is read-only or unavailable. "
            + "Wait for index recovery, then retry Remove.");
    var ops = ops(roots, state, mock(RootLifecycleOps.WorkerWatchFn.class),
        (path, context) -> { throw refusal; });
    assertSame(refusal, assertThrows(io.justsearch.app.api.knowledge.KnowledgeClientException.class,
        () -> ops.removeWatchedPath(root, TestEngineContexts.internal())));
    assertIncomplete(ops, root);
    var restoredRoots = new ConcurrentHashMap<Path, Instant>();
    var restored = new WatchedRootsState(restoredRoots, new WatchedRootsStore(file, null));
    restored.loadPersistedRoots();
    var retry = ops(restoredRoots, restored, mock(RootLifecycleOps.WorkerWatchFn.class),
        (path, context) -> deleted());
    assertIncomplete(retry, root);
    assertEquals(1, retry.removeWatchedPath(root, TestEngineContexts.internal()));
    assertTrue(restoredRoots.isEmpty());
  }

  private static void assertIncomplete(RootLifecycleOps ops, Path root) {
    var row = ops.getWatchedRoots(TestEngineContexts.internal()).stream()
        .filter(item -> item.path().equals(root)).findFirst().orElseThrow();
    String reason = row.walkError();
    assertNotNull(reason, "folder must visibly report incomplete removal");
    assertTrue(reason.toLowerCase(java.util.Locale.ROOT).contains("remov"), reason);
    assertTrue(reason.toLowerCase(java.util.Locale.ROOT).contains("retry"), reason);
    assertTrue(reason.toLowerCase(java.util.Locale.ROOT).contains("watch"), reason);
  }

  private static DeleteByPathResponse deleted() {
    return DeleteByPathResponse.newBuilder().setDeletedJobs(1).build();
  }

  private static RootLifecycleOps ops(Map<Path, Instant> roots, WatchedRootsState state,
      RootLifecycleOps.WorkerWatchFn watcher, BiFunction<Path, EngineContext, DeleteByPathResponse> delete) {
    return new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
        (path, collection, mode, globs, progress, context) -> null,
        watcher, delete, (id, context) -> null, mock(SyncOps.class), mock(ExecutorService.class),
        (body, context) -> body.accept(context));
  }
}
