/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@DisplayName("VDU recovery replay completeness")
final class VduRecoveryReplayCompletenessTest {

  private static final String BUFFER_KEY = "vdu_recovery:completeness";
  private static final List<String> SELECTED =
      List.of("processing-missing", "processing-success");

  @TempDir Path tempDir;

  @Test
  @DisplayName("all selected misses retain the row and do not commit")
  void allUpdatesFalseRetainWithoutCommit() throws Exception {
    Path dbPath = tempDir.resolve("all-missing.db");
    SwitchBufferCapableQueue.SwitchBufferOp before = seed(dbPath);
    RunningRuntime runtime = runtimeWithSelected(SELECTED);
    when(runtime.indexingCoordinator().updateDocument(anyString(), anyMap())).thenReturn(false);

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, runtime);
      assertEquals(1, queue.switchBufferDepth());
      assertEquals(before, find(queue));
      verify(runtime.indexingCoordinator()).updateDocument(eq("processing-missing"), anyMap());
      verify(runtime.indexingCoordinator()).updateDocument(eq("processing-success"), anyMap());
      verify(runtime.commitOps(), never()).commitAndTrack(CommitReason.VDU_RECOVERY);
    }
    assertRetainedAfterReopen(dbPath, before);
  }

  @Test
  @DisplayName("a successful subset commits but an incomplete selection retains the row")
  void oneMissAndOneSuccessCommitSubsetAndRetain() throws Exception {
    Path dbPath = tempDir.resolve("one-missing.db");
    SwitchBufferCapableQueue.SwitchBufferOp before = seed(dbPath);
    RunningRuntime runtime = runtimeWithSelected(SELECTED);
    when(runtime.indexingCoordinator().updateDocument(anyString(), anyMap()))
        .thenAnswer(invocation -> "processing-success".equals(invocation.getArgument(0)));

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, runtime);
      assertEquals(1, queue.switchBufferDepth());
      assertEquals(before, find(queue));
      verifyRecoveryAttemptOrder(runtime);
      verify(runtime.commitOps()).commitAndTrack(CommitReason.VDU_RECOVERY);
    }
    assertRetainedAfterReopen(dbPath, before);
  }

  @Test
  @DisplayName("an IOException and a successful reset commit the subset and retain the row")
  void oneIoFailureAndOneSuccessCommitSubsetAndRetain() throws Exception {
    Path dbPath = tempDir.resolve("one-io-failure.db");
    SwitchBufferCapableQueue.SwitchBufferOp before = seed(dbPath);
    RunningRuntime runtime = runtimeWithSelected(SELECTED);
    IndexingCoordinator indexing = runtime.indexingCoordinator();
    doAnswer(
            invocation -> {
              if ("processing-missing".equals(invocation.getArgument(0))) {
                throw new IOException("controlled recovery reset failure");
              }
              return true;
            })
        .when(indexing)
        .updateDocument(anyString(), anyMap());

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, runtime);
      assertEquals(1, queue.switchBufferDepth());
      assertEquals(before, find(queue));
      verifyRecoveryAttemptOrder(runtime);
      verify(runtime.commitOps()).commitAndTrack(CommitReason.VDU_RECOVERY);
    }
    assertRetainedAfterReopen(dbPath, before);
  }

  @Test
  @DisplayName("all successful resets commit and clear the recovery row")
  void allUpdatesSuccessCommitAndClear() throws Exception {
    Path dbPath = tempDir.resolve("all-success.db");
    RunningRuntime runtime = runtimeWithSelected(SELECTED);
    when(runtime.indexingCoordinator().updateDocument(anyString(), anyMap())).thenReturn(true);

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      seed(queue);
      drain(queue, runtime);
      assertEquals(0, queue.switchBufferDepth());
      verifyRecoveryAttemptOrder(runtime);
      verify(runtime.commitOps()).commitAndTrack(CommitReason.VDU_RECOVERY);
    }
    try (SqliteJobQueue reopened = openQueue(dbPath)) {
      assertEquals(0, reopened.switchBufferDepth());
    }
  }

  @Test
  @DisplayName("an empty selected list clears the normal recovery row without a commit")
  void emptySelectedListClearsNormally() throws Exception {
    Path dbPath = tempDir.resolve("empty-selection.db");
    RunningRuntime runtime = runtimeWithSelected(List.of());

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      seed(queue);
      drain(queue, runtime);
      assertEquals(0, queue.switchBufferDepth());
      verify(runtime.indexingCoordinator(), never()).updateDocument(anyString(), anyMap());
      verify(runtime.commitOps(), never()).commitAndTrack(CommitReason.VDU_RECOVERY);
    }
  }

  private RunningRuntime runtimeWithSelected(List<String> selected) throws IOException {
    RunningRuntime runtime = mock(RunningRuntime.class);
    DocumentFieldOps fields = mock(DocumentFieldOps.class);
    IndexingCoordinator indexing = mock(IndexingCoordinator.class);
    CommitOps commits = mock(CommitOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(runtime.commitOps()).thenReturn(commits);
    when(fields.queryDocIdsByFieldOrThrow(anyString(), anyString(), anyInt()))
        .thenReturn(selected);
    return runtime;
  }

  private void verifyRecoveryAttemptOrder(RunningRuntime runtime) {
    var order = inOrder(runtime.indexingCoordinator(), runtime.commitOps());
    order.verify(runtime.indexingCoordinator()).updateDocument(eq("processing-missing"), anyMap());
    order.verify(runtime.indexingCoordinator()).updateDocument(eq("processing-success"), anyMap());
    order.verify(runtime.commitOps()).commitAndTrack(CommitReason.VDU_RECOVERY);
  }

  private void drain(SqliteJobQueue queue, RunningRuntime runtime) {
    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            queue,
            runtime,
            null,
            IndexingPacing.unthrottled(),
            null,
            null,
            new ObjectMapper(),
            () -> false,
            () -> true,
            LoggerFactory.getLogger(VduRecoveryReplayCompletenessTest.class)));
  }

  private SwitchBufferCapableQueue.SwitchBufferOp seed(Path dbPath) throws Exception {
    try (SqliteJobQueue queue = openQueue(dbPath)) {
      seed(queue);
      return find(queue);
    }
  }

  private void seed(SqliteJobQueue queue) {
    if (!queue.putSwitchBuffer(BUFFER_KEY, "VDU_RECOVER_PROCESSING", "{}")) {
      throw new IllegalStateException("failed to seed recovery replay row");
    }
  }

  private SwitchBufferCapableQueue.SwitchBufferOp find(SqliteJobQueue queue) {
    return queue.listSwitchBufferOps().stream()
        .filter(op -> BUFFER_KEY.equals(op.key()))
        .findFirst()
        .orElseThrow();
  }

  private void assertRetainedAfterReopen(
      Path dbPath, SwitchBufferCapableQueue.SwitchBufferOp expected) throws Exception {
    try (SqliteJobQueue reopened = openQueue(dbPath)) {
      assertEquals(1, reopened.switchBufferDepth());
      assertEquals(expected, find(reopened));
    }
  }

  private SqliteJobQueue openQueue(Path dbPath) throws Exception {
    SqliteJobQueue queue = new SqliteJobQueue(dbPath);
    queue.open();
    return queue;
  }
}
