/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ipc.UpdateVduResultRequest;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VduResultWriterTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;
  private static final String DOC = "parent";
  private static final String ORIGINAL = "Original content has a different quality score. ".repeat(10);
  private static final String REPLACEMENT = "a".repeat(31) + "!".repeat(69);
  private static final String QUALITY = SchemaFields.EXTRACTION_QUALITY_SCORE;

  private RunningRuntime open() {
    return IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
        .atPath(tempDir).withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private void refresh(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private void seed(RunningRuntime runtime) {
    runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
        SchemaFields.DOC_ID, DOC, SchemaFields.DOC_UID, DOC,
        SchemaFields.CONTENT, ORIGINAL, QUALITY, 0.1,
        SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(ORIGINAL))));
    refresh(runtime);
  }

  private void assertState(RunningRuntime runtime, String content, double quality) {
    assertEquals(content, runtime.documentFieldOps().getDocumentContent(DOC));
    assertEquals(Double.toString(quality), runtime.documentFieldOps().getDocumentField(DOC, QUALITY));
  }

  @ParameterizedTest
  @ValueSource(strings = {"explicit", "legacy", "unspecified"})
  void everyTextReplacementRecomputesScoreInTheSameCommittedDocument(String path) throws Exception {
    double expectedScore = TextQualityAnalyzer.computeQualityScore(REPLACEMENT);
    assertTrue(expectedScore > 0.0 && expectedScore < 1.0, "replacement must have a fractional score");
    assertNotEquals(0.1, expectedScore);
    var request = UpdateVduResultRequest.newBuilder().setDocId(DOC).setExtractedContent(REPLACEMENT);
    if (path.equals("explicit")) request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);
    if (path.equals("legacy")) request.setVduStatus("COMPLETED");
    try (var runtime = open()) {
      seed(runtime);
      assertTrue(VduResultWriter.apply(runtime, request.build(), false,
          Map.of(SchemaFields.CONTENT, ORIGINAL), SchemaFields.VDU_MAX_RETRIES));
      refresh(runtime);
      assertState(runtime, REPLACEMENT, expectedScore);
    }
    try (var runtime = open()) {
      assertState(runtime, REPLACEMENT, expectedScore);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "failed", "rejected", "blank", "stale"})
  void nonReplacingAndStaleResultsPreserveTextAndExactScore(String path) throws Exception {
    var request = UpdateVduResultRequest.newBuilder().setDocId(DOC).setExtractedContent(REPLACEMENT);
    switch (path) {
      case "empty" -> request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY);
      case "failed" -> request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_FAILED);
      case "rejected" -> request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_REJECTED_SUSPECT_TEXT);
      case "blank" -> request.setExtractedContent("  ");
      case "stale" -> request.setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT);
      default -> throw new AssertionError(path);
    }
    try (var runtime = open()) {
      seed(runtime);
      boolean applied = VduResultWriter.apply(runtime, request.build(), false,
          Map.of(SchemaFields.CONTENT, path.equals("stale") ? "stale revision" : ORIGINAL), SchemaFields.VDU_MAX_RETRIES);
      assertEquals(!path.equals("stale"), applied);
      refresh(runtime);
      assertState(runtime, ORIGINAL, 0.1);
    }
    try (var runtime = open()) {
      assertState(runtime, ORIGINAL, 0.1);
    }
  }

  @Test
  void invalidSuccessTextCannotReplaceEitherField() throws Exception {
    try (var runtime = open()) {
      seed(runtime);
      var request = UpdateVduResultRequest.newBuilder().setDocId(DOC)
          .setOutcome(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT).setExtractedContent(" ").build();
      assertThrows(IllegalArgumentException.class, () -> VduResultWriter.apply(runtime, request, false, Map.of(), SchemaFields.VDU_MAX_RETRIES));
      assertState(runtime, ORIGINAL, 0.1);
    }
  }
}
