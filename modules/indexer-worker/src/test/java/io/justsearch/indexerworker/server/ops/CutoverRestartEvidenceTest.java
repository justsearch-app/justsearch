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
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import java.io.IOException;
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
    var queue = mock(JobQueue.class);
    org.mockito.Mockito.when(queue.failureSummary())
        .thenReturn(new JobQueue.FailureSummary(0, null, null, null, null));
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager, queue, () -> true, () -> true, () -> null, 0, 60_000, -1,
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
  void nativeLiveCutoverPublishesWithoutRequestingRestart(@TempDir Path tempDir)
      throws Exception {
    var manager = new IndexGenerationManager(tempDir.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("manual").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    var queue = mock(JobQueue.class);
    org.mockito.Mockito.when(queue.failureSummary())
        .thenReturn(new JobQueue.FailureSummary(0, null, null, null, null));
    var runtime = mock(RunningRuntime.class);
    var live = new AtomicBoolean();
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager, queue, () -> true, () -> true, () -> null, 0, 60_000, -1,
        () -> runtime, () -> true, () -> {
          throw new AssertionError("live owner verifies Green under its final fence");
        }, () -> {}, () -> {}, () -> {
          throw new AssertionError("live publication must not request a cutover restart");
        }, tempDir, LoggerFactory.getLogger(CutoverRestartEvidenceTest.class),
        () -> {
          throw new AssertionError("live owner replaces the legacy promotion callback");
        }, observed -> {}, () -> {
          live.set(true);
          try (var promotion = manager.beginNativePromotion(blue, green)) {
            return promotion.promote();
          }
        });

    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);

    assertTrue(live.get());
    assertEquals(green, manager.readStateBestEffort().active_generation());
  }

  @Test
  void recordedGapWaitRetainsBlueAndGreenBeyondSwitchingDeadline(@TempDir Path tempDir)
      throws Exception {
    var manager = new IndexGenerationManager(tempDir.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("recorded").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    var queue = mock(JobQueue.class);
    org.mockito.Mockito.when(queue.failureSummary())
        .thenThrow(new IllegalStateException("recorded operation owns exact gap settlement"));
    var decisions = new java.util.concurrent.atomic.AtomicInteger();
    var promotions = new java.util.concurrent.atomic.AtomicInteger();
    var preApprovalReplayAttempts = new java.util.concurrent.atomic.AtomicInteger();
    var sourceRestorations = new java.util.concurrent.atomic.AtomicInteger();
    var candidateResumes = new java.util.concurrent.atomic.AtomicInteger();
    var accepted = new AtomicBoolean();
    var lateGap = new AtomicBoolean();
    var cutover = new KnowledgeServerMigrationOps.CheckedLiveCutover() {
      @Override public boolean recorded() { return true; }

      @Override public boolean enterGapWait() throws IOException {
        if (IndexGenerationManager.MigrationState.AWAITING_ACCEPTANCE.name().equals(
            manager.readStateBestEffort().migration_state())) return true;
        int attempt = sourceRestorations.incrementAndGet();
        if (attempt == 1) {
          assertEquals(IndexGenerationManager.MigrationState.SWITCHING.name(),
              manager.readStateBestEffort().migration_state(),
              "a refused physical restoration must not publish the unbounded wait");
          return false;
        }
        if (lateGap.get()) {
          assertEquals(IndexGenerationManager.MigrationState.SWITCHING.name(),
              manager.readStateBestEffort().migration_state(),
              "a gap found inside promotion still requires physical A restoration");
          lateGap.set(false);
        }
        manager.updateMigrationState(IndexGenerationManager.MigrationState.AWAITING_ACCEPTANCE);
        return true;
      }

      @Override public boolean resumeAcceptedGapBuild() throws IOException {
        assertTrue(sourceRestorations.get() > 1,
            "an accepted gap cannot resume B before A's wait transition settles");
        if (candidateResumes.incrementAndGet() == 1) return false;
        manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
        return true;
      }

      @Override public RecordedIngestionLifecycle.GapDecision gapDecision() {
        decisions.incrementAndGet();
        if (lateGap.get()) return RecordedIngestionLifecycle.GapDecision.AWAITING_ACCEPTANCE;
        if (accepted.get()) return RecordedIngestionLifecycle.GapDecision.ACCEPTED;
        if (!IndexGenerationManager.MigrationState.AWAITING_ACCEPTANCE.name().equals(
            manager.readStateBestEffort().migration_state())) {
          return RecordedIngestionLifecycle.GapDecision.AWAITING_ACCEPTANCE;
        }
        if (!accepted.get()) {
          assertEquals(IndexGenerationManager.MigrationState.AWAITING_ACCEPTANCE.name(),
              manager.readStateBestEffort().migration_state());
          assertEquals(blue, manager.readStateBestEffort().active_generation());
          assertEquals(green, manager.readStateBestEffort().building_generation());
          accepted.set(true);
        }
        return RecordedIngestionLifecycle.GapDecision.ACCEPTED;
      }

      @Override public IndexGenerationManager.State promote() throws IOException {
        if (!accepted.get()) {
          // The candidate must attempt strict replay to discover its current gap before a user
          // decision exists. Only the eventual pointer commitment requires that decision.
          assertEquals(IndexGenerationManager.MigrationState.SWITCHING.name(),
              manager.readStateBestEffort().migration_state());
          preApprovalReplayAttempts.incrementAndGet();
          lateGap.set(true);
          return null;
        }
        if (promotions.getAndIncrement() == 0) {
          lateGap.set(true);
          return null;
        }
        try (var promotion = manager.beginNativePromotion(blue, green)) {
          return promotion.promote();
        }
      }
    };
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager, queue, () -> true, () -> true, () -> null, 0, 5_000, 0,
        () -> mock(RunningRuntime.class), () -> true, () -> true, () -> {}, () -> {},
        () -> { throw new AssertionError("live cutover cannot request a restart"); },
        tempDir, LoggerFactory.getLogger(CutoverRestartEvidenceTest.class),
        () -> { throw new AssertionError("recorded live cutover owns promotion"); },
        observed -> {}, cutover);

    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);

    assertTrue(preApprovalReplayAttempts.get() > 0,
        "strict replay must discover candidate gaps before asking for approval");
    assertEquals(2, promotions.get());
    assertEquals(3, sourceRestorations.get());
    assertEquals(3, candidateResumes.get());
    assertEquals(green, manager.readStateBestEffort().active_generation());
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void enumerationFailurePersistsDespitePauseAndTransientWriteFailure(
      boolean unreadableFirstState, @TempDir Path data) throws Exception {
    var manager = org.mockito.Mockito.spy(new IndexGenerationManager(data.resolve("index")));
    String blue = manager.initializeOrLoad().state().active_generation();
    manager.startMigration("manual");
    manager.setMigrationPaused(true, "test");
    org.mockito.Mockito.doThrow(new IOException("transient state write failure"))
        .doCallRealMethod().when(manager).updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
    if (unreadableFirstState) {
      org.mockito.Mockito.doReturn(null).doCallRealMethod().when(manager).readStateBestEffort();
    }
    var active = new AtomicBoolean(true);
    var drained = new AtomicBoolean();
    var runtime = mock(RunningRuntime.class, RETURNS_DEEP_STUBS);
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager, mock(JobQueue.class), active::get, () -> false,
        () -> new IOException("incomplete coverage"), 0, 60_000, -1,
        () -> runtime, () -> { throw new AssertionError("failed scan cannot certify embeddings"); },
        () -> { throw new AssertionError("failed scan cannot verify Green"); },
        () -> drained.set(true), () -> {},
        () -> { throw new AssertionError("failed scan cannot restart/promote"); },
        data, LoggerFactory.getLogger(CutoverRestartEvidenceTest.class));
    Thread monitor = new Thread(() -> KnowledgeServerMigrationOps.runMigrationCutoverLoop(context));
    monitor.start();
    try {
      monitor.join(8_000);
      assertFalse(monitor.isAlive(), "failed persistence must retry even while migration is paused");
      assertEquals(IndexGenerationManager.MigrationState.FAILED.name(), manager.readStateBestEffort().migration_state());
      assertEquals(blue, manager.readStateBestEffort().active_generation());
      assertTrue(drained.get());
      org.mockito.Mockito.verify(manager, org.mockito.Mockito.times(2))
          .updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
      org.mockito.Mockito.verifyNoInteractions(runtime);
    } finally {
      active.set(false);
      monitor.interrupt();
      monitor.join(5_000);
    }
  }

  @Test
  void unreadableFailedJobsCountRefusesCutoverAndKeepsBlue(@TempDir Path data) throws Exception {
    var manager = new IndexGenerationManager(data.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    manager.startMigration("manual");
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);

    var queue = mock(JobQueue.class);
    org.mockito.Mockito.when(queue.queueDepth()).thenReturn(0L);
    org.mockito.Mockito.when(queue.failureSummary())
        .thenThrow(new IllegalStateException("failed jobs table is unreadable"));
    var drained = new AtomicBoolean();
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager,
        queue,
        () -> true,
        () -> true,
        () -> null,
        0,
        60_000,
        0,
        () -> {
          throw new AssertionError("unreadable failed-job count must refuse before cutover");
        },
        () -> {
          throw new AssertionError("unreadable failed-job count must refuse before finalization");
        },
        () -> {
          throw new AssertionError("unreadable failed-job count must refuse before verification");
        },
        () -> drained.set(true),
        () -> {},
        () -> {
          throw new AssertionError("unreadable failed-job count must not restart");
        },
        data,
        LoggerFactory.getLogger(CutoverRestartEvidenceTest.class));

    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);

    assertEquals(IndexGenerationManager.MigrationState.FAILED.name(),
        manager.readStateBestEffort().migration_state());
    assertEquals(blue, manager.readStateBestEffort().active_generation());
    assertTrue(drained.get(), "a failed cutover must drain the switch buffer");
  }

  private static KnowledgeServerMigrationOps.CutoverContext context(
      IndexGenerationManager genManager, Path dataDir, Runnable flush) {
    return new KnowledgeServerMigrationOps.CutoverContext(
        genManager,
        null,
        () -> true,
        () -> true,
        () -> null,
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
