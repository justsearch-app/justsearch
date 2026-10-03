/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.InferenceRequest;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class SessionAcquisitionRequestTest {

  @Test
  void neutralCallerMappingPreservesDeadlineUrgencyAndLiveCancellation() {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var caller = InferenceRequest.within(
        InferenceRequest.Urgency.BACKGROUND,
        Duration.ofSeconds(5), cancelled::get);
    var request = SessionAcquisitionRequest.from(caller);
    assertEquals(SessionAcquisitionRequest.Urgency.BACKGROUND, request.urgency());
    assertEquals(caller.deadlineNanos(), request.deadlineNanos());
    assertSame(caller.cancellationRequested(), request.cancellationRequested());
    assertTrue(request.remainingNanos() > 0);
    cancelled.set(true);
    assertThrows(CancellationException.class, request::remainingNanos);

    var foreground = new InferenceRequest(
        InferenceRequest.Urgency.FOREGROUND,
        System.nanoTime() - 1, () -> false);
    var deadline = assertThrows(SessionAcquireDeadlineExceededException.class,
        SessionAcquisitionRequest.from(foreground)::remainingNanos);
    assertTrue(deadline instanceof InferenceRequest.DeadlineExceeded);
    assertEquals(SessionAcquisitionRequest.Urgency.FOREGROUND,
        SessionAcquisitionRequest.from(foreground).urgency());
  }

  @Test
  void boundedFactoryPreservesUrgencyAndCreatesFutureMonotonicDeadline() {
    SessionAcquisitionRequest request =
        SessionAcquisitionRequest.within(
            SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofSeconds(1));

    assertEquals(SessionAcquisitionRequest.Urgency.BACKGROUND, request.urgency());
    assertTrue(request.remainingNanos() > 0);
  }

  @Test
  void boundedFactoryRejectsUnboundedOrNonPositiveTimeouts() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SessionAcquisitionRequest.within(
                SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SessionAcquisitionRequest.within(
                SessionAcquisitionRequest.Urgency.FOREGROUND,
                SessionAcquisitionRequest.MAX_TIMEOUT.plusNanos(1)));
  }

  @Test
  void exposesTypedDeadlineAndCancellationOutcomes() {
    SessionAcquisitionRequest expired =
        new SessionAcquisitionRequest(
            SessionAcquisitionRequest.Urgency.FOREGROUND, System.nanoTime() - 1, () -> false);
    SessionAcquisitionRequest cancelled =
        SessionAcquisitionRequest.within(
            SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(1), () -> true);

    assertThrows(SessionAcquireDeadlineExceededException.class, expired::remainingNanos);
    assertThrows(CancellationException.class, cancelled::remainingNanos);
  }
}
