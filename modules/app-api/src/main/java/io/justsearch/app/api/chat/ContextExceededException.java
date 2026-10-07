/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** The server rejected an individually oversized request. Replaying it cannot repair it. */
public final class ContextExceededException extends RuntimeException {
  public ContextExceededException() {
    super("the request is larger than the model's context window");
  }

  public static boolean causedBy(Throwable failure) {
    for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
      if (failure instanceof ContextExceededException) return true;
    }
    return false;
  }
}
