/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task b6d536 b101: helper-window records scale with bursts of Tesseract-capable activity, not
 * with documents. A parse that cannot launch Tesseract writes none; consecutive parses that can
 * share one window. Runs unchanged on b599d4d7e, which wrote two windows for every parse.
 */
final class ExtractionHelperWindowRecordsTest {
  @TempDir Path dir;
  private final List<String> types = new CopyOnWriteArrayList<>();
  private Object savedRecorder;

  @BeforeEach void record() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    savedRecorder = field.get(null);
    LedgerSink sink = (type, fields) -> {
      types.add(type.token());
      return true;
    };
    OwnedHelper.recordTo(sink, new EngineIdentity(ProcessHandle.current().pid(), Optional.empty(), "window-records"));
  }

  @AfterEach void restore() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    field.set(null, savedRecorder);
  }

  private long count(String type) {
    return types.stream().filter(type::equals).count();
  }

  private static PolicyDrivenTikaExtractor extractor() {
    return new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(),
        TikaExtractionPolicy.defaults(), OcrRoutingConfig.defaults());
  }

  @Test void markdownAndTextParsesWriteNoHelperWindowRecords() throws Exception {
    try (var extractor = extractor()) {
      types.clear(); // construction is not a parse
      for (int i = 0; i < 1_000; i++) {
        Path file = dir.resolve("doc-" + i + (i % 2 == 0 ? ".md" : ".txt"));
        Files.writeString(file, "# Harbor ledger " + i + "\n\nCustoms record for the storm survey " + i + ".\n",
            StandardCharsets.UTF_8);
        extractor.extractArtifact(file);
      }
      assertEquals(0, count("helper-window-start") + count("helper-window-end"),
          "1,000 parses that cannot launch Tesseract write no helper-window record: " + types.size()
              + " records, " + count("helper-window-start") + " window starts");
    }
  }

  @Test void consecutiveOcrCapableParsesShareOneWindow() throws Exception {
    extractor().close(); // ends any window an earlier test left idle in this JVM
    Path image = dir.resolve("scan.png");
    BufferedImage pixels = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    ImageIO.write(pixels, "png", image.toFile());
    // OCR enabled and an image: every parse may launch Tesseract. The image guard then declines
    // OCR, which keeps 100 parses fast without changing what the window must cover.
    var ocr = new OcrRoutingConfig(true, List.of("eng"), 30_000, 200, 1, 40_000_000, 300, 0);
    try (var extractor = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(),
        TikaExtractionPolicy.defaults(), ocr)) {
      types.clear();
      for (int i = 0; i < 100; i++) {
        extractor.extractArtifact(image);
      }
    }
    assertEquals(1, count("helper-window-start"),
        "100 consecutive OCR-capable parses open one window, not one per document");
    assertEquals(1, count("helper-window-end"), "the window ends once, at the latest at shutdown");
  }
}
