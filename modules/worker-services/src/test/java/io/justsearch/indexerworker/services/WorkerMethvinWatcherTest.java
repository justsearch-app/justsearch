package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRejectedException.Reason;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 418 Phase B — verifies the Worker-side Methvin watcher delivers real filesystem
 * change events into the {@link JobQueue}. Uses a real {@code DirectoryWatcher} against a temp
 * directory; events are async, so the test polls for arrival rather than asserting immediately.
 */
final class WorkerMethvinWatcherTest {

  /**
   * Slice G.2 (M8) — recording delete sink shared across all test cases. Create/modify-focused
   * cases assert {@code sink.observed.isEmpty()} so a spurious DELETE from Methvin's debouncer
   * doesn't disappear silently the way it would with a true no-op sink.
   */
  private static final class RecordingDeleteSink implements Consumer<String> {
    final CopyOnWriteArrayList<String> observed = new CopyOnWriteArrayList<>();

    @Override
    public void accept(String path) {
      observed.add(path);
    }
  }

  @TempDir Path tempDir;

  @Test
  @Timeout(15)
  void deliversCreateEventToJobQueue() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("watched"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      assertTrue(watcher.registerRoot(root, "docs"), "registerRoot must report success");

      // Methvin uses LAST_MODIFIED_TIME hashing — emit a brief delay so the watcher's initial
      // snapshot completes before the create lands, and the create produces a CREATE event.
      Thread.sleep(500);
      Path created = Files.writeString(root.resolve("hello.txt"), "world");

      Path observed = pollForEnqueued(queue, created, 10_000);
      assertEquals(created, observed, "Worker watcher must enqueue created file path");
      assertEquals("docs", queue.lastCollection, "Collection tag must propagate to enqueue");
      assertTrue(sink.observed.isEmpty(),
          "Create-only path must not produce DELETE sink calls; observed: " + sink.observed);
    }
  }

  /**
   * 813 Slice B call-site pin: the watcher is one of the four producers of queue rows, and the
   * remaining-work byte weight is only as good as what each producer records. A watcher enqueue
   * that fell back to the unsized path would be invisible in the aggregate — the sum would simply
   * understate the backlog — so the size is asserted at the call site, not just in the queue.
   *
   * <p>Tempdoc 912 item 1: the file is written OUTSIDE the watched root and moved in, so it is
   * complete at the instant it becomes visible to the watcher. The previous fixture wrote it in
   * place, which let the event-thread stat race the writer and observe 0 bytes under load (the
   * flake this fixture removes); the racing case is now pinned deterministically by {@link
   * #liveEventOnAStillEmptyFileRecordsUnknownSizeNotAKnownZero()}.
   */
  @Test
  @Timeout(15)
  void createEventCarriesTheFilesRealSizeToTheQueue() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("sized"));
    Path staging = Files.createDirectory(tempDir.resolve("sized-staging"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      assertTrue(watcher.registerRoot(root, "docs"), "registerRoot must report success");

      Thread.sleep(500);
      Path staged = Files.writeString(staging.resolve("sized.txt"), "x".repeat(4_096));
      Path created = Files.move(staged, root.resolve("sized.txt"));

      pollForEnqueued(queue, created, 10_000);
      JobQueue.EnqueueEntry entry =
          queue.enqueuedEntries.stream()
              .filter(e -> e.path().equals(created))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "The watcher must enqueue the SIZED form; entries: "
                              + new ArrayList<>(queue.enqueuedEntries)));
      assertEquals(
          Files.size(created),
          entry.sizeBytes(),
          "The watcher must record the file's real size, not the unknown-size sentinel");
      assertTrue(
          queue.enqueuedEntries.stream()
              .filter(e -> e.path().equals(created))
              .noneMatch(e -> e.sizeBytes() == 0L),
          "No event for the path may record a KNOWN size of zero; entries: "
              + new ArrayList<>(queue.enqueuedEntries));
    }
  }

  /**
   * Tempdoc 912 item 1 — the race the old code recorded as fact. A live CREATE notification can
   * reach the event thread before the writer has flushed a byte, so the stat returns 0 for a file
   * that is about to be large. Recording that as a KNOWN zero sums it into {@code
   * PendingBytes.knownBytes} as "0 bytes of work" instead of counting it in {@code
   * unknownSizeJobs}, silently understating the backlog with nothing marking the estimate
   * incomplete — the exact tri-state 813 Slice B built the two fields to preserve.
   *
   * <p>Fed as a synthetic event so the assertion does not depend on OS scheduling: the file exists
   * and is empty at event time, which is precisely the state the racing watcher observes. On the
   * pre-fix code path ({@code EnqueueEntry.stat} straight through) this records {@code 0}.
   */
  @Test
  void liveEventOnAStillEmptyFileRecordsUnknownSizeNotAKnownZero() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("racing"));
    Path stillEmpty = Files.createFile(root.resolve("growing.bin"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      watcher.handleUpsert(root, "docs", stillEmpty);

      assertEquals(1, queue.enqueuedEntries.size(), "The event must produce exactly one entry");
      JobQueue.EnqueueEntry entry = queue.enqueuedEntries.get(0);
      assertEquals(stillEmpty, entry.path(), "The entry must carry the event's path");
      assertEquals(
          JobQueue.UNKNOWN_SIZE_BYTES,
          entry.sizeBytes(),
          "A zero-byte stat on a live event is 'looked too early', not a known size of zero");
      assertEquals("docs", queue.lastCollection, "Collection tag must still propagate");
    }
  }

  /**
   * Tempdoc 912 item 1 — the fix must not over-reach: a file that already has content at event
   * time still records its real size, so the unknown-size mapping is confined to the zero case.
   */
  @Test
  void liveEventOnAWrittenFileRecordsItsRealSize() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("settled"));
    Path written = Files.writeString(root.resolve("settled.txt"), "y".repeat(2_048));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      watcher.handleUpsert(root, "docs", written);

      assertEquals(1, queue.enqueuedEntries.size(), "The event must produce exactly one entry");
      assertEquals(
          2_048L,
          queue.enqueuedEntries.get(0).sizeBytes(),
          "A settled file's real size must survive the zero-is-unknown mapping");
    }
  }

  @Test
  @Timeout(15)
  void unregisterRootStopsEventDelivery() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("transient"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      watcher.registerRoot(root, null);
      Thread.sleep(500);
      assertTrue(watcher.unregisterRoot(root), "unregisterRoot must report success");
      Files.writeString(root.resolve("after-unregister.txt"), "should-not-fire");
      Thread.sleep(2_000);
      assertFalse(
          queue.enqueuedPaths.stream().anyMatch(p -> p.getFileName().toString().equals("after-unregister.txt")),
          "Events after unregisterRoot must not reach the queue");
      assertTrue(sink.observed.isEmpty(),
          "Unregister-then-mutate must not fire any sink calls; observed: " + sink.observed);
    }
  }

  @Test
  @Timeout(15)
  void deliversDeleteEventToSink() throws Exception {
    // B-H.4 defect G — DELETE events must propagate to the deletePathSink so the parent doc +
    // chunks are removed in one Worker-side write. Pre-fix the case was a no-op.
    Path root = Files.createDirectory(tempDir.resolve("deletes"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      assertTrue(watcher.registerRoot(root, null), "registerRoot must report success");
      Thread.sleep(500);
      Path doomed = Files.writeString(root.resolve("doomed.txt"), "soon-to-die");
      // Wait for the CREATE to flow before deleting; otherwise the watcher's hash-based diff
      // can collapse create+delete into a single observed state.
      pollForEnqueued(queue, doomed, 10_000);
      Files.delete(doomed);

      String observed = pollForDeletedPath(sink.observed, doomed, 10_000);
      assertNotNull(observed, "Delete sink must receive the deleted path");
      assertTrue(
          observed.endsWith("doomed.txt"),
          "Sink path must point at the deleted file (got " + observed + ")");
    }
  }

  /** Records (root, force) reconcile invocations for the Phase-2 overflow/burst recovery tests. */
  private static final class RecordingReconcileSink
      implements java.util.function.BiConsumer<Path, Boolean> {
    final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void accept(Path root, Boolean force) {
      calls.add(root + "|force=" + force);
    }
  }

  @Test
  @Timeout(10)
  void overflowSchedulesForcedReconcile() throws Exception {
    // Tempdoc 626 §Axis-A — OVERFLOW (events dropped by the OS) must trigger an immediate forced
    // reconcile so the index re-converges. Before this the Worker watcher only logged the overflow.
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink delete = new RecordingDeleteSink();
    RecordingReconcileSink reconcile = new RecordingReconcileSink();
    Path root = tempDir;
    try (WorkerMethvinWatcher watcher =
        new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, delete, reconcile)) {
      watcher.handleOverflow(root, root.resolve("anything"));
      String expected = root + "|force=true";
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline && !reconcile.calls.contains(expected)) {
        Thread.sleep(50);
      }
      assertTrue(
          reconcile.calls.contains(expected),
          "OVERFLOW must schedule a forced reconcile; observed: " + reconcile.calls);
    }
  }

  @Test
  void deleteForChildIsForwardedWhenRootExists() {
    // Tempdoc 626 §I.3-A — the guard must NOT block normal deletions: when the watched root is
    // present, a child DELETE flows through to the sink as before.
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    Path root = tempDir; // exists
    Path child = root.resolve("doc.txt");
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      watcher.handleDelete(root, child);
      assertEquals(1, sink.observed.size(), "Delete under an existing root must reach the sink");
      assertTrue(sink.observed.get(0).endsWith("doc.txt"));
    }
  }

  @Test
  void deleteCascadeIsSkippedWhenWatchedRootIsGone() throws Exception {
    // Tempdoc 626 §I.3-A (the data-loss regression) — when a watched root goes unavailable
    // (unmount / UNC disconnect / drive unplug), the OS fires a cascade of child-DELETE events.
    // Forwarding them would silently wipe the folder's index. The Worker DELETE path must skip the
    // delete while the root is missing (mirroring the Head-side WatcherEventOps.handleDelete guard),
    // leaving reconciliation to a later sync once the root returns.
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    Path missingRoot = Files.createDirectory(tempDir.resolve("removable"));
    Path child = missingRoot.resolve("photo.jpg");
    Files.delete(missingRoot); // simulate the root vanishing (unmount/unplug)
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      watcher.handleDelete(missingRoot, child);
      assertTrue(
          sink.observed.isEmpty(),
          "Delete must be SKIPPED while the watched root is gone; observed: " + sink.observed);
    }
  }

  private static String pollForDeletedPath(
      CopyOnWriteArrayList<String> sink, Path expected, long timeoutMs) throws InterruptedException {
    String tail = expected.getFileName().toString();
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      for (String s : sink) {
        if (s != null && s.endsWith(tail)) return s;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Expected delete sink to receive a path ending with "
            + tail
            + " within "
            + timeoutMs
            + "ms; observed: "
            + new ArrayList<>(sink));
  }

  @Test
  void registerThenReregisterReplacesPriorSubscription() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("idempotent"));
    RecordingQueue queue = new RecordingQueue();
    RecordingDeleteSink sink = new RecordingDeleteSink();
    try (WorkerMethvinWatcher watcher = new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), queue, null, sink)) {
      assertTrue(watcher.registerRoot(root, "v1"));
      assertTrue(watcher.registerRoot(root, "v2"), "Re-registration must succeed (idempotent)");
      assertTrue(watcher.unregisterRoot(root));
      assertFalse(watcher.unregisterRoot(root), "Second unregister returns false");
      assertTrue(sink.observed.isEmpty(),
          "Idempotent register-cycle must not fire spurious deletes; observed: " + sink.observed);
    }
  }

  @Test
  @Timeout(10)
  void capacityRefusalRetainsOverflowUntilExecutorCanAcceptIt() throws Exception {
    RefuseThirdRegistration registration = new RefuseThirdRegistration();
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch reconciled = new CountDownLatch(3);
    List<Path> observed = new CopyOnWriteArrayList<>();
    Path first = tempDir.resolve("first");
    Path second = tempDir.resolve("second");
    Path refused = tempDir.resolve("refused");

    try (WorkerMethvinWatcher watcher =
        new WorkerMethvinWatcher(
            registration,
            new RecordingQueue(),
            null,
            new RecordingDeleteSink(),
            (root, force) -> {
              observed.add(root);
              if (root.equals(first)) {
                firstEntered.countDown();
                try {
                  assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(e);
                }
              }
              reconciled.countDown();
            })) {
      watcher.handleOverflow(first, first);
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
      watcher.handleOverflow(second, second);
      watcher.handleOverflow(refused, refused);
      releaseFirst.countDown();

      assertTrue(reconciled.await(5, TimeUnit.SECONDS));
      assertTrue(observed.containsAll(List.of(first, second, refused)));
      assertEquals(4, registration.submissionCount(), "the refused overflow must be retried");
    }
  }

  private static final class RefuseThirdRegistration
      implements EngineExecutorRegistry.Registration {
    private static final EngineExecutorSpec SPEC =
        new EngineExecutorSpec("test-watcher-cap", Kind.BACKGROUND, Mode.SCHEDULED, 1, 1, 1);

    private final AtomicInteger submissions = new AtomicInteger();
    private ScheduledThreadPoolExecutor executor;

    @Override
    public EngineExecutorSpec spec() {
      return SPEC;
    }

    @Override
    public ExecutorService open(ThreadFactory threadFactory) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledExecutorService openScheduled(ThreadFactory threadFactory) {
      executor =
          new ScheduledThreadPoolExecutor(1, threadFactory) {
            @Override
            public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
              if (submissions.incrementAndGet() == 3) {
                throw new EngineExecutorRejectedException(Reason.QUEUE_LIMIT, SPEC.name(), 1);
              }
              return super.schedule(task, delay, unit);
            }
          };
      return executor;
    }

    int submissionCount() {
      return submissions.get();
    }

    @Override
    public ExecutorService openVirtual() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      if (executor != null) {
        executor.shutdownNow();
      }
    }
  }

  private static Path pollForEnqueued(RecordingQueue queue, Path expected, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      for (Path p : queue.enqueuedPaths) {
        if (p.equals(expected)) return p;
      }
      Thread.sleep(100);
    }
    throw new AssertionError(
        "Expected enqueue of "
            + expected
            + " within "
            + timeoutMs
            + "ms; observed: "
            + new ArrayList<>(queue.enqueuedPaths));
  }

  private static final class RecordingQueue implements JobQueue {
    final CopyOnWriteArrayList<Path> enqueuedPaths = new CopyOnWriteArrayList<>();
    /** 813 Slice B — the sized form, so a call site's SIZE is observable, not just its path. */
    final CopyOnWriteArrayList<EnqueueEntry> enqueuedEntries = new CopyOnWriteArrayList<>();

    volatile String lastCollection;

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      enqueuedPaths.addAll(paths);
      lastCollection = collection;
      return paths.size();
    }

    @Override
    public int enqueueEntries(List<EnqueueEntry> entries, String collection) {
      enqueuedEntries.addAll(entries);
      return enqueue(EnqueueEntry.paths(entries), collection);
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {}

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public void recordIngestionEvent(
        Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {}

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long queueDepth() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }
}
