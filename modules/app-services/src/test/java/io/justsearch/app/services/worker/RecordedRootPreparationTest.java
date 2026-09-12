/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.api.operations.RecordedRootPlan;
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
  @DisplayName("snapshot bindings is immutable")
  void snapshotBindingsIsImmutable() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("notes")).toAbsolutePath().normalize();
    Fixture fixture = new Fixture(() -> ExcludeMatcher.empty(), null);

    fixture.state.register(root, "my-notes", false);

    List<IngestCollectionPolicy.RootBinding> snapshot = fixture.state.snapshotBindings();
    assertEquals(List.of(new IngestCollectionPolicy.RootBinding(root, "my-notes")), snapshot);
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.add(new IngestCollectionPolicy.RootBinding(tempDir, "late")));
  }

  @Test
  @DisplayName("snapshot cannot observe membership before its collection label")
  void registerPublishesLabelAtomicallyAgainstSnapshot() throws Exception {
    Path root = tempDir.resolve("paused-registration").toAbsolutePath().normalize();
    CountDownLatch membershipInserted = new CountDownLatch(1);
    CountDownLatch releaseRegistration = new CountDownLatch(1);
    PausingMap watchedRoots = new PausingMap(membershipInserted, releaseRegistration);
    WatchedRootsState state = new WatchedRootsState(watchedRoots, new WatchedRootsStore(null, null));
    AtomicReference<List<IngestCollectionPolicy.RootBinding>> observed = new AtomicReference<>();
    AtomicReference<Throwable> threadFailure = new AtomicReference<>();
    CountDownLatch snapshotFinished = new CountDownLatch(1);
    Thread registrar =
        new Thread(
            () -> {
              try {
                state.register(root, "labelled", false);
              } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
              }
            },
            "recorded-root-registration");
    Thread snapshotter =
        new Thread(
            () -> {
              try {
                observed.set(state.snapshotBindings());
              } catch (Throwable failure) {
                threadFailure.compareAndSet(null, failure);
              } finally {
                snapshotFinished.countDown();
              }
            },
            "recorded-root-snapshot");
    try {
      registrar.start();
      assertTrue(membershipInserted.await(10, TimeUnit.SECONDS));
      snapshotter.start();

      // The registrar holds the state monitor while the map has membership but not its label.
      // A completed snapshot here would prove that register is not atomic with snapshotBindings.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (snapshotFinished.getCount() != 0 && snapshotter.getState() != Thread.State.BLOCKED
          && System.nanoTime() < deadline) Thread.sleep(5);
      if (snapshotFinished.getCount() == 0) {
        assertEquals(null, threadFailure.get(), "snapshot thread must not fail");
        assertEquals(List.of(new IngestCollectionPolicy.RootBinding(root, "labelled")), observed.get(),
            "snapshot must never publish membership without its collection");
      }
      assertEquals(Thread.State.BLOCKED, snapshotter.getState());
    } finally {
      releaseRegistration.countDown();
      if (registrar.getState() != Thread.State.NEW) registrar.join(10_000);
      if (snapshotter.getState() != Thread.State.NEW) snapshotter.join(10_000);
    }

    assertFalse(registrar.isAlive(), "registration thread must be released");
    assertFalse(snapshotter.isAlive(), "snapshot thread must be released");
    assertEquals(null, threadFailure.get(), "worker threads must complete without failure");
    assertEquals(
        List.of(new IngestCollectionPolicy.RootBinding(root, "labelled")), observed.get());
  }

  @Test
  @DisplayName("preparation is pure and freezes nested labels, excludes, and directory metadata")
  void prepareSnapshotsPurelyAndPreservesDistinctNestedPolicy() throws Exception {
    Path parent = Files.createDirectories(tempDir.resolve("library")).toAbsolutePath().normalize();
    Path child = Files.createDirectories(parent.resolve("private")).toAbsolutePath().normalize();
    Path sibling = Files.createDirectories(tempDir.resolve("library-other")).toAbsolutePath().normalize();
    Path rootsFile = tempDir.resolve("prepared-roots.json");
    AtomicInteger excludeCalls = new AtomicInteger();
    Fixture fixture =
        new Fixture(
            () -> {
              excludeCalls.incrementAndGet();
              return ExcludeMatcher.fromPatterns(List.of("*.tmp"), false);
            },
            null,
            rootsFile);

    fixture.state.register(parent, "notes", false);
    fixture.state.register(child, "secret", false);
    fixture.state.register(sibling, "  ", false);

    RecordedRootPlan plan = fixture.ops.prepareReindexPlan("generation-1", true);

    assertEquals(1, excludeCalls.get(), "the exclusion source is captured once");
    assertEquals(3, plan.roots().size(), "distinct nested policies remain separate roots");
    RecordedRootPlan.Root plannedParent = rootFor(plan, parent);
    RecordedRootPlan.Root plannedChild = rootFor(plan, child);
    RecordedRootPlan.Root plannedSibling = rootFor(plan, sibling);
    assertEquals("notes", plannedParent.collection());
    assertEquals("secret", plannedChild.collection());
    assertEquals(IngestCollectionPolicy.DEFAULT_COLLECTION, plannedSibling.collection());
    assertEquals(List.of(child), plannedParent.excludedSubtrees());
    assertEquals(List.of("**/*.tmp"), plannedParent.excludePatterns());
    assertEquals(List.of("**/*.tmp"), plannedChild.excludePatterns());
    assertTrue(plannedParent.force());
    assertFalse(plannedParent.singleFile(), "watched roots are directory units");
    assertFalse(plannedChild.singleFile(), "watched roots are directory units");

    // Scope is frozen in the immutable plan even when live state changes after capture.
    fixture.state.register(child, "changed-after-capture", false);
    Path late = Files.createDirectories(tempDir.resolve("late")).toAbsolutePath().normalize();
    fixture.state.register(late, "late", false);
    assertEquals("secret", rootFor(plan, child).collection());
    assertEquals(3, plan.roots().size());
    assertThrows(
        UnsupportedOperationException.class,
        () -> plan.roots().get(0).excludePatterns().add("late/**"));

    assertFalse(Files.exists(rootsFile), "pure preparation must not persist root state");
    assertEquals(0, fixture.submittedWalks.get());
    assertEquals(0, fixture.watchCalls.get());
    assertEquals(0, fixture.scanCalls.get());
    assertEquals(0, fixture.deleteCalls.get());
    verify(fixture.walkExecutor, never()).execute(any(Runnable.class));
  }

  @Test
  @DisplayName("null and explicit default labels collapse before partitioning")
  void nullAndDefaultCollectionsHaveOneEffectivePolicy() throws Exception {
    Path parent = Files.createDirectories(tempDir.resolve("shared")).toAbsolutePath().normalize();
    Path child = Files.createDirectories(parent.resolve("child")).toAbsolutePath().normalize();
    Fixture fixture = new Fixture(ExcludeMatcher::empty, null);
    fixture.state.register(parent, null, false);
    fixture.state.register(child, IngestCollectionPolicy.DEFAULT_COLLECTION, false);

    RecordedRootPlan plan = fixture.ops.prepareReindexPlan("generation-2", false);

    assertEquals(1, plan.roots().size(), "null and default must be normalized before partitioning");
    assertEquals(parent, plan.roots().get(0).path());
    assertEquals(IngestCollectionPolicy.DEFAULT_COLLECTION, plan.roots().get(0).collection());
    assertEquals(List.of(), plan.roots().get(0).excludedSubtrees());
  }

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

    assertEquals(1, fixture.state.snapshotBindings().size());
    assertEquals("same-label", fixture.state.snapshotBindings().get(0).collection());
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

    assertTrue(fixture.state.snapshotBindings().isEmpty());
    assertFalse(fixture.watchedRoots.containsKey(root));
  }

  private static RecordedRootPlan.Root rootFor(RecordedRootPlan plan, Path path) {
    return plan.roots().stream()
        .filter(root -> root.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing planned root " + path));
  }

  private static final class Fixture {
    private final Map<Path, Instant> watchedRoots = new java.util.concurrent.ConcurrentHashMap<>();
    private final WatchedRootsState state;
    private final ExecutorService walkExecutor;
    private final RootLifecycleOps ops;
    private final AtomicInteger submittedWalks = new AtomicInteger();
    private final AtomicInteger watchCalls = new AtomicInteger();
    private final AtomicInteger scanCalls = new AtomicInteger();
    private final AtomicInteger deleteCalls = new AtomicInteger();
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
                scanCalls.incrementAndGet();
                return null;
              }
              : (rootPath, collection, mode, globs, progress, context) -> {
                scanCalls.incrementAndGet();
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
                deleteCalls.incrementAndGet();
                return DeleteByPathResponse.newBuilder().build();
              },
              (id, context) -> DeleteByIdResponse.newBuilder().setSuccess(true).build(),
              mock(SyncOps.class),
              walkExecutor,
              (body, context) -> {
                submittedWalks.incrementAndGet();
                bindingsAtSubmission.set(state.snapshotBindings());
                queuedWalk.set(body);
              });
    }
  }

  /** Pauses the first membership put after it is visible but before register can publish its label. */
  private static final class PausingMap extends java.util.concurrent.ConcurrentHashMap<Path, Instant> {
    private static final long serialVersionUID = 1L;
    private final CountDownLatch membershipInserted;
    private final CountDownLatch releaseRegistration;
    private final java.util.concurrent.atomic.AtomicBoolean pause =
        new java.util.concurrent.atomic.AtomicBoolean(true);

    private PausingMap(CountDownLatch membershipInserted, CountDownLatch releaseRegistration) {
      this.membershipInserted = membershipInserted;
      this.releaseRegistration = releaseRegistration;
    }

    @Override
    public Instant put(Path key, Instant value) {
      Instant prior = super.put(key, value);
      if (pause.compareAndSet(true, false)) {
        membershipInserted.countDown();
        try {
          if (!releaseRegistration.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("registration was not released");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError("registration was interrupted", e);
        }
      }
      return prior;
    }
  }
}
