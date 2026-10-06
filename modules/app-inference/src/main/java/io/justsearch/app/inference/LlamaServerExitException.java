/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.chat.FailedCode;
import java.util.Objects;

/**
 * A managed llama-server exited before it became healthy. Carries the exit code and its {@link
 * LlamaServerExitClassifier.Classification}, so the policy above the launcher acts on the class
 * rather than re-reading the log (Spec A §7.1).
 */
public final class LlamaServerExitException extends ModeTransitionException {
  private static final long serialVersionUID = 1L;

  private final int exitCode;
  private final transient LlamaServerExitClassifier.Classification classification;
  private final FailedCode failedCode;

  LlamaServerExitException(
      Reason reason,
      String message,
      int exitCode,
      LlamaServerExitClassifier.Classification classification) {
    this(reason, message, exitCode, classification, classification.failedCode());
  }

  private LlamaServerExitException(
      Reason reason,
      String message,
      int exitCode,
      LlamaServerExitClassifier.Classification classification,
      FailedCode failedCode) {
    super(reason, message);
    this.exitCode = exitCode;
    this.classification = Objects.requireNonNull(classification, "classification");
    this.failedCode = Objects.requireNonNull(failedCode, "failedCode");
  }

  /** The same exit, reported with the given message and failed code. */
  LlamaServerExitException withOutcome(String message, FailedCode code) {
    return new LlamaServerExitException(reason(), message, exitCode, classification, code);
  }

  /** The process exit code, or -1 when it was not readable. */
  public int exitCode() {
    return exitCode;
  }

  public LlamaServerExitClassifier.Classification classification() {
    return classification;
  }

  /** What chat reports for this exit when it ends the start attempt. */
  public FailedCode failedCode() {
    return failedCode;
  }

  /**
   * The code a failed start reports (Spec A §3.1 FAILED codes): the classified code for an exit,
   * {@code startup_timeout} for the 120 s health deadline, {@code missing_dll} for a missing
   * dependency, otherwise {@code start_failed}.
   */
  public static FailedCode failedCodeOf(Throwable failure) {
    if (failure instanceof LlamaServerExitException exit) return exit.failedCode();
    if (failure instanceof ModeTransitionException transition) {
      return switch (transition.reason()) {
        case HEALTH_CHECK_TIMEOUT -> FailedCode.STARTUP_TIMEOUT;
        case MISSING_DLL -> FailedCode.MISSING_DLL;
        default -> FailedCode.START_FAILED;
      };
    }
    return FailedCode.START_FAILED;
  }
}
