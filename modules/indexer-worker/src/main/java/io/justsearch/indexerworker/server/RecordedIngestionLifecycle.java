/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.configuration.resolved.ResolvedConfig;
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

  /**
   * The accepted installer candidate for an exact recorded boot. Empty means an ordinary bulk
   * plan. A malformed or drifting installer plan must throw and fence boot rather than select A.
   */
  default Optional<RecordedCandidate> recordedCandidate(String operationKey) throws IOException {
    return Optional.empty();
  }

  record RecordedCandidate(ResolvedConfig configuration, IndexTargetSnapshot target) {
    public RecordedCandidate {
      java.util.Objects.requireNonNull(configuration, "configuration");
      java.util.Objects.requireNonNull(target, "target");
    }
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

  /** Prepared settings projection for a typed activation. Physical publication calls it in order. */
  @FunctionalInterface
  interface CommittedProjection {
    default void admitBeforePointer() {}
    void afterPointerCommitted() throws IOException;
    default void afterRuntimePublished() {}
    default void abortBeforePointer() {}
  }

  /** Settings owner locks wrap only the short physical pointer/publication cut. */
  interface PreparedCompositeProjection {
    IndexGenerationManager.State withOwnerLocks(CheckedPromotion promotion) throws IOException;
    CommittedProjection callbacks();
    void abortBeforePointer();
  }

  /** Prepare outside runtime/generation/publication locks after Green's final replay. */
  default PreparedCompositeProjection prepareRecordedGenerationProjection(
      String operationKey, JobQueue queue) throws IOException {
    return new PreparedCompositeProjection() {
      private final CommittedProjection empty = () -> {};
      @Override public IndexGenerationManager.State withOwnerLocks(CheckedPromotion promotion)
          throws IOException { return promotion.promote(); }
      @Override public CommittedProjection callbacks() { return empty; }
      @Override public void abortBeforePointer() {}
    };
  }

  @FunctionalInterface
  interface CheckedCompositePromotion {
    IndexGenerationManager.State promote(CommittedProjection projection) throws IOException;
  }

  /** The application owner serializes cancellation, settlement and the supplied exact promotion. */
  default IndexGenerationManager.State promoteRecordedGeneration(String operationKey, JobQueue queue,
      CheckedPromotion promotion) throws IOException {
    return null;
  }

  /** Legacy bulk uses an empty projection; the installer owner supplies its accepted settings. */
  default IndexGenerationManager.State promoteRecordedGenerationWithProjection(
      String operationKey, JobQueue queue, CheckedCompositePromotion promotion) throws IOException {
    return promoteRecordedGeneration(operationKey, queue,
        () -> promotion.promote(() -> {}));
  }

  /** Bounded current permission check under the queue lock; never call jobs or operation storage. */
  JobQueue.RecordedClaimDecision recordedClaimDecision(String operationKey);

  /** Terminal committed generation evidence; used only to retire its closed predecessor. */
  default boolean committedBulkTerminal(String operationKey) { return false; }

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
      String buildingGeneration, String migrationState, String writableGeneration, boolean promotedBoot,
      boolean promotedReplaySettled) {
    public BulkRuntime(IndexGenerationManager.BootDisposition disposition, String activeGeneration,
        String buildingGeneration, String migrationState, String writableGeneration, boolean promotedBoot) {
      this(disposition, activeGeneration, buildingGeneration, migrationState, writableGeneration,
          promotedBoot, true);
    }
  }

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
