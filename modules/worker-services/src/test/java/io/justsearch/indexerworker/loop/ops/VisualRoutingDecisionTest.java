package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionFallbackBudget;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class VisualRoutingDecisionTest {

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {".tif", ".tiff", ".TIF", ".TIFF"})
  void tiffAliasesPersistEquivalentFallbackDemand(String suffix) throws Exception {
    var artifact = io.justsearch.indexerworker.extract.ExtractionArtifact.full(
        new ExtractionResult("", null, "image/tiff"),
        io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults(), "tika-policy-structured", false);
    var doc = IndexingDocumentOps.buildDocument(Path.of("scan" + suffix), artifact.validate(
        io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults(), null),
        null, null, null, false, null, null, (stage, elapsed, reason) -> {},
        org.slf4j.LoggerFactory.getLogger(VisualRoutingDecisionTest.class), null, null, "tiff-test");
    assertEquals(SchemaFields.VDU_STATUS_PENDING, doc.fields().get(SchemaFields.VDU_STATUS));
    assertEquals(SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT, doc.fields().get(SchemaFields.VDU_DEMAND_KIND));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "\"pagesMissingReadableText\":1", "\"contentTruncated\":true"})
  void highConfidencePartialFullPdfOcrPersistsFallbackDemand(String partial) throws Exception {
    String text = "Readable OCR text ".repeat(30);
    String evidence = "{\"schemaVersion\":1,\"ocrMeanConfidence\":0.99,"
        + "\"ocrLowConfidenceWordCount\":0,\"ocrWordCount\":90,\"mixedPdf\":false," + partial + "}";
    var artifact = io.justsearch.indexerworker.extract.ExtractionArtifact.full(
        new ExtractionResult(text, null, "application/pdf"),
        io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults(),
        io.justsearch.indexerworker.extract.OcrRoutingConfig.PARSER_ID, true)
        .withVisualExtractionEvidenceJson(evidence);
    var doc = IndexingDocumentOps.buildDocument(Path.of("partial.pdf"), artifact.validate(
        io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults(), null),
        null, null, null, false, null, null, (stage, elapsed, reason) -> {},
        org.slf4j.LoggerFactory.getLogger(VisualRoutingDecisionTest.class), null, null, "partial-test");
    assertEquals(SchemaFields.VDU_STATUS_PENDING, doc.fields().get(SchemaFields.VDU_STATUS));
    assertEquals(SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT, doc.fields().get(SchemaFields.VDU_DEMAND_KIND));
    var capped = VisualRoutingDecision.decide(Path.of("partial.pdf"), artifact.result(),
        SchemaFields.EXTRACTION_METHOD_OCR_TIKA, evidence, 0.3, new ExtractionFallbackBudget(1, 30_000));
    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, capped.status());
  }

  @Test
  void highConfidencePlainOcrDoesNotQueueVdu() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("plain-scan.pdf"),
            new ExtractionResult("Readable OCR text ".repeat(20), null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"textQualityScore\":0.94,\"layoutComplexity\":\"mixed_visual\","
                + "\"imagePageCount\":1,\"mixedPdf\":false,"
                + "\"ocrMeanConfidence\":0.92,\"ocrLowConfidenceWordCount\":1,\"ocrWordCount\":30}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, decision.status());
    assertNull(decision.demandKind());
  }

  @Test
  void lowConfidenceOcrQueuesEnrichment() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("weak-scan.pdf"),
            new ExtractionResult("Readable OCR text ".repeat(20), null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"textQualityScore\":0.82,\"layoutComplexity\":\"none\","
                + "\"ocrMeanConfidence\":0.61,\"ocrLowConfidenceWordCount\":2,\"ocrWordCount\":20}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_PENDING, decision.status());
    assertEquals(SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT, decision.demandKind());
  }

  @Test
  void emptyOcrOutputQueuesTheNextTierInsteadOfDeclaringItselfSufficient() {
    // Tempdoc 790 regression: before the dropout gate, an OCR pass that produced NOTHING took the
    // "no visual-enrichment demand" branch and returned notNeeded("ocr_baseline_sufficient") — the
    // VLM tier was never reached for exactly the documents that needed it most.
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("empty-scan.pdf"),
            new ExtractionResult("", null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"textQualityScore\":0.0,\"layoutComplexity\":\"none\","
                + "\"ocrMeanConfidence\":0.0,\"ocrLowConfidenceWordCount\":0,\"ocrWordCount\":0}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_PENDING, decision.status());
    assertEquals(SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT, decision.demandKind());
    assertEquals("extraction_dropout", decision.reason());
  }

  @Test
  void trivialOcrOutputQueuesTheNextTier() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("wordless-scan.pdf"),
            new ExtractionResult("\\", null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"ocrWordCount\":0}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_PENDING, decision.status());
    assertEquals("extraction_dropout", decision.reason());
  }

  @Test
  void dropoutStillRespectsTheFallbackBudget() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("empty-scan.pdf"),
            new ExtractionResult("", null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"ocrWordCount\":0}",
            0.3d,
            new ExtractionFallbackBudget(1, 30_000L));

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, decision.status());
    assertNull(decision.demandKind());
    assertEquals("fallback_budget_spent", decision.reason());
  }

  @Test
  void healthyOcrOutputStillDoesNotQueueTheNextTier() {
    // Precision guard: the dropout gate must not turn every OCR result into a VDU demand.
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("clean-scan.pdf"),
            new ExtractionResult("Readable OCR text ".repeat(20), null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            "{\"schemaVersion\":1,\"textQualityScore\":0.94,\"layoutComplexity\":\"none\","
                + "\"ocrMeanConfidence\":0.92,\"ocrLowConfidenceWordCount\":1,\"ocrWordCount\":30}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, decision.status());
    assertEquals("ocr_baseline_sufficient", decision.reason());
  }

  @Test
  void missingReadableStructuredPagesQueueBaselineDemand() {
    VisualRoutingDecision decision =
        VisualRoutingDecision.decide(
            Path.of("partial.pdf"),
            new ExtractionResult("Some digital text", null, "application/pdf"),
            SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
            "{\"schemaVersion\":1,\"pagesMissingReadableText\":1}",
            0.3d);

    assertEquals(SchemaFields.VDU_STATUS_PENDING, decision.status());
    assertEquals(SchemaFields.VDU_DEMAND_KIND_BASELINE_TEXT, decision.demandKind());
  }

  @Test
  void correctedOcrEvidenceCreatesVisualEnrichmentDemand() throws Exception {
    assertOcrEvidenceAndMarkers("AA" + "\uD840\uDC00".repeat(33) + "!".repeat(65),
        0.35, 0.56, SchemaFields.VDU_STATUS_PENDING,
        SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT, false);
  }

  @Test
  void correctedSupplementaryOcrRemovesFalseBaselineDemand() throws Exception {
    assertOcrEvidenceAndMarkers("\uD840\uDC00".repeat(100),
        1.0, 1.0, SchemaFields.VDU_STATUS_NOT_NEEDED, null, false);
  }

  @Test
  void correctedEmojiDenominatorRemovesOldEnrichmentDemand() throws Exception {
    assertOcrEvidenceAndMarkers("A".repeat(40) + "\uD83D\uDE00".repeat(20) + "!".repeat(20),
        0.5, 0.8, SchemaFields.VDU_STATUS_NOT_NEEDED, null, false);
  }

  @Test
  void independentMixedPdfSignalStillQueuesEnrichment() throws Exception {
    assertOcrEvidenceAndMarkers("A".repeat(200),
        1.0, 1.0, SchemaFields.VDU_STATUS_PENDING,
        SchemaFields.VDU_DEMAND_KIND_VISUAL_ENRICHMENT, true);
  }

  private static void assertOcrEvidenceAndMarkers(
      String text, double ratio, double score, String status, String demand, boolean mixedPdf)
      throws Exception {
    var summary = new io.justsearch.indexerworker.extract.StructuredDocumentSummary(
        1, text.length(), 1, 0, 0, 1, 0, 0, 0);
    var evidence = io.justsearch.indexerworker.extract.VisualExtractionEvidence.from(
        text, summary, "ocr", io.justsearch.indexerworker.extract.OcrRoutingConfig.disabled(),
        true, null, mixedPdf);
    var parsed = VisualRoutingDecision.parseEvidence(evidence.toJson());
    var result = new ExtractionResult(text, null, "application/pdf");
    var decision = VisualRoutingDecision.decide(Path.of("unicode.pdf"), result,
        SchemaFields.EXTRACTION_METHOD_OCR_TIKA, evidence.toJson(), 0.3);
    var policy = io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults();
    var artifact = io.justsearch.indexerworker.extract.ExtractionArtifact.full(result, policy,
        io.justsearch.indexerworker.extract.OcrRoutingConfig.PARSER_ID, false)
        .withVisualExtractionEvidence(evidence).validate(policy, null);
    var doc = IndexingDocumentOps.buildDocument(Path.of("unicode.pdf"), artifact,
        null, null, null, false, null, null, (stage, elapsed, reason) -> {},
        org.slf4j.LoggerFactory.getLogger(VisualRoutingDecisionTest.class), null, null,
        "unicode-ocr-test");
    System.out.println("OCR unicode evidence=" + evidence.toJson() + " decision=" + decision
        + " method=" + doc.fields().get(SchemaFields.EXTRACTION_METHOD)
        + " marker=" + doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals(ratio, evidence.alphanumericRatio()),
        () -> assertEquals(score, evidence.textQualityScore()),
        () -> assertEquals(1, evidence.schemaVersion()),
        () -> assertEquals(text.length(), evidence.textCharCount()),
        () -> assertEquals((double) text.length(), evidence.charsPerPage()),
        () -> assertEquals(score, ((Number) parsed.get("textQualityScore")).doubleValue()),
        () -> assertEquals(ratio, ((Number) parsed.get("alphanumericRatio")).doubleValue()),
        () -> assertEquals(status, decision.status()),
        () -> assertEquals(demand, decision.demandKind()),
        () -> assertEquals(status, doc.fields().get(SchemaFields.VDU_STATUS)),
        () -> assertEquals(demand, doc.fields().get(SchemaFields.VDU_DEMAND_KIND)),
        () -> assertEquals(SchemaFields.EXTRACTION_METHOD_OCR_TIKA,
            doc.fields().get(SchemaFields.EXTRACTION_METHOD)),
        () -> assertNull(doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE)),
        () -> assertEquals(text, doc.fields().get(SchemaFields.CONTENT)),
        () -> assertEquals(evidence.toJson(), doc.fields().get(SchemaFields.VISUAL_EXTRACTION_EVIDENCE)));

  }

}
