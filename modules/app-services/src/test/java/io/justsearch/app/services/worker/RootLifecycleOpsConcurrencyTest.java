package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ipc.DeleteByPathResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
final class RootLifecycleOpsConcurrencyTest {
  @TempDir Path tempDir;

  @Test
  void retainedAncestorPreventsFalseCompletedSubtreeRemoval() throws Exception {
    Path parent = Files.createDirectories(tempDir.resolve("ancestor"));
    Path nested = Files.createDirectories(parent.resolve("nested"));
    Path rootsFile = tempDir.resolve("ancestor.json");
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null));
    state.register(parent, "parent", false);
    state.register(nested, "nested", false);
    state.persist();
    Set<String> watchers = ConcurrentHashMap.newKeySet();
    watchers.add(parent.toString());
    watchers.add(nested.toString());
    var deletes = new java.util.concurrent.atomic.AtomicInteger();
    var removing = ops(roots, state, watcher(watchers, path -> {}), (path, context) -> {
      deletes.incrementAndGet();
      return deleted();
    });
    assertEquals(-1, removing.removeWatchedPath(nested, TestEngineContexts.internal()));
    assertEquals(0, deletes.get());
    assertEquals(Set.of(parent, nested), roots.keySet());
    assertEquals(roots.keySet(), reloaded(rootsFile).keySet());
    assertEquals(Set.of(parent.toString(), nested.toString()), watchers);
    assertEquals(1, removing.removeWatchedPath(parent, TestEngineContexts.internal()));
    assertTrue(roots.isEmpty());
    assertTrue(watchers.isEmpty());
  }

  @Test
  void removalDrainsActiveAndFencesQueuedRootScans() throws Exception {
    for (String entry : java.util.List.of("initial", "forced", "persisted")) {
      Path parent = Files.createDirectories(tempDir.resolve(entry));
      Path nested = Files.createDirectories(parent.resolve("nested"));
      Path rootsFile = tempDir.resolve(entry + "-roots.json");
      Map<Path, Instant> roots = new ConcurrentHashMap<>();
      var state = new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null));
      Set<String> watchers = ConcurrentHashMap.newKeySet();
      Set<String> documents = ConcurrentHashMap.newKeySet();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var scanCalls = new java.util.concurrent.atomic.AtomicInteger();
      var queued = new java.util.ArrayList<java.util.function.Consumer<EngineContext>>();
      var scanning = new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
          (path, collection, mode, globs, progress, context) -> {
            scanCalls.incrementAndGet();
            documents.add("first batch");
            entered.countDown();
            await(release);
            documents.add("last batch");
            return io.justsearch.ipc.ScanRootProgress.newBuilder().setComplete(true).build();
          }, watcher(watchers, path -> {}),
          (path, context) -> deleted(), (id, context) -> null,
          mock(SyncOps.class), mock(ExecutorService.class), (body, context) -> queued.add(body));
      var context = TestEngineContexts.internal();
      scanning.addWatchedRoot("parent", parent, context);
      scanning.addWatchedRoot("nested", nested, context);
      if (!entry.equals("initial")) {
        queued.clear();
        if (entry.equals("forced")) scanning.reindexWatchedRoots(true, context);
        else scanning.reindexPersistedRoots(context);
      }
      var removing = ops(roots, state, watcher(watchers, path -> {}), (path, owned) -> {
        assertEquals(0, release.getCount(), "deletion must follow producer exit");
        documents.clear();
        return deleted();
      });
      var scan = new FutureTask<Void>(() -> {
        // A nested root's producer must be drained by removal of its parent too.
        queued.get(queued.size() - 1).accept(context);
        return null;
      });
      var remove = new FutureTask<Integer>(() -> removing.removeWatchedPath(parent, context));
      var scanThread = new Thread(scan);
      var removeThread = new Thread(remove);
      try {
        scanThread.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        removeThread.start();
        assertBlocked(removeThread);
        assertFalse(remove.isDone(), "an active producer owns the cleanup fence");
        release.countDown();
        scan.get(2, TimeUnit.SECONDS);
        assertEquals(1, remove.get(2, TimeUnit.SECONDS).intValue());
        queued.forEach(body -> body.accept(context));
        assertEquals(1, scanCalls.get(), "removed membership must fence queued initial/reindex scans");
        assertTrue(documents.isEmpty(), "the last scan batch must be deleted before success");
        assertTrue(watchers.isEmpty());
        assertTrue(roots.isEmpty());
        assertTrue(reloaded(rootsFile).isEmpty());
      } finally {
        release.countDown();
        scanThread.join(2_000);
        removeThread.join(2_000);
      }
    }
  }

  @Test
  void removalDrainsReconciliationAndFencesStaleRootSnapshots() throws Exception {
    for (boolean force : java.util.List.of(false, true)) {
      Path root = Files.createDirectories(tempDir.resolve("sync" + force));
      Path rootsFile = tempDir.resolve("sync" + force + ".json");
      Map<Path, Instant> roots = new ConcurrentHashMap<>();
      var state = new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null));
      state.register(root, null, false);
      state.persist();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      Set<String> documents = ConcurrentHashMap.newKeySet();
      var service = mock(IngestServiceCalls.class, org.mockito.Mockito.CALLS_REAL_METHODS);
      doAnswer(invocation -> {
        calls.incrementAndGet();
        entered.countDown();
        await(release);
        documents.add("reconciled file");
        return io.justsearch.ipc.SyncDirectoryResponse.newBuilder().setFilesAdded(1).build();
      }).when(service).syncDirectory(org.mockito.ArgumentMatchers.any());
      IngestRpcExecutor rpc = new IngestRpcExecutor() {
        @Override
        public <T> T execute(String operation, KnowledgeClient.RpcDeadlineCategory category,
            java.util.function.Function<IngestServiceCalls, T> fn, EngineContext context) {
          return fn.apply(service);
        }
      };
      var sync = new SyncOps(new io.justsearch.core.execution.TestEngineExecutors(), rpc,
          roots, (path, unverified) -> {}, (path, count) -> {}, state);
      var removing = ops(roots, state, watcher(ConcurrentHashMap.newKeySet(), path -> {}),
          (path, owned) -> {
            documents.clear();
            return deleted();
          });
      var context = TestEngineContexts.internal();
      var reconcile = new FutureTask<Void>(() -> {
        sync.syncWatchedDirectory(root.toString(), force, context);
        return null;
      });
      var remove = new FutureTask<Integer>(() -> removing.removeWatchedPath(root, context));
      var reconcileThread = new Thread(reconcile);
      var removeThread = new Thread(remove);
      try {
        reconcileThread.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        removeThread.start();
        assertBlocked(removeThread);
        release.countDown();
        reconcile.get(2, TimeUnit.SECONDS);
        assertEquals(1, remove.get(2, TimeUnit.SECONDS).intValue());
        assertTrue(sync.syncWatchedDirectory(root.toString(), force, context).getSkipped());
        assertEquals(1, calls.get(), "stale periodic/explicit reconciliation must not call the Engine");
        assertTrue(documents.isEmpty());
        assertTrue(roots.isEmpty());
        assertTrue(reloaded(rootsFile).isEmpty());
      } finally {
        release.countDown();
        reconcileThread.join(2_000);
        removeThread.join(2_000);
        sync.stopPeriodicSync();
      }
    }
  }

  @Test
  void failedPersistenceRetainsLiveAndDurableRemovalObligation() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("persist-failure"));
    Path rootsFile = tempDir.resolve("persist-failure.json");
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var store = spy(new WatchedRootsStore(rootsFile, null));
    var state = new WatchedRootsState(roots, store);
    state.register(root, "original", false);
    state.persist();
    var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(invocation -> {
      if (failing.get()) throw new java.io.UncheckedIOException(new java.io.IOException("disk unavailable"));
      return invocation.callRealMethod();
    }).when(store).persistRoots(org.mockito.ArgumentMatchers.anyMap(),
        org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anySet(),
        org.mockito.ArgumentMatchers.anyMap());
    var removing = ops(roots, state, watcher(ConcurrentHashMap.newKeySet(), path -> {}),
        (path, context) -> deleted());
    org.junit.jupiter.api.Assertions.assertThrows(java.io.UncheckedIOException.class,
        () -> removing.removeWatchedPath(root, TestEngineContexts.internal()));
    assertEquals(Set.of(root), roots.keySet());
    assertEquals(Set.of(root), reloaded(rootsFile).keySet());
    assertEquals("original", state.getCollection(root));
    failing.set(false);
    assertEquals(1, removing.removeWatchedPath(root, TestEngineContexts.internal()));
    assertTrue(roots.isEmpty());
    assertTrue(reloaded(rootsFile).isEmpty());
  }

  @Test
  void removalWaitsForNestedWatcherCreationAcrossClients() throws Exception {
    Path parent = Files.createDirectories(tempDir.resolve("parent"));
    Path nested = Files.createDirectories(parent.resolve("nested"));
    Path rootsFile = tempDir.resolve("roots.json");
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null));
    state.register(parent, "default", false);
    state.persist();
    Set<String> watchers = ConcurrentHashMap.newKeySet();
    watchers.add(parent.toString());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var watcher = watcher(watchers, path -> {
      if (path.equals(nested.toString())) {
        entered.countDown();
        await(release);
      }
    });
    var adding = ops(roots, state, watcher, (path, context) -> deleted());
    var removing = ops(roots, state, watcher, (path, context) -> deleted());
    var add = new FutureTask<Void>(() -> {
      adding.addWatchedRoot("nested", nested, TestEngineContexts.internal());
      return null;
    });
    var remove = new FutureTask<Integer>(
        () -> removing.removeWatchedPath(parent, TestEngineContexts.internal()));
    var addThread = new Thread(add);
    var removeThread = new Thread(remove);
    try {
      addThread.start();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      removeThread.start();
      assertBlocked(removeThread);
      assertFalse(remove.isDone());
      release.countDown();
      add.get(2, TimeUnit.SECONDS);
      assertEquals(1, remove.get(2, TimeUnit.SECONDS).intValue());
      assertTrue(watchers.isEmpty(), "completed removal must stop the newly created nested watcher");
      assertTrue(roots.isEmpty());
      assertTrue(reloaded(rootsFile).isEmpty());
    } finally {
      release.countDown();
      addThread.join(2_000);
      removeThread.join(2_000);
    }
  }

  @Test
  void nestedAdditionDuringDeletionKeepsItsWatcherAndMetadataTogether() throws Exception {
    Path parent = Files.createDirectories(tempDir.resolve("parent"));
    Path nested = Files.createDirectories(parent.resolve("nested"));
    Path rootsFile = tempDir.resolve("roots.json");
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var state = new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null));
    state.register(parent, "default", false);
    state.persist();
    Set<String> watchers = ConcurrentHashMap.newKeySet();
    watchers.add(parent.toString());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var watcher = watcher(watchers, path -> {});
    var removing = ops(roots, state, watcher, (path, context) -> {
      entered.countDown();
      await(release);
      return deleted();
    });
    var adding = ops(roots, state, watcher, (path, context) -> deleted());
    var remove = new FutureTask<Integer>(
        () -> removing.removeWatchedPath(parent, TestEngineContexts.internal()));
    var add = new FutureTask<Void>(() -> {
      adding.addWatchedRoot("nested", nested, TestEngineContexts.internal());
      return null;
    });
    var removeThread = new Thread(remove);
    var addThread = new Thread(add);
    try {
      removeThread.start();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      addThread.start();
      assertBlocked(addThread);
      assertFalse(roots.containsKey(nested), "registration must wait for removal to complete");
      release.countDown();
      assertEquals(1, remove.get(2, TimeUnit.SECONDS).intValue());
      add.get(2, TimeUnit.SECONDS);
      // This addition takes effect after removal. Its live watcher must have persisted ownership.
      assertEquals(Set.of(nested.toString()), watchers);
      assertEquals(Set.of(nested), roots.keySet());
      assertEquals(roots.keySet(), reloaded(rootsFile).keySet());
    } finally {
      release.countDown();
      removeThread.join(2_000);
      addThread.join(2_000);
    }
  }

  private static void assertBlocked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
        && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(Thread.State.BLOCKED, thread.getState(), "lifecycle must await the owning operation");
  }

  @Test
  void restoredWatcherCannotOutliveRemovalOfItsSnapshot() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("parent"));
    Path rootsFile = tempDir.resolve("roots.json");
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    var state = spy(new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null)));
    state.register(root, "default", false);
    state.persist();
    Set<String> watchers = ConcurrentHashMap.newKeySet();
    watchers.add(root.toString());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(invocation -> {
      entered.countDown();
      await(release);
      return invocation.callRealMethod();
    }).when(state).getCollection(root);
    var watcher = watcher(watchers, path -> {});
    var restoring = ops(roots, state, watcher, (path, context) -> deleted());
    var removing = ops(roots, state, watcher, (path, context) -> deleted());
    var restore = new FutureTask<Void>(() -> {
      restoring.reindexPersistedRoots(TestEngineContexts.internal());
      return null;
    });
    var restoreThread = new Thread(restore);
    try {
      restoreThread.start();
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertEquals(1, removing.removeWatchedPath(root, TestEngineContexts.internal()));
      release.countDown();
      restore.get(2, TimeUnit.SECONDS);
      assertTrue(watchers.isEmpty(), "stale restoration must not resurrect a removed watcher");
      assertTrue(roots.isEmpty());
      assertTrue(reloaded(rootsFile).isEmpty());
    } finally {
      release.countDown();
      restoreThread.join(2_000);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static DeleteByPathResponse deleted() {
    return DeleteByPathResponse.newBuilder().setDeletedJobs(1).build();
  }

  private static RootLifecycleOps.WorkerWatchFn watcher(
      Set<String> live, java.util.function.Consumer<String> beforeWatch) {
    return new RootLifecycleOps.WorkerWatchFn() {
      @Override
      public void watch(String path, String collection, EngineContext context) {
        beforeWatch.accept(path);
        live.add(path);
      }

      @Override
      public void unwatch(String path, EngineContext context) {
        live.remove(path);
      }
    };
  }

  private static RootLifecycleOps ops(Map<Path, Instant> roots, WatchedRootsState state,
      RootLifecycleOps.WorkerWatchFn watcher,
      BiFunction<Path, EngineContext, DeleteByPathResponse> delete) {
    return new RootLifecycleOps(roots, state, ExcludeMatcher::empty,
        (path, collection, mode, globs, progress, context) -> null, watcher, delete,
        (id, context) -> null, mock(SyncOps.class), mock(ExecutorService.class),
        (body, context) -> {});
  }

  private static Map<Path, Instant> reloaded(Path rootsFile) {
    Map<Path, Instant> roots = new ConcurrentHashMap<>();
    new WatchedRootsState(roots, new WatchedRootsStore(rootsFile, null)).loadPersistedRoots();
    return roots;
  }
}
