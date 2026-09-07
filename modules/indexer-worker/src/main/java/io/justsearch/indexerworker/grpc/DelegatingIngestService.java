/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.BatchRequest;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.ClearFailedJobsRequest;
import io.justsearch.ipc.ClearFailedJobsResponse;
import io.justsearch.ipc.ResetIndexRequest;
import io.justsearch.ipc.ResetIndexResponse;
import io.justsearch.ipc.DeleteByIdRequest;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathRequest;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.IndexGcRequest;
import io.justsearch.ipc.IndexGcResponse;
import io.justsearch.ipc.IngestServiceGrpc;
import io.justsearch.ipc.ListFailedJobsRequest;
import io.justsearch.ipc.ListFailedJobsResponse;
import io.justsearch.ipc.MarkVduProcessingRequest;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationPauseRequest;
import io.justsearch.ipc.MigrationPauseResponse;
import io.justsearch.ipc.MigrationResumeRequest;
import io.justsearch.ipc.MigrationResumeResponse;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.QueryPendingVduRequest;
import io.justsearch.ipc.QueryPendingVduResponse;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.SettleIndexRequest;
import io.justsearch.ipc.SettleIndexResponse;
import io.justsearch.ipc.StatusRequest;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.UpdatePathsRequest;
import io.justsearch.ipc.UpdatePathsResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import io.justsearch.ipc.UpgradeQuiescenceRequest;
import io.justsearch.ipc.UpgradeQuiescenceResponse;
import java.util.Objects;

/**
 * gRPC adapter for {@link WorkerIngestService}, and the seam that enables runtime service
 * swapping.
 *
 * <p>Registered once with the gRPC server. All RPC calls are forwarded to a {@code volatile}
 * delegate that can be swapped without restarting the gRPC server (hot reload, tempdoc 305).
 *
 * <p>Lane F stage A item A3: the delegate is the converted service, which returns its response
 * instead of writing to a {@code StreamObserver}; this wrapper builds the {@link
 * io.justsearch.indexerworker.services.CallContext} from the two worker-core interceptors, does the
 * {@code onNext} / {@code onCompleted}, and maps {@code WorkerServiceException} back onto the
 * identical status code — so the wire behaviour is unchanged. Deleted at item A9.
 *
 * <p>The two server-streaming RPCs take different helpers on purpose. {@code scanRoot} finishes
 * when the service method returns (every frame has been emitted), so it gets
 * {@code streamThenComplete}. {@code subscribeIndexingJobs} registers a change-feed subscription
 * and returns with the stream still live — completing it there would kill the Library SSE fan-out
 * after its first snapshot frame — so it gets {@code streamOpen}, which leaves the call open and
 * lets cancellation end it.
 *
 * <p>Non-RPC operations (model wiring, GPU diagnostics) are routed through
 * {@code WorkerAppServices}, not through this wrapper.
 */
public final class DelegatingIngestService extends IngestServiceGrpc.IngestServiceImplBase {

  private volatile WorkerIngestService delegate;

