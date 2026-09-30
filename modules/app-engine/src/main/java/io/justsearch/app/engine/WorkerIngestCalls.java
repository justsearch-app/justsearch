/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.services.worker.IngestServiceCalls;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerIngestService;

/**
 * Binds {@link IngestServiceCalls} to the converted {@link WorkerIngestService} for one call (lane F stage A item A6).
 *
 * <p>This is the whole of "the ports as direct calls": each method is the same request the wire
 * carried, handed straight to the worker service in this JVM. The {@link CallContext} is per-call —
 * it carries the trace and request ids and the deadline's cancellation signal — so an instance of
 * this class is per-call too, never cached.
 */
final class WorkerIngestCalls implements IngestServiceCalls {

  private final WorkerIngestService service;
  private final CallContext ctx;

  WorkerIngestCalls(WorkerIngestService service, CallContext ctx) {
    this.service = service;
    this.ctx = ctx;
  }

  @Override
  public io.justsearch.ipc.BatchResponse submitBatch(io.justsearch.ipc.BatchRequest request) {
    return service.submitBatch(request, ctx);
  }

  @Override
  public io.justsearch.ipc.StatusResponse indexStatus(io.justsearch.ipc.StatusRequest request) {
    return service.indexStatus(request, ctx);
  }

  @Override
  public io.justsearch.ipc.DeleteByPathResponse deleteByPath(io.justsearch.ipc.DeleteByPathRequest request) {
    return service.deleteByPath(request, ctx);
  }

  @Override
  public io.justsearch.ipc.DeleteByIdResponse deleteById(io.justsearch.ipc.DeleteByIdRequest request) {
    return service.deleteById(request, ctx);
  }

  @Override
  public io.justsearch.ipc.DeleteByCollectionResponse deleteByCollection(io.justsearch.ipc.DeleteByCollectionRequest request) {
    return service.deleteByCollection(request, ctx);
  }

  @Override
  public io.justsearch.ipc.PruneResponse pruneMissing(io.justsearch.ipc.PruneRequest request) {
    return service.pruneMissing(request, ctx);
  }

  @Override
  public io.justsearch.ipc.SyncDirectoryResponse syncDirectory(io.justsearch.ipc.SyncDirectoryRequest request) {
    return service.syncDirectory(request, ctx);
  }

  @Override
  public io.justsearch.ipc.UpdateVduResultResponse updateVduResult(io.justsearch.ipc.UpdateVduResultRequest request) {
    return service.updateVduResult(request, ctx);
  }

  @Override
  public io.justsearch.ipc.QueryPendingVduResponse queryPendingVdu(io.justsearch.ipc.QueryPendingVduRequest request) {
    return service.queryPendingVdu(request, ctx);
  }

  @Override
  public int countPendingEmbeddings() {
    return service.countPendingEmbeddings(ctx);
  }

  @Override
  public String captureServingGeneration() {
    return service.captureServingGeneration(ctx);
  }

  @Override
  public io.justsearch.ipc.MarkVduProcessingResponse markVduProcessing(io.justsearch.ipc.MarkVduProcessingRequest request) {
    return service.markVduProcessing(request, ctx);
  }

  @Override
  public io.justsearch.ipc.RecoverVduProcessingResponse recoverVduProcessing(io.justsearch.ipc.RecoverVduProcessingRequest request) {
    return service.recoverVduProcessing(request, ctx);
  }

  @Override
  public io.justsearch.ipc.MigrationStartResponse startMigration(io.justsearch.ipc.MigrationStartRequest request) {
    return service.startMigration(request, ctx);
  }

  @Override
  public io.justsearch.ipc.MigrationCutoverResponse requestCutover(io.justsearch.ipc.MigrationCutoverRequest request) {
    return service.requestCutover(request, ctx);
  }

  @Override
  public io.justsearch.ipc.MigrationPauseResponse pauseMigration(io.justsearch.ipc.MigrationPauseRequest request) {
    return service.pauseMigration(request, ctx);
  }

  @Override
  public io.justsearch.ipc.MigrationResumeResponse resumeMigration(io.justsearch.ipc.MigrationResumeRequest request) {
    return service.resumeMigration(request, ctx);
  }

  @Override
  public io.justsearch.ipc.IndexGcResponse runIndexGc(io.justsearch.ipc.IndexGcRequest request) {
    return service.runIndexGc(request, ctx);
  }

