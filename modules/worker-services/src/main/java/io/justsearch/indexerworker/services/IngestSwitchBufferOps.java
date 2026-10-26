/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.PathPrefixScope;

import static io.justsearch.indexerworker.services.IngestResponses.*;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import io.justsearch.ipc.DeleteByIdResponse;
import io.justsearch.ipc.DeleteByPathResponse;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.SyncDirectoryResponse;
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

  static final String SWITCHBUF_OP_DELETE = "DELETE";
  static final String SWITCHBUF_OP_DELETE_PREFIX = "DELETE_PREFIX";
  static final String SWITCHBUF_OP_DELETE_COLLECTION = "DELETE_COLLECTION";
  static final String SWITCHBUF_OP_PRUNE_PREFIX = "PRUNE_PREFIX";

  private final JobQueue jobQueue;
  private final IndexGenerationManager indexGenerationManager;
  IngestSwitchBufferOps(
      JobQueue jobQueue, IndexGenerationManager indexGenerationManager) {
    this.jobQueue = jobQueue;
    this.indexGenerationManager = indexGenerationManager;
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
    } catch (Exception unavailable) {
      log.warn("Cannot establish migration state for a mutation", unavailable);
      throw switchingUnavailable();
    }
  }

  /** The candidate that must retain accepted mutations while A still serves. */
  String migratingGeneration() {
    if (indexGenerationManager == null) return null;
    try {
      IndexGenerationManager.State state = indexGenerationManager.readStateBestEffort();
      if (state == null || (!"MIGRATING".equalsIgnoreCase(state.migration_state())
          && !"AWAITING_ACCEPTANCE".equalsIgnoreCase(state.migration_state()))) return null;
      if (state.building_generation() == null || state.building_generation().isBlank()) {
        throw switchingUnavailable();
      }
      return state.building_generation();
    } catch (WorkerServiceException unavailable) {
      throw unavailable;
    } catch (Exception unavailable) {
      log.warn("Cannot establish candidate generation for a mutation", unavailable);
      throw switchingUnavailable();
    }
  }

  /** File scans may keep admitting before the final fence throughout candidate cutover. */
  String buildingGenerationForFileAdmission() {
    if (indexGenerationManager == null) return null;
    try {
      IndexGenerationManager.State state = indexGenerationManager.readStateBestEffort();
      if (state == null) return null;
      String phase = state.migration_state();
      if ("IDLE".equalsIgnoreCase(phase) && jobQueue instanceof SwitchBufferCapableQueue recovery) {
        return recovery.release030RetirementGeneration();
      }
      if (!"MIGRATING".equalsIgnoreCase(phase)
          && !"SWITCHING".equalsIgnoreCase(phase)
          && !"AWAITING_ACCEPTANCE".equalsIgnoreCase(phase)) return null;
      if (state.building_generation() == null || state.building_generation().isBlank()) {
        throw switchingUnavailable();
      }
      return state.building_generation();
    } catch (WorkerServiceException unavailable) {
      throw unavailable;
    } catch (Exception unavailable) {
      log.warn("Cannot establish candidate generation for file admission", unavailable);
      throw switchingUnavailable();
    }
  }

  /** Durable admission precedes any A or B Lucene effect. */
  void journalDeleteForGeneration(String generation, String normalizedId) {
    if (!(jobQueue instanceof SwitchBufferCapableQueue sbq)
        || !sbq.putSwitchBufferForGeneration(generation, switchBufferPathKey(normalizedId),
            SWITCHBUF_OP_DELETE, new SwitchBufferDelete(
                normalizedId, SwitchBufferDelete.Origin.EXPLICIT, null).encode())) {
      throw switchBufferUnavailable();
    }
  }

  /** A path-prefix deletion uses the same ordered candidate journal as exact deletes. */
  void journalDeletePrefixForGeneration(String generation,
      PathPrefixScope scope) {
    if (!(jobQueue instanceof SwitchBufferCapableQueue sbq)
        || !sbq.putSwitchBufferForGeneration(generation,
            switchBufferPrefixKey(scope.prefix()), SWITCHBUF_OP_DELETE_PREFIX, scope.encode())) {
      throw switchBufferUnavailable();
    }
  }

  void journalDeleteCollectionForGeneration(String generation, String collection) {
    if (!(jobQueue instanceof SwitchBufferCapableQueue sbq)
        || !sbq.putSwitchBufferForGeneration(generation,
            "collection:" + collection, SWITCHBUF_OP_DELETE_COLLECTION, collection)) {
      throw switchBufferUnavailable();
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
    if (sbq.putSwitchBufferForGeneration(
        buildingGenerationForFileAdmission(), key, operation, payload)) {
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

  SwitchBufferCapableQueue.AcceptedReceipt journalSyncRootForGeneration(String generation, String rootPath, boolean force,
      JobQueue.EnqueueProvenance provenance) {
    String root = resolveNormalizedPathPrefix(rootPath);
    if (!(jobQueue instanceof SwitchBufferCapableQueue queue)
        || !queue.putSyncRootForGeneration(generation, switchBufferSyncRootKey(root),
            new io.justsearch.indexerworker.queue.SwitchBufferSyncRoot(root, force, provenance))) {
      throw switchBufferUnavailable();
    }
    return ((SwitchBufferCapableQueue) jobQueue).retirementRootReceipt(generation,switchBufferSyncRootKey(root));
  }

  SyncDirectoryResponse bufferSyncDirectoryDuringSwitching(
      SwitchBufferCapableQueue sbq, String rootPath, boolean force,
      JobQueue.EnqueueProvenance provenance) throws Exception {
    String resolvedRoot = resolveNormalizedPathPrefix(rootPath);
    if (!sbq.putSyncRootForGeneration(buildingGenerationForFileAdmission(),
        switchBufferSyncRootKey(resolvedRoot),
        new io.justsearch.indexerworker.queue.SwitchBufferSyncRoot(resolvedRoot, force, provenance))) {
      throw switchBufferUnavailable();
    }
    return deferredSyncDirectoryResponse();
  }

  DeleteByPathResponse bufferDeleteByPathDuringSwitching(
      SwitchBufferCapableQueue sbq, PathPrefixScope scope) {
    String normalizedPrefix = scope.prefix();
    putSwitchBufferOrThrow(
        sbq,
        switchBufferPrefixKey(normalizedPrefix),
        SWITCHBUF_OP_DELETE_PREFIX,
        scope.encode(),
        "deleteByPath");
    log.info("Buffered deleteByPathPrefix during SWITCHING: {}", normalizedPrefix);
    return bufferedDeleteByPathResponse();
  }

  DeleteByIdResponse bufferDeleteByIdDuringSwitching(
      SwitchBufferCapableQueue sbq, String normalizedId) {
    putSwitchBufferOrThrow(
        sbq, switchBufferPathKey(normalizedId), SWITCHBUF_OP_DELETE,
        new SwitchBufferDelete(normalizedId, SwitchBufferDelete.Origin.EXPLICIT, null).encode(),
        "deleteById");
    log.info("Buffered deleteById during SWITCHING: {}", normalizedId);
    return bufferedDeleteByIdResponse();
  }

  void journalWatcherDelete(String normalizedPath, java.nio.file.Path root,
      String rootIdentity, String collection) {
    if (!(jobQueue instanceof SwitchBufferCapableQueue sbq)) throw switchBufferUnavailable();
    String generation = buildingGenerationForFileAdmission();
    String payload = new SwitchBufferDelete(normalizedPath, SwitchBufferDelete.Origin.WATCHER,
        root.toAbsolutePath().normalize().toString(), rootIdentity, collection).encode();
    boolean accepted = generation == null
        ? sbq.putSwitchBuffer(switchBufferPathKey(normalizedPath), SWITCHBUF_OP_DELETE, payload)
        : sbq.putSwitchBufferForGeneration(generation, switchBufferPathKey(normalizedPath),
            SWITCHBUF_OP_DELETE, payload);
    if (!accepted) throw switchBufferUnavailable();
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
