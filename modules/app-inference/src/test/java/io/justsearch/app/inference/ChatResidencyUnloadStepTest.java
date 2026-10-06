/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.GenerationTracker;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Spec A step 9 on the real seam: llama-server runs as a separate stand-in process
 * (HealthyFakeLlamaServer). The residency unload step stops an idle server, records why (the status
 * reads it as UNLOADED), and changes nothing while a reply is in progress or once the pressure has
 * cleared.
 */
final class ChatResidencyUnloadStepTest {

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
  void anIdleServerStopsAndChatReadsUnloadedUntilItsNextStart() throws Exception {
    manager = onlineManager();
    List<String> landings = new CopyOnWriteArrayList<>();
    manager.addModeTransitionListener((from, to, reason) -> {
      if (to != Mode.TRANSITIONING) {
        landings.add(to + ":" + reason + ":" + manager.residencyUnloadDetail());
      }
    });

    assertEquals(ChatActuator.ResidencyUnload.UNLOADED, run(StopDetail.GPU_BUSY, true));

    assertEquals(Mode.INDEXING, manager.getCurrentMode());
    assertEquals(StopDetail.GPU_BUSY, manager.residencyUnloadDetail());
    assertTrue(landings.contains("INDEXING:RESIDENCY_UNLOAD:GPU_BUSY"),
        "the landing carries the unload's reason and detail: " + landings);

    manager.switchToOnlineMode();
    assertTrue(manager.isOnline());
    assertNull(manager.residencyUnloadDetail(), "a start clears the unload's record");
  }

  @Test
  void aReplyInProgressRefusesTheUnloadAndNothingChanges() throws Exception {
    manager = onlineManager();
    var tracker = (GenerationTracker) manager.chatActuator().generationActivity();
    try (var lease = tracker.admit(GenerationTracker.Kind.GENERATE, null)) {
      var step = manager.chatActuator().residencyUnload(StopDetail.GPU_BUSY, () -> true);
      var policy = step.drainPolicy(Duration.ofSeconds(30));
      var failure = assertThrows(IllegalStateException.class,
          () -> manager.chatActuator().drain(policy));
      assertEquals(ChatActuator.ResidencyUnload.BUSY, step.drainFailed(failure));
    }
    assertTrue(manager.isOnline(), "the reply keeps its server");
    assertNull(manager.residencyUnloadDetail());
  }

  @Test
  void pressureThatClearedBeforeTheStopLeavesTheServerRunning() throws Exception {
    manager = onlineManager();
    assertEquals(ChatActuator.ResidencyUnload.NOT_WANTED, run(StopDetail.GAME_MODE, false));
    assertTrue(manager.isOnline());
    assertNull(manager.residencyUnloadDetail());
    assertTrue(manager.chatActuator().generationActivity() instanceof GenerationTracker tracker
        && tracker.admissionOpen(), "the drain's hold is released");
  }

  @Test
  void aStartRefusedUnderPressureReadsUnloadedWithoutAServer() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    manager = newManager();
    assertEquals(ChatActuator.ResidencyUnload.UNLOADED, run(StopDetail.GPU_BUSY, true));
    assertEquals(StopDetail.GPU_BUSY, manager.residencyUnloadDetail());
    assertTrue(!manager.isOnline());
  }

  private ChatActuator.ResidencyUnload run(StopDetail detail, boolean stillWanted)
      throws Exception {
    ChatActuator actuator = manager.chatActuator();
    ChatActuation<ChatActuator.ResidencyUnload> step =
        actuator.residencyUnload(detail, () -> stillWanted);
    ChatActuator.DrainHold drained = actuator.drain(step.drainPolicy(Duration.ofSeconds(30)));
    return actuator.execute(step, drained);
  }

  private InferenceLifecycleManager onlineManager() throws Exception {
    fakeDir = tmp.resolve("fake-llama");
    InferenceLifecycleManager created = newManager();
    created.switchToOnlineMode();
    assertTrue(created.isOnline(), "the manager starts online");
    return created;
  }

  private InferenceLifecycleManager newManager() throws Exception {
    Path executable = HealthyFakeLlamaServer.install(fakeDir);
    Path model = tmp.resolve("model.gguf");
    if (!Files.exists(model)) Files.createFile(model);
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
