package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;

import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 626 Goal 4 / §I.2 — the consolidated standing guard over the Worker-watcher transition
 * matrix. One table-driven test that pins the deterministic file-event cells so a future watcher change
 * cannot silently reintroduce a drift class (the per-cell behaviours also have focused tests; this is the
 * matrix expressed as one executable table). The reconcile verified/unverified cell and the FE folder
 * cells are guarded by {@code SyncOpsReconcileVerificationTest}, {@code IndexDriftHealthTapTest}, and
 * {@code folderStatus.test} in their own modules.
 */
final class WatcherTransitionMatrixGuardTest {

  @TempDir Path tempDir;

  private static final class RecordingDeleteSink implements Consumer<String> {
    final CopyOnWriteArrayList<String> deleted = new CopyOnWriteArrayList<>();

    @Override
    public void accept(String p) {
      deleted.add(p);
    }
  }

  private static final class RecordingReconcile implements BiConsumer<Path, Boolean> {
    final CopyOnWriteArrayList<String> calls = new CopyOnWriteArrayList<>();

    @Override
    public void accept(Path root, Boolean force) {
      calls.add(root + "|force=" + force);
    }
  }

  @Test
  @Timeout(10)
  void watcherTransitionMatrixHoldsForAllDeterministicCells() throws Exception {
    RecordingDeleteSink delete = new RecordingDeleteSink();
    RecordingReconcile reconcile = new RecordingReconcile();
    Path presentRoot = Files.createDirectory(tempDir.resolve("present"));
    Path child = presentRoot.resolve("doc.txt");

    // A root that vanishes (unmount/unplug): the OS would fire a child-DELETE cascade.
    Path goneRoot = Files.createDirectory(tempDir.resolve("removable"));
    Path goneChild = goneRoot.resolve("photo.jpg");
    Files.delete(goneRoot);

    try (WorkerMethvinWatcher watcher =
        new WorkerMethvinWatcher(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.watcher(), mock(JobQueue.class), null, delete, reconcile)) {

      // Cell: DELETE while watched root is PRESENT → forwarded to the delete sink.
      watcher.handleDelete(presentRoot, child);
      assertEquals(1, delete.deleted.size(), "delete under a present root must forward");
      assertTrue(delete.deleted.get(0).endsWith("doc.txt"));

      // Cell: DELETE cascade while watched root is GONE (unmount) → SKIPPED (599 data-loss guard).
      watcher.handleDelete(goneRoot, goneChild);
      assertEquals(
          1, delete.deleted.size(), "delete under a gone root must be skipped (no new sink call)");

      // Cell: OVERFLOW (events dropped) → forced reconcile of the root.
      watcher.handleOverflow(presentRoot, child);
      String expected = presentRoot + "|force=true";
      long deadline = System.currentTimeMillis() + 5_000;
      while (System.currentTimeMillis() < deadline && !reconcile.calls.contains(expected)) {
        Thread.sleep(50);
      }
      assertTrue(
          reconcile.calls.contains(expected),
          "overflow must schedule a forced reconcile; observed: " + reconcile.calls);
    }

    // Cell: burst threshold crossing → trips exactly once per second (the missed-event net).
    WorkerBurstDetector burst = new WorkerBurstDetector();
    Path root = Path.of("/r");
    for (int i = 0; i < 3; i++) {
      assertEquals(false, burst.recordEvent(root, 3), "below/at threshold must not trip");
    }
    assertTrue(burst.recordEvent(root, 3), "crossing the burst threshold must trip");
    assertEquals(false, burst.recordEvent(root, 3), "must not re-trip within the same second");
  }
}
