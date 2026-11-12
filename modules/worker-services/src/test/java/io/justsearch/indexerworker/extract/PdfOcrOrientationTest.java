/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class PdfOcrOrientationTest {
  @TempDir Path tempDir;
  private static final List<String> ENGLISH = List.of(
      "Searchable orientation recognises sideways documents.",
      "The greenhouse regulation contains practical instructions.",
      "Every page preserves readable words for keyword search.",
      "Please keep the original photographs and scanned pages.",
      "Important information is available in the attached report.",
      "The brown fox jumps over the lazy dog in the garden.",
      "Receipts and letters contain useful reference information.",
      "Orientation detection should preserve this readable text.");
  private static final List<String> GERMAN = List.of(
      "Bescheid über die Gewächshausverordnung",
      "Die Prüfung der Unterlagen ist abgeschlossen.",
      "Änderungen müssen vollständig überprüft werden.",
      "Für Rückfragen stehen wir Ihnen gerne zur Verfügung.",
      "Die Größe der Gewächshäuser beträgt zwölf Meter.",
      "Bitte beachten Sie die beigefügten Erläuterungen.",
      "Öffentliche Gebäude benötigen zusätzliche Prüfungen.",
      "Mit freundlichen Grüßen aus der Behörde");

  @ParameterizedTest
  @ValueSource(ints = {90, 180, 270})
  void sidewaysImageAndFullAndSelectivePdfRemainSearchable(int angle) throws Exception {
    var runtime = runtime(true, false);
    BufferedImage rotated = rotate(page(ENGLISH), angle);
    Path image = tempDir.resolve("scan-" + angle + ".png");
    ImageIO.write(rotated, "png", image.toFile());
    Path pdf = pdf(rotated, 0);
    try (var engine = engine(runtime, english())) {
      assertWords(engine.ocrImage(image, 100_000).text());
      assertWords(engine.ocrPdf(pdf, 100_000).text());
      assertWords(engine.ocrPdfPages(pdf, List.of(0), 100_000).text());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"page", "heading", "receipt", "blank"})
  void uprightAndSparseTextAreNoWorseThanEnglishPsm6(String kind) throws Exception {
    var runtime = runtime(true, false);
    List<String> lines = switch (kind) {
      case "heading" -> List.of("Important payment information");
      case "receipt" -> List.of("GARDEN SHOP", "Greenhouse 24.50", "Total 24.50");
      case "blank" -> List.of();
      default -> ENGLISH;
    };
    Path image = tempDir.resolve(kind + ".png");
    ImageIO.write(page(lines), "png", image.toFile());
    // A runtime with eng only exercises the existing no-OSD/psm-6 path.
    Path data = Files.createDirectories(tempDir.resolve("english-only"));
    Files.copy(dataFile(runtime, "eng"), data.resolve("eng.traineddata"));
    var beforeRuntime = new TikaOcrRuntime.RuntimePaths(runtime.executable(), runtime.executableDirectory(), data);
    try (var before = engine(beforeRuntime, english()); var after = engine(runtime, english())) {
      long start = System.nanoTime();
      String oldText = before.ocrImage(image, 100_000).text();
      long middle = System.nanoTime();
      String newText = after.ocrImage(image, 100_000).text();
      long end = System.nanoTime();
      System.out.printf("OCR timing %s psm6=%.3fs orientation=%.3fs%n", kind,
          (middle - start) / 1e9, (end - middle) / 1e9);
      assertEquals(words(oldText), words(newText), "Upright recognition must preserve every word in order");
    }
  }

  @Test
  void uprightParagraphStillJoinsLineEndHyphen() throws Exception {
    var runtime = runtime(true, false);
    BufferedImage paragraph = page(List.of(
        "The greenhouse regulation contains useful practical infor-",
        "mation for every person who reads the attached report.",
        "Please keep the original photographs and scanned pages.",
        "Every page preserves readable words for keyword search."), 44);
    Path image = tempDir.resolve("hyphenated-paragraph.png");
    ImageIO.write(paragraph, "png", image.toFile());
    Path pdf = pdf(paragraph, 0);
    Path data = Files.createDirectories(tempDir.resolve("english-only"));
    Files.copy(dataFile(runtime, "eng"), data.resolve("eng.traineddata"));
    var beforeRuntime = new TikaOcrRuntime.RuntimePaths(runtime.executable(), runtime.executableDirectory(), data);
    try (var before = engine(beforeRuntime, english()); var after = engine(runtime, english())) {
      assertTrue(before.ocrImage(image, 100_000).text().contains("infor-\nmation"),
          "Fixture must exercise a word split at a line end");
      String oldText = before.ocrPdf(pdf, 100_000).text();
      assertTrue(oldText.contains("information"), oldText);
      for (String newText : List.of(after.ocrPdf(pdf, 100_000).text(),
          after.ocrPdfPages(pdf, List.of(0), 100_000).text())) {
        assertTrue(newText.contains("information"), newText);
        assertEquals(words(oldText), words(newText), "Line-end joining must preserve every word in order");
      }
    }
  }

  @Test
  void defaultGermanRecognisesUmlautsInScanAndPhotoAndPreservesEnglish() throws Exception {
    var runtime = runtime(true, true);
    Path scan = tempDir.resolve("german.png");
    Path photo = tempDir.resolve("german.jpg");
    ImageIO.write(page(GERMAN), "png", scan.toFile());
    ImageIO.write(page(GERMAN), "jpeg", photo.toFile());
    try (var engine = engine(runtime, OcrRoutingConfig.defaults())) {
      for (Path image : List.of(scan, photo)) {
        String text = engine.ocrImage(image, 100_000).text();
        for (String word : List.of("Bescheid über die Gewächshausverordnung", "Prüfung", "Grüßen", "Größe")) {
          assertTrue(text.contains(word), text);
        }
      }
    }
    Path english = tempDir.resolve("english.png");
    ImageIO.write(page(ENGLISH), "png", english.toFile());
    try (var old = engine(runtime, english()); var defaults = engine(runtime, OcrRoutingConfig.defaults())) {
      long start = System.nanoTime();
      String oldText = old.ocrImage(english, 100_000).text();
      long middle = System.nanoTime();
      String newText = defaults.ocrImage(english, 100_000).text();
      long end = System.nanoTime();
      System.out.printf("OCR timing English eng=%.3fs deu+eng=%.3fs%n", (middle-start)/1e9, (end-middle)/1e9);
      assertEquals(oldText, newText);
    }
  }

  @Test
  void pdfRotationMetadataIsAppliedOnlyOnce() throws Exception {
    var runtime = runtime(true, false);
    try (var engine = engine(runtime, english())) {
      assertWords(engine.ocrPdf(pdf(rotate(page(ENGLISH), 270), 90), 100_000).text());
    }
  }

  private TikaOcrRuntime.RuntimePaths runtime(boolean osd, boolean german) throws Exception {
    var runtime = TikaOcrRuntime.resolve();
    assumeTrue(runtime.available(), "Real OCR skipped: no Tesseract executable");
    assumeTrue(!osd || dataFile(runtime, "osd") != null, "Real OCR skipped: no osd.traineddata");
    assumeTrue(!german || dataFile(runtime, "deu") != null, "German OCR skipped: no deu.traineddata");
    return runtime;
  }

  private Path dataFile(TikaOcrRuntime.RuntimePaths runtime, String language) throws Exception {
    if (runtime.tessdataDirectory() != null) {
      Path path = runtime.tessdataDirectory().resolve(language + ".traineddata");
      return Files.isRegularFile(path) ? path : null;
    }
    // The default system data location on Linux; other installations should select tessdata
    // through the app's JUSTSEARCH_TESSDATA_PATH or TESSDATA_PREFIX setting.
    Path path = Path.of("/usr/share/tesseract-ocr/5/tessdata", language + ".traineddata");
    return Files.isRegularFile(path) ? path : null;
  }

  private PdfOcrEngine engine(TikaOcrRuntime.RuntimePaths runtime, OcrRoutingConfig config) {
    return new PdfOcrEngine(Executors::newFixedThreadPool, config, () -> runtime, 144, 1, null);
  }

  private static OcrRoutingConfig english() {
    return new OcrRoutingConfig(true, List.of("eng"), 30_000, 10, 4096, 40_000_000, 144, 1);
  }

  private static void assertWords(String text) {
    assertTrue(text.contains("Searchable orientation recognises sideways documents"), text);
    assertTrue(text.contains("greenhouse regulation"), text);
  }

  private static List<String> words(String text) {
    return text.isBlank() ? List.of() : List.of(text.strip().split("\\s+"));
  }

  private BufferedImage page(List<String> lines) {
    return page(lines, 85);
  }

  private BufferedImage page(List<String> lines, int lineSpacing) {
    var image = new BufferedImage(1400, 1000, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, image.getWidth(), image.getHeight());
    g.setColor(Color.BLACK);
    g.setFont(new Font("SansSerif", Font.PLAIN, 32));
    for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), 60, 100 + i * lineSpacing);
    g.dispose();
    return image;
  }

  private BufferedImage rotate(BufferedImage source, int angle) {
    int w = source.getWidth(), h = source.getHeight();
    var result = new BufferedImage(angle == 180 ? w : h, angle == 180 ? h : w, BufferedImage.TYPE_INT_RGB);
    var g = result.createGraphics();
    if (angle == 90) { g.translate(h, 0); g.rotate(Math.PI / 2); }
    if (angle == 180) { g.translate(w, h); g.rotate(Math.PI); }
    if (angle == 270) { g.translate(0, w); g.rotate(-Math.PI / 2); }
    g.drawImage(source, 0, 0, null);
    g.dispose();
    return result;
  }

  private Path pdf(BufferedImage image, int rotation) throws Exception {
    Path path = tempDir.resolve("scan-" + rotation + ".pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage(new PDRectangle(image.getWidth() / 2f, image.getHeight() / 2f));
      page.setRotation(rotation);
      document.addPage(page);
      try (var stream = new PDPageContentStream(document, page)) {
        stream.drawImage(LosslessFactory.createFromImage(document, image), 0, 0,
            page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
      }
      document.save(path.toFile());
    }
    return path;
  }
}
