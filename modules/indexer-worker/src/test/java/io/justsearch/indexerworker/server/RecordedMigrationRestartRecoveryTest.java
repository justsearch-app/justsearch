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
  // M8: a FENCED boot's pending root walk hands over once A's writer, run now, commits it
  void pendingFencedRootWalkHandsOverOnceAsWriterCommitsIt(@TempDir Path tempDir)
      throws Exception {
    Rollback rollback = new Rollback(tempDir);
    rollback.recoveryBoot();
    rollback.fileChangedSinceA();
    // The FENCED boot re-walked the root: rows are PENDING under a later revision. The refused
    // walk is sealed, so this boot's producer, which writes A, now runs them.
    when(rollback.queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(40, 40, 0, 5, 0));
    rollback.sourceWork.set("OUTSTANDING");
    when(rollback.queue.matchesAcceptedFileProjection(
        rollback.file.toString(), "accepted-revision", rollback.hash, rollback.hash)).thenReturn(false);
    rollback.reconcile();
    assertTrue(Mockito.mockingDetails(rollback.producer).getInvocations().stream()
            .anyMatch(call -> "startIndexingLoop".equals(call.getMethod().getName())),
        "this boot's writer, which writes A, runs the pending rows");
    rollback.assertGreenRetained();
    rollback.aWritesTheCurrentBytes();
    rollback.reconcile();
    rollback.assertRolledBackToA();
    verify(rollback.queue, never()).deleteByExactPath(anyString());
  }

  @Test
  // preserved: a FENCED boot runs A's writer only once the refused walk can issue no claim
  void aFencedBootKeepsItsWriterStoppedWhileTheRefusedWalkIsOpen(@TempDir Path tempDir)
      throws Exception {
    Rollback rollback = new Rollback(tempDir);
    rollback.recoveryBoot();
    rollback.fileChangedSinceA();
    when(rollback.queue.recordedWalk(OPERATION)).thenReturn(Optional.of(walk(1)));
    rollback.sourceWork.set("OUTSTANDING");
    when(rollback.queue.matchesAcceptedFileProjection(
        rollback.file.toString(), "accepted-revision", rollback.hash, rollback.hash)).thenReturn(false);
    rollback.reconcile();
    verify(rollback.producer, never()).startIndexingLoop();
    rollback.assertGreenRetained();
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
    rollback.fileChangedSinceA();
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

  // M1+: hand-over to A is judged by A's committed effect, on the real queue
  @Test
  void aLaterFinishedAdmissionWaitsForTheAJobItQueues(@TempDir Path tempDir) throws Exception {
    // A later admission of the file finished (its row is DONE) but A still holds stale bytes
    // and no work is pending: the row alone never hands over. Cleanup queues an ordinary A job
    // and keeps Green and the journal until A's writer commits it.
    try (var handover = new RealQueueHandover(tempDir, "DONE", "stale")) {
      handover.reconcile();
      String queued = handover.assertWaitingOnAJob(true);
      handover.reconcile();
      assertEquals(queued, handover.assertWaitingOnAJob(true), "a re-entered attempt queues nothing");
      handover.aCommits(RealQueueHandover.LATER);
      handover.reconcile();
      handover.assertHandedOver();
    }
  }

  // M8: a pending later admission is A's work to finish, not yet its effect
  @Test
  void aPendingLaterAdmissionHandsOverOnceACommitsIt(@TempDir Path tempDir) throws Exception {
    try (var handover = new RealQueueHandover(tempDir, "PENDING", "stale")) {
      handover.reconcile();
      handover.assertWaitingOnAJob(false);
      handover.aCommits(RealQueueHandover.LATER);
      handover.reconcile();
      handover.assertHandedOver();
    }
  }

  // preserved: A already committed the later admission's exact bytes
  @Test
  void aLaterAdmissionCommittedOnAHandsTheUpdateToA(@TempDir Path tempDir) throws Exception {
    try (var handover = new RealQueueHandover(tempDir, "DONE", "later")) {
      handover.reconcile();
      handover.assertHandedOver();
    }
  }

  // M1+: a later row retired or refused without writing is not A's effect
  @Test
  void aRetiredLaterAdmissionWaitsForTheAJobItQueues(@TempDir Path tempDir) throws Exception {
    // The refused walk retired the file's later row (DONE, nothing written). Its captured plan
    // names the bytes A still holds, but the accepted watcher update never reached A. Cleanup
    // queues an ordinary A job and acknowledges only once A's writer committed it.
    String onA = RealQueueHandover.STALE;
    try (var handover = new RealQueueHandover(tempDir, "DONE", onA, null, onA)) {
      handover.reconcile();
      handover.assertWaitingOnAJob(true);
      handover.aCommits(RealQueueHandover.LATER);
      handover.reconcile();
      handover.assertHandedOver();
    }
  }

  // M1+: A committed a later captured revision whose bytes changed after its plan
  @Test
  void aLaterRevisionCommittedOnAWithChangedBytesHandsTheUpdateToA(@TempDir Path tempDir)
      throws Exception {
    // The later admission's plan kept the captured bytes, its writer committed newer ones, and
    // A holds them: the accepted update is superseded and nothing pending can improve on it.
    String committed = RealQueueHandover.LATER;
    try (var handover = new RealQueueHandover(
        tempDir, "DONE", RealQueueHandover.STALE, committed, committed)) {
      handover.reconcile();
      handover.assertHandedOver();
    }
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

  @Test
  void installerEmptyCandidateCanFinalizeWithoutEarningAStamp(@TempDir Path tempDir) throws Exception {
    Switching switching = new Switching(tempDir, "current-model", true);
    try {
      assertTrue(switching.finalizeCandidate(), "installer-only structural admission must succeed");
      assertEquals("SWITCHING", switching.state().migration_state());
      assertEquals(switching.active, switching.state().active_generation());
      verify(switching.server.embeddingCompatController, never()).fingerprintToStamp();
      verify(switching.green.commitOps(), never()).commitWithBuildState(any(), any());
    } finally { switching.stop(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"document", "planned-unit", "issued-claim",
      "pending-row", "processing-row", "running-enumerator", "unfinished-enumerator", "incomplete-walk",
      "uncaptured-walk", "unreadable-count", "upsert", "projection", "projection-source", "replay-uncertain"})
  void installerEmptyExceptionRequiresEachProof(String missing, @TempDir Path tempDir) throws Exception {
    Switching switching = new Switching(tempDir, "current-model", true,
        missing.equals("projection-source") ? List.of("seed") : List.of());
    try {
      switch (missing) {
        case "document" -> when(switching.greenCounts.docCountOrThrow()).thenReturn(1L);
        case "planned-unit" -> when(switching.queue.recordedWalk(OPERATION)).thenReturn(Optional.of(walk(1)));
        case "issued-claim" -> when(switching.queue.hasIssuedRecordedClaims(OPERATION)).thenReturn(true);
        case "pending-row" -> when(switching.queue.jobStateCountsStrict())
            .thenReturn(new JobQueue.JobStateCounts(1, 0, 0, 0, 0));
        case "processing-row" -> when(switching.queue.jobStateCountsStrict())
            .thenReturn(new JobQueue.JobStateCounts(0, 0, 1, 0, 0));
        case "running-enumerator" -> ((AtomicBoolean) field(switching.server, "migrationEnumeratorRunning")).set(true);
        case "unfinished-enumerator" -> setField(switching.server, "migrationEnumeratorDone", false);
        case "incomplete-walk" -> when(switching.queue.recordedWalk(OPERATION))
            .thenReturn(Optional.of(new JobQueue.WalkProgress(OPERATION, SHA, 1, null, null,
                0, 0, 1, null, null, 0, true, null, null)));
        case "uncaptured-walk" -> when(switching.queue.recordedWalk(OPERATION))
            .thenReturn(Optional.of(new JobQueue.WalkProgress(OPERATION, SHA, 1, 1L,
                JobQueue.WalkEnumerationOutcome.COMPLETE, 0, 0, 1, null, null, 0)));
        case "unreadable-count" -> when(switching.greenCounts.docCountOrThrow()).thenThrow(new IOException("unreadable"));
        case "upsert", "projection" -> when(switching.queue.listSwitchBufferOpsStrictForGeneration(switching.building))
            .thenReturn(List.of(new SwitchBufferCapableQueue.SwitchBufferOp(switching.building,
                "obligation", missing.equals("upsert") ? "UPSERT" : "PROJECTION", "{}", 1, "v1")));
        case "projection-source" -> { } // The exact manifest already retains this source obligation.
        case "replay-uncertain" -> switching.admission.markReplayUncertain();
        default -> throw new IllegalArgumentException(missing);
      }
      assertFalse(switching.finalizeCandidate(), "missing proof: " + missing);
      assertEquals(switching.active, switching.state().active_generation());
    } finally { switching.stop(); }
  }

  @Test
  void installerEmptyProofIsNotReusedAfterAnInterveningWrite(@TempDir Path tempDir) throws Exception {
    Switching switching = new Switching(tempDir, "current-model", true);
    try {
      assertTrue(switching.finalizeCandidate());
      when(switching.greenCounts.docCountOrThrow()).thenReturn(1L);
      assertFalse(switching.finalizeCandidate(), "an earlier empty proof grants no later admission");
    } finally { switching.stop(); }
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

  // M5: a removal already reconciled on an empty candidate leaves it provably empty
  @Test
  void anEmptyCandidateWithOnlyACollectionDeleteFailsPromptly(@TempDir Path tempDir)
      throws Exception {
    Switching switching = new Switching(tempDir, "current-model");
    when(switching.queue.listSwitchBufferOpsStrictForGeneration(switching.building)).thenReturn(
        List.of(new SwitchBufferCapableQueue.SwitchBufferOp(switching.building,
            "collection:mcp-ingest", "DELETE_COLLECTION", "mcp-ingest", 1, "v1")));
    switching.start();
    assertEquals("FAILED", switching.awaitStateOtherThanSwitching(5_000));
    assertEquals(switching.active, switching.state().active_generation(), "A stays active");
    switching.stop();
  }

  @Test
  void aCandidateWithAProjectionObligationKeepsWaiting(@TempDir Path tempDir) throws Exception {
    Switching switching = new Switching(tempDir, "current-model");
    when(switching.queue.listSwitchBufferOpsStrictForGeneration(switching.building)).thenReturn(
        List.of(new SwitchBufferCapableQueue.SwitchBufferOp(switching.building,
            "projection:x", "PROJECTION", "{}", 1, "v1")));
    switching.start();
    assertNull(switching.awaitStateOtherThanSwitching(1_500), "SWITCHING must keep waiting");
    switching.stop();
  }

  // Bound: a latched candidate is refused without waiting for its queue or enumeration
  @Test
  void aLatchedSwitchingCandidateWithQueuedWorkFailsPromptly(@TempDir Path tempDir)
      throws Exception {
    Switching switching = new Switching(tempDir, null);
    switching.admission.markReplayUncertain();
    when(switching.queue.queueDepth()).thenReturn(5_000L);
    when(switching.queue.jobStateCounts())
        .thenReturn(new JobQueue.JobStateCounts(5_000, 5_000, 1, 0, 0));
    switching.start();
    assertEquals("FAILED", switching.awaitStateOtherThanSwitching(5_000));
    switching.stop();
  }

  @Test
  void aLatchedMigratingCandidateFailsBeforeItsEnumerationEnds(@TempDir Path tempDir)
      throws Exception {
    Switching switching = new Switching(tempDir, null);
    switching.manager.updateMigrationState(IndexGenerationManager.MigrationState.MIGRATING);
    setField(switching.server, "migrationEnumeratorDone", false);
    when(switching.queue.queueDepth()).thenReturn(5_000L);
    switching.admission.markReplayUncertain();
    switching.start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline
        && "MIGRATING".equals(switching.state().migration_state())) Thread.sleep(25);
    assertEquals("FAILED", switching.state().migration_state());
    assertEquals(switching.active, switching.state().active_generation(), "A stays active");
    switching.stop();
  }

  // Bound: an in-flight mutation (a root walk) delays the early refusal without holding the
  // swap lock, so the Engine's own calls keep answering; the refusal follows once it ends
  @Test
  void anInFlightMutationDelaysTheRefusalWithoutHoldingTheSwapLock(@TempDir Path tempDir)
      throws Exception {
    Switching switching = new Switching(tempDir, null);
    switching.admission.markReplayUncertain();
    when(switching.queue.queueDepth()).thenReturn(5_000L); // the walk's rows are still queued
    when(switching.queue.jobStateCounts())
        .thenReturn(new JobQueue.JobStateCounts(5_000, 5_000, 1, 0, 0));
    var swapLock = (java.util.concurrent.locks.ReentrantLock) field(switching.server,
        "runtimeSwapLock");
    var walk = switching.admission.enter(
        ((DefaultWorkerAppServices) switching.server.appServices).mutationOwnerToken());
    try {
      switching.start();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (System.nanoTime() < end) {
        assertTrue(swapLock.tryLock(1, TimeUnit.SECONDS), "the swap lock stays available");
        swapLock.unlock();
        Thread.sleep(50);
      }
      assertEquals("SWITCHING", switching.state().migration_state());
    } finally {
      walk.close();
    }
    assertEquals("FAILED", switching.awaitStateOtherThanSwitching(5_000));
    switching.stop();
  }

  // preserved: an unlatched migration keeps building while its enumeration runs
  @Test
  void anUnlatchedMigratingCandidateKeepsBuilding(@TempDir Path tempDir) throws Exception {
    Switching switching = new Switching(tempDir, null);
    switching.manager.updateMigrationState(IndexGenerationManager.MigrationState.MIGRATING);
    setField(switching.server, "migrationEnumeratorDone", false);
    switching.start();
    Thread.sleep(1_500);
    assertEquals("MIGRATING", switching.state().migration_state());
    switching.stop();
  }

  // Bound: cleanup retries promptly while the Engine has not yet recorded the refusal
  @Test
  void cleanupRetriesUntilTheEngineRecordsTheRefusal(@TempDir Path tempDir) throws Exception {
    Rollback rollback = new Rollback(tempDir);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(rollback.lifecycle.recordedPrecommitRefused(OPERATION))
        .thenAnswer(ignored -> calls.incrementAndGet() > 5);
    setField(rollback.server, "running", true);
    var start = KnowledgeServer.class.getDeclaredMethod("startMigrationCutoverMonitorBestEffort");
    start.setAccessible(true);
    start.invoke(rollback.server);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline && !rollback.restarted.get()) Thread.sleep(50);
    rollback.assertRolledBackToA();
    setField(rollback.server, "running", false);
    assertTrue(rollback.server.awaitMigrationCutoverExitForTests(10, TimeUnit.SECONDS));
  }

  // M7: a keyword-only empty candidate is not refused as empty
  @Test
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

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object sourceFileWork(Class<?> type, String name) {
    return Enum.valueOf((Class) type, name);
  }

  private static Object field(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
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
    /** The queue's SourceFileWork answer, by name: earlier versions lack the type. */
    final java.util.concurrent.atomic.AtomicReference<String> sourceWork =
        new java.util.concurrent.atomic.AtomicReference<>("NONE");
    final SwitchBufferCapableQueue queue = mock(SwitchBufferCapableQueue.class, invocation ->
        "sourceFileWork".equals(invocation.getMethod().getName())
            ? sourceFileWork(invocation.getMethod().getReturnType(), sourceWork.get())
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

    /**
     * The file changed after A wrote the accepted bytes, so A holding them proves nothing: only
     * A's write of the current bytes would.
     */
    void fileChangedSinceA() throws Exception {
      Files.writeString(file, "changed after the accepted admission");
    }

    /** A's writer ran the file's job and committed its current bytes, which A now holds. */
    void aWritesTheCurrentBytes() throws Exception {
      sourceWork.set("COMMITTED");
      when(fields.getDocumentFieldOrThrow(file.toString(), SchemaFields.SOURCE_SHA256))
          .thenReturn(SourceContentHash.sha256(file));
    }

    /** A FENCED recovery boot: its one producer writes A. */
    void recoveryBoot() throws Exception {
      setField(server, "generationBootDisposition", IndexGenerationManager.BootDisposition.FENCED);
      setField(server, "ingestLifecycle", source);
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
      var publisher = VouchingRootAuthority.publisher();
      when(producer.ingestService()).thenReturn(publisher);
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

  /**
   * A refused candidate whose one journal UPSERT was replaced on the real queue by a later
   * admission of the same file, in {@code laterState}; A's committed source witness is either
   * the accepted admission's predecessor ("stale") or the later admission's bytes ("later").
   */
  private static final class RealQueueHandover implements AutoCloseable {
    final IndexGenerationManager manager;
    final io.justsearch.indexerworker.queue.SqliteJobQueue queue;
    final AtomicBoolean restarted = new AtomicBoolean();
    final String building;
    final KnowledgeServer server;
    final String key;
    final Path database;
    final java.util.concurrent.atomic.AtomicReference<String> onA =
        new java.util.concurrent.atomic.AtomicReference<>();

    /** The later bytes: the file holds them once the later admission is accepted. */
    static final String LATER_BYTES = "later";
    static final String LATER = sha256(LATER_BYTES);
    static final String STALE = "d".repeat(64);

    private static String sha256(String text) {
      try {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    RealQueueHandover(Path tempDir, String laterState, String sourceHolds) throws Exception {
      this(tempDir, laterState, LATER, "DONE".equals(laterState) ? LATER : null,
          "later".equals(sourceHolds) ? LATER : STALE);
    }

    /**
     * The later row carries {@code planned} as its plan and {@code committed} as its written
     * content hash (null when it wrote nothing); A's committed source witness is {@code onA}.
     */
    RealQueueHandover(Path tempDir, String laterState, String planned, String committed,
        String onA) throws Exception {
      var layout = preparedLayout(tempDir);
      var lifecycle = mock(RecordedIngestionLifecycle.class);
      server = new KnowledgeServer(new TestEngineExecutors(),
          WorkerBootFixture.workerConfig(layout.dataDir()), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle);
      manager = Mockito.spy(layout.genManager());
      String active = manager.readStateBestEffort().active_generation();
      building = manager.startRecordedMigration(OPERATION, "recorded-test", "a".repeat(64),
          active, List.of()).building_generation();
      manager.updateMigrationState(IndexGenerationManager.MigrationState.FAILED);
      when(lifecycle.recordedPrecommitRefused(OPERATION)).thenReturn(true);
      Path file = Files.writeString(tempDir.resolve("handed.txt"), "accepted").toAbsolutePath();
      queue = new io.justsearch.indexerworker.queue.SqliteJobQueue(tempDir.resolve("handover.db"));
      queue.open();
      assertTrue(queue.enqueueAndBufferFileForGeneration(
          building, JobQueue.EnqueueEntry.stat(file), null, null));
      key = io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file);
      database = tempDir.resolve("handover.db").toAbsolutePath();
      Files.writeString(file, LATER_BYTES); // A later watcher update, after the accepted one
      assertEquals(LATER, SourceContentHash.sha256(file));
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
          var update = connection.prepareStatement("UPDATE jobs SET state = ?, unit_revision = ?, "
              + "planned_source_sha256 = ?, content_hash = ? WHERE path = ?")) {
        update.setString(1, laterState);
        update.setString(2, "later-revision");
        update.setString(3, planned);
        update.setString(4, committed);
        update.setString(5, key);
        assertEquals(1, update.executeUpdate());
      }
      var source = mock(RunningRuntime.class);
      var fields = mock(DocumentFieldOps.class);
      when(source.documentFieldOps()).thenReturn(fields);
      when(source.commitOps()).thenReturn(mock(CommitOps.class));
      this.onA.set(onA);
      when(fields.getDocumentFieldOrThrow(key, SchemaFields.SOURCE_SHA256))
          .thenAnswer(ignored -> this.onA.get());
      var owner = new Object();
      var producer = mock(DefaultWorkerAppServices.class);
      when(producer.mutationAdmission()).thenReturn(new WorkerMutationAdmission(owner));
      when(producer.mutationOwnerToken()).thenReturn(owner);
      when(producer.pauseProducerForCutover(10_000)).thenReturn(true);
      var publisher = VouchingRootAuthority.publisher();
      when(producer.ingestService()).thenReturn(publisher);
      var closed = new AtomicBoolean();
      Mockito.doAnswer(invocation -> { closed.set(true); return null; }).when(producer).close();
      when(producer.producerClosed()).thenAnswer(ignored -> closed.get());
      server.appServices = producer;
      setField(server, "generationBootOwnership", new IndexGenerationManager.BootOwnership.Recorded(
          OPERATION, active, "recorded-test", "a".repeat(64), true));
      setField(server, "indexGenerationManager", manager);
      setField(server, "activeIndexPath", layout.activePath());
      setField(server, "searchLifecycle", source);
      setField(server, "ingestLifecycle", mock(RunningRuntime.class));
      setField(server, "jobQueue", queue);
      setField(server, "migrationRestartAction", (Runnable) () -> restarted.set(true));
    }

    void reconcile() throws Exception {
      var reconcile = KnowledgeServer.class.getDeclaredMethod("reconcileRefusedRecordedCandidate");
      reconcile.setAccessible(true);
      reconcile.invoke(server);
    }

    int journalSize() {
      return queue.listSwitchBufferOpsStrict().size();
    }

    /** A's writer ran the file's job and committed {@code hash}, which A now holds. */
    void aCommits(String hash) throws Exception {
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
          var update = connection.prepareStatement(
              "UPDATE jobs SET state = 'DONE', content_hash = ? WHERE path = ?")) {
        update.setString(1, hash);
        update.setString(2, key);
        assertEquals(1, update.executeUpdate());
      }
      onA.set(hash);
    }

    /**
     * Green and the journal wait for an A job for the file: a new ordinary one cleanup queued
     * ({@code queuedByCleanup}), or the file's own later admission. Returns its revision.
     */
    String assertWaitingOnAJob(boolean queuedByCleanup) throws Exception {
      assertEquals(1, journalSize(), "the accepted update waits for A's writer");
      assertFalse(restarted.get(), "cleanup has not finished");
      assertEquals("FAILED", manager.readStateBestEffort().migration_state());
      assertEquals(building, manager.readStateBestEffort().building_generation(), "Green is kept");
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
          var query = connection.prepareStatement("SELECT state, unit_revision, scan_id, "
              + "planned_source_sha256 FROM jobs WHERE path = ?")) {
        query.setString(1, key);
        try (var row = query.executeQuery()) {
          assertTrue(row.next());
          assertEquals("PENDING", row.getString("state"), "A's writer runs it");
          assertEquals(!queuedByCleanup, "later-revision".equals(row.getString("unit_revision")),
              queuedByCleanup ? "a new admission" : "the file's own job is not replaced");
          if (queuedByCleanup) {
            assertNull(row.getString("scan_id"));
            assertNull(row.getString("planned_source_sha256"), "the writer reads the file's bytes");
          }
          return row.getString("unit_revision");
        }
      }
    }

    void assertHandedOver() throws Exception {
      assertEquals(0, journalSize(), "the accepted update is acknowledged");
      assertTrue(restarted.get(), "cleanup finished in this process");
      assertEquals("IDLE", manager.readStateBestEffort().migration_state());
    }

    @Override
    public void close() throws Exception {
      queue.close();
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
      this(tempDir, embeddingFingerprint, false);
    }

    Switching(Path tempDir, String embeddingFingerprint, boolean installer) throws Exception {
      this(tempDir, embeddingFingerprint, installer, List.of());
    }

    Switching(Path tempDir, String embeddingFingerprint, boolean installer, List<String> sources) throws Exception {
      var layout = preparedLayout(tempDir);
      server = new KnowledgeServer(new TestEngineExecutors(),
          WorkerBootFixture.workerConfig(layout.dataDir()), null,
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), lifecycle);
      manager = layout.genManager();
      active = manager.readStateBestEffort().active_generation();
      String source = installer ? "installer_model_activation" : "recorded-test";
      String targetFingerprint = installer ? java.util.HexFormat.of().formatHex(java.security.MessageDigest
          .getInstance("SHA-256").digest("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8))) : "a".repeat(64);
      building = manager.startRecordedMigration(OPERATION, source, targetFingerprint,
          active, sources).building_generation();
      manager.updateMigrationState(IndexGenerationManager.MigrationState.SWITCHING);
      when(lifecycle.recordedCutoverReady(OPERATION)).thenReturn(true);
      when(green.indexCountOps()).thenReturn(greenCounts);
      when(green.commitOps()).thenReturn(mock(CommitOps.class));
      when(queue.recordedWalk(OPERATION)).thenReturn(Optional.of(walk(0L)));
      when(queue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
      when(queue.jobStateCountsStrict()).thenAnswer(ignored -> queue.jobStateCounts());
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
          OPERATION, active, source, targetFingerprint, true, true, sources));
      if (installer) {
        var target = new io.justsearch.app.api.operations.IndexTargetSnapshot(targetFingerprint, "fixture");
        var config = io.justsearch.configuration.resolved.ResolvedConfig.builder().build();
        setField(server, "recordedCandidate", new RecordedIngestionLifecycle.RecordedCandidate(config,
            target, java.util.Map.of("embedding", new IndexGenerationManager.ModelArtifact(
                tempDir.resolve("model.onnx").toString(), SHA))));
        setField(server, "candidateEmbeddingCompatController", ecc);
      }
      setField(server, "indexGenerationManager", manager);
      setField(server, "activeIndexPath", layout.activePath());
      setField(server, "buildingIndexPath", manager.resolveGenerationPathStrict(building));
      setField(server, "searchLifecycle", mock(RunningRuntime.class));
      setField(server, "ingestLifecycle", green);
      setField(server, "jobQueue", queue);
      setField(server, "migrationEnumeratorDone", true);
      setField(server, "migrationRestartAction", (Runnable) () -> {});
    }

    boolean finalizeCandidate() throws Exception {
      var method = KnowledgeServer.class.getDeclaredMethod("finalizeOrRefuseRecordedCandidateBeforeCutover");
      method.setAccessible(true);
      return (boolean) method.invoke(server);
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
