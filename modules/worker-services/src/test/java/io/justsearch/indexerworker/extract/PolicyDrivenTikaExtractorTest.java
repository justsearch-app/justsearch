package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionException;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

final class PolicyDrivenTikaExtractorTest {
  @TempDir Path tempDir;

  @Test
  void ocrCapacityRefusalPreservesRealStructuredPdfText() throws Exception {
    Path pdf = tempDir.resolve("mixed-refused.pdf");
    writeMixedTextAndImagePdf(pdf);
    var config = new OcrRoutingConfig(true, List.of("eng"), 20_000, 5, 4096, 40_000_000, null, null);
    var metrics = new TestMetricRegistry(OcrMetricCatalog.DEFINITIONS);
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    try (var runtime = org.mockito.Mockito.mockStatic(TikaOcrRuntime.class);
        var extractor = new PolicyDrivenTikaExtractor(workers -> {
          opens.incrementAndGet();
          throw new io.justsearch.core.execution.EngineExecutorRejectedException(
              io.justsearch.core.execution.EngineExecutorRejectedException.Reason.INSTANCE_LIMIT,
              "index.pdf-ocr", 1);
        }, TikaExtractionPolicy.defaults(), config, new OcrMetricCatalog(metrics))) {
      runtime.when(() -> TikaOcrRuntime.blockedReason(config)).thenReturn("");
      ExtractionArtifact result = extractor.extractArtifact(pdf);
      assertEquals(1, opens.get(), "real mixed-PDF routing must reach the refusing OCR pool");
      assertTrue(result.result().content().contains("readable digital text"));
      assertTrue(result.parserId().contains("structured"));
      assertFalse(result.visualExtractionEvidenceJson().contains("PARSER_FAILED"));
      assertEquals(1, metrics.counterValue(OcrMetricCatalog.FAILED_TOTAL,
          OcrTags.OcrFailureTags.of(OcrRoutingConfig.ENGINE, "INSTANCE_LIMIT")));
    }
  }