  @Override
  public io.justsearch.ipc.SettleIndexResponse settleIndex(io.justsearch.ipc.SettleIndexRequest request) {
    return service.settleIndex(request, ctx);
  }

  @Override
  public io.justsearch.ipc.MigrationRollbackResponse rollbackMigration(io.justsearch.ipc.MigrationRollbackRequest request) {
    return service.rollbackMigration(request, ctx);
  }

  @Override
  public io.justsearch.ipc.UpgradeQuiescenceResponse prepareUpgrade(io.justsearch.ipc.UpgradeQuiescenceRequest request) {
    return service.prepareUpgrade(request, ctx);
  }

  @Override
  public io.justsearch.ipc.UpgradeQuiescenceResponse upgradeStatus(io.justsearch.ipc.UpgradeQuiescenceRequest request) {
    return service.upgradeStatus(request, ctx);
  }

  @Override
  public io.justsearch.ipc.UpgradeQuiescenceResponse cancelUpgrade(io.justsearch.ipc.UpgradeQuiescenceRequest request) {
    return service.cancelUpgrade(request, ctx);
  }

  @Override
  public io.justsearch.ipc.UpdatePathsResponse updateDocumentPaths(io.justsearch.ipc.UpdatePathsRequest request) {
    return service.updateDocumentPaths(request, ctx);
  }

  @Override
  public io.justsearch.ipc.ListFailedJobsResponse listFailedJobs(io.justsearch.ipc.ListFailedJobsRequest request) {
    return service.listFailedJobs(request, ctx);
  }

  @Override
  public io.justsearch.ipc.CountJobsByPathPrefixResponse countJobsByPathPrefix(io.justsearch.ipc.CountJobsByPathPrefixRequest request) {
    return service.countJobsByPathPrefix(request, ctx);
  }

  @Override
  public io.justsearch.ipc.ListFailedJobsResponse listFailedJobsByPathPrefix(io.justsearch.ipc.ListFailedJobsByPathPrefixRequest request) {
    return service.listFailedJobsByPathPrefix(request, ctx);
  }

  @Override
  public io.justsearch.ipc.ClearFailedJobsResponse clearFailedJobs(io.justsearch.ipc.ClearFailedJobsRequest request) {
    return service.clearFailedJobs(request, ctx);
  }

  @Override
  public io.justsearch.ipc.CancelIndexingJobResponse cancelIndexingJob(io.justsearch.ipc.CancelIndexingJobRequest request) {
    return service.cancelIndexingJob(request, ctx);
  }

  @Override
  public io.justsearch.ipc.RetryIndexingJobResponse retryIndexingJob(io.justsearch.ipc.RetryIndexingJobRequest request) {
    return service.retryIndexingJob(request, ctx);
  }

  @Override
  public io.justsearch.ipc.ResetIndexResponse resetIndex(io.justsearch.ipc.ResetIndexRequest request) {
    return service.resetIndex(request, ctx);
  }

  @Override
  public io.justsearch.ipc.SessionPoliciesResponse getSessionPolicies(io.justsearch.ipc.SessionPoliciesRequest request) {
    return service.getSessionPolicies(request, ctx);
  }

  @Override
  public io.justsearch.ipc.ReloadRuntimeResponse reloadRuntime(io.justsearch.ipc.ReloadRuntimeRequest request) {
    return service.reloadRuntime(request, ctx);
  }

  @Override
  public io.justsearch.ipc.RecentIngestionEventsResponse recentIngestionEvents(io.justsearch.ipc.RecentIngestionEventsRequest request) {
    return service.recentIngestionEvents(request, ctx);
  }

  @Override
  public io.justsearch.ipc.IngestionOutcomeSummaryResponse ingestionOutcomeSummary(io.justsearch.ipc.IngestionOutcomeSummaryRequest request) {
    return service.ingestionOutcomeSummary(request, ctx);
  }

  @Override
  public io.justsearch.ipc.LookupPathByHashResponse lookupPathByHash(io.justsearch.ipc.LookupPathByHashRequest request) {
    return service.lookupPathByHash(request, ctx);
  }

  @Override
  public io.justsearch.ipc.WatchRootResponse watchRoot(io.justsearch.ipc.WatchRootRequest request) {
    return service.watchRoot(request, ctx);
  }

  @Override
  public io.justsearch.ipc.UnwatchRootResponse unwatchRoot(io.justsearch.ipc.UnwatchRootRequest request) {
    return service.unwatchRoot(request, ctx);
  }
}
