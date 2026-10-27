/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.extraction.StructuredDocument;
import io.justsearch.indexing.extraction.StructuredDocument.PageBreak;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** Physical PDF pages must agree before and after PDFBox evidence enrichment. */
final class StructuredPdfPageInferenceTest {
  private static final int MAX_CHARS = 100_000;
  private static final List<String> TEXT = List.of(
      "Alpha readable digital content describes the orchard and its flowering apple trees.",
      "Bravo readable digital content explains the river and its surrounding green meadows.",
      "Charlie readable digital content records the mountain and its snowy northern slopes.",
      "Delta readable digital content follows the coastline and its many sheltered harbours.",
      "Echo readable digital content explores the forest and its remarkable ancient wildlife.").stream().map(text -> text + " This physical page retains its complete readable text layer.").toList();

  @TempDir Path tempDir;

  static Stream<String> physicalPages() {
    // T = readable text; B = empty wrapper; I = embedded image without a text layer.
    return Stream.of("T", "TTT", "BT", "TB", "TBT", "TBBT", "BB", "B",
        "IT", "TI", "TIT", "TIIT", "II", "I");
  }

  @ParameterizedTest(name = "physical pages {0}")
  @MethodSource("physicalPages")
  void physicalPagesAndMissingIndicesAgreeBeforeEnrichment(String pages) throws Exception {
    Path pdf = writePdf(pages);
    StructuredDocument document = parsePdf(pdf);
    var expectedMissing = new HashSet<Integer>();
    var expectedImages = new HashSet<Integer>();
    var actualReadable = new HashSet<Integer>();
    StringBuilder expectedText = new StringBuilder();
    for (int i = 0; i < pages.length(); i++) {
      if (i > 0) expectedText.append("---\n\n");
      if (pages.charAt(i) == 'T') {
        expectedText.append(TEXT.get(i)).append("\n\n");
      } else {
        expectedMissing.add(i);
        if (pages.charAt(i) == 'I') expectedImages.add(i);
      }
    }
    assertAll(
        () -> assertEquals(pages.length(), document.pageCount()),
        () -> assertEquals(pages.length() - 1,
            document.elements().stream().filter(PageBreak.class::isInstance).count()),
        () -> assertEquals(expectedText.toString().strip(), document.toAnnotatedText()));
    int page = 0;
    for (var element : document.elements()) {
      if (element instanceof PageBreak) page++;
      assertEquals(page, element.pageIndex(), "boundary belongs to its destination physical page");
      if (!(element instanceof PageBreak)) {
        assertEquals('T', pages.charAt(element.pageIndex()));
        actualReadable.add(element.pageIndex());
      }
    }
    var actualMissing = new HashSet<Integer>();
    for (int i = 0; i < pages.length(); i++) {
      if (!actualReadable.contains(i)) actualMissing.add(i);
    }
    assertEquals(expectedMissing, actualMissing);
    var signals = PdfVisualAnalyzer.analyze(pdf);
    assertNotNull(signals);
    assertEquals(expectedMissing, signals.missingReadableTextPages());
    assertEquals(actualReadable, signals.readablePages());
    assertEquals(expectedImages, signals.imagePages());
    var summary = StructuredDocumentSummary.fromDocument(document);
    assertEquals(pages.length(), summary.pageCount());
    assertEquals(expectedMissing.size(), summary.pagesMissingReadableText());
    try (var extractor = new StructuredContentExtractor(MAX_CHARS)) {
      var structured = extractor.extractWithStatus(pdf);
      assertEquals(document.toAnnotatedText(), structured.result().content());
      assertEquals(summary, structured.summary());
    }
    var enriched = PdfVisualAnalyzer.enrich(pdf, summary);
    assertEquals(summary.pageCount(), enriched.pageCount());
    assertEquals(summary.pagesMissingReadableText(), enriched.pagesMissingReadableText());
    assertEquals(summary.pagesWithReadableText(), enriched.pagesWithReadableText());

    try (var extractor = new PolicyDrivenTikaExtractor(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(),
        TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled())) {
      var artifact = extractor.extractArtifact(pdf);
      var evidence = JsonMapper.builder().build().readTree(artifact.visualExtractionEvidenceJson());
      assertEquals(document.toAnnotatedText(), artifact.result().content());
      assertEquals(pages.length(), evidence.path("pageCount").asInt());
      assertEquals(expectedMissing.size(), evidence.path("pagesMissingReadableText").asInt());
      assertEquals(expectedMissing.isEmpty() ? "textual" : "disabled",
          evidence.path("ocrSkipReason").asText());
      var indexed = IndexingDocumentOps.buildDocument(pdf, artifact.validate(TikaExtractionPolicy.defaults(), null), null, null, null, false,
          null, null, (stage, elapsed, reason) -> {},
          LoggerFactory.getLogger(StructuredPdfPageInferenceTest.class), null, null, "physical-pages");
      assertEquals(SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
          indexed.fields().get(SchemaFields.EXTRACTION_METHOD));
      assertEquals(expectedMissing.isEmpty() ? SchemaFields.VDU_STATUS_NOT_NEEDED
          : SchemaFields.VDU_STATUS_PENDING, indexed.fields().get(SchemaFields.VDU_STATUS));
      assertEquals(actualReadable.isEmpty(), ExtractionDropoutPolicy.isDropout(artifact.result().content()));
      if (actualReadable.isEmpty()) {
        assertNotNull(indexed.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
      } else {
        assertNull(indexed.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
      }
    }
  }

  @Test
  void wrapperFreePlainTextAndHtmlKeepTextAndUnknownPageCount() throws Exception {
    try (var extractor = new StructuredContentExtractor(MAX_CHARS)) {
      Path plain = tempDir.resolve("plain.txt");
      Files.writeString(plain, "Plain text remains intact.");
      var text = extractor.extractWithStatus(plain);
      assertEquals("Plain text remains intact.", text.result().content());
      assertEquals(0, text.pageCount());
      Path html = tempDir.resolve("plain.html");
      Files.writeString(html, "<html><body><h1>Title</h1><p>Body text.</p></body></html>");
      var web = extractor.extractWithStatus(html);
      assertEquals("# Title\n\nBody text.", web.result().content());
      assertEquals(0, web.pageCount());
    }
  }

  private Path writePdf(String pages) throws Exception {
    Path pdf = tempDir.resolve(pages + ".pdf");
    try (var document = new PDDocument()) {
      for (int i = 0; i < pages.length(); i++) {
        var page = new PDPage();
        document.addPage(page);
        if (pages.charAt(i) == 'B') continue;
        try (var content = new PDPageContentStream(document, page)) {
          if (pages.charAt(i) == 'T') {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
            content.newLineAtOffset(40, 700);
            content.showText(TEXT.get(i));
            content.endText();
          } else {
            var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            content.drawImage(LosslessFactory.createFromImage(document, image), 40, 500, 100, 100);
          }
        }
      }
      document.save(pdf.toFile());
    }
    return pdf;
  }

  private static StructuredDocument parsePdf(Path file) throws Exception {
    var handler = new StructuredContentHandler(MAX_CHARS);
    var context = new ParseContext();
    Class<?> configClass = Class.forName("org.apache.tika.parser.pdf.PDFParserConfig");
    Object config = configClass.getDeclaredConstructor().newInstance();
    configClass.getMethod("setExtractMarkedContent", boolean.class).invoke(config, true);
    context.getClass().getMethod("set", Class.class, Object.class).invoke(context, configClass, config);
    var parser = OwnedTikaParsers.create(TextNameMagicConflictDetector.wrapDefault());
    try (var input = Files.newInputStream(file)) {
      parser.parse(input, handler, new Metadata(), context);
    }
    return handler.getDocument();
  }
}
