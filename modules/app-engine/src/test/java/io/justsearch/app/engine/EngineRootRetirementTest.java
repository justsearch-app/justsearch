/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.worker.CancelToken;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.RootWatcherRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Engine tasks, Worker disk walks and SQLite admissions; only the Lucene runtime is mocked. */
@Timeout(30)
final class EngineRootRetirementTest {
  private static final String KEY = "retirement-walk";

  @Test
  void queuedWatcherCannotReplaceReaddedRoot(@TempDir Path directory) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture = fixture(directory, 1, IndexingPacing.unthrottled());
        var watchRegistry = boundedRegistry();
        var watchClient = client(watchRegistry, fixture.services(), fixture.roots(), 5_000)) {
      var field = EngineKnowledgeClient.class.getDeclaredField("foregroundCallThreads");
      field.setAccessible(true);
      var executor = (java.util.concurrent.ThreadPoolExecutor) field.get(watchClient);
      executor.execute(() -> {
        entered.countDown();
        await(release);
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var old = new FutureTask<>(() -> watchClient.watchRoot(fixture.root().toString(), "A", TestEngineContexts.FOREGROUND));
      var callerThread = new Thread(old);
      try {
        callerThread.start();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.getQueue().isEmpty() && !old.isDone() && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(1, executor.getQueue().size(), "the old Engine watch call must really be queued");
        assertEquals(0, fixture.client().removeWatchedPath(fixture.root(), TestEngineContexts.FOREGROUND));
        fixture.client().addWatchedRoot("B", fixture.root(), TestEngineContexts.FOREGROUND);
        release.countDown();
        assertFalse(old.get(5, TimeUnit.SECONDS).getWatching(), "the queued old incarnation must not register");
        var scans = org.mockito.ArgumentCaptor.forClass(io.justsearch.ipc.WatchRootRequest.class);
        verify(fixture.service()).watchRoot(scans.capture(), any());
        assertEquals("B", scans.getValue().getCollection());
        assertEquals("B", fixture.roots().snapshotBindings().getFirst().collection());
      } finally {
        release.countDown();
        callerThread.join(5_000);
      }
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"direct", "add", "restored"})
  void watcherDeadlineCannotReleaseRemovalBeforeRegistryEntry(String entry, @TempDir Path directory)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var watchContext = new java.util.concurrent.atomic.AtomicReference<io.justsearch.indexerworker.services.CallContext>();
    try (var fixture = fixture(directory, 1, IndexingPacing.unthrottled(), false, entry.equals("add"));
        var watchRegistry = new DefaultEngineExecutorRegistry();
        var watchClient = client(watchRegistry, fixture.services(), fixture.roots(), 500)) {
      var registry = new RootWatcherRegistry();
      fixture.service().setRootWatcherRegistry(registry);
      var registrations = new AtomicInteger();
      doAnswer(call -> {
        watchContext.set(call.getArgument(1));
        entered.countDown();
        await(release);
        var result = (io.justsearch.ipc.WatchRootResponse) call.callRealMethod();
        assertTrue(result.getWatching(), "the delayed worker must actually register the real watcher");
        registrations.incrementAndGet();
        return result;
      }).when(fixture.service()).watchRoot(any(), any());
      var caller = new FutureTask<Void>(() -> {
        if (entry.equals("direct")) watchClient.watchRoot(fixture.root().toString(), "documents", TestEngineContexts.FOREGROUND);
        else if (entry.equals("add")) watchClient.addWatchedRoot("documents", fixture.root(), TestEngineContexts.FOREGROUND);
        else watchClient.reindexPersistedRoots(TestEngineContexts.FOREGROUND);
        return null;
      });
      var callerThread = new Thread(caller);
      var removal = removal(fixture);
      var removalThread = new Thread(removal);
      try {
        callerThread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the real Engine worker must reach the watch service");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!watchContext.get().cancelled() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(watchContext.get().cancelled(), "expire the actual unary caller before registry entry");
        if (entry.equals("direct")) {
          var expired = assertThrows(java.util.concurrent.ExecutionException.class, () -> caller.get(5, TimeUnit.SECONDS));
          assertEquals(io.justsearch.app.api.knowledge.KnowledgeClientException.Status.DEADLINE_EXCEEDED,
              ((io.justsearch.app.api.knowledge.KnowledgeClientException) expired.getCause()).status());
        } else if (entry.equals("restored")) caller.get(5, TimeUnit.SECONDS);
        removalThread.start();
        assertBlocked(removalThread, removal);
        assertEquals(0, registrations.get(), "unwatch cannot be mistaken for draining a not-yet-registered watcher");
        release.countDown();
        assertTrue(removal.get(5, TimeUnit.SECONDS) >= 0);
        if (!entry.equals("direct")) caller.get(5, TimeUnit.SECONDS);
        assertEquals(1, registrations.get(), "the timed-out worker really resumes registry creation");
        var rootsMethod = RootWatcherRegistry.class.getDeclaredMethod("watchedRoots");
        rootsMethod.setAccessible(true);
        assertEquals(Set.of(), rootsMethod.invoke(registry), "successful removal must retire the delayed registration");
        assertRemoved(fixture);
      } finally {
        release.countDown();
        callerThread.join(5_000);
        removalThread.join(5_000);
      }
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reconciliationDeadlineDoesNotReleaseActualProducerFence(boolean force, @TempDir Path directory)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var admissions = new AtomicInteger();
    var pacing = mock(IndexingPacing.class);
    doAnswer(call -> {
      entered.countDown();
      await(release);
      return null;
    }).when(pacing).pace();
    try (var fixture = fixture(directory, 110, pacing);
        var syncRegistry = new DefaultEngineExecutorRegistry();
        var syncClient = client(syncRegistry, fixture.services(), fixture.roots(), 10)) {
      doAnswer(call -> {
        if (force) {
          entered.countDown();
          await(release);
        }
        admissions.incrementAndGet();
        return call.callRealMethod();
      }).when(fixture.queue()).enqueueEntries(anyList());
      var caller = new FutureTask<>(() ->
          syncClient.syncDirectory(fixture.root().toString(), force, TestEngineContexts.FOREGROUND));
      var callerThread = new Thread(caller);
      var removal = removal(fixture);
      var removalThread = new Thread(removal);
      try {
        callerThread.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "pause inside the actual reconciliation worker");
        assertNull(caller.get(5, TimeUnit.SECONDS), "the unary deadline must release the caller first");
        assertEquals(0, fixture.queue().queueDepth());
        removalThread.start();
        assertBlocked(removalThread, removal);
        assertFalse(fixture.roots().watchedPaths().isEmpty());
        release.countDown();
        // S6 (ee0f0806b) checks cancellation before draining the reconciliation spool. The
        // paced walk admits nothing after expiry; force mode already entered queue admission.
        assertEquals(force ? 110 : 0, removal.get(5, TimeUnit.SECONDS).intValue());
        assertEquals(force ? 1 : 0, admissions.get(),
            "only a queue admission already entered before expiry may complete");
        assertRemoved(fixture);
      } finally {
        release.countDown();
        callerThread.join(5_000);
        removalThread.join(5_000);
      }
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void removalDrainsRealRecordedReindexBetweenBatches(boolean nested, @TempDir Path directory) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture = fixture(directory, 2_001, IndexingPacing.unthrottled(), nested)) {
      var batches = new AtomicInteger();
      doAnswer(call -> {
        if (batches.incrementAndGet() == 2) {
          entered.countDown();
          await(release);
        }
        return call.callRealMethod();
      }).when(fixture.queue()).enqueueRecordedEntries(anyString(), anyLong(), anyList(), any());
      long epoch = epoch(fixture, KEY);
      var producer = fixture.client().enumerateWatchedRecordedRoot(fixture.plan(), KEY, epoch,
          TestEngineContexts.FOREGROUND, new CancelToken());
      var removal = new FutureTask<>(() -> fixture.client().removeWatchedPath(
          nested ? fixture.root().getParent() : fixture.root(), TestEngineContexts.FOREGROUND));
      var removalThread = new Thread(removal);
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(2_000, fixture.queue().queueDepth(), "the first recorded batch has been admitted");
        removalThread.start();
        assertBlocked(removalThread, removal);
        release.countDown();
        assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE,
            producer.toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertEquals(2_001, removal.get(5, TimeUnit.SECONDS).intValue());
        assertEquals(2, batches.get());
        assertRemoved(fixture);
      } finally {
        release.countDown();
        removalThread.join(5_000);
      }
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"watched", "ingest", "captured"})
  void queuedRecordedProducersCannotAdmitAfterRemoval(String mode, @TempDir Path directory)
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var fixture = fixture(directory, 1, IndexingPacing.unthrottled())) {
      var field = KnowledgeClient.class.getDeclaredField("walkExecutor");
      field.setAccessible(true);
      var executor = (ExecutorService) field.get(fixture.client());
      executor.execute(() -> {
        entered.countDown();
        await(release);
      });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      long epoch = mode.equals("captured")
          ? fixture.queue().beginCapturedWalk(KEY, "b".repeat(64), true).enumerationEpoch()
          : epoch(fixture, KEY);
      CompletionStage<JobQueue.WalkEnumerationOutcome> queued = switch (mode) {
        case "watched" -> fixture.client().enumerateWatchedRecordedRoot(fixture.plan(), KEY, epoch,
            TestEngineContexts.FOREGROUND, new CancelToken());
        case "ingest" -> fixture.client().enumerateRecordedRoot(fixture.plan(), KEY, epoch,
            TestEngineContexts.FOREGROUND, new CancelToken());
        case "captured" -> fixture.client().enumerateWatchedCapturedRoots(fixture.plan(), KEY, epoch,
            TestEngineContexts.FOREGROUND, new CancelToken());
        default -> throw new AssertionError(mode);
      };
      assertFalse(queued.toCompletableFuture().isDone());
      assertEquals(0, fixture.client().removeWatchedPath(fixture.root(), TestEngineContexts.FOREGROUND));
      release.countDown();
      assertEquals(JobQueue.WalkEnumerationOutcome.CANCELLED,
          queued.toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertRemoved(fixture);
      // A previously frozen watched plan can reach the adapter only after removal has completed.
      assertEquals(JobQueue.WalkEnumerationOutcome.CANCELLED,
          fixture.client().enumerateWatchedRecordedRoot(fixture.plan(), KEY, epoch,
              TestEngineContexts.FOREGROUND, new CancelToken()).toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertRemoved(fixture);
      assertEquals(JobQueue.WalkEnumerationOutcome.CANCELLED,
          fixture.client().enumerateWatchedCapturedRoots(fixture.plan(), KEY, epoch,
              TestEngineContexts.FOREGROUND, new CancelToken()).toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertRemoved(fixture);
      // A new explicit ingestion remains authorized to target an unwatched path.
      String explicitKey = KEY + "-explicit";
      long explicitEpoch = epoch(fixture, explicitKey);
      assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE,
          fixture.client().enumerateRecordedRoot(fixture.plan(), explicitKey, explicitEpoch,
              TestEngineContexts.FOREGROUND, new CancelToken()).toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertEquals(1, fixture.queue().queueDepth());
    } finally {
      release.countDown();
    }
  }

  private static FutureTask<Integer> removal(Fixture fixture) {
    return new FutureTask<>(() -> fixture.client().removeWatchedPath(fixture.root(), TestEngineContexts.FOREGROUND));
  }

  private static void assertBlocked(Thread thread, FutureTask<?> removal) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.getState() != Thread.State.BLOCKED && !removal.isDone() && System.nanoTime() < until) {
      Thread.sleep(10);
    }
    assertEquals(Thread.State.BLOCKED, thread.getState(), "removal must wait for actual producer exit");
    assertFalse(removal.isDone(), "caller timeout is not safe producer retirement");
  }

  private static void assertRemoved(Fixture fixture) {
    assertEquals(0, fixture.queue().queueDepth(), "no resumed or stale producer may recreate jobs");
    assertTrue(fixture.roots().watchedPaths().isEmpty());
    assertTrue(WatchedRootsState.load(fixture.data()).watchedPaths().isEmpty());
    verify(fixture.runtime().indexingCoordinator()).deleteByPathPrefix(fixture.removalRoot().toString());
  }

  private static long epoch(Fixture fixture, String key) {
    return fixture.queue().beginRecordedWalk(key, "b".repeat(64), true).enumerationEpoch();
  }

  private static Fixture fixture(Path directory, int files, IndexingPacing pacing) throws Exception {
    return fixture(directory, files, pacing, false);
  }

  private static Fixture fixture(Path directory, int files, IndexingPacing pacing, boolean nested) throws Exception {
    return fixture(directory, files, pacing, nested, false);
  }

  private static Fixture fixture(Path directory, int files, IndexingPacing pacing, boolean nested, boolean emptyRoots) throws Exception {
    Path root = Files.createDirectories(nested ? directory.resolve("documents/nested") : directory.resolve("documents"));
    for (int i = 0; i < files; i++) Files.writeString(root.resolve("entry-" + i + ".txt"), "root retirement fixture");
    Path data = Files.createDirectory(directory.resolve("authority"));
    Files.writeString(data.resolve("watched_roots.json"),
        tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
            "schemaVersion", 1, "roots", emptyRoots ? List.of() : List.of(Map.of("path", root.toString())))));
    var roots = WatchedRootsState.load(data);
    var layout = new IndexGenerationManager(directory.resolve("index")).initializeOrLoad();
    var queue = spy(new SqliteJobQueue(layout.basePath().resolve("jobs.db"),
        ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE));
    queue.open();
    var runtime = mock(RunningRuntime.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(runtime.readPathOps()).thenReturn(null);
    when(runtime.pruneOps()).thenReturn(null);
    var service = spy(new WorkerIngestService(queue, null, null, pacing, layout.basePath(),
        layout.activeGenerationPath(), runtime, runtime, null, 0L));
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(service);
    var registry = boundedRegistry();
    var client = client(registry, services, roots, 5_000);
    var plan = new RecordedRootPlan(layout.activeGenerationId(), List.of(
        new RecordedRootPlan.Root(root, null, true, false, List.of(), List.of())));
    return new Fixture(root, data, roots, queue, services, registry, client, plan, service, runtime,
        nested ? root.getParent() : root);
  }

  private static DefaultEngineExecutorRegistry boundedRegistry() {
    return new DefaultEngineExecutorRegistry(new EngineResourcePolicy(Map.of(
        "perContextLimit", 8, "aggregateLimit", 8, "retryAfterSeconds", 1,
        "foregroundThreads", 1, "foregroundQueue", 4,
        "backgroundThreads", 1, "backgroundQueue", 4,
        "timerRegistrations", 16, "directMemoryMiB", 1),
        new io.justsearch.core.context.RetainedStateBudget()), Duration.ofMillis(100));
  }

  private static EngineKnowledgeClient client(DefaultEngineExecutorRegistry registry,
      WorkerAppServices services, WatchedRootsState roots, long deadlineMs) {
    return new EngineKnowledgeClient(registry, () -> services, new ForegroundLoadGate(new ForegroundLoad()),
        deadlineMs, 100, IpcTelemetry.noop(), () -> {}, new EngineAdmissionController(8, 8, 1), roots);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private record Fixture(Path root, Path data, WatchedRootsState roots, SqliteJobQueue queue,
      WorkerAppServices services, DefaultEngineExecutorRegistry registry, EngineKnowledgeClient client,
      RecordedRootPlan plan, WorkerIngestService service, RunningRuntime runtime, Path removalRoot) implements AutoCloseable {
    @Override
    public void close() throws Exception {
      try {
        client.close();
      } finally {
        try {
          registry.close();
        } finally {
          queue.close();
        }
      }
    }
  }
}
