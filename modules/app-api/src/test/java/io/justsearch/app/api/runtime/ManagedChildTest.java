/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ManagedChildTest {
  private static final String START = "2026-10-01T00:00:00.123Z";
  private static final String EXE = ManagedChild.normalizePath(Path.of("child.exe"));

  @Test
  void sameSourceStartsMustMatchExactlyIncludingPidAndExecutable() {
    ManagedChild child =
        new ManagedChild(
            "child",
            ManagedChild.Kind.EXTRACTION,
            12,
            START,
            EXE,
            "stdio",
            null,
            "declared",
            "argv");
    assertEquals(ManagedChild.IdentityMatch.MATCH, child.identityOf(handle(12, START, EXE)));
    for (long delta : new long[] {1, 500, 999, 1000}) {
      String reusedStart = Instant.parse(START).plusMillis(delta).toString();
      assertEquals(
          ManagedChild.IdentityMatch.MISMATCH,
          child.identityOf(handle(12, reusedStart, EXE)),
          "different birth by " + delta + " ms");
    }
    assertEquals(
        ManagedChild.IdentityMatch.MISMATCH,
        child.identityOf(handle(12, Instant.parse(START).plusNanos(1).toString(), EXE)));
    assertEquals(ManagedChild.IdentityMatch.MISMATCH, child.identityOf(handle(13, START, EXE)));
    assertEquals(
        ManagedChild.IdentityMatch.MISMATCH,
        child.identityOf(handle(12, START, EXE + ".unrelated")));
    assertEquals(ManagedChild.IdentityMatch.UNKNOWN, child.identityOf(handle(12, null, EXE)));
  }

  private static ProcessHandle handle(long pid, String start, String executable) {
    ProcessHandle.Info info =
        (ProcessHandle.Info)
            Proxy.newProxyInstance(
                ProcessHandle.Info.class.getClassLoader(),
                new Class<?>[] {ProcessHandle.Info.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "startInstant" -> Optional.ofNullable(start).map(Instant::parse);
                      case "command" -> Optional.ofNullable(executable);
                      default -> throw new UnsupportedOperationException(method.getName());
                    });
    return (ProcessHandle)
        Proxy.newProxyInstance(
            ProcessHandle.class.getClassLoader(),
            new Class<?>[] {ProcessHandle.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "pid" -> pid;
                  case "info" -> info;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }
}
