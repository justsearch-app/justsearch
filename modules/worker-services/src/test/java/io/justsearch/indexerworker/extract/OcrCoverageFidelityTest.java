/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class OcrCoverageFidelityTest {
  @TempDir Path tempDir;
  private static final String TEXT = "Readable searchable original OCR page content ".repeat(8);
  private static final OcrRoutingConfig CONFIG =
      new OcrRoutingConfig(true, List.of("eng"), 10_000, 10, null, null, 72, 2);

  @Test
  void fullPdfFailureRetainsTextAndSerializesActualCoverage() throws Exception {
    Path pdf = pdf(false);
    var result = extract(pdf, 10_000, command -> {
      if (page(command) == 1) throw new IOException("page opening failed");
      return success(command, TEXT);
    });
    assertTrue(result.result().content().contains(TEXT.strip()));
    assertEquals(ExtractionStatus.SUCCESS_PARTIAL, result.status());
    assertTrue(result.truncated());
    assertCoverage(result, 2, 1, 1);
    var evidence = JsonMapper.builder().build().readTree(result.visualExtractionEvidenceJson());
    assertFalse(evidence.get("mixedPdf").asBoolean(), "partial scans are not mixed digital/scan PDFs");
    assertEquals(0.99, evidence.get("ocrMeanConfidence").asDouble());
    var doc = io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.buildDocument(
        pdf, result.validate(TikaExtractionPolicy.fromWorkerLimits(
            new io.justsearch.configuration.resolved.ResolvedConfig.Worker(10_000, 1_000_000L)), null),
        null, null, null, false, null, null, (stage, ms, reason) -> {},
        org.slf4j.LoggerFactory.getLogger(OcrCoverageFidelityTest.class), null, null, "coverage-test");
    assertEquals(io.justsearch.indexing.SchemaFields.VDU_STATUS_PENDING,
        doc.fields().get(io.justsearch.indexing.SchemaFields.VDU_STATUS));
    assertEquals(io.justsearch.indexing.SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT,
        doc.fields().get(io.justsearch.indexing.SchemaFields.VDU_DEMAND_KIND));
    var wire = SandboxExtractionResponse.fromArtifact("coverage", result);
    var mapper = JsonMapper.builder().build();
    var restored = mapper.readValue(mapper.writeValueAsBytes(wire), SandboxExtractionResponse.class).toArtifact();
    assertEquals(result, restored);
  }

  @Test
  void selectivePdfUnionsOnlyReadableBaselineAndRetainedOcrPages() throws Exception {
    Path pdf = pdf(true);
    var seen = new java.util.concurrent.ConcurrentSkipListSet<Integer>();
    var result = extract(pdf, 10_000, command -> {
      int page = page(command);
      seen.add(page);
      if (page == 3) throw new IOException("failed page");
      return success(command, page == 1 ? TEXT : "");
    });
    assertEquals(java.util.Set.of(1, 3, 4), seen);
    assertEquals(ExtractionStatus.SUCCESS_PARTIAL, result.status());
    assertCoverage(result, 5, 3, 2);
  }

  @Test
  void characterCapDoesNotClaimUnretainedPages() throws Exception {
    var result = extract(pdf(false), 80, command -> success(command, TEXT));
    assertTrue(result.result().content().length() <= 80);
    assertTrue(result.truncated());
    assertCoverage(result, 2, 1, 1);
  }

  @Test
  void shortPageTextRetainedWithoutItsTrailingNewlineStillCountsAsCovered() throws Exception {
    String text = "Short title";
    Path source = pdf(false);
    int baselineChars;
    try (var baseline = new StructuredContentExtractor()) {
      baselineChars = baseline.extract(source).content().stripTrailing().length();
    }
    int cap = baselineChars + "\n\n--- OCR page 1 ---\n".length() + text.length();
    var result = extract(source, cap, command -> success(command, text));
    assertTrue(result.result().content().endsWith(text));
    assertTrue(result.truncated());
    assertCoverage(result, 2, 1, 1);
  }

  @Test
  void completeShortOcrStillCountsEveryCompletedPage() throws Exception {
    var result = extract(pdf(false), 10_000, command -> success(command, "Short title"));
    assertEquals(ExtractionStatus.SUCCESS_FULL, result.status());
    assertFalse(result.truncated());
    assertCoverage(result, 2, 2, 0);
  }

  @Test
  void completeOcrStillReportsFullCoverage() throws Exception {
    var result = extract(pdf(false), 10_000, command -> success(command, TEXT));
    assertEquals(ExtractionStatus.SUCCESS_FULL, result.status());
    assertFalse(result.truncated());
    assertCoverage(result, 2, 2, 0);
  }

  @Test
  void selectiveOcrKeepsBaselineTruncationAndCountsOnlyRetainedPages() throws Exception {
    Path source = tempDir.resolve("limited-selective.pdf");
    String header = "Repeated page header ".repeat(8);
    try (var document = new PDDocument()) {
      for (int page = 0; page < 5; page++) {
        var item = new PDPage(new PDRectangle(1200, 200));
        document.addPage(item);
        if (page == 4) continue;
        try (var content = new PDPageContentStream(document, item)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 5);
          content.newLineAtOffset(5, 150);
          content.showText(header);
          content.newLineAtOffset(0, -50);
          content.showText("Unique digital body for page " + page + " with searchable content retained.");
          content.endText();
        }
      }
      document.save(source.toFile());
    }
    int cap = 3 * (header.length() + 75);
    try (var baseline = new StructuredContentExtractor(cap)) {
      var result = baseline.extractWithStatus(source);
      assertTrue(result.truncated());
      assertFalse(result.result().content().contains("Unique digital body for page 3"));
      assertTrue(result.result().content().length() + TEXT.length() + 30 < cap,
          "header removal must leave room for successful selective OCR");
    }
    var result = extract(source, cap, command -> success(command, TEXT));
    assertTrue(result.result().content().contains(TEXT.strip()));
    assertEquals(ExtractionStatus.SUCCESS_PARTIAL, result.status());
    assertTrue(result.truncated());
    assertCoverage(result, 5, 4, 1);
    var mapper = JsonMapper.builder().build();
    var wire = SandboxExtractionResponse.fromArtifact("baseline-cap", result);
    var restored = mapper.readValue(mapper.writeValueAsBytes(wire), SandboxExtractionResponse.class).toArtifact();
    assertEquals(result, restored);
    var policy = TikaExtractionPolicy.fromWorkerLimits(
        new io.justsearch.configuration.resolved.ResolvedConfig.Worker(cap, 1_000_000L));
    var doc = io.justsearch.indexerworker.loop.ops.IndexingDocumentOps.buildDocument(
        source, restored.validate(policy, null), null, null, null, false, null, null,
        (stage, ms, reason) -> {}, org.slf4j.LoggerFactory.getLogger(getClass()), null, null, "baseline-cap");
    assertEquals(io.justsearch.indexing.SchemaFields.VDU_STATUS_PENDING,
        doc.fields().get(io.justsearch.indexing.SchemaFields.VDU_STATUS));
    var storedEvidence = mapper.readTree((String) doc.fields().get(
        io.justsearch.indexing.SchemaFields.VISUAL_EXTRACTION_EVIDENCE));
    assertTrue(storedEvidence.get("contentTruncated").asBoolean());
    assertEquals(1, storedEvidence.get("pagesMissingReadableText").asInt());
  }

  private ExtractionArtifact extract(Path pdf, int maxChars, PdfOcrEngine.ProcessStarter starter)
      throws Exception {
    var policy = TikaExtractionPolicy.fromWorkerLimits(
        new io.justsearch.configuration.resolved.ResolvedConfig.Worker(maxChars, 1_000_000L));
    try (var runtime = mockStatic(TikaOcrRuntime.class);
        var extractor = new PolicyDrivenTikaExtractor(ignored -> {
          throw new AssertionError("only the fixture pool may open");
        }, policy, CONFIG)) {
      runtime.when(() -> TikaOcrRuntime.blockedReason(CONFIG)).thenReturn("");
      var engine = new PdfOcrEngine(Executors::newFixedThreadPool, CONFIG, starter, "fixture", 72, 2, null);
      var field = PolicyDrivenTikaExtractor.class.getDeclaredField("ocrEngine");
      field.setAccessible(true);
      field.set(extractor, engine);
      return extractor.extractArtifact(pdf);
    }
  }

  private static Process success(List<String> command, String text) throws IOException {
    Path base = Path.of(command.get(2));
    Files.writeString(Path.of(base + ".txt"), text);
    Files.writeString(Path.of(base + ".tsv"),
        "level\tpage\tblock\tpar\tline\tword\tleft\ttop\twidth\theight\tconf\ttext\n"
            + "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t99\tReadable\n");
    Process process = mock(Process.class);
    try {
      when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
    } catch (InterruptedException impossible) { throw new AssertionError(impossible); }
    return process;
  }

  private static int page(List<String> command) {
    String name = Path.of(command.get(1)).getFileName().toString();
    return Integer.parseInt(name.substring(5, name.length() - 4));
  }

  private Path pdf(boolean selective) throws IOException {
    Path path = tempDir.resolve(selective ? "selective.pdf" : "full.pdf");
    try (var document = new PDDocument()) {
      for (int page = 0; page < (selective ? 5 : 2); page++) {
        var item = new PDPage(new PDRectangle(200, 100));
        document.addPage(item);
        if (selective && (page == 0 || page == 2)) {
          try (var content = new PDPageContentStream(document, item)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 5);
            content.newLineAtOffset(5, 50);
            content.showText("Readable baseline content for this digital page ".repeat(2));
            content.endText();
          }
        }
      }
      document.save(path.toFile());
    }
    return path;
  }

  private static void assertCoverage(ExtractionArtifact artifact, int pages, int readable, int missing) {
    var evidence = JsonMapper.builder().build().readTree(artifact.visualExtractionEvidenceJson());
    assertEquals(pages, evidence.get("pageCount").asInt());
    assertEquals(readable, evidence.get("pagesWithTextLayer").asInt());
    assertEquals(missing, evidence.get("pagesMissingReadableText").asInt());
    if (missing > 0) assertTrue(evidence.get("contentTruncated").asBoolean());
  }
}
