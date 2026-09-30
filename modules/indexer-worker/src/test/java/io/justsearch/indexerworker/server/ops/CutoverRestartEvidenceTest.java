/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

import io.justsearch.adapters.lucene.runtime.CleanShutdownMarker;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 915 (live validation) — the cutover restart is the one shutdown the Worker performs on its
 * own, and two facts did not survive it.
 *
 * <p>The clean-shutdown marker was left to {@code RuntimeSession.close()}, which makes it contingent
 * on the whole shutdown sequence finishing before the process goes away. Live runs showed the boot
 * after every cutover logging {@code Unclean previous shutdown detected} for the freshly promoted
 * generation and paying a FULL integrity verification for an index that had just been committed and
 * verified. The metrics snapshot had the same shape: a 60s cadence against a restart about 20s after
 * the migration starts, so {@code commit_by_reason} never carried {@code migration/cutover} live
 * even though both emit sites are production code.
 *
 * <p>Both are now stated at the moment they are true, rather than hoped for from a later step.
 */
final class CutoverRestartEvidenceTest {

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void restartFollowsVerifiedPromotionAndEvidence(boolean verified, @TempDir Path tempDir)
      throws Exception {
    var manager = new IndexGenerationManager(tempDir.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("manual").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    Path greenPath = manager.resolveGenerationPathStrict(green);
    var flushed = new AtomicBoolean();
    var restarted = new AtomicBoolean();
    var drainChecks = new java.util.concurrent.atomic.AtomicInteger();
    var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager, mock(JobQueue.class), () -> true, () -> true, 0, 60_000, -1,
        () -> runtime, () -> drainChecks.incrementAndGet() > 1, () -> {
          assertTrue(drainChecks.get() > 1, "unfinished embeddings cannot reach final verification");
          return verified;
        }, () -> {}, () -> flushed.set(true),
        () -> {
          assertEquals(green, manager.readStateBestEffort().active_generation());
          assertTrue(flushed.get(), "restart cannot discard unflushed evidence");
          assertTrue(Files.exists(CleanShutdownMarker.pathFor(greenPath)));
          restarted.set(true);
        }, tempDir, LoggerFactory.getLogger(CutoverRestartEvidenceTest.class));
    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);
    assertEquals(2, drainChecks.get(), "pending embeddings must be observed again before promotion");
    assertEquals(verified, restarted.get());
    assertEquals(verified ? green : blue, manager.readStateBestEffort().active_generation());
  }

  @Test
  void thePromotedGenerationIsMarkedCleanAndTheMetricsAreFlushedBeforeTheRestart(
      @TempDir Path tempDir) throws Exception {
    IndexGenerationManager genManager = new IndexGenerationManager(tempDir.resolve("index"));
    IndexGenerationManager.State state = genManager.initializeOrLoad().state();
    Path activePath = genManager.resolveGenerationPathStrict(state.active_generation());

    assertFalse(
        Files.exists(CleanShutdownMarker.pathFor(activePath)),
        "precondition: nothing has recorded a clean shutdown for this generation yet");

    AtomicBoolean flushed = new AtomicBoolean(false);
    KnowledgeServerMigrationOps.preserveEvidenceBeforeRestart(
        context(genManager, tempDir, () -> flushed.set(true)), state);

    assertTrue(
        Files.exists(CleanShutdownMarker.pathFor(activePath)),
        "the promoted generation has just been committed and verified — the next boot must not pay"
            + " a FULL integrity scan for it");
    assertTrue(flushed.get(), "and the cutover's own counters are written before they are lost");
  }

  /** A failure here must not abort a cutover that has already completed. */
  @Test
  void aFlushThatThrowsDoesNotEscape(@TempDir Path tempDir) throws Exception {
    IndexGenerationManager genManager = new IndexGenerationManager(tempDir.resolve("index"));
    IndexGenerationManager.State state = genManager.initializeOrLoad().state();

    KnowledgeServerMigrationOps.preserveEvidenceBeforeRestart(
        context(
            genManager,
            tempDir,
            () -> {
              throw new IllegalStateException("telemetry is gone");
            }),
        state);

    assertTrue(
        Files.exists(
            CleanShutdownMarker.pathFor(
                genManager.resolveGenerationPathStrict(state.active_generation()))),
        "and the marker still lands: the two facts are independent");
  }

  private static KnowledgeServerMigrationOps.CutoverContext context(
      IndexGenerationManager genManager, Path dataDir, Runnable flush) {
    return new KnowledgeServerMigrationOps.CutoverContext(
        genManager,
        null,
        () -> true,
        () -> true,
        0L,
        0L,
        0,
        () -> null,
        () -> true,
        () -> true,
        () -> {},
        flush,
        () -> {},
        dataDir,
        LoggerFactory.getLogger(CutoverRestartEvidenceTest.class));
  }
}
