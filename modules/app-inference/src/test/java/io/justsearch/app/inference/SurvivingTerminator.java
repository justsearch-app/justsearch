/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.child.TerminateResult;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The OS terminator, except that while {@link #survive} is set a terminate call reports OK but
 * kills nothing and the wait after it returns at once: the stop of a real process then ends in
 * SURVIVED without the 10 s of real waits. Counts the terminate calls.
 */
final class SurvivingTerminator implements ProcessTerminator {
  private final ProcessTerminator real = ProcessTerminator.forCurrentOs();
  private final List<Process> processes = Collections.synchronizedList(new ArrayList<>());
  final AtomicInteger terminateCalls = new AtomicInteger();
  volatile boolean survive;

  @Override
  public OsProcess open(Process process) throws IOException {
    processes.add(process);
    OsProcess os = real.open(process);
    return new OsProcess() {
      @Override
      public Optional<String> creationFileTime() {
        return os.creationFileTime();
      }

      @Override
      public TerminateResult terminate(int code) {
        terminateCalls.incrementAndGet();
        return survive ? TerminateResult.OK : os.terminate(code);
      }

      @Override
      public boolean awaitExit(Duration max) throws InterruptedException {
        if (survive && process.isAlive()) return false;
        return os.awaitExit(max);
      }

      @Override
      public OptionalInt exitCode() {
        return os.exitCode();
      }

      @Override
      public Optional<Instant> exitedAt() {
        return os.exitedAt();
      }

      @Override
      public void close() {
        os.close();
      }
    };
  }

  @Override
  public int ourCode() {
    return real.ourCode();
  }

  @Override
  public boolean codeDistinctive() {
    return real.codeDistinctive();
  }

  @Override
  public Optional<String> creationFileTime(long pid) {
    return real.creationFileTime(pid);
  }

  /** The processes started under this terminator that are still alive. */
  List<Process> alive() {
    synchronized (processes) {
      return processes.stream().filter(Process::isAlive).toList();
    }
  }

  /** Ends every process started under it (test cleanup). */
  void killAll() {
    survive = false;
    synchronized (processes) {
      for (Process process : processes) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }
}
