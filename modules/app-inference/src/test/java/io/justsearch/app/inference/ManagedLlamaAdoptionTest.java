/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class ManagedLlamaAdoptionTest {
  @Test
  void failedRegistrationRollbackRetainsHandleForRootCleanup(@TempDir Path tmp) throws Exception {
    RetryKillProcess process = new RetryKillProcess();
    InferenceConfig config =
        new InferenceConfig(tmp.resolve("llama.exe"), tmp.resolve("model.gguf"), null, 1, 1, 0, false);
    LlamaServerOps ops =
        new LlamaServerOps(
            HttpClient.newHttpClient(), new ObjectMapper(), () -> config, null, () -> Mode.OFFLINE,
            new NoopPropsObserver(), () -> {}, ignored -> {},
            NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop());
    try {
      ops.rollbackFailedRegistration(process, new IOException("disk full"));
      assertTrue(process.isAlive());
      assertEquals(1, process.destroyCalls);

      ops.stopLlamaServer();
      assertFalse(process.isAlive());
      assertEquals(2, process.destroyCalls);
    } finally {
      ops.shutdown();
    }
  }

  @Test
  void repeatedRollbackTerminationFailureMakesCloseFailAndRetainsCleanup(@TempDir Path tmp) {
    RetryKillProcess process = new RetryKillProcess();
    process.killAfter = Integer.MAX_VALUE;
    InferenceConfig config =
        new InferenceConfig(tmp.resolve("llama.exe"), tmp.resolve("model.gguf"), null, 1, 1, 0, false);
    LlamaServerOps ops = new LlamaServerOps(
        HttpClient.newHttpClient(), new ObjectMapper(), () -> config, null, () -> Mode.OFFLINE,
        new NoopPropsObserver(), () -> {}, ignored -> {},
        NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop());
    try {
      ops.rollbackFailedRegistration(process, new IOException("disk full"));
      assertThrows(IllegalStateException.class, ops::stopLlamaServer);
      assertTrue(process.isAlive());
      assertEquals(2, process.destroyCalls);
      var refused = assertThrows(IllegalStateException.class, ops::startLlamaServer);
      assertTrue(refused.getMessage().contains("survived terminal cleanup"));
      assertEquals(3, process.destroyCalls);
      assertThrows(IllegalStateException.class, ops::closeUnregisteredChild);
      assertTrue(process.isAlive());
      process.killAfter = process.destroyCalls + 1;
      ops.closeUnregisteredChild();
      assertFalse(process.isAlive());
    } finally {
      process.killAfter = 0;
      ops.closeUnregisteredChild();
      ops.shutdown();
    }
  }

  @Test
  void terminalCloseRetiresDeadAdoptedChildEvenWhenExitCallbackWasCancelled(@TempDir Path tmp)
      throws Exception {
    try (Fixture fixture = new Fixture(tmp)) {
      assertTrue(fixture.ops.adoptManagedServerIfPresent("declared"));
      // Stop the actual monitor before death, guaranteeing its callback cannot remove the record.
      fixture.ops.shutdown();
      fixture.child.destroyForcibly();
      assertTrue(fixture.child.waitFor(5, TimeUnit.SECONDS));
      assertEquals(1, fixture.registry.snapshot().size());
      fixture.ops.stopLlamaServer();
      assertTrue(fixture.registry.snapshot().isEmpty());
    }
  }

  @Test
  void deadBetweenReconciliationAndAdoptionIsRemoved(@TempDir Path tmp) throws Exception {
    try (Fixture fixture = new Fixture(tmp)) {
      fixture.child.destroyForcibly();
      assertTrue(fixture.child.waitFor(5, TimeUnit.SECONDS));

      assertFalse(fixture.ops.adoptManagedServerIfPresent("declared"));
      assertTrue(fixture.registry.snapshot().isEmpty());
    }
  }

  @Test
  void adoptedManagedDeathRetiresOnlyItsRecord(@TempDir Path tmp) throws Exception {
    AtomicInteger recoveries = new AtomicInteger();
    try (Fixture fixture = new Fixture(tmp, null, recoveries::incrementAndGet)) {
      assertTrue(fixture.ops.adoptManagedServerIfPresent("declared"));
      assertTrue(
          fixture.ops.hasLiveManagedProcess(),
          "the periodic health path must treat the adopted handle as managed and probe it");
      ManagedChild replacement =
          new ManagedChild(
              "replacement", ManagedChild.Kind.EXTRACTION, ProcessHandle.current().pid(),
              ProcessHandle.current().info().startInstant().orElseThrow().toString(),
              ManagedChild.normalizePath(
                  Path.of(ProcessHandle.current().info().command().orElseThrow())),
              "stdio", null, null, "argv");
      fixture.registry.register(replacement);
      fixture.mode.set(Mode.ONLINE);

      fixture.child.destroyForcibly();
      assertTrue(fixture.child.waitFor(5, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while ((fixture.registry.snapshot().stream().anyMatch(c -> c.id().equals("managed"))
              || recoveries.get() == 0)
          && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }

      assertFalse(fixture.registry.snapshot().stream().anyMatch(c -> c.id().equals("managed")));
      assertTrue(fixture.registry.snapshot().stream().anyMatch(c -> c.id().equals("replacement")));
      assertEquals(1, recoveries.get());
    }
  }

  @Test
  void failedMatchedTerminationBlocksActivationAndRetainsOwnership(@TempDir Path tmp)
      throws Exception {
    try (Fixture fixture = new Fixture(tmp, ignored -> false)) {
      assertThrows(
          IOException.class, () -> fixture.ops.adoptManagedServerIfPresent("different-config"));
      assertTrue(fixture.child.isAlive());
      assertTrue(fixture.registry.snapshot().stream().anyMatch(c -> c.id().equals("managed")));
    }
  }

  @Test
  void configurationMismatchTerminatesMatchedChildBeforeActivation(@TempDir Path tmp)
      throws Exception {
    try (Fixture fixture = new Fixture(tmp)) {
      assertFalse(fixture.ops.adoptManagedServerIfPresent("different-config"));
      assertTrue(fixture.child.waitFor(5, TimeUnit.SECONDS));
      assertTrue(fixture.registry.snapshot().isEmpty());
    }
  }

  @Test
  void adoptedManagedHangReachesManagedCrashRecovery(@TempDir Path tmp) throws Exception {
    AtomicInteger recoveries = new AtomicInteger();
    try (Fixture fixture = new Fixture(tmp, null, recoveries::incrementAndGet)) {
      assertTrue(fixture.ops.adoptManagedServerIfPresent("declared"));
      fixture.mode.set(Mode.ONLINE);
      fixture.healthy.set(false);

      fixture.ops.runPeriodicHealthCheck();
      fixture.ops.runPeriodicHealthCheck();
      fixture.ops.runPeriodicHealthCheck();

      assertEquals(1, recoveries.get());
      assertTrue(fixture.child.isAlive());
      assertTrue(fixture.registry.snapshot().stream().anyMatch(c -> c.id().equals("managed")));
    }
  }

  private static final class Fixture implements AutoCloseable {
    private final Registry registry = new Registry();
    private final Process child;
    private final HttpServer server;
    private final LlamaServerOps ops;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OFFLINE);
    private final AtomicBoolean healthy = new AtomicBoolean(true);

    Fixture(Path tmp) throws Exception {
      this(tmp, null, null);
    }

    Fixture(Path tmp, LlamaServerOps.ManagedHandleTermination termination) throws Exception {
      this(tmp, termination, null);
    }

    Fixture(
        Path tmp,
        LlamaServerOps.ManagedHandleTermination termination,
        Runnable managedCrashHandler)
        throws Exception {
      child =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin",
                      System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                      .toString(),
                  "-cp", System.getProperty("java.class.path"), SleepingChild.class.getName())
              .start();
      Path executable = Path.of(child.info().command().orElseThrow());
      Path model = Files.createFile(tmp.resolve("model.gguf"));
      server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/health",
          exchange -> {
            exchange.sendResponseHeaders(healthy.get() ? 200 : 503, -1);
            exchange.close();
          });
      server.createContext(
          "/props",
          exchange -> {
            byte[] body =
                "{\"model_path\":\"model.gguf\",\"n_ctx\":4096}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      int port = server.getAddress().getPort();
      InferenceConfig config = new InferenceConfig(executable, model, null, port, 4096, 0, false);
      registry.register(
          new ManagedChild(
              "managed", ManagedChild.Kind.LLAMA_SERVER, child.pid(),
              child.info().startInstant().orElseThrow().toString(),
              ManagedChild.normalizePath(executable), "http://127.0.0.1:" + port,
              model.toString(), "declared", "diagnostic-argv"));
      ops =
          termination == null && managedCrashHandler == null
              ? new LlamaServerOps(
                  HttpClient.newHttpClient(), new ObjectMapper(), () -> config, null, mode::get,
                  new NoopPropsObserver(), () -> {}, ignored -> {},
                  NoopInferenceTelemetryEvents.INSTANCE, registry)
              : new LlamaServerOps(
                  HttpClient.newHttpClient(), new ObjectMapper(), () -> config, null, mode::get,
                  new NoopPropsObserver(), () -> {}, ignored -> {},
                  NoopInferenceTelemetryEvents.INSTANCE, registry,
                  termination == null ? handle -> true : termination, managedCrashHandler);
    }

    @Override
    public void close() {
      ops.stopLlamaServer();
      ops.shutdown();
      child.destroyForcibly();
      server.stop(0);
    }
  }

  private static final class Registry implements ManagedChildRegistry {
    private final List<ManagedChild> children = new ArrayList<>();

    @Override
    public synchronized List<ManagedChild> snapshot() {
      return List.copyOf(children);
    }

    @Override
    public synchronized void register(ManagedChild child) {
      children.removeIf(existing -> existing.id().equals(child.id()));
      children.add(child);
    }

    @Override
    public synchronized void remove(String childId) {
      children.removeIf(child -> child.id().equals(childId));
    }
  }

  private static final class NoopPropsObserver implements PropsObserver {
    @Override public void onModelIdObserved(String modelId) {}
    @Override public void onContextTokensObserved(int contextTokens) {}
    @Override public String observedModelId() { return null; }
    @Override public Integer observedContextTokens() { return null; }
  }

  public static final class SleepingChild {
    public static void main(String[] args) throws InterruptedException {
      Thread.sleep(30_000);
    }
  }

  private static final class RetryKillProcess extends Process {
    private boolean alive = true;
    private int destroyCalls;
    private int killAfter = 2;

    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public int waitFor() { alive = false; return 0; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
    @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
    @Override public void destroy() { destroyForcibly(); }
    @Override public Process destroyForcibly() {
      destroyCalls++;
      if (destroyCalls >= killAfter) alive = false;
      return this;
    }
    @Override public boolean isAlive() { return alive; }
    @Override public long pid() { return 424242L; }
  }
}
