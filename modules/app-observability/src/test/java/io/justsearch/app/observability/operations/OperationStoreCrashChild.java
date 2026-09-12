/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Abrupt process-exit fixture: deliberately runs neither close nor shutdown hooks. */
public final class OperationStoreCrashChild {
  private OperationStoreCrashChild() {}

  public static void main(String[] args) throws Exception {
    Clock clock = Clock.fixed(Instant.ofEpochMilli(Long.parseLong(args[2])), ZoneOffset.UTC);
    try (var store = new SqliteOperationStore(Path.of(args[0]), clock, step -> {
      if (step.equals(args[1])) Runtime.getRuntime().halt(71);
    })) {
      throw new AssertionError("Requested crash point was not reached; recovery=" + store.recovery());
    }
  }
}
