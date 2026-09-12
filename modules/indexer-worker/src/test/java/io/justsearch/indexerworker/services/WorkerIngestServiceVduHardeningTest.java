package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import io.justsearch.ipc.VduUpdateOutcome;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for P0.4 Worker-side VDU hardening.
 *
 * <p>Verifies that when Head sends vdu_status=COMPLETED but extracted_content is blank,
 * the Worker coerces the status to FAILED with a "no_text_detected" enrichment.
 */
@DisplayName("WorkerIngestService VDU Hardening (P0.4)")
final class WorkerIngestServiceVduHardeningTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;
  private SqliteJobQueue jobQueue;
  private RunningRuntime lifecycle;
  private WorkerIngestService service;
  @BeforeEach
  void setUp() throws Exception {
    Path dbPath = tempDir.resolve("jobs.db");
    jobQueue = new SqliteJobQueue(dbPath);
    jobQueue.open();

    // Use chunk-aware testing catalog with explicit vdu_retry_count support for markVduProcessing tests.
    lifecycle = io.justsearch.adapters.lucene.runtime.IndexSchema.fromCatalog(FieldCatalogDef.forChunkTestingWithVduRetryCount(0)).atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();

    // Create service with the real lifecycle
    IndexingLoop stubLoop = stubIndexingLoop();
    WorkerSignalBus stubBus = new StubWorkerSignalBus();
    Path indexBasePath = tempDir.resolve("indexBase");
    Files.createDirectories(indexBasePath);
    Path indexPath = tempDir.resolve("index");
    Files.createDirectories(indexPath);
    service = new WorkerIngestService(
        jobQueue, stubLoop, stubBus, IndexingPacing.unthrottled(), indexBasePath, indexPath,
        lifecycle, lifecycle, null, 0L);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
    if (jobQueue != null) {
      jobQueue.close();
    }
  }

  @Nested
  @DisplayName("Legacy COMPLETED + blank content behavior")
  class LegacyCompletedBlankContentBehavior {

    @Test
    @DisplayName("legacy COMPLETED with empty content is rejected (invariant violation)")
    void legacyCompletedWithEmptyContentRejected() throws Exception {
      // Create a document in the index first
      String docId = indexTestDocument("doc1");

      // Send updateVduResult with legacy COMPLETED but empty content
      // New behavior: legacy "COMPLETED" is mapped to SUCCESS_TEXT, which requires non-blank content
      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setExtractedContent("")  // Empty content
          .setVduStatus(SchemaFields.VDU_STATUS_COMPLETED)
          .setVduEnrichment("{\"pages\":3}")
          .setPageCount(3)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      // Should be rejected (invariant violation: SUCCESS_TEXT requires non-blank)
      assertTrue(!response.getSuccess(),
          "Legacy COMPLETED with blank content should be rejected");
    }

    @Test
    @DisplayName("legacy COMPLETED with whitespace-only content is rejected")
    void legacyCompletedWithWhitespaceRejected() throws Exception {
      String docId = indexTestDocument("doc2");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setExtractedContent("   \t\n  ")  // Whitespace only
          .setVduStatus(SchemaFields.VDU_STATUS_COMPLETED)
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      // New behavior: should be rejected
      assertTrue(!response.getSuccess(),
          "Legacy COMPLETED with whitespace-only content should be rejected");
    }

    @Test
    @DisplayName("legacy COMPLETED_EMPTY status is accepted and sets status correctly")
    void legacyCompletedEmptyAccepted() throws Exception {
      String docId = indexTestDocument("doc3");
      String originalContent = lifecycle.documentFieldOps().getDocumentContent(docId);

      // Head sends legacy COMPLETED_EMPTY status
      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setExtractedContent("")
          .setVduStatus("COMPLETED_EMPTY")  // New legacy value recognized
          .setVduEnrichment("{\"error\":\"no_text_detected\"}")
          .setPageCount(5)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      assertTrue(response.getSuccess());

      // Status should be COMPLETED_EMPTY
      String status = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_STATUS);
      assertEquals("COMPLETED_EMPTY", status);

      // Content should NOT be overwritten
      String content = lifecycle.documentFieldOps().getDocumentContent(docId);
      assertEquals(originalContent, content);
    }

    @Test
    @DisplayName("legacy COMPLETED with non-blank content succeeds")
    void legacyCompletedWithNonBlankContentSucceeds() throws Exception {
      String docId = indexTestDocument("doc4");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setExtractedContent("This is actual VDU-extracted text.")
          .setVduStatus(SchemaFields.VDU_STATUS_COMPLETED)
          .setVduEnrichment("{\"summary\":\"test\"}")
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      assertTrue(response.getSuccess());

      // Should remain COMPLETED
      String status = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_STATUS);
      assertEquals(SchemaFields.VDU_STATUS_COMPLETED, status,
          "Should keep COMPLETED status when content is non-blank");

      // Content should be updated
      String content = lifecycle.documentFieldOps().getDocumentContent(docId);
      assertEquals("This is actual VDU-extracted text.", content);
    }
  }

  @Nested
  @DisplayName("FAILED status passthrough")
  class FailedStatusPassthrough {

    @Test
    @DisplayName("passes through FAILED status without modification")
    void passesThroughFailedStatus() throws Exception {
      String docId = indexTestDocument("doc6");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setExtractedContent("")
          .setVduStatus(SchemaFields.VDU_STATUS_FAILED)
          .setVduEnrichment("{\"error\":\"extraction_timeout\"}")
          .setPageCount(0)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      assertTrue(response.getSuccess());

      String status = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_STATUS);
      assertEquals(SchemaFields.VDU_STATUS_FAILED, status);

      String enrichment = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_ENRICHMENT);
      assertEquals("{\"error\":\"extraction_timeout\"}", enrichment);
    }
  }

  @Nested
  @DisplayName("REJECTED_SUSPECT_TEXT retains baseline (tempdoc 677 abstention gate)")
  class RejectedSuspectTextSemantics {

    @Test
    @DisplayName("REJECTED_SUSPECT_TEXT keeps baseline content despite non-blank extracted text")
    void rejectedSuspectTextRetainsBaseline() throws Exception {
      String docId = indexTestDocument("rejected1");
      String originalContent = lifecycle.documentFieldOps().getDocumentContent(docId);
      String originalEmbeddingStatus =
          lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS);

      // The gate rejected fluent-but-suspect model output — it arrives non-blank by definition.
      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT)
          .setExtractedContent("A confident bibliography of mathematics history books.")
          .setVduEnrichment("{\"gate\":{\"stage\":\"logprob\",\"meanLogprob\":-1.9}}")
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      assertTrue(response.getSuccess());

      // Honest terminal status, and the fabricated text must NOT reach the index.
      assertEquals(
          SchemaFields.VDU_STATUS_REJECTED,
          lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_STATUS));
      assertEquals(
          originalContent,
          lifecycle.documentFieldOps().getDocumentContent(docId),
          "rejected output must not overwrite baseline content");
      assertEquals(
          originalEmbeddingStatus,
          lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS),
          "rejection must not trigger re-embedding");
      // Gate evidence travels via the enrichment JSON (honest evidence trail).
      assertEquals(
          "{\"gate\":{\"stage\":\"logprob\",\"meanLogprob\":-1.9}}",
          lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_ENRICHMENT));
    }
  }

  @Nested
  @DisplayName("Explicit VduUpdateOutcome semantics (Phase A)")
  class ExplicitOutcomeSemantics {

    @Test
    @DisplayName("SUCCESS_TEXT requires non-blank content, rejects if blank")
    void successTextRejectsBlankContent() throws Exception {
      String docId = indexTestDocument("outcome1");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent("")  // Blank content with SUCCESS_TEXT = invariant violation
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      // Should fail: SUCCESS_TEXT requires non-blank content
      assertTrue(!response.getSuccess() || response.getError().contains("non-blank"),
          "SUCCESS_TEXT with blank content should fail or set error");
    }

    @Test
    @DisplayName("SUCCESS_TEXT with content updates index and regenerates chunks")
    void successTextWithContentUpdatesAndChunks() throws Exception {
      String docId = indexTestDocument("outcome2");

      // Create long content to trigger chunking
      String longContent = "VDU extracted text. ".repeat(200);  // ~4000 chars
      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(longContent)
          .setVduEnrichment("{\"summary\":\"test\"}")
          .setPageCount(3)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      assertTrue(response.getSuccess(), "Should succeed");

      // Verify status is COMPLETED (not COMPLETED_EMPTY or FAILED)
      String status = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_STATUS);
      assertEquals("COMPLETED", status);

      // Verify content was updated
      String content = lifecycle.documentFieldOps().getDocumentContent(docId);
      assertTrue(content != null && content.contains("VDU extracted text"),
          "Content should be updated");

      // Verify embedding_status is PENDING (should trigger re-embedding)
      String embeddingStatus = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS);
      assertEquals(SchemaFields.EMBEDDING_STATUS_PENDING, embeddingStatus);
    }

    @Test
    @DisplayName("SUCCESS_EMPTY does not overwrite content or trigger embedding")
    void successEmptyDoesNotOverwriteContent() throws Exception {
      String docId = indexTestDocument("outcome3");
      String originalContent = lifecycle.documentFieldOps().getDocumentContent(docId);

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY)
          // Note: not setting extractedContent at all (proto3 optional)
          .setVduEnrichment("{\"error\":\"no_text_detected\"}")
          .setPageCount(2)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      assertTrue(response.getSuccess());

      // Verify status is COMPLETED_EMPTY
      String status = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_STATUS);
      assertEquals("COMPLETED_EMPTY", status);

      // Content should NOT be changed
      String content = lifecycle.documentFieldOps().getDocumentContent(docId);
      assertEquals(originalContent, content, "Content should not be overwritten");

      // Embedding status should NOT be set to PENDING
      String embeddingStatus = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.EMBEDDING_STATUS);
      assertTrue(embeddingStatus == null || embeddingStatus.isEmpty() ||
                 !embeddingStatus.equals(SchemaFields.EMBEDDING_STATUS_PENDING),
          "SUCCESS_EMPTY should not trigger embedding");
    }

    @Test
    @DisplayName("FAILED does not overwrite content")
    void failedDoesNotOverwriteContent() throws Exception {
      String docId = indexTestDocument("outcome4");
      String originalContent = lifecycle.documentFieldOps().getDocumentContent(docId);

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED)
          .setVduEnrichment("{\"error\":\"timeout\"}")
          .setPageCount(0)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      assertTrue(response.getSuccess());

      String status = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_STATUS);
      assertEquals("FAILED", status);

      String content = lifecycle.documentFieldOps().getDocumentContent(docId);
      assertEquals(originalContent, content, "Content should not be overwritten on FAILED");
    }

    @Test
    @DisplayName("outcome takes precedence over legacy vdu_status")
    void outcomeTakesPrecedenceOverLegacyStatus() throws Exception {
      String docId = indexTestDocument("outcome5");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY)
          .setVduStatus("COMPLETED")  // Legacy status says COMPLETED, but outcome is SUCCESS_EMPTY
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());

      assertTrue(response.getSuccess());

      // Outcome should win
      String status = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_STATUS);
      assertEquals("COMPLETED_EMPTY", status,
          "Explicit outcome should take precedence over legacy vdu_status");
    }
  }

  @Nested
  @DisplayName("SWITCHING fallback behavior")
  class SwitchingFallbackBehavior {

    @Test
    @DisplayName("updateVduResult returns UNAVAILABLE when SWITCHING and queue is not SQLite")
    void updateVduResultReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
      String docId = indexTestDocument("switching-update-vdu");
      WorkerIngestService switchingService =
          createSwitchingServiceWithQueue(new NoopJobQueue(), "nonsqlite-vdu-update");

      UpdateVduResultRequest request =
          UpdateVduResultRequest.newBuilder()
              .setDocId(docId)
              .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED)
              .build();

      assertSwitchingUnavailable(
          assertThrows(
              WorkerServiceException.class,
              () -> switchingService.updateVduResult(request, CallContext.none())));
    }

    @Test
    @DisplayName("markVduProcessing returns UNAVAILABLE when SWITCHING and queue is not SQLite")
    void markVduProcessingReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
      String docId = indexTestDocument("switching-mark-vdu");
      WorkerIngestService switchingService =
          createSwitchingServiceWithQueue(new NoopJobQueue(), "nonsqlite-vdu-mark");

      MarkVduProcessingRequest request =
          MarkVduProcessingRequest.newBuilder().setDocId(docId).setMaxRetries(3).build();

      assertSwitchingUnavailable(
          assertThrows(
              WorkerServiceException.class,
              () -> switchingService.markVduProcessing(request, CallContext.none())));
    }

    @Test
    @DisplayName("deleteById returns UNAVAILABLE when SWITCHING and queue is not SQLite")
    void deleteByIdReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
      String docId = indexTestDocument("switching-delete-id");
      WorkerIngestService switchingService =
          createSwitchingServiceWithQueue(new NoopJobQueue(), "nonsqlite-delete-id");

      DeleteByIdRequest request = DeleteByIdRequest.newBuilder().setDocId(docId).build();

      assertSwitchingUnavailable(
          assertThrows(
              WorkerServiceException.class,
              () -> switchingService.deleteById(request, CallContext.none())));
    }

    @Test
    @DisplayName("deleteByPath returns UNAVAILABLE when SWITCHING and queue is not SQLite")
    void deleteByPathReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
      indexTestDocument("switching-delete-path");
      WorkerIngestService switchingService =
          createSwitchingServiceWithQueue(new NoopJobQueue(), "nonsqlite-delete-path");

      DeleteByPathRequest request =
          DeleteByPathRequest.newBuilder().setPath(tempDir.toAbsolutePath().toString()).build();

      assertSwitchingUnavailable(
          assertThrows(
              WorkerServiceException.class,
              () -> switchingService.deleteByPath(request, CallContext.none())));
    }

    @Test
    @DisplayName("pruneMissing returns UNAVAILABLE when SWITCHING and queue is not SQLite")
    void pruneMissingReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
      indexTestDocument("switching-prune-missing");
      WorkerIngestService switchingService =
          createSwitchingServiceWithQueue(new NoopJobQueue(), "nonsqlite-prune-missing");

      PruneRequest request =
          PruneRequest.newBuilder().setPathPrefix(tempDir.toAbsolutePath().toString()).build();

      assertSwitchingUnavailable(
          assertThrows(
              WorkerServiceException.class,
              () -> switchingService.pruneMissing(request, CallContext.none())));
    }

    @Test
    @DisplayName("recoverVduProcessing returns UNAVAILABLE when SWITCHING and queue is not SQLite")
    void recoverVduProcessingReturnsUnavailableWhenSwitchingAndQueueIsNotSqlite() throws Exception {
      indexTestDocument("switching-recover-vdu");
      WorkerIngestService switchingService =
          createSwitchingServiceWithQueue(new NoopJobQueue(), "nonsqlite-vdu-recover");

      assertSwitchingUnavailable(
          assertThrows(
              WorkerServiceException.class,
              () ->
                  switchingService.recoverVduProcessing(
                      RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none())));
    }

    @Test
    @DisplayName("updateVduResult buffers VDU_UPDATE op when SWITCHING and queue is SQLite")
    void updateVduResultBuffersSwitchOpWhenSwitchingAndQueueIsSqlite() throws Exception {
      String docId = indexTestDocument("switching-buffer-vdu-update");
      WorkerIngestService switchingService = createSwitchingServiceWithQueue(jobQueue, "sqlite-vdu-update");

      UpdateVduResultResponse response =
          switchingService.updateVduResult(
              UpdateVduResultRequest.newBuilder()
                  .setDocId(docId)
                  .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY)
                  .setPageCount(2)
                  .build(),
              CallContext.none());

      assertTrue(response.getSuccess(), "Expected buffered ACK while switching");

      SqliteJobQueue.SwitchBufferOp op = requireSwitchOp("vdu_update:" + docId);
      assertEquals("VDU_UPDATE", op.op());

      @SuppressWarnings("unchecked")
      Map<String, Object> payload = JSON.readValue(op.payload(), Map.class);
      assertEquals(docId, payload.get("doc_id"));
      assertEquals(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY.getNumber(), payload.get("outcome"));
      assertEquals(2, payload.get("page_count"));
    }

    @Test
    @DisplayName("markVduProcessing buffers PROCESSING op with incremented retry when SWITCHING")
    void markVduProcessingBuffersProcessingOpWhenSwitchingAndQueueIsSqlite() throws Exception {
      String docId = indexTestDocument("switching-buffer-vdu-mark-processing");
      WorkerIngestService switchingService = createSwitchingServiceWithQueue(jobQueue, "sqlite-vdu-mark-processing");

      MarkVduProcessingResponse response =
          switchingService.markVduProcessing(
              MarkVduProcessingRequest.newBuilder().setDocId(docId).setMaxRetries(3).build(),
              CallContext.none());

      assertTrue(response.getSuccess(), "Expected buffered processing ACK while switching");
      assertEquals(1, response.getRetryCount());

      SqliteJobQueue.SwitchBufferOp op = requireSwitchOp("vdu_mark:" + docId);
      assertEquals("VDU_MARK_PROCESSING", op.op());

      @SuppressWarnings("unchecked")
      Map<String, Object> payload = JSON.readValue(op.payload(), Map.class);
      assertEquals(docId, payload.get("doc_id"));
      assertEquals(1, payload.get("retry_count"));
    }

    @Test
    @DisplayName("recoverVduProcessing buffers switch op when SWITCHING and queue is SQLite")
    void recoverVduProcessingBuffersSwitchOpWhenSwitchingAndQueueIsSqlite() throws Exception {
      indexTestDocument("switching-buffer-vdu-recover");
      WorkerIngestService switchingService = createSwitchingServiceWithQueue(jobQueue, "sqlite-vdu-recover");

      RecoverVduProcessingResponse response =
          switchingService.recoverVduProcessing(
              RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none());

      assertEquals(0, response.getRecoveredCount());

      SqliteJobQueue.SwitchBufferOp op = requireSwitchOp("vdu_recover_processing");
      assertEquals("VDU_RECOVER_PROCESSING", op.op());

      @SuppressWarnings("unchecked")
      Map<String, Object> payload = JSON.readValue(op.payload(), Map.class);
      assertTrue(payload.isEmpty(), "Expected empty payload for recovery switch op");
    }

    @Test
    @DisplayName("deleteById buffers DELETE op when SWITCHING and queue is SQLite")
    void deleteByIdBuffersSwitchOpWhenSwitchingAndQueueIsSqlite() throws Exception {
      String docId = indexTestDocument("switching-buffer-delete-id");
      WorkerIngestService switchingService = createSwitchingServiceWithQueue(jobQueue, "sqlite-delete-id");

      DeleteByIdResponse response =
          switchingService.deleteById(
              DeleteByIdRequest.newBuilder().setDocId(docId).build(), CallContext.none());

      assertTrue(response.getSuccess(), "Expected buffered delete ACK while switching");

      SqliteJobQueue.SwitchBufferOp op = requireSwitchOp("path:" + docId);
      assertEquals("DELETE", op.op());
      assertEquals(docId, op.payload());
    }

    @Test
    @DisplayName("deleteByPath buffers DELETE_PREFIX op when SWITCHING and queue is SQLite")
    void deleteByPathBuffersSwitchOpWhenSwitchingAndQueueIsSqlite() throws Exception {
      indexTestDocument("switching-buffer-delete-path");
      WorkerIngestService switchingService = createSwitchingServiceWithQueue(jobQueue, "sqlite-delete-path");
      String pathPrefix = tempDir.toAbsolutePath().toString();
      String normalizedPrefix =
          io.justsearch.indexerworker.util.PathNormalizer.normalizePath(pathPrefix);

      DeleteByPathResponse response =
          switchingService.deleteByPath(
              DeleteByPathRequest.newBuilder().setPath(pathPrefix).build(), CallContext.none());

      assertEquals(0, response.getDeletedJobs());
      assertEquals("", response.getError());

      SqliteJobQueue.SwitchBufferOp op = requireSwitchOp("prefix:" + normalizedPrefix);
      assertEquals("DELETE_PREFIX", op.op());
      assertEquals(normalizedPrefix, op.payload());
    }

    @Test
    @DisplayName("pruneMissing buffers PRUNE_PREFIX op when SWITCHING and queue is SQLite")
    void pruneMissingBuffersSwitchOpWhenSwitchingAndQueueIsSqlite() throws Exception {
      indexTestDocument("switching-buffer-prune-missing");
      WorkerIngestService switchingService = createSwitchingServiceWithQueue(jobQueue, "sqlite-prune-missing");
      String pathPrefix = tempDir.toAbsolutePath().toString();
      String normalizedPrefix =
          io.justsearch.indexerworker.util.PathNormalizer.normalizePathPrefix(pathPrefix);
      String expectedPrefix = normalizedPrefix == null ? pathPrefix : normalizedPrefix;

      PruneResponse response =
          switchingService.pruneMissing(
              PruneRequest.newBuilder().setPathPrefix(pathPrefix).build(), CallContext.none());

      assertEquals(0, response.getPrunedCount());
      assertTrue(response.getError().contains("DEFERRED"));

      SqliteJobQueue.SwitchBufferOp op = requireSwitchOp("prune_prefix:" + expectedPrefix);
      assertEquals("PRUNE_PREFIX", op.op());
      assertEquals(expectedPrefix, op.payload());
    }

    @Test
    @DisplayName("markVduProcessing buffers FAILED op when retries already hit max in SWITCHING")
    void markVduProcessingBuffersFailedOpWhenSwitchingAndRetriesExceeded() throws Exception {
      String docId = indexTestDocument("switching-buffer-vdu-mark-failed");
      boolean updated =
          lifecycle.indexingCoordinator().updateDocument(docId, Map.of(SchemaFields.VDU_RETRY_COUNT, String.valueOf(3)));
      assertTrue(updated, "Expected test document to be updateable");
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();
      assertEquals("3", lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.VDU_RETRY_COUNT));

      WorkerIngestService switchingService = createSwitchingServiceWithQueue(jobQueue, "sqlite-vdu-mark-failed");

      MarkVduProcessingResponse response =
          switchingService.markVduProcessing(
              MarkVduProcessingRequest.newBuilder().setDocId(docId).setMaxRetries(3).build(),
              CallContext.none());

      assertTrue(!response.getSuccess(), "Expected max-retries error response while switching");
      assertEquals("Max retries exceeded", response.getError());

      SqliteJobQueue.SwitchBufferOp op = requireSwitchOp("vdu_mark:" + docId);
      assertEquals("VDU_MARK_FAILED", op.op());

      @SuppressWarnings("unchecked")
      Map<String, Object> payload = JSON.readValue(op.payload(), Map.class);
      assertEquals(docId, payload.get("doc_id"));
      assertEquals(3, payload.get("retry_count"));
      assertEquals("Max retries exceeded", payload.get("reason"));
    }

  }

  @Nested
  @DisplayName("recoverVduProcessing flow")
  class RecoverVduProcessingFlow {

    @Test
    @DisplayName("recoverVduProcessing resets PROCESSING docs back to PENDING")
    void recoverVduProcessingResetsProcessingDocsToPending() throws Exception {
      String doc1 = indexTestDocument("recover-doc-1");
      String doc2 = indexTestDocument("recover-doc-2");
      String untouched = indexTestDocument("recover-doc-untouched");

      setVduStatus(doc1, SchemaFields.VDU_STATUS_PROCESSING);
      setVduStatus(doc2, SchemaFields.VDU_STATUS_PROCESSING);
      setVduStatus(untouched, SchemaFields.VDU_STATUS_PENDING);

      RecoverVduProcessingResponse response =
          service.recoverVduProcessing(
              RecoverVduProcessingRequest.getDefaultInstance(), CallContext.none());

      assertEquals(2, response.getRecoveredCount());
      // Direct recovery now returns only after its covering commit and reader refresh.
      assertEquals(SchemaFields.VDU_STATUS_PENDING, lifecycle.documentFieldOps().getDocumentField(doc1, SchemaFields.VDU_STATUS));
      assertEquals(SchemaFields.VDU_STATUS_PENDING, lifecycle.documentFieldOps().getDocumentField(doc2, SchemaFields.VDU_STATUS));
      assertEquals(
          SchemaFields.VDU_STATUS_PENDING,
          lifecycle.documentFieldOps().getDocumentField(untouched, SchemaFields.VDU_STATUS));
    }

    @Test
    @DisplayName("recoverProcessingDocs continues when one reset throws")
    void recoverProcessingDocsContinuesWhenOneResetThrows() throws Exception {
      List<String> docIds = List.of("recover-a", "recover-b", "recover-c");
      List<String> attempts = new java.util.ArrayList<>();

      int recovered =
          WorkerIngestService.recoverProcessingDocsWithResetOp(
              docIds,
              docId -> {
                attempts.add(docId);
                if ("recover-b".equals(docId)) {
                  throw new IOException("injected reset failure");
                }
                return true;
              });

      assertEquals(2, recovered, "Expected both non-failing documents to recover");
      assertEquals(docIds, attempts, "Expected recovery loop to continue after injected failure");
    }
  }

  @Test
  void invalidVduResultsAreRejectedBeforeSwitchBufferAcceptance() throws Exception {
    String docId = indexTestDocument("invalid-switching-result");
    var switching = createSwitchingServiceWithQueue(jobQueue, "invalid-switching-result");
    for (var request : List.of(
        UpdateVduResultRequest.newBuilder().setDocId(docId)
            .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT).build(),
        UpdateVduResultRequest.newBuilder().setDocId(docId).setVduStatus("COMPLETED")
            .setExtractedContent("  ").build(),
        UpdateVduResultRequest.newBuilder().setDocId(docId).setOutcomeValue(999).build())) {
      var response = switching.updateVduResult(request, CallContext.none());
      org.junit.jupiter.api.Assertions.assertFalse(response.getSuccess());
      assertEquals(0, jobQueue.switchBufferDepth(), "invalid input must not create accepted replay work");
    }
  }

  // ========== Test Helpers ==========

  /**
   * Creates a test document in the index and returns its doc_id.
   */
  private String indexTestDocument(String name) throws Exception {
    String docId = tempDir.resolve(name + ".pdf").toAbsolutePath().toString();
    // Normalize path for consistency
    docId = io.justsearch.indexerworker.util.PathNormalizer.normalizePath(docId);

    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, docId);
    fields.put(SchemaFields.DOC_UID, java.util.UUID.randomUUID().toString());
    fields.put(SchemaFields.PATH, docId);
    fields.put(SchemaFields.CONTENT, "Initial Tika content");
    fields.put(SchemaFields.CONTENT_PREVIEW, "Initial Tika content");
    fields.put(SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING);
    fields.put(SchemaFields.INDEXED_AT, System.currentTimeMillis());

    lifecycle.indexingCoordinator().indexSingle(new IndexDocument(fields));
    lifecycle.commitOps().commitAndTrack();

    return docId;
  }

  private void setVduStatus(String docId, String status) throws Exception {
    boolean updated = lifecycle.indexingCoordinator().updateDocument(docId, Map.of(SchemaFields.VDU_STATUS, status));
    assertTrue(updated, "Expected test document update to succeed");
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }

  private WorkerIngestService createSwitchingServiceWithQueue(JobQueue queue, String testName)
      throws Exception {
    Path indexBasePath = tempDir.resolve("indexBase-" + testName);
    Path genDir = indexBasePath.resolve("indices").resolve("g-test");
    Files.createDirectories(genDir);
    writeSwitchingState(indexBasePath);
    return new WorkerIngestService(
        queue, stubIndexingLoop(), new StubWorkerSignalBus(), IndexingPacing.unthrottled(),
        indexBasePath, genDir, lifecycle, lifecycle, null, 0L);
  }

  private static void writeSwitchingState(Path indexBasePath) throws Exception {
    Path statePath = indexBasePath.resolve("state.json");
    String stateJson =
        """
        {
          "format_version": 2,
          "active_generation": "g-active",
          "building_generation": "g-test",
          "previous_generation": null,
          "migration_state": "SWITCHING",
          "migration_paused": false,
          "pause_reason": null,
          "paused_at_ms": null,
          "updated_at_ms": %d
        }
        """
            .formatted(System.currentTimeMillis());
    Files.writeString(statePath, stateJson);
  }

  private static void assertSwitchingUnavailable(WorkerServiceException error) {
    assertNotNull(error, "Expected UNAVAILABLE error in SWITCHING state");
    assertEquals(WorkerServiceException.Status.UNAVAILABLE, error.status());
    assertTrue(
        error.getMessage().contains("Migration is switching"),
        "Expected switching UNAVAILABLE description");
  }

  private SqliteJobQueue.SwitchBufferOp requireSwitchOp(String key) {
    return jobQueue.listSwitchBufferOps().stream()
        .filter(op -> key.equals(op.key()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing switch_buffer op for key: " + key));
  }

  // ========== Stub Classes ==========

  // This service test borrows loop status only; do not construct an unused extractor owner.
  private static IndexingLoop stubIndexingLoop() {
    IndexingLoop loop = org.mockito.Mockito.mock(IndexingLoop.class);
    org.mockito.Mockito.when(loop.getLastCommitTime()).thenAnswer(ignored -> System.currentTimeMillis());
    org.mockito.Mockito.when(loop.getCurrentState()).thenReturn("IDLE");
    return loop;
  }

  private static final class StubWorkerSignalBus implements WorkerSignalBus {
    private final long startupTime = System.currentTimeMillis();

    @Override public void open() {}
    @Override public boolean isMainGpuActive() { return false; }
    @Override public long startupTime() { return startupTime; }
    @Override public void close() {}
  }

  private static final class NoopJobQueue implements JobQueue {
    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      return paths == null ? 0 : paths.size();
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {}

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long queueDepth() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }

  // ========== P1.5 Language Parity Tests ==========

  @Nested
  @DisplayName("Language detection parity (P1.5)")
  class LanguageDetectionParity {

    @Test
    @DisplayName("SUCCESS_TEXT with Chinese content sets language to 'zh'")
    void successTextWithChineseContentSetsChineseLanguage() throws Exception {
      // Chinese text for language detection
      String chineseContent = "这是一段中文测试文本。中华人民共和国成立于一九四九年。这段文字用于验证语言检测功能。";
      String docId = indexTestDocument("chinese-doc");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(chineseContent)
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      assertTrue(response.getSuccess(), "VDU update should succeed");

      // Verify language was detected as Chinese
      String language = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.LANGUAGE);
      assertEquals("zh", language, "Language should be detected as 'zh' for Chinese content");
    }

    @Test
    @DisplayName("SUCCESS_TEXT with Cyrillic content sets language to 'ru'")
    void successTextWithCyrillicContentSetsRussianLanguage() throws Exception {
      // Russian text for language detection
      String russianContent = "Это тестовый текст на русском языке. Москва является столицей России. Язык определяется автоматически.";
      String docId = indexTestDocument("russian-doc");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(russianContent)
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      assertTrue(response.getSuccess(), "VDU update should succeed");

      // Verify language was detected as Russian
      String language = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.LANGUAGE);
      assertEquals("ru", language, "Language should be detected as 'ru' for Cyrillic content");
    }

    @Test
    @DisplayName("SUCCESS_TEXT with Japanese content sets language to 'ja'")
    void successTextWithJapaneseContentSetsJapaneseLanguage() throws Exception {
      // Japanese text (hiragana/katakana) for language detection
      String japaneseContent = "これはテストテキストです。日本語の検出をテストしています。ひらがなとカタカナが含まれています。";
      String docId = indexTestDocument("japanese-doc");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(japaneseContent)
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      assertTrue(response.getSuccess(), "VDU update should succeed");

      // Verify language was detected as Japanese
      String language = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.LANGUAGE);
      assertEquals("ja", language, "Language should be detected as 'ja' for Japanese content");
    }

    @Test
    @DisplayName("SUCCESS_TEXT with Latin content uses system default language")
    void successTextWithLatinContentUsesDefaultLanguage() throws Exception {
      // Latin text - language detection falls back to system default
      String latinContent = "This is a test document with English text. The quick brown fox jumps over the lazy dog.";
      String docId = indexTestDocument("latin-doc");

      UpdateVduResultRequest request = UpdateVduResultRequest.newBuilder()
          .setDocId(docId)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT)
          .setExtractedContent(latinContent)
          .setPageCount(1)
          .build();

      UpdateVduResultResponse response = service.updateVduResult(request, CallContext.none());
      lifecycle.commitOps().commitAndTrack();
      lifecycle.commitOps().maybeRefreshBlocking();

      assertTrue(response.getSuccess(), "VDU update should succeed");

      // Verify language was set to something (system default, not empty)
      String language = lifecycle.documentFieldOps().getDocumentField(docId, SchemaFields.LANGUAGE);
      assertTrue(language != null && !language.isEmpty(),
          "Language should be set for Latin content (system default)");
    }
  }
}
