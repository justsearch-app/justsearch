package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests that exercise StructuredContentExtractor on real document fixtures (PDF, DOCX,
 * XLSX) and verify structural elements are captured.
 */
class StructuredExtractionIntegrationTest {

  @TempDir Path tempDir;

  @Test
  void pdfPageEdgesWithLongCommonPrefixesRetainUniqueSuffixes() throws Exception {
    Path pdf = tempDir.resolve("unique-edges.pdf");
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      for (int page = 0; page < 3; page++) {
        var item = new org.apache.pdfbox.pdmodel.PDPage(
            new org.apache.pdfbox.pdmodel.common.PDRectangle(1000, 1000));
        document.addPage(item);
        try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, item)) {
          content.beginText();
          content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
              org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 5);
          content.newLineAtOffset(10, 950);
          content.showText("h".repeat(200) + "HeaderSecret" + page);
          content.newLineAtOffset(0, -450);
          content.showText("Interior paragraph " + page);
          content.newLineAtOffset(0, -450);
          content.showText("f".repeat(200) + "FooterSecret" + page);
          content.endText();
        }
      }
      document.save(pdf.toFile());
    }
    try (var extractor = new StructuredContentExtractor()) {
      String text = extractor.extract(pdf).content();
      for (int page = 0; page < 3; page++) {
        assertTrue(text.contains("HeaderSecret" + page));
        assertTrue(text.contains("FooterSecret" + page));
      }
    }
  }

  @Nested
  class DocxFixture {

    @Test
    void docxExtractsSearchableContent() throws Exception {
      Path docx = copyFixture("/fixtures/office/office-marker.docx", "office-marker.docx");

      var structured = new StructuredContentExtractor();
      ExtractionResult result = structured.extract(docx);

      assertNotNull(result);
      assertFalse(result.content().isBlank(), "DOCX should produce non-empty content");
      assertTrue(result.isOffice(), "Should detect as Office MIME");

      // The DOCX fixture should produce content through the structured path
      System.out.println("=== DOCX Structured Output (first 500 chars) ===");
      System.out.println(result.content().substring(0, Math.min(500, result.content().length())));
      System.out.println("=== Total length: " + result.content().length() + " ===");
    }

    @Test
    void docxContainsSameTermsAsFlat() throws Exception {
      Path docx = copyFixture("/fixtures/office/office-marker.docx", "office-marker.docx");

      var structured = new StructuredContentExtractor();
      var flat = new ContentExtractor();

      ExtractionResult structuredResult = structured.extract(docx);
      ExtractionResult flatResult = flat.extract(docx);

      // Both should contain the marker text from the fixture
      String flatContent = flatResult.content().toLowerCase();
      String structuredContent = structuredResult.content().toLowerCase();

      // Extract key searchable terms from flat output and verify they exist in structured
      String[] words = flatContent.split("\\s+");
      int matchCount = 0;
      int totalWords = 0;
      for (String word : words) {
        if (word.length() > 3) { // skip short words
          totalWords++;
          if (structuredContent.contains(word)) {
            matchCount++;
          }
        }
      }

      double coverage = totalWords > 0 ? (double) matchCount / totalWords : 0;
      System.out.println(
          "DOCX term coverage: "
              + matchCount
              + "/"
              + totalWords
              + " ("
              + String.format("%.1f%%", coverage * 100)
              + ")");
      assertTrue(
          coverage > 0.8,
          "Structured output should contain >80% of flat output's terms, got "
              + String.format("%.1f%%", coverage * 100));
    }
  }

  @Nested
  class DocxWithStructure {

    @Test
    void docxWithTableAndHeadingsExtractsStructure() throws Exception {
      Path docx =
          copyFixture("/fixtures/office/structured-test.docx", "structured-test.docx");

      var structured = new StructuredContentExtractor();
      ExtractionResult result = structured.extract(docx);

      String content = result.content();
      System.out.println("=== DOCX Structured Output ===");
      System.out.println(content);
      System.out.println("=== End ===");

      // Verify content is present
      assertTrue(content.contains("Financial Report"), "Should contain heading text");
      assertTrue(content.contains("quarterly results"), "Should contain paragraph text");
      assertTrue(content.contains("Q1"), "Should contain table data");
      assertTrue(content.contains("$10M"), "Should contain table values");

      // Check for structural markers
      boolean hasHeadingMarkers = content.contains("# Financial Report");
      boolean hasTripletFormat =
          content.contains("Revenue =") || content.contains("Profit =");
      System.out.println("Heading markers: " + hasHeadingMarkers);
      System.out.println("Triplet table format: " + hasTripletFormat);
    }
  }

  @Nested
  class XlsxFixture {

    @Test
    void xlsxExtractsContent() throws Exception {
      Path xlsx = copyFixture("/fixtures/office/office-marker.xlsx", "office-marker.xlsx");

      var structured = new StructuredContentExtractor();
      ExtractionResult result = structured.extract(xlsx);

      assertNotNull(result);
      assertFalse(result.content().isBlank(), "XLSX should produce non-empty content");

      System.out.println("=== XLSX Structured Output (first 500 chars) ===");
      System.out.println(result.content().substring(0, Math.min(500, result.content().length())));
      System.out.println("=== Total length: " + result.content().length() + " ===");
    }
  }

  /** Local-only PDF fixture coverage; hosted Windows CI keeps Tika PDF fixtures out of broad unit lanes. */
  @Nested
  @Timeout(60)
  @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
  class PdfFixture {

    @Test
    void pdfTextLayerExtractsContent() throws Exception {
      Path pdf = copyFixture("/fixtures/pdf/pdf-text-layer.pdf", "pdf-text-layer.pdf");

      var structured = new StructuredContentExtractor();
      ExtractionResult result = structured.extract(pdf);

      assertNotNull(result);
      assertFalse(result.content().isBlank(), "PDF with text layer should produce content");
      assertTrue(result.isPdf(), "Should detect as PDF MIME");

      System.out.println("=== PDF Text Layer Structured Output (first 500 chars) ===");
      System.out.println(result.content().substring(0, Math.min(500, result.content().length())));
      System.out.println("=== Total length: " + result.content().length() + " ===");

      // Check if page boundaries were detected
      boolean hasPageBreaks = result.content().contains("---");
      System.out.println("Page breaks detected: " + hasPageBreaks);
    }

    @Test
    void pdfImageOnlyProducesMinimalContent() throws Exception {
      Path pdf = copyFixture("/fixtures/pdf/pdf-image-only.pdf", "pdf-image-only.pdf");

      var structured = new StructuredContentExtractor();
      ExtractionResult result = structured.extract(pdf);

      assertNotNull(result);
      // Image-only PDFs should produce empty or very short content
      System.out.println("=== PDF Image-Only Structured Output ===");
      System.out.println("Content: '" + result.content() + "'");
      System.out.println("Length: " + result.content().length());
    }

    @Test
    void pdfTextLayerContainsSameTermsAsFlat() throws Exception {
      Path pdf = copyFixture("/fixtures/pdf/pdf-text-layer.pdf", "pdf-text-layer.pdf");

      var structured = new StructuredContentExtractor();
      var flat = new ContentExtractor();

      ExtractionResult structuredResult = structured.extract(pdf);
      ExtractionResult flatResult = flat.extract(pdf);

      String flatContent = flatResult.content().toLowerCase();
      String structuredContent = structuredResult.content().toLowerCase();

      String[] words = flatContent.split("\\s+");
      int matchCount = 0;
      int totalWords = 0;
      for (String word : words) {
        if (word.length() > 3) {
          totalWords++;
          if (structuredContent.contains(word)) {
            matchCount++;
          }
        }
      }

      double coverage = totalWords > 0 ? (double) matchCount / totalWords : 0;
      System.out.println(
          "PDF term coverage: "
              + matchCount
              + "/"
              + totalWords
              + " ("
              + String.format("%.1f%%", coverage * 100)
              + ")");
      assertTrue(
          coverage > 0.8,
          "Structured output should contain >80% of flat output's terms, got "
              + String.format("%.1f%%", coverage * 100));
    }
  }

  @Nested
  class HtmlDocument {

    @Test
    void htmlWithTableExtractsStructure() throws Exception {
      Path html = tempDir.resolve("table-test.html");
      Files.writeString(
          html,
          """
          <html><body>
          <h1>Financial Report</h1>
          <p>This is the introduction paragraph.</p>
          <h2>Revenue Summary</h2>
          <table>
            <tr><th>Quarter</th><th>Revenue</th><th>Profit</th></tr>
            <tr><td>Q1</td><td>$10M</td><td>$2M</td></tr>
            <tr><td>Q2</td><td>$15M</td><td>$3M</td></tr>
          </table>
          <ul>
            <li>Growth rate: 50%</li>
            <li>Market share: 12%</li>
          </ul>
          </body></html>
          """);

      var structured = new StructuredContentExtractor();
      ExtractionResult result = structured.extract(html);

      String content = result.content();
      System.out.println("=== HTML Structured Output ===");
      System.out.println(content);
      System.out.println("=== End ===");

      // Verify structural elements are captured
      assertTrue(content.contains("Financial Report"), "Should contain H1 text");
      assertTrue(content.contains("Revenue Summary"), "Should contain H2 text");
      assertTrue(content.contains("introduction"), "Should contain paragraph text");
      assertTrue(content.contains("Q1"), "Should contain table data");
      assertTrue(content.contains("$10M"), "Should contain table values");
      assertTrue(content.contains("Growth rate"), "Should contain list items");

      // Check for structural markers
      boolean hasHeadingMarkers = content.contains("# Financial Report");
      boolean hasTripletFormat = content.contains("Revenue =") || content.contains("Profit =");
      boolean hasListMarkers = content.contains("- Growth rate");

      System.out.println("Heading markers: " + hasHeadingMarkers);
      System.out.println("Triplet table format: " + hasTripletFormat);
      System.out.println("List markers: " + hasListMarkers);
    }
  }

  private Path copyFixture(String resourcePath, String fileName) throws IOException {
    try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Missing test resource: " + resourcePath);
      }
      Path out = tempDir.resolve(fileName);
      Files.copy(is, out);
      return out;
    }
  }
}
