/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
final class LinuxChildReapingRaceTest {
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
    var held = ProcessTerminator.forCurrentOs().open(delayed);
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
