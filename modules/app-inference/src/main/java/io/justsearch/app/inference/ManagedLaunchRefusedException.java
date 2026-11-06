/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.ModeTransitionException;

/**
 * A managed answer-server launch refused before any process started, because another owner holds
 * the answer model's files (design D4: "Removing files - try again when it finishes"). It is a
 * refusal, not a failed start: it is never latched as a chat failure, and the next launch after the
 * holder finishes starts normally.
 */
public final class ManagedLaunchRefusedException extends ModeTransitionException {
  private static final long serialVersionUID = 1L;

  public ManagedLaunchRefusedException(String message) {
    super(Reason.ALREADY_TRANSITIONING, message);
  }
}
