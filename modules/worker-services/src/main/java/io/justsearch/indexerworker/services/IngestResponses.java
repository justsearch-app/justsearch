/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import tools.jackson.databind.ObjectMapper;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.MarkVduProcessingResponse;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.UpdateVduResultResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Static response builders and switch-buffer payload helpers for {@link WorkerIngestService}.
 *
 * <p>All methods are pure static with no instance state. Extracted to reduce the size of the
 * service class while keeping response-construction logic centralized and auditable.
 */
final class IngestResponses {
  private IngestResponses() {}

  private static final ObjectMapper JSON = new ObjectMapper();

  // ==================== Switch-buffer key constants ====================

  static final String SWITCHING_DEFERRED_BUFFERED_ERROR =
      "DEFERRED: migration switching; buffered and will run after restart";

  static final String SWITCHBUF_KEY_PATH_PREFIX = "path:";
  static final String SWITCHBUF_KEY_PREFIX_PREFIX = "prefix:";
  static final String SWITCHBUF_KEY_SYNCROOT_PREFIX = "sync_root:";
  static final String SWITCHBUF_KEY_PRUNEPREFIX_PREFIX = "prune_prefix:";
  static final String SWITCHBUF_KEY_VDU_UPDATE_PREFIX = "vdu_update:";
  static final String SWITCHBUF_KEY_VDU_MARK_PREFIX = "vdu_mark:";
  static final String SWITCHBUF_KEY_VDU_RECOVER_PROCESSING = "vdu_recover_processing";

  // ==================== Switch-buffer key builders ====================

  static String switchBufferPathKey(String normalizedPath) {
    return SWITCHBUF_KEY_PATH_PREFIX + normalizedPath;
  }

  static String switchBufferPrefixKey(String normalizedPrefix) {
    return SWITCHBUF_KEY_PREFIX_PREFIX + normalizedPrefix;
  }

  static String switchBufferSyncRootKey(String normalizedRoot) {
    return SWITCHBUF_KEY_SYNCROOT_PREFIX + normalizedRoot;
  }

  static String switchBufferPrunePrefixKey(String normalizedPrefix) {
    return SWITCHBUF_KEY_PRUNEPREFIX_PREFIX + normalizedPrefix;
  }

  static String switchBufferVduUpdateKey(String normalizedDocId) {
    return SWITCHBUF_KEY_VDU_UPDATE_PREFIX + normalizedDocId;
  }

  static String switchBufferVduMarkKey(String normalizedDocId) {
    return SWITCHBUF_KEY_VDU_MARK_PREFIX + normalizedDocId;
  }

  // ==================== Normalization helpers ====================

  static String normalizeDocIdForMutation(String rawDocId) {
    return PathNormalizer.normalizePath(rawDocId);
  }

  static String normalizeDeletePrefixForMutation(String rawPathPrefix) {
    return PathNormalizer.normalizePath(rawPathPrefix);
  }

  static String resolveNormalizedPathPrefix(String rawPathPrefix) {
    String normalized = PathNormalizer.normalizePathPrefix(rawPathPrefix);
    return normalized == null ? rawPathPrefix : normalized;
  }

  // ==================== Switch-buffer payload builders ====================

  static String vduMarkFailedSwitchBufferPayload(String normalizedId, int retryCount)
      throws Exception {
    return JSON.writeValueAsString(
        Map.of("doc_id", normalizedId, "retry_count", retryCount, "reason", "Max retries exceeded"));
  }

  static String vduMarkProcessingSwitchBufferPayload(String normalizedId, int retryCount)
      throws Exception {
    return JSON.writeValueAsString(Map.of("doc_id", normalizedId, "retry_count", retryCount));
  }

  static String updateVduSwitchBufferPayload(UpdateVduResultRequest request, String normalizedId)
      throws Exception {
    Map<String, Object> payloadMap = new HashMap<>();
    payloadMap.put("doc_id", normalizedId);
    payloadMap.put("extracted_content", request.hasExtractedContent() ? request.getExtractedContent() : null);
    payloadMap.put("has_extracted_content", request.hasExtractedContent());
    payloadMap.put("vdu_status", request.getVduStatus());
    payloadMap.put("vdu_enrichment", request.getVduEnrichment());
    payloadMap.put("page_count", request.getPageCount());
    payloadMap.put("outcome", request.getOutcome().getNumber());
    return JSON.writeValueAsString(payloadMap);
  }

  // ==================== Response builders ====================

  static PruneResponse deferredPruneResponse() {
    return PruneResponse.newBuilder()
        .setPrunedCount(0)
        .setAborted(false)
        .setError(SWITCHING_DEFERRED_BUFFERED_ERROR)
        .build();
  }

  static SyncDirectoryResponse deferredSyncDirectoryResponse() {
    return SyncDirectoryResponse.newBuilder()
        .setFilesAdded(0)
        .setFilesDeleted(0)
        .setSkipped(false)
        .setError(SWITCHING_DEFERRED_BUFFERED_ERROR)
        .setDeferredToSwitchBuffer(true)
        .build();
  }

