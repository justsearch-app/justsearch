/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.app.api.runtime.ledger.LedgerRecordType;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task b6d536 b101: real OCR writes no forced record per document. Three raster images OCR'd by the
 * bundled Tesseract through the production extractor: the shared window's start is the one forced
 * record; each real Tesseract still has its start and exit receipts, REQUESTED with a reason,
 * written unforced and forced as a group, at the latest when the window closes.
 */
@EnabledOnOs(OS.WINDOWS)
final class OcrReceiptForcingTest {
  @TempDir Path temp;

  private record Written(String type, boolean forced, Map<String, Object> fields) {}

  @Test void threeRealOcrsForceOnlyTheWindowStart() throws Exception {
    Assumptions.assumeTrue(TikaOcrRuntime.resolve().available(), "real Tesseract unavailable");
    var config = OcrRoutingConfig.defaults();
    Assumptions.assumeTrue(TikaOcrRuntime.blockedReason(config).isEmpty(), "Tesseract English data unavailable");
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    Object saved = field.get(null);
    List<Written> writes = new CopyOnWriteArrayList<>();
    try {
      OwnedHelper.recordTo(new LedgerSink() {
        @Override public boolean append(LedgerRecordType type, Map<String, ?> fields) {
          writes.add(new Written(type.token(), true, new LinkedHashMap<>(fields)));
          return true;
        }

        @Override public boolean appendUnforced(LedgerRecordType type, Map<String, ?> fields) {
          writes.add(new Written(type.token(), false, new LinkedHashMap<>(fields)));
          return true;
        }

        public boolean flush() {
          writes.add(new Written("flush", true, Map.of()));
          return true;
        }
      }, new EngineIdentity(ProcessHandle.current().pid(), Optional.empty(), "ocr-forcing"));
      try (var extractor = new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(),
          TikaExtractionPolicy.defaults(), config)) {
        PolicyDrivenTikaExtractor.TESSERACT_WINDOW.close(); // start from no open window
        writes.clear();
        for (int document = 0; document < 3; document++) {
          Path image = temp.resolve("scan-" + document + ".png");
          BufferedImage pixels = new BufferedImage(800, 160, BufferedImage.TYPE_INT_RGB);
          var graphics = pixels.createGraphics();
          try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 800, 160);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("Arial", Font.BOLD, 36));
            graphics.drawString("Harbor customs ledger", 20, 60);
            graphics.drawString("Cargo manifest number " + document, 20, 115);
          } finally {
            graphics.dispose();
          }
          ImageIO.write(pixels, "png", image.toFile());
          var artifact = extractor.extractArtifact(image);
          assertTrue(artifact.result().content().toLowerCase(Locale.ROOT).contains("harbor customs ledger"),
              "real OCR ran for document " + document + ": " + artifact + "; writes=" + writes);
        }
      }
      List<Written> records = writes.stream().filter(w -> !w.type().equals("flush")).toList();
      List<Object> ocr = records.stream().filter(w -> w.type().equals("helper-start")
          && String.valueOf(w.fields().get("purpose")).contains("page OCR")).map(w -> w.fields().get("helperId")).toList();
      assertEquals(3, ocr.size(), "one real Tesseract per document: " + records);
      List<Written> exits = records.stream().filter(w -> w.type().equals("helper-exit")
          && ocr.contains(w.fields().get("helperId"))).toList();
      assertEquals(3, exits.size(), "every real OCR exit is recorded once: " + records);
      assertTrue(exits.stream().allMatch(w -> "REQUESTED".equals(w.fields().get("exitClass"))
          && w.fields().get("reason") != null), exits.toString());
      assertEquals(List.of("helper-window-start"), records.stream().filter(Written::forced).map(Written::type).toList(),
          "the window start is the one forced record; no forced record per document: " + records);
      assertEquals(1, records.stream().filter(w -> w.type().equals("helper-window-end")).count(), records.toString());
      int lastRecord = writes.indexOf(records.getLast());
      assertTrue(writes.subList(lastRecord, writes.size()).stream().anyMatch(w -> w.type().equals("flush")),
          "the unforced receipts and window end are forced as a group at close: " + writes);
    } finally {
      field.set(null, saved);
    }
  }
}
