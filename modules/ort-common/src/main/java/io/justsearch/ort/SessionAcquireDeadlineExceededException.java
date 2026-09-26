/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/** The monotonic acquisition deadline elapsed before a native lease could be issued. */
public final class SessionAcquireDeadlineExceededException extends SessionAcquisitionException {
  public SessionAcquireDeadlineExceededException(String message) {
    super(message);
  }
}
