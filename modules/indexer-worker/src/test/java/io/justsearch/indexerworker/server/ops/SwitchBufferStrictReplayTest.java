/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

final class SwitchBufferStrictReplayTest {
  @TempDir Path tempDir;
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
  void collectionDeleteReplaysAndCommitsBeforeItsJournalVersionIsRemoved() {
    var version = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "collection:notes", "DELETE_COLLECTION", "notes", 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(version));
    when(queue.removeReplayedSwitchBufferOps(List.of(version))).thenReturn(1);
    var indexing = mock(IndexingCoordinator.class);
    var commits = mock(CommitOps.class);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(runtime.commitOps()).thenReturn(commits);

    assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(scopedContext("green")));
    var order = inOrder(indexing, commits, queue);
    order.verify(indexing).deleteByCollection("notes");
    order.verify(commits).commitAndTrack(org.mockito.ArgumentMatchers.any());
    order.verify(queue).removeReplayedSwitchBufferOps(List.of(version));
  }

  @Test
  void candidateReplayLeavesAnotherGenerationUntouched() {
    var selected = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:green", "DELETE", "green", 1, "v1");
    var foreign = new SwitchBufferCapableQueue.SwitchBufferOp(
        "abandoned", "path:other", "DELETE", "other", 2, "v2");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(selected, foreign));
    when(queue.removeReplayedSwitchBufferOps(List.of(selected))).thenReturn(1);
    var indexing = mock(IndexingCoordinator.class);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(runtime.commitOps()).thenReturn(mock(CommitOps.class));

    assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(scopedContext("green")));
    verify(indexing).deleteByIdAndChunks("green");
    verify(indexing, never()).deleteByIdAndChunks("other");
    verify(queue).removeReplayedSwitchBufferOps(List.of(selected));
  }

  @Test
  void refusalReconcilesOnlyAcceptedCandidateRowsOnSurvivingSource() {
    var selected = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:removed", "DELETE", "removed", 3, "v3");
    var historical = new SwitchBufferCapableQueue.SwitchBufferOp(
        "path:historical", "DELETE", "historical", 4, "v4");
    var foreign = new SwitchBufferCapableQueue.SwitchBufferOp(
        "other", "path:other", "DELETE", "other", 5, "v5");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(selected, historical, foreign));
    when(queue.removeReplayedSwitchBufferOps(List.of(selected))).thenReturn(1);
    var indexing = mock(IndexingCoordinator.class);
    var commits = mock(CommitOps.class);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(runtime.commitOps()).thenReturn(commits);

    assertTrue(KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(
        scopedContext("green")));
    var order = inOrder(indexing, commits, queue);
    order.verify(indexing).deleteByIdAndChunks("removed");
    order.verify(commits).commitAndTrack(org.mockito.ArgumentMatchers.any());
    order.verify(queue).removeReplayedSwitchBufferOps(List.of(selected));
    verify(indexing, never()).deleteByIdAndChunks("historical");
    verify(indexing, never()).deleteByIdAndChunks("other");
  }

  @Test
  void refusalRetainsCandidateWhenSourceProjectionIsMissing() throws Exception {
    Path file = Files.writeString(tempDir.resolve("candidate.txt"), "accepted").toAbsolutePath();
    var payload = new SwitchBufferUpsert(file.toString(), null, null,
        "accepted-revision", SourceContentHash.sha256(file)).encode();
    var selected = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:" + file, "UPSERT", payload, 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(selected));
    when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 1, 0));
    when(queue.matchesAcceptedFileProjection(anyString(), anyString(), anyString()))
        .thenReturn(true);
    when(runtime.documentFieldOps()).thenReturn(mock(DocumentFieldOps.class));

    assertFalse(KnowledgeServerMigrationOps.drainRefusedCandidateOnSource(
        scopedContext("green")));
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void committedNativePointerClearsOnlyVerifiedFileWitnesses() throws Exception {
    String hash = "a".repeat(64);
    String path = Files.writeString(tempDir.resolve("accepted-native.txt"), "accepted")
        .toAbsolutePath().toString();
    var upsert = new SwitchBufferUpsert(path, null, null, "issued-revision", hash);
    var selected = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:" + path, "UPSERT", upsert.encode(), 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(selected), List.of());
    when(queue.matchesAcceptedFileProjection(path, "issued-revision", hash))
        .thenReturn(true);
    var fields = mock(DocumentFieldOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(fields.getDocumentField(path, SchemaFields.SOURCE_SHA256))
        .thenReturn(hash);
    when(queue.removeReplayedSwitchBufferOps(List.of(selected))).thenReturn(1);

    assertTrue(KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(
        queue, runtime, "green", LoggerFactory.getLogger(getClass())));
    verify(queue).removeReplayedSwitchBufferOps(List.of(selected));
  }

  @Test
  void committedNativePointerRetainsUnknownEffectForRecovery() {
    var selected = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:deleted", "DELETE", "deleted", 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(selected));

    assertFalse(KnowledgeServerMigrationOps.settleCommittedNativeFileWitnesses(
        queue, runtime, "green", LoggerFactory.getLogger(getClass())));
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void changedAcceptedSourceCannotReplayDifferentBytes() throws Exception {
    Path file = Files.writeString(tempDir.resolve("accepted.txt"), "accepted").toAbsolutePath();
    String acceptedHash = SourceContentHash.sha256(file);
    var payload = new SwitchBufferUpsert(file.toString(), null, null, "accepted-revision",
        acceptedHash).encode();
    var version = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:" + file, "UPSERT", payload, 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(version));
    when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 1, 0));
    when(queue.matchesAcceptedFileProjection(anyString(), anyString(), anyString()))
        .thenReturn(true);
    var fields = mock(DocumentFieldOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    Files.writeString(file, "later unaccepted bytes");

    assertFalse(KnowledgeServerMigrationOps.drainSwitchBufferStrict(scopedContext("green")));
    verify(fields).getDocumentField(file.toString(), SchemaFields.SOURCE_SHA256);
    verify(queue, never()).enqueueEntries(anyList(), isNull());
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void acceptedGreenProjectionSurvivesLaterUnacceptedFileEdit() throws Exception {
    Path file = Files.writeString(tempDir.resolve("already-projected.txt"), "accepted")
        .toAbsolutePath();
    String acceptedHash = SourceContentHash.sha256(file);
    var payload = new SwitchBufferUpsert(file.toString(), null, null, "accepted-revision",
        acceptedHash).encode();
    var version = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:" + file, "UPSERT", payload, 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(version));
    when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 1, 0));
    when(queue.matchesAcceptedFileProjection(anyString(), anyString(), anyString()))
        .thenReturn(true);
    when(queue.removeReplayedSwitchBufferOps(List.of(version))).thenReturn(1);
    var fields = mock(DocumentFieldOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(fields.getDocumentField(file.toString(), SchemaFields.SOURCE_SHA256))
        .thenReturn(acceptedHash);
    Files.writeString(file, "unaccepted later bytes");

    assertTrue(KnowledgeServerMigrationOps.drainSwitchBufferStrict(scopedContext("green")));
    verify(queue, never()).enqueueEntries(anyList(), isNull());
    verify(queue).removeReplayedSwitchBufferOps(List.of(version));
  }

  @Test
  void missingAcceptedSourceCannotReplayAsSuccessfulStaleDelete() throws Exception {
    Path file = Files.writeString(tempDir.resolve("removed.txt"), "accepted").toAbsolutePath();
    var payload = new SwitchBufferUpsert(file.toString(), null, null, "accepted-revision",
        SourceContentHash.sha256(file)).encode();
    var version = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:" + file, "UPSERT", payload, 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(version));
    when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 1, 0));
    when(queue.matchesAcceptedFileProjection(anyString(), anyString(), anyString()))
        .thenReturn(true);
    var fields = mock(DocumentFieldOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    Files.delete(file);

    assertFalse(KnowledgeServerMigrationOps.drainSwitchBufferStrict(scopedContext("green")));
    verify(fields).getDocumentField(file.toString(), SchemaFields.SOURCE_SHA256);
    verify(queue, never()).enqueueEntries(anyList(), isNull());
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void drainedQueueWithoutAcceptedGreenProjectionRefusesPromotion() throws Exception {
    Path file = Files.writeString(tempDir.resolve("unprojected.txt"), "accepted").toAbsolutePath();
    var payload = new SwitchBufferUpsert(file.toString(), null, null, "accepted-revision",
        SourceContentHash.sha256(file)).encode();
    var version = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:" + file, "UPSERT", payload, 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(version));
    when(queue.enqueueEntries(anyList(), isNull())).thenReturn(1);
    when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 1, 0));
    when(queue.matchesAcceptedFileProjection(anyString(), anyString(), anyString()))
        .thenReturn(true);
    when(runtime.documentFieldOps()).thenReturn(mock(DocumentFieldOps.class));

    assertFalse(KnowledgeServerMigrationOps.drainSwitchBufferStrict(scopedContext("green")));
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
  }

  @Test
  void matchingOldGreenDocumentCannotCertifyMissingAcceptedQueueRevision() throws Exception {
    Path file = Files.writeString(tempDir.resolve("revision.txt"), "same bytes")
        .toAbsolutePath();
    String hash = SourceContentHash.sha256(file);
    var payload = new SwitchBufferUpsert(file.toString(), null, null, "new-revision",
        hash).encode();
    var version = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:" + file, "UPSERT", payload, 1, "v1");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(List.of(version));
    when(queue.jobStateCountsStrict()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 1, 0));
    var fields = mock(DocumentFieldOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(fields.getDocumentField(file.toString(), SchemaFields.SOURCE_SHA256)).thenReturn(hash);

    assertFalse(KnowledgeServerMigrationOps.drainSwitchBufferStrict(scopedContext("green")));
    verify(queue).matchesAcceptedFileProjection(file.toString(), "new-revision", hash);
    verify(queue, never()).removeReplayedSwitchBufferOps(anyList());
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

  @Test
  void promotedBootCleansOnlyCommittedGenerationAndHistoricalCutoverEntries() {
    var legacy = new SwitchBufferCapableQueue.SwitchBufferOp(
        "path:legacy", "DELETE", "legacy", 1, "v1");
    var promoted = new SwitchBufferCapableQueue.SwitchBufferOp(
        "green", "path:green", "DELETE", "green", 2, "v2");
    var foreign = new SwitchBufferCapableQueue.SwitchBufferOp(
        "foreign", "path:foreign", "DELETE", "foreign", 3, "v3");
    when(queue.listSwitchBufferOpsStrict()).thenReturn(
        List.of(legacy, promoted, foreign), List.of(foreign));
    when(queue.removeReplayedSwitchBufferOps(List.of(legacy, promoted))).thenReturn(2);

    assertTrue(KnowledgeServerMigrationOps.finishCommittedBootSwitchReplay(queue, "green"));
    verify(queue).removeReplayedSwitchBufferOps(List.of(legacy, promoted));
  }

  private KnowledgeServerMigrationOps.DrainSwitchBufferContext context() {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
        IndexingPacing.unthrottled(), Path.of("."), Path.of("."), new ObjectMapper(),
        () -> false, () -> true, LoggerFactory.getLogger(getClass()));
  }

  private KnowledgeServerMigrationOps.DrainSwitchBufferContext scopedContext(String generation) {
    return new KnowledgeServerMigrationOps.DrainSwitchBufferContext(queue, runtime, null,
        IndexingPacing.unthrottled(), Path.of("."), Path.of("."), new ObjectMapper(),
        () -> false, () -> true, LoggerFactory.getLogger(getClass()), Long.MAX_VALUE,
        generation);
  }
}
