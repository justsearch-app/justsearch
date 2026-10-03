/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.CleanShutdownMarker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** Live promotion, embedding readiness and gap refusal replace the retired cutover restart. */
final class LiveMigrationCutoverTest {

  @Test
  void defaultFailureBudgetRefusesOneUnsupersededFailedJob(@TempDir Path data) throws Exception {
    var config = new io.justsearch.configuration.resolved.ResolvedConfigBuilder().build();
    var manager = new IndexGenerationManager(data.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("manual").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    var queue = mock(JobQueue.class);
    org.mockito.Mockito.when(queue.failureSummary())
        .thenReturn(new JobQueue.FailureSummary(1, "missing.txt", "failed", null, null));
    var drained = new AtomicBoolean();
    var promoted = new AtomicBoolean();
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager, queue, () -> true, () -> true, () -> null, 0, 60_000,
        config.index().migrationCutoverMaxFailedJobs(),
        () -> mock(RunningRuntime.class), () -> true, () -> drained.set(true),
        LoggerFactory.getLogger(LiveMigrationCutoverTest.class), observed -> {}, () -> {
          promoted.set(true);
          try (var promotion = manager.beginNativePromotion(blue, green)) {
            return promotion.promote();
          }
        });
    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);
    assertFalse(promoted.get(), "the omitted-key default must preserve Blue when a job failed");
    assertEquals(blue, manager.readStateBestEffort().active_generation());
    assertEquals(IndexGenerationManager.MigrationState.FAILED.name(),
        manager.readStateBestEffort().migration_state());
    assertTrue(drained.get());
  }

  @Test
  void aLivePromotionOwnerIsRequired() {
    assertThrows(NullPointerException.class, () -> new KnowledgeServerMigrationOps.CutoverContext(
        null, null, () -> true, () -> true, () -> null, 0, 60_000, -1,
        () -> null, () -> true, () -> {}, LoggerFactory.getLogger(LiveMigrationCutoverTest.class),
        observed -> {}, null));
  }

  @Test
  void unfinishedEmbeddingsCannotReachLivePromotion(@TempDir Path tempDir) throws Exception {
    var manager = new IndexGenerationManager(tempDir.resolve("index"));
    String blue = manager.initializeOrLoad().state().active_generation();
    String green = manager.startMigration("manual").building_generation();
    manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
    var queue = mock(JobQueue.class);
    org.mockito.Mockito.when(queue.failureSummary())
        .thenReturn(new JobQueue.FailureSummary(0, null, null, null, null));
    var drainChecks = new java.util.concurrent.atomic.AtomicInteger();
    var promotions = new java.util.concurrent.atomic.AtomicInteger();
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager, queue, () -> true, () -> true, () -> null, 0, 60_000, -1,
        () -> mock(RunningRuntime.class), () -> drainChecks.incrementAndGet() > 1,
        () -> {}, LoggerFactory.getLogger(LiveMigrationCutoverTest.class), observed -> {}, () -> {
          assertEquals(2, drainChecks.get(), "pending embeddings must settle before live verification");
          promotions.incrementAndGet();
          try (var promotion = manager.beginNativePromotion(blue, green)) {
            return promotion.promote();
          }
        });
    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);
    assertEquals(1, promotions.get());
    assertEquals(green, manager.readStateBestEffort().active_generation());
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
        manager,
        queue,
        () -> true,
        () -> true,
        () -> null,
        0,
        60_000,
        -1,
        () -> runtime,
        () -> true,
        () -> {},
        LoggerFactory.getLogger(LiveMigrationCutoverTest.class),
        observed -> {},
        () -> {
          live.set(true);
          try (var promotion = manager.beginNativePromotion(blue, green)) {
            return promotion.promote();
          }
        });

    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);

    assertTrue(live.get());
    assertEquals(green, manager.readStateBestEffort().active_generation());
    assertFalse(Files.exists(CleanShutdownMarker.pathFor(manager.resolveGenerationPathStrict(green))),
        "live promotion must not mark an open generation as cleanly shut down");
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
        manager,
        queue,
        () -> true,
        () -> true,
        () -> null,
        0,
        5_000,
        0,
        () -> mock(RunningRuntime.class),
        () -> true,
        () -> {},
        LoggerFactory.getLogger(LiveMigrationCutoverTest.class),
        observed -> {},
        cutover);

    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);

    assertTrue(preApprovalReplayAttempts.get() > 0,
        "strict replay must discover candidate gaps before asking for approval");
    assertEquals(2, promotions.get());
    assertEquals(3, sourceRestorations.get());
    assertEquals(3, candidateResumes.get());
    assertEquals(green, manager.readStateBestEffort().active_generation());
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
    var runtime = mock(RunningRuntime.class);
    var context = new KnowledgeServerMigrationOps.CutoverContext(
        manager,
        mock(JobQueue.class),
        active::get,
        () -> false,
        () -> new IOException("incomplete coverage"),
        0,
        60_000,
        -1,
        () -> runtime,
        () -> { throw new AssertionError("failed scan cannot certify embeddings"); },
        () -> drained.set(true),
        LoggerFactory.getLogger(LiveMigrationCutoverTest.class),
        observed -> {},
        () -> { throw new AssertionError("refused candidate cannot promote"); });
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
        () -> drained.set(true),
        LoggerFactory.getLogger(LiveMigrationCutoverTest.class),
        observed -> {},
        () -> { throw new AssertionError("refused candidate cannot promote"); });

    KnowledgeServerMigrationOps.runMigrationCutoverLoop(context);

    assertEquals(IndexGenerationManager.MigrationState.FAILED.name(),
        manager.readStateBestEffort().migration_state());
    assertEquals(blue, manager.readStateBestEffort().active_generation());
    assertTrue(drained.get(), "a failed cutover must drain the switch buffer");
  }

}
