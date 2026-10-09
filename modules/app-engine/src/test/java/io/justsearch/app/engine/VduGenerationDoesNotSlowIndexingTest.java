/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.services.bootstrap.BootstrapInferenceFactory;
import io.justsearch.app.services.vdu.ImagePreparer;
import io.justsearch.app.services.vdu.VduProcessor;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeServerConfig;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.core.scheduling.BulkWorkGate;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Spec E §1.2 and F19, VDU's half ("VDU's own generation is bulk (`BulkWorkKind.VDU`). Status must
 * not report it as \"chat is answering\""; "ASSIST and VDU leases block drains (`closeIfIdle`) but
 * are not counted in `generating()`/`inFlight()`"). Composed as the Engine composes it: the Engine
 * root, the chat server's manager from the bootstrap factory, the production OnlineAiService and
 * VduProcessor, a stand-in llama-server that holds the vision completion open, and the
 * knowledge-server bootstrap whose bulk-work verdict the operating status block reads.
 *
 * <p>A VDU generation with no chat reply in flight keeps the verdict RUN while the page's vision
 * completion is open at the server.
 */
@Timeout(120)
final class VduGenerationDoesNotSlowIndexingTest {

  private static final long OBSERVE_MS = 1_500;

  // Spec E F19: a VDU generation is not chat generating, so indexing is not slowed
  @Test
  void vduGenerationLeavesIndexingRunning(@TempDir Path tempDir) throws Exception {
    var root = new EngineRoot(mock(OperationStore.class),
        mock(OperationAttemptRunner.class),
        (gpu, executors, ingestion, indexComponent, encoderComponent) -> {
          throw new AssertionError("the index half is not composed in this test");
        }, 30_000, 5_000);
    var release = new CountDownLatch(1);
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
          firstReceived.countDown();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"stable extracted page text\"},"
              + "\"finish_reason\":\"stop\"}],\"usage\":{\"completion_tokens\":4}}").getBytes();
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
              org.slf4j.LoggerFactory.getLogger(VduGenerationDoesNotSlowIndexingTest.class));
          var tempFiles = new TempFileManager(tempDir.resolve("vdu-temp"))) {
        assertNotNull(manager);
        manager.switchToOnlineMode();
        OnlineAiRuntimeIntrospection introspection = mock(OnlineAiRuntimeIntrospection.class);
        when(introspection.hasVisionCapability()).thenReturn(true);
        var processor = new VduProcessor(introspection, mock(OnlineAiLifecycleControl.class),
            new OnlineAiServiceImpl(root.admission(), manager), tempFiles, new ImagePreparer());
        Path page = writeLegiblePage(tempDir.resolve("page.png"));
        var vdu = Thread.ofVirtual().start(() -> {
          try {
            processor.process(page, TestEngineContexts.BACKGROUND);
          } catch (Exception | AssertionError ignored) {
            // The outcome of the document is not under test; only the verdict while it runs.
          }
        });
        try {
          assertTrue(firstReceived.await(10, TimeUnit.SECONDS),
              "precondition: the VDU vision completion is open at the server");

          long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OBSERVE_MS);
          while (System.nanoTime() < until) {
            BulkWorkGate.Verdict now = bootstrap.bulkWork().verdict();
            assertEquals(BulkWorkGate.Mode.RUN, now.mode(),
                "a VDU generation with no chat reply must not slow indexing; verdict: " + now);
            Thread.sleep(10);
          }
        } finally {
          release.countDown();
          vdu.join(30_000);
        }
        assertFalse(vdu.isAlive(), "the VDU document finishes once the server answers");
        assertEquals(BulkWorkGate.Mode.RUN, bootstrap.bulkWork().verdict().mode());
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

  private static Path writeLegiblePage(Path file) throws Exception {
    var image = new BufferedImage(1275, 1650, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, image.getWidth(), image.getHeight());
      g.setColor(Color.BLACK);
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
          RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
      String[] lines = {
          "Quarterly report for the visual document",
          "JustSearch visual document processing",
          "ABCDEFGHIJKLMNOPQRSTUVWXYZ 1234567890",
          "The quick brown fox jumps over the lazy dog",
          "Revenue grew in every region this quarter"};
      int y = 160;
      for (String line : lines) {
        g.drawString(line, 80, y);
        y += 90;
      }
    } finally {
      g.dispose();
    }
    ImageIO.write(image, "png", file.toFile());
    return file;
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
