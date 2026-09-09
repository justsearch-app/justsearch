/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.process.IndexBasePathLockE2ETest}.
 *
 * <p><b>What the retired test asserted, and why it survives the collapse.</b> It spawned two Worker
 * processes with different data directories but the same {@code justsearch.index.base_path} and
 * asserted the second exited fast while the first stayed healthy. The <em>process</em> half of that
 * is gone; the <em>property</em> is not. {@code IndexRootLock} still guards the index base path
 * (KnowledgeServer.java:603-604), it is still the only thing standing between two index owners and
 * a corrupted Lucene directory, and one JVM has made the failure mode <em>more</em> reachable, not
 * less: a second {@link EngineRoot} composed by mistake in the same process is now a plausible
 * defect, and it is exactly what this pins.
 *
 * <p><b>One honest difference, stated rather than papered over.</b> Across processes
 * {@code FileChannel.tryLock()} returns {@code null} and {@code IndexRootLock} raises "Index base
 * path is already locked by another process" (IndexRootLock.java:72). Within one JVM the JDK
 * refuses earlier, with {@code OverlappingFileLockException}, which
 * {@code IndexRootLock.acquireInner}'s {@code catch (Exception)} wraps as "Failed to acquire index
 * root lock" (IndexRootLock.java:64-68). Both are the same refusal — a second owner is turned away
 * before it can open a writer — so this test asserts the refusal and the lock file it names, not
 * the message that distinguishes the two paths.
 */
@Timeout(180)
final class EngineIndexBasePathLockTest {

  private EngineTestHarness first;

  @AfterEach
  void tearDown() {
    if (first != null) {
      first.close();
      first = null;
    }
  }

  @Test
  @DisplayName("a second index owner on the same index base path is refused, and the first survives")
  void secondOwnerOnTheSameIndexBaseIsRefused(@TempDir Path tempDir) throws Exception {
    Path sharedIndexBase = tempDir.resolve("shared-index-base");
    Path dataA = tempDir.resolve("data-a");
    Path dataB = tempDir.resolve("data-b");
    Files.createDirectories(dataB);

    first = EngineTestHarness.start(dataA, sharedIndexBase, Map.of());

    // The first owner works: a document submitted through it becomes findable.
    Path doc = tempDir.resolve("lock-probe.txt");
    Files.writeString(doc, "quokka index base path lock probe");
    assertTrue(
        first.client().submitBatch(List.of(doc), TestEngineContexts.FOREGROUND).getAcceptedCount() > 0,
        "the first owner must accept work");
    assertTrue(
        first.awaitSearchable("quokka", 120_000),
        "the first owner must index and serve the probe document");

    // A second owner, different data dir, SAME index base path. It must not get a writer.
    EngineTestHarness.publishConfig(dataB, sharedIndexBase, Map.of());
    EngineRoot second = new EngineRoot(30_000L, 5_000);
    IOException refused =
        assertThrows(
            IOException.class,
            () -> second.start(new GpuSchedulingGauge(), IpcTelemetry.noop()),
            "a second owner of one index base path must be refused, not silently admitted");
    assertNotNull(refused.getMessage(), "the refusal must say what it refused");
    // KnowledgeServer.start wraps the boot failure ("Failed to start KnowledgeServer"), so the
    // assertion walks the cause chain: what matters is that the refusal came from the index root
    // lock and not from some unrelated boot failure that would pass a bare assertThrows.
    assertTrue(
        causeChainMentions(refused, "index root lock")
            || causeChainMentions(refused, "already locked"),
        "the refusal must come from the index root lock rather than from an unrelated boot"
            + " failure; chain was: " + causeChain(refused));
    // Republish the first owner's config so nothing downstream reads dataB's, then prove the
    // first owner is untouched — the refusal must cost the incumbent nothing.
    EngineTestHarness.publishConfig(dataA, sharedIndexBase, Map.of());
    assertTrue(first.client().isHealthy(TestEngineContexts.FOREGROUND), "the first owner must remain healthy");
    assertTrue(
        first.client().search("quokka", 10, TestEngineContexts.FOREGROUND).getResultsCount() > 0,
        "the first owner must still serve its index after the second was refused");

    // The lock is a sibling of the index base path, not a file inside it (IndexRootLock.java:44)
    // — Windows refuses to move a directory containing an open handle, and the migration path
    // moves this directory.
    Path lockFile = sharedIndexBase.resolveSibling(sharedIndexBase.getFileName() + ".index.lock");
    assertTrue(Files.exists(lockFile), "the index root lock file must exist at " + lockFile);

    // Do not close the failed second Root before retrying it. KnowledgeServer.start owns cleanup of
    // its partially constructed runtime, queue, telemetry and locks; after the incumbent releases
    // the shared directory, the same Root and data directory must open successfully.
    first.close();
    first = null;
    EngineTestHarness.publishConfig(dataB, sharedIndexBase, Map.of());
    KnowledgeClient recovered =
        second.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
    try {
      assertTrue(recovered.isHealthy(TestEngineContexts.FOREGROUND), "the failed start must release resources for retry");
    } finally {
      second.close();
    }
  }

  private static boolean causeChainMentions(Throwable thrown, String needle) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t.getMessage() != null && t.getMessage().contains(needle)) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  private static String causeChain(Throwable thrown) {
    StringBuilder chain = new StringBuilder();
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" <- ");
      if (t.getCause() == t) {
        break;
      }
    }
    return chain.toString();
  }
}
