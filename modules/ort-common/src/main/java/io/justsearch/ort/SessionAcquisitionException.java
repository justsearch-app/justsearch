/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

/** Base type for native-session acquisition refusals. */
public abstract class SessionAcquisitionException extends RuntimeException {
  protected SessionAcquisitionException(String message) {
    super(message);
  }

  protected SessionAcquisitionException(String message, Throwable cause) {
    super(message, cause);
  }
}
