/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.CanonicalOperationArguments;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedParentFixture;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.justsearch.app.api.operations.OperationRecord;
import org.junit.jupiter.api.io.TempDir;

/** Actual runner/store/queue tests; fake enumeration exit stands in for the later bounded client adapter. */
final class RecordedIngestionCoordinatorTest {
  private static final String GENERATION = "coordinator-generation";
  private static final Clock CLOCK = Clock.systemUTC();
  @TempDir Path temp;

  @Test
  void freshRootsShareOneWorkAndAdvanceOnlyAfterDurableChildAcknowledgement() throws Exception {
    try (Fixture f = new Fixture(temp, 2)) {
      List<String> children = new ArrayList<>();
      List<EngineContext> contexts = new ArrayList<>();
      CompletableFuture<JobQueue.WalkEnumerationOutcome> firstExit = new CompletableFuture<>();
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
        children.add(key); contexts.add(context);
        assertEquals(OperationState.RUNNING, f.operations.find(key).orElseThrow().state());
        if (children.size() == 2) {
          var first = f.queue.recordedWalk(children.getFirst()).orElseThrow();
          assertEquals(first.revision(), first.acknowledgedRevision());
        }
        return children.size() == 1 ? firstExit : CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = f.request();
      var accepted = f.accept(request);
      OperationAttemptRunner.Result result;
      try (var work = f.admission.admit(request.context(), false)) {
        result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        assertEquals(1, children.size());
        assertEquals(1, f.admission.activeWorkCount());
        assertFalse(result.completion().toCompletableFuture().isDone());
        firstExit.complete(JobQueue.WalkEnumerationOutcome.COMPLETE);
        f.coordinator.maintain();
        assertEquals(2, children.size());
        assertEquals(work.context().workId(), contexts.getFirst().workId());
        assertEquals(contexts.getFirst().workId(), contexts.getLast().workId());
      }
      f.coordinator.maintain();
      assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS).state());
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void synchronousRootsDoNotLoseTheirWakeupOrNeedTheMaintenanceClock() throws Exception {
    try (Fixture f = new Fixture(temp, 9)) {
      AtomicInteger starts = new AtomicInteger();
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
        starts.incrementAndGet();
        return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        assertEquals(9, starts.get());
        assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
      }
    }
  }

  @Test
  void cancellationRequestDoesNotFinishBeforeActualEnumerationExit() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      List<String> childKeys = new ArrayList<>();
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> { childKeys.add(key); return exit; });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        work.cancel("user cancelled");
        assertFalse(f.coordinator.mayClaimRecorded(childKeys.getFirst()), "cancellation fences claims before maintenance");
        f.coordinator.maintain();
        assertFalse(result.completion().toCompletableFuture().isDone());
        assertEquals(OperationState.RUNNING, f.operations.find(childKeys.getFirst()).orElseThrow().state());
        exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
        f.coordinator.maintain();
        assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS).state());
      }
    }
  }

  @Test
  void terminalChildCatchupRequiresNoProducerOrAdmission() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var request = f.request();
      var parent = f.accept(request).accepted();
      f.operations.start(parent.id());
      String childKey = OperationKeys.generate(CLOCK);
      var child = f.operations.acceptIngestChild(parent.key(), childKey,
          f.operations.acceptedPreparation(parent.id()).orElseThrow(), f.plan).record();
      f.operations.start(child.id());
      var progress = f.queue.beginRecordedWalk(childKey, CanonicalOperationArguments.digest(f.plan.toReplayPayload()), true);
      f.queue.closeRecordedWalkEnumeration(childKey, progress.enumerationEpoch(), JobQueue.WalkEnumerationOutcome.COMPLETE);
      f.queue.trySealRecordedWalk(childKey);
      var receipt = f.queue.sealedRecordedWalkReceipt(childKey).orElseThrow();
      f.operations.checkpoint(child.id(), RecordedIngestionReceipt.cursor(receipt), 0, 0);
      f.operations.finish(child.id(), OperationState.COMPLETE, new OperationReceipt("SUCCESS", null));
      f.attachment.close();
      f.admission.freezeAdmission("receipt-only proof");
      var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX),
          null, new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        org.junit.jupiter.api.Assertions.assertNotNull(attachment);
        coordinator.maintain();
        assertEquals(OperationState.COMPLETE, f.operations.find(parent.key()).orElseThrow().state());
        assertEquals(receipt.revision(), f.queue.recordedWalk(childKey).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount());
      }
    }
  }

  @Test
  void indexReplacementKeepsPendingWorkButDropsFreshCapsuleAuthority() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      AtomicInteger starts = new AtomicInteger();
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
        starts.incrementAndGet();
        cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
        return exit;
      });
      var request = f.request(new OperationAuthorizationBasis.EphemeralCapsule());
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        f.coordinator.stopProducers(1000);
        assertFalse(result.completion().toCompletableFuture().isDone());
        assertTrue(work.cancellationReason().isEmpty());
        f.attachment.close();
        assertFalse(result.completion().toCompletableFuture().isDone());
        f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true);
        f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
          starts.incrementAndGet(); return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        f.coordinator.maintain();
        assertEquals(1, starts.get());
        assertEquals(OperationState.FAILED, result.completion().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        assertEquals("RECOVERY_AUTHORIZATION_REFUSED", f.operations.find(request.key()).orElseThrow().receipt().code());
        assertTrue(work.cancellationReason().isEmpty());
      }
    }
  }

  @Test
  void replacementRevalidatesPersistedParentAndChildInsteadOfCachedAuthority() throws Exception {
    for (String corruption : List.of("parent", "child-payload", "child-attribution")) {
      Path directory = temp.resolve(corruption);
      try (Fixture f = new Fixture(directory, 1)) {
        AtomicInteger starts = new AtomicInteger();
        List<String> children = new ArrayList<>();
        CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
        f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
          starts.incrementAndGet(); children.add(key);
          cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
          return exit;
        });
        var request = f.request();
        var accepted = f.accept(request);
        try (var work = f.admission.admit(request.context(), false)) {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
          f.coordinator.stopProducers(1000);
          f.attachment.close();
          String key = "parent".equals(corruption) ? request.key() : children.getFirst();
          String column = "child-attribution".equals(corruption) ? "client_id" : "preparation_payload";
          corruptOperation(directory.resolve("operations.db"), key, column, "changed-binding");
          f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true);
          f.coordinator.bindProducer((root, childKey, epoch, context, cancellation) -> {
            starts.incrementAndGet(); return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
          });
          assertEquals(OperationState.FAILED, result.completion().toCompletableFuture()
              .get(2, java.util.concurrent.TimeUnit.SECONDS).state(), corruption);
          assertEquals(RecordedIngestionSettlement.UNAVAILABLE, f.operations.find(request.key()).orElseThrow().receipt().code());
          assertEquals(1, starts.get(), "no replacement effect from cached binding: " + corruption);
          assertFalse(f.coordinator.mayClaimRecorded(children.getFirst()));
          assertEquals(0, f.queue.recordedWalk(children.getFirst()).orElseThrow().acknowledgedRevision());
          assertTrue(work.cancellationReason().isEmpty());
        }
      }
    }
  }

  @Test
  void neverStartedProducerSurvivesReplacementInTheSameAttemptAndWork() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertTrue(f.queue.recordedWalk(child.key()).isEmpty());
        f.coordinator.stopProducers(1000);
        f.attachment.close();
        assertFalse(result.completion().toCompletableFuture().isDone());
        f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true);
        f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
          assertEquals(work.context().workId(), context.workId());
          return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        assertEquals(1, f.operations.find(request.key()).orElseThrow().attempts());
        assertEquals(1, f.operations.find(child.key()).orElseThrow().attempts());
      }
    }
  }

  private static void corruptOperation(Path database, String key, String column, String value) throws Exception {
    if (!Set.of("client_id", "preparation_payload").contains(column)) throw new IllegalArgumentException("fixture column");
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
        var update = connection.prepareStatement("UPDATE operations SET " + column + " = ? WHERE operation_key = ?")) {
      update.setString(1, value); update.setString(2, key);
      assertEquals(1, update.executeUpdate());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void bootFailedChildRepairsMissingOrStaleParentCheckpointBeforeFailure(
      boolean staleParentCheckpoint) throws Exception {
    Path directory = temp.resolve(staleParentCheckpoint ? "failed-parent-stale" : "failed-parent-missing");
    try (Fixture f = new Fixture(directory, 1)) {
      SeededChild seeded = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.FAILED);
      if (staleParentCheckpoint) {
        assertTrue(f.operations.checkpoint(seeded.parent().id(), "stale-parent-cursor", 0, 0));
      }
      assertEquals(0, f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());

      RecordedIngestionCoordinator coordinator = restartForReceiptOnly(f);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        org.junit.jupiter.api.Assertions.assertNotNull(attachment);
        coordinator.maintain();

        OperationRecord parent = f.operations.find(seeded.parent().key()).orElseThrow();
        assertEquals(OperationState.FAILED, parent.state());
        assertEquals(new OperationReceipt("INGEST_ENUMERATION_FAILED", null), parent.receipt());
        assertEquals("ingest-parent:1:1", parent.checkpointCursor());
        assertEquals(seeded.receipt().completedUnits(), parent.unitsCompleted());
        assertEquals(seeded.receipt().failedUnits(), parent.unitsFailed());
        assertEquals(2, parent.attempts(), "repair uses one budgeted parent resume");
        assertEquals(seeded.receipt().revision(),
            f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount(), "receipt bookkeeping needs no work slot");
      }
    }
  }

  @Test
  void bootCancelledChildRepairsParentAndPropagatesCancellationWithoutAdmission() throws Exception {
    try (Fixture f = new Fixture(temp.resolve("cancelled-parent"), 1)) {
      SeededChild seeded = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.CANCELLED);
      assertEquals(0, f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());

      RecordedIngestionCoordinator coordinator = restartForReceiptOnly(f);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        org.junit.jupiter.api.Assertions.assertNotNull(attachment);
        coordinator.maintain();

        OperationRecord parent = f.operations.find(seeded.parent().key()).orElseThrow();
        assertEquals(OperationState.CANCELLED, parent.state());
        assertEquals(new OperationReceipt("cancelled", null), parent.receipt());
        assertEquals("ingest-parent:1:1", parent.checkpointCursor());
        assertEquals(seeded.receipt().completedUnits(), parent.unitsCompleted());
        assertEquals(seeded.receipt().failedUnits(), parent.unitsFailed());
        assertEquals(2, parent.attempts(), "repair uses one budgeted parent resume");
        assertEquals(seeded.receipt().revision(),
            f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount(), "receipt bookkeeping needs no work slot");
      }
    }
  }

  @Test
  void bootParentWithMoreConfirmedUnitsThanChildFailsUnavailableAndPreservesCheckpoint() throws Exception {
    try (Fixture f = new Fixture(temp.resolve("parent-confirmed-ahead"), 1)) {
      SeededChild seeded = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.FAILED);
      assertTrue(f.operations.checkpoint(seeded.parent().id(), "already-confirmed-parent", 1, 0));
      assertEquals(0, f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());

      RecordedIngestionCoordinator coordinator = restartForReceiptOnly(f);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        org.junit.jupiter.api.Assertions.assertNotNull(attachment);
        coordinator.maintain();

        OperationRecord parent = f.operations.find(seeded.parent().key()).orElseThrow();
        assertEquals(OperationState.FAILED, parent.state());
        assertEquals(new OperationReceipt(RecordedIngestionSettlement.UNAVAILABLE, null),
            parent.receipt());
        assertEquals("already-confirmed-parent", parent.checkpointCursor());
        assertEquals(1, parent.unitsCompleted());
        assertEquals(0, parent.unitsFailed());
        assertEquals(1, parent.attempts(), "contradictory progress refuses without a new resume");
        assertEquals(seeded.receipt().revision(),
            f.queue.recordedWalk(seeded.child().key()).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount(), "receipt bookkeeping needs no work slot");
      }
    }
  }

  private static SeededChild seedTerminalChild(Fixture f,
      JobQueue.WalkEnumerationOutcome outcome) {
    OperationRecord parent = f.accept(f.request()).accepted();
    assertTrue(f.operations.start(parent.id()));
    String childKey = OperationKeys.generate(CLOCK);
    RecordedRootPlan childPlan = oneRoot(f.plan, 0);
    OperationRecord child = f.operations.acceptIngestChild(parent.key(), childKey,
        f.operations.acceptedPreparation(parent.id()).orElseThrow(), childPlan).record();
    assertTrue(f.operations.start(child.id()));

    var walk = f.queue.beginRecordedWalk(childKey,
        CanonicalOperationArguments.digest(childPlan.toReplayPayload()), true);
    f.queue.closeRecordedWalkEnumeration(childKey, walk.enumerationEpoch(), outcome);
    f.queue.trySealRecordedWalk(childKey);
    JobQueue.SealedWalkReceipt receipt = f.queue.sealedRecordedWalkReceipt(childKey).orElseThrow();
    assertTrue(f.operations.checkpoint(child.id(), RecordedIngestionReceipt.cursor(receipt),
        receipt.completedUnits(), receipt.failedUnits()));
    assertTrue(f.operations.finish(child.id(), RecordedIngestionReceipt.terminalState(receipt),
        RecordedIngestionReceipt.terminalReceipt(receipt)).isPresent());
    assertEquals(0, f.queue.recordedWalk(childKey).orElseThrow().acknowledgedRevision());
    return new SeededChild(parent, child, receipt);
  }

  private static RecordedRootPlan oneRoot(RecordedRootPlan plan, int index) {
    return new RecordedRootPlan(plan.generation(), List.of(plan.roots().get(index)));
  }

  private static RecordedIngestionCoordinator restartForReceiptOnly(Fixture f) throws Exception {
    f.attachment.close();
    f.admission.freezeAdmission("receipt-only parent outcome recovery");
    var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK,
        Set.of(OperationKind.INGEST, OperationKind.REINDEX), null,
        new RecordedIngestPlanResolver());
    return new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
  }

  private record SeededChild(OperationRecord parent, OperationRecord child,
      JobQueue.SealedWalkReceipt receipt) {}

  @Test
  void cancellationFencesPendingClaimsAndParentWaitsForTheExactIssuedOwner() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      List<String> children = new ArrayList<>();
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
        children.add(key);
        f.queue.enqueueRecordedEntries(key, epoch, List.of(
            JobQueue.EnqueueEntry.ofUnknownSize(root.path().resolve("first.txt")),
            JobQueue.EnqueueEntry.ofUnknownSize(root.path().resolve("second.txt"))), null);
        return exit;
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var issued = f.queue.pollPending(1);
        assertEquals(1, issued.size());
        work.cancel("cancel with actual queue owner");
        assertTrue(f.queue.pollPending(1).isEmpty(), "pending second member is denied before maintenance");
        f.coordinator.maintain();
        exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
        assertTrue(f.queue.hasIssuedRecordedClaims(children.getFirst()));
        assertFalse(result.completion().toCompletableFuture().isDone());
        f.queue.returnUnfinishedClaims(issued);
        assertFalse(f.queue.hasIssuedRecordedClaims(children.getFirst()));
        assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        var receipt = f.queue.recordedWalk(children.getFirst()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
      }
    }
  }

  @Test
  void deferredAdmissionIsBoundedAndThawContinuesWithoutFailingAcceptedWork() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      List<OperationRecord> accepted = new ArrayList<>();
      for (int index = 0; index < 3; index++) accepted.add(f.accept(f.request()).accepted());
      f.attachment.close();
      var freeze = f.admission.freezeAdmission("hold ingestion activation");
      var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX),
          null, new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        org.junit.jupiter.api.Assertions.assertNotNull(attachment);
        assertEquals(1, f.operations.find(accepted.get(0).key()).orElseThrow().attempts());
        assertEquals(1, f.operations.find(accepted.get(1).key()).orElseThrow().attempts());
        assertEquals(0, f.operations.find(accepted.get(2).key()).orElseThrow().attempts(), "bounded third parent remains Wait");
        assertEquals(0, f.admission.activeWorkCount());
        AtomicInteger effects = new AtomicInteger();
        coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
          effects.incrementAndGet();
          return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        assertEquals(0, effects.get());
        f.admission.releaseAdmission(freeze.preparationId());
        coordinator.maintain();
        assertEquals(3, effects.get());
        for (OperationRecord row : accepted) assertEquals(OperationState.COMPLETE, f.operations.find(row.key()).orElseThrow().state());
        assertEquals(0, f.admission.activeWorkCount());
      }
    }
  }

  @Test
  void malformedParentCannotAcknowledgeHiddenTerminalChildOrOutrunIssuedSibling() throws Exception {
    try (Fixture f = new Fixture(temp, 2)) {
      SeededChild first = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.COMPLETE);
      assertTrue(f.operations.checkpoint(first.parent().id(), "last-confirmed-parent", 4, 1));
      RecordedRootPlan secondPlan = oneRoot(f.plan, 1);
      String secondKey = OperationKeys.generate(CLOCK);
      var second = f.operations.acceptIngestChild(first.parent().key(), secondKey,
          f.operations.acceptedPreparation(first.parent().id()).orElseThrow(), secondPlan).record();
      f.operations.start(second.id());
      var walk = f.queue.beginRecordedWalk(secondKey, CanonicalOperationArguments.digest(secondPlan.toReplayPayload()), true);
      f.queue.enqueueRecordedEntries(secondKey, walk.enumerationEpoch(),
          List.of(JobQueue.EnqueueEntry.ofUnknownSize(temp.resolve("issued-sibling.txt"))), null);
      f.fixtureClaimOwner.set(true);
      var issued = f.queue.pollPending(1);
      f.fixtureClaimOwner.set(false);
      assertEquals(1, issued.size());
      assertTrue(f.queue.hasIssuedRecordedClaims(secondKey));
      f.attachment.close();
      corruptOperation(temp.resolve("operations.db"), first.parent().key(), "preparation_payload", "lost-parent-binding");
      assertFalse(f.operations.openRecords().stream().anyMatch(row -> row.key().equals(first.child().key())),
          "terminal unacknowledged child is absent from the captured recovery cohort");
      var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX),
          null, new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
      try (var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        org.junit.jupiter.api.Assertions.assertNotNull(attachment);
        assertEquals(OperationState.RUNNING, f.operations.find(first.parent().key()).orElseThrow().state());
        assertEquals(OperationState.RUNNING, f.operations.find(secondKey).orElseThrow().state());
        assertFalse(coordinator.mayClaimRecorded(secondKey));
        f.queue.returnUnfinishedClaims(issued);
        coordinator.maintain();
        var parent = f.operations.find(first.parent().key()).orElseThrow();
        assertEquals(OperationState.FAILED, parent.state());
        assertEquals(RecordedIngestionSettlement.UNAVAILABLE, parent.receipt().code());
        assertEquals("last-confirmed-parent", parent.checkpointCursor());
        assertEquals(4, parent.unitsCompleted()); assertEquals(1, parent.unitsFailed());
        assertEquals(OperationState.FAILED, f.operations.find(secondKey).orElseThrow().state());
        assertEquals(0, f.queue.recordedWalk(first.child().key()).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.queue.recordedWalk(secondKey).orElseThrow().acknowledgedRevision());
        assertEquals(0, f.admission.activeWorkCount());
      }
    }
  }

  @Test
  void contradictoryTerminalChildDoesNotPreventLaterSiblingSettlementAndAcknowledgement() throws Exception {
    try (Fixture f = new Fixture(temp, 2)) {
      SeededChild first = seedTerminalChild(f, JobQueue.WalkEnumerationOutcome.COMPLETE);
      RecordedRootPlan secondPlan = oneRoot(f.plan, 1);
      String secondKey = OperationKeys.generate(CLOCK);
      var second = f.operations.acceptIngestChild(first.parent().key(), secondKey,
          f.operations.acceptedPreparation(first.parent().id()).orElseThrow(), secondPlan).record();
      f.operations.start(second.id());
      f.queue.beginRecordedWalk(secondKey, CanonicalOperationArguments.digest(secondPlan.toReplayPayload()), true);
      f.attachment.close();
      corruptOperation(temp.resolve("operations.db"), first.child().key(), "client_id", "wrong-child-attribution");
      var resumed = new OperationAttemptRunnerImpl(f.operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX),
          null, new RecordedIngestPlanResolver());
      var coordinator = new RecordedIngestionCoordinator(f.operations, resumed, f.admission, f.authority);
      List<String> terminals = new ArrayList<>();
      var subscription = f.operations.subscribeCompletions(row -> terminals.add(row.key()));
      try (subscription; var attachment = coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true)) {
        org.junit.jupiter.api.Assertions.assertNotNull(attachment);
        coordinator.maintain();
        assertEquals(List.of(secondKey, first.parent().key()), terminals);
        assertEquals(RecordedIngestionSettlement.UNAVAILABLE, f.operations.find(first.parent().key()).orElseThrow().receipt().code());
        assertEquals(0, f.queue.recordedWalk(first.child().key()).orElseThrow().acknowledgedRevision());
        var receipt = f.queue.recordedWalk(secondKey).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void finalAttachmentFlushSealsCheckpointsAndCompletesAfterActualIndexDrain(boolean exitsDuringStop) throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      List<String> children = new ArrayList<>();
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
        children.add(key);
        f.queue.enqueueRecordedEntries(key, epoch,
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(root.path().resolve("last.txt"))), null);
        CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
        if (exitsDuringStop) cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.COMPLETE));
        else exit.complete(JobQueue.WalkEnumerationOutcome.COMPLETE);
        return exit;
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var issued = f.queue.pollPending(1).getFirst();
        f.coordinator.stopProducers(1000);
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, f.attachment::close,
            "failed final close retains the attachment while an issued index owner is live");
        f.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(issued, null, "a".repeat(64))),
            io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
        assertFalse(f.queue.hasIssuedRecordedClaims(children.getFirst()));
        assertFalse(result.completion().toCompletableFuture().isDone(), "normal maintenance has already stopped");
        f.attachment.close();
        assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        var receipt = f.queue.recordedWalk(children.getFirst()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        assertEquals(1, f.operations.find(request.key()).orElseThrow().unitsCompleted());
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final SqliteOperationStore operations;
    final SqliteJobQueue queue;
    final java.util.concurrent.atomic.AtomicBoolean fixtureClaimOwner = new java.util.concurrent.atomic.AtomicBoolean();
    final OperationAuthority authority;
    final EngineAdmissionController admission = new EngineAdmissionController(2, 2, 1);
    final OperationAttemptRunnerImpl runner;
    final RecordedIngestionCoordinator coordinator;
    final RecordedRootPlan plan;
    io.justsearch.indexerworker.server.RecordedIngestionLifecycle.Attachment attachment;
    Fixture(Path directory, int roots) throws Exception {
      Files.createDirectories(directory);
      List<RecordedRootPlan.Root> plans = new ArrayList<>();
      for (int index = 0; index < roots; index++) plans.add(new RecordedRootPlan.Root(
          Files.createDirectory(directory.resolve("root-" + index)), null, false, false, List.of(), List.of()));
      plan = new RecordedRootPlan(GENERATION, plans);
      Path authorityDirectory = directory.resolve("authority");
      Files.createDirectories(authorityDirectory);
      Files.writeString(authorityDirectory.resolve("watched_roots.json"),
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
              "schemaVersion", 1, "roots", List.of(java.util.Map.of("path", directory.toAbsolutePath().toString())))));
      authority = OperationAuthority.load(authorityDirectory);
      operations = new SqliteOperationStore(directory.resolve("operations.db"));
      runner = new OperationAttemptRunnerImpl(operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX),
          null, new RecordedIngestPlanResolver());
      coordinator = new RecordedIngestionCoordinator(operations, runner, admission, authority);
      queue = new SqliteJobQueue(directory.resolve("jobs.db"), key -> fixtureClaimOwner.get() || coordinator.mayClaimRecorded(key));
      queue.open();
      attachment = coordinator.attach(queue, () -> Optional.of(GENERATION), () -> true);
    }
    OperationAttemptRunner.Request request() { return request(new OperationAuthorizationBasis.StructuralAuto()); }
    OperationAttemptRunner.Request request(OperationAuthorizationBasis basis) {
      EngineContext context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "coordinator-test",
          Optional.empty(), Optional.of(basis.encode()), TransportTag.SYSTEM_INTERNAL,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      return new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.AGENT, Instant.now(CLOCK), Optional.empty()));
    }
    OperationAttemptRunner.PreparedAttempt accept(OperationAttemptRunner.Request request) {
      var preparation = RecordedParentFixture.prepare(request, plan);
      runner.savePreparation(request, preparation);
      return runner.acceptPrepared(request, preparation.nonce());
    }
    @Override public void close() throws Exception {
      coordinator.stopProducers(1000);
      attachment.close();
      queue.close();
      operations.close();
    }
  }
}
