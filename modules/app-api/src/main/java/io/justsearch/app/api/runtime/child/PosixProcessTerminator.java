/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/**
 * Terminates through the JDK's forcible destroy (Spec B §2.3, non-Windows): SIGKILL, which the JDK
 * reports as 137. The code is not distinctive, so a REQUESTED class still needs our terminate call
 * to have been made. Also the Windows fallback when FFM cannot bind kernel32 (code 1).
 */
final class PosixProcessTerminator implements ProcessTerminator {
  /** What the JDK reports for a SIGKILLed child. */
  static final int SIGKILL_CODE = 137;

  private final int ourCode;

  PosixProcessTerminator(int ourCode) {
    this.ourCode = ourCode;
  }

  @Override
  public OsProcess open(Process process) {
    return new JdkProcess(process);
  }

  @Override
  public OsProcess open(ProcessHandle process) {
    return new OsProcess() {
      @Override public Optional<String> creationFileTime() { return Optional.empty(); }
      @Override
      public TerminateResult terminate(int code) {
        if (!process.isAlive()) return TerminateResult.ALREADY_EXITED;
        return process.destroyForcibly() ? TerminateResult.OK : TerminateResult.failed(-1);
      }
      @Override public boolean awaitExit(Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (process.isAlive()) {
          if (System.nanoTime() >= deadline) return false;
          TimeUnit.MILLISECONDS.sleep(10);
        }
        return true;
      }
      // A non-child ProcessHandle cannot supply an exit code or time. Never invent them.
      @Override public OptionalInt exitCode() { return OptionalInt.empty(); }
      @Override public Optional<Instant> exitedAt() { return Optional.empty(); }
      @Override public void close() {}
    };
  }

  @Override
  public int ourCode() {
    return ourCode;
  }

  @Override
  public boolean codeDistinctive() {
    return false;
  }

  @Override
  public Optional<String> creationFileTime(long pid) {
    return Optional.empty();
  }

  private static final class JdkProcess implements OsProcess {
    private final Process process;

    JdkProcess(Process process) {
      this.process = process;
    }

    @Override
    public Optional<String> creationFileTime() {
      return Optional.empty();
    }

    @Override
    public TerminateResult terminate(int code) {
      if (!process.isAlive()) return TerminateResult.ALREADY_EXITED;
      process.destroyForcibly();
      return TerminateResult.OK;
    }

    @Override
    public boolean awaitExit(Duration max) throws InterruptedException {
      return process.waitFor(Math.max(0, max.toMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public OptionalInt exitCode() {
      try {
        return OptionalInt.of(process.exitValue());
      } catch (IllegalThreadStateException alive) {
        return OptionalInt.empty();
      }
    }

    @Override
    public Optional<Instant> exitedAt() {
      return Optional.empty();
    }

    @Override
    public void close() {}
  }
}
