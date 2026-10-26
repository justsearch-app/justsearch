/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.ExtractionDropoutPolicy;
import io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor;
import io.justsearch.indexerworker.extract.OcrRoutingConfig;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Document-level check of the dropout chain on real PDF bytes (tempdoc 790 verification tier b):
 * the image-only fixture is the same failure shape as the 126 empty-extraction documents in
 * {@code ohr-bench-tika-pdf} — a page whose text layer yields nothing.
 *
 * <p>Runs the production extractor, not a hand-made {@code ExtractionResult}, so the routing
 * decision is the one production would make. The VLM tier's OUTPUT is not asserted here: that
 * requires the local llama/VDU runtime. What is asserted is that the document reaches the VLM
 * tier — the routing decision this work exists to fix.
 *
 * <p><b>Disabled in CI</b> for the same reason as {@code VduEligibilityPdfFixturesTest}: Tika
 * cold-start exceeds the timeout on Windows CI runners.
 */
@DisplayName("Extraction dropout chain (PDF fixtures)")
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
final class ExtractionDropoutPdfFixturesTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("image-only PDF: dropout detected, VLM tier reached, honest pending marker written")
  @Timeout(60)
  void imageOnlyPdfReachesTheVlmTier() throws Exception {
    Path pdf = copyFixture("/fixtures/pdf/pdf-image-only.pdf", "pdf-image-only.pdf");

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory()).extractArtifact(pdf);
    ExtractionResult extraction = artifact.result();
    assertNotNull(extraction);
    assertTrue(
        ExtractionDropoutPolicy.isDropout(extraction.content()),
        "fixture must reproduce the empty-extraction shape, got "
            + (extraction.content() == null ? "null" : extraction.content().length() + " chars"));

    IndexDocument doc = TestDocumentBuilder.buildDocument(pdf, extraction);

    assertEquals(
        SchemaFields.VDU_STATUS_PENDING,
        doc.fields().get(SchemaFields.VDU_STATUS),
        "a dropout must be queued for the next tier");
    assertEquals(
        SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT, doc.fields().get(SchemaFields.VDU_DEMAND_KIND));
    assertEquals(
        IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK,
        doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
  }

  @Test
  @DisplayName("text-layer PDF: no dropout, no marker, no fallback tier queued")
  @Timeout(60)
  void textLayerPdfIsUntouched() throws Exception {
    Path pdf = copyFixture("/fixtures/pdf/pdf-text-layer.pdf", "pdf-text-layer.pdf");

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory()).extractArtifact(pdf);
    ExtractionResult extraction = artifact.result();
    assertFalse(ExtractionDropoutPolicy.isDropout(extraction.content()));

    IndexDocument doc = TestDocumentBuilder.buildDocument(pdf, extraction);

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, doc.fields().get(SchemaFields.VDU_STATUS));
    assertNull(doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
    assertEquals(
        SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
        doc.fields().get(SchemaFields.EXTRACTION_METHOD));
  }

  private Path copyFixture(String resourcePath, String fileName) throws IOException {
    try (InputStream is = ExtractionDropoutPdfFixturesTest.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Missing test resource: " + resourcePath);
      }
      Path out = tempDir.resolve(fileName);
      Files.copy(is, out);
      return out;
    }
  }

  @Test
  @Timeout(60)
  void supplementaryPdfTextIsExtractedBeforeQualityAndRoutingAreMeasured() throws Exception {
    Path pdf = tempDir.resolve("supplementary.pdf");
    // Explicit ToUnicode mappings avoid installed fonts, downloads and an OCR runtime.
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      var cmap = document.getDocument().createCOSStream();
      try (var output = cmap.createOutputStream()) {
        output.write(
            ("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                    + "/CMapName /Supplementary def\n/CMapType 2 def\n"
                    + "1 begincodespacerange\n<00> <FF>\nendcodespacerange\n"
                    + "2 beginbfchar\n<41> <D840DC00>\n<42> <D801DC00>\nendbfchar\n"
                    + "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
                .getBytes(StandardCharsets.US_ASCII));
      }
      font.getCOSObject().setItem(COSName.TO_UNICODE, cmap);
      try (var stream = new PDPageContentStream(document, page)) {
        stream.beginText();
        stream.setFont(font, 6);
        stream.newLineAtOffset(40, 700);
        stream.showText("AB".repeat(60));
        stream.endText();
      }
      document.save(pdf.toFile());
    }
    byte[] originalBytes = Files.readAllBytes(pdf);
    String expectedText = "\uD840\uDC00\uD801\uDC00".repeat(60);
    try (var parsed = Loader.loadPDF(pdf.toFile())) {
      assertEquals(1, parsed.getNumberOfPages(), "the physical PDF has exactly one page");
      assertEquals(
          expectedText,
          new PDFTextStripper().getText(parsed).strip(),
          "PDFBox must decode the supplementary text independently of quality scoring");
    }
    var policy = TikaExtractionPolicy.defaults();
    try (var extractor =
        new PolicyDrivenTikaExtractor(
            io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(),
            policy,
            OcrRoutingConfig.disabled())) {
      ExtractionArtifact artifact = extractor.extractArtifact(pdf);
      String actualContent = artifact.result().content();
      assertEquals(
          expectedText,
          actualContent,
          "production Tika must preserve the complete sequence without a leading page separator");
      assertTrue(artifact.parserId().contains("structured"));
      var evidence = JsonMapper.builder().build().readTree(artifact.visualExtractionEvidenceJson());
      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              pdf,
              artifact.validate(policy, null),
              null, null, null, false, null, null,
              (stage, elapsed, reason) -> {},
              LoggerFactory.getLogger(ExtractionDropoutPdfFixturesTest.class),
              null, null, "supplementary-pdf");
      System.out.println("Supplementary PDF evidence=" + artifact.visualExtractionEvidenceJson()
          + " storedQuality=" + doc.fields().get(SchemaFields.EXTRACTION_QUALITY_SCORE)
          + " method=" + doc.fields().get(SchemaFields.EXTRACTION_METHOD)
          + " marker=" + doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
      // A readable physical page is sufficient without a phantom missing page or recovery demand.
      assertAll(
          () -> assertEquals(1, evidence.path("pageCount").asInt()),
          () -> assertEquals(0, evidence.path("pagesMissingReadableText").asInt()),
          () -> assertEquals("textual", evidence.path("ocrSkipReason").asText()),
          () -> assertEquals(240, evidence.path("textCharCount").asInt()),
          () -> assertEquals(240.0, evidence.path("charsPerPage").asDouble()),
          // All 120 supplementary code points are letters.
          () -> assertEquals(1.0, evidence.path("alphanumericRatio").asDouble()),
          () -> assertEquals(1.0, evidence.path("textQualityScore").asDouble()),
          () -> assertEquals(
              1.0, TextQualityAnalyzer.computeQualityScore(
                  actualContent, evidence.path("pageCount").asInt())),
          () -> assertEquals(1.0, TextQualityAnalyzer.computeQualityScore(actualContent)),
          () -> assertEquals(1.0f, doc.fields().get(SchemaFields.EXTRACTION_QUALITY_SCORE)),
          () -> assertFalse(ExtractionDropoutPolicy.isDropout(actualContent)),
          () -> assertEquals(
              SchemaFields.VDU_STATUS_NOT_NEEDED, doc.fields().get(SchemaFields.VDU_STATUS)),
          () -> assertNull(doc.fields().get(SchemaFields.VDU_DEMAND_KIND)),
          () -> assertEquals(
              SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
              doc.fields().get(SchemaFields.EXTRACTION_METHOD)),
          () -> assertNull(doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE)),
          () -> assertEquals(actualContent, doc.fields().get(SchemaFields.CONTENT)),
          () -> assertArrayEquals(originalBytes, Files.readAllBytes(pdf)));
    }
  }
}