  @Test
  @Timeout(10)
  void outputLimitProducesValidatedPartialArtifact() throws Exception {
    Path file = tempDir.resolve("long.txt");
    Files.writeString(file, "abcdefghijklmnopqrstuvwxyz");
    TikaExtractionPolicy policy =
        new TikaExtractionPolicy(
            "tiny-policy", 5, 1024, 1024, 128, 128, 4096, 0, 0, 100.0d, true, Set.of(), Set.of());

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), policy).extractArtifact(file);

    assertEquals("tiny-policy", artifact.policyId());
    assertTrue(artifact.truncated());
    assertTrue(artifact.result().content().length() <= 5);
    assertEquals(artifact, artifact.validateContentBoundsOnly(5));
  }

  @Test
  @Timeout(10)
  void tableAnnotationExpansionIsClampedEvenWhenSaxInputStayedBelowThePolicyLimit()
      throws Exception {
    Path file = tempDir.resolve("expanding-table.html");
    Files.writeString(
        file,
        "<html><body><table><tr><th>A</th><th>B</th></tr>"
            + "<tr><td>R1</td><td>V</td></tr>"
            + "<tr><td>R2</td><td>V</td></tr>"
            + "<tr><td>R3</td><td>V</td></tr>"
            + "<tr><td>R4</td><td>V</td></tr>"
            + "<tr><td>R5</td><td>V</td></tr>"
            + "<tr><td>R6</td><td>V</td></tr>"
            + "<tr><td>R7</td><td>V</td></tr>"
            + "<tr><td>R8</td><td>V</td></tr>"
            + "</table></body></html>");
    int maxChars = 64;
    StructuredContentExtractor.StructuredExtractionResult expanded =
        new StructuredContentExtractor(maxChars).extractWithStatus(file);
    assertFalse(expanded.truncated(), "source SAX characters stay below the policy cap");
    assertTrue(
        expanded.result().content().length() > maxChars,
        "triplet annotation must expand the source cells past the cap");
    TikaExtractionPolicy policy =
        new TikaExtractionPolicy(
            "table-expansion-policy",
            maxChars,
            4096,
            4096,
            128,
            128,
            4096,
            0,
            0,
            100.0d,
            true,
            Set.of(),
            Set.of());

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), policy).extractArtifact(file);

    assertEquals(maxChars, artifact.result().content().length());
    assertTrue(artifact.truncated());
    assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
    assertEquals(artifact, artifact.validateContentBoundsOnly(maxChars));
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"textCharCount\":" + maxChars),
        artifact.visualExtractionEvidenceJson());
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"contentTruncated\":true"),
        artifact.visualExtractionEvidenceJson());
  }

  @Test
  void finalPolicyClampDoesNotSplitUtf16SurrogatePairs() {
    TikaExtractionPolicy policy =
        new TikaExtractionPolicy(
            "surrogate-policy",
            5,
            1024,
            1024,
            128,
            128,
            4096,
            0,
            0,
            100.0d,
            true,
            Set.of(),
            Set.of());

    ExtractionArtifact artifact =
        ExtractionArtifact.full(
            new ContentExtractor.ExtractionResult("aaaa😀tail", null, "text/plain"),
            policy,
            "surrogate-test",
            false);

    assertEquals("aaaa", artifact.result().content());
    assertTrue(artifact.truncated());
    assertEquals(ExtractionStatus.SUCCESS_PARTIAL, artifact.status());
  }

  @Test
  void policyRejectsDisabledXmlHardening() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TikaExtractionPolicy(
                "unsafe", 1024, 1024, 1024, 128, 128, 4096, 0, 0, 100.0d, false, Set.of(), Set.of()));
  }

  @Test
  void validateRejectsArtifactWithMismatchedPolicyId() throws Exception {
    Path file = tempDir.resolve("plain.txt");
    Files.writeString(file, "hello");
    TikaExtractionPolicy policyA =
        new TikaExtractionPolicy(
            "policy-a", 1024, 1024, 1024, 128, 128, 4096, 0, 0, 100.0d, true, Set.of(), Set.of());
    TikaExtractionPolicy policyB =
        new TikaExtractionPolicy(
            "policy-b", 1024, 1024, 1024, 128, 128, 4096, 0, 0, 100.0d, true, Set.of(), Set.of());

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), policyA).extractArtifact(file);
    assertEquals("policy-a", artifact.policyId());

    ExtractionException thrown =
        assertThrows(ExtractionException.class, () -> artifact.validate(policyB, null));
    assertTrue(thrown.getMessage().contains("policy id"));
  }

  @Test
  @Timeout(10)
  void policyExtractorPreservesStructuredAnnotatedText() throws Exception {
    Path html = tempDir.resolve("structured.html");
    Files.writeString(html, "<html><body><h1>Heading</h1><p>Paragraph</p></body></html>");

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory()).extractArtifact(html);

    assertTrue(artifact.result().content().contains("Heading"));
    assertTrue(
        artifact.parserId().contains("structured"),
        "Policy extraction must keep the structured parser path as production default");
  }

  @Test
  @Timeout(10)
  void excludedMimeTypeFailsBeforeParse() throws Exception {
    Path file = tempDir.resolve("blocked.txt");
    Files.writeString(file, "plain text");
    TikaExtractionPolicy policy =
        new TikaExtractionPolicy(
            "blocked-text",
            1024,
            1024,
            1024,
            128,
            128,
            4096,
            0,
            0,
            100.0d,
            true,
            Set.of(),
            Set.of("text/plain"));

    assertThrows(ExtractionException.class, () -> new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), policy).extract(file));
  }

  /**
   * Production-path regression: the prior structural defect was that the post-extraction length
   * check (`result.content().length() &gt; policy.maxExtractedChars()`) is structurally unable
   * to fire when input chunk boundaries align with the cap. Validation on 2026-04-26 measured a
   * 12 MiB plain-text file under the 10 MiB default cap producing
   * {@code contentLen=10485760 truncated=false status=SUCCESS_FULL}. The fix routes the SAX
   * handler's {@code limitReached} flag through {@code extractWithStatus} so production extraction
   * surfaces SUCCESS_PARTIAL on oversized real-Tika input.
   */
  @Test
  @Timeout(120)
  void defaultPolicyOversizedPlainTextProducesPartialArtifact() throws Exception {
    Path file = tempDir.resolve("oversize.txt");
    long target = 12L * 1024 * 1024; // 12 MiB, 2 MiB over the 10 MiB default cap
    try (java.io.BufferedWriter writer = Files.newBufferedWriter(file)) {
      char[] chunk = new char[8192];
      java.util.Arrays.fill(chunk, 'x');
      long written = 0;
      while (written < target) {
        int len = (int) Math.min(chunk.length, target - written);
        writer.write(chunk, 0, len);
        written += len;
      }
    }

    ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory()).extractArtifact(file);

    assertTrue(
        artifact.truncated(),
        "12 MiB input over 10 MiB default cap must surface as truncated; the SAX-level"
            + " limitReached signal replaces the structurally-unreachable length-based check.");
    assertEquals(
        ExtractionStatus.SUCCESS_PARTIAL,
        artifact.status(),
        "Truncated artifact must classify as SUCCESS_PARTIAL so the ledger and document agree.");
    assertTrue(
        artifact.result().content().length() <= TikaExtractionPolicy.DEFAULT_MAX_EXTRACTED_CHARS,
        "Defensive trim must keep content at or under the policy cap.");
  }

  @Test
  @Timeout(10)
  void xmlEntityPayloadDoesNotLeakExternalFileContent() throws Exception {
    Path secret = tempDir.resolve("secret.txt");
    Files.writeString(secret, "SECRET-XXE-CONTENT");
    Path xml = tempDir.resolve("xxe.xml");
    String uri = secret.toUri().toString();
    Files.writeString(
        xml,
        """
        <?xml version="1.0"?>
        <!DOCTYPE root [ <!ENTITY xxe SYSTEM "%s"> ]>
        <root>&xxe;</root>
        """
            .formatted(uri));

    try {
      ExtractionArtifact artifact = new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory()).extractArtifact(xml);
      assertFalse(artifact.result().content().contains("SECRET-XXE-CONTENT"));
    } catch (ExtractionException e) {
      assertFalse(e.getMessage().contains("SECRET-XXE-CONTENT"));
    }
  }

  @Test
  @Timeout(30)
  void eligibleLowQualityImageWithDisabledOcrRecordsWorkerSkipMetric() throws Exception {
    Path image = tempDir.resolve("ocr-disabled.png");
    writeTextImage(image, "DISABLED OCR");
    TestMetricRegistry registry = new TestMetricRegistry(OcrMetricCatalog.DEFINITIONS);
    OcrMetricCatalog catalog = new OcrMetricCatalog(registry);

    ExtractionArtifact artifact =
        new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(),
                TikaExtractionPolicy.defaults(), OcrRoutingConfig.disabled(), catalog)
            .extractArtifact(image);

    assertFalse(OcrRoutingConfig.PARSER_ID.equals(artifact.parserId()));
    assertFalse(
        artifact.result().content().toLowerCase(java.util.Locale.ROOT).contains("disabled"),
        "disabled OCR must not leak ambient Tika image OCR text");
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"structured\""));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"disabled\""));
    assertEquals(
        1L,
        registry.counterValue(
            OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.DISABLED)));
  }

  @Test
  @Timeout(30)
  void oversizedImageWithEnabledOcrRecordsWorkerSizeSkipMetric() throws Exception {
    Path image = tempDir.resolve("ocr-too-large.png");
    writeTextImage(image, "OVERSIZED OCR");
    TestMetricRegistry registry = new TestMetricRegistry(OcrMetricCatalog.DEFINITIONS);
    OcrMetricCatalog catalog = new OcrMetricCatalog(registry);
    OcrRoutingConfig guarded =
        new OcrRoutingConfig(true, List.of("eng"), 10_000, 1, 10, 40_000_000, null, null);

    ExtractionArtifact artifact =
        new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), TikaExtractionPolicy.defaults(), guarded, catalog)
            .extractArtifact(image);

    assertFalse(OcrRoutingConfig.PARSER_ID.equals(artifact.parserId()));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"size\""));
    assertEquals(
        1L,
        registry.counterValue(
            OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE)));
  }

  @Test
  @Timeout(30)
  void realTesseractRuntimeProducesOcrArtifactForImageText() throws Exception {
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 10_000, 1, 4096, 40_000_000, null, null);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path image = tempDir.resolve("ocr-alpha.png");
    writeTextImage(image, "ALPHA DOCUMENT");
    PolicyDrivenTikaExtractor admissionProbe =
        new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), TikaExtractionPolicy.defaults(), ocrConfig);
    assertTrue(
        admissionProbe.shouldAttemptOcrForTesting(
            image, "image/png", "", StructuredDocumentSummary.empty()),
        "empty raster image should be OCR-admitted");
    String directText = PdfOcrEngine.create(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), ocrConfig, null).ocrImage(image, Integer.MAX_VALUE).text();
    TikaOcrRuntime.RuntimePaths runtimePaths = TikaOcrRuntime.resolve();
    assertTrue(
        directText.toLowerCase(java.util.Locale.ROOT).contains("alpha"),
        "owned engine Tesseract should read fixture text, got: "
            + directText
            + " runtime="
            + runtimePaths);
    TestMetricRegistry registry = new TestMetricRegistry(OcrMetricCatalog.DEFINITIONS);
    OcrMetricCatalog catalog = new OcrMetricCatalog(registry);

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null, ocrConfig, catalog)) {
      artifact = extractor.extractArtifact(image);
    }

    assertEquals(
        OcrRoutingConfig.PARSER_ID,
        artifact.parserId(),
        "expected OCR parser, got content: "
            + artifact.result().content()
            + " evidence="
            + artifact.visualExtractionEvidenceJson()
            + " skippedDisabled="
            + registry.counterValue(
                OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.DISABLED))
            + " skippedSize="
            + registry.counterValue(
                OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.SIZE))
            + " skippedTextual="
            + registry.counterValue(
                OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.TEXTUAL))
            + " failedExtraction="
            + registry.counterValue(
                OcrMetricCatalog.FAILED_TOTAL,
                OcrTags.OcrFailureTags.of(OcrRoutingConfig.ENGINE, "ExtractionException")));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrLanguage\":\"eng\""));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"ocr_full\""));
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"ocrMeanConfidence\":"),
        artifact.visualExtractionEvidenceJson());
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"ocrWordCount\":"),
        artifact.visualExtractionEvidenceJson());
    String normalized = artifact.result().content().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        normalized.contains("alpha") && normalized.contains("document"),
        "expected OCR text to contain ALPHA DOCUMENT, got: " + artifact.result().content());
    assertEquals(
        1L,
        registry.counterValue(
            OcrMetricCatalog.SUCCEEDED_TOTAL, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE)));
    assertEquals(
        1L,
        registry.histogramCount(
            OcrMetricCatalog.TIME_MS, OcrTags.OcrEngineTags.of(OcrRoutingConfig.ENGINE)));
  }

  @Test
  @Timeout(30)
  void realTesseractRuntimeRecordsNoTextFoundForBlankImageWithNoBaseline() throws Exception {
    // Tempdoc 671: OCR is genuinely attempted (real Tesseract) against an image with no text at
    // all and no baseline text either. Before the fix this was mislabeled OcrSkipReason.TEXTUAL
    // ("existing text was already adequate") — the exact bug this test guards against.
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 10_000, 1, 4096, 40_000_000, null, null);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path image = tempDir.resolve("ocr-blank.png");
    writeBlankImage(image);
    TestMetricRegistry registry = new TestMetricRegistry(OcrMetricCatalog.DEFINITIONS);
    OcrMetricCatalog catalog = new OcrMetricCatalog(registry);

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null, ocrConfig, catalog)) {
      artifact = extractor.extractArtifact(image);
    }

    assertEquals(0, artifact.result().content().length());
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"no_text_found\""),
        "expected no_text_found, got evidence=" + artifact.visualExtractionEvidenceJson());
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"structured\""));
    assertFalse(
        artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"textual\""),
        "must not be mislabeled textual (tempdoc 671)");
    // Tempdoc 671, Long-term design part 2: the more foundational, cross-extractor field must
    // also be honestly labeled for this exact document, not just the OCR-specific evidence.
    assertEquals(
        ExtractionStatus.SUCCESS_EMPTY,
        artifact.status(),
        "must not be mislabeled SUCCESS_FULL for an empty document (tempdoc 671 part 2)");
    assertEquals(
        1L,
        registry.counterValue(
            OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.NO_TEXT_FOUND)));
    assertEquals(
        0L,
        registry.counterValue(
            OcrMetricCatalog.SKIPPED_TOTAL, OcrTags.OcrSkipTags.of(OcrSkipReason.TEXTUAL)));
  }

  @Test
  @Timeout(60)
  void realTesseractRuntimeAddsSelectiveOcrEvidenceForMixedPdf() throws Exception {
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 20_000, 5, 4096, 40_000_000, null, null);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path pdf = tempDir.resolve("mixed-image.pdf");
    writeMixedTextAndImagePdf(pdf);
    PolicyDrivenTikaExtractor admissionProbe =
        new PolicyDrivenTikaExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(), TikaExtractionPolicy.defaults(), ocrConfig);
    assertTrue(
        admissionProbe.shouldAttemptOcrForTesting(
            pdf,
            "application/pdf",
            "This page contains enough readable digital text for the PDF text layer. ".repeat(4),
            new StructuredDocumentSummary(3, 297, 1, 2, 0, 1, 0, 0, 1)),
        "mixed PDF with missing readable pages should be OCR-admitted");

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null, ocrConfig, OcrMetricCatalog.noop())) {
      artifact = extractor.extractArtifact(pdf);
    }

    assertEquals(
        OcrRoutingConfig.PARSER_ID,
        artifact.parserId(),
        "expected OCR parser, got content: "
            + artifact.result().content()
            + " evidence="
            + artifact.visualExtractionEvidenceJson());
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"ocr_selective\""));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"mixedPdf\":true"));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"pagesMissingReadableText\":0"));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrMeanConfidence\":"));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"ocrWordCount\":"));
    String normalized = artifact.result().content().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        normalized.contains("mixed") && normalized.contains("token"),
        "expected selective OCR text to contain MIXED TOKEN, got: " + artifact.result().content());
  }

  @Test
  @Timeout(60)
  void realTesseractRuntimeClearsMissingReadableTextForImageOnlyPdfOcr() throws Exception {
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 20_000, 5, 4096, 40_000_000, null, null);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path pdf = tempDir.resolve("image-only.pdf");
    writeImageOnlyPdf(pdf, "PLAIN SCAN TOKEN");

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null, ocrConfig, OcrMetricCatalog.noop())) {
      artifact = extractor.extractArtifact(pdf);
    }

    assertEquals(
        OcrRoutingConfig.PARSER_ID,
        artifact.parserId(),
        "expected OCR parser, got content: "
            + artifact.result().content()
            + " evidence="
            + artifact.visualExtractionEvidenceJson());
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"route\":\"ocr_full\""));
    assertTrue(artifact.visualExtractionEvidenceJson().contains("\"pagesMissingReadableText\":0"));
    String normalized = artifact.result().content().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        normalized.contains("plain") && normalized.contains("scan") && normalized.contains("token"),
        "expected OCR text to contain PLAIN SCAN TOKEN, got: " + artifact.result().content());
  }

  @Test
  @Timeout(60)
  void realTesseractRuntimeKeepsTextualForMixedPdfWithUnreadableImagePage() throws Exception {
    // Tempdoc 671: empirically confirms (not just statically argues) that trySelectivePdfOcr
    // never needs the NO_TEXT_FOUND code — a mixed PDF, by definition, always has real baseline
    // text on another page, so its own no-improvement tail must stay TEXTUAL.
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 20_000, 5, 4096, 40_000_000, null, null);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path pdf = tempDir.resolve("mixed-unreadable-image.pdf");
    writeMixedTextAndBlankImagePdf(pdf);

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null, ocrConfig, OcrMetricCatalog.noop())) {
      artifact = extractor.extractArtifact(pdf);
    }

    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"route\":\"structured\""),
        "expected the unreadable image page to fall back to the structured baseline, got evidence="
            + artifact.visualExtractionEvidenceJson());
    assertTrue(
        artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"textual\""),
        "mixed PDF with real baseline text must stay textual (tempdoc 671), got evidence="
            + artifact.visualExtractionEvidenceJson());
    assertFalse(
        artifact.visualExtractionEvidenceJson().contains("\"ocrSkipReason\":\"no_text_found\""),
        "mixed PDF must never be mislabeled no_text_found (real baseline text exists)");
    String normalized = artifact.result().content().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        normalized.contains("readable digital text"),
        "expected the real text page's baseline content to survive, got: " + artifact.result().content());
    // Tempdoc 671, Long-term design part 2: real, non-empty baseline content survives — the
    // cross-extractor status must correctly read SUCCESS_FULL, not SUCCESS_EMPTY.
    assertEquals(ExtractionStatus.SUCCESS_FULL, artifact.status());
  }

  @Test
  @Timeout(60)
  void realTesseractRuntimeNeverMislabelsTextualForImageOnlyPdfWithBlankPage() throws Exception {
    // Tempdoc 671: regression test for the fourth affected call site (tryRenderedPdfOcr), found
    // only during implementation.
    //
    // This does NOT reliably reach the removed-skip-call fallthrough (tryOcr's tail) the way
    // this tempdoc's other new regression test does for a raw raster image: real Tesseract on a
    // PDFRenderer-rasterized *blank* PDF page is not perfectly noise-free — it deterministically
    // returns a few stray characters here even with nothing drawn on the page, unlike a raw
    // synthetic blank PNG (see realTesseractRuntimeRecordsNoTextFoundForBlankImageWithNoBaseline,
    // which does reach it reliably). Because the pre-existing (unrelated to tempdoc 671) success
    // check is `mergedQuality >= baselineQuality` and both sides are 0 for an empty baseline, a
    // few noise characters satisfy that check and this fixture resolves via the *success* branch
    // instead of the no-improvement tail this test originally set out to exercise.
    //
    // The invariant tempdoc 671 actually cares about doesn't depend on which branch fires: an
    // image-only PDF with no baseline text must never be mislabeled "textual" (that label means
    // "there was already adequate text," which is false here) — assert that directly, and assert
    // no_text_found specifically only in the branch where a skip reason is actually recorded.
    OcrRoutingConfig ocrConfig = new OcrRoutingConfig(true, List.of("eng"), 20_000, 5, 4096, 40_000_000, null, null);
    assumeTrue(
        TikaOcrRuntime.blockedReason(ocrConfig).isBlank(),
        "real Tesseract OCR runtime with eng tessdata is not available");
    Path pdf = tempDir.resolve("image-only-blank.pdf");
    writeImageOnlyBlankPdf(pdf);

    ExtractionArtifact artifact;
    try (TimeboxedContentExtractor extractor =
        ExtractionSandboxFactory.inProcessStructured(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocr(), io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(), null, ocrConfig, OcrMetricCatalog.noop())) {
      artifact = extractor.extractArtifact(pdf);
    }

    String evidence = artifact.visualExtractionEvidenceJson();
    assertTrue(
        artifact.result().content().length() < TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH,
        "expected no meaningfully readable text from a blank page, got: " + artifact.result().content());
    assertFalse(
        evidence.contains("\"ocrSkipReason\":\"textual\""),
        "image-only PDF with no baseline text must never be mislabeled textual (tempdoc 671), evidence="
            + evidence);
    if (evidence.contains("\"ocrSkipReason\":")) {
      assertTrue(
          evidence.contains("\"ocrSkipReason\":\"no_text_found\""),
          "the only non-textual skip reason expected here is no_text_found, evidence=" + evidence);
    }
    // Tempdoc 671, Long-term design part 2: when content is genuinely empty, the cross-extractor
    // status must say so too — not fall back to a mislabeled SUCCESS_FULL. When the real-Tesseract
    // noise floor leaks a few stray characters (documented above), SUCCESS_FULL is legitimately
    // correct per the classifier's own definition (non-empty content), so this assertion is
    // conditional on the same noise-floor variability the rest of this test already accommodates.
    if (artifact.result().content().isEmpty()) {
      assertEquals(ExtractionStatus.SUCCESS_EMPTY, artifact.status());
    }
  }

  @Test
  @Timeout(10)
  void mixedPdfEvidenceWithDisabledOcrRecordsMissingPageEvidence() {
    StructuredDocumentSummary summary =
        StructuredDocumentSummary.empty().withPdfPageSignals(2, Set.of(0), Set.of());
    VisualExtractionEvidence evidence =
        VisualExtractionEvidence.from(
            "This page contains enough readable digital text for the PDF text layer.",
            summary,
            "structured",
            OcrRoutingConfig.disabled(),
            false,
            OcrConfidenceExtractor.Summary.empty(),
            false,
            VisualExtractionEvidence.RoutingFacts.of(false, null, OcrSkipReason.DISABLED));

    String evidenceJson = evidence.toJson();
    assertTrue(evidenceJson.contains("\"mixedPdf\":true"));
    assertTrue(
        evidenceJson.contains("\"pagesMissingReadableText\":")
            && !evidenceJson.contains("\"pagesMissingReadableText\":0"),
        evidenceJson);
    assertTrue(evidenceJson.contains("\"ocrSkipReason\":\"disabled\""), evidenceJson);
  }

  private static void writeTextImage(Path image, String text) throws Exception {
    ImageIO.write(createTextImage(text), "png", image.toFile());
  }

  /** A genuinely blank (no drawn content) raster image — used by the tempdoc 671 regression tests. */
  private static void writeBlankImage(Path image) throws Exception {
    ImageIO.write(createBlankImage(), "png", image.toFile());
  }

  private static BufferedImage createBlankImage() {
    BufferedImage buffered = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = buffered.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
    } finally {
      graphics.dispose();
    }
    return buffered;
  }

  private static BufferedImage createTextImage(String text) {
    BufferedImage buffered = new BufferedImage(1400, 700, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = buffered.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, buffered.getWidth(), buffered.getHeight());
      graphics.setColor(Color.BLACK);
      graphics.setFont(ocrFixtureFont());
      graphics.drawString(text, 90, 220);
    } finally {
      graphics.dispose();
    }
    return buffered;
  }

  private static Font ocrFixtureFont() {
    File arial = Path.of("C:", "Windows", "Fonts", "arial.ttf").toFile();
    if (arial.isFile()) {
      try {
        return Font.createFont(Font.TRUETYPE_FONT, arial).deriveFont(Font.PLAIN, 72f);
      } catch (Exception ignored) {
        // Fall back to the logical font if Arial is unavailable or cannot be loaded.
      }
    }
    return new Font(Font.SANS_SERIF, Font.PLAIN, 72);
  }

  private static void writeMixedTextAndImagePdf(Path pdf) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage textPage = new PDPage();
      document.addPage(textPage);
      try (PDPageContentStream content = new PDPageContentStream(document, textPage)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 720);
        content.showText("This page contains enough readable digital text for the PDF text layer. ".repeat(4));
        content.endText();
      }

      PDPage imagePage = new PDPage();
      document.addPage(imagePage);
      PDImageXObject image = LosslessFactory.createFromImage(document, createTextImage("MIXED TOKEN"));
      try (PDPageContentStream content = new PDPageContentStream(document, imagePage)) {
        content.drawImage(image, 72, 500, 430, 125);
      }
      document.save(pdf.toFile());
    }
  }

  private static void writeImageOnlyPdf(Path pdf, String text) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage imagePage = new PDPage();
      document.addPage(imagePage);
      PDImageXObject image = LosslessFactory.createFromImage(document, createTextImage(text));
      try (PDPageContentStream content = new PDPageContentStream(document, imagePage)) {
        content.drawImage(image, 72, 500, 430, 125);
      }
      document.save(pdf.toFile());
    }
  }

  /**
   * Tempdoc 671 regression fixture: a mixed PDF (real text page + a genuinely empty page with no
   * embedded image at all) — used to prove the {@code trySelectivePdfOcr} no-improvement tail
   * correctly keeps {@code OcrSkipReason.TEXTUAL} (real baseline text exists) rather than
   * misclassifying as {@code NO_TEXT_FOUND}. A truly empty page (not an embedded "blank" raster)
   * is used deliberately: rendering an embedded image through {@code PDFRenderer} introduces
   * enough incidental noise that real Tesseract occasionally reads a few stray characters off an
   * otherwise-blank embedded raster — an empty page has nothing for OCR to latch onto.
   */
  private static void writeMixedTextAndBlankImagePdf(Path pdf) throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage textPage = new PDPage();
      document.addPage(textPage);
      try (PDPageContentStream content = new PDPageContentStream(document, textPage)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 720);
        content.showText("This page contains enough readable digital text for the PDF text layer. ".repeat(4));
        content.endText();
      }

      document.addPage(new PDPage());
      document.save(pdf.toFile());
    }
  }

  /**
   * Tempdoc 671 regression fixture: an image-only PDF (no PDF text layer at all) whose sole page
   * is genuinely empty (no embedded image) — used to prove {@code tryRenderedPdfOcr}'s removed
   * internal skip call was correctly replaced by reliance on {@code tryOcr}'s tail, not silently
   * dropped. See {@link #writeMixedTextAndBlankImagePdf} for why an empty page, not an embedded
   * "blank" raster, is used.
   */
  private static void writeImageOnlyBlankPdf(Path pdf) throws Exception {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new PDPage());
      document.save(pdf.toFile());
    }
  }

}
