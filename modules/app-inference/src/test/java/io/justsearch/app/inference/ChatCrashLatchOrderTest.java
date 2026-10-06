/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
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
 * Spec A step 7 on the real seam: a lifecycle manager runs llama-server as a separate stand-in
 * process (HealthyFakeLlamaServer), and the process is ended the way a crash ends it. The OFFLINE
 * landing of the crash is where the status projection learns why chat failed, so the classified
 * failure must already be latched when that landing is published.
 */
final class ChatCrashLatchOrderTest {
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

  /*
   * A crash moments after READY is a crash loop (Spec A T29). The listeners of its OFFLINE landing
   * read the manager's latched failure and see CRASH_LOOP; a tree that latches after the landing
   * shows them the latch from before it, and the projection only ever says "crashed".
   */
  @Test
  void aCrashIsLatchedBeforeItsOfflineLandingIsPublished() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    manager = onlineManager();
    List<String> seenAtLanding = new CopyOnWriteArrayList<>();
    manager.addModeTransitionListener((from, to, reason) -> {
      if (to == Mode.OFFLINE && reason == TransitionReason.CRASH_RECOVERY) {
        seenAtLanding.add(String.valueOf(manager.lastChatFailedCode()));
      }
    });

    HealthyFakeLlamaServer.crashLatest(fakeDir);

    assertTrue(await(() -> !seenAtLanding.isEmpty(), CRASH_SEEN_BOUND), "the crash lands OFFLINE");
    assertEquals(List.of("CRASH_LOOP"), seenAtLanding,
        "the landing's listeners see the classified failure, not the latch from before it");
  }

  private InferenceLifecycleManager onlineManager() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir);
    Path model = Files.createFile(tmp.resolve("model.gguf"));
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
