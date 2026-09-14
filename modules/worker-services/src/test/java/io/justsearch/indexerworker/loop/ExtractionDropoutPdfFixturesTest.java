/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.ExtractionDropoutPolicy;
import io.justsearch.indexerworker.extract.PolicyDrivenTikaExtractor;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

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
}
