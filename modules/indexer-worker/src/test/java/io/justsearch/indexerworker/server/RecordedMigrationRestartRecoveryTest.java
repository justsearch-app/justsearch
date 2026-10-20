/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.services.WorkerMutationAdmission;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * A recorded full-index migration interrupted by an Engine restart must end cleanly: a refused
 * candidate rolls back to A, and a SWITCHING candidate that can never certify is refused before
 * the pointer moves instead of holding SWITCHING for its whole deadline (task a5fbcb, M1/M2/M5/M8/M9).
 */
class RecordedMigrationRestartRecoveryTest {
  private static final String OPERATION = "01994180-0000-7000-8000-000000000199";
  private static final String SHA = "b".repeat(64);

  // ---------------------------------------------------------------- refused-candidate rollback

  @Test
  // M1: a restart's watcher uncertainty latch does not keep a refused candidate alive
  void latchedWatcherUncertaintyDoesNotBlockRollback(@TempDir Path tempDir) throws Exception {
    Rollback rollback = new Rollback(tempDir);
    rollback.admission.markReplayUncertain(); // reindexPersistedRoots re-registered the root
    rollback.reconcile();
    rollback.assertRolledBackToA();
  }

  @Test
  // M8: a FENCED boot's pending root walk does not keep a refused candidate alive
  void pendingFencedRootWalkDoesNotBlockRollback(@TempDir Path tempDir) throws Exception {
    Rollback rollback = new Rollback(tempDir);
    // The FENCED boot re-walked the root: rows are PENDING under a later revision, and the
    // indexing loop never runs while FENCED, so they cannot settle before cleanup.
    when(rollback.queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(40, 40, 0, 5, 0));
    rollback.supersededByLaterAdmission.set(true);
    when(rollback.queue.matchesAcceptedFileProjection(
        rollback.file.toString(), "accepted-revision", rollback.hash, rollback.hash)).thenReturn(false);
    rollback.reconcile();
    rollback.assertRolledBackToA();
    verify(rollback.queue, never()).deleteByExactPath(anyString());
  }

  @Test
  // preserved: a claimed queue row still retains Green
  void claimedRowStillRetainsGreen(@TempDir Path tempDir) throws Exception {
    Rollback rollback = new Rollback(tempDir);
    when(rollback.queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(1, 1, 1, 5, 0));
    rollback.reconcile();
    rollback.assertGreenRetained();
  }

  @Test
  // preserved: an accepted admission neither on A nor owned by the queue retains Green
  void unprovedAdmissionStillRetainsGreen(@TempDir Path tempDir) throws Exception {
    Rollback rollback = new Rollback(tempDir);
    rollback.admission.markReplayUncertain();
    when(rollback.queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(1, 1, 0, 5, 0));
    when(rollback.queue.matchesAcceptedFileProjection(
        rollback.file.toString(), "accepted-revision", rollback.hash, rollback.hash)).thenReturn(false);
    rollback.reconcile();
    rollback.assertGreenRetained();
  }

  // M9: a cut at each cleanup seam, then a later attempt reaches the clean-failure state
  @Test
  void aCutAfterTheSourceCommitResumesToCleanFailure(@TempDir Path tempDir) throws Exception {
    cleanupCutResumesToCleanFailure("migration-refusal-after-source-commit", tempDir);
  }

  @Test
  void aCutAfterTheJournalAcknowledgementResumesToCleanFailure(@TempDir Path tempDir)
      throws Exception {
    cleanupCutResumesToCleanFailure("migration-refusal-after-journal-ack", tempDir);
  }

  @Test
  void aCutAfterAbandonmentResumesToCleanFailure(@TempDir Path tempDir) throws Exception {
    cleanupCutResumesToCleanFailure("migration-refusal-after-abandonment", tempDir);
  }

  @Test
  void aCutAfterPhysicalRetirementResumesToCleanFailure(@TempDir Path tempDir) throws Exception {
    cleanupCutResumesToCleanFailure("migration-refusal-after-retirement", tempDir);
  }

  private static void cleanupCutResumesToCleanFailure(String point, Path tempDir) throws Exception {
    Rollback rollback = new Rollback(tempDir);
    rollback.admission.markReplayUncertain();
    AtomicBoolean reached = new AtomicBoolean();
    rollback.server.installMigrationBarrierForTests(transition -> {
      if (point.equals(transition.point()) && reached.compareAndSet(false, true)) {
        throw new IOException("harness cut at " + point);
      }
    });
    rollback.reconcile();
    assertTrue(reached.get(), "cleanup must pass the " + point + " seam");
    assertFalse(rollback.restarted.get(), "a cut before the restart leaves the restart undone");

    // The next owner (a restarted process re-entering cleanup) starts from the durable state.
    rollback.freshProducer();
    rollback.reconcile();
    rollback.assertRolledBackToA();
  }

