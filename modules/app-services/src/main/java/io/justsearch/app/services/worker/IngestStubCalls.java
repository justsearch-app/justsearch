/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Adapts a generated IngestService blocking stub to {@link IngestServiceCalls} (lane F stage A item A6).
 *
 * <p>Pure forwarding, one line per RPC. It exists because the generated stub is a class only a
 * {@code Channel} can produce, so it cannot be the type the ops layer is written against once a
 * second, in-process implementation of the same calls has to exist. Deleted at item A10 together
 * with {@link RemoteKnowledgeClient}.
 */
final class IngestStubCalls implements IngestServiceCalls {

  private final io.justsearch.ipc.IngestServiceGrpc.IngestServiceBlockingStub stub;

  IngestStubCalls(io.justsearch.ipc.IngestServiceGrpc.IngestServiceBlockingStub stub) {
    this.stub = stub;
  }

  @Override
  public io.justsearch.ipc.BatchResponse submitBatch(io.justsearch.ipc.BatchRequest request) {
    return stub.submitBatch(request);
  }

  @Override
  public io.justsearch.ipc.StatusResponse indexStatus(io.justsearch.ipc.StatusRequest request) {
    return stub.indexStatus(request);
  }

  @Override
  public io.justsearch.ipc.DeleteByPathResponse deleteByPath(io.justsearch.ipc.DeleteByPathRequest request) {
    return stub.deleteByPath(request);
  }

  @Override
  public io.justsearch.ipc.DeleteByIdResponse deleteById(io.justsearch.ipc.DeleteByIdRequest request) {
    return stub.deleteById(request);
  }

  @Override
  public io.justsearch.ipc.DeleteByCollectionResponse deleteByCollection(io.justsearch.ipc.DeleteByCollectionRequest request) {
    return stub.deleteByCollection(request);
  }

  @Override
  public io.justsearch.ipc.PruneResponse pruneMissing(io.justsearch.ipc.PruneRequest request) {
    return stub.pruneMissing(request);
  }

  @Override
  public io.justsearch.ipc.SyncDirectoryResponse syncDirectory(io.justsearch.ipc.SyncDirectoryRequest request) {
    return stub.syncDirectory(request);
  }

  @Override
  public io.justsearch.ipc.UpdateVduResultResponse updateVduResult(io.justsearch.ipc.UpdateVduResultRequest request) {
    return stub.updateVduResult(request);
  }

  @Override
  public io.justsearch.ipc.QueryPendingVduResponse queryPendingVdu(io.justsearch.ipc.QueryPendingVduRequest request) {
    return stub.queryPendingVdu(request);
  }

  @Override
  public io.justsearch.ipc.MarkVduProcessingResponse markVduProcessing(io.justsearch.ipc.MarkVduProcessingRequest request) {
    return stub.markVduProcessing(request);
  }

  @Override
  public io.justsearch.ipc.RecoverVduProcessingResponse recoverVduProcessing(io.justsearch.ipc.RecoverVduProcessingRequest request) {
    return stub.recoverVduProcessing(request);
  }

  @Override
  public io.justsearch.ipc.MigrationStartResponse startMigration(io.justsearch.ipc.MigrationStartRequest request) {
    return stub.startMigration(request);
  }

  @Override
  public io.justsearch.ipc.MigrationCutoverResponse requestCutover(io.justsearch.ipc.MigrationCutoverRequest request) {
    return stub.requestCutover(request);
  }

  @Override
  public io.justsearch.ipc.MigrationPauseResponse pauseMigration(io.justsearch.ipc.MigrationPauseRequest request) {
    return stub.pauseMigration(request);
  }

  @Override
  public io.justsearch.ipc.MigrationResumeResponse resumeMigration(io.justsearch.ipc.MigrationResumeRequest request) {
    return stub.resumeMigration(request);
  }

  @Override
  public io.justsearch.ipc.IndexGcResponse runIndexGc(io.justsearch.ipc.IndexGcRequest request) {
    return stub.runIndexGc(request);
  }

  @Override
  public io.justsearch.ipc.SettleIndexResponse settleIndex(io.justsearch.ipc.SettleIndexRequest request) {
    return stub.settleIndex(request);
  }

