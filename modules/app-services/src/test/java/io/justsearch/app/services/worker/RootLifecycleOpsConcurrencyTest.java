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
