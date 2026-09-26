/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionRetryPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real queue claims carry the single current authorization/force decision, without another store. */
final class RecordedWalkForceClaimTest {
  private static final String KEY = "01900000-0000-7000-8000-000000000051";
  private static final String PLAN = "a".repeat(64);
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void issuedSnapshotContainsTheSelectedForceDecision(boolean force) throws Exception {
    var decision = force ? JobQueue.RecordedClaimDecision.ALLOW_FORCE : JobQueue.RecordedClaimDecision.ALLOW;
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), ignored -> decision)) {
      queue.open();
      admit(queue, temp.resolve("first.txt"));
      var claim = queue.pollPending(1).getFirst();
      assertEquals(KEY, claim.scanId());
      assertTrue(claim.walkEpoch() > 0);
      assertEquals(force, claim.recordedForce());
    }
  }

  @Test
  void oneDecisionIsSampledForTheCandidateBeforeConstructingItsClaim() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), ignored ->
        calls.getAndIncrement() == 0 ? JobQueue.RecordedClaimDecision.ALLOW_FORCE : JobQueue.RecordedClaimDecision.DENY)) {
      queue.open();
      admit(queue, temp.resolve("first.txt"));
      var claim = queue.pollPending(1).getFirst();
      assertEquals(1, calls.get(), "authorization and force must not be separate reads");
      assertTrue(claim.recordedForce(), "the issued snapshot is the decision that authorized this claim");
    }
  }

  @Test
  void revocationCannotChangeIssuedForceOrLetACopyReleaseTheOwner() throws Exception {
    AtomicReference<JobQueue.RecordedClaimDecision> decision = new AtomicReference<>(JobQueue.RecordedClaimDecision.ALLOW_FORCE);
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), ignored -> decision.get())) {
      queue.open();
      long epoch = admit(queue, temp.resolve("first.txt"));
      var claim = queue.pollPending(1).getFirst();
      decision.set(JobQueue.RecordedClaimDecision.DENY);
      queue.enqueueRecordedEntries(KEY, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(temp.resolve("second.txt"))), null);
      assertTrue(queue.pollPending(2).isEmpty());
      assertTrue(claim.recordedForce(), "revocation applies to later claims, not this admitted snapshot");
      var copy = new JobQueue.IndexJob(claim.path(), claim.collection(), claim.provenance(),
          claim.scanId(), claim.unitRevision(), claim.walkEpoch(), claim.recordedForce());
      assertEquals(claim, copy);
      assertFalse(queue.markClaimDone(copy, skipped(), null));
      queue.returnUnfinishedClaims(List.of(copy));
      assertTrue(queue.hasIssuedRecordedClaims(KEY), "a value-equal forged object cannot release the real owner");
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(copy, null, "b".repeat(64))), success());
      assertTrue(queue.hasIssuedRecordedClaims(KEY));
      assertEquals(0, queue.recordedWalk(KEY).orElseThrow().completedUnits());
      queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, "b".repeat(64))), success());
      assertFalse(queue.hasIssuedRecordedClaims(KEY));
      assertEquals(1, queue.recordedWalk(KEY).orElseThrow().completedUnits());
    }
  }

  @ParameterizedTest
  @CsvSource({"false,false", "false,true", "true,false", "true,true"})
  void recoveryOnlyMovesTheRowAndActualPollResamplesForce(boolean timed, boolean recoveryForce) throws Exception {
    Path db = temp.resolve("jobs.db");
    try (var beforeCrash = new SqliteJobQueue(db, ignored -> JobQueue.RecordedClaimDecision.ALLOW_FORCE)) {
      beforeCrash.open();
      admit(beforeCrash, temp.resolve("first.txt"));
      assertTrue(beforeCrash.pollPending(1).getFirst().recordedForce());
    }
    AtomicReference<JobQueue.RecordedClaimDecision> decision = new AtomicReference<>(JobQueue.RecordedClaimDecision.DENY);
    AtomicInteger calls = new AtomicInteger();
    try (var reopened = new SqliteJobQueue(db, ignored -> { calls.incrementAndGet(); return decision.get(); })) {
      reopened.open();
      assertEquals(0, timed ? reopened.recoverStuckJobs(0) : reopened.recoverStuckJobs());
      decision.set(recoveryForce ? JobQueue.RecordedClaimDecision.ALLOW_FORCE : JobQueue.RecordedClaimDecision.ALLOW);
      assertEquals(1, timed ? reopened.recoverStuckJobs(0) : reopened.recoverStuckJobs());
      calls.set(0);
      decision.set(recoveryForce ? JobQueue.RecordedClaimDecision.ALLOW : JobQueue.RecordedClaimDecision.ALLOW_FORCE);
      var claim = reopened.pollPending(1).getFirst();
      assertEquals(1, calls.get(), "the recovery probe is not a cached claim decision");
      assertEquals(!recoveryForce, claim.recordedForce());
    }
  }

  @Test
  void legacyClaimCannotCarryRecordedForce() {
    assertThrows(IllegalArgumentException.class, () -> new JobQueue.IndexJob(
        temp.resolve("legacy.txt"), null, null, null, null, null, true));
  }

  @Test
  void nullDecisionFencesRecordedWorkWithoutBlockingLegacyAdmission() throws Exception {
    try (var queue = new SqliteJobQueue(temp.resolve("jobs.db"), ignored -> null)) {
      queue.open();
      admit(queue, temp.resolve("recorded.txt"));
      Path legacy = temp.resolve("legacy.txt");
      queue.enqueueEntries(List.of(JobQueue.EnqueueEntry.ofUnknownSize(legacy)), null);
      var claims = queue.pollPending(10);
      assertEquals(1, claims.size());
      assertEquals(legacy, claims.getFirst().path());
      assertFalse(claims.getFirst().recordedForce());
    }
  }

  private static long admit(SqliteJobQueue queue, Path file) {
    var progress = queue.beginRecordedWalk(KEY, PLAN, true);
    queue.enqueueRecordedEntries(KEY, progress.enumerationEpoch(), List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
    return progress.enumerationEpoch();
  }
  private static IngestionOutcome success() {
    return IngestionOutcome.of(IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS", IngestionRetryPolicy.NONE);
  }
  private static IngestionOutcome skipped() {
    return IngestionOutcome.of(IngestionOutcomeClass.SKIPPED_POLICY, "SKIPPED_POLICY", IngestionRetryPolicy.NONE);
  }
}
