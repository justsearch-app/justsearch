/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/** A session cannot be leased safely now, but owner recovery may make it available later. */
public final class SessionTemporarilyUnavailableException extends SessionAcquisitionException {
  public SessionTemporarilyUnavailableException(String message) {
    super(message);
  }

  public SessionTemporarilyUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