  static BatchResponse batchSuccessResponse(int acceptedCount) {
    return BatchResponse.newBuilder().setAcceptedCount(acceptedCount).build();
  }

  static BatchResponse batchErrorResponse(String errorMessage) {
    return BatchResponse.newBuilder().setAcceptedCount(0).setErrorMessage(errorMessage).build();
  }

  static UpdateVduResultResponse updateVduSuccessResponse() {
    return UpdateVduResultResponse.newBuilder().setSuccess(true).build();
  }

  static UpdateVduResultResponse updateVduErrorResponse(String error) {
    return UpdateVduResultResponse.newBuilder().setSuccess(false).setError(error).build();
  }

  static DeleteByPathResponse deleteByPathResponse(int deletedJobs, String error) {
    return DeleteByPathResponse.newBuilder()
        .setDeletedJobs(deletedJobs)
        .setError(error == null ? "" : error)
        .build();
  }

  static DeleteByPathResponse bufferedDeleteByPathResponse() {
    return deleteByPathResponse(0, "");
  }

  /** Tempdoc 811 (C-2a) — collection-keyed removal route response. */
  static io.justsearch.ipc.DeleteByCollectionResponse deleteByCollectionResponse(
      int deletedDocs, String error) {
    return io.justsearch.ipc.DeleteByCollectionResponse.newBuilder()
        .setDeletedDocs(deletedDocs)
        .setError(error == null ? "" : error)
        .build();
  }

  static DeleteByIdResponse deleteByIdResponse(boolean success, String error) {
    return DeleteByIdResponse.newBuilder().setSuccess(success).setError(error == null ? "" : error).build();
  }

  static DeleteByIdResponse bufferedDeleteByIdResponse() {
    return deleteByIdResponse(true, "");
  }

  static PruneResponse pruneErrorResponse(String error) {
    return PruneResponse.newBuilder().setPrunedCount(0).setError(error == null ? "" : error).build();
  }

  static PruneResponse pruneResultResponse(int prunedCount, boolean aborted) {
    return PruneResponse.newBuilder().setPrunedCount(prunedCount).setAborted(aborted).build();
  }

  static SyncDirectoryResponse syncDirectoryErrorResponse(String error) {
    return SyncDirectoryResponse.newBuilder().setError(error == null ? "" : error).build();
  }

  static SyncDirectoryResponse syncDirectoryResultResponse(int filesDeleted, int filesAdded) {
    return SyncDirectoryResponse.newBuilder().setFilesDeleted(filesDeleted).setFilesAdded(filesAdded).build();
  }

  static SyncDirectoryResponse syncDirectorySkippedResponse() {
    return SyncDirectoryResponse.newBuilder().setSkipped(true).build();
  }

  /**
   * Tempdoc 626 §Axis-B/C — the reconcile pruned the orphans it could see but could NOT run
   * missing-file (delete) detection for this root (indexed-path set exceeded the scan cap), so the
   * index-vs-disk delete correspondence is UNVERIFIED. Carries {@code delete_detection_unverified}
   * so the Head can surface a per-root "couldn't verify" state instead of a silent "✓ indexed".
   */
  static SyncDirectoryResponse syncDirectoryDeleteUnverifiedResponse(int filesDeleted, int filesAdded) {
    return SyncDirectoryResponse.newBuilder()
        .setFilesDeleted(filesDeleted)
        .setFilesAdded(filesAdded)
        .setSkipped(true)
        .setDeleteDetectionUnverified(true)
        .build();
  }

  static SyncDirectoryResponse syncDirectoryErrorResponse(
      int filesDeleted, int filesAdded, String error) {
    return SyncDirectoryResponse.newBuilder()
        .setFilesDeleted(filesDeleted)
        .setFilesAdded(filesAdded)
        .setError(error == null ? "" : error)
        .build();
  }

  static MarkVduProcessingResponse markVduErrorResponse(String error) {
    return MarkVduProcessingResponse.newBuilder()
        .setSuccess(false)
        .setRetryCount(-1)
        .setError(error == null ? "" : error)
        .build();
  }

  static MarkVduProcessingResponse markVduSuccessResponse(int retryCount) {
    return MarkVduProcessingResponse.newBuilder()
        .setSuccess(true)
        .setRetryCount(retryCount)
        .build();
  }

  static RecoverVduProcessingResponse recoverVduCountResponse(int recoveredCount) {
    return RecoverVduProcessingResponse.newBuilder().setRecoveredCount(recoveredCount).build();
  }

  // ==================== VDU retry decision (shared by WorkerIngestService + IngestSwitchBufferOps) ====================

  record MarkVduRetryDecision(boolean maxRetriesExceeded, int retryCount) {}

  static MarkVduRetryDecision decideMarkVduRetry(int currentCount, int maxRetries) {
    if (currentCount >= maxRetries) {
      return new MarkVduRetryDecision(true, currentCount);
    }
    return new MarkVduRetryDecision(false, currentCount + 1);
  }
}
