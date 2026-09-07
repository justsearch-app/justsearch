/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * The unary IngestService calls, as a plain Java interface (lane F stage A item A6).
 *
 * <p>Thirty-six unary calls: ingestion, migration, VDU, upgrade quiescence and queue inspection.
 *
 * <p><b>Why this interface exists.</b> Until A6 the executor seam
 * ({@link SearchRpcExecutor}, {@link IngestRpcExecutor}) was typed on the <em>generated gRPC
 * blocking stub</em>, a final-ish class that only a {@code Channel} can produce. That made the
 * whole ops layer ({@code SearchRpcOps}, {@code MigrationOps}, {@code VduOps}, {@code SyncOps},
 * {@code RootLifecycleOps}) reachable only over the wire, even though none of its logic is about
 * a network. This interface is the same call surface with the same method names and signatures,
 * so every existing {@code stub -> stub.foo(request)} lambda compiles verbatim; what changes is
 * that a second implementation is now possible — the in-process one in
 * {@code io.justsearch.app.engine}, which calls the converted worker service directly.
 *
 * <p>Proto DTOs at the signature are transitional (design §6): without a wire they carry its cost
 * and none of its benefit, and a named follow-up replaces them with the {@code app-api} records.

 * <p>The server-streaming RPCs (SubscribeIndexingJobs, ScanRoot) are deliberately NOT on this interface: a stream is not a
 * {@code Resp m(Req)} call. They are carried by their own seams on
 * {@code KnowledgeClientCore} (items A7 and A8).
 */
public interface IngestServiceCalls {

  /** {@code IngestService/SubmitBatch}. */
  io.justsearch.ipc.BatchResponse submitBatch(io.justsearch.ipc.BatchRequest request);

  /** {@code IngestService/IndexStatus}. */
  io.justsearch.ipc.StatusResponse indexStatus(io.justsearch.ipc.StatusRequest request);

  /** {@code IngestService/DeleteByPath}. */
  io.justsearch.ipc.DeleteByPathResponse deleteByPath(io.justsearch.ipc.DeleteByPathRequest request);

  /** {@code IngestService/DeleteById}. */
  io.justsearch.ipc.DeleteByIdResponse deleteById(io.justsearch.ipc.DeleteByIdRequest request);

  /** {@code IngestService/DeleteByCollection}. */
  io.justsearch.ipc.DeleteByCollectionResponse deleteByCollection(io.justsearch.ipc.DeleteByCollectionRequest request);

  /** {@code IngestService/PruneMissing}. */
  io.justsearch.ipc.PruneResponse pruneMissing(io.justsearch.ipc.PruneRequest request);

  /** {@code IngestService/SyncDirectory}. */
  io.justsearch.ipc.SyncDirectoryResponse syncDirectory(io.justsearch.ipc.SyncDirectoryRequest request);

  /** {@code IngestService/UpdateVduResult}. */
  io.justsearch.ipc.UpdateVduResultResponse updateVduResult(io.justsearch.ipc.UpdateVduResultRequest request);

  /** {@code IngestService/QueryPendingVdu}. */
  io.justsearch.ipc.QueryPendingVduResponse queryPendingVdu(io.justsearch.ipc.QueryPendingVduRequest request);

  /** {@code IngestService/MarkVduProcessing}. */
  io.justsearch.ipc.MarkVduProcessingResponse markVduProcessing(io.justsearch.ipc.MarkVduProcessingRequest request);

  /** {@code IngestService/RecoverVduProcessing}. */
  io.justsearch.ipc.RecoverVduProcessingResponse recoverVduProcessing(io.justsearch.ipc.RecoverVduProcessingRequest request);

  /** {@code IngestService/StartMigration}. */
  io.justsearch.ipc.MigrationStartResponse startMigration(io.justsearch.ipc.MigrationStartRequest request);

  /** {@code IngestService/RequestCutover}. */
  io.justsearch.ipc.MigrationCutoverResponse requestCutover(io.justsearch.ipc.MigrationCutoverRequest request);

  /** {@code IngestService/PauseMigration}. */
  io.justsearch.ipc.MigrationPauseResponse pauseMigration(io.justsearch.ipc.MigrationPauseRequest request);

  /** {@code IngestService/ResumeMigration}. */
  io.justsearch.ipc.MigrationResumeResponse resumeMigration(io.justsearch.ipc.MigrationResumeRequest request);

