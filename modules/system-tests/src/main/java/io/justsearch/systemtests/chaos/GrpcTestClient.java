/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.chaos;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.FetchDocumentSliceRequest;
import io.justsearch.ipc.FetchDocumentSliceResponse;
import io.justsearch.ipc.FetchDocumentsRequest;
import io.justsearch.ipc.FetchDocumentsResponse;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.ipc.HealthServiceGrpc;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationPauseRequest;
import io.justsearch.ipc.MigrationPauseResponse;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationResumeRequest;
import io.justsearch.ipc.MigrationResumeResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.QueryPendingVduResponse;
import io.justsearch.ipc.RecentIngestionEventsRequest;
import io.justsearch.ipc.RecentIngestionEventsResponse;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.RetrieveContextRequest;
import io.justsearch.ipc.RetrieveContextResponse;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.PipelineConfigs;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchServiceGrpc;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import io.justsearch.ipc.VduUpdateOutcome;
import java.io.Closeable;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight gRPC client for chaos testing.
 *
 * <p>Unlike the production {@code KnowledgeClient}, this client:
 * <ul>
 *   <li>Does NOT retry on failures (we want to observe failures)</li>
 *   <li>Has configurable timeouts for testing latency</li>
 *   <li>Exposes raw gRPC status for assertion</li>
 * </ul>
 */
