/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.Locale;

/**
 * The one failure type a {@link KnowledgeClient} call throws, and the type the Head translates into
 * an HTTP response.
 *
 * <p><b>Why this exists (lane F stage A, A6-A9 review B1).</b> The worker services report failures
 * as {@code io.justsearch.indexerworker.services.WorkerServiceException} — the right home for the
 * vocabulary, since it is a property of the work (item A3). But that type lives behind
 * {@code app-engine}'s {@code implementation} edge on {@code worker-services}, so {@code ui} cannot
 * name it, and {@code ui} is where the vocabulary has to arrive: nine catch sites in
 * {@code IndexingController}, two in {@code KnowledgeSearchController} plus its cursor check, and
 * {@code ApiErrorHandler}'s status mapping.
 *
 * <p>Before A6 those sites caught {@code io.grpc.StatusRuntimeException} and the mapping worked
 * because the transport carried the status. After A6 it did not: every worker error fell through to
 * a 500, silently — an invalid cursor stopped being a 400, a deadline stopped being a 504, an
 * unavailable index stopped being a 503. Nothing failed; the answers just got worse. That is the
 * shape this class closes.
 *
 * <p>So the client boundary translates: {@code EngineKnowledgeClient} catches
 * {@code WorkerServiceException} and rethrows this, with the status carried across 1:1. The status
 * enum is deliberately a copy rather than a reference — copying nine constants is the cost of not
 * putting {@code worker-services} on {@code ui}'s compile classpath, which ArchUnit rule 6b forbids
 * for good reason. {@code KnowledgeClientExceptionStatusParityTest} pins the two enums against each
 * other so the copy cannot drift.
 */
public final class KnowledgeClientException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * The failure classes a port call can report. One-for-one with
   * {@code WorkerServiceException.Status}; see the class javadoc for why it is a copy.
   */
  public enum Status {
    /** The request is malformed or self-contradictory. Maps to HTTP 400. */
    INVALID_ARGUMENT,
    /** The service cannot do this in its current state. Maps to HTTP 409. */
    FAILED_PRECONDITION,
    /** A conflicting concurrent change made the answer unusable. Maps to HTTP 500 (see below). */
    ABORTED,
    /** A bound was hit. Maps to HTTP 429. */
    RESOURCE_EXHAUSTED,
    /** A dependency is temporarily not accepting work. Maps to HTTP 503. */
    UNAVAILABLE,
    /** The backing implementation does not offer this operation. Maps to HTTP 500 (see below). */
    UNIMPLEMENTED,
    /** The caller's budget elapsed. Maps to HTTP 504. */
    DEADLINE_EXCEEDED,
    /** The caller abandoned the call. Maps to HTTP 500 (see below). */
    CANCELLED,
    /** An unexpected failure. Maps to HTTP 500. */
    INTERNAL
  }

  private final Status status;

  public KnowledgeClientException(Status status, String message) {
    super(message);
    this.status = status;
  }

  public KnowledgeClientException(Status status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  /** The failure class, which the API front maps onto an HTTP status. */
  public Status status() {
    return status;
  }

  /**
   * Whether this is the "your pagination cursor is no longer valid" case.
   *
   * <p>Lives here rather than in the controller because it is a property of the failure, and
   * because the controller's previous version ({@code isInvalidCursor(StatusRuntimeException)})
   * became unreachable the moment the transport stopped throwing. Matching on the message is
   * unchanged from that version — the worker builds the description, and the alternative (a
   * dedicated status constant) is a worker-services vocabulary change this review does not own.
   */
  public boolean isInvalidCursor() {
    if (status != Status.INVALID_ARGUMENT) {
      return false;
    }
    String msg = getMessage();
    return msg != null && msg.toLowerCase(Locale.ROOT).contains("cursor");
  }
}
