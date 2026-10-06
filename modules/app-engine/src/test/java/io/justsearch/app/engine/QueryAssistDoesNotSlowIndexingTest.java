/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.app.services.worker.QueryUnderstandingService;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Spec E E-S7b, its ASSIST half (reconciliation v2: "Chat generating (HUMAN_CHAT, AGENT_CHAT,
 * BACKGROUND GENERATE leases; not ASSIST)"). Composed as the Engine composes it: the Engine root,
 * the chat server's manager from the bootstrap factory, the production OnlineAiService and
 * QueryUnderstandingService, a stand-in llama-server that holds each completion open, and the
 * knowledge-server bootstrap whose bulk-work verdict the operating status block reads.
 *
 * <p>A burst of query-understanding calls with no chat reply in flight keeps the verdict RUN for
 * as long as the calls are open at the server, and afterwards.
 */
@Timeout(120)
final class QueryAssistDoesNotSlowIndexingTest {

  private static final int BURST = 5;
  private static final long OBSERVE_MS = 1_500;

  // E-S7b (ASSIST): query understanding does not put indexing on the duty floor
  @Test
  void queryUnderstandingBurstLeavesIndexingRunning(@TempDir Path tempDir) throws Exception {
    var root = new EngineRoot(Mockito.mock(OperationStore.class),
        Mockito.mock(OperationAttemptRunner.class),
        (gpu, executors, ingestion, indexComponent, encoderComponent) -> {
          throw new AssertionError("the index half is not composed in this test");
        }, 30_000, 5_000);
    var release = new CountDownLatch(1);
    var received = new AtomicInteger();
    var firstReceived = new CountDownLatch(1);
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
          received.incrementAndGet();
          firstReceived.countDown();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          byte[] body = "{\"choices\":[{\"message\":{\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}"
              .getBytes();
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
        }
      });
      server.start();
      Path dataDir = tempDir.resolve("data");
      Files.createDirectories(dataDir);
      var bootstrap = new KnowledgeServerBootstrap(root.executors(),
          new KnowledgeServerConfig(false, dataDir, tempDir, tempDir,
              1_000L, 1_000L, 1, 2_000L, 1_000L, 300_000L, 100, 2_000L, 0),
          null, root.components(), root.indexComponent(), root, false, root.publicationLock());
      try (var manager = BootstrapInferenceFactory.createInferenceManager(root.executors(), true,
          inferenceConfig(tempDir, server.getAddress().getPort()), tempDir.toString(), null,
          org.slf4j.LoggerFactory.getLogger(QueryAssistDoesNotSlowIndexingTest.class))) {
        assertNotNull(manager);
        manager.switchToOnlineMode();
        var qu = new QueryUnderstandingService(
            new OnlineAiServiceImpl(root.admission(), manager), () -> true);
        assertTrue(qu.isAvailable(), "precondition: query understanding runs");
        var work = root.admission().admit(TestEngineContexts.FOREGROUND, false);
        try {
          List<CompletableFuture<?>> burst = new ArrayList<>();
          for (int i = 0; i < BURST; i++) {
            burst.add(qu.extract("quarterly report " + i, "", work.context()));
          }
          assertTrue(firstReceived.await(10, TimeUnit.SECONDS),
              "precondition: a query-understanding completion is open at the server");

          long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OBSERVE_MS);
          while (System.nanoTime() < until) {
            BulkWorkGate.Verdict now = bootstrap.bulkWork().verdict();
            assertEquals(BulkWorkGate.Mode.RUN, now.mode(),
                "query assistance with no chat reply must not slow indexing; verdict: " + now);
            Thread.sleep(10);
          }

          release.countDown();
          for (var call : burst) {
            call.handle((result, failure) -> null).get(15, TimeUnit.SECONDS);
          }
          assertTrue(received.get() >= 1);
          assertEquals(BulkWorkGate.Mode.RUN, bootstrap.bulkWork().verdict().mode());
        } finally {
          release.countDown();
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
}
