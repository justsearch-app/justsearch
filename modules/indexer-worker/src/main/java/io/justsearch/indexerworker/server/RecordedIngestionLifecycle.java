/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Indexer-local attachment for the Engine's recorded-ingestion owner. */
public interface RecordedIngestionLifecycle {
  /** Observe application authority once, before generation fallback or any writable runtime opens. */
  default IndexGenerationManager.BootOwnership bootOwnership(JobQueue queue) throws IOException {
    return new IndexGenerationManager.BootOwnership.Native();
  }

  /** Bounded owner readiness before recorded cutover can advance phase or commit Green. */
  default boolean recordedCutoverReady(String operationKey) { return false; }

  /** Persist sealed operation evidence outside queue locking before recorded promotion. */
  default boolean beforeRecordedPromotion(String operationKey, JobQueue queue) {
    return false;
  }

  @FunctionalInterface
  interface CheckedPromotion {
    IndexGenerationManager.State promote() throws IOException;
  }

  /** The application owner serializes cancellation, settlement and the supplied exact promotion. */
  default IndexGenerationManager.State promoteRecordedGeneration(String operationKey, JobQueue queue,
      CheckedPromotion promotion) throws IOException {
    return null;
  }

  /** Bounded current permission check under the queue lock; never call jobs or operation storage. */
  JobQueue.RecordedClaimDecision recordedClaimDecision(String operationKey);

  /**
   * Called after runtime/services/compatibility initialization and before recovery or polling.
   * Creation is failure-atomic: clean provisional activation on any failure, including Error.
   * After return, the server owns the attachment through drain. Worker readiness is index-only;
   * the Engine maps its own capability vocabulary without conflating embedding and inference.
   */
  Attachment attach(JobQueue queue, CheckedServingGeneration currentServingGeneration,
      BooleanSupplier workerOnline) throws IOException;

  /** Dynamic strict state plus the actual runtime binding; it does not grant application authority. */
  record BulkRuntime(IndexGenerationManager.BootDisposition disposition, String activeGeneration,
      String buildingGeneration, String migrationState, String writableGeneration, boolean promotedBoot) {}

  @FunctionalInterface
  interface CheckedBulkRuntime {
    Optional<BulkRuntime> current() throws IOException;
  }

  default Attachment attach(JobQueue queue, CheckedServingGeneration currentServingGeneration,
      BooleanSupplier workerOnline, CheckedBulkRuntime bulkRuntime) throws IOException {
    return attach(queue, currentServingGeneration, workerOnline);
  }

  @FunctionalInterface
  interface CheckedServingGeneration {
    /** Strict dynamic observation; empty is fenced, unreadable state throws rather than falling back. */
    Optional<String> current() throws IOException;
  }

  interface Attachment extends Closeable {
    /** Re-observe readiness after the server publishes initialized services for this attachment. */
    default void servicesPublished() {}

    /** Flush final receipts after index drain, then revoke. Failure must remain retryable. */
    @Override void close() throws IOException;
  }

  /** Explicit no-owner composition: opening or reopening jobs never authorizes recorded work. */
  static RecordedIngestionLifecycle denied() {
    return new RecordedIngestionLifecycle() {
      @Override public JobQueue.RecordedClaimDecision recordedClaimDecision(String operationKey) { return JobQueue.RecordedClaimDecision.DENY; }
      @Override public Attachment attach(JobQueue queue, CheckedServingGeneration generation,
          BooleanSupplier workerOnline) { return () -> {}; }
    };
  }
}