  // ---------------------------------------------------------------- SWITCHING refusal

  @Test
  // M2: a SWITCHING candidate with an unrecorded watcher mutation fails at once
  void latchedSwitchingCandidateFailsPromptly(@TempDir Path tempDir) throws Exception {
    Switching switching = new Switching(tempDir, null);
    switching.admission.markReplayUncertain();
    switching.start();
    assertEquals("FAILED", switching.awaitStateOtherThanSwitching(5_000));
    assertEquals(switching.active, switching.state().active_generation(), "A stays active");
    switching.stop();
  }

  @Test
  // M5: a provably empty candidate with an embedding model fails at once
  void provablyEmptyCandidateFailsPromptly(@TempDir Path tempDir) throws Exception {
    Switching switching = new Switching(tempDir, "current-model");
    switching.start();
    assertEquals("FAILED", switching.awaitStateOtherThanSwitching(5_000));
    assertEquals(switching.active, switching.state().active_generation(), "A stays active");
    switching.stop();
  }

  // M5: a candidate that is not provably empty keeps earning its stamp
  @Test
  void aCandidateWithADocumentKeepsWaiting(@TempDir Path tempDir) throws Exception {
    candidateThatIsNotProvablyEmptyKeepsWaiting("document", tempDir);
  }

  @Test
  void aCandidateWithAPlannedUnitKeepsWaiting(@TempDir Path tempDir) throws Exception {
    candidateThatIsNotProvablyEmptyKeepsWaiting("planned-unit", tempDir);
  }

  @Test
  void aCandidateWithAJournalObligationKeepsWaiting(@TempDir Path tempDir) throws Exception {
    candidateThatIsNotProvablyEmptyKeepsWaiting("journal-obligation", tempDir);
  }

  @Test
  void aCandidateWithAPendingRowKeepsWaiting(@TempDir Path tempDir) throws Exception {
    candidateThatIsNotProvablyEmptyKeepsWaiting("pending-row", tempDir);
  }

  @Test
  void aCandidateWhoseEnumeratorRunsKeepsWaiting(@TempDir Path tempDir) throws Exception {
    candidateThatIsNotProvablyEmptyKeepsWaiting("running-enumerator", tempDir);
  }

  @Test
  void aCandidateWithAnUnreadableWalkKeepsWaiting(@TempDir Path tempDir) throws Exception {
    candidateThatIsNotProvablyEmptyKeepsWaiting("unreadable-walk", tempDir);
  }

