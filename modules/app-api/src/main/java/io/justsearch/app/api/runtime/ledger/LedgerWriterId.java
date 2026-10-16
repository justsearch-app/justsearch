/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code writer} block of every ledger record: who wrote it, so a reader can tell writer
 * sessions apart ({@code seq} restarts at 1 in each).
 */
public record LedgerWriterId(Role role, long pid, Instant startedAt) {

  public LedgerWriterId {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(startedAt, "startedAt");
    if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
  }

  /** The writer of the current JVM, started now. */
  public static LedgerWriterId currentProcess(Role role, Instant startedAt) {
    return new LedgerWriterId(role, ProcessHandle.current().pid(), startedAt);
  }

  /** The processes that write the ledger. */
  public enum Role {
    ENGINE("engine"),
    SHELL("shell"),
    DEV_RUNNER("dev-runner"),
    DEV_CLI("dev-cli");

    private final String token;

    Role(String token) {
      this.token = token;
    }

    @JsonValue
    public String token() {
      return token;
    }

    public static Optional<Role> fromToken(String token) {
      return Arrays.stream(values()).filter(r -> r.token.equals(token)).findFirst();
    }
  }
}
