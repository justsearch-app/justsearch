/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The honest-hole marker (tempdoc 790 item 3): a document whose indexed text is an extraction
 * dropout must say so in the provenance fields the search path reads, and a document with real text
 * must be untouched by the whole mechanism.
 */
@DisplayName("Extraction dropout marker (index-time provenance)")
final class ExtractionDropoutMarkerTest {

  @Test
  @DisplayName("empty PDF extraction with a fallback tier queued -> PENDING_FALLBACK, tier retained")
  void pendingFallbackKeepsTheTierThatRan() {
    IndexDocument doc =
        TestDocumentBuilder.buildDocument(
            Path.of("scan.pdf"), new ExtractionResult("", null, "application/pdf"));

    assertEquals(SchemaFields.VDU_STATUS_PENDING, doc.fields().get(SchemaFields.VDU_STATUS));
    assertEquals(
        IngestionReasonCodes.EXTRACTION_DROPOUT_PENDING_FALLBACK,
        doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
    assertEquals(
        SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
        doc.fields().get(SchemaFields.EXTRACTION_METHOD),
        "while a tier is still queued, extraction_method names the tier that ran");
  }

  @Test
  @DisplayName("empty extraction with NO eligible tier -> extraction_failed marker")
  void unrecoverableDropoutIsMarkedFailed() {
    // A .txt file is not VDU-eligible: no tier remains, so the chain terminates here.
    IndexDocument doc =
        TestDocumentBuilder.buildDocument(
            Path.of("placeholder.txt"), new ExtractionResult("", null, "text/plain"));

    assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, doc.fields().get(SchemaFields.VDU_STATUS));
    assertEquals(
        SchemaFields.EXTRACTION_METHOD_NONE, doc.fields().get(SchemaFields.EXTRACTION_METHOD));
    assertEquals(
        IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED,
        doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
  }

  @Test
  @DisplayName("wordless extraction is marked too, not just empty")
  void trivialDropoutIsMarked() {
    IndexDocument doc =
        TestDocumentBuilder.buildDocument(
            Path.of("wordless.txt"), new ExtractionResult("\\", null, "text/plain"));

    assertEquals(
        SchemaFields.EXTRACTION_METHOD_NONE, doc.fields().get(SchemaFields.EXTRACTION_METHOD));
    assertEquals(
        IngestionReasonCodes.EXTRACTION_DROPOUT_UNRECOVERED,
        doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
    assertEquals("\\", doc.fields().get(SchemaFields.CONTENT), "the marker never discards text");
  }

  @Test
  @DisplayName("healthy documents are untouched — zero fire on real content")
  void healthyDocumentIsUntouched() {
    IndexDocument doc =
        TestDocumentBuilder.buildDocument(
            Path.of("real.txt"),
            new ExtractionResult(
                "The quick brown fox jumps over the lazy dog. ".repeat(10), null, "text/plain"));

    assertEquals(
        SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
        doc.fields().get(SchemaFields.EXTRACTION_METHOD));
    assertNull(doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
    assertNotEquals(
        SchemaFields.EXTRACTION_METHOD_NONE, doc.fields().get(SchemaFields.EXTRACTION_METHOD));
  }

  @Test
  @DisplayName("legitimately short documents do not trip the marker")
  void legitimateShortDocumentIsNotMarked() {
    // Verbatim from ohr-bench-clean: the corpus's shortest legitimate document.
    IndexDocument doc =
        TestDocumentBuilder.buildDocument(
            Path.of("short.txt"), new ExtractionResult("$f 5$", null, "text/plain"));

    assertEquals(
        SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
        doc.fields().get(SchemaFields.EXTRACTION_METHOD));
    assertNull(doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
  }

  @Test
  @org.junit.jupiter.api.Timeout(60)
  void utf8SupplementaryTextKeepsItsStructuredMethodAndNoDropoutMarker(
      @org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
    // Real nonvisual UTF-8 bytes: no eligible visual fallback can conceal a false dropout.
    String source = "\uD840\uDC00\uD801\uDC00".repeat(60);
    Path file = tempDir.resolve("supplementary.txt");
    byte[] bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.nio.file.Files.write(file, bytes);
    try (var extractor = new io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor(
        io.justsearch.indexerworker.TestWorkerExecutorRegistrations.ocrFactory(),
        io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults(),
        io.justsearch.indexerworker.extract.OcrRoutingConfig.disabled())) {
      var artifact = extractor.extractArtifact(file);
      assertEquals(source, artifact.result().content().strip(), "establish UTF-8 parser success first");
      var policy = io.justsearch.indexerworker.extract.TikaExtractionPolicy.defaults();
      var doc = IndexingDocumentOps.buildDocument(file, artifact.validate(policy, null),
          null, null, null, false, null, null, (stage, elapsed, reason) -> {},
          org.slf4j.LoggerFactory.getLogger(ExtractionDropoutMarkerTest.class), null, null,
          "supplementary-txt");
      assertEquals(SchemaFields.EXTRACTION_METHOD_TIKA_STRUCTURED,
          doc.fields().get(SchemaFields.EXTRACTION_METHOD));
      assertNull(doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
      assertEquals(SchemaFields.VDU_STATUS_NOT_NEEDED, doc.fields().get(SchemaFields.VDU_STATUS));
      assertEquals(1.0f, doc.fields().get(SchemaFields.EXTRACTION_QUALITY_SCORE));
      assertEquals(artifact.result().content(), doc.fields().get(SchemaFields.CONTENT));
      org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(file));
    }
  }

}
