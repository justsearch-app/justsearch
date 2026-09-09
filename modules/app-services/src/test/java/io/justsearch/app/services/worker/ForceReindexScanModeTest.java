/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import io.justsearch.ipc.ScanMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 821 §3-C3 — the Head half of "make force-reindex real".
 *
 * <p>{@code ReindexHandler} has always told the user "force=true; bypasses mtime unchanged-check",
 * but the flag stopped at {@link RootLifecycleOps#reindexWatchedRoots(boolean)}: it only chose
 * which Head-side branch ran, and BOTH branches dispatched {@code SCAN_MODE_INITIAL}. The Worker
 * therefore could not tell a force-reindex from an ordinary rewalk, which is the "installed bases
 * cannot self-repair" member of the C3 class.
 *
 * <p>These tests pin the mode the scan arm actually receives. The Worker-side consequence is
 * pinned separately by {@code WorkerScanOpsTest#forceReindexScanMarksEveryAdmittedPathForced} and
 * {@code JobBatchExtractorForcedPathTest}.
 */
@DisplayName("reindexWatchedRoots — the force flag reaches the scan RPC as a ScanMode")
final class ForceReindexScanModeTest {

  @TempDir Path tempDir;

  /** Runs one reindex against a recording scan arm and reports the mode it was handed. */
  private ScanMode reindexAndCaptureMode(boolean force, String storeName) throws Exception {
    Path root = Files.createDirectories(tempDir.resolve(storeName));
    Map<Path, Instant> watchedRoots = new ConcurrentHashMap<>();
    watchedRoots.put(root, Instant.now());
    ExecutorService walkExecutor = Executors.newSingleThreadExecutor();
    AtomicReference<ScanMode> scannedMode = new AtomicReference<>();

    WatchedRootsState state =
        new WatchedRootsState(
            watchedRoots, new WatchedRootsStore(tempDir.resolve(storeName + ".json"), null));
    RootLifecycleOps ops =
        new RootLifecycleOps(
            watchedRoots,
            state,
            // A non-empty exclude set on BOTH arms. force=false without excludes takes the
            // syncDirectory branch and never scans at all, so this is what makes the two arms
            // differ ONLY in `force` — otherwise the unforced case would prove nothing.
            () -> ExcludeMatcher.fromPatterns(java.util.List.of("*.tmp"), true),
            (rootPath, collection, mode, globs, progress, engineContext) -> {
              scannedMode.set(mode);
              return null;
            },
            new RootLifecycleOps.WorkerWatchFn() {
              @Override
              public void watch(String rootPath, String collection, io.justsearch.core.context.EngineContext engineContext) {}

              @Override
              public void unwatch(String rootPath, io.justsearch.core.context.EngineContext engineContext) {}
            },
            (p, engineContext) -> null,
            (s, engineContext) -> null,
            mock(SyncOps.class),
            walkExecutor);

    ops.reindexWatchedRoots(force, io.justsearch.app.services.TestEngineContexts.durableInternal());
    walkExecutor.shutdown();
    assertEquals(
        true,
        walkExecutor.awaitTermination(10, TimeUnit.SECONDS),
        "the queued walk must finish before the assertion reads what it dispatched");
    assertNotNull(scannedMode.get(), "the reindex must have dispatched a scan for the root");
    return scannedMode.get();
  }

  @Test
  @DisplayName("force=true dispatches SCAN_MODE_FORCE_REINDEX")
  void forceReindexReachesTheWorkerAsForceMode() throws Exception {
    assertEquals(
        ScanMode.SCAN_MODE_FORCE_REINDEX,
        reindexAndCaptureMode(true, "forced"),
        "a hard-coded SCAN_MODE_INITIAL here is the defect that made force-reindex inert");
  }

  @Test
  @DisplayName("force=false still dispatches an ordinary initial scan")
  void ordinaryReindexKeepsTheInitialMode() throws Exception {
    assertEquals(
        ScanMode.SCAN_MODE_INITIAL,
        reindexAndCaptureMode(false, "unforced"),
        "only the force path may ask the Worker to bypass the unchanged-check");
  }
}