  public DelegatingIngestService(WorkerIngestService delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  public void setDelegate(WorkerIngestService delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }

  // ==================== RPC forwards ====================

  @Override
  public void submitBatch(BatchRequest req, StreamObserver<BatchResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.submitBatch(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void indexStatus(StatusRequest req, StreamObserver<StatusResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.indexStatus(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void deleteByPath(DeleteByPathRequest req, StreamObserver<DeleteByPathResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.deleteByPath(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void deleteById(DeleteByIdRequest req, StreamObserver<DeleteByIdResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.deleteById(req, WorkerServiceCalls.callContext(obs)));
  }

  /** Tempdoc 811 (C-2a) — removal route for collection-tagged ad-hoc ingests. */
  @Override
  public void deleteByCollection(
      io.justsearch.ipc.DeleteByCollectionRequest req,
      StreamObserver<io.justsearch.ipc.DeleteByCollectionResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.deleteByCollection(req, WorkerServiceCalls.callContext(obs)));
  }

  @SuppressWarnings("deprecation")
  @Override
  public void pruneMissing(PruneRequest req, StreamObserver<PruneResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.pruneMissing(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void syncDirectory(SyncDirectoryRequest req, StreamObserver<SyncDirectoryResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.syncDirectory(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void updateVduResult(
      UpdateVduResultRequest req, StreamObserver<UpdateVduResultResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.updateVduResult(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void queryPendingVdu(
      QueryPendingVduRequest req, StreamObserver<QueryPendingVduResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.queryPendingVdu(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void markVduProcessing(
      MarkVduProcessingRequest req, StreamObserver<MarkVduProcessingResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.markVduProcessing(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void recoverVduProcessing(
      RecoverVduProcessingRequest req, StreamObserver<RecoverVduProcessingResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.recoverVduProcessing(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void startMigration(
      MigrationStartRequest req, StreamObserver<MigrationStartResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.startMigration(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void requestCutover(
      MigrationCutoverRequest req, StreamObserver<MigrationCutoverResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.requestCutover(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void pauseMigration(
      MigrationPauseRequest req, StreamObserver<MigrationPauseResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.pauseMigration(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void resumeMigration(
      MigrationResumeRequest req, StreamObserver<MigrationResumeResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.resumeMigration(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void rollbackMigration(
      MigrationRollbackRequest req, StreamObserver<MigrationRollbackResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.rollbackMigration(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void runIndexGc(IndexGcRequest req, StreamObserver<IndexGcResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.runIndexGc(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void settleIndex(SettleIndexRequest req, StreamObserver<SettleIndexResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.settleIndex(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void prepareUpgrade(
      UpgradeQuiescenceRequest req, StreamObserver<UpgradeQuiescenceResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.prepareUpgrade(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void upgradeStatus(
      UpgradeQuiescenceRequest req, StreamObserver<UpgradeQuiescenceResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.upgradeStatus(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void cancelUpgrade(
      UpgradeQuiescenceRequest req, StreamObserver<UpgradeQuiescenceResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.cancelUpgrade(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void updateDocumentPaths(UpdatePathsRequest req, StreamObserver<UpdatePathsResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.updateDocumentPaths(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void listFailedJobs(
      ListFailedJobsRequest req, StreamObserver<ListFailedJobsResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.listFailedJobs(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void countJobsByPathPrefix(
      io.justsearch.ipc.CountJobsByPathPrefixRequest req,
      StreamObserver<io.justsearch.ipc.CountJobsByPathPrefixResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.countJobsByPathPrefix(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void listFailedJobsByPathPrefix(
      io.justsearch.ipc.ListFailedJobsByPathPrefixRequest req,
      StreamObserver<ListFailedJobsResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.listFailedJobsByPathPrefix(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void clearFailedJobs(
      ClearFailedJobsRequest req, StreamObserver<ClearFailedJobsResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.clearFailedJobs(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void resetIndex(ResetIndexRequest req, StreamObserver<ResetIndexResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.resetIndex(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void getSessionPolicies(
      io.justsearch.ipc.SessionPoliciesRequest req,
      StreamObserver<io.justsearch.ipc.SessionPoliciesResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.getSessionPolicies(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void reloadRuntime(
      io.justsearch.ipc.ReloadRuntimeRequest req,
      StreamObserver<io.justsearch.ipc.ReloadRuntimeResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.reloadRuntime(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void recentIngestionEvents(
      io.justsearch.ipc.RecentIngestionEventsRequest req,
      StreamObserver<io.justsearch.ipc.RecentIngestionEventsResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.recentIngestionEvents(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void ingestionOutcomeSummary(
      io.justsearch.ipc.IngestionOutcomeSummaryRequest req,
      StreamObserver<io.justsearch.ipc.IngestionOutcomeSummaryResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.ingestionOutcomeSummary(req, WorkerServiceCalls.callContext(obs)));
  }

  // Tempdoc 419 / T5.3 (ADR-0028) — scoped reverse-lookup forward.
  @Override
  public void lookupPathByHash(
      io.justsearch.ipc.LookupPathByHashRequest req,
      StreamObserver<io.justsearch.ipc.LookupPathByHashResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.lookupPathByHash(req, WorkerServiceCalls.callContext(obs)));
  }

  // Tempdoc 418 Phase A — Worker-owned filesystem traversal forwards.

  @Override
  public void scanRoot(
      io.justsearch.ipc.ScanRootRequest req,
      StreamObserver<io.justsearch.ipc.ScanRootProgress> obs) {
    WorkerServiceCalls.streamThenComplete(
        obs, () -> delegate.scanRoot(req, obs::onNext, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void watchRoot(
      io.justsearch.ipc.WatchRootRequest req,
      StreamObserver<io.justsearch.ipc.WatchRootResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.watchRoot(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void unwatchRoot(
      io.justsearch.ipc.UnwatchRootRequest req,
      StreamObserver<io.justsearch.ipc.UnwatchRootResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.unwatchRoot(req, WorkerServiceCalls.callContext(obs)));
  }

  // Slice 445 — Job-queue TABULAR Resource forwards.

  @Override
  public void subscribeIndexingJobs(
      io.justsearch.ipc.SubscribeIndexingJobsRequest req,
      StreamObserver<io.justsearch.ipc.IndexingJobsFrame> obs) {
    // streamOpen, NOT streamThenComplete: the call returns with the change-feed subscription
    // still live and further frames arriving from the feed's threads.
    WorkerServiceCalls.streamOpen(
        obs,
        () -> delegate.subscribeIndexingJobs(req, obs::onNext, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void cancelIndexingJob(
      io.justsearch.ipc.CancelIndexingJobRequest req,
      StreamObserver<io.justsearch.ipc.CancelIndexingJobResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.cancelIndexingJob(req, WorkerServiceCalls.callContext(obs)));
  }

  @Override
  public void retryIndexingJob(
      io.justsearch.ipc.RetryIndexingJobRequest req,
      StreamObserver<io.justsearch.ipc.RetryIndexingJobResponse> obs) {
    WorkerServiceCalls.unary(
        obs, () -> delegate.retryIndexingJob(req, WorkerServiceCalls.callContext(obs)));
  }
}
