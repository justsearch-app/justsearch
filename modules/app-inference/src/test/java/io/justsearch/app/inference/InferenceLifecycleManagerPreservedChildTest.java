/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0051 decision 2 (scenario S14): an ordered restart preserves nothing. Closing the old
 * manager ends its managed child and retires its registry row, and the next manager launches and
 * registers its own server on a fresh port under the same declared configuration.
 */
final class InferenceLifecycleManagerPreservedChildTest {

  @Test
  @SuppressWarnings("removal") // states that the keep-running request is refused
  void orderedRestartEndsTheManagedChildAndTheNextManagerLaunchesItsOwn(@TempDir Path tempDir)
      throws Exception {
    ConfigStore previousStore = ConfigStore.globalOrNull();
    ResolvedConfig captured =
        TestResolvedConfigHelper.fromEntries(Map.of("justsearch.context.size", "4096", "justsearch.data.dir", tempDir.resolve("captured").toString()));
    // A contradictory ambient snapshot makes a fallback/global recapture fail the hash match.
    ConfigStore.setGlobal(
        new ConfigStore(
            TestResolvedConfigHelper.fromEntries(
                Map.of("justsearch.context.size", "8192", "justsearch.data.dir", tempDir.resolve("ambient").toString()))));

    InferenceLifecycleManager managerA = null;
    InferenceLifecycleManager managerB = null;
    try {
      Path executable = HealthyFakeLlamaServer.install(tempDir.resolve("fake-llama"));
      Path model = Files.createFile(tempDir.resolve("model.gguf"));
      InferenceConfig configuration =
          new InferenceConfig(executable, model, null, freePort(), 4096, 0, false);
      String declaredHash =
          ManagedLlamaConfigIdentity.declaredHash(configuration, captured, 0);

      RecordingRegistry registry = new RecordingRegistry();
      managerA = manager(configuration, captured, registry);
      managerA.switchToOnlineMode();
      assertTrue(managerA.isOnline());
      assertFalse(managerA.isUsingExternalLlamaServer());
      assertEquals(1, registry.registrationCount());
      ManagedChild first = registry.snapshot().getFirst();
      ProcessHandle firstProcess = ProcessHandle.of(first.pid()).orElseThrow();

      // The restart path used to ask the manager to leave its child running for the successor.
      managerA.setStopServerOnClose(false);
      managerA.close();
      managerA = null;

      assertTrue(
          awaitExit(firstProcess), "restart close must end the managed child, even when asked not to");
      assertTrue(registry.snapshot().isEmpty(), "restart close must retire its registry row");

      managerB = manager(configuration, captured, registry);
      InferenceLifecycleManager.ConfigApplyResult result =
          managerB.applyResolvedConfig(
              configuration, captured, InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS);

      assertEquals(InferenceLifecycleManager.ConfigApplyDisposition.APPLIED, result.disposition());
      assertNull(result.failure());
      assertEquals(declaredHash, result.declaredConfigHash());
      assertTrue(managerB.isOnline());
      assertFalse(managerB.isUsingExternalLlamaServer());
      assertEquals(
          2,
          registry.registrationCount(),
          "the next manager must launch and register its own server, never readopt");
      ManagedChild second = registry.snapshot().getFirst();
      assertNotEquals(first.pid(), second.pid());
      assertNotEquals(first.endpoint(), second.endpoint(), "each launch binds its own fresh port");
      assertEquals(first.declaredConfigHash(), second.declaredConfigHash());
      ProcessHandle secondProcess = ProcessHandle.of(second.pid()).orElseThrow();

      managerB.close();
      managerB = null;
      assertTrue(awaitExit(secondProcess), "ordinary close must stop the managed child");
      assertTrue(registry.snapshot().isEmpty(), "ordinary close must retire the managed-child row");
    } finally {
      try {
        if (managerB != null) managerB.close();
      } finally {
        try {
          if (managerA != null) managerA.close();
        } finally {
          HealthyFakeLlamaServer.endAll(tempDir.resolve("fake-llama"));
          TestResolvedConfigHelper.restoreGlobal(previousStore);
        }
      }
    }
  }

  private static InferenceLifecycleManager manager(
      InferenceConfig configuration, ResolvedConfig captured, ManagedChildRegistry registry) {
    return new InferenceLifecycleManager(
        new io.justsearch.core.execution.TestEngineExecutors(),
        configuration,
        NoopInferenceTelemetryEvents.INSTANCE,
        registry,
        captured);
  }

  /** True when {@code process} exits within 10 s. */
  private static boolean awaitExit(ProcessHandle process) throws Exception {
    try {
      process.onExit().get(10, TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException stillRunning) {
      return false;
    }
    return !process.isAlive();
  }

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static final class RecordingRegistry implements ManagedChildRegistry {
    private final List<ManagedChild> children = new ArrayList<>();
    private int registrations;

    @Override
    public synchronized List<ManagedChild> snapshot() {
      return List.copyOf(children);
    }

    @Override
    public synchronized void register(ManagedChild child) {
      registrations++;
      children.removeIf(existing -> existing.id().equals(child.id()));
      children.add(child);
    }

    @Override
    public synchronized void remove(String childId) {
      children.removeIf(child -> child.id().equals(childId));
    }

    synchronized int registrationCount() {
      return registrations;
    }
  }
}
