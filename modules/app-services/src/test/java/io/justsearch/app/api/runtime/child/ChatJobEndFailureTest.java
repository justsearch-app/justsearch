/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.containment.WindowsEngineJob;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * When what a chat root left in its job cannot be ended, the ledger says so after the intent, the
 * leftover stays counted and keeps refusing chat starts, and its own later exit is recorded. The
 * failure is injected at the job-limit call only; the processes and their job are real, and the
 * leftover is released by its exit file, never terminated by PID.
 */
@EnabledOnOs(OS.WINDOWS)
final class ChatJobEndFailureTest {
  private static final String NATIVE_ERROR = "SetInformationJobObject failed: 5";
  @TempDir Path directory;

  @Test @Timeout(120)
  void aLeftoverThatCannotBeEndedIsRecordedCountedAndItsExitRecorded() throws Exception {
    List<String> notes = new CopyOnWriteArrayList<>();
    LedgerSink sink = (type, fields) -> {
      if (type == LedgerRecordType.CHILD_NOTE) notes.add(String.valueOf(fields.get("token")));
      return true;
    };
    Path home = Files.createDirectory(directory.resolve("leftover"));
    Class<?> kernel = Class.forName(WindowsEngineJob.class.getName() + "$Kernel");
    try (var control = DefaultChildControl.of(sink, ProcessTerminator.forCurrentOs(),
        ManagedChildRegistry.noop())) {
      try {
        ChildHandle root = control.start(spec(ChatJobAccountingTest.Fixture.argv("wrapper", home)));
        root.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
        ProcessHandle server = published(home);
        Instant birth = server.info().startInstant().orElseThrow();
        String identity = server.pid() + "@" + birth;
        String reason = "before_" + ChildKind.LLAMA_SERVER + "_start";

        IOException refusal;
        try (var _ = Mockito.mockStatic(kernel, invocation -> {
          Object[] raw = invocation.getRawArguments();
          if (raw.length == 2 && "SetInformationJobObject".equals(raw[0])) throw new IOException(NATIVE_ERROR);
          return invocation.callRealMethod();
        })) {
          refusal = assertThrows(IOException.class,
              () -> control.start(spec(ChatJobAccountingTest.Fixture.argv("idle", home))));
        }
        assertEquals("chat start blocked: could not end leftover chat processes (" + NATIVE_ERROR + ")",
            refusal.getMessage());
        String intent = "intended_cleanup:" + reason + ":job_member:" + identity;
        String outcome = "cleanup_not_ended:" + reason + ":job_member:" + identity + ":" + NATIVE_ERROR;
        assertTrue(notes.contains(intent), notes.toString());
        assertTrue(notes.indexOf(intent) < notes.indexOf(outcome), "the outcome follows the intent: " + notes);
        assertTrue(server.isAlive(), "the leftover was not ended");
        assertTrue(root.descendants().stream().anyMatch(p -> p.pid() == server.pid()),
            "the leftover is still counted in its root's scope");

        // Its own exit is recorded, once, and then a chat start proceeds.
        Files.writeString(home.resolve("exit"), "exit");
        server.onExit().get(15, TimeUnit.SECONDS);
        String exited = "leftover_exited:job_member:" + identity;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!notes.contains(exited) && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(1, notes.stream().filter(exited::equals).count(), notes.toString());
        ChildHandle next = control.start(spec(ChatJobAccountingTest.Fixture.argv("idle", home)));
        next.stdin().close();
        next.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
      } finally {
        Files.writeString(home.resolve("exit"), "exit");
        awaitPublishedExit(home);
      }
    }
  }

  private static ProcessHandle published(Path home) throws Exception {
    Path file = home.resolve("identity");
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(10);
    String[] fields = Files.readString(file).trim().split(" ");
    Instant birth = Instant.parse(fields[1]);
    return ProcessHandle.of(Long.parseLong(fields[0]))
        .filter(p -> p.info().startInstant().filter(birth::equals).isPresent())
        .orElseThrow(() -> new AssertionError("the leftover server is not running"));
  }

  private static void awaitPublishedExit(Path home) throws Exception {
    Path file = home.resolve("identity");
    if (!Files.exists(file)) file = home.resolve("spawn-identity");
    if (!Files.exists(file)) return;
    String[] fields = Files.readString(file).trim().split(" ");
    Instant birth = Instant.parse(fields[1]);
    var process = ProcessHandle.of(Long.parseLong(fields[0]))
        .filter(p -> p.info().startInstant().filter(birth::equals).isPresent());
    if (process.isPresent()) process.get().onExit().get(15, TimeUnit.SECONDS);
  }

  private static ChildSpec spec(List<String> argv) {
    return new ChildSpec(ChildKind.LLAMA_SERVER, argv, null, Map.of(), new ChildSpec.Stdio.Pipes(),
        false, OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "end-failure");
  }
}
