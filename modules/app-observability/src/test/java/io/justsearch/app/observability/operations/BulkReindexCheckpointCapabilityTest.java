/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.BulkReindexProgress;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BulkReindexCheckpointCapabilityTest {
  private final OperationTestClock clock =
      new OperationTestClock(Instant.parse("2026-09-17T10:20:00Z").toEpochMilli());

  @TempDir Path temp;

  @Test
  void onlyTheIssuingRunnerCanCheckpointItsLiveHandle() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("capabilities.db"), clock, ignored -> {})) {
      var owner = new OperationAttemptRunnerImpl(store, clock, Set.of(OperationKind.REINDEX));
      var otherRunner = new OperationAttemptRunnerImpl(store, clock, Set.of(OperationKind.REINDEX));
      var ownerRequest = request();
      var otherRequest = request();
      var ownerAttempt = owner.accept(ownerRequest);
      var foreignAttempt = otherRunner.accept(otherRequest);
      var ownerHandleRef = new AtomicReference<OperationRecordHandle>();
      var foreignHandleRef = new AtomicReference<OperationRecordHandle>();
      var ownerEffect = new CompletableFuture<OperationResult>();
      var foreignEffect = new CompletableFuture<OperationResult>();

      var ownerRun = owner.start(ownerAttempt, handle -> {
        ownerHandleRef.set(handle);
        return new OperationExecution(OperationResult.success("started"), ownerEffect);
      });
      var foreignRun = otherRunner.start(foreignAttempt, handle -> {
        foreignHandleRef.set(handle);
        return new OperationExecution(OperationResult.success("started"), foreignEffect);
      });
      OperationRecordHandle ownerHandle = ownerHandleRef.get();
      OperationRecordHandle foreignHandle = foreignHandleRef.get();
      assertFalse(ownerRun.completion().toCompletableFuture().isDone(),
          "the real handle remains live while its effect is asynchronous");

      var target = target("{\"index\":\"owner\"}");
      var progress = capturing(ownerHandle.key(), target);
      OperationRecordHandle forged = new OperationRecordHandle() {
        @Override public long id() { return ownerHandle.id(); }
        @Override public String key() { return ownerHandle.key(); }
        @Override public void checkpoint(String cursor, long completed, long failed) {}
      };
      assertThrows(IllegalArgumentException.class, () -> owner.checkpointBulkReindex(forged, progress));
      assertThrows(IllegalArgumentException.class, () -> owner.checkpointBulkReindex(foreignHandle,
          capturing(foreignHandle.key(), target)));
      assertFalse(owner.persistenceFailure().toCompletableFuture().isDone(),
          "invalid capabilities are refused before they can signal a storage failure");

      owner.checkpointBulkReindex(ownerHandle, progress);
      assertEquals(Optional.of(progress), store.bulkReindexProgress(ownerHandle.id()));
      assertEquals(OperationState.RUNNING, store.find(ownerHandle.key()).orElseThrow().state());

      ownerEffect.complete(OperationResult.success("complete"));
      assertEquals(OperationState.COMPLETE, ownerRun.completion().toCompletableFuture().join().state());
      assertThrows(IllegalArgumentException.class, () -> owner.checkpointBulkReindex(ownerHandle, progress));

      foreignEffect.complete(OperationResult.success("complete"));
      assertEquals(OperationState.COMPLETE, foreignRun.completion().toCompletableFuture().join().state());
    }
  }

  @Test
  void bulkBoundaryObservationsAreReadOnlyAndRequireOwnLiveReindexControl() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("observation-capabilities.db"), clock, ignored -> {})) {
      var emittedBoundaries = new java.util.ArrayList<OperationAttemptRunnerImpl.FaultBoundary>();
      var owner = new OperationAttemptRunnerImpl(store, clock,
          Set.of(OperationKind.REINDEX, OperationKind.INGEST), null, null, emittedBoundaries::add);
      var otherRunner = new OperationAttemptRunnerImpl(store, clock, Set.of(OperationKind.REINDEX));
      var ownerAttempt = owner.accept(request());
      var otherAttempt = otherRunner.accept(request());
      var ingestAttempt = owner.accept(request(OperationKind.INGEST, "core.ingest"));
      var ownerHandleRef = new AtomicReference<OperationRecordHandle>();
      var otherHandleRef = new AtomicReference<OperationRecordHandle>();
      var ingestHandleRef = new AtomicReference<OperationRecordHandle>();
      var ownerEffect = new CompletableFuture<OperationResult>();
      var otherEffect = new CompletableFuture<OperationResult>();
      var ingestEffect = new CompletableFuture<OperationResult>();
      var ownerRun = owner.start(ownerAttempt, handle -> {
        ownerHandleRef.set(handle);
        return new OperationExecution(OperationResult.success("started"), ownerEffect);
      });
      var otherRun = otherRunner.start(otherAttempt, handle -> {
        otherHandleRef.set(handle);
        return new OperationExecution(OperationResult.success("started"), otherEffect);
      });
      var ingestRun = owner.start(ingestAttempt, handle -> {
        ingestHandleRef.set(handle);
        return new OperationExecution(OperationResult.success("started"), ingestEffect);
      });
      OperationRecordHandle ownerHandle = ownerHandleRef.get();
      OperationRecordHandle otherHandle = otherHandleRef.get();
      OperationRecordHandle ingestHandle = ingestHandleRef.get();
      var target = target("{\"index\":\"observed\"}");
      var progress = capturing(ownerHandle.key(), target);
      owner.checkpointBulkReindex(ownerHandle, progress);
      var runningBefore = store.find(ownerHandle.key()).orElseThrow();

      owner.observeBulkBoundary(ownerHandle, OperationAttemptRunner.BulkBoundary.PARTIAL_CAPTURE);
      owner.observeBulkBoundary(ownerHandle, OperationAttemptRunner.BulkBoundary.AFTER_PROMOTION);

      assertEquals(java.util.List.of("bulk-partial-capture", "bulk-after-promotion"),
          emittedBoundaries.stream()
              .map(OperationAttemptRunnerImpl.FaultBoundary::phase)
              .filter(phase -> phase.startsWith("bulk-"))
              .toList());
      assertEquals(runningBefore, store.find(ownerHandle.key()).orElseThrow());
      assertEquals(Optional.of(progress), store.bulkReindexProgress(ownerHandle.id()));
      assertFalse(ownerRun.completion().toCompletableFuture().isDone(),
          "observations do not terminalize the live operation");
      assertThrows(IllegalArgumentException.class, () ->
          owner.observeBulkBoundary(otherHandle, OperationAttemptRunner.BulkBoundary.PARTIAL_CAPTURE));
      assertThrows(IllegalArgumentException.class, () ->
          owner.observeBulkBoundary(ingestHandle, OperationAttemptRunner.BulkBoundary.PARTIAL_CAPTURE));
      assertFalse(owner.persistenceFailure().toCompletableFuture().isDone(),
          "capability refusals are not persistence failures");

      ownerEffect.complete(OperationResult.success("complete"));
      assertEquals(OperationState.COMPLETE, ownerRun.completion().toCompletableFuture().join().state());
      var terminal = store.find(ownerHandle.key()).orElseThrow();
      assertThrows(IllegalArgumentException.class, () ->
          owner.observeBulkBoundary(ownerHandle, OperationAttemptRunner.BulkBoundary.AFTER_PROMOTION));
      assertEquals(terminal, store.find(ownerHandle.key()).orElseThrow());
      assertEquals(Optional.of(progress), store.bulkReindexProgress(ownerHandle.id()));

      otherEffect.complete(OperationResult.success("complete"));
      ingestEffect.complete(OperationResult.success("complete"));
      assertEquals(OperationState.COMPLETE, otherRun.completion().toCompletableFuture().join().state());
      assertEquals(OperationState.COMPLETE, ingestRun.completion().toCompletableFuture().join().state());
    }
  }

  @Test
  void buildingCheckpointFaultBoundaryObservesDurableCapturingStateBeforeWrite() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("before-building-boundary.db"), clock, ignored -> {})) {
      var boundaries = new java.util.ArrayList<OperationAttemptRunnerImpl.FaultBoundary>();
      var progressAtBoundary = new AtomicReference<Optional<BulkReindexProgress>>();
      var stateAtBoundary = new AtomicReference<OperationState>();
      var cursorAtBoundary = new AtomicReference<String>();
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(OperationKind.REINDEX), null, null,
          boundary -> {
            boundaries.add(boundary);
            if (boundary.phase().equals("bulk-before-building-checkpoint")) {
              progressAtBoundary.set(store.bulkReindexProgress(boundary.operationRecordId()));
              var row = store.find(boundary.parentKey()).orElseThrow();
              stateAtBoundary.set(row.state());
              cursorAtBoundary.set(row.checkpointCursor());
            }
          });
      var attempt = runner.accept(request());
      var handleRef = new AtomicReference<OperationRecordHandle>();
      var effect = new CompletableFuture<OperationResult>();
      var running = runner.start(attempt, handle -> {
        handleRef.set(handle);
        return new OperationExecution(OperationResult.success("started"), effect);
      });
      OperationRecordHandle handle = handleRef.get();
      var target = target("{\"index\":\"before-building\"}");
      var capturing = capturing(handle.key(), target);
      runner.checkpointBulkReindex(handle, capturing);
      var capturingRow = store.find(handle.key()).orElseThrow();
      var building = new BulkReindexProgress(capturing.generationId(), target,
          BulkReindexProgress.Phase.BUILDING, new BulkReindexProgress.Capture("4".repeat(64), 2), null);

      runner.checkpointBulkReindex(handle, building);

      var checkpointBoundary = boundaries.stream()
          .filter(boundary -> boundary.phase().equals("bulk-before-building-checkpoint"))
          .toList();
      assertEquals(1, checkpointBoundary.size());
      var observed = checkpointBoundary.getFirst();
      assertEquals(OperationKind.REINDEX, observed.parentKind());
      assertEquals(handle.key(), observed.parentKey());
      assertEquals(handle.key(), observed.operationKey());
      assertEquals(handle.id(), observed.operationRecordId());
      assertNull(observed.cursor());
      assertEquals(0, observed.completed());
      assertEquals(0, observed.failed());
      assertEquals(Optional.of(capturing), progressAtBoundary.get(),
          "the injected cut runs immediately before the BUILDING metadata write");
      assertEquals(capturingRow.checkpointCursor(), cursorAtBoundary.get());
      assertEquals(OperationState.RUNNING, stateAtBoundary.get());
      assertEquals(OperationState.RUNNING, store.find(handle.key()).orElseThrow().state());
      assertEquals(building, store.bulkReindexProgress(handle.id()).orElseThrow());

      effect.complete(OperationResult.success("complete"));
      assertEquals(OperationState.COMPLETE, running.completion().toCompletableFuture().join().state());
    }
  }

  @Test
  void recoveredBulkCheckpointAtAttemptLimitDoesNotResumeAndRemainsEligible() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("recovery-limit.db"), clock, ignored -> {})) {
      var request = request();
      var row = store.accept(request.key(), request.descriptor(), request.context(), null).record();
      assertTrue(store.start(row.id()));
      var target = target("{\"index\":\"recovery\"}");
      var capturing = capturing(row.key(), target);
      assertTrue(store.checkpointBulkReindex(row.id(), capturing));
      for (int attempt = 1; attempt < OperationAttemptRunner.MAX_DURABLE_ATTEMPTS; attempt++) {
        assertTrue(store.resume(row.id()));
      }
      assertEquals(OperationAttemptRunner.MAX_DURABLE_ATTEMPTS,
          store.find(row.key()).orElseThrow().attempts());

      var capture = new BulkReindexProgress.Capture("1".repeat(64), 3);
      var building = new BulkReindexProgress(capturing.generationId(), target,
          BulkReindexProgress.Phase.BUILDING, capture, null);
      assertTrue(store.checkpointBulkReindex(row.id(), building));
      var settlement = new BulkReindexProgress.Settlement(1, "3".repeat(64),
          0, 0, java.util.List.of(), java.util.List.of());
      var settled = new BulkReindexProgress(capturing.generationId(), target,
          BulkReindexProgress.Phase.SETTLED, capture, settlement);
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(OperationKind.REINDEX));
      runner.reconcile(OperationKind.REINDEX,
          ignored -> new OperationAttemptRunner.Reconciliation.CheckpointBulkAndWait(settled));

      var checkpointed = store.find(row.key()).orElseThrow();
      assertEquals(OperationState.RUNNING, checkpointed.state());
      assertEquals(OperationAttemptRunner.MAX_DURABLE_ATTEMPTS, checkpointed.attempts());
      assertEquals(settled.cursor(), checkpointed.checkpointCursor());
      assertEquals(3, checkpointed.unitsCompleted());
      assertEquals(0, checkpointed.unitsFailed());
      assertEquals(Optional.of(settled), store.bulkReindexProgress(row.id()));
      assertFalse(runner.persistenceFailure().toCompletableFuture().isDone());

      runner.reconcile(OperationKind.REINDEX, current -> {
        assertEquals(checkpointed, current);
        return new OperationAttemptRunner.Reconciliation.Complete(new OperationReceipt("RECOVERED", null));
      });
      var completed = store.find(row.key()).orElseThrow();
      assertEquals(OperationState.COMPLETE, completed.state());
      assertEquals(OperationAttemptRunner.MAX_DURABLE_ATTEMPTS, completed.attempts());
    }
  }

  @Test
  void recoveredBulkCheckpointRejectsRegressiveEvidenceAndSignalsPersistenceFailure() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("recovery-regression.db"), clock, ignored -> {})) {
      var request = request();
      var row = store.accept(request.key(), request.descriptor(), request.context(), null).record();
      assertTrue(store.start(row.id()));
      var target = target("{\"index\":\"recovery\"}");
      var capturing = capturing(row.key(), target);
      assertTrue(store.checkpointBulkReindex(row.id(), capturing));
      var capture = new BulkReindexProgress.Capture("2".repeat(64), 2);
      var building = new BulkReindexProgress(capturing.generationId(), target,
          BulkReindexProgress.Phase.BUILDING, capture, null);
      assertTrue(store.checkpointBulkReindex(row.id(), building));

      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of(OperationKind.REINDEX));
      assertThrows(IllegalStateException.class, () -> runner.reconcile(OperationKind.REINDEX,
          ignored -> new OperationAttemptRunner.Reconciliation.CheckpointBulkAndWait(capturing)));

      var unchanged = store.find(row.key()).orElseThrow();
      assertEquals(OperationState.RUNNING, unchanged.state());
      assertEquals(1, unchanged.attempts());
      assertEquals(building.cursor(), unchanged.checkpointCursor());
      assertEquals(Optional.of(building), store.bulkReindexProgress(row.id()));
      var failure = runner.persistenceFailure().toCompletableFuture().join();
      assertEquals(row.key(), failure.operationKey());
      assertEquals(OperationState.RUNNING, failure.intendedState());
    }
  }

  private OperationAttemptRunner.Request request() {
    return request(OperationKind.REINDEX, "core.bulk-reindex");
  }

  private OperationAttemptRunner.Request request(OperationKind kind, String executor) {
    return new OperationAttemptRunner.Request(OperationKeys.generate(clock),
        OperationDescriptor.invocation(kind, executor, "{}", false),
        new EngineContext(EngineContext.ClientKind.INTERNAL, "bulk-capability-test", Optional.empty(),
            Optional.empty(), "system", "SYSTEM_INTERNAL", EngineContext.Survival.DURABLE,
            EngineContext.Urgency.BACKGROUND), null);
  }

  private static BulkReindexProgress capturing(String key, IndexTargetSnapshot target) {
    return new BulkReindexProgress("g-" + key, target, BulkReindexProgress.Phase.CAPTURING, null, null);
  }

  private static IndexTargetSnapshot target(String canonicalJson) {
    try {
      String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
      return new IndexTargetSnapshot(digest, canonicalJson);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
