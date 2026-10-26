/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.ort.OnnxSessionCache;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

/** Genuine CPU model/compatibility preservation, separately from scripted-failure policy. */
@Timeout(900)
final class MmrStandardModelInterfacesIntegrationTest extends MmrEngineFixture {
  @Test
  void standardCpuBm25WithMmrReachesRestMcpAndConversation() throws Exception {
    prove("bm25", false, "BM25");
  }

  @Test
  void standardCpuDocFirstHybridWithMmrReachesRestMcpAndConversation() throws Exception {
    prove("hybrid", false, "HYBRID");
  }

  @Test
  void standardCpuChunkVectorHybridWithMmrReachesRestMcpAndConversation() throws Exception {
    prove("hybrid", true, "CHUNK_HYBRID");
  }

  private void prove(String retrieval, boolean chunkVectors, String expectedMode) throws Exception {
    Path model = privateModelDirectory();
    start(model, retrieval, chunkVectors);
    var provider = (EmbeddingProvider) field(worker, "embeddingProvider");
    var compat = (EmbeddingCompatibilityController) field(worker, "embeddingCompatController");
    assertInstanceOf(EmbeddingService.class, provider, "production Engine composition");
    assertNotNull(compat, "never clear or replace genuine compatibility");
    await("production CPU provider READY", Duration.ofMinutes(5), () -> {
      var state = provider.availability();
      assertNotEquals(EmbeddingProvider.Availability.State.FAILED, state.state(),
          () -> "native load failed: " + state.failure());
      return state.state() == EmbeddingProvider.Availability.State.READY;
    });
    assertEquals(768, provider.dimension(), "standard GTE dimension");
    assertFalse(provider.isUsingGpu(), "explicit CPU-only resolved policy");
    assertEquals(io.justsearch.ort.QueryDevice.CPU, provider.queryDevice());
    var rag = field(worker, "ragContextOps");
    var counts = (IndexCountOps) field(rag, "indexCountOps");
    await("completed real embeddings and persisted matching fingerprint", Duration.ofMinutes(5),
        () -> {
          var embedded = counts.queryEmbeddingCounts();
          return embedded.total() == 2 && embedded.completed() == 2 && embedded.pending() == 0
              && embedded.failed() == 0
              && compat.state() == EmbeddingCompatibilityController.State.COMPATIBLE
              && compat.allowQueryEmbeddings()
              && "FINGERPRINT_MATCH".equals(compat.reasonCode())
              && compat.currentFingerprint() != null
              && compat.currentFingerprint().equals(compat.storedFingerprint());
        });
    @SuppressWarnings("unchecked")
    var persisted = ((Supplier<Map<String, String>>) field(compat, "storedMetadataSupplier")).get();
    assertEquals(compat.currentFingerprint(),
        persisted.get(EmbeddingCompatibilityController.COMMIT_META_KEY),
        "read actual committed metadata, not NEW_INDEX_NO_FINGERPRINT");
    if (chunkVectors) {
      await("real persisted passage vectors cover the chunk-hybrid branch", Duration.ofMinutes(5),
          () -> counts.queryChunkVectorPresenceCount().isReady(100));
    }

    // Observe the production MMR completion log without replacing/decorating the provider or
    // altering compatibility. The line is emitted only after all bounded real passage vectors
    // were encoded and the selector completed. Parent metrics alone cannot establish this.
    var logger = (Logger) LoggerFactory.getLogger(rag.getClass());
    Level previousLevel = logger.getLevel();
    var evidence = new ListAppender<ILoggingEvent>();
    evidence.start();
    logger.addAppender(evidence);
    logger.setLevel(Level.DEBUG);
    try {
      var result = retrieve();
      assertPassages(result);
      assertEquals(expectedMode, result.retrievalMode(), result.toString());
      assertEquals(expectedMode.equals("BM25") ? "BM25_CONFIGURED" : "HYBRID_AVAILABLE",
          result.retrievalModeReason());
      assertTrue(evidence.list.stream().map(ILoggingEvent::getFormattedMessage)
          .anyMatch(line -> line.startsWith("MMR diversification:")
              && line.contains("selected " + K)),
          () -> "real MMR did not finish; cannot claim passage-vector participation: "
              + evidence.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
      long beforeOutward = completedMmr(evidence);
      assertOutward(result);
      assertEquals(beforeOutward + 2, completedMmr(evidence),
          "REST and MCP must each complete real-provider MMR, not silently degrade to position");
      long beforeConversation = completedMmr(evidence);
      assertConversation(documents);
      assertEquals(beforeConversation + 1, completedMmr(evidence),
          "conversation retrieval must complete MMR before its independently owned trim");
      System.out.println("MMR_STANDARD model=" + model + " provider=" + provider.getClass().getName()
          + " availability=" + provider.availability() + " dimension=" + provider.dimension()
          + " device=" + provider.queryDevice() + " mode=" + result.retrievalMode()
          + " reason=" + compat.reasonCode() + " currentFingerprint=" + compat.currentFingerprint()
          + " storedFingerprint=" + compat.storedFingerprint()
          + " committedFingerprint=" + persisted.get(EmbeddingCompatibilityController.COMMIT_META_KEY)
          + " embeddings=" + counts.queryEmbeddingCounts()
          + " passageVectors=" + counts.queryChunkVectorPresenceCount());
      System.out.println("MMR_STANDARD_SELECTION " + evidence.list.stream()
          .map(ILoggingEvent::getFormattedMessage)
          .filter(line -> line.startsWith("MMR diversification:")).toList());
      loadedNativeIdentity();
    } finally {
      logger.detachAppender(evidence);
      logger.setLevel(previousLevel);
      evidence.stop();
    }
  }

  private static long completedMmr(ListAppender<ILoggingEvent> evidence) {
    return evidence.list.stream().map(ILoggingEvent::getFormattedMessage)
        .filter(line -> line.startsWith("MMR diversification:")).count();
  }

  private static Path privateModelDirectory() throws Exception {
    // ModelDirTestResolver's directory convention and eight-parent bound. UI does not depend
    // on ort-common testFixtures; do not add a build dependency solely for the walker. Stop at
    // THIS checkout root so a missing private package can never fall through to shared assets.
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 8 && root != null; i++, root = root.getParent()) {
      if (!Files.isRegularFile(root.resolve("settings.gradle.kts"))) continue;
      Path model = root.resolve("models/onnx/embedding");
      List<String> required = List.of("model.onnx", "tokenizer.json", "model_manifest.json",
          "pooling_config.json", "prefix_config.json", "tokenizer_config.json",
          "special_tokens_map.json");
      assumeTrue(required.stream().allMatch(name ->
          Files.isRegularFile(model.resolve(name), LinkOption.NOFOLLOW_LINKS)),
          "root must stage an independent standard CPU package at " + model);
      assertEquals(model.toAbsolutePath().normalize(), model.toRealPath(),
          "private model directory cannot traverse a symlink");
      for (String name : required) {
        Path file = model.resolve(name);
        if (Files.getFileStore(file).supportsFileAttributeView("unix")) {
          assertEquals(1, ((Number) Files.getAttribute(file, "unix:nlink")).intValue(),
              "private model files cannot be hardlinks: " + file);
        }
      }
      return model;
    }
    throw new AssertionError("cannot find this checkout root within ModelDirTestResolver bound");
  }