public final class GrpcTestClient implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(GrpcTestClient.class);
  private static final String HOST = "127.0.0.1";

  private final ManagedChannel channel;
  private final HealthServiceGrpc.HealthServiceBlockingStub healthStub;
  private final SearchServiceGrpc.SearchServiceBlockingStub searchStub;
  private final IngestServiceGrpc.IngestServiceBlockingStub ingestStub;
  private final int port;

  /**
   * Creates a new test client connected to the specified port.
   *
   * @param port The gRPC port to connect to
   */
  public GrpcTestClient(int port) {
    this.port = port;
    this.channel = ManagedChannelBuilder
        .forAddress(HOST, port)
        .usePlaintext()
        .build();
    this.healthStub = HealthServiceGrpc.newBlockingStub(channel);
    this.searchStub = SearchServiceGrpc.newBlockingStub(channel);
    this.ingestStub = IngestServiceGrpc.newBlockingStub(channel);
    log.info("GrpcTestClient connected to {}:{}", HOST, port);
  }

  /**
   * Performs a health check with the given timeout.
   *
   * @param timeoutMs Timeout in milliseconds
   * @return The health check response
   * @throws StatusRuntimeException if the call fails
   */
  public HealthCheckResponse healthCheck(long timeoutMs) {
    return healthStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .check(HealthCheckRequest.getDefaultInstance());
  }

  /**
   * Performs a health check with default timeout (5 seconds).
   */
  public HealthCheckResponse healthCheck() {
    return healthCheck(5000);
  }

  /**
   * Checks if the worker is healthy (returns true/false, no exception).
   */
  public boolean isHealthy() {
    try {
      HealthCheckResponse response = healthCheck();
      return response.getServing();
    } catch (StatusRuntimeException e) {
      log.debug("Health check failed: {}", e.getStatus());
      return false;
    }
  }

  /**
   * Gets the worker PID from health check.
   *
   * @return The worker PID
   * @throws StatusRuntimeException if the call fails
   */
  public long getWorkerPid() {
    return healthCheck().getPid();
  }

  /**
   * Gets the worker state from health check.
   *
   * @return The worker state (RUNNING, PAUSED, IDLE)
   * @throws StatusRuntimeException if the call fails
   */
  public String getWorkerState() {
    return healthCheck().getWorkerState();
  }

  /**
   * Gets detailed status from the IngestService.
   *
   * <p>Returns a full StatusResponse with:
   * <ul>
   *   <li>queue_depth - Number of pending/processing jobs</li>
   *   <li>doc_count - Number of completed documents</li>
   *   <li>is_healthy - Whether the worker is healthy</li>
   *   <li>state - Worker state (IDLE, INDEXING, ERROR)</li>
   *   <li>last_commit_timestamp - Last Lucene commit time</li>
   *   <li>signal_bus_activity_ts - retired (tempdoc 885 item 3); always 0</li>
   *   <li>signal_bus_heartbeat_ts - Last main process heartbeat</li>
   *   <li>uptime_ms - Worker uptime in milliseconds</li>
   * </ul>
   *
   * @return The full StatusResponse for state-based assertions
   * @throws StatusRuntimeException if the call fails
   */
  public StatusResponse getDetailedStatus() {
    return getDetailedStatus(5000);
  }

  /**
   * Gets detailed status from the IngestService with custom timeout.
   *
   * @param timeoutMs Timeout in milliseconds
   * @return The full StatusResponse for state-based assertions
   * @throws StatusRuntimeException if the call fails
   */
  public StatusResponse getDetailedStatus(long timeoutMs) {
    return ingestStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .indexStatus(StatusRequest.getDefaultInstance());
  }

  /**
   * Performs a search request with the specified mode and timeout.
   *
   * @param query The search query
   * @param limit Maximum results
   * @param pipeline Pipeline configuration
   * @param timeoutMs Timeout in milliseconds
   * @return The search response
   * @throws StatusRuntimeException if the call fails
   */
  public SearchResponse search(String query, int limit, PipelineConfig pipeline, long timeoutMs) {
    SearchRequest request = SearchRequest.newBuilder()
        .setQuery(query)
        .setLimit(limit)
        .setPipeline(pipeline)
        .build();
    return searchStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .search(request);
  }

  /**
   * Performs a text search with default settings.
   */
  public SearchResponse searchText(String query, int limit) {
    return search(query, limit, PipelineConfigs.TEXT, 5000);
  }

  // =========================================================================
  // Document Fetching Operations (gRPC)
  // =========================================================================

  /**
   * Fetches document content by IDs via gRPC.
   *
   * @param docIds List of document IDs to fetch
   * @param timeoutMs Timeout in milliseconds
   * @return Response containing document content and metadata
   * @throws StatusRuntimeException if the call fails
   */
  public FetchDocumentsResponse fetchDocuments(List<String> docIds, long timeoutMs) {
    FetchDocumentsRequest request = FetchDocumentsRequest.newBuilder()
        .addAllDocIds(docIds)
        .build();
    return searchStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .fetchDocuments(request);
  }

  /**
   * Fetches document content by IDs with default timeout.
   *
   * @param docIds List of document IDs to fetch
   * @return Response containing document content and metadata
   */
  public FetchDocumentsResponse fetchDocuments(List<String> docIds) {
    return fetchDocuments(docIds, 10_000);
  }

  /**
   * Fetches a single document by ID.
   *
   * @param docId Document ID to fetch
   * @return DocumentContent or null if not found
   */
  public DocumentContent fetchDocument(String docId) {
    FetchDocumentsResponse response = fetchDocuments(List.of(docId));
    return response.getDocumentsCount() > 0 ? response.getDocuments(0) : null;
  }

  /**
   * Fetches a slice of a document's extracted text (paged) via gRPC.
   *
   * @param docId Document ID to fetch
   * @param offsetChars 0-based character offset
   * @param maxChars maximum chars to return (server-side capped)
   * @param timeoutMs Timeout in milliseconds
   * @return FetchDocumentSliceResponse
   */
  public FetchDocumentSliceResponse fetchDocumentSlice(String docId, int offsetChars, int maxChars, long timeoutMs) {
    FetchDocumentSliceRequest request =
        FetchDocumentSliceRequest.newBuilder()
            .setDocId(docId == null ? "" : docId)
            .setOffsetChars(offsetChars)
            .setMaxChars(maxChars)
            .build();
    return searchStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .fetchDocumentSlice(request);
  }

  /**
   * Fetches a slice with default timeout (10 seconds).
   */
  public FetchDocumentSliceResponse fetchDocumentSlice(String docId, int offsetChars, int maxChars) {
    return fetchDocumentSlice(docId, offsetChars, maxChars, 10_000);
  }

  // =========================================================================
  // RAG Context Retrieval Operations (gRPC)
  // =========================================================================

  /**
   * Retrieves relevant context for RAG using BM25 search.
   *
   * <p>Searches chunk_content first, falls back to full document search if no chunks found.
   *
   * @param question The user's question for BM25 ranking
   * @param docIds Document IDs to search within
   * @param topK Number of chunks/docs to retrieve
   * @param timeoutMs Timeout in milliseconds
   * @return Response containing formatted context and metadata
   * @throws StatusRuntimeException if the call fails
   */
  public RetrieveContextResponse retrieveContext(String question, List<String> docIds, int topK, long timeoutMs) {
    RetrieveContextRequest request = RetrieveContextRequest.newBuilder()
        .setQuestion(question)
        .addAllDocIds(docIds)
        .setTopK(topK)
        .build();
    return searchStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .retrieveContext(request);
  }

  /**
   * Retrieves relevant context for RAG with default timeout.
   *
   * @param question The user's question for BM25 ranking
   * @param docIds Document IDs to search within
   * @param topK Number of chunks/docs to retrieve
   * @return Response containing formatted context and metadata
   */
  public RetrieveContextResponse retrieveContext(String question, List<String> docIds, int topK) {
    return retrieveContext(question, docIds, topK, 10_000);
  }

  /**
   * Retrieves relevant context for RAG with defaults (topK=5).
   *
   * @param question The user's question for BM25 ranking
   * @param docIds Document IDs to search within
   * @return Response containing formatted context
   */
  public RetrieveContextResponse retrieveContext(String question, List<String> docIds) {
    return retrieveContext(question, docIds, 5);
  }

  /**
   * Returns the connected port.
   */
  public int getPort() {
    return port;
  }

  /**
   * Returns the underlying channel (for advanced testing).
   */
  public ManagedChannel getChannel() {
    return channel;
  }

  // =========================================================================
  // Batch Ingestion Operations
  // =========================================================================

  /**
   * Submits a batch of file paths for indexing.
   *
   * @param filePaths List of absolute file paths to index
   * @param timeoutMs Timeout in milliseconds
   * @return Response containing accepted count
   * @throws StatusRuntimeException if the call fails
   */
  public BatchResponse submitBatch(List<String> filePaths, long timeoutMs) {
    BatchRequest request = BatchRequest.newBuilder()
        .addAllFilePaths(filePaths)
        .build();
    return ingestStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .submitBatch(request);
  }

  /**
   * Submits a batch of file paths for indexing with default timeout.
   *
   * @param filePaths List of absolute file paths to index
   * @return Number of files accepted for indexing
   */
  public int submitBatch(List<String> filePaths) {
    return submitBatch(filePaths, 10_000).getAcceptedCount();
  }

  /** Returns the Worker's most recent privacy-safe ingestion ledger events. */
  public RecentIngestionEventsResponse recentIngestionEvents(int limit, long timeoutMs) {
    RecentIngestionEventsRequest request =
        RecentIngestionEventsRequest.newBuilder().setLimit(limit).build();
    return ingestStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .recentIngestionEvents(request);
  }

  /**
   * Submits a single file for indexing.
   *
   * @param filePath Absolute file path to index
   * @return true if the file was accepted
   */
  public boolean submitFile(String filePath) {
    return submitBatch(List.of(filePath)) == 1;
  }

  /**
   * Waits until the specified document appears in the index.
   *
   * <p>Polls the index status until queue becomes empty and doc count increases,
   * indicating the document has been indexed.
   *
   * @param expectedDocCount Expected document count after indexing
   * @param timeoutMs Maximum time to wait
   * @param pollIntervalMs Interval between polls
   * @return true if indexing completed, false if timeout
   */
  public boolean awaitIndexing(long expectedDocCount, long timeoutMs, long pollIntervalMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        StatusResponse status = getDetailedStatus();
        if (status.getCore().getQueueDepth() == 0
            && status.getCore().getDocCount() >= expectedDocCount) {
          return true;
        }
        Thread.sleep(pollIntervalMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } catch (StatusRuntimeException e) {
        log.debug("Status check failed: {}", e.getStatus());
        try {
          Thread.sleep(pollIntervalMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return false;
  }

  /**
   * Waits until the queue is empty with default timeout.
   *
   * @param expectedDocCount Expected document count after indexing
   * @return true if indexing completed within 30 seconds
   */
  public boolean awaitIndexing(long expectedDocCount) {
    return awaitIndexing(expectedDocCount, 30_000, 200);
  }

  // =========================================================================
  // Mutations (delete/prune) - used for SWITCHING fence tests
  // =========================================================================

  public DeleteByIdResponse deleteById(String docId, long timeoutMs) {
    DeleteByIdRequest req =
        DeleteByIdRequest.newBuilder().setDocId(docId == null ? "" : docId).build();
    return ingestStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).deleteById(req);
  }

  public DeleteByPathResponse deleteByPathPrefix(String prefix, long timeoutMs) {
    DeleteByPathRequest req =
        DeleteByPathRequest.newBuilder().setPath(prefix == null ? "" : prefix).build();
    return ingestStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).deleteByPath(req);
  }

  public PruneResponse pruneMissing(String prefix, long timeoutMs) {
    PruneRequest req =
        PruneRequest.newBuilder().setPathPrefix(prefix == null ? "" : prefix).build();
    return ingestStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).pruneMissing(req);
  }

  // =========================================================================
  // Migration Controls
  // =========================================================================

  public MigrationStartResponse startMigration(String reason, boolean restartWorker, long timeoutMs) {
    MigrationStartRequest req =
        MigrationStartRequest.newBuilder()
            .setReason(reason == null ? "" : reason)
            .setRestartWorker(restartWorker)
            .build();
    return ingestStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).startMigration(req);
  }

  public MigrationCutoverResponse requestCutover(boolean forceSwitching, long timeoutMs) {
    MigrationCutoverRequest req =
        MigrationCutoverRequest.newBuilder().setForceSwitching(forceSwitching).build();
    return ingestStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).requestCutover(req);
  }

  public MigrationRollbackResponse rollbackMigration(boolean restartWorker, long timeoutMs) {
    MigrationRollbackRequest req =
        MigrationRollbackRequest.newBuilder().setRestartWorker(restartWorker).build();
    return ingestStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).rollbackMigration(req);
  }

  public MigrationPauseResponse pauseMigration(String reason, long timeoutMs) {
    MigrationPauseRequest req =
        MigrationPauseRequest.newBuilder().setReason(reason == null ? "" : reason).build();
    return ingestStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).pauseMigration(req);
  }

  public MigrationResumeResponse resumeMigration(long timeoutMs) {
    MigrationResumeRequest req = MigrationResumeRequest.getDefaultInstance();
    return ingestStub.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS).resumeMigration(req);
  }

  // =========================================================================
  // Sync Directory Operations
  // =========================================================================

  /**
   * Performs bidirectional sync: deletes orphans + adds missing files.
   *
   * <p>This is used for:
   * <ul>
   *   <li>OVERFLOW events (force=true) - events were dropped, must resync</li>
   *   <li>Periodic maintenance (force=false) - skip if user is active</li>
   *   <li>Windows DELETE workaround - catch missed deletes</li>
   * </ul>
   *
   * @param rootPath Root directory path to sync (e.g., "D:\docs")
   * @param force true to run regardless of user activity
   * @param timeoutMs Timeout in milliseconds
   * @return Response with files_added, files_deleted, skipped, error
   * @throws StatusRuntimeException if the call fails
   */
  public SyncDirectoryResponse syncDirectory(String rootPath, boolean force, long timeoutMs) {
    SyncDirectoryRequest request = SyncDirectoryRequest.newBuilder()
        .setRootPath(rootPath)
        .setForce(force)
        .build();
    return ingestStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .syncDirectory(request);
  }

  /**
   * Performs bidirectional sync with default timeout (5 minutes for large directories).
   *
   * @param rootPath Root directory path to sync
   * @param force true to run regardless of user activity
   * @return Response with files_added, files_deleted, skipped, error
   */
  public SyncDirectoryResponse syncDirectory(String rootPath, boolean force) {
    return syncDirectory(rootPath, force, 300_000);  // 5 min for large dirs
  }

  /**
   * Waits for sync to complete and queue to drain.
   *
   * <p>After syncDirectory enqueues files, this method waits for them to be indexed.
   *
   * @param expectedDocCount Expected document count after sync completes
   * @return true if sync completed within timeout
   */
  public boolean awaitSyncComplete(long expectedDocCount) {
    return awaitIndexing(expectedDocCount, 60_000, 500);
  }

  // =========================================================================
  // VDU Operations (Phase 3)
  // =========================================================================

  /**
   * Queries documents pending VDU processing.
   *
   * @param limit Maximum documents to return
   * @param timeoutMs Timeout in milliseconds
   * @return Response containing doc_ids and total_count
   * @throws StatusRuntimeException if the call fails
   */
  public QueryPendingVduResponse queryPendingVdu(int limit, long timeoutMs) {
    QueryPendingVduRequest request = QueryPendingVduRequest.newBuilder()
        .setLimit(limit)
        .build();
    return ingestStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .queryPendingVdu(request);
  }

  /**
   * Queries documents pending VDU processing with default timeout.
   *
   * @param limit Maximum documents to return
   * @return List of document IDs
   */
  public List<String> queryPendingVduDocIds(int limit) {
    return queryPendingVdu(limit, 5000).getDocIdsList();
  }

  /**
   * Gets the count of documents pending VDU processing.
   *
   * @return Total count of pending VDU documents
   */
  public int countPendingVdu() {
    return queryPendingVdu(0, 5000).getTotalCount();
  }

  /**
   * Marks a document as PROCESSING and increments retry count.
   *
   * <p>This is an atomic operation that:
   * <ul>
   *   <li>Sets vdu_status to PROCESSING</li>
   *   <li>Increments vdu_retry_count</li>
   *   <li>Returns -1 if max retries exceeded</li>
   * </ul>
   *
   * @param docId Document ID to mark
   * @param maxRetries Maximum retry attempts
   * @param timeoutMs Timeout in milliseconds
   * @return Response containing success flag and current retry count
   * @throws StatusRuntimeException if the call fails
   */
  public MarkVduProcessingResponse markVduProcessing(String docId, int maxRetries, long timeoutMs) {
    MarkVduProcessingRequest request = MarkVduProcessingRequest.newBuilder()
        .setDocId(docId)
        .setMaxRetries(maxRetries)
        .build();
    return ingestStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .markVduProcessing(request);
  }

  /**
   * Marks a document as PROCESSING with default timeout.
   *
   * @param docId Document ID to mark
   * @param maxRetries Maximum retry attempts
   * @return Current retry count, or -1 if max exceeded
   */
  public int markVduProcessing(String docId, int maxRetries) {
    MarkVduProcessingResponse response = markVduProcessing(docId, maxRetries, 5000);
    return response.getSuccess() ? response.getRetryCount() : -1;
  }

  /**
   * Updates a document with VDU processing results.
   *
   * @param docId Document ID
   * @param extractedContent Text extracted by VDU
   * @param outcome Explicit VDU outcome (preferred over legacy vdu_status)
   * @param vduEnrichment JSON enrichment data
   * @param pageCount Number of pages processed
   * @param timeoutMs Timeout in milliseconds
   * @return Response containing success flag
   * @throws StatusRuntimeException if the call fails
   */
  public UpdateVduResultResponse updateVduResult(
      String docId,
      String extractedContent,
      VduUpdateOutcome outcome,
      String vduEnrichment,
      int pageCount,
      long timeoutMs) {
    UpdateVduResultRequest.Builder builder = UpdateVduResultRequest.newBuilder()
        .setDocId(docId)
        .setOutcome(outcome)
        .setVduEnrichment(vduEnrichment)
        .setPageCount(pageCount);
    if (extractedContent != null) {
      builder.setExtractedContent(extractedContent);
    }
    UpdateVduResultRequest request = builder.build();
    return ingestStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .updateVduResult(request);
  }

  /**
   * Updates a document with VDU processing results (simplified).
   *
   * @param docId Document ID
   * @param extractedContent Text extracted by VDU
   * @param outcome Explicit VDU outcome (preferred over legacy vdu_status)
   * @param vduEnrichment JSON enrichment data
   * @param pageCount Number of pages processed
   * @return true if update succeeded
   */
  public boolean updateVduResult(
      String docId,
      String extractedContent,
      VduUpdateOutcome outcome,
      String vduEnrichment,
      int pageCount) {
    return updateVduResult(docId, extractedContent, outcome, vduEnrichment, pageCount, 5000)
        .getSuccess();
  }

  /**
   * Recovers documents stuck in PROCESSING state.
   *
   * <p>Resets vdu_status from PROCESSING back to PENDING for documents
   * that may have been abandoned due to a crash.
   *
   * @param timeoutMs Timeout in milliseconds
   * @return Response containing count of recovered documents
   * @throws StatusRuntimeException if the call fails
   */
  public RecoverVduProcessingResponse recoverVduProcessing(long timeoutMs) {
    return ingestStub
        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
        .recoverVduProcessing(RecoverVduProcessingRequest.getDefaultInstance());
  }

  /**
   * Recovers documents stuck in PROCESSING state with default timeout.
   *
   * @return Number of documents recovered
   */
  public int recoverVduProcessing() {
    return recoverVduProcessing(5000).getRecoveredCount();
  }

  @Override
  public void close() {
    if (channel != null && !channel.isShutdown()) {
      log.info("Shutting down GrpcTestClient on port {}", port);
      channel.shutdownNow();
      try {
        channel.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
