/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.LedgerReconciler;
import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import io.justsearch.app.api.runtime.child.TerminateResult;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.api.runtime.child.VramProbe;
import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.ExitLedgerWriter;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.app.api.runtime.ledger.LedgerWriterId;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S15: the boot fallback owns its stop intent and final exit observation. */
final class ManagedChildBootFallbackTest {
  @TempDir Path dataDir;

  @Test
  void bootFallbackRecordsIntentAndFinalExitAndNextBootAddsNothing() throws Exception {
    Process child = sleepingJava();
    try {
      var registry = activeRegistry();
      ManagedChild entry = record(child,
          ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())),
          child.info().startInstant().orElseThrow());
      registry.register(entry);
      seedUncontainedPredecessor(child);
      try (var control = DefaultChildControl.openForEngine(dataDir, "successor", registry,
          VramProbe.none(), EngineContainment.current())) {
        assertEquals(List.of("owner_died_alive"), childRecords(LedgerRecordType.CHILD_SURVIVED)
            .stream().map(r -> r.text("basis").orElseThrow()).toList());
        recordedBootReconciler(registry, control).reconcile();
        assertTrue(child.waitFor(Duration.ofSeconds(15)));
        var intents = childRecords(LedgerRecordType.STOP_INTENT);
        assertEquals(1, intents.size(), "the owner that kills the survivor records its intent");
        var exits = childRecords(LedgerRecordType.CHILD_EXIT);
        assertEquals(1, exits.size(), "the fallback must record the observed final exit");
        assertEquals(Optional.of("CRASH_RECOVERY"), intents.getFirst().text("reason"));
        assertEquals(intents.getFirst().text("intentId"), exits.getFirst().text("intentId"));
        assertEquals(Optional.of("CRASH_RECOVERY"), exits.getFirst().text("reason"));
        assertEquals(Optional.of("REQUESTED"), exits.getFirst().text("exitClass"));
        assertEquals(ProcessHandle.current().pid(), exits.getFirst().writer().pid());
        assertTrue(exits.getFirst().field("forced").asBoolean());
        assertEquals(ProcessTerminator.forCurrentOs().ourCode(), exits.getFirst().field("code").asInt());
        var all = ExitLedgerReader.read(LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records();
        assertTrue(all.indexOf(intents.getFirst()) < all.indexOf(exits.getFirst()));
        assertTrue(registry.snapshot().isEmpty());
      }
      int count = ExitLedgerReader.read(LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records().size();
      try (var next = DefaultChildControl.openForEngine(dataDir, "next-boot", registry,
          VramProbe.none(), EngineContainment.current())) {
        recordedBootReconciler(registry, next).reconcile();
      }
      assertEquals(count,
          ExitLedgerReader.read(LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records().size(),
          "a subsequent boot adds no ending or intent");
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  @Test
  void bootFallbackAlreadyGoneRecordsUnknownFactsWithoutInventingATime() throws Exception {
    Process child = sleepingJava();
    try {
      var registry = activeRegistry();
      registry.register(record(child, ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())),
          child.info().startInstant().orElseThrow()));
      seedUncontainedPredecessor(child);
      try (var control = DefaultChildControl.openForEngine(dataDir, "successor", registry,
          VramProbe.none(), EngineContainment.current())) {
        child.destroyForcibly();
        assertTrue(child.waitFor(Duration.ofSeconds(5)));
        recordedBootReconciler(registry, control).reconcile();
        var exits = childRecords(LedgerRecordType.CHILD_EXIT);
        assertEquals(1, exits.size(), "a recorded survivor that disappears still needs its final observation");
        assertEquals(Optional.of("UNKNOWN"), exits.getFirst().text("exitClass"));
        assertTrue(exits.getFirst().field("code").isNull());
        assertTrue(exits.getFirst().field("exitedAt").isNull(), "the time of observation is not an exit time");
        assertEquals(Optional.of("NOT_CALLED"), exits.getFirst().text("terminateResult"));
        assertFalse(exits.getFirst().field("forced").asBoolean());
      }
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  private List<io.justsearch.app.api.runtime.ledger.LedgerRecord> childRecords(LedgerRecordType type)
      throws java.io.IOException {
    return ExitLedgerReader.read(LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN).records()
        .stream().filter(r -> r.type() == type && r.text("childId").filter("c-orphan"::equals).isPresent()).toList();
  }

  @Test
  void bootFallbackFailedKillKeepsTheSurvivorAndRecordsNoFictitiousExit() throws Exception {
    scriptedFallback(true);
  }

  @Test
  void bootFallbackCrashDuringStopStaysUnexpectedAndUnknownTimeStaysUnknown() throws Exception {
    scriptedFallback(false);
  }

  private void scriptedFallback(boolean failKill) throws Exception {
    Process child = sleepingJava();
    try {
      var registry = activeRegistry();
      registry.register(record(child, ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())),
          child.info().startInstant().orElseThrow()));
      seedUncontainedPredecessor(child);
      ProcessTerminator real = ProcessTerminator.forCurrentOs();
      var writer = ExitLedgerWriter.rotating(LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN,
          LedgerWriterId.currentProcess(LedgerWriterId.Role.ENGINE, Instant.now()), Clock.systemUTC());
      new LedgerReconciler(LedgerFamily.directory(dataDir), LedgerSink.of(writer),
          LedgerReconciler.osIdentity(real)).reconcile();
      ProcessTerminator scripted = new ProcessTerminator() {
        @Override public OsProcess open(Process process) throws java.io.IOException { return real.open(process); }
        // Extra overload compiles on the baseline, where the boot path does not use it.
        public OsProcess open(ProcessHandle process) throws java.io.IOException {
          OsProcess held = real.open(child);
          return new OsProcess() {
            @Override public Optional<String> creationFileTime() { return held.creationFileTime(); }
            @Override public TerminateResult terminate(int code) {
              try {
                assertEquals(1, childRecords(LedgerRecordType.STOP_INTENT).size(), "intent precedes the kill");
                if (failKill) return TerminateResult.failed(5);
                child.destroyForcibly(); // a different actor wins the stop race, with its own code
                assertTrue(child.waitFor(Duration.ofSeconds(5)));
                return TerminateResult.ALREADY_EXITED;
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            }
            @Override public boolean awaitExit(Duration max) throws InterruptedException {
              return failKill ? false : held.awaitExit(max);
            }
            @Override public OptionalInt exitCode() { return held.exitCode(); }
            @Override public Optional<Instant> exitedAt() { return Optional.empty(); }
            @Override public void close() { held.close(); }
          };
        }
        @Override public int ourCode() { return real.ourCode(); }
        @Override public boolean codeDistinctive() { return real.codeDistinctive(); }
        @Override public Optional<String> creationFileTime(long pid) { return real.creationFileTime(pid); }
      };
      try (var control = DefaultChildControl.of(LedgerSink.of(writer), scripted, registry)) {
        recordedBootReconciler(registry, control).reconcile();
        assertEquals(1, childRecords(LedgerRecordType.STOP_INTENT).size());
        if (failKill) {
          assertTrue(child.isAlive(), "failed termination is not a final exit");
          assertFalse(registry.snapshot().isEmpty());
          assertTrue(childRecords(LedgerRecordType.CHILD_EXIT).isEmpty());
          assertEquals(Optional.of("terminate_timeout"),
              childRecords(LedgerRecordType.CHILD_SURVIVED).getLast().text("basis"));
        } else {
          var exit = childRecords(LedgerRecordType.CHILD_EXIT).getFirst();
          assertEquals(Optional.of("UNEXPECTED"), exit.text("exitClass"));
          assertTrue(exit.field("exitedAt").isNull());
          assertFalse(exit.field("forced").asBoolean());
          assertEquals(Optional.of("ALREADY_EXITED"), exit.text("terminateResult"));
        }
      }
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  private void seedUncontainedPredecessor(Process child) {
    var writer = ExitLedgerWriter.rotating(LedgerFamily.directory(dataDir), LedgerFamily.CHILDREN,
        new LedgerWriterId(LedgerWriterId.Role.ENGINE, 4242L, Instant.parse("2026-10-06T09:00:00Z")), Clock.systemUTC());
    assertTrue(writer.append(LedgerRecordType.CHILD_SPAWN, Map.of("childId", "c-orphan", "kind", "EXTRACTION",
        "label", "predecessor", "gpu", false, "owner", Map.of("pid", 4242L,
            "creationFileTime", "134358000000000000", "instanceId", "predecessor"))));
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", "c-orphan");
    fields.put("pid", child.pid());
    fields.put("processStartedAt", child.info().startInstant().orElseThrow());
    fields.put("creationFileTime", ProcessTerminator.forCurrentOs().creationFileTime(child.pid()));
    fields.put("executable", child.info().command().orElseThrow());
    fields.put("argvHash", "a".repeat(64));
    fields.put("contained", false);
    fields.put("gpuDevice", null);
    assertTrue(writer.append(LedgerRecordType.CHILD_START, fields));
  }

  /** The actual boot factory where present; baseline still kills, so its missing records fail assertions. */
  private ManagedChildReconciler recordedBootReconciler(ManagedChildRegistry registry, DefaultChildControl control)
      throws ReflectiveOperationException {
    for (Method method : ManagedChildReconciler.class.getMethods()) {
      if (method.getName().equals("withRecordedFallback")) {
        return (ManagedChildReconciler) method.invoke(null, registry,
            ManagedChildReconciler.fromChildrenLedger(dataDir, false), control, dataDir);
      }
    }
    return ManagedChildReconciler.withContainment(registry,
        ManagedChildReconciler.fromChildrenLedger(dataDir, false));
  }

  private static MutableManagedChildRegistry activeRegistry() {
    var registry = new MutableManagedChildRegistry();
    registry.seed(List.of());
    registry.installWriter(ignored -> {});
    return registry;
  }

  private static ManagedChild record(Process process, String executable, Instant start) {
    return new ManagedChild(
        "child-" + process.pid(), ManagedChild.Kind.EXTRACTION, process.pid(), start.toString(),
        executable, "stdio", null, null, "argv");
  }

  private static Process sleepingJava() throws Exception {
    return System.getProperty("os.name", "").startsWith("Windows")
        ? new ProcessBuilder(sleeperExecutable().toString(), "-NoProfile", "-Command", "Start-Sleep -Seconds 30").start()
        : new ProcessBuilder(sleeperExecutable().toString(), "30").start();
  }

  private static Path sleeperExecutable() {
    return System.getProperty("os.name", "").startsWith("Windows")
        ? Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        : Path.of("/bin/sleep");
  }

}
