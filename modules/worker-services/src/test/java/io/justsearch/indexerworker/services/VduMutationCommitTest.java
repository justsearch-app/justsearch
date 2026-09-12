/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Read the committed directory before close, with the periodic commit timer stopped. */
class VduMutationCommitTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;
  private static final String DOC = "vdu-parent";

  private WorkerIngestService service(RunningRuntime runtime) {
    return new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
  }

  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0))
        .atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private void seed(RunningRuntime runtime, String status, int retries) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, DOC, SchemaFields.DOC_UID, "vdu-identity",
        SchemaFields.CONTENT, "baseline content", SchemaFields.VDU_STATUS, status,
        SchemaFields.VDU_RETRY_COUNT, String.valueOf(retries))));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private String committedField(String field) throws IOException {
    try (var directory = FSDirectory.open(tempDir); var reader = DirectoryReader.open(directory)) {
      var hit = new IndexSearcher(reader).search(new TermQuery(new Term(SchemaFields.DOC_ID, DOC)), 1);
      assertEquals(1, hit.scoreDocs.length);
      return reader.storedFields().document(hit.scoreDocs[0].doc).get(field);
    }
  }

  @ParameterizedTest
  @EnumSource(value = VduUpdateOutcome.class, names = {
      "VDU_UPDATE_OUTCOME_SUCCESS_TEXT", "VDU_UPDATE_OUTCOME_SUCCESS_EMPTY",
      "VDU_UPDATE_OUTCOME_FAILED", "VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT"})
  void resultAcknowledgementCoversParentAndReplacementChunks(VduUpdateOutcome outcome) throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PROCESSING, 1);
      String content = "Durable extraction with enough words for multiple chunks. ".repeat(400);
      var result = service(runtime).updateVduResult(UpdateVduResultRequest.newBuilder()
          .setDocId(DOC).setOutcome(outcome).setExtractedContent(content).build(), CallContext.none());
      assertTrue(result.getSuccess(), result.getError());
      String expectedStatus = switch (outcome) {
        case VDU_UPDATE_OUTCOME_SUCCESS_TEXT -> SchemaFields.VDU_STATUS_COMPLETED;
        case VDU_UPDATE_OUTCOME_SUCCESS_EMPTY -> SchemaFields.VDU_STATUS_COMPLETED_EMPTY;
        case VDU_UPDATE_OUTCOME_FAILED -> SchemaFields.VDU_STATUS_FAILED;
        default -> SchemaFields.VDU_STATUS_REJECTED;
      };
      assertEquals(expectedStatus, committedField(SchemaFields.VDU_STATUS));
      if (outcome == VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT) {
        assertEquals(ChunkParentRevision.sha256Hex(content), committedField(SchemaFields.CONTENT_SHA256));
        try (var directory = FSDirectory.open(tempDir); var reader = DirectoryReader.open(directory)) {
          var chunks = new IndexSearcher(reader).search(
              new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, DOC)), 100);
          assertTrue(chunks.scoreDocs.length > 1, "chunks must share the covering commit");
          for (var chunk : chunks.scoreDocs) {
            assertEquals(ChunkParentRevision.sha256Hex(content), reader.storedFields()
                .document(chunk.doc).get(SchemaFields.CHUNK_PARENT_CONTENT_SHA256));
          }
        }
      }
    }
  }

  @Test
  void processingClaimAndRecoveryAreCommittedBeforeTheirCountsReturn() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 0);
      var service = service(runtime);
      var claim = service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setMaxRetries(3).build(), CallContext.none());
      assertTrue(claim.getSuccess(), claim.getError());
      assertEquals(1, claim.getRetryCount());
      assertEquals(SchemaFields.VDU_STATUS_PROCESSING, committedField(SchemaFields.VDU_STATUS));
      assertEquals("1", committedField(SchemaFields.VDU_RETRY_COUNT));
      assertEquals(1, service.recoverVduProcessing(RecoverVduProcessingRequest.getDefaultInstance(),
          CallContext.none()).getRecoveredCount());
      assertEquals(SchemaFields.VDU_STATUS_PENDING, committedField(SchemaFields.VDU_STATUS));
      assertEquals("1", committedField(SchemaFields.VDU_RETRY_COUNT));
      assertEquals(0, service.recoverVduProcessing(RecoverVduProcessingRequest.getDefaultInstance(),
          CallContext.none()).getRecoveredCount());
    }
  }

  @Test
  void exhaustedRetryRejectionCoversItsFailedStatus() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 3);
      var claim = service(runtime).markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setMaxRetries(3).build(), CallContext.none());
      assertFalse(claim.getSuccess());
      assertEquals("Max retries exceeded", claim.getError());
      assertEquals(SchemaFields.VDU_STATUS_FAILED, committedField(SchemaFields.VDU_STATUS));
    }
  }

  @ParameterizedTest
  @EnumSource(value = CommitReason.class, names = {"VDU_UPDATE", "VDU_MARK_PROCESSING", "VDU_RECOVERY"})
  void commitFailureCannotAcknowledgeAnEffect(CommitReason reason) throws Exception {
    var runtime = mock(RunningRuntime.class);
    var commits = mock(CommitOps.class);
    var fields = mock(DocumentFieldOps.class);
    var indexing = mock(IndexingCoordinator.class);
    when(runtime.commitOps()).thenReturn(commits);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(indexing.updateDocument(eq(DOC), anyMap())).thenReturn(true);
    when(fields.queryDocIdsByFieldOrThrow(anyString(), anyString(), anyInt())).thenReturn(List.of(DOC));
    var failure = new IllegalStateException("controlled commit failure");
    doThrow(failure).when(commits).commitAndTrack(reason);
    var service = service(runtime);
    switch (reason) {
      case VDU_UPDATE -> assertFalse(service.updateVduResult(UpdateVduResultRequest.newBuilder()
          .setDocId(DOC).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY).build(),
          CallContext.none()).getSuccess());
      case VDU_MARK_PROCESSING -> assertFalse(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).build(), CallContext.none()).getSuccess());
      case VDU_RECOVERY -> {
        var thrown = assertThrows(WorkerServiceException.class, () -> service.recoverVduProcessing(
            RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none()));
        assertEquals(WorkerServiceException.Status.INTERNAL, thrown.status());
        assertSame(failure, thrown.getCause());
      }
      default -> fail("unexpected test reason");
    }
    verify(commits, never()).maybeRefreshBlocking();
  }

  @Test
  void recoveryReaderFailureCannotReportZeroRecovered() throws Exception {
    var runtime = mock(RunningRuntime.class);
    var fields = mock(DocumentFieldOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    var failure = new IOException("controlled recovery reader failure");
    when(fields.queryDocIdsByFieldOrThrow(anyString(), anyString(), anyInt())).thenThrow(failure);
    var thrown = assertThrows(WorkerServiceException.class, () -> service(runtime).recoverVduProcessing(
        RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none()));
    assertSame(failure, thrown.getCause());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedChunkReplacementStaysRecoverableEvenAfterAnUnrelatedCommit(boolean failInsertion) throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PROCESSING, 1);
      var indexing = spy(runtime.indexingCoordinator());
      var failure = new IllegalStateException("controlled chunk replacement failure");
      if (failInsertion) {
        var inserted = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
          if (inserted.incrementAndGet() == 2) throw failure;
          return invocation.callRealMethod();
        }).when(indexing).indexSingle(any(IndexDocument.class));
      } else {
        doThrow(failure).when(indexing).deleteChunksForParentDocId(DOC);
      }
      var facade = mock(RunningRuntime.class);
      when(facade.indexingCoordinator()).thenReturn(indexing);
      when(facade.documentFieldOps()).thenReturn(runtime.documentFieldOps());
      when(facade.commitOps()).thenReturn(runtime.commitOps());
      String content = "Replacement text long enough to create several chunks. ".repeat(400);
      var result = service(facade).updateVduResult(UpdateVduResultRequest.newBuilder()
          .setDocId(DOC).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(content).build(), CallContext.none());
      assertFalse(result.getSuccess());
      runtime.commitOps().commitAndTrack(CommitReason.TIMER);
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(SchemaFields.VDU_STATUS_PROCESSING, committedField(SchemaFields.VDU_STATUS));
      assertEquals("baseline content", runtime.documentFieldOps().getDocumentContent(DOC));
      assertEquals(1, service(runtime).recoverVduProcessing(RecoverVduProcessingRequest.getDefaultInstance(),
          CallContext.none()).getRecoveredCount());
      assertEquals(SchemaFields.VDU_STATUS_PENDING, committedField(SchemaFields.VDU_STATUS));
    }
  }

  @Test
  void allFailedResetsCannotReportSuccessfulZero() throws Exception {
    var runtime = mock(RunningRuntime.class);
    var fields = mock(DocumentFieldOps.class);
    var indexing = mock(IndexingCoordinator.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(fields.queryDocIdsByFieldOrThrow(anyString(), anyString(), anyInt()))
        .thenReturn(List.of("first", "second"));
    var failure = new IllegalStateException("reset failed");
    when(indexing.updateDocument(anyString(), anyMap())).thenThrow(failure);
    var thrown = assertThrows(WorkerServiceException.class, () -> service(runtime).recoverVduProcessing(
        RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none()));
    assertEquals(WorkerServiceException.Status.INTERNAL, thrown.status());
    assertSame(failure, thrown.getCause().getCause());
    verify(indexing).updateDocument(eq("first"), anyMap());
    verify(indexing).updateDocument(eq("second"), anyMap());
  }

  @Test
  void retryReadFailureCannotResetTheCountAndStartAnotherAttempt() throws Exception {
    var runtime = mock(RunningRuntime.class);
    var fields = mock(DocumentFieldOps.class);
    var indexing = mock(IndexingCoordinator.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(fields.getDocumentFieldOrThrow(DOC, SchemaFields.VDU_RETRY_COUNT))
        .thenThrow(new IOException("retry count unreadable"));
    var result = service(runtime).markVduProcessing(MarkVduProcessingRequest.newBuilder()
        .setDocId(DOC).setMaxRetries(3).build(), CallContext.none());
    assertFalse(result.getSuccess());
    assertEquals("retry count unreadable", result.getError());
    verifyNoInteractions(indexing);
  }
}
