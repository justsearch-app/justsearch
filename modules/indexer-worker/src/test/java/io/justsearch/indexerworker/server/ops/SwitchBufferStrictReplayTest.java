/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class SwitchBufferStrictReplayTest {
  private final SwitchBufferCapableQueue queue = mock(SwitchBufferCapableQueue.class);
  private final RunningRuntime runtime = mock(RunningRuntime.class);

  @Test
  void emptySnapshotCertifiesAndUnknownOperationRefusesPromotion() {
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of());
    assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(context()));

    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(
        new SwitchBufferCapableQueue.SwitchBufferOp("path:x", "UNKNOWN", "x", 1, "v1")));
    assertFalse(KnowledgeServerMigrationOps.drainSwitchBufferStrict(context()));
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void exactVersionRemovalMustSucceedToCertifyReplay() {
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(
        new SwitchBufferCapableQueue.SwitchBufferOp("path:x", "DELETE", "x", 1, "v1")));
    when(runtime.indexingCoordinator()).thenReturn(mock(IndexingCoordinator.class));
    when(runtime.commitOps()).thenReturn(mock(CommitOps.class));
    assertFalse(KnowledgeServerMigrationOps.drainSwitchBufferStrict(context()));
    verify(queue).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void unreadableSnapshotCannotBeCertifiedAsEmpty() {
    when(queue.listSwitchBufferOpsStrict()).thenThrow(new IllegalStateException("SQLite read failed"));
    when(queue.listSwitchBufferOps()).thenReturn(List.of());

    assertFalse(KnowledgeServerMigrationOps.drainSwitchBufferStrict(context()));
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void promotionReplayRetainsExactVersionsUntilAfterPointerCommit() {
    var version = new SwitchBufferCapableQueue.SwitchBufferOp("path:x", "DELETE", "x", 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(version));
    when(runtime.indexingCoordinator()).thenReturn(mock(IndexingCoordinator.class));
    when(runtime.commitOps()).thenReturn(mock(CommitOps.class));
    when(queue.removeReplayedSwitchBufferOps(List.of(version))).thenReturn(1);

    var replay = KnowledgeServerMigrationOps.prepareSwitchReplayForPromotion(context()).orElseThrow();
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
    assertTrue(KnowledgeServerMigrationOps.finishPromotedSwitchReplay(queue, replay));
    verify(queue).removeReplayedSwitchBufferOps(List.of(version));
  }

  @Test
  void upsertBeforePrefixDeletePreservesAcceptedMutationOrder() {
    String root = Path.of(System.getProperty("java.io.tmpdir"), "replay-root")
        .toAbsolutePath().toString();
    String file = Path.of(root, "removed.txt").toString();
    var versions = List.of(
        new SwitchBufferCapableQueue.SwitchBufferOp("path:" + file, "UPSERT", file, 1, "v1"),
        new SwitchBufferCapableQueue.SwitchBufferOp("prefix:" + root, "DELETE_PREFIX", root, 2, "v2"));
    when(queue.listSwitchBufferOpsStrict()).thenReturn(versions);
    when(queue.enqueueEntries(anyList(), isNull())).thenReturn(1);
    when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 1, 0));
    when(queue.removeReplayedSwitchBufferOps(versions)).thenReturn(2);
    when(runtime.indexingCoordinator()).thenReturn(mock(IndexingCoordinator.class));
    when(runtime.commitOps()).thenReturn(mock(CommitOps.class));

    assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(context()));
    var order = inOrder(queue);
    order.verify(queue).enqueueEntries(anyList(), isNull());
    order.verify(queue).deleteByPathPrefix(root);
    order.verify(queue).removeReplayedSwitchBufferOps(versions);
  }

  @Test
  void claimedUpsertMustFinishBeforeLaterPrefixDelete() throws Exception {
    String root = Path.of(System.getProperty("java.io.tmpdir"), "ordered-replay-root")
        .toAbsolutePath().toString();
    String file = Path.of(root, "removed.txt").toString();
    var versions = List.of(
        new SwitchBufferCapableQueue.SwitchBufferOp("path:" + file, "UPSERT", file, 1, "v1"),
        new SwitchBufferCapableQueue.SwitchBufferOp("prefix:" + root, "DELETE_PREFIX", root, 2, "v2"));
    when(queue.listSwitchBufferOpsStrict()).thenReturn(versions);
    when(queue.enqueueEntries(anyList(), isNull())).thenReturn(1);
    when(queue.removeReplayedSwitchBufferOps(versions)).thenReturn(2);
    when(runtime.indexingCoordinator()).thenReturn(mock(IndexingCoordinator.class));
    when(runtime.commitOps()).thenReturn(mock(CommitOps.class));
    CountDownLatch claimed = new CountDownLatch(1);
    CountDownLatch writerFinished = new CountDownLatch(1);
    when(queue.jobStateCountsStrict()).thenAnswer(ignored -> {
      claimed.countDown();
      if (!writerFinished.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Test writer never finished");
      }
      return new JobQueue.JobStateCounts(0, 0, 0, 1, 0);
    });
    FutureTask<Boolean> replay = new FutureTask<>(
        () -> KnowledgeServerMigrationOps.drainSwitchBufferStrict(context()));
    Thread thread = new Thread(replay, "switch-replay-order-test");
    thread.setDaemon(true);
    thread.start();
    try {
      assertTrue(claimed.await(5, TimeUnit.SECONDS));
      assertFalse(replay.isDone());
      verify(queue, never()).deleteByPathPrefix(root);
    } finally {
      writerFinished.countDown();
    }
    assertTrue(replay.get(5, TimeUnit.SECONDS));
    verify(queue).deleteByPathPrefix(root);
  }

  @Test
  void sharedCutoverDeadlineRetainsUnsettledUpsertWithoutApplyingLaterDelete() {
    String root = Path.of(System.getProperty("java.io.tmpdir"), "deadline-replay-root")
        .toAbsolutePath().toString();
    String file = Path.of(root, "pending.txt").toString();
    var versions = List.of(
        new SwitchBufferCapableQueue.SwitchBufferOp("path:" + file, "UPSERT", file, 1, "v1"),
        new SwitchBufferCapableQueue.SwitchBufferOp("prefix:" + root, "DELETE_PREFIX", root, 2, "v2"));
    when(queue.listSwitchBufferOpsStrict()).thenReturn(versions);
    when(queue.enqueueEntries(anyList(), isNull())).thenReturn(1);
    when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 1, 0, 0, 0));
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
    var bounded = new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
        IndexingPacing.unthrottled(), Path.of("."), Path.of("."), new ObjectMapper(),
        () -> false, () -> true, LoggerFactory.getLogger(getClass()), deadline);

    assertFalse(KnowledgeServerMigrationOps.drainSwitchBufferStrict(bounded));
    verify(queue, never()).deleteByPathPrefix(root);
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void promotedBootOnlyDeletesExactCommittedVersionsWithoutReenqueuing() {
    String file = Path.of(System.getProperty("java.io.tmpdir"), "committed.txt")
        .toAbsolutePath().toString();
    var versions = List.of(
        new SwitchBufferCapableQueue.SwitchBufferOp("path:" + file, "UPSERT", file, 1, "v1"));
    when(queue.listSwitchBufferOpsStrict()).thenReturn(versions, List.of());
    when(queue.removeReplayedSwitchBufferOps(versions)).thenReturn(1);

    assertTrue(KnowledgeServerMigrationOps.finishCommittedBootSwitchReplay(queue));
    verify(queue, never()).enqueueEntries(anyList(), isNull());
    verify(queue).removeReplayedSwitchBufferOps(versions);
  }

  private KnowledgeServerMigrationOps.DrainSwitchBufferContext context() {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
        IndexingPacing.unthrottled(), Path.of("."), Path.of("."), new ObjectMapper(),
        () -> false, () -> true, LoggerFactory.getLogger(getClass()));
  }
}
