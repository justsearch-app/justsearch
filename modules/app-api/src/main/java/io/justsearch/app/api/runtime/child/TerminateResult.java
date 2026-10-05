/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * What the terminate call returned. The wire form is {@code OK}, {@code ALREADY_EXITED}, {@code
 * NOT_CALLED} or {@code FAILED:<win32 error>}.
 */
public record TerminateResult(Status status, OptionalInt error) {
  public static final TerminateResult NOT_CALLED =
      new TerminateResult(Status.NOT_CALLED, OptionalInt.empty());
  public static final TerminateResult OK = new TerminateResult(Status.OK, OptionalInt.empty());
  public static final TerminateResult ALREADY_EXITED =
      new TerminateResult(Status.ALREADY_EXITED, OptionalInt.empty());

  public TerminateResult {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(error, "error");
    if ((status == Status.FAILED) != error.isPresent()) {
      throw new IllegalArgumentException("an error code is present exactly when status is FAILED");
    }
  }

  public static TerminateResult failed(int win32Error) {
    return new TerminateResult(Status.FAILED, OptionalInt.of(win32Error));
  }

  /** The ledger form of this result. */
  public String wireValue() {
    return status == Status.FAILED ? "FAILED:" + error.getAsInt() : status.name();
  }

  /** The outcome of the terminate call. */
  public enum Status {
    NOT_CALLED,
    OK,
    ALREADY_EXITED,
    FAILED
  }
}
