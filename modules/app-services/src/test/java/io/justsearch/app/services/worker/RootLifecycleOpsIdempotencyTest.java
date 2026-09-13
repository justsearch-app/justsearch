package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.justsearch.ipc.ScanRootProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 608 — convergence-point idempotency. A re-add of an already-watched root is a no-op at {@link
 * RootLifecycleOps#addWatchedRoot}: without this guard, the duplicate-submit the UI used to invite (no
 * in-flight acknowledgement) would re-run {@code markNeverIndexed}, resetting a fully-indexed root's
 * timestamp and clearing its walk-completed flag, reverting a fully-indexed folder to "Scanning" and
 * re-queuing a redundant full walk. This guards EVERY caller (UI op + agent tool), including ones the FE
 * busy-overlay guard can't reach.
 */
@DisplayName("RootLifecycleOps add-watched-root idempotency (tempdoc 608)")
final class RootLifecycleOpsIdempotencyTest {

  @TempDir Path tempDir;

  /** Build a RootLifecycleOps whose only live deps are the watched-roots map/state + a counting walk
   * executor; everything addWatchedRoot does not touch is mocked. The walk executor is a mock so the
   * queued walk never actually runs (we only count submissions). */
  private RootLifecycleOps newOps(Map<Path, Instant> watchedRoots, WatchedRootsState state,
      ExecutorService walkExecutor) {
    return newOps(
        watchedRoots,
        state,
        walkExecutor,
        (rootPath, collection, mode, globs, progress, engineContext) -> null,
        (body, context) -> walkExecutor.execute(() -> body.accept(context)));
  }

  private RootLifecycleOps newOps(
      Map<Path, Instant> watchedRoots,
      WatchedRootsState state,
      ExecutorService walkExecutor,
      RootLifecycleOps.ScanRootFn scanRootFn,
      java.util.function.BiConsumer<
              java.util.function.Consumer<io.justsearch.core.context.EngineContext>,
              io.justsearch.core.context.EngineContext>
          submitWalk) {
    return new RootLifecycleOps(
        watchedRoots,
        state,
        ExcludeMatcher::empty,
        scanRootFn,
        new RootLifecycleOps.WorkerWatchFn() {
          @Override
          public void watch(String rootPath, String collection, io.justsearch.core.context.EngineContext engineContext) {}

          @Override
          public void unwatch(String rootPath, io.justsearch.core.context.EngineContext engineContext) {}
        },
        (p, engineContext) -> null, // deleteByPathFn
        (s, engineContext) -> null, // deleteByIdFn
        mock(SyncOps.class),
        walkExecutor,
        submitWalk);
  }

  @Test
  @DisplayName("a walk failure after admission is persisted as a completed failed walk")
  void walkFailureAfterAdmissionPersistsTerminalFailure() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("failed-after-admission"));
    Path normalized = root.toAbsolutePath().normalize();
    Path rootsFile = tempDir.resolve("failed-after-admission.json");
    String failureReason = "SCAN_ABORTED_AFTER_ADMISSION";

    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state = new WatchedRootsState(watchedRoots, new WatchedRootsStore(rootsFile, null));
    ExecutorService walkExecutor = mock(ExecutorService.class);
    RootLifecycleOps ops =
        newOps(
            watchedRoots,
            state,
            walkExecutor,
            (rootPath, collection, mode, globs, progress, engineContext) -> {
              progress.accept(ScanRootProgress.newBuilder().setFilesAdmitted(1).build());
              throw new RuntimeException(failureReason);
            },
            (body, context) -> body.accept(context));

    ops.addWatchedRoot("default", root, io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(state.isWalkCompleted(normalized));
    assertEquals(failureReason, state.getWalkError(normalized));
    assertEquals(WatchedRootsStore.NEVER_INDEXED, watchedRoots.get(normalized));

    Map<Path, Instant> reopenedRoots = new ConcurrentHashMap<>();
    WatchedRootsState reopened =
        new WatchedRootsState(reopenedRoots, new WatchedRootsStore(rootsFile, null));
    reopened.loadPersistedRoots();
    assertTrue(reopened.isWalkCompleted(normalized));
    assertEquals(failureReason, reopened.getWalkError(normalized));
    assertEquals(WatchedRootsStore.NEVER_INDEXED, reopenedRoots.get(normalized));
  }

  @Test
  @DisplayName("re-adding a walk-completed root is a no-op: walk queued once, state NOT reset")
  void reAddOfIndexedRootIsNoOp() throws Exception {
    Path root = tempDir.resolve("watched");
    Files.createDirectories(root);
    Path normalized = root.toAbsolutePath().normalize();

    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state =
        new WatchedRootsState(watchedRoots, new WatchedRootsStore(tempDir.resolve("roots.json"), null));
    ExecutorService walkExecutor = mock(ExecutorService.class);
    RootLifecycleOps ops = newOps(watchedRoots, state, walkExecutor);

    // First add registers the root and queues exactly one walk.
    ops.addWatchedRoot("default", root, io.justsearch.app.services.TestEngineContexts.internal());
    verify(walkExecutor, times(1)).execute(any());

    // Simulate the walk finishing with admitted files: timestamp set, walk-completed.
    state.markIndexed(normalized);
    Instant indexedAt = watchedRoots.get(normalized);
    assertTrue(state.isWalkCompleted(normalized));

    // Re-add the SAME root (the duplicate-submit) — must be a no-op.
    ops.addWatchedRoot("default", root, io.justsearch.app.services.TestEngineContexts.internal());

    // No second walk was queued, and the indexed state was NOT reset to NEVER_INDEXED.
    verify(walkExecutor, times(1)).execute(any());
    assertEquals(indexedAt, watchedRoots.get(normalized), "indexed timestamp must survive the re-add");
    assertTrue(state.isWalkCompleted(normalized), "walk-completed flag must survive the re-add");
  }

  @Test
  @DisplayName("a genuinely new root still proceeds (the guard only no-ops already-watched roots)")
  void newRootStillProceeds() throws Exception {
    Path a = tempDir.resolve("a");
    Path b = tempDir.resolve("b");
    Files.createDirectories(a);
    Files.createDirectories(b);

    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    WatchedRootsState state =
        new WatchedRootsState(watchedRoots, new WatchedRootsStore(tempDir.resolve("roots.json"), null));
    ExecutorService walkExecutor = mock(ExecutorService.class);
    RootLifecycleOps ops = newOps(watchedRoots, state, walkExecutor);

    ops.addWatchedRoot("default", a, io.justsearch.app.services.TestEngineContexts.internal());
    ops.addWatchedRoot("default", b, io.justsearch.app.services.TestEngineContexts.internal());

    // Two distinct roots → two walks queued; both registered.
    verify(walkExecutor, times(2)).execute(any());
    assertTrue(watchedRoots.containsKey(a.toAbsolutePath().normalize()));
    assertTrue(watchedRoots.containsKey(b.toAbsolutePath().normalize()));
  }
}
