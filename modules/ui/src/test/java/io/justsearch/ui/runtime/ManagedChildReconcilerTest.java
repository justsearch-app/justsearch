/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ManagedChildReconcilerTest {
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

  @Test
  void matchingManagedLlamaIdentityHealthPropsAndConfigAreRetainedForAdoption() throws Exception {
    Process child = sleepingJava();
    try (HealthyLlamaStub stub = new HealthyLlamaStub()) {
      var registry = activeRegistry();
      ManagedChild record =
          new ManagedChild(
              "managed-llama", ManagedChild.Kind.LLAMA_SERVER, child.pid(),
              child.info().startInstant().orElseThrow().toString(),
              ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())), stub.endpoint(), "model.gguf",
              "applied-A", "diagnostic-argv");
      registry.register(record);

      try (var processExecutors = new TestEngineExecutors();
          var reconciler = new ManagedChildReconciler(processExecutors, registry, "applied-A")) {
        reconciler.reconcile();
      }

      assertTrue(child.isAlive());
      assertEquals(List.of(record), registry.snapshot());
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  @Test
  void matchingManagedLlamaWithDifferentAppliedConfigIsStopped() throws Exception {
    Process child = sleepingJava();
    try (HealthyLlamaStub stub = new HealthyLlamaStub()) {
      var registry = activeRegistry();
      registry.register(
          new ManagedChild(
              "stale-config", ManagedChild.Kind.LLAMA_SERVER, child.pid(),
              child.info().startInstant().orElseThrow().toString(),
              ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())), stub.endpoint(), "model.gguf",
              "old-B", "diagnostic-argv"));

      try (var processExecutors = new TestEngineExecutors();
          var reconciler = new ManagedChildReconciler(processExecutors, registry, "applied-A")) {
        reconciler.reconcile();
      }

      assertTrue(child.waitFor(Duration.ofSeconds(5)));
      assertTrue(registry.snapshot().isEmpty());
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void registeredExtractionChildIsStoppedOnlyWhenAllIdentityAxesMatch() throws Exception {
    Process child = sleepingJava();
    try {
      var registry = activeRegistry();
      registry.register(record(child, ManagedChild.normalizePath(Path.of(child.info().command().orElseThrow())), child.info().startInstant().orElseThrow()));

      try (var processExecutors = new TestEngineExecutors();
          var reconciler = new ManagedChildReconciler(processExecutors, registry, null)) {
        reconciler.reconcile();
      }

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

      try (var processExecutors = new TestEngineExecutors();
          var reconciler = new ManagedChildReconciler(processExecutors, registry, null)) {
        reconciler.reconcile();
      }

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

      try (var processExecutors = new TestEngineExecutors();
          var reconciler = new ManagedChildReconciler(processExecutors, registry, null)) {
        reconciler.reconcile();
      }

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

      try (var processExecutors = new TestEngineExecutors();
          var reconciler =
              new ManagedChildReconciler(processExecutors, registry, null, ignored -> false)) {
        reconciler.reconcile();
      }

      assertTrue(child.isAlive());
      assertEquals(List.of(record), registry.snapshot());
    } finally {
      child.destroyForcibly();
      child.waitFor(Duration.ofSeconds(5));
    }
  }

  @Test
  void closeReleasesOwnedRegistrationWithoutClosingProcessRegistry() throws Exception {
    var processExecutors = spy(new TestEngineExecutors());
    java.util.concurrent.ExecutorService httpExecutor;
    try (var reconciler = new ManagedChildReconciler(processExecutors, activeRegistry(), null)) {
      var httpField = ManagedChildReconciler.class.getDeclaredField("http");
      httpField.setAccessible(true);
      var http = (java.net.http.HttpClient) httpField.get(reconciler);
      httpExecutor = (java.util.concurrent.ExecutorService) http.executor().orElseThrow();
      assertTrue(!httpExecutor.isShutdown());
    }
    assertTrue(httpExecutor.isShutdown(), "close must retire the owned HTTP executor");
    verify(processExecutors, never()).close();
    processExecutors.close();
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
