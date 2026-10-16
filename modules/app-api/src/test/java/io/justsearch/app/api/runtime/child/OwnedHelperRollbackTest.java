/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
final class OwnedHelperRollbackTest {
  @Test void failedRollbackReturnsTheExactSurvivor() throws Exception {
    var exited = new java.util.concurrent.CompletableFuture<Process>();
    var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
    Process survivor = new Process() {
      @Override public long pid() { return Integer.MAX_VALUE; }
      @Override public ProcessHandle toHandle() { return ProcessHandle.current(); }
      @Override public boolean isAlive() { return alive.get(); }
      @Override public java.util.concurrent.CompletableFuture<Process> onExit() { return exited; }
      @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
      @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
      @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
      @Override public int waitFor() { return 0; }
      @Override public boolean waitFor(long max, TimeUnit unit) { return false; }
      @Override public int exitValue() { throw new IllegalThreadStateException(); }
      @Override public void destroy() {}
      @Override public Process destroyForcibly() { return this; } // No actual process exists.
    };
    try {
      IOException failure = assertThrows(IOException.class, () -> OwnedHelper.own(survivor, "survivor fixture"));
      Object retained = assertDoesNotThrow(() -> failure.getClass().getMethod("survivor").invoke(failure),
          "an unconfirmed rollback must return explicit ownership of its survivor");
      assertEquals(java.util.Optional.of(survivor), retained);
    } finally { alive.set(false); exited.complete(survivor); }
  }

  @Test void failedHandleAcquisitionRollsBackSpawn() throws Exception {
    Process raw = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
        "-Command", "Start-Sleep -Seconds 30").redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    var os = ProcessTerminator.forCurrentOs();
    String birth = os.creationFileTime(raw.pid()).orElseThrow();
    var cleanup = os.open(raw);
    assertEquals(birth, cleanup.creationFileTime().orElseThrow());
    System.out.println("fixture-start=" + raw.pid() + "/" + birth);
    // Only handle acquisition sees an invalid PID. Rollback retains the original spawn object.
    Process failedOpen = new Process() {
      @Override public long pid() { return Integer.MAX_VALUE; }
      @Override public ProcessHandle toHandle() { return raw.toHandle(); }
      @Override public boolean isAlive() { return raw.isAlive(); }
      @Override public InputStream getInputStream() { return raw.getInputStream(); }
      @Override public InputStream getErrorStream() { return raw.getErrorStream(); }
      @Override public OutputStream getOutputStream() { return raw.getOutputStream(); }
      @Override public int waitFor() throws InterruptedException { return raw.waitFor(); }
      @Override public boolean waitFor(long max, TimeUnit unit) throws InterruptedException {
        return raw.waitFor(max, unit);
      }
      @Override public int exitValue() { return raw.exitValue(); }
      @Override public void destroy() { destroyForcibly(); }
      @Override public Process destroyForcibly() {
        assertEquals(birth, cleanup.creationFileTime().orElseThrow());
        cleanup.terminate(os.ourCode()); return this;
      }
    };
    try {
      assertThrows(IOException.class, () -> OwnedHelper.own(failedOpen, "rollback fixture"));
      assertFalse(raw.isAlive(), "failed registration must roll back the exact spawn before returning");
    } finally {
      if (raw.isAlive()) {
        assertEquals(birth, cleanup.creationFileTime().orElseThrow());
        cleanup.terminate(os.ourCode());
      }
      assertTrue(cleanup.awaitExit(Duration.ofSeconds(5)));
      cleanup.close();
    }
  }
}