  /** {@code IngestService/RunIndexGc}. */
  io.justsearch.ipc.IndexGcResponse runIndexGc(io.justsearch.ipc.IndexGcRequest request);

  /** {@code IngestService/SettleIndex}. */
  io.justsearch.ipc.SettleIndexResponse settleIndex(io.justsearch.ipc.SettleIndexRequest request);

  /** {@code IngestService/RollbackMigration}. */
  io.justsearch.ipc.MigrationRollbackResponse rollbackMigration(io.justsearch.ipc.MigrationRollbackRequest request);

  /** {@code IngestService/PrepareUpgrade}. */
  io.justsearch.ipc.UpgradeQuiescenceResponse prepareUpgrade(io.justsearch.ipc.UpgradeQuiescenceRequest request);

  /** {@code IngestService/UpgradeStatus}. */
  io.justsearch.ipc.UpgradeQuiescenceResponse upgradeStatus(io.justsearch.ipc.UpgradeQuiescenceRequest request);

  /** {@code IngestService/CancelUpgrade}. */
  io.justsearch.ipc.UpgradeQuiescenceResponse cancelUpgrade(io.justsearch.ipc.UpgradeQuiescenceRequest request);

  /** {@code IngestService/UpdateDocumentPaths}. */
  io.justsearch.ipc.UpdatePathsResponse updateDocumentPaths(io.justsearch.ipc.UpdatePathsRequest request);

  /** {@code IngestService/ListFailedJobs}. */
  io.justsearch.ipc.ListFailedJobsResponse listFailedJobs(io.justsearch.ipc.ListFailedJobsRequest request);

  /** {@code IngestService/CountJobsByPathPrefix}. */
  io.justsearch.ipc.CountJobsByPathPrefixResponse countJobsByPathPrefix(io.justsearch.ipc.CountJobsByPathPrefixRequest request);

  /** {@code IngestService/ListFailedJobsByPathPrefix}. */
  io.justsearch.ipc.ListFailedJobsResponse listFailedJobsByPathPrefix(io.justsearch.ipc.ListFailedJobsByPathPrefixRequest request);

  /** {@code IngestService/ClearFailedJobs}. */
  io.justsearch.ipc.ClearFailedJobsResponse clearFailedJobs(io.justsearch.ipc.ClearFailedJobsRequest request);

  /** {@code IngestService/CancelIndexingJob}. */
  io.justsearch.ipc.CancelIndexingJobResponse cancelIndexingJob(io.justsearch.ipc.CancelIndexingJobRequest request);

  /** {@code IngestService/RetryIndexingJob}. */
  io.justsearch.ipc.RetryIndexingJobResponse retryIndexingJob(io.justsearch.ipc.RetryIndexingJobRequest request);

  /** {@code IngestService/ResetIndex}. */
  io.justsearch.ipc.ResetIndexResponse resetIndex(io.justsearch.ipc.ResetIndexRequest request);

  /** {@code IngestService/GetSessionPolicies}. */
  io.justsearch.ipc.SessionPoliciesResponse getSessionPolicies(io.justsearch.ipc.SessionPoliciesRequest request);

  /** {@code IngestService/ReloadRuntime}. */
  io.justsearch.ipc.ReloadRuntimeResponse reloadRuntime(io.justsearch.ipc.ReloadRuntimeRequest request);

  /** {@code IngestService/RecentIngestionEvents}. */
  io.justsearch.ipc.RecentIngestionEventsResponse recentIngestionEvents(io.justsearch.ipc.RecentIngestionEventsRequest request);

  /** {@code IngestService/IngestionOutcomeSummary}. */
  io.justsearch.ipc.IngestionOutcomeSummaryResponse ingestionOutcomeSummary(io.justsearch.ipc.IngestionOutcomeSummaryRequest request);

  /** {@code IngestService/LookupPathByHash}. */
  io.justsearch.ipc.LookupPathByHashResponse lookupPathByHash(io.justsearch.ipc.LookupPathByHashRequest request);

  /** {@code IngestService/WatchRoot}. */
  io.justsearch.ipc.WatchRootResponse watchRoot(io.justsearch.ipc.WatchRootRequest request);

  /** {@code IngestService/UnwatchRoot}. */
  io.justsearch.ipc.UnwatchRootResponse unwatchRoot(io.justsearch.ipc.UnwatchRootRequest request);
}
