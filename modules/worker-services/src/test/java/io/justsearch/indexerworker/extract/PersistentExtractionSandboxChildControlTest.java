/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static io.justsearch.indexerworker.extract.PersistentExtractionSandboxTest.javaCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.TerminateCodes;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.indexerworker.extract.ChildControlFixtures.Line;
import io.justsearch.indexerworker.extract.ChildControlFixtures.RecordingLedger;
import io.justsearch.indexerworker.extract.PersistentExtractionSandboxTest.ScriptedChild;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec B T19: every way the extraction pool ends a child is a {@link
 * io.justsearch.app.api.runtime.child.ChildControl} stop recorded with the pool's reason, and a
 * child that dies by itself is recorded as an unexpected exit with its own code.
 */
final class PersistentExtractionSandboxChildControlTest {
  private static final String POOL = "extraction-pool";

  @TempDir Path tempDir;

  private final RecordingLedger ledger = new RecordingLedger();
  private final ProcessTerminator terminator = ProcessTerminator.forCurrentOs();
  private final DefaultChildControl control =
      DefaultChildControl.of(ledger, terminator, ManagedChildRegistry.noop());

  @AfterEach
  void closeControl() {
    control.close();
  }

  private PersistentExtractionSandbox pool(Duration timeout, int maxRequests, String... jvmArgs) {
    return ChildControlFixtures.pool(
        javaCommand(ScriptedChild.class, jvmArgs), timeout, maxRequests, null, control);
  }

