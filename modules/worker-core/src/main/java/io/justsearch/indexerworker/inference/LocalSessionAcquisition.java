/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.inference;

import io.justsearch.ort.SessionAcquisitionRequest;
import java.time.Duration;

/**
 * Bounded scheduling inputs used until D2 propagates admitted request authority into encoders.
 *
 * <p>The generous local horizon preserves the former wait behavior while making every native
 * session acquisition monotonic, interrupt-cancellable, and finite. D2 replaces these factories at
 * the encoder boundary with the caller's urgency, deadline, and cancellation authority.
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
