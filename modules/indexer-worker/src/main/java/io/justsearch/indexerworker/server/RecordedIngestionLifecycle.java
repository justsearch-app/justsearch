/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.queue.JobQueue;
import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Indexer-local attachment for the Engine's recorded-ingestion owner. */
public interface RecordedIngestionLifecycle {
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

  @FunctionalInterface
  interface CheckedServingGeneration {
    /** Strict dynamic observation; empty is fenced, unreadable state throws rather than falling back. */
    Optional<String> current() throws IOException;
  }

  interface Attachment extends Closeable {
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