  @Override
  public io.justsearch.ipc.MigrationRollbackResponse rollbackMigration(io.justsearch.ipc.MigrationRollbackRequest request) {
    return stub.rollbackMigration(request);
  }

  @Override
  public io.justsearch.ipc.UpgradeQuiescenceResponse prepareUpgrade(io.justsearch.ipc.UpgradeQuiescenceRequest request) {
    return stub.prepareUpgrade(request);
  }

  @Override
  public io.justsearch.ipc.UpgradeQuiescenceResponse upgradeStatus(io.justsearch.ipc.UpgradeQuiescenceRequest request) {
    return stub.upgradeStatus(request);
  }

  @Override
  public io.justsearch.ipc.UpgradeQuiescenceResponse cancelUpgrade(io.justsearch.ipc.UpgradeQuiescenceRequest request) {
    return stub.cancelUpgrade(request);
  }

  @Override
  public io.justsearch.ipc.UpdatePathsResponse updateDocumentPaths(io.justsearch.ipc.UpdatePathsRequest request) {
    return stub.updateDocumentPaths(request);
  }

  @Override
  public io.justsearch.ipc.ListFailedJobsResponse listFailedJobs(io.justsearch.ipc.ListFailedJobsRequest request) {
    return stub.listFailedJobs(request);
  }

  @Override
  public io.justsearch.ipc.CountJobsByPathPrefixResponse countJobsByPathPrefix(io.justsearch.ipc.CountJobsByPathPrefixRequest request) {
    return stub.countJobsByPathPrefix(request);
  }

  @Override
  public io.justsearch.ipc.ListFailedJobsResponse listFailedJobsByPathPrefix(io.justsearch.ipc.ListFailedJobsByPathPrefixRequest request) {
    return stub.listFailedJobsByPathPrefix(request);
  }

  @Override
  public io.justsearch.ipc.ClearFailedJobsResponse clearFailedJobs(io.justsearch.ipc.ClearFailedJobsRequest request) {
    return stub.clearFailedJobs(request);
  }

  @Override
  public io.justsearch.ipc.CancelIndexingJobResponse cancelIndexingJob(io.justsearch.ipc.CancelIndexingJobRequest request) {
    return stub.cancelIndexingJob(request);
  }

  @Override
  public io.justsearch.ipc.RetryIndexingJobResponse retryIndexingJob(io.justsearch.ipc.RetryIndexingJobRequest request) {
    return stub.retryIndexingJob(request);
  }

  @Override
  public io.justsearch.ipc.ResetIndexResponse resetIndex(io.justsearch.ipc.ResetIndexRequest request) {
    return stub.resetIndex(request);
  }

  @Override
  public io.justsearch.ipc.SessionPoliciesResponse getSessionPolicies(io.justsearch.ipc.SessionPoliciesRequest request) {
    return stub.getSessionPolicies(request);
  }

  @Override
  public io.justsearch.ipc.ReloadRuntimeResponse reloadRuntime(io.justsearch.ipc.ReloadRuntimeRequest request) {
    return stub.reloadRuntime(request);
  }

  @Override
  public io.justsearch.ipc.RecentIngestionEventsResponse recentIngestionEvents(io.justsearch.ipc.RecentIngestionEventsRequest request) {
    return stub.recentIngestionEvents(request);
  }

  @Override
  public io.justsearch.ipc.IngestionOutcomeSummaryResponse ingestionOutcomeSummary(io.justsearch.ipc.IngestionOutcomeSummaryRequest request) {
    return stub.ingestionOutcomeSummary(request);
  }

  @Override
  public io.justsearch.ipc.LookupPathByHashResponse lookupPathByHash(io.justsearch.ipc.LookupPathByHashRequest request) {
    return stub.lookupPathByHash(request);
  }

  @Override
  public io.justsearch.ipc.WatchRootResponse watchRoot(io.justsearch.ipc.WatchRootRequest request) {
    return stub.watchRoot(request);
  }

  @Override
  public io.justsearch.ipc.UnwatchRootResponse unwatchRoot(io.justsearch.ipc.UnwatchRootRequest request) {
    return stub.unwatchRoot(request);
  }
}
