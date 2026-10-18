/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Spec E E-S7a and E-S7b (S7: "indexing slows while chat is answering"), composed the way the
 * Engine composes them: the Engine root, the chat server's manager from the bootstrap factory, a
 * stand-in llama-server streaming an answer, and the knowledge-server bootstrap whose bulk-work
 * verdict the operating status block reads.
 *
 * <p>While the answer streams, the verdict is SLOWED's mode (the duty floor) with the single
 * reason chat_generating, within 4 s; within 4 s of the end it is back to running. Ruling F1: a
 * background generation counts as well.
 */
@Timeout(120)
final class ChatGenerationSlowsIndexingTest {

  private static final long WITHIN_MS = 4_000;

  // E-S7a: a streaming chat answer moves the bulk-work verdict to the duty floor, and back
  @Test
  void chatAnswerSlowsIndexingUntilItEnds(@TempDir Path tempDir) throws Exception {
    assertGenerationSlowsIndexing(tempDir, TestEngineContexts.FOREGROUND);
  }

  // E-S7b: a background generation holds the duty floor too (ruling F1)
  @Test
  void backgroundGenerationSlowsIndexingToo(@TempDir Path tempDir) throws Exception {
    assertGenerationSlowsIndexing(tempDir, TestEngineContexts.BACKGROUND);
  }

  private static void assertGenerationSlowsIndexing(Path tempDir, EngineContext context)
      throws Exception {
    var root = new EngineRoot(Mockito.mock(OperationStore.class),
        Mockito.mock(OperationAttemptRunner.class),
        (gpu, executors, ingestion, indexComponent, encoderComponent) -> {
          throw new AssertionError("the index half is not composed in this test");
        }, 30_000, 5_000);
    var release = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
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
      Path dataDir = tempDir.resolve("data");
      Files.createDirectories(dataDir);
      var bootstrap = new KnowledgeServerBootstrap(root.executors(),
          new KnowledgeServerConfig(false, dataDir, tempDir, tempDir,
              1_000L, 1_000L, 1, 2_000L, 1_000L, 300_000L, 100, 2_000L, 0),
          null, root.components(), root.indexComponent(), root, false, root.publicationLock());
      Supplier<BulkWorkGate.Verdict> verdict = () -> bootstrap.bulkWork().verdict();
      assertEquals(BulkWorkGate.Mode.RUN, verdict.get().mode());

      try (var manager = BootstrapInferenceFactory.createInferenceManager(root.executors(), true,
          inferenceConfig(tempDir, server.getAddress().getPort()), tempDir.toString(), null,
          org.slf4j.LoggerFactory.getLogger(ChatGenerationSlowsIndexingTest.class))) {
        assertNotNull(manager);
        manager.switchToOnlineMode();
        var work = root.admission().admit(context, false);
        try {
          var firstChunk = new CountDownLatch(1);
          var terminal = new CompletableFuture<Throwable>();
          manager.stream(List.of(Map.of("role", "user", "content", "answer")), null, 32,
              chunk -> firstChunk.countDown(), null, ignored -> {}, null,
              ignored -> terminal.complete(null), terminal::complete, null, true, work);
          assertTrue(firstChunk.await(5, TimeUnit.SECONDS), "the answer is streaming");

          assertTrue(within(() -> verdict.get().mode() == BulkWorkGate.Mode.DUTY_FLOOR),
              "while chat answers, indexing is slowed to the duty floor; verdict: "
                  + verdict.get());
          assertEquals(List.of(BulkWorkGate.Reason.CHAT_GENERATING), verdict.get().reasons());

          release.countDown();
          assertEquals(null, terminal.get(5, TimeUnit.SECONDS));
          assertTrue(within(() -> verdict.get().mode() == BulkWorkGate.Mode.RUN),
              "once the answer ends, indexing runs again; verdict: " + verdict.get());
          assertEquals(List.of(), verdict.get().reasons());
        } finally {
          work.close();
        }
      } finally {
        release.countDown();
        server.stop(0);
        bootstrap.close();
      }
    } finally {
      root.close();
      root.processResources().close();
    }
  }

  private static io.justsearch.configuration.resolved.ResolvedConfig inferenceConfig(
      Path tempDir, int port) throws Exception {
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
    config.putDefault("justsearch.inference.external_endpoint", "http://127.0.0.1:" + port);
    config.putDefault("justsearch.context.size", "4096");
    config.putDefault("justsearch.gpu.layers", "0");
    config.putDefault("justsearch.ai.disabled", "false");
    config.putDefault("justsearch.llm.enabled", "true");
    return config.build();
  }

  private static boolean within(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WITHIN_MS);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) return false;
      Thread.sleep(10);
    }
    return true;
  }
}
