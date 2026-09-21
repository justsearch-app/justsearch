/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

  private OperationAttemptRunner.Request request() {
    return new OperationAttemptRunner.Request(OperationKeys.generate(clock),
        OperationDescriptor.invocation(OperationKind.REINDEX, "core.bulk-reindex", "{}", false),
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
