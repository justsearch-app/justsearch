/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** A new answer was refused by the automatic indexing policy; no model lifecycle change. */
public final class AnswersPausedException extends GenerationUnavailableException {
  private static final long serialVersionUID = 1L;
  public static final String MESSAGE = "Answers paused while indexing";
  public AnswersPausedException() { super(MESSAGE); }

  public static boolean causedByPause(Throwable failure) {
    for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
      if (failure instanceof AnswersPausedException) return true;
    }
    return false;
  }
}
