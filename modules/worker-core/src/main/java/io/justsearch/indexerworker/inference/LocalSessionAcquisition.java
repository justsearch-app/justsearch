/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.inference;

import io.justsearch.ort.SessionAcquisitionRequest;
import java.time.Duration;

/**
 * Bounded scheduling inputs for local work without admitted Engine request authority.
 *
 * <p>The generous local horizon preserves the former wait behavior while making every native
 * session acquisition monotonic, interrupt-cancellable, and finite. Engine query paths instead pass
 * the caller's urgency, absolute deadline, and cooperative cancellation authority explicitly.
 */
public final class LocalSessionAcquisition {
  private static final Duration ACQUIRE_TIMEOUT = Duration.ofMinutes(5);

  private LocalSessionAcquisition() {}

  public static SessionAcquisitionRequest foreground() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.FOREGROUND, ACQUIRE_TIMEOUT);
  }

  public static SessionAcquisitionRequest background() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.BACKGROUND, ACQUIRE_TIMEOUT);
  }
}