  private static void candidateThatIsNotProvablyEmptyKeepsWaiting(String evidence, Path tempDir)
      throws Exception {
    Switching switching = new Switching(tempDir, "current-model");
    switch (evidence) {
      case "document" -> {
        when(switching.greenCounts.docCount()).thenReturn(1L);
        when(switching.greenCounts.docCountOrThrow()).thenReturn(1L);
      }
      case "planned-unit" -> when(switching.queue.recordedWalk(OPERATION))
          .thenReturn(Optional.of(walk(1L)));
      case "journal-obligation" -> when(switching.queue.listSwitchBufferOpsStrictForGeneration(
          switching.building)).thenReturn(List.of(new SwitchBufferCapableQueue.SwitchBufferOp(
              switching.building, "path:/x", "UPSERT", "{}", 1, "v1")));
      case "pending-row" -> when(switching.queue.jobStateCountsStrict())
          .thenReturn(new JobQueue.JobStateCounts(1, 0, 0, 0, 0));
      case "running-enumerator" -> {
        var running = KnowledgeServer.class.getDeclaredField("migrationEnumeratorRunning");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(switching.server)).set(true);
      }
      case "unreadable-walk" -> when(switching.queue.recordedWalk(OPERATION))
          .thenThrow(new IllegalStateException("unreadable"));
      default -> throw new IllegalArgumentException(evidence);
    }
    switching.start();
    assertNull(switching.awaitStateOtherThanSwitching(1_500), "SWITCHING must keep waiting");
    switching.stop();
  }

  @Test
  // M7: a keyword-only empty candidate is not refused as empty
  void keywordOnlyEmptyCandidateIsNotRefused(@TempDir Path tempDir) throws Exception {
    Switching switching = new Switching(tempDir, null);
    switching.start();
    // finalize succeeds without a model; promotion itself is not under test here.
    assertNull(switching.awaitStateOtherThanSwitching(1_500),
        "keyword-only candidates keep their structural path");
    switching.stop();
  }

  // ---------------------------------------------------------------- fixtures

  private static JobQueue.WalkProgress walk(long plannedUnits) {
    return new JobQueue.WalkProgress(OPERATION, SHA, 1L, 1L,
        JobQueue.WalkEnumerationOutcome.COMPLETE, 0L, 0L, 2L, null, null, 0L, true, SHA,
        plannedUnits);
  }

  private static WorkerBootFixture.Layout preparedLayout(Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");
    return layout;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  /** A refused, recorded candidate whose one accepted file journal entry must settle on A. */
  private static final class Rollback {
    final WorkerBootFixture.Layout layout;
    final KnowledgeServer server;
    final IndexGenerationManager manager;
    final RecordedIngestionLifecycle lifecycle = mock(RecordedIngestionLifecycle.class);
    final RunningRuntime source = mock(RunningRuntime.class);
    final RunningRuntime green = mock(RunningRuntime.class);
    final DocumentFieldOps fields = mock(DocumentFieldOps.class);
    final AtomicBoolean supersededByLaterAdmission = new AtomicBoolean();
    final SwitchBufferCapableQueue queue = mock(SwitchBufferCapableQueue.class, invocation ->
        "queueStillOwnsFileAdmission".equals(invocation.getMethod().getName())
            ? supersededByLaterAdmission.get()
            : Mockito.RETURNS_DEFAULTS.answer(invocation));
    final AtomicBoolean restarted = new AtomicBoolean();
    final AtomicBoolean journalAcked = new AtomicBoolean();
    final String active;
    final String building;
    final Path file;
    final String hash;
    WorkerMutationAdmission admission;
    DefaultWorkerAppServices producer;

    Rollback(Path tempDir) throws Exception {
      layout = preparedLayout(tempDir);
      server = new KnowledgeServer(new TestEngineExecutors(),
          WorkerBootFixture.workerConfig(layout.dataDir()), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle);
      manager = Mockito.spy(layout.genManager());
      active = manager.readStateBestEffort().active_generation();
      building = manager.startRecordedMigration(OPERATION, "recorded-test", "a".repeat(64),
          active, List.of()).building_generation();
      manager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
      when(lifecycle.recordedPrecommitRefused(OPERATION)).thenReturn(true);
      when(source.documentFieldOps()).thenReturn(fields);
      when(source.commitOps()).thenReturn(mock(CommitOps.class));
      file = Files.writeString(tempDir.resolve("accepted.txt"), "accepted").toAbsolutePath();
      hash = SourceContentHash.sha256(file);
      var version = new SwitchBufferCapableQueue.SwitchBufferOp(building, "path:" + file, "UPSERT",
          new SwitchBufferUpsert(file.toString(), null, null, "accepted-revision", hash).encode(),
          1, "v1");
      when(queue.listSwitchBufferOpsStrict()).thenAnswer(
          ignored -> journalAcked.get() ? List.of() : List.of(version));
      when(queue.listSwitchBufferOpsStrictForGeneration(building)).thenAnswer(
          ignored -> journalAcked.get() ? List.of() : List.of(version));
      when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 1, 0));
      when(queue.matchesAcceptedFileProjection(file.toString(), "accepted-revision", hash, hash))
          .thenReturn(true);
      when(fields.getDocumentFieldOrThrow(file.toString(), SchemaFields.SOURCE_SHA256))
          .thenReturn(hash);
      when(queue.removeReplayedSwitchBufferOps(List.of(version))).thenAnswer(ignored -> {
        journalAcked.set(true);
        return 1;
      });
      freshProducer();
      setField(server, "generationBootOwnership", new IndexGenerationManager.BootOwnership.Recorded(
          OPERATION, active, "recorded-test", "a".repeat(64), true));
      setField(server, "indexGenerationManager", manager);
      setField(server, "activeIndexPath", layout.activePath());
      setField(server, "searchLifecycle", source);
      setField(server, "ingestLifecycle", green);
      setField(server, "jobQueue", queue);
      setField(server, "migrationRestartAction", (Runnable) () -> restarted.set(true));
    }

    void freshProducer() throws Exception {
      var owner = new Object();
      var latched = admission != null && !admission.replayCertain();
      admission = new WorkerMutationAdmission(owner);
      if (latched) admission.markReplayUncertain(); // the same process keeps its latch
      producer = mock(DefaultWorkerAppServices.class);
      when(producer.mutationAdmission()).thenReturn(admission);
      when(producer.mutationOwnerToken()).thenReturn(owner);
      when(producer.pauseProducerForCutover(10_000)).thenReturn(true);
      var closed = new AtomicBoolean();
      Mockito.doAnswer(invocation -> { closed.set(true); return null; }).when(producer).close();
      when(producer.producerClosed()).thenAnswer(ignored -> closed.get());
      server.appServices = producer;
    }

    void reconcile() throws Exception {
      var reconcile = KnowledgeServer.class.getDeclaredMethod("reconcileRefusedRecordedCandidate");
      reconcile.setAccessible(true);
      reconcile.invoke(server);
    }

    void assertRolledBackToA() throws Exception {
      var state = manager.readStateBestEffort();
      assertEquals(active, state.active_generation(), "A stays the active generation");
      assertEquals("IDLE", state.migration_state(), "the pointer returns to IDLE");
      assertNull(state.building_generation(), "B is no longer the building generation");
      assertNull(state.previous_generation(), "no previous alias survives");
      assertTrue(journalAcked.get(), "the candidate journal was settled on A and acknowledged");
      assertTrue(restarted.get(), "the producer is replaced by a fresh owner");
      try (var directories = Files.list(layout.indexBase().resolve("indices"))) {
        assertEquals(List.of(active), directories.filter(Files::isDirectory)
            .map(path -> path.getFileName().toString()).toList(), "B is physically absent");
      }
    }

    void assertGreenRetained() throws Exception {
      verify(manager, never()).abandonBuildingGeneration(anyString());
      verify(producer, never()).close();
      verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
      assertEquals(building, manager.readStateBestEffort().building_generation());
      assertFalse(restarted.get());
    }
  }

  /** A recorded candidate held in SWITCHING by the real cutover monitor. */
  private static final class Switching {
    final KnowledgeServer server;
    final IndexGenerationManager manager;
    final RecordedIngestionLifecycle lifecycle = mock(RecordedIngestionLifecycle.class);
    final SwitchBufferCapableQueue queue = mock(SwitchBufferCapableQueue.class);
    final RunningRuntime green = mock(RunningRuntime.class);
    final IndexCountOps greenCounts = mock(IndexCountOps.class);
    final WorkerMutationAdmission admission;
    final String active;
    final String building;

    Switching(Path tempDir, String embeddingFingerprint) throws Exception {
      var layout = preparedLayout(tempDir);
      server = new KnowledgeServer(new TestEngineExecutors(),
          WorkerBootFixture.workerConfig(layout.dataDir()), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle);
      manager = layout.genManager();
      active = manager.readStateBestEffort().active_generation();
      building = manager.startRecordedMigration(OPERATION, "recorded-test", "a".repeat(64),
          active, List.of()).building_generation();
      manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
      when(lifecycle.recordedCutoverReady(OPERATION)).thenReturn(true);
      when(green.indexCountOps()).thenReturn(greenCounts);
      when(green.commitOps()).thenReturn(mock(CommitOps.class));
      when(queue.recordedWalk(OPERATION)).thenReturn(Optional.of(walk(0L)));
      when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
      when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
      when(queue.listSwitchBufferOpsStrictForGeneration(any())).thenReturn(List.of());
      var ecc = mock(EmbeddingCompatibilityController.class);
      when(ecc.currentFingerprint()).thenReturn(embeddingFingerprint);
      var owner = new Object();
      admission = new WorkerMutationAdmission(owner);
      var producer = mock(DefaultWorkerAppServices.class);
      when(producer.mutationAdmission()).thenReturn(admission);
      when(producer.mutationOwnerToken()).thenReturn(owner);
      server.appServices = producer;
      server.embeddingCompatController = ecc;
      setField(server, "generationBootOwnership", new IndexGenerationManager.BootOwnership.Recorded(
          OPERATION, active, "recorded-test", "a".repeat(64), true, true, List.of()));
      setField(server, "indexGenerationManager", manager);
      setField(server, "activeIndexPath", layout.activePath());
      setField(server, "buildingIndexPath", manager.resolveGenerationPathStrict(building));
      setField(server, "searchLifecycle", mock(RunningRuntime.class));
      setField(server, "ingestLifecycle", green);
      setField(server, "jobQueue", queue);
      setField(server, "migrationEnumeratorDone", true);
      setField(server, "migrationRestartAction", (Runnable) () -> {});
    }

    IndexGenerationManager.State state() {
      return manager.readStateBestEffort();
    }

    void start() throws Exception {
      setField(server, "running", true);
      var start = KnowledgeServer.class.getDeclaredMethod("startMigrationCutoverMonitorBestEffort");
      start.setAccessible(true);
      start.invoke(server);
    }

    /** The first non-SWITCHING state within the bound, or null when SWITCHING held throughout. */
    String awaitStateOtherThanSwitching(long timeoutMs) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
      while (System.nanoTime() < deadline) {
        var state = state();
        if (state != null && !"SWITCHING".equals(state.migration_state())) {
          return state.migration_state();
        }
        Thread.sleep(25);
      }
      return null;
    }

    void stop() throws Exception {
      setField(server, "running", false);
      var thread = (Thread) getMonitor();
      if (thread != null) {
        thread.interrupt();
        assertTrue(server.awaitMigrationCutoverExitForTests(10, TimeUnit.SECONDS));
      }
      server.close();
    }

    private Object getMonitor() throws Exception {
      var field = KnowledgeServer.class.getDeclaredField("migrationCutoverThread");
      field.setAccessible(true);
      return field.get(server);
    }
  }
}
