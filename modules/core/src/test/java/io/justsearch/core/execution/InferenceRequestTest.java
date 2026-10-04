/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.execution;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InferenceRequestTest {
  @Test
  void liveCancellationTakesPrecedenceOverExpiredDeadline() {
    var cancelled = new AtomicBoolean();
    var request = new InferenceRequest(
        InferenceRequest.Urgency.BACKGROUND, System.nanoTime() - 1, cancelled::get);
    assertThrows(InferenceRequest.DeadlineExceededException.class, request::remainingNanos);
    cancelled.set(true);
    assertThrows(CancellationException.class, request::remainingNanos);
  }

  @Test
  void interruptionRemainsVisibleToTheCaller() {
    var request = InferenceRequest.foreground();
    Thread.currentThread().interrupt();
    try {
      var failure = assertThrows(CancellationException.class, request::remainingNanos);
      assertInstanceOf(InterruptedException.class, failure.getCause());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void localAuthorityRejectsUnboundedOrNonPositiveTimeouts() {
    for (var timeout : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(366)}) {
      assertThrows(IllegalArgumentException.class,
          () -> InferenceRequest.within(InferenceRequest.Urgency.FOREGROUND, timeout, () -> false));
    }
    assertTrue(InferenceRequest.foreground().remainingNanos() > 0);
  }
}
