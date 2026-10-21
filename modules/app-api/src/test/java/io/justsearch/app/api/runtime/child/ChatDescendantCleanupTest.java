/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** #858: captured chat descendants end by TERM/KILL, including unreaped Linux zombies. */
@EnabledOnOs(OS.LINUX)
@Timeout(40)
final class ChatDescendantCleanupTest {
  @TempDir Path directory;

  @Test
  void termEndsACooperativeDescendantAndRecordsItsCleanup() throws Exception {
    endDescendant(false);
  }

  @Test
  void killEndsADescendantThatIgnoresTermAndAllowsTheNextChat() throws Exception {
    endDescendant(true);
  }

  private void endDescendant(boolean ignoreTerm) throws Exception {
    Path pidFile = directory.resolve("serving.pid");
    Path termFile = directory.resolve("term-seen");
    Path script = directory.resolve("wrapper.py");
    Files.writeString(script, """
        import os, signal, sys, time
        pid = os.fork()
        if pid == 0:
            def term(sig, frame):
                open(sys.argv[2], 'w').write('TERM')
                if sys.argv[3] == 'false':
                    os._exit(0)
            signal.signal(signal.SIGTERM, term)
            open(sys.argv[1], 'w').write(str(os.getpid()))
        while True:
            time.sleep(0.02)
        """);
    ProcessHandle serving = null;
    try (var harness = new ChildControlHarness(directory)) {
      var control = harness.control();
      ChildHandle root = control.start(new ChildSpec(ChildKind.LLAMA_SERVER,
          List.of("/usr/bin/python3", script.toString(), pidFile.toString(), termFile.toString(),
              Boolean.toString(ignoreTerm)), null, Map.of(), new ChildSpec.Stdio.Pipes(), false,
          OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "chat-wrapper"));
      awaitFile(pidFile);
      serving = root.descendants().stream()
          .filter(member -> member.pid() == readPid(pidFile)).findFirst().orElseThrow();
      var request = StopRequest.of(StopReason.ROLLBACK, StopMode.NOW, "cleanup-test");
      ChildControlHarness.await(control.stop(root, request));
      assertFalse(running(serving), "stop must end the captured serving descendant before returning");
      assertEquals(0, assertDoesNotThrow(() -> control.endRemaining(root, request),
          "cleanup must also be idempotent after stop"));
      assertTrue(Files.exists(termFile), "the descendant receives TERM before escalation");
      assertFalse(running(serving), "a zombie is ended even when PID 1 never reaps it");
      List<String> notes = harness.records(LedgerRecordType.CHILD_NOTE, root.childId()).stream()
          .map(record -> record.text("token").orElse("")).toList();
      String identity = serving.pid() + "@";
      int intent = index(notes, "intended_cleanup:ROLLBACK:job_member:" + identity);
      int ended = index(notes, "cleanup_ended:ROLLBACK:job_member:" + identity);
      assertTrue(intent >= 0 && ended > intent, "cleanup intent precedes its ended outcome: " + notes);
      assertTrue(notes.stream().noneMatch(note -> note.startsWith("cleanup_not_ended:")));
      ChildHandle next = control.start(new ChildSpec(ChildKind.LLAMA_SELF_TEST,
          List.of("/bin/sleep", "60"), null, Map.of(), new ChildSpec.Stdio.Pipes(), false,
          OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "next-chat"));
      assertEquals(ChildHandle.State.RUNNING, next.state());
      ChildControlHarness.await(control.stop(next, request));
      harness.assertLedgerConforms();
    } finally {
      if (serving != null && running(serving)) {
        serving.destroyForcibly();
        awaitEnded(serving);
      }
    }
  }

