/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/** The native-session handle has entered monotonic retirement and cannot issue another lease. */
public final class SessionRetiredException extends SessionAcquisitionException {
  public SessionRetiredException(String message) {
    super(message);
  }
}
