/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.inference;

import io.justsearch.ort.SessionAcquisitionRequest;
import io.justsearch.core.execution.InferenceRequest;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;

/**
 * Bounded scheduling inputs for local work without admitted Engine request authority.
 *
 * <p>The generous local horizon preserves the former wait behavior while making every native
 * session acquisition monotonic, interrupt-cancellable, and finite. Engine query paths instead pass
 * the caller's urgency, absolute deadline, and cooperative cancellation authority explicitly.
 */
public final class LocalSessionAcquisition {
  private static final Duration ACQUIRE_TIMEOUT = Duration.ofMinutes(5);

  private static final ScopedValue<BooleanSupplier> CANCELLATION = ScopedValue.newInstance();

  private LocalSessionAcquisition() {}

  /** Binds only hard cancellation to synchronous background inference, never a cycle deadline. */
  public static ScopedValue.Carrier withCancellation(BooleanSupplier cancelled) {
    java.util.Objects.requireNonNull(cancelled);
    BooleanSupplier outer = CANCELLATION.orElse(() -> false);
    return ScopedValue.where(CANCELLATION, () -> outer.getAsBoolean() || cancelled.getAsBoolean());
  }

  /** Observe hard stops even when an operation reuses an already acquired native lease. */
  public static void checkCancellation() {
    if (Thread.currentThread().isInterrupted() || CANCELLATION.orElse(() -> false).getAsBoolean()) {
      throw new CancellationException("background inference cancelled");
    }
  }

  /** The same live authority for per-document fallback APIs that accept an inference request. */
  public static InferenceRequest backgroundInference() {
    var acquisition = background();
    return new InferenceRequest(
        InferenceRequest.Urgency.BACKGROUND,
        acquisition.deadlineNanos(), acquisition.cancellationRequested());
  }

  public static SessionAcquisitionRequest foreground() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, ACQUIRE_TIMEOUT);
  }

  public static SessionAcquisitionRequest background() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.BACKGROUND, ACQUIRE_TIMEOUT,
        CANCELLATION.orElse(() -> false));
  }
}
