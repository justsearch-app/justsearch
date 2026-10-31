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
  private static final String ATTEMPT="01b18ddd-e0e3-41b3-bc9f-a991965dbff8";
  private static final String DOC = "vdu-parent";

  private WorkerIngestService service(RunningRuntime runtime) {
    return new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
  }

  private RunningRuntime open() {
    var runtime = IndexSchema.fromCatalog(VduRevisionTestSupport.catalog(0))
        .atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    return runtime;
  }

  private void seed(RunningRuntime runtime, String status, int retries) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, DOC, SchemaFields.DOC_UID, "vdu-identity",
        SchemaFields.SOURCE_SHA256,"a".repeat(64), SchemaFields.CONTENT, "baseline content", SchemaFields.VDU_STATUS, status,
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
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 0);
      var service=service(runtime);var ctx=procedureContext();
      assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(DOC).setAttemptId(ATTEMPT).build(),ctx).getSuccess());
      String content = "Durable extraction with enough words for multiple chunks. ".repeat(400);
      var result = service.updateVduResult(UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64))
          .setDocId(DOC).setAttemptId(ATTEMPT).setOutcome(outcome).setExtractedContent(content).build(), ctx);
      assertTrue(result.getSuccess(), result.getError());
      String expectedStatus = switch (outcome) {
        case VDU_UPDATE_OUTCOME_SUCCESS_TEXT -> SchemaFields.VDU_STATUS_COMPLETED;
        case VDU_UPDATE_OUTCOME_SUCCESS_EMPTY -> SchemaFields.VDU_STATUS_COMPLETED_EMPTY;
        case VDU_UPDATE_OUTCOME_FAILED -> SchemaFields.VDU_STATUS_PENDING;
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
  void supplementaryVduReplacementIsExactAfterCommitAndReopen() throws Exception {
    String content = "a" + "\uD83D\uDE00\uD840\uDC00".repeat(3000);
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 0);
      var service = service(runtime);
      var context = procedureContext();
      assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setAttemptId(ATTEMPT).build(), context).getSuccess());
      var result = service.updateVduResult(UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64))
          .setDocId(DOC).setAttemptId(ATTEMPT).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(content).build(), context);
      assertTrue(result.getSuccess(), result.getError());
    }
    try (var runtime = open()) {
      assertEquals(content, runtime.documentFieldOps().getDocumentField(DOC, SchemaFields.CONTENT));
      var chunks = runtime.documentFieldOps().queryDocIdsByField(SchemaFields.PARENT_DOC_ID, DOC, 100);
      assertTrue(chunks.size() > 1);
      for (String chunkId : chunks) {
        int start = Integer.parseInt(runtime.documentFieldOps().getDocumentField(chunkId, SchemaFields.CHUNK_START_CHAR));
        int end = Integer.parseInt(runtime.documentFieldOps().getDocumentField(chunkId, SchemaFields.CHUNK_END_CHAR));
        assertFalse(start > 0 && Character.isHighSurrogate(content.charAt(start - 1))
            && Character.isLowSurrogate(content.charAt(start)));
        assertFalse(end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))
            && Character.isLowSurrogate(content.charAt(end)));
        var hit = runtime.readPathOps().search(new TermQuery(new Term(SchemaFields.DOC_ID, chunkId)),
            1, java.util.Set.of(SchemaFields.CHUNK_CONTENT),
            io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null).hits().get(0);
        assertEquals(content.substring(start, end), hit.fields().get(SchemaFields.CHUNK_CONTENT));
        assertEquals("PENDING", runtime.documentFieldOps().getDocumentField(chunkId, SchemaFields.CHUNK_EMBEDDING_STATUS));
        assertEquals("0", runtime.documentFieldOps().getDocumentField(chunkId, SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
      }
    }
  }

  @Test
  void processingClaimAndRecoveryAreCommittedBeforeTheirCountsReturn() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 0);
      var service = service(runtime);
      var claim = service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setAttemptId(ATTEMPT).setMaxRetries(3).build(), procedureContext());
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

  @Test void successWinningShutdownResetKeepsItsTerminalStateAndText() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 0);
      var fields = spy(runtime.documentFieldOps());
      var facade = mock(RunningRuntime.class);
      when(facade.indexingCoordinator()).thenReturn(runtime.indexingCoordinator());
      when(facade.documentFieldOps()).thenReturn(fields);
      when(facade.commitOps()).thenReturn(runtime.commitOps());
      var service = service(facade);
      var ctx = procedureContext();
      assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setAttemptId(ATTEMPT).setMaxRetries(3).build(), ctx).getSuccess());
      doAnswer(call -> {
        Object selected = call.callRealMethod();
        assertTrue(service.updateVduResult(success(), ctx).getSuccess());
        return selected;
      }).when(fields).queryDocIdsByFieldOrThrow(eq(SchemaFields.VDU_STATUS),
          eq(SchemaFields.VDU_STATUS_PROCESSING), anyInt());
      service.recoverVduProcessing(ownedRecovery(ctx), ctx);
      assertEquals(SchemaFields.VDU_STATUS_COMPLETED, committedField(SchemaFields.VDU_STATUS),
          "a selected PROCESSING document cannot reset a later terminal success");
      assertEquals("new successful text", committedField(SchemaFields.CONTENT));
    }
  }

  @Test void shutdownResetWinningLateSuccessKeepsPendingWithOriginalText() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 0);
      var service = service(runtime);
      var ctx = procedureContext();
      assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setAttemptId(ATTEMPT).setMaxRetries(3).build(), ctx).getSuccess());
      assertEquals(1, service.recoverVduProcessing(ownedRecovery(ctx), ctx).getRecoveredCount());
      var result = service.updateVduResult(success(), ctx);
      assertFalse(result.getSuccess(), "a reset retires the abandoned procedure's terminal authority");
      assertEquals(SchemaFields.VDU_STATUS_PENDING, committedField(SchemaFields.VDU_STATUS));
      assertEquals("baseline content", committedField(SchemaFields.CONTENT));
    }
  }

  @Test void shutdownResetCannotResetAnotherProceduresClaim() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 0);
      var service = service(runtime);
      var abandoned = procedureContext();
      var current = procedureContext();
      assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setAttemptId(ATTEMPT).setMaxRetries(3).build(), current).getSuccess());
      assertEquals(0, service.recoverVduProcessing(ownedRecovery(abandoned), abandoned).getRecoveredCount());
      assertEquals(SchemaFields.VDU_STATUS_PROCESSING, committedField(SchemaFields.VDU_STATUS));
      assertTrue(service.updateVduResult(success(), current).getSuccess());
      assertEquals(SchemaFields.VDU_STATUS_COMPLETED, committedField(SchemaFields.VDU_STATUS));
    }
  }

  private static UpdateVduResultRequest success() {
    return UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC).setAttemptId(ATTEMPT)
        .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
        .setExtractedContent("new successful text").build();
  }

  private static CallContext procedureContext() {
    var base = CallContext.none();
    return new CallContext(null, null, null,
        base.engineContext().withWorkId(java.util.UUID.randomUUID()),
        base.provenance(), base.childLifetime());
  }

  private static RecoverVduProcessingRequest ownedRecovery(CallContext ctx) throws Exception {
    var builder = RecoverVduProcessingRequest.newBuilder();
    try { builder.getClass().getMethod("setProcedureOwner", String.class)
        .invoke(builder, ctx.engineContext().workId().orElseThrow().toString()); }
    catch (NoSuchMethodException beforeScopedRecovery) { /* exercise the previous reset */ }
    builder.addAttemptIds(ATTEMPT);
    return builder.build();
  }

  @Test
  void exhaustedRetryRejectionCoversItsFailedStatus() throws Exception {
    try (var runtime = open()) {
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 3);
      var claim = service(runtime).markVduProcessing(MarkVduProcessingRequest.newBuilder()
          .setDocId(DOC).setAttemptId(ATTEMPT).setMaxRetries(3).build(), procedureContext());
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
    Map<String,String> state=new java.util.HashMap<>(Map.of(SchemaFields.SOURCE_SHA256,"a".repeat(64),
        SchemaFields.CONTENT,"baseline",SchemaFields.DOC_UID,"uid",SchemaFields.VDU_STATUS,"PENDING",SchemaFields.VDU_RETRY_COUNT,"0"));
    when(fields.getEnrichmentInputs(List.of(DOC))).thenAnswer(call -> Map.of(DOC,new DocumentFieldOps.EnrichmentInput("baseline",Map.copyOf(state),null,Map.of())));
    when(fields.getDocumentFieldOrThrow(DOC,SchemaFields.VDU_RETRY_COUNT)).thenAnswer(call->state.get(SchemaFields.VDU_RETRY_COUNT));
    when(indexing.updateDocument(eq(DOC),anyMap())).thenAnswer(call->{ Map<String,Object> updates=call.getArgument(1);updates.forEach((k,v)->state.put(k,v.toString()));return true; });
    stubRecoveryUpdates(indexing,call->true);
    when(fields.queryDocIdsByFieldOrThrow(anyString(),anyString(),anyInt())).thenReturn(List.of(DOC));
    var service=service(runtime);var ctx=procedureContext();
    if(reason==CommitReason.VDU_UPDATE) assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(DOC).setAttemptId(ATTEMPT).build(),ctx).getSuccess());
    clearInvocations(commits);
    var failure=new IllegalStateException("controlled commit failure");doThrow(failure).when(commits).commitAndTrack(reason);
    switch(reason) {
      case VDU_UPDATE -> assertFalse(service.updateVduResult(UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC).setAttemptId(ATTEMPT).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY).build(),ctx).getSuccess());
      case VDU_MARK_PROCESSING -> assertFalse(service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(DOC).setAttemptId(ATTEMPT).build(),ctx).getSuccess());
      case VDU_RECOVERY -> { var thrown=assertThrows(WorkerServiceException.class,()->service.recoverVduProcessing(RecoverVduProcessingRequest.getDefaultInstance(),CallContext.none()));assertEquals(WorkerServiceException.Status.INTERNAL,thrown.status());assertSame(failure,thrown.getCause()); }
      default -> fail("unexpected test reason");
    }
    var order=inOrder(commits);
    if(reason!=CommitReason.VDU_RECOVERY) order.verify(commits).maybeRefreshBlocking();
    order.verify(commits).commitAndTrack(reason);
    order.verify(commits,never()).maybeRefreshBlocking();
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
      seed(runtime, SchemaFields.VDU_STATUS_PENDING, 0);
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
      var service=service(facade);var ctx=procedureContext();
      assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(DOC).setAttemptId(ATTEMPT).build(),ctx).getSuccess());
      String content = "Replacement text long enough to create several chunks. ".repeat(400);
      var result = service.updateVduResult(UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64))
          .setDocId(DOC).setAttemptId(ATTEMPT).setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(content).build(), ctx);
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
    stubRecoveryUpdates(indexing, call -> { throw failure; });
    var thrown = assertThrows(WorkerServiceException.class, () -> service(runtime).recoverVduProcessing(
        RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none()));
    assertEquals(WorkerServiceException.Status.INTERNAL, thrown.status());
    assertSame(failure, thrown.getCause().getCause());
    verifyRecoveryUpdate(indexing, "first");
    verifyRecoveryUpdate(indexing, "second");
  }

  // Keep the same commit/failure oracles executable on the starting revision, whose recovery
  // used unconditional updateDocument. Only the mock's method selection follows the new CAS API.
  private static void stubRecoveryUpdates(
      IndexingCoordinator indexing, org.mockito.stubbing.Answer<Boolean> answer) throws Exception {
    try {
      var conditional = IndexingCoordinator.class.getMethod(
          "updateDocumentIf", String.class, Map.class, Map.class);
      conditional.invoke(doAnswer(answer).when(indexing), anyString(), anyMap(), anyMap());
    } catch (NoSuchMethodException beforeConditionalRecovery) {
      doAnswer(answer).when(indexing).updateDocument(anyString(), anyMap());
    }
  }

  private static void verifyRecoveryUpdate(IndexingCoordinator indexing, String docId)
      throws Exception {
    try {
      var conditional = IndexingCoordinator.class.getMethod(
          "updateDocumentIf", String.class, Map.class, Map.class);
      conditional.invoke(verify(indexing), eq(docId), anyMap(), anyMap());
    } catch (NoSuchMethodException beforeConditionalRecovery) {
      verify(indexing).updateDocument(eq(docId), anyMap());
    }
  }

  @Test
  void retryReadFailureCannotResetTheCountAndStartAnotherAttempt() throws Exception {
    var runtime=mock(RunningRuntime.class);var fields=mock(DocumentFieldOps.class);var indexing=mock(IndexingCoordinator.class);
    when(runtime.documentFieldOps()).thenReturn(fields);when(runtime.indexingCoordinator()).thenReturn(indexing);
    when(runtime.commitOps()).thenReturn(mock(CommitOps.class));
    when(fields.getEnrichmentInputs(List.of(DOC))).thenReturn(Map.of(DOC,new DocumentFieldOps.EnrichmentInput(
        "baseline",Map.of(SchemaFields.SOURCE_SHA256,"a".repeat(64)),null,Map.of())));
    when(fields.getDocumentFieldOrThrow(DOC,SchemaFields.VDU_RETRY_COUNT)).thenThrow(new IOException("retry count unreadable"));
    var result=service(runtime).markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(DOC).setAttemptId(ATTEMPT).build(),procedureContext());
    assertFalse(result.getSuccess());assertEquals("retry count unreadable",result.getError());verifyNoInteractions(indexing);
  }

  @Test
  void dropoutReadFailureCannotPublishAnIncorrectTerminalReason() throws Exception {
    try(var runtime=open()) {
      seed(runtime,SchemaFields.VDU_STATUS_PENDING,0);
      var fields=spy(runtime.documentFieldOps());var indexing=spy(runtime.indexingCoordinator());var facade=mock(RunningRuntime.class);
      when(facade.documentFieldOps()).thenReturn(fields);when(facade.indexingCoordinator()).thenReturn(indexing);when(facade.commitOps()).thenReturn(runtime.commitOps());
      var service=service(facade);var ctx=procedureContext();
      assertTrue(service.markVduProcessing(MarkVduProcessingRequest.newBuilder().setDocId(DOC).setAttemptId(ATTEMPT).build(),ctx).getSuccess());
      clearInvocations(indexing);
      doThrow(new IOException("dropout reason unreadable")).when(fields).getDocumentFieldOrThrow(DOC,SchemaFields.EXTRACTION_REASON_CODE);
      var result=service.updateVduResult(UpdateVduResultRequest.newBuilder().setSourceSha256("a".repeat(64)).setDocId(DOC).setAttemptId(ATTEMPT)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY).build(),ctx);
      assertFalse(result.getSuccess());assertEquals("dropout reason unreadable",result.getError());verifyNoInteractions(indexing);
    }
  }
}
