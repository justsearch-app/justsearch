/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedChildReconcilerTest {
  @TempDir Path dataDir;

  @Test
  void captureUsesTheOwnedProcessesObservedExecutable() throws Exception {
    Process child = sleepingJava();
    try {
      ManagedChild captured = ManagedChild.fromProcess(
          child, ManagedChild.Kind.EXTRACTION, "stdio", null, null, "argv");

      assertEquals(
          ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())),
          captured.executable());
      assertEquals(ManagedChild.IdentityMatch.MATCH, captured.identityOf(child.toHandle()));
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  @Test
  void missingObservedExecutableRefusesRegistration() {
    Process child = mock(Process.class);
    ProcessHandle.Info info = mock(ProcessHandle.Info.class);
    when(child.info()).thenReturn(info);
    when(info.startInstant()).thenReturn(Optional.of(Instant.now()));
    when(info.command()).thenReturn(Optional.empty());

    assertThrows(IllegalStateException.class, () -> ManagedChild.fromProcess(
        child, ManagedChild.Kind.EXTRACTION, "stdio", null, null, "argv"));
  }

  /**
   * S14 / ADR-0051 decision 2: a predecessor Engine's own llama-server ends with it. It is
   * terminated at boot even when it is healthy, answers /props and was launched with the very
   * configuration the new Engine would use; it is never kept for adoption.
   */
  @Test
  void verifiedOwnedHealthyCompatibleLlamaPredecessorIsTerminatedAtBoot() throws Exception {
    Process child = sleepingJava();
    try (HealthyLlamaStub stub = new HealthyLlamaStub()) {
      var registry = activeRegistry();
      registry.register(
          new ManagedChild(
              "managed-llama", ManagedChild.Kind.LLAMA_SERVER, child.pid(),
              child.info().startInstant().orElseThrow().toString(),
              ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())), stub.endpoint(), "model.gguf",
              "applied-A", "diagnostic-argv"));

      new ManagedChildReconciler(registry).reconcile();

      assertTrue(child.waitFor(Duration.ofSeconds(15)), "the predecessor's llama-server must have ended");
      assertTrue(registry.snapshot().isEmpty(), "the ended predecessor's record is released");
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  /** A llama-server record whose identity no longer matches is stale: the live process is not ours. */
  @Test
  void llamaRecordWithReusedPidLeavesTheLiveProcessUntouched() throws Exception {
    Process child = sleepingJava();
    try (HealthyLlamaStub stub = new HealthyLlamaStub()) {
      var registry = activeRegistry();
      registry.register(
          new ManagedChild(
              "reused-llama", ManagedChild.Kind.LLAMA_SERVER, child.pid(), Instant.EPOCH.toString(),
              ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())), stub.endpoint(), "model.gguf",
              "applied-A", "diagnostic-argv"));

      new ManagedChildReconciler(registry).reconcile();

      assertTrue(child.isAlive(), "an unrelated process holding a reused PID is never killed");
      assertTrue(registry.snapshot().isEmpty(), "proved stale record is removed");
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  @Test
  void registeredExtractionChildIsStoppedOnlyWhenAllIdentityAxesMatch() throws Exception {
    Process child = sleepingJava();
    try {
      var registry = activeRegistry();
      registry.register(record(child, ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())), child.info().startInstant().orElseThrow()));

      new ManagedChildReconciler(registry).reconcile();

      assertTrue(child.waitFor(Duration.ofSeconds(5)));
      assertTrue(registry.snapshot().isEmpty());
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void executableMismatchLeavesUnrelatedLiveProcessUntouched() throws Exception {
    Process child = sleepingJava();
    try {
      var registry = activeRegistry();
      registry.register(record(child, ManagedChild.normalizePath(Path.of("unrelated.exe")), child.info().startInstant().orElseThrow()));

      new ManagedChildReconciler(registry).reconcile();

      assertTrue(child.isAlive());
      assertTrue(registry.snapshot().isEmpty(), "proved stale record is removed");
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  @Test
  void reusedPidStartInstantLeavesLiveProcessUntouched() throws Exception {
    Process child = sleepingJava();
    try {
      var registry = activeRegistry();
      registry.register(record(child, ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())), Instant.EPOCH));

      new ManagedChildReconciler(registry).reconcile();

      assertTrue(child.isAlive());
      assertTrue(registry.snapshot().isEmpty());
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  @Test
  void failedTerminationOfMatchedProcessRetainsOwnership() throws Exception {
    Process child = sleepingJava();
    try {
      var registry = activeRegistry();
      ManagedChild record = record(
          child, ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())), child.info().startInstant().orElseThrow());
      registry.register(record);

      new ManagedChildReconciler(registry, ignored -> false).reconcile();

      assertTrue(child.isAlive());
      assertEquals(List.of(record), registry.snapshot());
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  /**
   * Spec B step 7: a predecessor's child that the children ledger records inside its Engine's job
   * (child-start contained true) ends with that job; one still alive is the ledger's to record
   * (child-survived), so the reconciler leaves it running and keeps its record, whatever its kind.
   * A matched child with no such record (an earlier version's) still takes the fallback and ends.
   */
  @Test
  void aChildTheLedgerRecordsInItsEngineJobIsLeftToItWhileAnUnrecordedOneIsEnded() throws Exception {
    Process llama = sleepingJava();
    Process extraction = sleepingJava();
    Process earlier = sleepingJava();
    try (HealthyLlamaStub stub = new HealthyLlamaStub()) {
      var registry = activeRegistry();
      ManagedChild llamaRecord = llamaRecord(llama, stub);
      ManagedChild extractionRecord = record(extraction,
          ManagedChild.normalizePath(Path.of(extraction.info().command().orElseThrow())),
          extraction.info().startInstant().orElseThrow());
      ManagedChild earlierRecord = record(earlier,
          ManagedChild.normalizePath(Path.of(earlier.info().command().orElseThrow())),
          earlier.info().startInstant().orElseThrow());
      registry.register(llamaRecord);
      registry.register(extractionRecord);
      registry.register(earlierRecord);
      recordContainedStart(llama);
      recordContainedStart(extraction);

      reconcilerFor(registry, true).reconcile();

      assertTrue(earlier.waitFor(Duration.ofSeconds(15)), "a child with no containment record ends");
      assertFalse(llama.waitFor(Duration.ofMillis(500)), "a contained llama-server is not ended");
      assertFalse(extraction.waitFor(Duration.ofMillis(500)), "a contained extraction child is not ended");
      assertEquals(List.of(llamaRecord, extractionRecord), registry.snapshot(),
          "the contained survivors' records are kept; the ended child's is released");
    } finally {
      for (Process p : List.of(llama, extraction, earlier)) p.destroyForcibly();
      for (Process p : List.of(llama, extraction, earlier)) p.waitFor(Duration.ofSeconds(5));
    }
  }

  /**
   * Spec F step 13 fallback, kept until one release after the Engine job: while this Engine has no
   * job (containment unavailable) an identity-matched orphan llama-server is still ended at boot,
   * even when the ledger says it was contained, and its record released.
   */
  @Test
  void withoutContainmentAnIdentityMatchedOrphanLlamaIsStillEndedAtBoot() throws Exception {
    Process llama = sleepingJava();
    try (HealthyLlamaStub stub = new HealthyLlamaStub()) {
      var registry = activeRegistry();
      registry.register(llamaRecord(llama, stub));
      recordContainedStart(llama);

      reconcilerFor(registry, false).reconcile();

      assertTrue(llama.waitFor(Duration.ofSeconds(15)), "the orphan llama-server must have ended");
      assertTrue(registry.snapshot().isEmpty(), "the ended orphan's record is released");
    } finally {
      llama.destroyForcibly();
      llama.waitFor(Duration.ofSeconds(5));
    }
  }

  /**
   * The reconciler HeadlessApp builds: told by the children ledger which predecessors were in their
   * Engine's job when the build has that seam, otherwise the build's only reconciler.
   */
  private ManagedChildReconciler reconcilerFor(ManagedChildRegistry registry, boolean engineContained)
      throws ReflectiveOperationException {
    for (Method method : ManagedChildReconciler.class.getMethods()) {
      if (!method.getName().equals("fromChildrenLedger")) continue;
      Object contained = method.invoke(null, dataDir, engineContained);
      return (ManagedChildReconciler) ManagedChildReconciler.class
          .getMethod("withContainment", ManagedChildRegistry.class, method.getReturnType())
          .invoke(null, registry, contained);
    }
    return new ManagedChildReconciler(registry);
  }

  /** The predecessor Engine's child-start for {@code process}, inside its job. */
  private void recordContainedStart(Process process) {
    Instant start = process.info().startInstant().orElseThrow();
    // The OS birth carries sub-millisecond ticks the manifest's start instant truncates.
    String birth = Long.toString((start.toEpochMilli() + 11_644_473_600_000L) * 10_000L + 1234L);
    ExitLedgerWriter writer = ExitLedgerWriter.rotating(LedgerFamily.directory(dataDir),
        LedgerFamily.CHILDREN, new LedgerWriterId(LedgerWriterId.Role.ENGINE, 4242L, Instant.parse("2026-10-06T09:00:00Z")),
        Clock.systemUTC());
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("childId", "c-" + process.pid());
    fields.put("pid", process.pid());
    fields.put("processStartedAt", start);
    fields.put("creationFileTime", birth);
    fields.put("contained", true);
    assertTrue(writer.append(LedgerRecordType.CHILD_START, fields));
  }

  private static ManagedChild llamaRecord(Process llama, HealthyLlamaStub stub) {
    return new ManagedChild(
        "managed-llama-" + llama.pid(), ManagedChild.Kind.LLAMA_SERVER, llama.pid(),
        llama.info().startInstant().orElseThrow().toString(),
        ManagedChild.normalizePath(Path.of(llama.info().command().orElseThrow())),
        stub.endpoint(), "model.gguf", "applied-A", "diagnostic-argv");
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

  /** Answers every request with 200 and a /props-shaped body, like a healthy llama-server. */
  private static final class HealthyLlamaStub implements AutoCloseable {
    private final java.net.ServerSocket server;
    private final Thread thread;

    HealthyLlamaStub() throws java.io.IOException {
      server = new java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
      thread = Thread.ofVirtual().start(this::serve);
    }

    String endpoint() {
      return "http://127.0.0.1:" + server.getLocalPort();
    }

    private void serve() {
      while (!server.isClosed()) {
        try (java.net.Socket socket = server.accept()) {
          var reader = new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
          while (true) {
            String line = reader.readLine();
            if (line == null || line.isEmpty()) break;
          }
          byte[] body = "{\"model_alias\":\"managed-test\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
              + body.length + "\r\nConnection: close\r\n\r\n";
          socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
          socket.getOutputStream().write(body);
        } catch (java.io.IOException closed) {
          if (!server.isClosed()) throw new java.io.UncheckedIOException(closed);
        }
      }
    }

    @Override public void close() throws Exception {
      server.close();
      thread.join(Duration.ofSeconds(2));
    }
  }
}
