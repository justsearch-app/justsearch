/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.justsearch.app.api.runtime.child.LinuxProcessIdentity;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code writer} block of every ledger record: who wrote it, so a reader can tell writer
 * sessions apart ({@code seq} restarts at 1 in each).
 */
public record LedgerWriterId(Role role, long pid, Instant startedAt,
    @JsonInclude(JsonInclude.Include.NON_NULL) LinuxProcessIdentity processIdentity) {

  public LedgerWriterId(Role role, long pid, Instant startedAt) { this(role, pid, startedAt, null); }

  public LedgerWriterId {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(startedAt, "startedAt");
    if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
    if (processIdentity != null && processIdentity.pid() != pid)
      throw new IllegalArgumentException("writer identity PID must agree");
  }

  /** The writer of the current JVM, started now. */
  public static LedgerWriterId currentProcess(Role role, Instant startedAt) {
    long pid = ProcessHandle.current().pid();
    return new LedgerWriterId(role, pid, startedAt, LinuxProcessIdentity.read(pid).orElse(null));
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
