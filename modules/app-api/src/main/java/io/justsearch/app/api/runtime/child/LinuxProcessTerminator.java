/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/** Original spawn objects for children; identity-checked pidfds for recorded non-children. */
final class LinuxProcessTerminator implements ProcessTerminator {
  private final PosixProcessTerminator children = new PosixProcessTerminator(PosixProcessTerminator.SIGKILL_CODE);

  @Override public IdentityPresence identityPresence(long pid) { return LinuxProcessIdentity.observe(pid).presence(); }
  @Override public int ourCode() { return PosixProcessTerminator.SIGKILL_CODE; }
  @Override public boolean codeDistinctive() { return false; }
  @Override public Optional<String> creationFileTime(long pid) { return Optional.empty(); }

  @Override public OsProcess open(Process process) throws IOException {
    var observed = LinuxProcessIdentity.observe(process.pid());
    if (observed.presence() != IdentityPresence.PRESENT && process.isAlive())
      throw new IOException("Cannot acquire Linux child birth identity");
    OsProcess held = children.open(process);
    return new OsProcess() {
      @Override public Optional<String> creationFileTime() { return Optional.empty(); }
      @Override public Optional<LinuxProcessIdentity> processIdentity() { return observed.identity(); }
      @Override public Optional<String> executable() { return observed.executable(); }
      @Override public TerminateResult terminate(int code) { return held.terminate(code); }
      @Override public boolean awaitExit(Duration max) throws InterruptedException { return held.awaitExit(max); }
      @Override public OptionalInt exitCode() { return held.exitCode(); }
      @Override public Optional<Instant> exitedAt() { return Optional.empty(); }
      @Override public void close() { held.close(); }
    };
  }

  @Override public OsProcess open(ProcessHandle process) throws IOException {
    return open(process, null); // Read-only observation; terminate refuses without a saved tuple.
  }

  @Override public OsProcess open(ProcessHandle process, LinuxProcessIdentity expected) throws IOException {
    var observed = LinuxProcessIdentity.observe(process.pid());
    LinuxProcessIdentity identity = observed.identity().orElseThrow(() -> new IOException("Linux identity unknown"));
    if (expected != null && !identity.equals(expected)) throw new IOException("Linux identity changed");
    return new OsProcess() {
      @Override public Optional<String> creationFileTime() { return Optional.empty(); }
      @Override public Optional<LinuxProcessIdentity> processIdentity() { return Optional.of(identity); }
      @Override public Optional<String> executable() { return observed.executable(); }
      @Override public TerminateResult terminate(int code) {
        if (expected == null) return TerminateResult.failed(-1);
        try {
          int result = expected.signal("KILL");
          return result == 0 ? TerminateResult.OK : result == 3 ? TerminateResult.ALREADY_EXITED : TerminateResult.failed(result);
        } catch (IOException | InterruptedException failure) {
          if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
          return TerminateResult.failed(-1);
        }
      }
      @Override public boolean awaitExit(Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        for (;;) {
          var live = LinuxProcessIdentity.observe(identity.pid());
          if (live.presence() == IdentityPresence.GONE) return true;
          if (live.identity().isPresent() && !live.identity().get().equals(identity)) return true;
          if (System.nanoTime() >= deadline) return false; // unreadable remains unknown, never ended
          TimeUnit.MILLISECONDS.sleep(10);
        }
      }
      @Override public OptionalInt exitCode() { return OptionalInt.empty(); }
      @Override public Optional<Instant> exitedAt() { return Optional.empty(); }
      @Override public void close() {}
    };
  }
}
