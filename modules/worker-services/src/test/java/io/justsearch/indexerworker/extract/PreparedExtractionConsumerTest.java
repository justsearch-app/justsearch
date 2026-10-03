/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedExtractionConsumerTest {
  @TempDir Path tempDir;

  @Test
  void pdfEvidenceKeepsSpilledSnapshotsFileBackedWithoutReopeningSource() throws Exception {
    for (boolean spilled : new boolean[] {false, true}) {
      byte[] original = spilled ? largePdf() : pdf();
      Path source = Files.write(tempDir.resolve(spilled ? "large.pdf" : "small.pdf"), original);
      try (var input = PreparedExtractionInput.prepare(source, TikaExtractionPolicy.defaults())) {
        var observed = org.mockito.Mockito.spy(input);
        assertEquals(spilled, observed.isDiskBacked());
        if (spilled) {
          assertTrue(original.length > PreparedExtractionInput.MAX_IN_MEMORY_BYTES);
          org.mockito.Mockito.doThrow(new AssertionError("Spilled PDF must use its snapshot file"))
              .when(observed).openStream();
        } else {
          assertTrue(original.length <= PreparedExtractionInput.MAX_IN_MEMORY_BYTES);
        }
        Files.delete(source);
        var summary = PdfVisualAnalyzer.enrich(observed, StructuredDocumentSummary.empty());
        assertEquals(1, summary.pageCount(), "Evidence must come from the captured PDF");
        org.mockito.Mockito.verify(observed,
            spilled ? org.mockito.Mockito.never() : org.mockito.Mockito.times(1)).openStream();
      }
    }
  }

  @Test
  void fullPolicyPathAndFlatFallbackPrepareOnlyOnce() throws Exception {
    Path file = Files.write(tempDir.resolve("document.zip"), zip("original searchable text"));
    var factory = new ObservedFactory();
    try (var independentFactories = org.mockito.Mockito.mockConstruction(
            PreparedExtractionInput.Factory.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
        var extractor = extractor(TikaExtractionPolicy.defaults(), factory)) {
      var structuredField = PolicyDrivenTikaExtractor.class.getDeclaredField("structuredExtractor");
      structuredField.setAccessible(true);
      Object structured = structuredField.get(extractor);
      var parser = org.mockito.Mockito.mock(org.apache.tika.parser.AutoDetectParser.class);
      org.mockito.Mockito.doThrow(new org.xml.sax.SAXException("force flat fallback"))
          .when(parser).parse(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
      var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
      parserField.setAccessible(true);
      parserField.set(structured, parser);
      assertTrue(extractor.extract(file).content().contains("original searchable text"));
      assertEquals(1, factory.preparations);
      assertEquals(1, factory.snapshots.size());
      assertFalse(Files.exists(factory.snapshots.getFirst()));
      assertEquals(1, independentFactories.constructed().size(), "Exercise the flat fallback factory");
      assertNoIndependentPreparations(independentFactories);
    }
    assertFalse(Files.exists(factory.snapshots.getFirst().getParent()));
  }

  @Test
  void preparationGuardRejectsTheIndependentPublicFallbackMutation() throws Exception {
    Path source = Files.write(tempDir.resolve("mutation.zip"), zip("original searchable text"));
    try (var input = PreparedExtractionInput.prepare(source, TikaExtractionPolicy.defaults());
        var independentFactories = org.mockito.Mockito.mockConstruction(
            PreparedExtractionInput.Factory.class,
            org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS))) {
      // Reproduce the reviewer's mutation: the public Path entry point prepares again through
      // a separately constructed default factory. It still extracts the expected text.
      try (var fallback = new ContentExtractor(100_000)) {
        assertTrue(fallback.extract(input.file()).content().contains("original searchable text"));
      }
      assertEquals(1, independentFactories.constructed().size());
      assertThrows(AssertionError.class, () -> assertNoIndependentPreparations(independentFactories));
    }
  }

  private PolicyDrivenTikaExtractor extractor(TikaExtractionPolicy policy, ObservedFactory factory) {
    return new PolicyDrivenTikaExtractor(workers -> { throw new AssertionError("OCR disabled"); },
        policy, OcrRoutingConfig.disabled(), OcrMetricCatalog.noop(),
        ExtractionFallbackBudget.defaults(), factory);
  }

  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

  private static void assertNoIndependentPreparations(
      org.mockito.MockedConstruction<PreparedExtractionInput.Factory> factories)
      throws IOException, ContentExtractor.ExtractionException {
    for (var factory : factories.constructed()) {
      org.mockito.Mockito.verify(factory, org.mockito.Mockito.never()).prepare(
          org.mockito.ArgumentMatchers.any(Path.class),
          org.mockito.ArgumentMatchers.any(TikaExtractionPolicy.class));
    }
  }

  private static byte[] largePdf() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      var padding = new org.apache.pdfbox.pdmodel.common.PDStream(document);
      try (var output = padding.createOutputStream()) {
        // No compression filter: retain a valid PDF with a content stream above the spill cap.
        output.write(bytes(" ".repeat(PreparedExtractionInput.MAX_IN_MEMORY_BYTES + 1)));
      }
      page.setContents(padding);
      document.save(bytes);
    }
    return bytes.toByteArray();
  }

  private static byte[] pdf() throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 700);
        content.showText("searchable ordinary PDF text");
        content.endText();
      }
      document.save(bytes);
    }
    return bytes.toByteArray();
  }

  private static byte[] zip(String text) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("content.txt"));
      zip.write(bytes(text));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static class ObservedFactory extends PreparedExtractionInput.Factory {
    int preparations;
    final List<Path> snapshots = new ArrayList<>();

    @Override
    PreparedExtractionInput prepare(Path source, TikaExtractionPolicy policy)
        throws IOException, ContentExtractor.ExtractionException {
      preparations++;
      return super.prepare(source, policy);
    }

    @Override
    synchronized Path createSnapshot(Path source) throws IOException {
      Path snapshot = super.createSnapshot(source);
      snapshots.add(snapshot);
      return snapshot;
    }
  }
}
