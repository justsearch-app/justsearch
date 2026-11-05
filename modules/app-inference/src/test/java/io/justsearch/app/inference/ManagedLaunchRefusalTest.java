/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Design D4 on the real lifecycle seam (llama-server is the HealthyFakeLlamaServer stand-in
 * process): an answer-server start refused because the answer model's files are being removed is
 * a refusal with its plain reason. It latches no chat failure, and once the removal has ended the
 * same start launches normally.
 */
final class ManagedLaunchRefusalTest {
  private static final String REMOVING = "Removing files - try again when it finishes";

  @TempDir Path tmp;
  private Path fakeDir;
  private InferenceLifecycleManager manager;

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (manager != null) manager.close();
    } finally {
      if (fakeDir != null) HealthyFakeLlamaServer.endAll(fakeDir);
    }
  }

  @Test
  void aStartRefusedWhileRemovingIsNotAStartFailureAndTheNextStartLaunches() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    manager = offlineManager();
    AtomicBoolean removing = new AtomicBoolean(true);
    ManagedLaunchFence fence = () -> {
      if (removing.get()) throw new IllegalStateException(REMOVING);
      return () -> {};
    };
    manager.setManagedLaunchFence(fence);

    ModeTransitionException refused =
        assertThrows(ModeTransitionException.class, () -> manager.switchToOnlineMode());

    assertTrue(String.valueOf(refused.getMessage()).contains(REMOVING), refused.getMessage());
    assertFalse(manager.isOnline());
    assertEquals(List.of(), HealthyFakeLlamaServer.launches(fakeDir), "nothing was launched");
    assertNull(manager.lastChatFailedCode(), "the refusal is not reported as a failed start");
    assertNull(manager.chatActuator().failureLatch(), "nor latched as one");

    removing.set(false);
    manager.switchToOnlineMode();

    assertTrue(manager.isOnline(), "after the removal the answer server starts normally");
    assertNull(manager.lastChatFailedCode());
    assertEquals(1, HealthyFakeLlamaServer.launches(fakeDir).size());
  }

  private InferenceLifecycleManager offlineManager() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir);
    Path model = tmp.resolve("model.gguf");
    Files.createFile(model);
    ResolvedConfig captured = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.context.size", "4096",
        "justsearch.data.dir", tmp.resolve("data").toString()));
    InferenceConfig configuration =
        new InferenceConfig(executable, model, null, freePort(), 4096, 0, false);
    return new InferenceLifecycleManager(
        new io.justsearch.core.execution.TestEngineExecutors(),
        configuration,
        NoopInferenceTelemetryEvents.INSTANCE,
        ManagedChildRegistry.noop(),
        captured);
  }

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
