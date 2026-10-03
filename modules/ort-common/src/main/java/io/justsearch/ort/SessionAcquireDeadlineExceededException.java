/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.core.execution.InferenceRequest;

/** The monotonic acquisition deadline elapsed before a native lease could be issued. */
public final class SessionAcquireDeadlineExceededException extends SessionAcquisitionException
    implements InferenceRequest.DeadlineExceeded {
  public SessionAcquireDeadlineExceededException(String message) {
    super(message);
  }
}
