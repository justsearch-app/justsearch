/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.CanonicalOperationArguments;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.executor.RecordedParentFixture;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.ReindexHandler;
import io.justsearch.core.context.EngineContext;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
        assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(childKeys.getFirst()), "cancellation fences claims before maintenance");
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
          assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(children.getFirst()));
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
            JobQueue.EnqueueEntry.ofUnknownSize(root.roots().getFirst().path().resolve("first.txt")),
            JobQueue.EnqueueEntry.ofUnknownSize(root.roots().getFirst().path().resolve("second.txt"))), null);
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
        assertEquals(JobQueue.RecordedClaimDecision.DENY, coordinator.recordedClaimDecision(secondKey));
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
            List.of(JobQueue.EnqueueEntry.ofUnknownSize(root.roots().getFirst().path().resolve("last.txt"))), null);
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
        assertThrows(java.io.IOException.class, f.attachment::close,
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

  @Test
  void cancellationBeforeFirstEnumerationPersistsAnEmptyCancelledReceipt() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertTrue(f.queue.recordedWalk(child.key()).isEmpty());
        work.cancel("cancel before any producer exists");
        assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(child.key()));
        f.coordinator.maintain();
        assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        var terminal = f.operations.find(child.key()).orElseThrow();
        assertEquals(OperationState.CANCELLED, terminal.state());
        assertEquals(0, terminal.unitsCompleted());
        assertEquals(0, terminal.unitsFailed());
        var receipt = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        assertTrue(f.queue.pollPending(1).isEmpty());
        AtomicInteger starts = new AtomicInteger();
        f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
          starts.incrementAndGet(); return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        assertEquals(0, starts.get(), "later producer binding cannot resurrect cancelled work");
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void cancellationBetweenAuthorizationAndTokenPublicationCannotStartTheProducer() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      f.attachment.close();
      JobQueue bridge = org.mockito.Mockito.mock(JobQueue.class,
          org.mockito.AdditionalAnswers.delegatesTo(f.queue));
      f.attachment = f.coordinator.attach(bridge, () -> Optional.of(GENERATION), () -> true);
      var request = f.request();
      var accepted = f.accept(request);
      AtomicInteger starts = new AtomicInteger();
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
        starts.incrementAndGet();
        cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
        return exit;
      });
      try (var work = f.admission.admit(request.context(), false)) {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<Void> cancellationResult = new CompletableFuture<>();
        Thread canceller = Thread.ofPlatform().start(() -> {
          try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            work.cancel("cancel before producer token publication");
            cancellationResult.complete(null);
          } catch (Throwable failure) { cancellationResult.completeExceptionally(failure); }
          finally { cancelled.countDown(); }
        });
        org.mockito.Mockito.doAnswer(invocation -> {
          var progress = f.queue.beginRecordedWalk(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
          entered.countDown();
          assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS), "cancellation must not wait for coordinator lock");
          cancellationResult.join();
          return progress;
        }).when(bridge).beginRecordedWalk(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyBoolean());
        try {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
          assertEquals(0, starts.get(), "cancelled context cannot start a producer after token publication");
          f.coordinator.maintain();
          assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture()
              .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
          var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
          assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(child.key()));
          var receipt = f.queue.recordedWalk(child.key()).orElseThrow();
          assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        } finally {
          exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
          entered.countDown();
          canceller.join(3000);
          assertFalse(canceller.isAlive());
        }
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void synchronousProducerRejectionSettlesChildBeforeParentFailure() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
        throw new java.util.concurrent.RejectedExecutionException("bounded producer queue full");
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        assertEquals(OperationState.FAILED, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertEquals(OperationState.FAILED, child.state(), "producer rejection must not orphan the accepted child");
        assertEquals("INGEST_ENUMERATION_FAILED", child.receipt().code());
        var receipt = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        assertEquals("INGEST_ENUMERATION_FAILED", f.operations.find(request.key()).orElseThrow().receipt().code());
        assertEquals(JobQueue.RecordedClaimDecision.DENY, f.coordinator.recordedClaimDecision(child.key()));
      }
      assertEquals(0, f.admission.activeWorkCount());
    }
  }

  @Test
  void cancellationCannotRecreateProgressAfterAnEnumerationHasStarted() throws Exception {
    try (Fixture f = new Fixture(temp, 1)) {
      CompletableFuture<JobQueue.WalkEnumerationOutcome> exit = new CompletableFuture<>();
      f.coordinator.bindProducer((root, key, epoch, context, cancellation) -> {
        cancellation.onCancel(() -> exit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED));
        return exit;
      });
      var request = f.request();
      var accepted = f.accept(request);
      try (var work = f.admission.admit(request.context(), false)) {
        var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
        var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
        assertTrue(f.queue.recordedWalk(child.key()).isPresent());
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("jobs.db"));
            var delete = connection.prepareStatement("DELETE FROM ingestion_walk_progress WHERE operation_key = ?")) {
          delete.setString(1, child.key());
          assertEquals(1, delete.executeUpdate());
        }
        work.cancel("cancel after recorded progress was lost");
        f.coordinator.maintain();
        assertEquals(OperationState.FAILED, result.completion().toCompletableFuture()
            .get(2, java.util.concurrent.TimeUnit.SECONDS).state());
        assertEquals(RecordedIngestionSettlement.UNAVAILABLE, f.operations.find(request.key()).orElseThrow().receipt().code());
        assertEquals(RecordedIngestionSettlement.UNAVAILABLE, f.operations.find(child.key()).orElseThrow().receipt().code());
        assertTrue(f.queue.recordedWalk(child.key()).isEmpty(), "lost evidence cannot be rebuilt as an empty cancellation");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reopenedQueueClaimsForceOnlyFromTheRecoveredPersistedParentPlan(boolean force) throws Exception {
    try (Fixture f = new Fixture(temp, 1, force)) {
      var parent = f.accept(f.request()).accepted();
      assertTrue(f.operations.start(parent.id()));
      String childKey = OperationKeys.generate(CLOCK);
      var child = f.operations.acceptIngestChild(parent.key(), childKey,
          f.operations.acceptedPreparation(parent.id()).orElseThrow(), f.plan).record();
      assertTrue(f.operations.start(child.id()));
      Path file = f.plan.roots().getFirst().path().resolve("recorded.txt");
      var walk = f.queue.beginRecordedWalk(childKey, CanonicalOperationArguments.digest(f.plan.toReplayPayload()), true);
      f.queue.enqueueRecordedEntries(childKey, walk.enumerationEpoch(), List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
      f.attachment.close();
      f.queue.close();
      var recoveredRunner = new OperationAttemptRunnerImpl(f.operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX), null, new RecordedIngestPlanResolver());
      var recovered = new RecordedIngestionCoordinator(f.operations, recoveredRunner, f.admission, f.authority);
      try (var reopened = new SqliteJobQueue(temp.resolve("jobs.db"), recovered::recordedClaimDecision)) {
        reopened.open();
        assertTrue(reopened.pollPending(1).isEmpty(), "reopen does not remember the old runtime permission");
        try (var attachment = recovered.attach(reopened, () -> Optional.of(GENERATION), () -> true)) {
          org.junit.jupiter.api.Assertions.assertNotNull(attachment);
          recovered.bindProducer((root, key, epoch, context, cancellation) -> {
            assertEquals(force, root.roots().getFirst().force(), "producer receives the persisted plan, not a new public flag");
            reopened.enqueueRecordedEntries(key, epoch, List.of(JobQueue.EnqueueEntry.ofUnknownSize(file)), null);
            return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
          });
          var claim = reopened.pollPending(1).getFirst();
          assertEquals(force, claim.recordedForce());
          assertEquals(childKey, claim.scanId());
          assertEquals(1, f.admission.activeWorkCount(), "recovered children share the admitted parent work");
          reopened.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(claim, null, "d".repeat(64))),
              io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                  io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                  io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
          recovered.maintain();
          assertEquals(OperationState.COMPLETE, f.operations.find(parent.key()).orElseThrow().state());
          assertEquals(0, f.admission.activeWorkCount());
          var receipt = reopened.recordedWalk(childKey).orElseThrow();
          assertEquals(receipt.revision(), receipt.acknowledgedRevision());
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualEngineProducerJournalsAndAcknowledgesARealFilesystemRoot(boolean singleFile) throws Exception {
    var generations = new io.justsearch.indexerworker.index.IndexGenerationManager(temp.resolve("index"));
    var layout = generations.initializeOrLoad();
    String generation = layout.activeGenerationPath().getFileName().toString();
    try (Fixture f = new Fixture(temp.resolve("fixture"), 1, true, generation, singleFile);
        var executors = new DefaultEngineExecutorRegistry()) {
      var runtime = org.mockito.Mockito.mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(f.queue, null, null,
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), layout.basePath(),
          layout.activeGenerationPath(), runtime, runtime, null, 0L);
      var services = org.mockito.Mockito.mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
      org.mockito.Mockito.when(services.ingestService()).thenReturn(service);
      Path file = singleFile ? f.plan.roots().getFirst().path()
          : Files.writeString(f.plan.roots().getFirst().path().resolve("probe.txt"), "recorded producer probe");
      var enumerated = new java.util.concurrent.CountDownLatch(1);
      try (var client = new EngineKnowledgeClient(executors, () -> services,
          new ForegroundLoadGate(new io.justsearch.indexerworker.loop.pacing.ForegroundLoad()),
          5_000, 100, io.justsearch.app.services.worker.IpcTelemetry.noop(), () -> {}, f.admission, f.authority.roots());
          var _ = f.queue.subscribeRecordedWalks(key -> {
            if (f.queue.recordedWalk(key).orElseThrow().enumerationClosedAt() != null) enumerated.countDown();
          })) {
        f.coordinator.bindProducer(client::enumerateRecordedRoot);
        var request = f.request();
        var accepted = f.accept(request);
        try (var work = f.admission.admit(request.context(), false)) {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
          assertTrue(enumerated.await(5, java.util.concurrent.TimeUnit.SECONDS), "real walk must reach enumeration closure");
          var child = f.operations.findIngestChild(request.key(), f.plan).orElseThrow();
          var progress = f.queue.recordedWalk(child.key()).orElseThrow();
          assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, progress.enumerationOutcome());
          assertFalse(result.completion().toCompletableFuture().isDone(), "enumeration is not index completion");
          var jobs = f.queue.pollPending(10);
          assertEquals(1, jobs.size(), "only the frozen file was admitted");
          var issued = jobs.getFirst();
          assertEquals(file, issued.path());
          assertEquals(child.key(), issued.scanId());
          assertEquals(progress.enumerationEpoch(), issued.walkEpoch());
          assertTrue(issued.recordedForce(), "force is supplied by recorded claim authority");
          assertEquals(new JobQueue.EnqueueProvenance("system", "SYSTEM_INTERNAL"), issued.provenance());
          f.queue.markDoneTransitions(List.of(new JobQueue.IngestionLedgerTransition(issued, null, "e".repeat(64))),
              io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                  io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                  io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
          var completed = result.completion().toCompletableFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
          assertEquals(OperationState.COMPLETE, completed.state());
          assertEquals(1, completed.unitsCompleted());
          var sealed = f.queue.recordedWalk(child.key()).orElseThrow();
          assertEquals(sealed.revision(), sealed.acknowledgedRevision());
        }
        f.coordinator.stopProducers(1_000);
        assertEquals(0, f.admission.activeWorkCount());
      }
    }
  }

  private enum RegisteredDispatchCase {
    FILE_INGEST,
    DIRECTORY_INGEST,
    DIRECTORY_REINDEX,
    FORCED_DIRECTORY_REINDEX
  }

  private enum RegisteredRecoveryRefusalCase {
    INGEST_STALE_GENERATION(RegisteredDispatchCase.DIRECTORY_INGEST,
        "replacement-generation", false, "RECOVERY_GENERATION_MISMATCH"),
    REINDEX_STALE_GENERATION(RegisteredDispatchCase.DIRECTORY_REINDEX,
        "replacement-generation", false, "RECOVERY_GENERATION_MISMATCH"),
    INGEST_REVOKED_AUTHORITY(RegisteredDispatchCase.DIRECTORY_INGEST,
        GENERATION, true, "RECOVERY_AUTHORIZATION_REFUSED"),
    REINDEX_REVOKED_AUTHORITY(RegisteredDispatchCase.DIRECTORY_REINDEX,
        GENERATION, true, "RECOVERY_AUTHORIZATION_REFUSED");

    final RegisteredDispatchCase dispatchCase;
    final String servingGeneration;
    final boolean revokeAuthority;
    final String refusalCode;

    RegisteredRecoveryRefusalCase(RegisteredDispatchCase dispatchCase, String servingGeneration,
        boolean revokeAuthority, String refusalCode) {
      this.dispatchCase = dispatchCase;
      this.servingGeneration = servingGeneration;
      this.revokeAuthority = revokeAuthority;
      this.refusalCode = refusalCode;
    }
  }

  @ParameterizedTest
  @EnumSource(RegisteredDispatchCase.class)
  void registeredPreparedHandlersReachTheActualProducerAndRetryOneRecordedIdentity(
      RegisteredDispatchCase scenario) throws Exception {
    boolean ingest = scenario == RegisteredDispatchCase.FILE_INGEST
        || scenario == RegisteredDispatchCase.DIRECTORY_INGEST;
    boolean singleFile = scenario == RegisteredDispatchCase.FILE_INGEST;
    boolean force = scenario == RegisteredDispatchCase.FORCED_DIRECTORY_REINDEX;
    Path scenarioDirectory = temp.resolve(scenario.name().toLowerCase(java.util.Locale.ROOT));
    var generations = new io.justsearch.indexerworker.index.IndexGenerationManager(
        scenarioDirectory.resolve("index"));
    var layout = generations.initializeOrLoad();
    String generation = layout.activeGenerationPath().getFileName().toString();
    try (Fixture f = new Fixture(scenarioDirectory.resolve("fixture"), 1, false, generation, singleFile);
        var executors = new DefaultEngineExecutorRegistry()) {
      Path target = f.plan.roots().getFirst().path();
      Path enumeratedFile = singleFile ? target
          : Files.writeString(target.resolve("frozen.txt"), "frozen recorded target");
      Path replacement = Files.createDirectory(scenarioDirectory.resolve("replacement-root"));
      Files.writeString(replacement.resolve("replacement.txt"), "must not be enumerated");
      Path outside = Files.writeString(scenarioDirectory.resolve("outside.txt"), "outside frozen target");
      var roots = new AtomicReference<>(List.of(new RootBinding(
          singleFile ? target.getParent() : target, "documents")));
      var currentGeneration = new AtomicReference<>(generation);
      var exclusions = new AtomicReference<List<String>>(List.of());
      AtomicInteger preparations = new AtomicInteger();
      AtomicInteger producerCalls = new AtomicInteger();
      var handlers = new HandlerRegistry();
      if (ingest) {
        handlers.register(AgentToolsOperationCatalog.INGEST_FILES,
            new IngestTool(f.coordinator, context -> {
              preparations.incrementAndGet();
              return roots.get();
            }, context -> currentGeneration.get(), exclusions::get));
      } else {
        handlers.register(CoreOperationCatalog.REINDEX,
            new ReindexHandler(f.coordinator, context -> {
              preparations.incrementAndGet();
              return roots.get();
            }, context -> currentGeneration.get(), exclusions::get));
      }
      var dispatcher = new OperationExecutorImpl(f.runner, f.admission, handlers, null, Map.of(),
          CLOCK, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog());
      var operation = ingest
          ? new AgentToolsOperationCatalog().findByIdValue(
              AgentToolsOperationCatalog.INGEST_FILES.value()).orElseThrow()
          : new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.REINDEX.value()).orElseThrow();
      String arguments = ingest
          ? tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
              Map.of("paths", List.of(target.toString()), "collection", "documents"))
          : "{\"force\":" + force + "}";
      String changedArguments = ingest
          ? tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
              Map.of("paths", List.of(replacement.toString()), "collection", "documents"))
          : "{\"force\":" + !force + "}";
      var seed = f.request();
      String operationKey = OperationKeys.generate(CLOCK);
      var enumerated = new java.util.concurrent.CountDownLatch(1);
      var parentCompletion = new CompletableFuture<OperationRecord>();
      var runtime = org.mockito.Mockito.mock(io.justsearch.adapters.lucene.runtime.RunningRuntime.class);
      // This is the production filesystem enumerator and queue journal. The controlled terminal
      // transition below is deliberately not evidence of a Lucene write or flush.
      var service = new io.justsearch.indexerworker.services.WorkerIngestService(f.queue, null, null,
          io.justsearch.indexerworker.loop.pacing.IndexingPacing.unthrottled(), layout.basePath(),
          layout.activeGenerationPath(), runtime, runtime, null, 0L);
      var services = org.mockito.Mockito.mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
      org.mockito.Mockito.when(services.ingestService()).thenReturn(service);
      try (var client = new EngineKnowledgeClient(executors, () -> services,
          new ForegroundLoadGate(new io.justsearch.indexerworker.loop.pacing.ForegroundLoad()),
          5_000, 100, io.justsearch.app.services.worker.IpcTelemetry.noop(), () -> {}, f.admission,
          f.authority.roots());
          var _ = f.queue.subscribeRecordedWalks(key -> {
            if (f.queue.recordedWalk(key).orElseThrow().enumerationClosedAt() != null) {
              enumerated.countDown();
            }
          });
          var _ = f.operations.subscribeCompletions(row -> {
            if (operationKey.equals(row.key())) parentCompletion.complete(row);
          })) {
        f.coordinator.bindProducer((acceptedPlan, childKey, epoch, context, cancellation) -> {
          producerCalls.incrementAndGet();
          var parent = f.operations.find(operationKey).orElseThrow();
          var preparation = f.operations.acceptedPreparation(parent.id()).orElseThrow();
          RecordedRootPlan frozen = new RecordedIngestPlanResolver().resolve(parent, preparation);
          var child = f.operations.findIngestChild(operationKey, frozen).orElseThrow();
          assertEquals(OperationState.RUNNING, parent.state());
          assertEquals(OperationState.RUNNING, child.state());
          assertEquals(frozen, RecordedRootPlan.fromReplayPayload(
              f.operations.acceptedPreparation(child.id()).orElseThrow().payload().value()));
          assertEquals(frozen, acceptedPlan);
          assertEquals(parent.context().withWorkId(context.workId().orElseThrow()), context);
          return client.enumerateRecordedRoot(acceptedPlan, childKey, epoch, context, cancellation);
        });
        var first = dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(),
            seed.context(), operationKey);
        assertTrue(first.success());
        assertEquals(operationKey, first.structuredData().get("operationKey"));
        long parentId = ((Number) first.structuredData().get("operationRecordId")).longValue();
        assertEquals(1, preparations.get());
        assertEquals(1, producerCalls.get());

        roots.set(List.of(new RootBinding(replacement, "replacement")));
        currentGeneration.set("replacement-generation");
        exclusions.set(List.of("**/frozen.txt"));
        var runningRetry = dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(),
            seed.context(), operationKey);
        assertTrue(runningRetry.success());
        assertEquals(parentId, ((Number) runningRetry.structuredData().get("operationRecordId")).longValue());
        assertEquals(1, preparations.get(), "running retry cannot resample preparation suppliers");
        assertEquals(1, producerCalls.get(), "running retry cannot start another producer");
        var conflict = assertThrows(OperationStoreException.class,
            () -> dispatcher.dispatch(operation, changedArguments, seed.provenance(), Optional.empty(),
                seed.context(), operationKey));
        assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED, conflict.code());
        assertEquals(1, preparations.get());
        assertEquals(1, producerCalls.get());

        assertTrue(enumerated.await(5, java.util.concurrent.TimeUnit.SECONDS),
            "real filesystem enumeration must close");
        var parent = f.operations.find(operationKey).orElseThrow();
        var frozen = new RecordedIngestPlanResolver().resolve(parent,
            f.operations.acceptedPreparation(parent.id()).orElseThrow());
        assertEquals(generation, frozen.generation());
        assertEquals(target.toAbsolutePath().normalize(), frozen.roots().getFirst().path());
        assertEquals(force, frozen.roots().getFirst().force());
        var child = f.operations.findIngestChild(operationKey, frozen).orElseThrow();
        var progress = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(JobQueue.WalkEnumerationOutcome.COMPLETE, progress.enumerationOutcome());
        assertFalse(f.operations.find(operationKey).orElseThrow().state().terminal(),
            "enumeration alone cannot complete the recorded parent");
        var jobs = f.queue.pollPending(10);
        assertEquals(1, jobs.size(), "only the frozen target is enumerated");
        var issued = jobs.getFirst();
        assertEquals(enumeratedFile.toAbsolutePath().normalize(), issued.path());
        assertFalse(issued.path().equals(outside));
        assertFalse(issued.path().startsWith(replacement));
        assertEquals(force, issued.recordedForce());
        assertEquals(child.key(), issued.scanId());
        assertTrue(f.queue.pollPending(10).isEmpty(), "retry cannot duplicate queue work");

        f.queue.markDoneTransitions(List.of(
            new JobQueue.IngestionLedgerTransition(issued, null, "f".repeat(64))),
            io.justsearch.indexerworker.ingest.IngestionOutcome.of(
                io.justsearch.indexerworker.ingest.IngestionOutcomeClass.SUCCESS_FULL, "SUCCESS",
                io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE));
        OperationRecord completed = parentCompletion.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(OperationState.COMPLETE, completed.state());
        assertEquals(1, completed.unitsCompleted());
        assertEquals(0, completed.unitsFailed());
        var completedChild = f.operations.find(child.key()).orElseThrow();
        assertEquals(OperationState.COMPLETE, completedChild.state());
        assertEquals(1, completedChild.unitsCompleted());
        assertEquals(0, completedChild.unitsFailed());
        var sealed = f.queue.sealedRecordedWalkReceipt(child.key()).orElseThrow();
        assertEquals(1, sealed.completedUnits());
        assertEquals(0, sealed.failedUnits());
        var acknowledged = f.queue.recordedWalk(child.key()).orElseThrow();
        assertEquals(sealed.revision(), acknowledged.acknowledgedRevision());

        var terminalRetry = dispatcher.dispatch(operation, arguments, seed.provenance(),
            Optional.empty(), seed.context(), operationKey);
        assertTrue(terminalRetry.success());
        assertEquals(parentId,
            ((Number) terminalRetry.structuredData().get("operationRecordId")).longValue());
        assertEquals(1, preparations.get(), "terminal retry cannot resample preparation suppliers");
        assertEquals(1, producerCalls.get(), "terminal retry cannot start another producer");
        assertTrue(f.queue.pollPending(10).isEmpty(), "terminal retry cannot duplicate queue work");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(RegisteredRecoveryRefusalCase.class)
  void registeredPreparedRecoveryRefusesStaleGenerationAndRevokedAuthorityWithoutEffect(
      RegisteredRecoveryRefusalCase scenario) throws Exception {
    boolean ingest = scenario.dispatchCase == RegisteredDispatchCase.DIRECTORY_INGEST;
    Path scenarioDirectory = temp.resolve(scenario.name().toLowerCase(java.util.Locale.ROOT));
    try (Fixture f = new Fixture(scenarioDirectory, 1)) {
      Path target = f.plan.roots().getFirst().path();
      AtomicInteger preparations = new AtomicInteger();
      AtomicInteger producerCalls = new AtomicInteger();
      AtomicBoolean oldProducerExited = new AtomicBoolean();
      var handlers = new HandlerRegistry();
      if (ingest) {
        handlers.register(AgentToolsOperationCatalog.INGEST_FILES,
            new IngestTool(f.coordinator, context -> {
              preparations.incrementAndGet();
              return List.of(new RootBinding(target, "documents"));
            }, context -> GENERATION, List::of));
      } else {
        handlers.register(CoreOperationCatalog.REINDEX,
            new ReindexHandler(f.coordinator, context -> {
              preparations.incrementAndGet();
              return List.of(new RootBinding(target, "documents"));
            }, context -> GENERATION, List::of));
      }
      var dispatcher = new OperationExecutorImpl(f.runner, f.admission, handlers, null, Map.of(),
          CLOCK, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog());
      var operation = ingest
          ? new AgentToolsOperationCatalog().findByIdValue(
              AgentToolsOperationCatalog.INGEST_FILES.value()).orElseThrow()
          : new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.REINDEX.value()).orElseThrow();
      String arguments = ingest
          ? tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(
              Map.of("paths", List.of(target.toString()), "collection", "documents"))
          : "{\"force\":false}";
      if (ingest) {
        f.authority.grants().grantAllowAlways(operation.id().value(), SourceTier.UNTRUSTED);
        dispatcher.setDurableGrantStore(f.authority.grants(), f.authority.scope());
      }
      String operationKey = OperationKeys.generate(CLOCK);
      var producerExit = new CompletableFuture<JobQueue.WalkEnumerationOutcome>();
      f.coordinator.bindProducer((plan, childKey, epoch, context, cancellation) -> {
        producerCalls.incrementAndGet();
        cancellation.onCancel(() -> {
          oldProducerExited.set(true);
          producerExit.complete(JobQueue.WalkEnumerationOutcome.CANCELLED);
        });
        return producerExit;
      });

      var seed = f.mcpRequest();
      var response = dispatcher.dispatch(operation, arguments, seed.provenance(), Optional.empty(),
          seed.context(), operationKey);
      assertTrue(response.success());
      assertEquals(1, preparations.get());
      assertEquals(1, producerCalls.get());
      var parentBefore = f.operations.find(operationKey).orElseThrow();
      var parentPreparation = f.operations.acceptedPreparation(parentBefore.id()).orElseThrow();
      var frozenPlan = new RecordedIngestPlanResolver().resolve(parentBefore, parentPreparation);
      var childBefore = f.operations.findIngestChild(operationKey, frozenPlan).orElseThrow();
      var childPreparation = f.operations.acceptedPreparation(childBefore.id()).orElseThrow();
      OperationAuthorizationBasis acceptedBasis = ingest
          ? new OperationAuthorizationBasis.OperationGrant(operation.id().value(), SourceTier.UNTRUSTED)
          : new OperationAuthorizationBasis.StructuralAuto();
      assertEquals(Optional.of(acceptedBasis.encode()), parentBefore.context().grantReference());
      assertEquals(SourceTier.UNTRUSTED.name(), parentBefore.context().sourceTier());
      assertEquals(TransportTag.MCP.name(), parentBefore.context().transport());
      assertTrue(f.authority.evaluateRecordedIngest(parentBefore, parentPreparation,
          Optional.of(GENERATION), ignored -> true)
          instanceof OperationAuthority.RecordedIngestRecoveryDecision.Authorized,
          "the dispatcher-selected grant must authorize recovery before revocation");

      f.coordinator.stopProducers(1_000);
      assertTrue(oldProducerExited.get(), "the old producer must exit before replacement");
      f.attachment.close();
      if (scenario.revokeAuthority) f.authority.hardStop().engage();
      var parentCompletion = new CompletableFuture<OperationRecord>();
      try (var _ = f.operations.subscribeCompletions(row -> {
        if (operationKey.equals(row.key())) parentCompletion.complete(row);
      })) {
        f.attachment = f.coordinator.attach(f.queue,
            () -> Optional.of(scenario.servingGeneration), () -> true);
        f.coordinator.bindProducer((plan, childKey, epoch, context, cancellation) -> {
          producerCalls.incrementAndGet();
          return CompletableFuture.completedFuture(JobQueue.WalkEnumerationOutcome.COMPLETE);
        });
        OperationRecord refused = parentCompletion.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(OperationState.FAILED, refused.state());
        assertEquals(scenario.refusalCode, refused.receipt().code());
      }

      var parentAfter = f.operations.find(operationKey).orElseThrow();
      var childAfter = f.operations.find(childBefore.key()).orElseThrow();
      assertEquals(parentBefore.id(), parentAfter.id());
      assertEquals(childBefore.id(), childAfter.id());
      assertEquals(parentPreparation,
          f.operations.acceptedPreparation(parentAfter.id()).orElseThrow());
      assertEquals(childPreparation,
          f.operations.acceptedPreparation(childAfter.id()).orElseThrow());
      assertEquals(1, preparations.get(), "recovery cannot invoke handler preparation");
      assertEquals(1, producerCalls.get(), "replacement producer cannot run refused work");
      assertTrue(f.queue.pollPending(10).isEmpty());
      assertFalse(f.queue.hasIssuedRecordedClaims(childAfter.key()));
      assertEquals(0, parentAfter.unitsCompleted());
      assertEquals(0, parentAfter.unitsFailed());
      assertEquals(0, childAfter.unitsCompleted());
      assertEquals(0, childAfter.unitsFailed());
      var progress = f.queue.recordedWalk(childAfter.key()).orElseThrow();
      assertEquals(0, progress.completedUnits());
      assertEquals(0, progress.failedUnits());
      f.queue.sealedRecordedWalkReceipt(childAfter.key()).ifPresent(receipt -> {
        assertEquals(0, receipt.completedUnits(), "an empty receipt is not an index effect");
        assertEquals(0, receipt.failedUnits(), "an empty receipt is not an index effect");
      });
    }
  }

  @Test
  void actualProducerReplacementWaitsForExitWithoutCancellingDurableParent() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (Fixture f = new Fixture(temp, 1); var executors = new DefaultEngineExecutorRegistry()) {
      var firstService = org.mockito.Mockito.mock(io.justsearch.indexerworker.services.WorkerIngestService.class);
      var seen = new AtomicReference<io.justsearch.indexerworker.services.CallContext>();
      org.mockito.Mockito.doAnswer(call -> {
        seen.set(call.getArgument(2));
        entered.countDown();
        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
        call.<java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress>>getArgument(1).accept(
            io.justsearch.ipc.ScanRootProgress.newBuilder().setComplete(true).setTerminalReasonCode("CLIENT_CANCELLED").build());
        return null;
      }).when(firstService).scanRecordedRoot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      var services = org.mockito.Mockito.mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
      org.mockito.Mockito.when(services.ingestService()).thenReturn(firstService);
      var current = new AtomicReference<>(services);
      var firstExit = new AtomicReference<java.util.concurrent.CompletionStage<JobQueue.WalkEnumerationOutcome>>();
      try (var client = new EngineKnowledgeClient(executors, current::get,
          new ForegroundLoadGate(new io.justsearch.indexerworker.loop.pacing.ForegroundLoad()), 5_000, 100,
          io.justsearch.app.services.worker.IpcTelemetry.noop(), () -> {}, f.admission, f.authority.roots())) {
        f.coordinator.bindProducer((plan, key, epoch, context, token) -> {
          var exit = client.enumerateRecordedRoot(plan, key, epoch, context, token);
          firstExit.set(exit);
          return exit;
        });
        var request = f.request();
        var accepted = f.accept(request);
        try (var work = f.admission.admit(request.context(), false)) {
          var result = f.runner.start(accepted, handle -> f.coordinator.execute(handle, work.context()));
          assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
          assertThrows(java.io.IOException.class, () -> f.coordinator.stopProducers(10));
          assertTrue(seen.get().cancelled());
          assertTrue(work.cancellationReason().isEmpty(), "physical replacement must retain durable parent authority");
          assertFalse(result.completion().toCompletableFuture().isDone());
          assertThrows(java.io.IOException.class, f.attachment::close);
          release.countDown();
          assertEquals(JobQueue.WalkEnumerationOutcome.CANCELLED,
              firstExit.get().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS));
          f.coordinator.stopProducers(1_000);
          f.attachment.close();
          assertFalse(result.completion().toCompletableFuture().isDone(), "same parent remains pending across replacement");
          var replacement = org.mockito.Mockito.mock(io.justsearch.indexerworker.services.WorkerIngestService.class);
          org.mockito.Mockito.doAnswer(call -> {
            call.<java.util.function.Consumer<io.justsearch.ipc.ScanRootProgress>>getArgument(1).accept(
                io.justsearch.ipc.ScanRootProgress.newBuilder().setComplete(true).build());
            return null;
          }).when(replacement).scanRecordedRoot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
          var replacementServices = org.mockito.Mockito.mock(io.justsearch.indexerworker.server.WorkerAppServices.class);
          org.mockito.Mockito.when(replacementServices.ingestService()).thenReturn(replacement);
          current.set(replacementServices);
          f.attachment = f.coordinator.attach(f.queue, () -> Optional.of(GENERATION), () -> true);
          f.coordinator.bindProducer(client::enumerateRecordedRoot);
          assertEquals(OperationState.COMPLETE, result.completion().toCompletableFuture().get(3, java.util.concurrent.TimeUnit.SECONDS).state());
          assertEquals(1, f.operations.find(request.key()).orElseThrow().attempts());
          org.mockito.Mockito.verify(replacement).scanRecordedRoot(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
      }
    } finally { release.countDown(); }
  }

  private static final class Fixture implements AutoCloseable {
    final SqliteOperationStore operations;
    final SqliteJobQueue queue;
    final AtomicBoolean fixtureClaimOwner = new AtomicBoolean();
    final OperationAuthority authority;
    final EngineAdmissionController admission = new EngineAdmissionController(2, 2, 1);
    final OperationAttemptRunnerImpl runner;
    final RecordedIngestionCoordinator coordinator;
    final RecordedRootPlan plan;
    io.justsearch.indexerworker.server.RecordedIngestionLifecycle.Attachment attachment;
    Fixture(Path directory, int roots) throws Exception { this(directory, roots, false); }
    Fixture(Path directory, int roots, boolean force) throws Exception {
      this(directory, roots, force, GENERATION, false);
    }
    Fixture(Path directory, int roots, boolean force, String generation, boolean singleFile) throws Exception {
      Files.createDirectories(directory);
      List<RecordedRootPlan.Root> plans = new ArrayList<>();
      for (int index = 0; index < roots; index++) plans.add(new RecordedRootPlan.Root(
          singleFile ? Files.writeString(directory.resolve("root-" + index + ".txt"), "recorded producer probe")
              : Files.createDirectory(directory.resolve("root-" + index)),
          null, force, singleFile, List.of(), List.of()));
      plan = new RecordedRootPlan(generation, plans);
      Path authorityDirectory = directory.resolve("authority");
      Files.createDirectories(authorityDirectory);
      Files.writeString(authorityDirectory.resolve("watched_roots.json"),
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
              "schemaVersion", 1, "roots", List.of(Map.of("path", directory.toAbsolutePath().toString())))));
      authority = OperationAuthority.load(authorityDirectory);
      operations = new SqliteOperationStore(directory.resolve("operations.db"));
      runner = new OperationAttemptRunnerImpl(operations, CLOCK, Set.of(OperationKind.INGEST, OperationKind.REINDEX),
          null, new RecordedIngestPlanResolver());
      coordinator = new RecordedIngestionCoordinator(operations, runner, admission, authority);
      queue = new SqliteJobQueue(directory.resolve("jobs.db"), key -> fixtureClaimOwner.get() ? JobQueue.RecordedClaimDecision.ALLOW : coordinator.recordedClaimDecision(key));
      queue.open();
      attachment = coordinator.attach(queue, () -> Optional.of(generation), () -> true);
    }
    OperationAttemptRunner.Request request() { return request(new OperationAuthorizationBasis.StructuralAuto()); }
    OperationAttemptRunner.Request mcpRequest() {
      EngineContext context = EngineProvenance.context(EngineContext.ClientKind.MCP_CLIENT,
          "coordinator-test", Optional.empty(), Optional.empty(), TransportTag.MCP,
          EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
      return new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false), context,
          EngineProvenance.invocation(context, ExecutorTag.AGENT, Instant.now(CLOCK), Optional.empty()));
    }
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
