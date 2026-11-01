package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SpladeTruncationEvidence")
class SpladeTruncationEvidenceTest {

  @Test
  @DisplayName("deriveWindowCount uses overlap-aware stride")
  void deriveWindowCountUsesOverlapAwareStride() {
    assertEquals(1, SpladeTruncationEvidence.deriveWindowCount(256, 256, 64));
    assertEquals(2, SpladeTruncationEvidence.deriveWindowCount(257, 256, 64));
    assertEquals(2, SpladeTruncationEvidence.deriveWindowCount(446, 256, 64));
    assertEquals(3, SpladeTruncationEvidence.deriveWindowCount(447, 256, 64));
  }

  @Test
  @DisplayName("snapshot reports complete window coverage without truncation")
  @SuppressWarnings("unchecked")
  void snapshotReportsTruncationAndWindowHistograms() {
    SpladeTruncationEvidence evidence = new SpladeTruncationEvidence(256, 64);
    evidence.record(120);
    evidence.record(300);
    evidence.record(700);

    Map<String, Object> summary = evidence.snapshot(Path.of("models", "splade", "naver-splade-v3"));

    assertEquals(3L, summary.get("documentsEncoded"));
    assertEquals(0L, summary.get("documentsTruncated"));
    assertEquals(700, summary.get("maxObservedTokens"));

    Map<String, Long> windowHistogram = (Map<String, Long>) summary.get("derivedWindowCountHistogram");
    assertEquals(1L, windowHistogram.get("1"));
    assertEquals(1L, windowHistogram.get("2"));
    assertEquals(1L, windowHistogram.get("4"));

    Map<String, Long> tokenBuckets = (Map<String, Long>) summary.get("tokenCountBuckets");
    assertEquals(1L, tokenBuckets.get("le_max_seq_len"));
    assertEquals(1L, tokenBuckets.get("max_seq_len_to_2x"));
    assertEquals(1L, tokenBuckets.get("2x_to_4x"));
  }

  @Test
  @DisplayName("flushIfNeeded writes evidence incrementally")
  void flushIfNeededWritesEvidenceIncrementally(@TempDir Path tempDir) throws Exception {
    SpladeTruncationEvidence evidence = new SpladeTruncationEvidence(256, 64, 2);
    Path outputPath = tempDir.resolve("splade-truncation-summary.json");

    evidence.record(120);
    evidence.flushIfNeeded(outputPath, tempDir.resolve("models"));
    assertTrue(Files.notExists(outputPath));

    evidence.record(600);
    evidence.flushIfNeeded(outputPath, tempDir.resolve("models"));
    assertTrue(Files.exists(outputPath));

    @SuppressWarnings("unchecked")
    Map<String, Object> summary =
        new tools.jackson.databind.ObjectMapper().readValue(outputPath.toFile(), Map.class);
    assertEquals(2, summary.get("documentsEncoded"));
    assertEquals(0, summary.get("documentsTruncated"));
  }
}
