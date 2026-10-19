/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

final class ChatResumeProgressTest {
  @Test @Timeout(90)
  void aSingleSlotReplyProgressingAfterResumePassesWithoutAProbeOrRelaunch(@TempDir Path dir) throws Exception {
    Path fake = dir.resolve("fake"), model = Files.createFile(dir.resolve("model.gguf"));
    Path executable = HealthyFakeLlamaServer.install(fake, "--single-slot-stream");
    int port;
    try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
    var captured = TestResolvedConfigHelper.fromEntries(Map.of("justsearch.context.size", "4096",
        "justsearch.llm.slots", "1", "justsearch.data.dir", dir.resolve("data").toString()));
    var manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(),
        new InferenceConfig(executable, model, null, port, 4096, 0, false),
        NoopInferenceTelemetryEvents.INSTANCE, ManagedChildRegistry.noop(), captured);
    var tokens = new AtomicInteger(); var first = new CountDownLatch(1);
    var ended = new CompletableFuture<Void>();
    try {
      manager.switchToOnlineMode();
      var argv = HealthyFakeLlamaServer.launches(fake).getFirst();
      assertEquals("1", argv.get(argv.indexOf("-np") + 1));
      manager.streamChat(List.of(Map.of("role", "user", "content", "keep replying")), 1000,
          token -> { tokens.incrementAndGet(); first.countDown(); },
          answer -> ended.complete(null), ended::completeExceptionally);
      assertTrue(first.await(10, TimeUnit.SECONDS));
      manager.chatActuator().noteResume();
      var actuator = manager.chatActuator();
      assertEquals(ChatActuator.ResumeOutcome.PASSED, actuator.execute(actuator.resumeProbe(), null).outcome(),
          "tokens produced after resume prove the single occupied slot is healthy");
      int seen = tokens.get();
      Thread.sleep(31_000); // Keep the real GENERATE stream open beyond the 30 s grace period.
      assertTrue(manager.generationActivity().generating());
      assertTrue(tokens.get() > seen);
      assertFalse(ended.isDone(), "the user's reply was not interrupted");
      assertEquals(1, HealthyFakeLlamaServer.launches(fake).size());
      assertFalse(Files.exists(fake.resolve("probe-attempts")), "no one-token probe queues behind the reply");
    } finally {
      Files.writeString(fake.resolve("stream-finish"), "finish");
      try { ended.get(3, TimeUnit.SECONDS); } catch (Exception ignored) { /* cancelled or failed on the before-state */ }
      manager.close(); HealthyFakeLlamaServer.endAll(fake);
    }
  }
}
