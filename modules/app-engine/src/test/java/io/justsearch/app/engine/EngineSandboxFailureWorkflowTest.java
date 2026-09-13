/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory;
import io.justsearch.indexerworker.fixtures.FormatCapabilityFixtureFactory.FormatId;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** C1-14: real Engine indexing, search and ledger with a child that cannot start. */
@Timeout(180)
final class EngineSandboxFailureWorkflowTest {
  @TempDir Path tempDir;

  @Test
  void brokenChildConfinesFiveFamiliesWhileThreeDecoderFormatsBecomeSearchable() throws Exception {
    String modeKey = "justsearch.extraction.sandbox.mode";
    String commandKey = "justsearch.extraction.sandbox.command";
    String previousMode = System.getProperty(modeKey);
    String previousCommand = System.getProperty(commandKey);
    System.setProperty(modeKey, "auto");
    System.setProperty(commandKey, "justsearch-no-such-extraction-child-binary");
    try {
      Path corpus = Files.createDirectories(tempDir.resolve("corpus"));
      // PDF/PNG signatures are sufficient for routing: this child fails before parsing begins.
      List<Path> routed = List.of(
          Files.writeString(corpus.resolve("routed.pdf"), "%PDF-1.7\n%%EOF\n"),
          FormatCapabilityFixtureFactory.write(corpus, FormatId.XLSX),
          FormatCapabilityFixtureFactory.write(corpus, FormatId.ZIP_WITH_XLSX),
          Files.write(corpus.resolve("routed.png"), Base64.getDecoder().decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
                  + "/x8AAusB9Wl2nGQAAAAASUVORK5CYII=")),
          Files.write(corpus.resolve("routed.bin"), new byte[] {0, 1, 2, 0, 3, 4}));
      ContentExtractor detector = new ContentExtractor();
      assertEquals(List.of("pdf", "office", "archive", "image", "binary"),
          routed.stream().map(path -> IndexingDocumentOps.classifyFileKind(
              path, detector.detectMimeType(path))).toList());
      Map<Path, String> decoders = Map.of(
          Files.writeString(corpus.resolve("plain.txt"), "C1_PLAIN_SURVIVES"), "C1_PLAIN_SURVIVES",
          Files.writeString(corpus.resolve("notes.md"), "C1_MARKDOWN_SURVIVES"), "C1_MARKDOWN_SURVIVES",
          Files.writeString(corpus.resolve("rows.csv"), "marker,value\nC1_CSV_SURVIVES,42\n"), "C1_CSV_SURVIVES");
      List<Path> all = new ArrayList<>(routed);
      all.addAll(decoders.keySet());
      try (EngineTestHarness engine = EngineTestHarness.start(tempDir.resolve("data"))) {
        assertEquals(all.size(), engine.client().submitBatch(all, TestEngineContexts.FOREGROUND)
            .getAcceptedCount());
        for (var decoder : decoders.entrySet()) {
          String identity = PathNormalizer.normalizePath(decoder.getKey().toString());
          assertTrue(engine.awaitSearchable(decoder.getValue(), 60_000), decoder.toString());
          assertTrue(engine.client().search(decoder.getValue(), 20, TestEngineContexts.FOREGROUND)
              .getResultsList().stream().anyMatch(hit -> identity.equals(hit.getId())), identity);
          assertTrue(engine.client().fetchDocumentSlice(identity, 0, 4096, TestEngineContexts.FOREGROUND)
              .getContent().contains(decoder.getValue()), identity);
        }
        // Retryable routed jobs prevent queue drain. Reconcile each identity's ledger instead.
        for (Path path : all) {
          String hash = DocumentIdentityStore.pathHash(PathNormalizer.normalizePath(path.toString()));
          long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
          List<Map<String, Object>> events;
          do {
            events = engine.client().recentIngestionEvents(100, TestEngineContexts.FOREGROUND).stream()
                .filter(event -> hash.equals(event.get("pathHash"))).toList();
            if (!events.isEmpty()) break;
            Thread.sleep(100);
          } while (System.nanoTime() < deadline);
          assertTrue(!events.isEmpty(), "missing ledger event for " + path);
          for (Map<String, Object> event : events) {
            assertEquals(routed.contains(path) ? "SANDBOX_FAILED" : "SUCCESS",
                event.get("reasonCode"), path.toString());
            if (routed.contains(path)) {
              assertEquals("RETRY_WITH_BACKOFF", event.get("retryPolicy"), path.toString());
            } else {
              assertEquals("SUCCESS_FULL", event.get("outcomeClass"), path.toString());
            }
          }
        }
        assertEquals(3, engine.status().getCore().getDocCount(), "only decoder documents enter the index");
      }
    } finally {
      if (previousMode == null) System.clearProperty(modeKey);
      else System.setProperty(modeKey, previousMode);
      if (previousCommand == null) System.clearProperty(commandKey);
      else System.setProperty(commandKey, previousCommand);
    }
  }
}
