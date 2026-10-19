/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.WorkerConfig;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.server.KnowledgeServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec E E-P14, preservation: CPU chat still paces CPU bulk at today's duty. With chat on the CPU
 * (no GPU layers) and no GPU profile known, a streaming answer makes the Engine's composed indexing
 * pacing busy, and one second of indexing work then yields today's capped debt, 2 s (20% duty,
 * 2 s cap). Passes before Spec E step E2, when generation entered the search gauge, and after it,
 * when generation reaches pacing through Switch A's duty floor.
 */
@Timeout(120)
final class CpuChatPacesIndexingTest {

  // E-P14: a CPU chat answer paces indexing with today's 2 s yield after a 1 s slice
  @Test
  void cpuChatPacesIndexingAtTodaysDuty(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    EngineTestHarness.publishConfig(dataDir, dataDir.resolve("index"), Map.of());
    KnowledgeServer[] built = new KnowledgeServer[1];
    var root = new EngineRoot(
        org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationStore.class),
        org.mockito.Mockito.mock(io.justsearch.app.api.operations.OperationAttemptRunner.class),
        (gpu, executors, ingestion, indexComponent, encoderComponent) -> {
          built[0] = new KnowledgeServer(executors, WorkerConfig.load(),
              new InProcessWorkerSignalBus(gpu),
              io.justsearch.app.api.runtime.ManagedChildRegistry.noop(), ingestion,
              indexComponent, encoderComponent);
          return built[0];
        }, 30_000, 5_000);
    var release = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
      root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
      var pacing = built[0].appServices().indexingPacing();
      server.setExecutor(handlers);
      server.createContext("/health", exchange -> {
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
      });
      server.createContext("/props", exchange -> {
        byte[] body = "{\"model_alias\":\"test-model\",\"n_ctx\":4096}".getBytes();
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      server.createContext("/v1/chat/completions", exchange -> {
        try (exchange) {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 0);
          var body = exchange.getResponseBody();
          body.write("data: {\"choices\":[{\"delta\":{\"content\":\"started\"}}]}\n\n".getBytes());
          body.flush();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          body.write("data: [DONE]\n\n".getBytes());
        }
      });
      server.start();
      var config = new io.justsearch.configuration.resolved.ResolvedConfigBuilder();
      config.putDefault("justsearch.home", tempDir.toString());
      config.putDefault("justsearch.server.exe",
          Files.writeString(tempDir.resolve("llama-server.exe"), "fixture").toString());
    if (!io.justsearch.configuration.PlatformPaths.isWindows()) {
      assertTrue(tempDir.resolve("llama-server.exe").toFile().setExecutable(true));
    }
      config.put("justsearch.llm.model_path", 400, "env_var", "test",
          Files.writeString(tempDir.resolve("model.gguf"), "fixture").toString());
      // The stand-in llama-server is the user's own server, named explicitly (ADR-0051 CH4).
      config.putDefault("justsearch.inference.external_endpoint",
          "http://127.0.0.1:" + server.getAddress().getPort());
      config.putDefault("justsearch.context.size", "4096");
      config.putDefault("justsearch.gpu.layers", "0"); // chat on the CPU
      config.putDefault("justsearch.ai.disabled", "false");
      config.putDefault("justsearch.llm.enabled", "true");
      try (var manager = BootstrapInferenceFactory.createInferenceManager(root.executors(), true,
          config.build(), tempDir.toString(), null,
          org.slf4j.LoggerFactory.getLogger(CpuChatPacesIndexingTest.class))) {
        assertNotNull(manager);
        manager.switchToOnlineMode();
        var work = root.admission().admit(TestEngineContexts.FOREGROUND, false);
        try {
          var firstChunk = new CountDownLatch(1);
          var terminal = new CompletableFuture<Throwable>();
          manager.stream(List.of(Map.of("role", "user", "content", "answer")), null, 32,
              chunk -> firstChunk.countDown(), null, ignored -> {}, null,
              ignored -> terminal.complete(null), terminal::complete, null, true, work);
          assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "the answer is streaming");
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (!pacing.foregroundBusy() && System.nanoTime() < deadline) Thread.sleep(10);
          assertTrue(pacing.foregroundBusy(), "CPU chat paces indexing");

          // One indexing work slice of a second, then the throttle point.
          pacing.pace();
          Thread.sleep(1_000);
          long before = System.nanoTime();
          pacing.pace();
          long yieldedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
          assertTrue(yieldedMs >= 1_900 && yieldedMs < 3_000,
              "a 1 s slice yields today's 2 s cap (20% duty, 2 s debt); yielded " + yieldedMs + " ms");
        } finally {
          release.countDown();
          work.close();
        }
      } finally {
        release.countDown();
        server.stop(0);
      }
    } finally {
      root.close();
      root.processResources().close();
    }
  }
}
