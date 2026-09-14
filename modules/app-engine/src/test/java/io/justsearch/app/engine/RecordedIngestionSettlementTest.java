/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.concurrent.CompletableFuture;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real store/runner/queue coverage for receipt-only recorded-ingestion recovery. */
final class RecordedIngestionSettlementTest {
  private static final String PLAN = "a".repeat(64);
  private static final String CHANGED_PLAN = "b".repeat(64);
  private static final Clock KEY_CLOCK = Clock.fixed(
      Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);
  private static final EngineContext CONTEXT = new EngineContext(
      EngineContext.ClientKind.INTERNAL, "recorded-settlement", Optional.empty(), Optional.empty(),
      "system", "SYSTEM_INTERNAL", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
  private static final OperationDescriptor DESCRIPTOR = OperationDescriptor.invocation(
      OperationKind.INGEST, "core.ingest-files", "{}", false);

  @TempDir Path temp;

  @Test
  void validSealedReceiptsSettleCompleteFailedAndCancelledAndAckAfterTerminal() throws Exception {
    for (JobQueue.WalkEnumerationOutcome outcome : JobQueue.WalkEnumerationOutcome.values()) {
      Path db = temp.resolve("settle-" + outcome.name().toLowerCase(java.util.Locale.ROOT) + ".db");
      try (var operations = new SqliteOperationStore(temp.resolve(
          "operations-" + outcome.name().toLowerCase(java.util.Locale.ROOT) + ".db"));
          var queue = new SqliteJobQueue(db, ignored -> true)) {
        queue.open();
        String key = OperationKeys.generate(KEY_CLOCK);
        JobQueue.SealedWalkReceipt sealed = sealEmptyWalk(queue, key, outcome);
        operations.accept(key, DESCRIPTOR, CONTEXT, null);
        var settlement = new RecordedIngestionSettlement(operations, queue);
        var runner = newRunner(operations);

        assertFalse(settlement.acknowledge(key, PLAN), "nonterminal operation cannot acknowledge");
        runner.reconcile(OperationKind.INGEST,
            row -> settlement.reconcile(row, PLAN, true));

        OperationRecord terminal = operations.find(key).orElseThrow();
        assertEquals(expectedState(outcome), terminal.state());
        assertEquals(expectedReceipt(outcome), terminal.receipt());
        assertEquals(1, terminal.attempts(), "a missing checkpoint uses one recovery resume");
        assertTrue(settlement.acknowledge(key, PLAN));
        assertEquals(sealed.revision(), queue.recordedWalk(key).orElseThrow().acknowledgedRevision());
      }
    }
  }

  @Test
  void exactPreexistingCheckpointFinishesWithoutAnotherAttempt() throws Exception {
    try (var operations = new SqliteOperationStore(temp.resolve("checkpoint-operations.db"));
        var queue = new SqliteJobQueue(temp.resolve("checkpoint-jobs.db"), ignored -> true)) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      JobQueue.SealedWalkReceipt sealed = sealEmptyWalk(queue, key,
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      OperationRecord accepted = operations.accept(key, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(operations.start(accepted.id()));
      assertTrue(operations.checkpoint(accepted.id(), RecordedIngestionReceipt.cursor(sealed),
          sealed.completedUnits(), sealed.failedUnits()));
      int attemptsBefore = operations.find(key).orElseThrow().attempts();
      var runner = newRunner(operations);
      var settlement = new RecordedIngestionSettlement(operations, queue);

      runner.reconcile(OperationKind.INGEST, row -> settlement.reconcile(row, PLAN, true));

      OperationRecord terminal = operations.find(key).orElseThrow();
      assertEquals(OperationState.COMPLETE, terminal.state());
      assertEquals(attemptsBefore, terminal.attempts());
      assertTrue(settlement.acknowledge(key, PLAN));
    }
  }

  @Test
  void thirdDurableAttemptSettlesWithCurrentHandleWithoutSpendingFourthAttempt() throws Exception {
    Path operationsPath = temp.resolve("third-attempt-operations.db");
    Path jobsPath = temp.resolve("third-attempt-jobs.db");
    try (var operations = new SqliteOperationStore(operationsPath);
        var queue = new SqliteJobQueue(jobsPath, ignored -> true)) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      JobQueue.SealedWalkReceipt sealed = sealEmptyWalk(queue, key,
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      OperationRecord accepted = operations.accept(key, DESCRIPTOR, CONTEXT, null).record();
      CompletableFuture<OperationResult> pending = new CompletableFuture<>();

      // Each fresh runner represents the next durable recovery attempt after the prior body
      // remained RUNNING. The first two bodies deliberately retain their private stages.
      var firstSettlement = new RecordedIngestionSettlement(operations, queue);
      var firstRunner = newRunner(operations);
      firstRunner.reconcile(OperationKind.INGEST, row -> {
        assertEquals(0, row.attempts());
        assertInstanceOf(OperationAttemptRunner.Reconciliation.Resume.class,
            firstSettlement.reconcile(row, PLAN, true));
        return new OperationAttemptRunner.Reconciliation.Resume(handle ->
            new OperationExecution(OperationResult.success("running"), pending));
      });
      assertEquals(1, operations.find(key).orElseThrow().attempts());

      var secondSettlement = new RecordedIngestionSettlement(operations, queue);
      var secondRunner = newRunner(operations);
      secondRunner.reconcile(OperationKind.INGEST, row -> {
        assertEquals(1, row.attempts());
        assertInstanceOf(OperationAttemptRunner.Reconciliation.Resume.class,
            secondSettlement.reconcile(row, PLAN, true));
        return new OperationAttemptRunner.Reconciliation.Resume(handle ->
            new OperationExecution(OperationResult.success("running"), pending));
      });
      assertEquals(2, operations.find(key).orElseThrow().attempts());

      var thirdSettlement = new RecordedIngestionSettlement(operations, queue);
      var thirdRunner = newRunner(operations);
      thirdRunner.reconcile(OperationKind.INGEST, row -> {
        assertEquals(2, row.attempts());
        return new OperationAttemptRunner.Reconciliation.Resume(handle -> {
          OperationRecord current = operations.find(key).orElseThrow();
          assertEquals(3, current.attempts(), "the private body owns the third durable attempt");
          assertEquals(accepted.id(), handle.id());
          assertEquals(key, handle.key());
          // Pass the post-resume row: settleRunning must accept the current attempt at the
          // ceiling while still refusing a new reconcile-driven repair (tested below).
          return thirdSettlement.settleRunning(current, PLAN, true, handle).orElseThrow();
        });
      });

      OperationRecord terminal = operations.find(key).orElseThrow();
      assertEquals(3, terminal.attempts());
      assertEquals(OperationState.COMPLETE, terminal.state());
      assertEquals(new OperationReceipt("SUCCESS", null), terminal.receipt());
      assertEquals(RecordedIngestionReceipt.cursor(sealed), terminal.checkpointCursor());
      assertEquals(sealed.completedUnits(), terminal.unitsCompleted());
      assertEquals(sealed.failedUnits(), terminal.unitsFailed());
      assertTrue(thirdSettlement.acknowledge(key, PLAN));
      assertEquals(sealed.revision(), queue.recordedWalk(key).orElseThrow().acknowledgedRevision());
    }
  }

  @Test
  void exhaustedRecoveryFailsWithoutCheckpointAndCannotAcknowledge() throws Exception {
    try (var operations = new SqliteOperationStore(temp.resolve("exhausted-operations.db"));
        var queue = new SqliteJobQueue(temp.resolve("exhausted-jobs.db"), ignored -> true)) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      sealEmptyWalk(queue, key, JobQueue.WalkEnumerationOutcome.COMPLETE);
      OperationRecord accepted = operations.accept(key, DESCRIPTOR, CONTEXT, null).record();
      setAttempts(temp.resolve("exhausted-operations.db"), accepted.id(),
          OperationAttemptRunner.MAX_DURABLE_ATTEMPTS);
      var settlement = new RecordedIngestionSettlement(operations, queue);
      var runner = newRunner(operations);

      runner.reconcile(OperationKind.INGEST, row -> settlement.reconcile(row, PLAN, true));

      OperationRecord terminal = operations.find(key).orElseThrow();
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals(RecordedIngestionSettlement.EXHAUSTED, terminal.receipt().code());
      assertNull(terminal.checkpointCursor());
      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> settlement.acknowledge(key, PLAN));
      assertEquals(0, queue.recordedWalk(key).orElseThrow().acknowledgedRevision());
    }
  }

