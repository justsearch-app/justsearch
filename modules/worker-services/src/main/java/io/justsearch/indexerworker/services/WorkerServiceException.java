/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

/**
 * The one failure type the worker services throw.
 *
 * <p>Lane F stage A item A3: before the conversion each service reported a failure by handing a
 * {@code io.grpc.Status.X.withDescription(...)} exception to a {@code StreamObserver}. That
 * vocabulary is a property of the <b>work</b>, not of the channel — "invalid argument", "the
 * index runtime is not up yet", "the queue is full" survive a transport deletion — so it is
 * re-homed here as {@link Status} rather than deleted with the wire (design §6: "none may vanish
 * with the channel").
 *
 * <p>{@link Status} enumerates exactly the codes the three services emit today; the
 * {@code Delegating*Service} adapters map each back onto the identical {@code io.grpc.Status}
 * constant, so every caller across the still-live wire observes what it observed before.
 * Codes with no producer in these services (for example {@code NOT_FOUND},
 * {@code DEADLINE_EXCEEDED}, {@code CANCELLED}) are deliberately absent: an unemitted member is
 * residue that reads as authority.
 */
public final class WorkerServiceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * The failure vocabulary the worker services actually use, enumerated from the pre-conversion
   * call sites.
   */
  public enum Status {
    /**
     * The request is malformed or self-contradictory. 22 sites, e.g. {@code SearchService.search}
     * on a Lucene parse failure and {@code IngestService.scanRoot} on a blank {@code root_path}.
     */
    INVALID_ARGUMENT,

    /**
     * The service cannot do this in its current state. 2 sites: an upgrade RPC arriving with
     * quiescence not held, and a runtime reload asked for while no runtime is loaded.
     */
    FAILED_PRECONDITION,

    /**
     * A conflicting concurrent change made the answer unusable. 2 sites, both in
     * {@code SearchService.listAllDocumentIds}: the snapshot epoch moved under the pager.
     */
    ABORTED,

    /** A bound was hit. 1 site: {@code IngestService.submitBatch} over the queue-depth ceiling. */
    RESOURCE_EXHAUSTED,

    /**
     * A dependency is temporarily not accepting work. 2 sites, both the switching-buffer
     * unavailable replies in {@code IngestSwitchBufferOps}.
     */
    UNAVAILABLE,

    /** The backing implementation does not offer this operation. 1 site: a non-SQLite job queue
     * asked for the indexing-jobs change feed. */
    UNIMPLEMENTED,

    /** An unexpected failure. 18 sites, the catch-all for a {@code RuntimeException} escape. */
    INTERNAL
  }

  private final Status status;

  public WorkerServiceException(Status status, String message) {
    super(message);
    this.status = status;
  }

  public WorkerServiceException(Status status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  /** The failure class, which the transport adapter maps onto its own status vocabulary. */
  public Status status() {
    return status;
  }

  public static WorkerServiceException invalidArgument(String message) {
    return new WorkerServiceException(Status.INVALID_ARGUMENT, message);
  }

  public static WorkerServiceException failedPrecondition(String message) {
    return new WorkerServiceException(Status.FAILED_PRECONDITION, message);
  }

  public static WorkerServiceException aborted(String message) {
    return new WorkerServiceException(Status.ABORTED, message);
  }

  public static WorkerServiceException resourceExhausted(String message) {
    return new WorkerServiceException(Status.RESOURCE_EXHAUSTED, message);
  }

  public static WorkerServiceException unavailable(String message) {
    return new WorkerServiceException(Status.UNAVAILABLE, message);
  }

  public static WorkerServiceException unimplemented(String message) {
    return new WorkerServiceException(Status.UNIMPLEMENTED, message);
  }

  public static WorkerServiceException internal(String message) {
    return new WorkerServiceException(Status.INTERNAL, message);
  }
}
