/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkSplitter;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@DisplayName("VDU replay failure retention")
final class VduReplayFailureRetentionTest {

  private static final String DOC_ID = "d:/docs/replay-parent.txt";
  private static final String PARENT_UID = "stable-replay-parent";
  private static final String CONTENT = "replay content ".repeat(2_000);

  @TempDir Path tempDir;

  @Test
  void unavailableRuntimeDoesNotDiscardTheAcceptedUpdate() throws Exception {
    Path dbPath = tempDir.resolve("unavailable.db");
    String payload = vduUpdatePayload(DOC_ID, CONTENT);
    persist(dbPath, payload);
    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, null);
      assertBuffered(queue, payload);
    }
  }

  @Test
  void blankPayloadIsRetainedForDiagnosisInsteadOfClearedAsApplied() throws Exception {
    Path dbPath = tempDir.resolve("blank.db");
    persist(dbPath, "");
    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, mock(RunningRuntime.class));
      assertBuffered(queue, "");
    }
  }

  @Test
  @DisplayName("a missing parent retains the same VDU update across queue reopen")
  void missingParentRetainsSamePayloadAcrossQueueReopen() throws Exception {
    Path dbPath = tempDir.resolve("missing-parent.db");
    String payload = vduUpdatePayload(DOC_ID, CONTENT);
    persist(dbPath, payload);

    IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
    when(indexingCoordinator.updateDocument(eq(DOC_ID), anyMap())).thenReturn(false);
    RunningRuntime runtime =
        mockRuntime(parentFields(), indexingCoordinator, mock(CommitOps.class));

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, runtime);
      assertBuffered(queue, payload);
      verify(indexingCoordinator).updateDocument(eq(DOC_ID), anyMap());
    }

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      assertBuffered(queue, payload);
    }
  }

  @Test
  @DisplayName("chunk deletion failure retains the VDU update and prevents terminal parent write")
  void chunkDeletionFailureRetainsPayloadAndPreventsTerminalParentWrite() throws Exception {
    assertTrue(ChunkSplitter.splitWithMetadata(CONTENT).size() > 1);
    Path dbPath = tempDir.resolve("delete-failure.db");
    String payload = vduUpdatePayload(DOC_ID, CONTENT);
    persist(dbPath, payload);

    DocumentFieldOps documentFieldOps = parentFields();
    IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
    RuntimeException deletionFailure = new RuntimeException("old chunk deletion failed");
    when(indexingCoordinator.updateDocument(eq(DOC_ID), anyMap())).thenReturn(true);
    doThrow(deletionFailure).when(indexingCoordinator).deleteChunksForParentDocId(DOC_ID);
    RunningRuntime runtime =
        mockRuntime(documentFieldOps, indexingCoordinator, mock(CommitOps.class));

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, runtime);
      assertBuffered(queue, payload);
      verify(indexingCoordinator).deleteChunksForParentDocId(DOC_ID);
      verify(indexingCoordinator, never()).indexSingle(any(IndexDocument.class));
      verify(indexingCoordinator, never()).updateDocument(anyString(), anyMap());
    }
  }

  @Test
  @DisplayName("a later chunk insertion failure retains the VDU update and prevents terminal parent write")
  void laterChunkInsertionFailureRetainsPayloadAndPreventsTerminalParentWrite() throws Exception {
    assertTrue(ChunkSplitter.splitWithMetadata(CONTENT).size() > 1);
    Path dbPath = tempDir.resolve("insert-failure.db");
    String payload = vduUpdatePayload(DOC_ID, CONTENT);
    persist(dbPath, payload);

    DocumentFieldOps documentFieldOps = parentFields();
    IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
    RuntimeException insertionFailure = new RuntimeException("second chunk insertion failed");
    when(indexingCoordinator.updateDocument(eq(DOC_ID), anyMap())).thenReturn(true);
    doNothing()
        .doThrow(insertionFailure)
        .when(indexingCoordinator)
        .indexSingle(any(IndexDocument.class));
    RunningRuntime runtime =
        mockRuntime(documentFieldOps, indexingCoordinator, mock(CommitOps.class));

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, runtime);
      assertBuffered(queue, payload);
      verify(indexingCoordinator, times(2)).indexSingle(any(IndexDocument.class));
      verify(indexingCoordinator, never()).updateDocument(anyString(), anyMap());
    }
  }

  @Test
  @DisplayName("commit failure retains the payload and a retry clears it only after commit")
  void commitFailureRetainsPayloadAndRetryClearsAfterCommit() throws Exception {
    Path dbPath = tempDir.resolve("commit-failure.db");
    String payload = vduUpdatePayload(DOC_ID, CONTENT);
    persist(dbPath, payload);

    DocumentFieldOps documentFieldOps = parentFields();
    IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
    when(indexingCoordinator.updateDocument(eq(DOC_ID), anyMap())).thenReturn(true);
    doNothing().when(indexingCoordinator).indexSingle(any(IndexDocument.class));
    CommitOps commitOps = mock(CommitOps.class);
    RuntimeException commitFailure = new RuntimeException("replay commit failed");
    doThrow(commitFailure)
        .doNothing()
        .when(commitOps)
        .commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    RunningRuntime runtime =
        mockRuntime(documentFieldOps, indexingCoordinator, commitOps);

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      drain(queue, runtime);
      assertBuffered(queue, payload);
    }

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      assertBuffered(queue, payload);
      drain(queue, runtime);
      assertEquals(0, queue.switchBufferDepth());
      verify(commitOps, times(2)).commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);
    }
  }

  @Test
  @DisplayName("successful VDU replay observes its buffer before clearing it")
  void successfulReplayCommitsBeforeClearingBuffer() throws Exception {
    Path dbPath = tempDir.resolve("success.db");
    String payload = vduUpdatePayload(DOC_ID, CONTENT);
    persist(dbPath, payload);

    DocumentFieldOps documentFieldOps = parentFields();
    IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);
    when(indexingCoordinator.updateDocument(eq(DOC_ID), anyMap())).thenReturn(true);
    doNothing().when(indexingCoordinator).indexSingle(any(IndexDocument.class));
    CommitOps commitOps = mock(CommitOps.class);
    AtomicBoolean commitSawBufferedOperation = new AtomicBoolean();
    RunningRuntime runtime =
        mockRuntime(documentFieldOps, indexingCoordinator, commitOps);

    try (SqliteJobQueue queue = openQueue(dbPath)) {
      doAnswer(
              ignored -> {
                assertEquals(1, queue.listSwitchBufferOps().size());
                commitSawBufferedOperation.set(true);
                return null;
              })
          .when(commitOps)
          .commitAndTrack(CommitReason.SWITCH_BUFFER_REPLAY);

      drain(queue, runtime);

      assertTrue(commitSawBufferedOperation.get(), "commit must observe the durable replay row");
      assertEquals(0, queue.switchBufferDepth());
    }
  }

  private RunningRuntime mockRuntime(
      DocumentFieldOps documentFieldOps,
      IndexingCoordinator indexingCoordinator,
      CommitOps commitOps) {
    RunningRuntime runtime = mock(RunningRuntime.class);
    when(runtime.documentFieldOps()).thenReturn(documentFieldOps);
    when(runtime.indexingCoordinator()).thenReturn(indexingCoordinator);
    when(runtime.commitOps()).thenReturn(commitOps);
    return runtime;
  }

  private DocumentFieldOps parentFields() {
    DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
    when(documentFieldOps.getDocumentField(DOC_ID, SchemaFields.MIME)).thenReturn("text/plain");
    when(documentFieldOps.getDocumentField(DOC_ID, SchemaFields.MIME_BASE))
        .thenReturn("text/plain");
    when(documentFieldOps.getDocumentField(DOC_ID, SchemaFields.FILE_KIND)).thenReturn("text");
    when(documentFieldOps.getDocumentField(DOC_ID, SchemaFields.PARENT_TOKEN_COUNT))
        .thenReturn(null);
    when(documentFieldOps.getDocumentField(DOC_ID, SchemaFields.COLLECTION)).thenReturn(null);
    when(documentFieldOps.getDocumentField(DOC_ID, SchemaFields.DOC_UID)).thenReturn(PARENT_UID);
    return documentFieldOps;
  }

  private void drain(SqliteJobQueue queue, RunningRuntime runtime) {
    KnowledgeServerMigrationOps.drainSwitchBufferBestEffort(
        new KnowledgeServerMigrationOps.DrainSwitchBufferContext(
            queue,
            runtime,
            null,
            IndexingPacing.unthrottled(),
            tempDir.resolve("index-base"),
            tempDir.resolve("active-index"),
            new ObjectMapper(),
            () -> false, () -> true,
            LoggerFactory.getLogger(VduReplayFailureRetentionTest.class)));
  }

  private void persist(Path dbPath, String payload) throws Exception {
    try (SqliteJobQueue queue = openQueue(dbPath)) {
      assertTrue(queue.putSwitchBuffer("vdu_update:" + DOC_ID, "VDU_UPDATE", payload));
    }
  }

  private SqliteJobQueue openQueue(Path dbPath) throws Exception {
    SqliteJobQueue queue = new SqliteJobQueue(dbPath);
    queue.open();
    return queue;
  }

  private void assertBuffered(SqliteJobQueue queue, String expectedPayload) {
    assertEquals(1, queue.listSwitchBufferOps().size());
    SwitchBufferCapableQueue.SwitchBufferOp op = queue.listSwitchBufferOps().getFirst();
    assertEquals("vdu_update:" + DOC_ID, op.key());
    assertEquals("VDU_UPDATE", op.op());
    assertEquals(expectedPayload, op.payload());
  }

  private String vduUpdatePayload(String docId, String content) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("doc_id", docId);
    payload.put("extracted_content", content);
    payload.put("has_extracted_content", true);
    payload.put("vdu_status", "");
    payload.put("vdu_enrichment", "");
    payload.put("page_count", 0);
    payload.put("outcome", VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT.getNumber());
    return new ObjectMapper().writeValueAsString(payload);
  }
}