  @Test
  void anUnreapedZombieIsAbsentFromTheRetainedScope() throws Exception {
    Path pidFile = directory.resolve("zombie.pid");
    Path exitFile = directory.resolve("exit-child");
    Path script = directory.resolve("non-reaping-parent.py");
    Files.writeString(script, """
        import os, sys, time
        pid = os.fork()
        if pid == 0:
            open(sys.argv[1], 'w').write(str(os.getpid()))
            while not os.path.exists(sys.argv[2]):
                time.sleep(0.01)
            os._exit(0)
        sys.stdin.readline()
        os.waitpid(pid, 0)
        """);
    Process parent = new ProcessBuilder("/usr/bin/python3", script.toString(),
        pidFile.toString(), exitFile.toString()).start();
    try {
      awaitFile(pidFile);
      ProcessHandle child = ProcessHandle.of(readPid(pidFile)).orElseThrow();
      try (var descendants = new ChildDescendants(parent)) {
        assertTrue(descendants.snapshot().contains(child));
        Files.writeString(exitFile, "exit");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (LinuxProcessIdentity.observe(child.pid()).presence()
            != ProcessTerminator.IdentityPresence.GONE && System.nanoTime() < deadline)
          Thread.sleep(10);
        assertTrue(child.isAlive(), "the deliberately non-reaping parent still holds its zombie");
        assertEquals('Z', LinuxProcessIdentity.parseStat(
            Files.readString(Path.of("/proc", Long.toString(child.pid()), "stat"))).state());
        assertTrue(descendants.snapshot().isEmpty(), "an unreaped zombie cannot hold the chat barrier");
      }
    } finally {
      Files.writeString(exitFile, "exit");
      parent.getOutputStream().write('\n');
      parent.getOutputStream().flush();
      if (!parent.waitFor(5, TimeUnit.SECONDS)) {
        parent.destroyForcibly();
        assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void aMemberSurvivingBothSignalsStillBlocksTheNextChat() throws Exception {
    // A logical scope seam, as in ChatJobAccountingTest: only the two real sleep roots are
    // signalled. This mock uses our PID solely for a readable, running Linux identity observation.
    var survivor = new RefusingHandle(List.of());
    var wrapper = new RefusingHandle(List.of(survivor));
    try (var harness = new ChildControlHarness(directory)) {
      var control = harness.control();
      var spec = new ChildSpec(ChildKind.LLAMA_SELF_TEST, List.of("/bin/sleep", "60"),
          null, Map.of(), new ChildSpec.Stdio.Pipes(), false, OptionalLong.empty(),
          Optional.empty(), ChildSpec.CooperativeStop.NONE, "logical-survivor");
      ChildHandle root = control.start(spec);
      ChildControlHarness.await(control.stop(root, StopReason.SELF_TEST, StopMode.NOW));
      var scope = new ChildDescendants(wrapper);
      wrapper.alive.set(false);
      var field = root.getClass().getDeclaredField("descendants");
      field.setAccessible(true);
      field.set(root, scope);
      var refused = assertThrows(java.io.IOException.class, () -> control.start(spec));
      assertTrue(refused.getMessage().contains("outlived cleanup"), refused::getMessage);
      assertTrue(refused.getMessage().contains("no LLAMA_SELF_TEST starts beside it"));
      assertEquals(1, survivor.termCalls);
      assertEquals(1, survivor.killCalls);
      assertTrue(control.list().isEmpty(), "refusal cannot spawn a second chat root");
      assertTrue(harness.records(LedgerRecordType.CHILD_NOTE, root.childId()).stream()
          .anyMatch(record -> record.text("token").orElse("")
              .startsWith("cleanup_not_ended:before_LLAMA_SELF_TEST_start:job_member:")));
      survivor.alive.set(false);
      ChildHandle next = assertDoesNotThrow(() -> control.start(spec));
      ChildControlHarness.await(control.stop(next, StopReason.SELF_TEST, StopMode.NOW));
      harness.assertLedgerConforms();
    }
  }

  /** Logical original handles: signalling records the request and deliberately leaves them live. */
  private static final class RefusingHandle implements ProcessHandle {
    final AtomicBoolean alive = new AtomicBoolean(true);
    final List<ProcessHandle> members;
    final Info info = ProcessHandle.current().info();
    final CompletableFuture<ProcessHandle> exit = new CompletableFuture<>();
    int termCalls;
    int killCalls;

    RefusingHandle(List<ProcessHandle> members) { this.members = members; }
    @Override public long pid() { return ProcessHandle.current().pid(); }
    @Override public boolean isAlive() { return alive.get(); }
    @Override public Info info() { return info; }
    @Override public Optional<ProcessHandle> parent() { return Optional.empty(); }
    @Override public Stream<ProcessHandle> children() { return members.stream(); }
    @Override public Stream<ProcessHandle> descendants() { return members.stream(); }
    @Override public CompletableFuture<ProcessHandle> onExit() { return exit; }
    @Override public boolean supportsNormalTermination() { return true; }
    @Override public boolean destroy() { termCalls++; return false; }
    @Override public boolean destroyForcibly() { killCalls++; return false; }
    @Override public int compareTo(ProcessHandle other) { return Long.compare(pid(), other.pid()); }
  }

  private static int index(List<String> notes, String prefix) {
    for (int i = 0; i < notes.size(); i++) if (notes.get(i).startsWith(prefix)) return i;
    return -1;
  }

  private static long readPid(Path file) {
    try { return Long.parseLong(Files.readString(file).strip()); }
    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
  }

  private static void awaitFile(Path file) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while ((!Files.exists(file) || Files.size(file) == 0) && System.nanoTime() < deadline)
      Thread.sleep(10);
    assertTrue(Files.exists(file) && Files.size(file) > 0, "fixture published its identity");
  }

  private static boolean running(ProcessHandle process) {
    return process.isAlive() && LinuxProcessIdentity.observe(process.pid()).presence()
        != ProcessTerminator.IdentityPresence.GONE;
  }

  private static void awaitEnded(ProcessHandle process) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (running(process) && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse(running(process), "fixture cleanup must end its owned descendant");
  }
}