  @Test
  void missingAndCorruptQueueEvidenceWaitForExitAndOwnerDrainThenFailUnavailable() throws Exception {
    assertUnavailableAfterDrain(false);
    assertUnavailableAfterDrain(true);
  }

  @Test
  void changedPlanBetweenDecisionAndResumeBodyBecomesUnavailable() throws Exception {
    Path operationsPath = temp.resolve("changed-plan-operations.db");
    Path jobsPath = temp.resolve("changed-plan-jobs.db");
    try (var operations = new SqliteOperationStore(operationsPath);
        var queue = new SqliteJobQueue(jobsPath, ignored -> true)) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      var walk = queue.beginRecordedWalk(key, PLAN, true);
      queue.closeRecordedWalkEnumeration(key, walk.enumerationEpoch(),
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      operations.accept(key, DESCRIPTOR, CONTEXT, null);
      var settlement = new RecordedIngestionSettlement(operations, queue);
      var runner = newRunner(operations);
      var subscription = queue.subscribeRecordedWalks(notified -> {
        if (key.equals(notified)) updatePlan(jobsPath, key, CHANGED_PLAN);
      });
      try (subscription) {
        assertDoesNotThrow(() -> runner.reconcile(OperationKind.INGEST,
            row -> settlement.reconcile(row, PLAN, true)));
      }

      OperationRecord terminal = operations.find(key).orElseThrow();
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals(RecordedIngestionSettlement.UNAVAILABLE, terminal.receipt().code());
      assertNull(terminal.checkpointCursor());
      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> settlement.acknowledge(key, PLAN));
    }
  }

  @Test
  void mismatchingTerminalReceiptCannotAcknowledgeTheSealedWalk() throws Exception {
    Path operationsPath = temp.resolve("mismatch-operations.db");
    Path jobsPath = temp.resolve("mismatch-jobs.db");
    try (var operations = new SqliteOperationStore(operationsPath);
        var queue = new SqliteJobQueue(jobsPath, ignored -> true)) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      JobQueue.SealedWalkReceipt sealed = sealEmptyWalk(queue, key,
          JobQueue.WalkEnumerationOutcome.COMPLETE);
      OperationRecord accepted = operations.accept(key, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(operations.finish(accepted.id(), OperationState.COMPLETE,
          new OperationReceipt("OTHER", null)).isPresent());
      var settlement = new RecordedIngestionSettlement(operations, queue);

      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> settlement.acknowledge(key, PLAN));
      assertEquals(0, queue.recordedWalk(key).orElseThrow().acknowledgedRevision());
      assertEquals(sealed.revision(), queue.recordedWalk(key).orElseThrow().revision());
    }
  }

  @Test
  void terminalWriteFailureCannotAcknowledgeAndRunnerSignalIsSticky() throws Exception {
    Path operationsPath = temp.resolve("write-failure-operations.db");
    Path jobsPath = temp.resolve("write-failure-jobs.db");
    try (var operations = new SqliteOperationStore(operationsPath);
        var queue = new SqliteJobQueue(jobsPath, ignored -> true)) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      sealEmptyWalk(queue, key, JobQueue.WalkEnumerationOutcome.COMPLETE);
      operations.accept(key, DESCRIPTOR, CONTEXT, null);
      var settlement = new RecordedIngestionSettlement(operations, queue);
      var runner = newRunner(operations);
      execute(operationsPath, "CREATE TRIGGER refuse_terminal_write BEFORE UPDATE OF state ON operations "
          + "WHEN NEW.state = 'COMPLETE' BEGIN SELECT RAISE(ABORT, 'terminal fixture'); END");
      try {
        assertThrows(RuntimeException.class, () -> runner.reconcile(OperationKind.INGEST,
            row -> settlement.reconcile(row, PLAN, true)));
        OperationRecord unresolved = operations.find(key).orElseThrow();
        assertEquals(OperationState.RUNNING, unresolved.state());
        assertFalse(settlement.acknowledge(key, PLAN));
        var failure = runner.persistenceFailure().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(key, failure.operationKey());
        assertEquals(OperationState.COMPLETE, failure.intendedState());
        assertSame(failure, runner.persistenceFailure().toCompletableFuture().get(5,
            java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        execute(operationsPath, "DROP TRIGGER IF EXISTS refuse_terminal_write");
      }
    }
  }


  @Test
  void unavailableEvidenceWaitsForEnumerationEvenWithoutIssuedClaims() throws Exception {
    try (var operations = new SqliteOperationStore(temp.resolve("enumeration-operations.db"));
        var queue = new SqliteJobQueue(temp.resolve("enumeration-jobs.db"))) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      operations.accept(key, DESCRIPTOR, CONTEXT, null);
      assertFalse(queue.hasIssuedRecordedClaims(key));
      assertTrue(queue.recordedWalk(key).isEmpty());
      var settlement = new RecordedIngestionSettlement(operations, queue);
      var runner = newRunner(operations);
      runner.reconcile(OperationKind.INGEST,
          row -> assertWait(settlement.reconcile(row, PLAN, false)));
      assertEquals(OperationState.ACCEPTED, operations.find(key).orElseThrow().state());
      runner.reconcile(OperationKind.INGEST, row -> settlement.reconcile(row, PLAN, true));
      assertEquals(RecordedIngestionSettlement.UNAVAILABLE,
          operations.find(key).orElseThrow().receipt().code());
    }
  }

  @Test
  void unavailablePlanWaitsForIssuedClaimEvenAfterEnumerationExit() throws Exception {
    Path jobsPath = temp.resolve("issued-jobs.db");
    var permitted = new java.util.concurrent.atomic.AtomicBoolean(true);
    try (var operations = new SqliteOperationStore(temp.resolve("issued-operations.db"));
        var queue = new SqliteJobQueue(jobsPath, ignored -> permitted.get())) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      operations.accept(key, DESCRIPTOR, CONTEXT, null);
      var walk = queue.beginRecordedWalk(key, PLAN, true);
      queue.enqueueRecordedEntries(key, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(temp.resolve("issued.txt"))), null);
      var claim = queue.pollPending(1).getFirst();
      permitted.set(false);
      // The projection remains readable for the exact claim return, but its binding is unavailable.
      updatePlan(jobsPath, key, CHANGED_PLAN);
      var settlement = new RecordedIngestionSettlement(operations, queue);
      var runner = newRunner(operations);
      runner.reconcile(OperationKind.INGEST,
          row -> assertWait(settlement.reconcile(row, PLAN, true)));
      assertEquals(OperationState.ACCEPTED, operations.find(key).orElseThrow().state());
      assertTrue(queue.hasIssuedRecordedClaims(key));
      queue.returnUnfinishedClaims(List.of(claim));
      assertFalse(queue.hasIssuedRecordedClaims(key));
      runner.reconcile(OperationKind.INGEST, row -> settlement.reconcile(row, PLAN, true));
      assertEquals(RecordedIngestionSettlement.UNAVAILABLE,
          operations.find(key).orElseThrow().receipt().code());
      assertEquals(0, queue.recordedWalk(key).orElseThrow().acknowledgedRevision());
    }
  }

  @Test
  void validReceiptCannotDecreaseConfirmedCounters() throws Exception {
    try (var operations = new SqliteOperationStore(temp.resolve("decreased-operations.db"));
        var queue = new SqliteJobQueue(temp.resolve("decreased-jobs.db"))) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      var accepted = operations.accept(key, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(operations.start(accepted.id()));
      assertTrue(operations.checkpoint(accepted.id(), "confirmed-position", 4, 1));
      sealEmptyWalk(queue, key, JobQueue.WalkEnumerationOutcome.COMPLETE);
      var settlement = new RecordedIngestionSettlement(operations, queue);
      var runner = newRunner(operations);
      assertDoesNotThrow(() -> runner.reconcile(OperationKind.INGEST,
          row -> settlement.reconcile(row, PLAN, true)));
      var terminal = operations.find(key).orElseThrow();
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals(RecordedIngestionSettlement.UNAVAILABLE, terminal.receipt().code());
      assertEquals("confirmed-position", terminal.checkpointCursor());
      assertEquals(4, terminal.unitsCompleted());
      assertEquals(1, terminal.unitsFailed());
      assertEquals(1, terminal.attempts());
      assertEquals(0, queue.recordedWalk(key).orElseThrow().acknowledgedRevision());
    }
  }

  @Test
  void validReceiptBytesChangedBetweenDecisionAndBodyCannotCheckpoint() throws Exception {
    Path jobsPath = temp.resolve("changed-receipt-jobs.db");
    try (var operations = new SqliteOperationStore(temp.resolve("changed-receipt-operations.db"));
        var queue = new SqliteJobQueue(jobsPath)) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      var accepted = operations.accept(key, DESCRIPTOR, CONTEXT, null).record();
      var original = sealEmptyWalk(queue, key, JobQueue.WalkEnumerationOutcome.COMPLETE);
      var settlement = new RecordedIngestionSettlement(operations, queue);
      var decision = settlement.reconcile(accepted, PLAN, true);
      assertInstanceOf(OperationAttemptRunner.Reconciliation.Resume.class, decision);
      updateReceiptJson(jobsPath, key, " " + queue.recordedWalk(key).orElseThrow().receiptJson());
      var changed = queue.sealedRecordedWalkReceipt(key).orElseThrow();
      assertEquals(original.revision(), changed.revision());
      assertFalse(original.sha256().equals(changed.sha256()), "exact stored bytes changed");
      var runner = newRunner(operations);
      runner.reconcile(OperationKind.INGEST, ignored -> decision);
      var terminal = operations.find(key).orElseThrow();
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals(RecordedIngestionSettlement.UNAVAILABLE, terminal.receipt().code());
      assertNull(terminal.checkpointCursor());
      assertEquals(0, queue.recordedWalk(key).orElseThrow().acknowledgedRevision());
    }
  }

  private void assertUnavailableAfterDrain(boolean corrupt) throws Exception {
    String label = corrupt ? "corrupt" : "missing";
    Path operationsPath = temp.resolve(label + "-evidence-operations.db");
    Path jobsPath = temp.resolve(label + "-evidence-jobs.db");
    try (var operations = new SqliteOperationStore(operationsPath);
        var queue = new SqliteJobQueue(jobsPath, ignored -> true)) {
      queue.open();
      String key = OperationKeys.generate(KEY_CLOCK);
      var walk = queue.beginRecordedWalk(key, PLAN, true);
      Path path = temp.resolve(label + ".txt");
      queue.enqueueRecordedEntries(key, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(path)), null);
      JobQueue.IndexJob claim = queue.pollPending(1).getFirst();
      OperationRecord accepted = operations.accept(key, DESCRIPTOR, CONTEXT, null).record();
      assertTrue(operations.start(accepted.id()));
      assertTrue(operations.checkpoint(accepted.id(), "preexisting-cursor", 4, 1));
      var settlement = new RecordedIngestionSettlement(operations, queue);
      var runner = newRunner(operations);

      runner.reconcile(OperationKind.INGEST,
          row -> assertWait(settlement.reconcile(row, PLAN, false)));
      assertEquals(OperationState.RUNNING, operations.find(key).orElseThrow().state());
      runner.reconcile(OperationKind.INGEST,
          row -> assertWait(settlement.reconcile(row, PLAN, true)));
      assertTrue(queue.hasIssuedRecordedClaims(key));

      assertTrue(queue.markClaimDone(claim, io.justsearch.indexerworker.ingest.IngestionOutcome.of(
          io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SKIPPED_POLICY,
          "SKIPPED_POLICY", io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE), null));
      assertFalse(queue.hasIssuedRecordedClaims(key));
      if (corrupt) {
        queue.closeRecordedWalkEnumeration(key, walk.enumerationEpoch(),
            JobQueue.WalkEnumerationOutcome.COMPLETE);
        assertTrue(queue.trySealRecordedWalk(key).sealedAt() != null);
        updateReceiptJson(jobsPath, key, "not-json");
      } else {
        execute(jobsPath, "DELETE FROM ingestion_walk_progress WHERE operation_key = '" + key + "'");
      }

      runner.reconcile(OperationKind.INGEST,
          row -> settlement.reconcile(row, PLAN, true));
      OperationRecord terminal = operations.find(key).orElseThrow();
      assertEquals(OperationState.FAILED, terminal.state());
      assertEquals(RecordedIngestionSettlement.UNAVAILABLE, terminal.receipt().code());
      assertEquals("preexisting-cursor", terminal.checkpointCursor());
      assertEquals(4, terminal.unitsCompleted());
      assertEquals(1, terminal.unitsFailed());
      assertThrows(JobQueue.RecordedWalkGapException.class,
          () -> settlement.acknowledge(key, PLAN));
    }
  }

  private static OperationAttemptRunnerImpl newRunner(SqliteOperationStore operations) {
    return new OperationAttemptRunnerImpl(operations, Clock.systemUTC(), Set.of(OperationKind.INGEST),
        null, null);
  }

  private static JobQueue.SealedWalkReceipt sealEmptyWalk(SqliteJobQueue queue, String key,
      JobQueue.WalkEnumerationOutcome outcome) {
    var walk = queue.beginRecordedWalk(key, PLAN, true);
    queue.closeRecordedWalkEnumeration(key, walk.enumerationEpoch(), outcome);
    queue.trySealRecordedWalk(key);
    return queue.sealedRecordedWalkReceipt(key).orElseThrow();
  }

  private static OperationState expectedState(JobQueue.WalkEnumerationOutcome outcome) {
    return switch (outcome) {
      case COMPLETE -> OperationState.COMPLETE;
      case FAILED -> OperationState.FAILED;
      case CANCELLED -> OperationState.CANCELLED;
    };
  }

  private static OperationReceipt expectedReceipt(JobQueue.WalkEnumerationOutcome outcome) {
    return switch (outcome) {
      case COMPLETE -> new OperationReceipt("SUCCESS", null);
      case FAILED -> new OperationReceipt("INGEST_ENUMERATION_FAILED", null);
      case CANCELLED -> new OperationReceipt("cancelled", null);
    };
  }

  private static OperationAttemptRunner.Reconciliation.Wait assertWait(
      OperationAttemptRunner.Reconciliation decision) {
    return assertInstanceOf(OperationAttemptRunner.Reconciliation.Wait.class, decision);
  }

  private static void setAttempts(Path path, long id, int attempts) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var update = connection.prepareStatement("UPDATE operations SET attempts = ? WHERE id = ?")) {
      update.setInt(1, attempts);
      update.setLong(2, id);
      assertEquals(1, update.executeUpdate());
    }
  }

  private static void updatePlan(Path path, String key, String planHash) {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var update = connection.prepareStatement(
            "UPDATE ingestion_walk_progress SET plan_hash = ? WHERE operation_key = ?")) {
      update.setString(1, planHash);
      update.setString(2, key);
      update.executeUpdate();
    } catch (Exception failure) {
      throw new AssertionError("plan mutation fixture failed", failure);
    }
  }

  private static void updateReceiptJson(Path path, String key, String receipt) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var update = connection.prepareStatement(
            "UPDATE ingestion_walk_progress SET receipt_json = ? WHERE operation_key = ?")) {
      update.setString(1, receipt);
      update.setString(2, key);
      assertEquals(1, update.executeUpdate());
    }
  }

  private static void execute(Path path, String sql) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
