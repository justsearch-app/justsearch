/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.ScanRootProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("recorded root preparation")
final class RecordedRootPreparationTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("concurrent duplicate registration queues and records one root")
  void concurrentDuplicateRegistrationIsAtomic() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("concurrent")).toAbsolutePath().normalize();
    Fixture fixture = new Fixture(ExcludeMatcher::empty, null);
    ExecutorService callers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var first =
          callers.submit(
              () -> {
                ready.countDown();
                start.await();
                fixture.ops.addWatchedRoot("same-label", root, TestEngineContexts.internal());
                return null;
              });
      var second =
          callers.submit(
              () -> {
                ready.countDown();
                start.await();
                fixture.ops.addWatchedRoot("same-label", root, TestEngineContexts.internal());
                return null;
              });
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw e.getCause() instanceof Exception exception ? exception : e;
    } finally {
      callers.shutdownNow();
    }

    assertEquals(1, fixture.watchedRoots.size());
    assertEquals("same-label", fixture.state.getCollection(root));
    assertEquals(1, fixture.submittedWalks.get(), "only one duplicate may queue a walk");
    assertEquals(1, fixture.watchCalls.get(), "only one duplicate may register a watcher");
    assertEquals("same-label", fixture.bindingsAtSubmission.get().get(0).collection());
  }

  @Test
  @DisplayName("a removed root is not reinserted by a completed walk")
  void completedWalkCannotReinsertRemovedRoot() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("removed")).toAbsolutePath().normalize();
    CountDownLatch scanEntered = new CountDownLatch(1);
    CountDownLatch releaseScan = new CountDownLatch(1);
    Fixture fixture =
        new Fixture(
            ExcludeMatcher::empty,
            (rootPath, collection, mode, excludes, progress, context) -> {
              scanEntered.countDown();
              try {
                releaseScan.await(10, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("walk was interrupted", e);
              }
              return ScanRootProgress.newBuilder().setComplete(true).build();
            });

    fixture.ops.addWatchedRoot("notes", root, TestEngineContexts.internal());
    Consumer<io.justsearch.core.context.EngineContext> queued = fixture.queuedWalk.get();
    assertNotNull(queued, "the initial walk must be captured for deterministic execution");
    ExecutorService walker = Executors.newSingleThreadExecutor();
    try {
      var walk = walker.submit(() -> queued.accept(TestEngineContexts.internal()));
      assertTrue(scanEntered.await(10, TimeUnit.SECONDS));
      fixture.ops.removeWatchedPath(root, TestEngineContexts.internal());
      releaseScan.countDown();
      walk.get(10, TimeUnit.SECONDS);
    } finally {
      walker.shutdownNow();
    }

    assertEquals(null, fixture.state.getCollection(root));
    assertFalse(fixture.watchedRoots.containsKey(root));
  }

  private static final class Fixture {
    private final Map<Path, Instant> watchedRoots = new java.util.concurrent.ConcurrentHashMap<>();
    private final WatchedRootsState state;
    private final ExecutorService walkExecutor;
    private final RootLifecycleOps ops;
    private final AtomicInteger submittedWalks = new AtomicInteger();
    private final AtomicInteger watchCalls = new AtomicInteger();
    private final AtomicReference<List<IngestCollectionPolicy.RootBinding>> bindingsAtSubmission =
        new AtomicReference<>();
    private final AtomicReference<Consumer<io.justsearch.core.context.EngineContext>> queuedWalk =
        new AtomicReference<>();

    private Fixture(
        Supplier<ExcludeMatcher> excludes,
        RootLifecycleOps.ScanRootFn scanRootFn) {
      this(excludes, scanRootFn, null);
    }

    private Fixture(
        Supplier<ExcludeMatcher> excludes,
        RootLifecycleOps.ScanRootFn scanRootFn,
        Path rootsFile) {
      Path storePath = rootsFile;
      this.state = new WatchedRootsState(watchedRoots, new WatchedRootsStore(storePath, null));
      this.walkExecutor = mock(ExecutorService.class);
      RootLifecycleOps.ScanRootFn scan =
          scanRootFn == null
              ? (rootPath, collection, mode, globs, progress, context) -> {
                return null;
              }
              : (rootPath, collection, mode, globs, progress, context) -> {
                return scanRootFn.scan(rootPath, collection, mode, globs, progress, context);
              };
      this.ops =
          new RootLifecycleOps(
              watchedRoots,
              state,
              excludes,
              scan,
              new RootLifecycleOps.WorkerWatchFn() {
                @Override
                public void watch(String rootPath, String collection, io.justsearch.core.context.EngineContext context) {
                  watchCalls.incrementAndGet();
                }

                @Override
                public void unwatch(String rootPath, io.justsearch.core.context.EngineContext context) {}
              },
              (path, context) -> {
                return DeleteByPathResponse.newBuilder().build();
              },
              (id, context) -> DeleteByIdResponse.newBuilder().setSuccess(true).build(),
              mock(SyncOps.class),
              walkExecutor,
              (body, context) -> {
                submittedWalks.incrementAndGet();
                bindingsAtSubmission.set(watchedRoots.keySet().stream()
                    .map(root -> new IngestCollectionPolicy.RootBinding(root, state.getCollection(root))).toList());
                queuedWalk.set(body);
              });
    }
  }

}
