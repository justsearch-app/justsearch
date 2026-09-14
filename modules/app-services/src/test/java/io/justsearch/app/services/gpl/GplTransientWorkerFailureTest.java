/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lane F stage A item A14 — pins {@code GplJobCoordinator.isTransientWorkerUnavailable} to the
 * failure type that actually reaches it.
 *
 * <p><b>Why this test exists.</b> The classifier decides whether a failed GPL re-query aborts the
 * pass or writes the positive triple with zero features (GplJobCoordinator.java:463-472). Until
 * A14 it matched on {@code io.grpc.StatusRuntimeException} / {@code io.grpc.StatusException}, which
 * stopped being reachable at item A6 when the client boundary went in-process — so it could only
 * return false, and every transient outage quietly poisoned the training set with a zero-feature
 * triple. Nothing failed; the answers just got worse, which is exactly the class of bug a static
 * "the grpc types are dead, delete them" sweep would have deleted rather than fixed. Retargeting it
 * without a test asserting the true branch would leave the same silence in place.
 */
@DisplayName("GPL transient-worker classification")
final class GplTransientWorkerFailureTest {

  @Test
  @DisplayName("UNAVAILABLE from the knowledge client aborts the pass")
  void unavailableIsTransient() {
    assertTrue(
        GplJobCoordinator.isTransientWorkerUnavailable(
            new KnowledgeClientException(
                KnowledgeClientException.Status.UNAVAILABLE, "index half is restarting")));
  }

  @Test
  @DisplayName("DEADLINE_EXCEEDED from the knowledge client aborts the pass")
  void deadlineExceededIsTransient() {
    assertTrue(
        GplJobCoordinator.isTransientWorkerUnavailable(
            new KnowledgeClientException(
                KnowledgeClientException.Status.DEADLINE_EXCEEDED, "search budget elapsed")));
  }

  @Test
  @DisplayName("the classifier walks the cause chain, not just the top-level throwable")
  void transientCauseIsFound() {
    // The re-query catch site sees whatever the supplier chain wrapped the failure in, so a
    // top-level-only check would miss the real cause. This is the property the retired gRPC
    // version had too (it looped over getCause()); keeping it is not optional.
    Throwable wrapped =
        new IllegalStateException(
            "re-query failed",
            new IOException(
                "transport",
                new KnowledgeClientException(
                    KnowledgeClientException.Status.UNAVAILABLE, "index half is restarting")));
    assertTrue(GplJobCoordinator.isTransientWorkerUnavailable(wrapped));
  }

  @Test
  @DisplayName("a bad query is NOT transient — the triple must still be written")
  void invalidArgumentIsNotTransient() {
    // The negative case is the load-bearing one: if every failure classified as transient, GPL
    // would abort on ordinary malformed-query failures and never produce training data at all.
    assertFalse(
        GplJobCoordinator.isTransientWorkerUnavailable(
            new KnowledgeClientException(
                KnowledgeClientException.Status.INVALID_ARGUMENT, "unparseable query")));
    assertFalse(
        GplJobCoordinator.isTransientWorkerUnavailable(
            new KnowledgeClientException(
                KnowledgeClientException.Status.INTERNAL, "unexpected")));
  }

  @Test
  @DisplayName("an unrelated exception is not transient")
  void unrelatedFailureIsNotTransient() {
    assertFalse(GplJobCoordinator.isTransientWorkerUnavailable(new RuntimeException("boom")));
    assertFalse(GplJobCoordinator.isTransientWorkerUnavailable(null));
  }
}
