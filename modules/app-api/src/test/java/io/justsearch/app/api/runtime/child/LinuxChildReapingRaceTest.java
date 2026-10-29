/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
final class LinuxChildReapingRaceTest {
  @Test @Timeout(10)
  void transientExeAccessDenialKeepsPollingAndPreservesExitStatus() throws Exception {
    for (int status : new int[] {0, 37}) {
      AtomicInteger reads = new AtomicInteger();
      Process child = assertDoesNotThrow(() -> BoundChildProcess.spawn(
          wrapped("/bin/sh", "-c", "read ignored; exit " + status), BoundChildProcess.helper(), link -> {
            if (reads.incrementAndGet() <= 3) throw new AccessDeniedException(link.toString());
            return Files.readSymbolicLink(link);
          }), "a racing exit's procfs denial must not abort a launch");
      try {
        assertTrue(reads.get() >= 4, "retry until the workload image is readable");
        assertEquals(LinuxProcessIdentity.read(child.pid()), BoundChildProcess.birthOf(child));
        assertTrue(BoundChildProcess.birthOf(child).isPresent());
        Process owned = OwnedHelper.own(child, "transient exe-link denial");
        // EOF releases the workload; no timing-dependent sleep or stress loop is needed.
        owned.getOutputStream().close();
        assertTrue(owned.waitFor(2, TimeUnit.SECONDS));
        assertEquals(status, owned.exitValue());
      } finally {
        child.destroyForcibly();
        assertTrue(child.waitFor(2, TimeUnit.SECONDS));
      }
    }
  }

  @Test @Timeout(10)
  void unreadableWrapperStillFailsAtTheExecDeadline() throws Exception {
    assertWrapperDeadline(link -> { throw new AccessDeniedException(link.toString()); });
  }

  @Test @Timeout(10)
  void unchangedWrapperStillFailsAtTheExecDeadline() throws Exception {
    assertWrapperDeadline(Files::readSymbolicLink);
  }

  private static void assertWrapperDeadline(BoundChildProcess.ExeLinkReader reader) throws Exception {
    // This wrapper stays blocked on stdin and never execs a workload.
    var wrapper = new ProcessBuilder("/bin/sh", "-c", "read ignored", "test-wrapper", "workload");
    long started = System.nanoTime();
    var failure = assertThrows(java.io.IOException.class,
        () -> BoundChildProcess.spawn(wrapper, Path.of("/bin/sh").toRealPath(), reader));
    assertTrue(failure.getMessage().contains("Linux child wrapper did not exec"), failure::toString);
    assertTrue(System.nanoTime() - started >= Duration.ofSeconds(2).toNanos(),
        "an unreadable image must retain the existing deadline");
  }

  @Test @Timeout(10)
  void missingExeLinkKeepsPolling() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    Process child = assertDoesNotThrow(() -> BoundChildProcess.spawn(
        wrapped("/bin/sh", "-c", "read ignored"), BoundChildProcess.helper(), link -> {
          if (reads.incrementAndGet() == 1) throw new NoSuchFileException(link.toString());
          return Files.readSymbolicLink(link);
        }));
    try {
      assertTrue(reads.get() >= 2);
      child.getOutputStream().write('\n');
      child.getOutputStream().flush();
      assertTrue(child.waitFor(2, TimeUnit.SECONDS));
      assertEquals(0, child.exitValue());
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(2, TimeUnit.SECONDS));
    }
  }

  @Test @Timeout(10)
  void wrapperRefusalStillIncludesExit125AndItsDetail() throws Exception {
    var failure = assertThrows(java.io.IOException.class,
        () -> BoundChildProcess.start(new ProcessBuilder("/no/such/justsearch-program")));
    assertTrue(failure.getMessage().contains("Linux child wrapper refused launch (exit 125)"), failure::toString);
    assertTrue(failure.getMessage().contains("exec"), failure::toString);
    assertTrue(failure.getMessage().contains("No such file or directory"), failure::toString);
  }

  private static ProcessBuilder wrapped(String... workload) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add(BoundChildProcess.helper().toString());
    command.add("exec");
    command.add(Long.toString(ProcessHandle.current().pid()));
    command.add("--");
    command.addAll(java.util.List.of(workload));
    return new ProcessBuilder(command);
  }

  @Test void endedChildIsAdmittedAfterItsOriginalHandleConfirmsExit() throws Exception {
    Process child = new ProcessBuilder("/bin/true").start();
    try {
      assertTrue(child.waitFor(2, TimeUnit.SECONDS));
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertTrue(child.waitFor(2, TimeUnit.SECONDS));
      }
    }
    var delayed = new DelayedReaper(child.pid(), true);
    var held = assertDoesNotThrow(() -> ProcessTerminator.forCurrentOs().open(delayed));
    try {
      assertTrue(delayed.waited, "reconcile procfs absence with the original spawn object");
      assertTrue(held.processIdentity().isEmpty(), "do not fabricate a birth for an ended child");
      assertTrue(held.awaitExit(Duration.ZERO));
      assertEquals(0, held.exitCode().orElseThrow());
    } finally { held.close(); }
  }

  @Test void shortLivedHelpersKeepTheirActualExitStatus() throws Exception {
    for (int i = 0; i < 128; i++) {
      Process child = OwnedHelper.start(new ProcessBuilder("/bin/true")
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD),
          "short-lived helper receipt");
      try {
        assertTrue(child.waitFor(2, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
      } finally {
        if (child.isAlive()) {
          child.destroyForcibly();
          assertTrue(child.waitFor(2, TimeUnit.SECONDS));
        }
      }
    }
  }

  @Test void missingBirthWithoutOriginalHandleExitStillRefusesOwnership() {
    for (long pid : new long[] {0, Integer.MAX_VALUE}) {
      var child = new DelayedReaper(pid, false);
      assertThrows(java.io.IOException.class, () -> ProcessTerminator.forCurrentOs().open(child));
      assertTrue(child.isAlive(), "unknown or absent procfs evidence cannot confirm a handle's exit");
    }
  }

  @Test void interruptedReapingPreservesInterruptionAndRefusesOwnership() {
    var child = new DelayedReaper(Integer.MAX_VALUE, false);
    Thread.currentThread().interrupt();
    try {
      assertThrows(java.io.IOException.class, () -> ProcessTerminator.forCurrentOs().open(child));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally { Thread.interrupted(); }
  }

  /** Models the JDK reaper publishing an already ended child's exit after the procfs observation. */
  private static final class DelayedReaper extends Process {
    private final long pid;
    private final boolean ended;
    private boolean waited;
    DelayedReaper(long pid, boolean ended) { this.pid = pid; this.ended = ended; }
    @Override public long pid() { return pid; }
    @Override public boolean isAlive() { return !waited || !ended; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (Thread.interrupted()) throw new InterruptedException();
      waited = true;
      return ended;
    }
    @Override public int waitFor() { waited = true; return exitValue(); }
    @Override public int exitValue() {
      if (isAlive()) throw new IllegalThreadStateException();
      return 0;
    }
    @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public void destroy() { throw new AssertionError("ownership must not signal a PID"); }
  }
}
