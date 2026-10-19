/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.chat.FailedCode;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Spec A step 7 on the real seam: llama-server runs as a separate stand-in process
 * (HealthyFakeLlamaServer). The status projection reads the manager's failure at an OFFLINE landing
 * to say why chat failed. That failure must belong to the attempt that landed: an activation refused
 * before it starts a server did not fail with the code an earlier crash latched.
 */
final class ChatFailureScopeTest {
  private static final Duration CRASH_SEEN_BOUND = Duration.ofSeconds(8);

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
  void aRefusedActivationDoesNotShowAnEarlierFailureAsItsOwn() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    Path model = tmp.resolve("model.gguf");
    manager = onlineManager(model);
    HealthyFakeLlamaServer.crashLatest(fakeDir);
    assertTrue(await(() -> manager.lastChatFailedCode() == FailedCode.CRASH_LOOP
            && manager.getCurrentMode() == Mode.OFFLINE, CRASH_SEEN_BOUND),
        "the crash lands OFFLINE, latched as a crash loop");

    List<String> seenAtLanding = new CopyOnWriteArrayList<>();
    manager.addModeTransitionListener((from, to, reason) -> {
      if (to == Mode.OFFLINE && reason == TransitionReason.USER_SWITCH) {
        seenAtLanding.add(String.valueOf(manager.lastChatFailedCode()));
      }
    });
    // The model is gone: the next activation is refused before any server starts.
    Files.delete(model);

    assertThrows(ModeTransitionException.class, () -> manager.switchToOnlineMode());

    assertEquals(List.of("null"), seenAtLanding,
        "the refused activation's landing does not show the crash loop as its failure");
    assertEquals(null, manager.lastChatFailedCode(), "nor does the status read afterwards");
    assertEquals(FailedCode.CRASH_LOOP, manager.chatActuator().failureLatch(),
        "the latch itself stays until an activation, an operator retry or a configuration change");
  }

  private InferenceLifecycleManager onlineManager(Path model) throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir);
    Files.createFile(model);
    ResolvedConfig captured = TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.context.size", "4096",
        "justsearch.data.dir", tmp.resolve("data").toString()));
    InferenceConfig configuration =
        new InferenceConfig(executable, model, null, freePort(), 4096, 0, false);
    InferenceLifecycleManager created = new InferenceLifecycleManager(
        new io.justsearch.core.execution.TestEngineExecutors(),
        configuration,
        NoopInferenceTelemetryEvents.INSTANCE,
        ManagedChildRegistry.noop(),
        captured);
    created.switchToOnlineMode();
    assertTrue(created.isOnline(), "the manager starts online");
    return created;
  }

  private static boolean await(BooleanSupplier condition, Duration bound)
      throws InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return true;
      TimeUnit.MILLISECONDS.sleep(50);
    }
    return condition.getAsBoolean();
  }

  private static int freePort() throws java.io.IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }
}