  private static void loadedNativeIdentity() throws Exception {
    System.out.println("MMR_NATIVE pid=" + ProcessHandle.current().pid()
        + " ortVersion=" + OnnxSessionCache.ortVersion());
    Path maps = Path.of("/proc/self/maps");
    if (!Files.isRegularFile(maps)) {
      System.out.println("MMR_NATIVE mappings unavailable; root must retain Test JVM native diagnostics");
      return;
    }
    var libraries = Files.readAllLines(maps).stream()
        .filter(line -> line.contains("onnxruntime") || line.contains("libtokenizers"))
        .map(line -> line.split("\\s+", 6))
        .filter(parts -> parts.length == 6 && parts[5].startsWith("/"))
        .map(parts -> parts[5]).distinct().sorted().toList();
    assertTrue(libraries.stream().anyMatch(path -> path.contains("onnxruntime")),
        "retain actual loaded ORT identity");
    assertTrue(libraries.stream().anyMatch(path -> path.contains("libtokenizers")),
        "retain actual loaded DJL tokenizer identity");
    for (String library : libraries) {
      Path file = Path.of(library);
      assertTrue(Files.isRegularFile(file), "loaded native library must still be identifiable: " + file);
      var digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(file)) {
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
      }
      System.out.println("MMR_NATIVE loaded=" + file.toRealPath() + " bytes=" + Files.size(file)
          + " sha256=" + HexFormat.of().formatHex(digest.digest()));
    }
  }
}