  private Path file(String name) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, "sandbox child content");
    return path;
  }

  /** The one stop-intent recorded for the child, asked for by the pool. */
  private Line intentOf(long pid) {
    List<Line> intents = ledger.of(LedgerRecordType.STOP_INTENT, pid);
    assertEquals(1, intents.size(), () -> "one stop-intent for pid " + pid + ": " + ledger.lines);
    Line intent = intents.getFirst();
    assertEquals(POOL, intent.get("requestedBy"));
    return intent;
  }

  /** A forced stop ends the child with the control's own code: JS_CHILD_STOP on Windows. */
  private void assertForcedWithOurCode(Line exit) {
    assertEquals(true, exit.get("forced"));
    assertEquals(OptionalInt.of(terminator.ourCode()), exit.get("code"));
    if (File.separatorChar == '\\') {
      assertEquals(OptionalInt.of(TerminateCodes.JS_CHILD_STOP), exit.get("code"));
    }
  }

  @Test
  @Timeout(60)
  void requestTimeoutIsARequestedStopForTimeout() throws Exception {
    try (var sandbox = pool(Duration.ofSeconds(3), 500)) {
      assertThrows(TimeboxedContentExtractor.ExtractionTimeoutException.class,
          () -> sandbox.extract(file("hang.txt")));
      long pid = (Long) ledger.of(LedgerRecordType.CHILD_START).getFirst().get("pid");

      assertEquals(StopReason.TIMEOUT, intentOf(pid).get("reason"));
      assertEquals(StopMode.NOW, intentOf(pid).get("mode"));
      Line exit = ledger.exitOf(pid);
      assertEquals(ExitClass.REQUESTED, exit.get("exitClass"));
      assertEquals(Optional.of(StopReason.TIMEOUT), exit.get("reason"));
      assertForcedWithOurCode(exit);
    }
  }

  @Test
  @Timeout(60)
  void childExitingMidRequestIsUnexpectedWithItsOwnCode() throws Exception {
    try (var sandbox = pool(Duration.ofSeconds(20), 500)) {
      assertThrows(SandboxExtractionException.class, () -> sandbox.extract(file("crash.txt")));
      long pid = (Long) ledger.of(LedgerRecordType.CHILD_START).getFirst().get("pid");

      assertTrue(ledger.of(LedgerRecordType.STOP_INTENT, pid).isEmpty(),
          "a child that ends itself is not stopped");
      Line exit = ledger.exitOf(pid);
      assertEquals(ExitClass.UNEXPECTED, exit.get("exitClass"));
      assertEquals(OptionalInt.of(3), exit.get("code"));
      assertEquals(Optional.empty(), exit.get("reason"));
      assertTrue(ledger.of(LedgerRecordType.CHILD_NOTE, pid).isEmpty(), "no oom on its stderr");
    }
  }

  @Test
  @Timeout(90)
  void heapExhaustedChildIsUnexpectedAndNotedOom() throws Exception {
    try (var sandbox = pool(Duration.ofSeconds(45), 500, "-Xmx64m")) {
      assertThrows(ContentExtractor.ExtractionException.class,
          () -> sandbox.extract(file("oom.txt")));
      long pid = (Long) ledger.of(LedgerRecordType.CHILD_START).getFirst().get("pid");

      assertTrue(ledger.of(LedgerRecordType.STOP_INTENT, pid).isEmpty());
      Line exit = ledger.exitOf(pid);
      assertEquals(ExitClass.UNEXPECTED, exit.get("exitClass"));
      assertEquals(OptionalInt.of(1), exit.get("code"));
      List<Line> notes = ledger.of(LedgerRecordType.CHILD_NOTE, pid);
      assertEquals(1, notes.size(), () -> "one note: " + ledger.lines);
      assertEquals("oom", notes.getFirst().get("token"));
    }
  }

  @Test
  @Timeout(60)
  void requestBudgetRecycleIsARequestedStopForRecycle() throws Exception {
    try (var sandbox = pool(Duration.ofSeconds(20), 2)) {
      sandbox.extract(file("ok-1.txt"));
      long first = sandbox.firstChildPid();
      sandbox.extract(file("ok-2.txt"));
      sandbox.extract(file("ok-3.txt"));

      Line intent = intentOf(first);
      assertEquals(StopReason.RECYCLE, intent.get("reason"));
      assertEquals(Optional.of("request_budget"), intent.get("detail"));
      Line exit = ledger.exitOf(first);
      assertEquals(ExitClass.REQUESTED, exit.get("exitClass"));
      assertEquals(Optional.of(StopReason.RECYCLE), exit.get("reason"));
      assertEquals(Optional.of("request_budget"), exit.get("detail"));
      assertForcedWithOurCode(exit);
    }
  }

  @Test
  @Timeout(60)
  void protocolViolationIsARequestedStopForRecycle() throws Exception {
    try (var sandbox = pool(Duration.ofSeconds(20), 500)) {
      assertThrows(SandboxExtractionException.class, () -> sandbox.extract(file("malformed.txt")));
      long pid = (Long) ledger.of(LedgerRecordType.CHILD_START).getFirst().get("pid");

      assertEquals(StopReason.RECYCLE, intentOf(pid).get("reason"));
      Line exit = ledger.exitOf(pid);
      assertEquals(ExitClass.REQUESTED, exit.get("exitClass"));
      assertEquals(Optional.of(StopReason.RECYCLE), exit.get("reason"));
      assertEquals(Optional.of("protocol"), exit.get("detail"));
      assertForcedWithOurCode(exit);
    }
  }

  @Test
  @Timeout(60)
  void interruptedCallerIsARequestedStopForCancelled() throws Exception {
    try (var sandbox = pool(Duration.ofSeconds(30), 500)) {
      Path hanging = file("hang.txt");
      CompletableFuture<Throwable> outcome = new CompletableFuture<>();
      Thread caller = Thread.ofPlatform().daemon().start(() -> {
        try {
          sandbox.extract(hanging);
          outcome.complete(null);
        } catch (Throwable failure) {
          outcome.complete(failure);
        }
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
      while (ledger.of(LedgerRecordType.CHILD_START).isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      Thread.sleep(500); // the request is on the pipe and the caller waits for its answer
      caller.interrupt();
      assertTrue(outcome.get(30, TimeUnit.SECONDS) instanceof InterruptedIOException,
          () -> "got " + outcome.join());
      long pid = (Long) ledger.of(LedgerRecordType.CHILD_START).getFirst().get("pid");

      assertEquals(StopReason.CANCELLED, intentOf(pid).get("reason"));
      Line exit = ledger.exitOf(pid);
      assertEquals(ExitClass.REQUESTED, exit.get("exitClass"));
      assertEquals(Optional.of(StopReason.CANCELLED), exit.get("reason"));
      assertForcedWithOurCode(exit);
    }
  }

  @Test
  @Timeout(60)
  void poolCloseIsARequestedCooperativeQuit() throws Exception {
    long pid;
    try (var sandbox = pool(Duration.ofSeconds(20), 500)) {
      sandbox.extract(file("ready.txt"));
      pid = sandbox.firstChildPid();
    }

    Line intent = intentOf(pid);
    assertEquals(StopReason.QUIT, intent.get("reason"));
    assertEquals(StopMode.COOPERATIVE, intent.get("mode"));
    Line exit = ledger.exitOf(pid);
    assertEquals(ExitClass.REQUESTED, exit.get("exitClass"));
    assertEquals(Optional.of(StopReason.QUIT), exit.get("reason"));
    assertEquals(OptionalInt.of(0), exit.get("code"), "EOF ends the serve loop cleanly");
    assertEquals(false, exit.get("forced"));
  }
}
