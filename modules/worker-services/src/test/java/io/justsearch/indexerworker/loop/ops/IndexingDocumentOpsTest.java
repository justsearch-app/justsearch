package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.extract.ContentExtractor;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link IndexingDocumentOps} metadata helpers. */
class IndexingDocumentOpsTest {

  @Test
  void extractionQualityRetainsTheComputedDoubleWithoutFloatRounding() {
    String content = "a".repeat(31) + "!".repeat(69);
    double expected = io.justsearch.indexerworker.text.TextQualityAnalyzer.computeQualityScore(content);
    assertTrue(expected > 0.0 && expected < 1.0, "fixture must have a fractional score");
    assertNotEquals((double) (float) expected, expected, "fixture must distinguish float precision");
    var document = io.justsearch.indexerworker.fixtures.TestDocumentBuilder.buildDocument(
        java.nio.file.Path.of("fraction.txt"), new ContentExtractor.ExtractionResult(content, null, "text/plain"));
    assertEquals(expected, document.fields().get(io.justsearch.indexing.SchemaFields.EXTRACTION_QUALITY_SCORE));
  }

  @Test
  void estimateTokenCount_nonBlankAndBlank() {
    // chars/3: a 2000-char doc (the chunk threshold) estimates ~666 tokens.
    assertEquals(666L, IndexingDocumentOps.estimateTokenCount("x".repeat(2000)));
    assertTrue(IndexingDocumentOps.estimateTokenCount("y".repeat(40_000)) > 512);
    assertEquals(0L, IndexingDocumentOps.estimateTokenCount(null));
    assertEquals(0L, IndexingDocumentOps.estimateTokenCount("   "));
  }

  /**
   * Tempdoc 717 (review Finding 2): a document long enough to have been chunked (≥ 2000 chars) must
   * estimate ABOVE the 512-token short threshold — for ANY script — so a chunked corpus is never
   * mis-classified "short". CJK packs more tokens per char, so a chunk-length CJK doc is only more
   * clearly long; the char-uniform estimate never under-classifies a chunked doc.
   */
  @Test
  void estimateTokenCount_chunkLengthDocReadsAboveShortThreshold() {
    assertTrue(
        IndexingDocumentOps.estimateTokenCount("x".repeat(2000)) > 512, "ASCII 2000-char doc");
    assertTrue(
        IndexingDocumentOps.estimateTokenCount("字".repeat(2000)) > 512, "CJK 2000-char doc");
  }

  /**
   * Tempdoc 717: when the SPLADE encoder isn't ready at index time (the fresh-build startup race),
   * {@code parent_token_count} must still be populated via the char estimate — otherwise the
   * corpus-profile classifier sees no token data → median 0 → mis-classifies a long corpus "short"
   * → the {@code chunk_merge} leg is skipped and dense quality halves.
   */
  @Test
  void deriveParentMetadata_nullSpladeStillPopulatesTokenCount() {
    var extraction =
        new ContentExtractor.ExtractionResult("word ".repeat(3000), "title", "text/plain");
    var meta =
        IndexingDocumentOps.deriveParentMetadata(
            java.nio.file.Path.of("doc.txt"), extraction, null, null);
    assertNotNull(
        meta.parentTokenCount(), "token count must not be null when SPLADE is unavailable");
    assertTrue(
        meta.parentTokenCount() > 512,
        "a long doc must estimate above the 512-token short threshold");
  }

  @Test
  void parsePublishedAt_corpusFormat() {
    Long ms = IndexingDocumentOps.parsePublishedAt("2023-11-27 08:45:59");
    assertNotNull(ms);
    // 2023-11-27T08:45:59Z in epoch millis
    assertEquals(1701074759000L, ms, "Should parse corpus date format as UTC");
  }

  @Test
  void parsePublishedAt_dateOnly() {
    Long ms = IndexingDocumentOps.parsePublishedAt("2023-11-27");
    assertNotNull(ms);
    // 2023-11-27T00:00:00Z in epoch millis
    assertEquals(1701043200000L, ms, "Should parse date-only as midnight UTC");
  }

  @Test
  void parsePublishedAt_isoFormat() {
    Long ms = IndexingDocumentOps.parsePublishedAt("2023-11-27T08:45:59");
    assertNotNull(ms);
    assertEquals(1701074759000L, ms, "Should parse ISO format as UTC");
  }

  @Test
  void parsePublishedAt_unparseable() {
    assertNull(IndexingDocumentOps.parsePublishedAt("not a date"));
  }

  @Test
  void parsePublishedAt_emptyString() {
    assertNull(IndexingDocumentOps.parsePublishedAt(""));
  }
}
