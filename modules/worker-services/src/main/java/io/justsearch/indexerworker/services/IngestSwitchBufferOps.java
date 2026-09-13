/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SWITCHING buffer coordination for {@link WorkerIngestService}.
 *
 * <p>During migration cutover (SWITCHING state), mutation requests are durably buffered via the
 * SQLite switch-buffer instead of being applied directly to the index. This class encapsulates
 * all buffer-writing logic, the SWITCHING state guard, and related constants. Extracted to reduce
 * the size of the service class.
 */
final class IngestSwitchBufferOps {
  private static final Logger log = LoggerFactory.getLogger(IngestSwitchBufferOps.class);

  // ==================== Switch-buffer operation constants ====================

  static final String SWITCHBUF_OP_UPSERT = "UPSERT";
  static final String SWITCHBUF_OP_DELETE = "DELETE";
  static final String SWITCHBUF_OP_DELETE_PREFIX = "DELETE_PREFIX";
  static final String SWITCHBUF_OP_PRUNE_PREFIX = "PRUNE_PREFIX";

  private final JobQueue jobQueue;
  private final IndexGenerationManager indexGenerationManager;
  private final OperationalMetrics metrics;

  IngestSwitchBufferOps(
      JobQueue jobQueue, IndexGenerationManager indexGenerationManager, OperationalMetrics metrics) {
    this.jobQueue = jobQueue;
    this.indexGenerationManager = indexGenerationManager;
    this.metrics = metrics;
  }

  // ==================== SWITCHING state guard ====================

  boolean isSwitching() {
    if (indexGenerationManager == null) {
      return false;
    }
    try {
      IndexGenerationManager.State s = indexGenerationManager.readStateBestEffort();
      if (s == null || s.migration_state() == null) {
        return false;
      }
      return "SWITCHING".equalsIgnoreCase(s.migration_state());
    } catch (Exception ignored) {
      return false;
    }
  }

  // ==================== Buffer infrastructure ====================

  /** A buffer write that produces the response the caller answers with, or throws. */
  @FunctionalInterface
  interface SwitchingBufferOp<T> {
    T run(SwitchBufferCapableQueue sbq) throws Exception;
  }

  /**
   * Runs {@code bufferOp} against a switch-buffer-capable queue and returns its response.
   *
   * <p>The {@link WorkerServiceException} rethrow must precede the generic catch: a buffer WRITE
   * failure has already reported its own "Switch buffer write failed during migration" reason, and
   * letting the catch-all swallow it would re-label that as the different, less specific
   * "Migration is switching" reply — exactly the pre-conversion behaviour's inverse (before the
   * conversion the failing write replied and returned false, and this method then returned without
   * sending a second error).
   */
  <T> T bufferDuringSwitchingOrThrow(String context, SwitchingBufferOp<T> bufferOp) {
    if (jobQueue instanceof SwitchBufferCapableQueue sbq) {
      try {
        return bufferOp.run(sbq);
      } catch (WorkerServiceException e) {
        throw e;
      } catch (Exception e) {
        log.warn("Failed to buffer {} during SWITCHING", context, e);
      }
    }
    throw switchingUnavailable();
  }

  void putSwitchBufferOrThrow(
      SwitchBufferCapableQueue sbq,
      String key,
      String operation,
      String payload,
      String context) {
    if (sbq.putSwitchBuffer(key, operation, payload)) {
      return;
    }
    log.error("Switch buffer write failed for {} during SWITCHING", context);
    throw switchBufferUnavailable();
  }

  static WorkerServiceException switchBufferUnavailable() {
    return WorkerServiceException.unavailable(
        "Switch buffer write failed during migration; retry shortly");
  }

  static WorkerServiceException switchingUnavailable() {
    return WorkerServiceException.unavailable("Migration is switching; retry shortly");
  }

  // ==================== Per-endpoint buffer methods ====================

  BatchResponse bufferSubmitBatchDuringSwitching(
      SwitchBufferCapableQueue sbq, List<Path> validPaths, int totalFiles, int rejected,
      String collection, JobQueue.EnqueueProvenance provenance) {
    int accepted = 0;
    for (Path p : validPaths) {
      String normalized = PathNormalizer.normalizeKey(p);
      putSwitchBufferOrThrow(
          sbq, switchBufferPathKey(normalized), SWITCHBUF_OP_UPSERT,
          new io.justsearch.indexerworker.queue.SwitchBufferUpsert(normalized, collection, provenance)
              .encode(), "submitBatch");
      accepted++;
    }
    metrics.recordBatchSubmitted(accepted);
    metrics.setQueueDepth(jobQueue.queueDepth());
    log.info(
        "Buffered {} of {} files for indexing (rejected {}) [SWITCHING]",
        accepted,
        totalFiles,
        rejected);
    return batchSuccessResponse(accepted);
  }

  SyncDirectoryResponse bufferSyncDirectoryDuringSwitching(
      SwitchBufferCapableQueue sbq, String rootPath, boolean force,
      JobQueue.EnqueueProvenance provenance) throws Exception {
    String resolvedRoot = resolveNormalizedPathPrefix(rootPath);
    if (!sbq.putSyncRoot(switchBufferSyncRootKey(resolvedRoot),
        new io.justsearch.indexerworker.queue.SwitchBufferSyncRoot(resolvedRoot, force, provenance))) {
      throw switchBufferUnavailable();
    }
    return deferredSyncDirectoryResponse();
  }

  DeleteByPathResponse bufferDeleteByPathDuringSwitching(
      SwitchBufferCapableQueue sbq, String pathPrefix) {
    String normalizedPrefix = normalizeDeletePrefixForMutation(pathPrefix);
    putSwitchBufferOrThrow(
        sbq,
        switchBufferPrefixKey(normalizedPrefix),
        SWITCHBUF_OP_DELETE_PREFIX,
        normalizedPrefix,
        "deleteByPath");
    log.info("Buffered deleteByPathPrefix during SWITCHING: {}", normalizedPrefix);
    return bufferedDeleteByPathResponse();
  }

  DeleteByIdResponse bufferDeleteByIdDuringSwitching(
      SwitchBufferCapableQueue sbq, String normalizedId) {
    putSwitchBufferOrThrow(
        sbq, switchBufferPathKey(normalizedId), SWITCHBUF_OP_DELETE, normalizedId, "deleteById");
    log.info("Buffered deleteById during SWITCHING: {}", normalizedId);
    return bufferedDeleteByIdResponse();
  }

  PruneResponse bufferPruneMissingDuringSwitching(
      SwitchBufferCapableQueue sbq, String pathPrefix) {
    String prefix = resolveNormalizedPathPrefix(pathPrefix);
    putSwitchBufferOrThrow(
        sbq,
        switchBufferPrunePrefixKey(prefix),
        SWITCHBUF_OP_PRUNE_PREFIX,
        prefix,
        "pruneMissing");
    return deferredPruneResponse();
  }
}
