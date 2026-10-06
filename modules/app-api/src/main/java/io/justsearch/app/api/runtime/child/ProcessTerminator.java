/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The OS side of a stop (Spec B §2.3): an owned handle per child, one terminate call with a chosen
 * code, and the OS exit facts the classification reads. A seam so the stop algorithm runs against
 * fakes in tests; production uses {@link #forCurrentOs()}.
 */
public interface ProcessTerminator {

  /** Opens the owned OS handle of a just-started child. Kept until its exit is recorded. */
  OsProcess open(Process process) throws IOException;

  /** The code a terminate call ends a child with: {@code JS_CHILD_STOP} on Windows. */
  int ourCode();

  /** Whether {@link #ourCode()} tells our terminate apart from any exit the child makes itself. */
  boolean codeDistinctive();

  /**
   * A live process's creation FILETIME (100 ns ticks since 1601, as a decimal string); empty when
   * the process is gone, unreadable, or the OS has no such time.
   */
  Optional<String> creationFileTime(long pid);

  /** FFM {@code TerminateProcess} on Windows; the JDK's forcible destroy elsewhere. */
  static ProcessTerminator forCurrentOs() {
    if (File.separatorChar == '\\') {
      ProcessTerminator windows = WindowsProcessTerminator.load();
      if (windows != null) return windows;
      return new PosixProcessTerminator(1); // FFM unavailable: the JDK destroy, code 1
    }
    return new PosixProcessTerminator(PosixProcessTerminator.SIGKILL_CODE);
  }

  /** One child's owned OS handle. */
  interface OsProcess {
    /** The child's creation FILETIME as a decimal string; empty where the OS has none. */
    Optional<String> creationFileTime();

    /** One terminate call with {@code code}: OK, ALREADY_EXITED or FAILED(error). */
    TerminateResult terminate(int code);

    /** Waits up to {@code max} for the child to exit; true when it has. */
    boolean awaitExit(Duration max) throws InterruptedException;

    /** The OS exit code; empty while alive or when it cannot be read. */
    OptionalInt exitCode();

    /** The OS exit time; empty when unknown. */
    Optional<Instant> exitedAt();

    /** Releases the handle. Idempotent. */
    void close();
  }
}
