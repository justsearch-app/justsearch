package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructuredContentExtractorTest {

  @TempDir Path tempDir;

  @Nested
  class BasicExtraction {

    @Test
    void plainTextFile() throws Exception {
      Path file = tempDir.resolve("test.txt");
      Files.writeString(file, "Hello, world!");

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertNotNull(result);
      assertTrue(result.content().contains("Hello, world"), result.content());
      assertTrue(result.isTextBased());
    }

    @Test
    void markdownFile() throws Exception {
      Path file = tempDir.resolve("readme.md");
      Files.writeString(file, "# Title\n\nSome body text.\n\n## Section\n\nMore text.");

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertNotNull(result);
      assertTrue(result.content().contains("Title"), result.content());
      assertTrue(result.content().contains("body text"), result.content());
      assertTrue(result.content().contains("Section"), result.content());
    }

    @Test
    void htmlFilePreservesStructure() throws Exception {
      Path file = tempDir.resolve("test.html");
      Files.writeString(
          file,
          """
          <html><body>
          <h1>Report</h1>
          <p>Introduction paragraph.</p>
          <table>
            <tr><th>Name</th><th>Value</th></tr>
            <tr><td>Revenue</td><td>$100M</td></tr>
          </table>
          </body></html>
          """);

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertNotNull(result);
      String content = result.content();
      assertTrue(content.contains("Report"), "Should contain heading text");
      assertTrue(content.contains("Introduction"), "Should contain paragraph text");
      assertTrue(content.contains("Revenue"), "Should contain table data");
      assertTrue(content.contains("$100M"), "Should contain table values");
    }

    @Test
    void emptyFile() throws Exception {
      Path file = tempDir.resolve("empty.txt");
      Files.writeString(file, "");

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertEquals("", result.content());
    }

    @Test
    void jsonFile() throws Exception {
      Path file = tempDir.resolve("data.json");
      Files.writeString(file, """
          {"name": "test", "value": 42}
          """);

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertNotNull(result);
      assertTrue(result.content().contains("test"), result.content());
    }
  }

  @Nested
  class SizeGuards {

    @Test
    void tableAmplificationIsCappedAndReportedBeforeArtifactClamping() throws Exception {
      String header = "h".repeat(256);
      Path file = tempDir.resolve("amplified.html");
      Files.writeString(file, "<html><body><table><tr><th>" + header + "</th></tr>"
          + "<tr><td>x</td></tr>".repeat(100) + "</table></body></html>");

      var result = new StructuredContentExtractor(1_024).extractWithStatus(file);

      assertTrue(result.result().content().length() <= 1_024);
      assertTrue(result.result().content().startsWith(header + " = x"));
      assertTrue(result.truncated(), "Serialization amplification must report truncation");
      assertEquals(1, result.summary().tableCount());
    }

    @Test
    void nonExistentFile() {
      var extractor = new StructuredContentExtractor();
      assertThrows(IOException.class, () -> extractor.extract(tempDir.resolve("missing.txt")));
    }

    @Test
    void nullFile() {
      var extractor = new StructuredContentExtractor();
      assertThrows(NullPointerException.class, () -> extractor.extract(null));
    }
  }

  @Nested
  class FallbackBehavior {

    @Test
    void fallbackProducesSameContentAsFlat() throws Exception {
      // A simple text file should produce equivalent content from both extractors
      Path file = tempDir.resolve("simple.txt");
      Files.writeString(file, "Simple plain text content for testing.");

      var structured = new StructuredContentExtractor();
      var flat = new ContentExtractor();

      ExtractionResult structuredResult = structured.extract(file);
      ExtractionResult flatResult = flat.extract(file);

      // Both should contain the same key terms
      assertTrue(structuredResult.content().contains("Simple plain text"));
      assertTrue(flatResult.content().contains("Simple plain text"));
    }
  }

  @Nested
  class MimeDetection {

    @Test
    void detectsTextPlain() throws Exception {
      Path file = tempDir.resolve("test.txt");
      Files.writeString(file, "text content");

      var extractor = new StructuredContentExtractor();
      String mime = extractor.detectMimeType(file);
      assertTrue(mime.startsWith("text/"), "Expected text/* MIME type, got: " + mime);
    }

    @Test
    void detectsHtml() throws Exception {
      Path file = tempDir.resolve("test.html");
      Files.writeString(file, "<html><body>Hello</body></html>");

      var extractor = new StructuredContentExtractor();
      String mime = extractor.detectMimeType(file);
      assertTrue(
          mime.contains("html") || mime.contains("xml"),
          "Expected HTML MIME type, got: " + mime);
    }
  }

  /**
   * Tempdoc 803 blocker: one document of 5,408 in {@code mixed/miracl-fr-2k} indexed with EMPTY
   * content, which failed embed + SPLADE + NER and made the whole corpus incomparable. The document
   * is ordinary French prose whose first two bytes happen to be {@code P4} — the NetPBM binary
   * bitmap magic number. Tika's content detection prefers a magic hit over the {@code .txt} name
   * hint when the two types are unrelated, so the file was parsed as an image and yielded no text.
   *
   * <p>These tests pin the general rule, not the one document: a file whose NAME says text and
   * whose BYTES decode as text is extracted as text, whatever binary format its opening bytes
   * resemble.
   */
  @Nested
  class TextNameVersusBinaryMagic {

    /** The exact document from tempdoc 803, byte-for-byte. */
    private static final String MIRACL_FR_1455689 =
        "P4 Jean Merieux\r\n\r\nLes principaux agents de classe 4 sont des virus generant"
            + " soit des fievres hemorragiques, comme Ebola, Lassa, Marburg, Congo-Crimee,"
            + " soit des maladies infectieuses a haut pouvoir de dissemination, et a haut"
            + " taux de mortalite, comme la variole.";

    @Test
    void extractsProseWhoseFirstBytesAreThePbmMagicNumber() throws Exception {
      Path file = tempDir.resolve("1455689.txt");
      Files.writeString(file, MIRACL_FR_1455689);

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      // Assert on body text, not merely non-empty: an image parser can still emit metadata-only
      // output, which "content is not blank" would accept.
      assertTrue(
          result.content().contains("fievres hemorragiques"),
          "expected the document body, got: " + result.content());
      assertTrue(
          result.content().contains("variole"),
          "expected the document tail, got: " + result.content());
    }

    @Test
    void detectsTextPlainForProseWhoseFirstBytesAreThePbmMagicNumber() throws Exception {
      Path file = tempDir.resolve("1455689-mime.txt");
      Files.writeString(file, MIRACL_FR_1455689);

      var extractor = new StructuredContentExtractor();
      String mime = extractor.detectMimeType(file);
      assertTrue(mime.startsWith("text/"), "Expected text/* MIME type, got: " + mime);
    }

    /**
     * The whole PNM magic family, so the fix cannot be a one-magic special case. {@code P1}-{@code
     * P6} are the NetPBM magic numbers; each is followed by whitespace in a real bitmap, which is
     * exactly what a sentence beginning "P4 Jean ..." looks like.
     */
    @Test
    void extractsProseForEveryPnmMagicNumber() throws Exception {
      for (int variant = 1; variant <= 6; variant++) {
        String text = "P" + variant + " Jean Merieux ecrit sur les fievres hemorragiques.";
        Path file = tempDir.resolve("pnm-" + variant + ".txt");
        Files.writeString(file, text);

        var extractor = new StructuredContentExtractor();
        ExtractionResult result = extractor.extract(file);

        assertTrue(
            result.content().contains("fievres hemorragiques"),
            "P" + variant + " prose lost its body: " + result.content());
      }
    }

    /**
     * Pins the conflict the fix resolves, so the fix cannot be deleted as "unnecessary": Tika's
     * OWN default detector really does call this prose a bitmap. If a future Tika release changes
     * that, this test fails and says so — which is the signal to reconsider the wrapper, not to
     * weaken it.
     */
    @Test
    void tikasDefaultDetectorMisreadsThisProseAsABitmap() throws Exception {
      Path file = tempDir.resolve("1455689-default.txt");
      Files.writeString(file, MIRACL_FR_1455689);

      org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();
      metadata.set(
          org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY,
          file.getFileName().toString());
      try (var in =
          org.apache.tika.io.TikaInputStream.get(Files.newInputStream(file))) {
        var defaultType =
            org.apache.tika.config.TikaConfig.getDefaultConfig()
                .getDetector()
                .detect(in, metadata);
        assertEquals(
            "image/x-portable-bitmap",
            defaultType.toString(),
            "the conflict this detector exists to resolve no longer reproduces");
      }
    }

    /** A genuine binary must NOT be dragged into the text path by a misleading extension. */
    @Test
    void doesNotTreatRealBinaryAsTextEvenWithATextExtension() throws Exception {
      Path file = tempDir.resolve("actually-a-bitmap.txt");
      // Minimal valid 8x1 binary PBM: magic, dimensions, then a raw packed byte row.
      byte[] pbm = new byte[] {'P', '4', '\n', '8', ' ', '1', '\n', (byte) 0xF0};
      Files.write(file, pbm);

      var extractor = new StructuredContentExtractor();
      String mime = extractor.detectMimeType(file);
      assertFalse(
          mime.startsWith("text/"),
          "A real binary bitmap must not be reclassified as text, got: " + mime);
    }
  }

  @Nested
  class FrontmatterTitle {

    @Test
    void extractsYamlFrontmatter() throws Exception {
      Path file = tempDir.resolve("doc.md");
      Files.writeString(file, "---\ntitle: My Document\n---\n\nContent here.");

      var extractor = new StructuredContentExtractor();
      ExtractionResult result = extractor.extract(file);

      assertEquals("My Document", result.title());
    }
  }
}
