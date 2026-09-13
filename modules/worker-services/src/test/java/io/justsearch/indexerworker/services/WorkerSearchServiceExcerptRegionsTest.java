package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.ExcerptRegion;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerSearchServiceExcerptRegionsTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final FieldCatalogDef CATALOG =
      new FieldCatalogDef(
          "test",
          List.of(
              new FieldCatalogDef.FieldDef(
                  SchemaFields.DOC_ID, "keyword", true, true, List.of("id", "sort"), null, null, false),
              new FieldCatalogDef.FieldDef(
                  SchemaFields.DOC_UID,
                  "keyword",
                  false,
                  true,
                  List.of("sort", "tiebreak"),
                  null,
                  null,
                  false),
              new FieldCatalogDef.FieldDef(
                  SchemaFields.TITLE, "text", true, false, List.of("highlight"), null, "icu", false),
              new FieldCatalogDef.FieldDef(
                  SchemaFields.CONTENT, "text", true, false, List.of("highlight"), null, "icu", false),
              new FieldCatalogDef.FieldDef(
                  SchemaFields.CONTENT_PREVIEW,
                  "text",
                  true,
                  false,
                  List.of("highlight"),
                  null,
                  "icu",
                  false)));

  private RunningRuntime lifecycle;
  private WorkerSearchService service;

  @BeforeEach
  void setUp() {
    lifecycle = IndexSchema.fromCatalog(CATALOG).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    service = new WorkerSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() {
    if (lifecycle != null) lifecycle.close();
  }

  private SearchResponse search(String query) {
    SearchResponse response =
        service.search(
            SearchRequest.newBuilder()
                .setQuery(query)
                .setLimit(10)
                .setMode(SearchMode.SEARCH_MODE_TEXT)
                .setIncludeExcerpts(true)
                .build(),
            CallContext.none());
    assertNotNull(response);
    return response;
  }

  /** Builds a long document with the needle at approximately the given character positions. */
  private String buildContent(String filler, String needle, int... positions) {
    StringBuilder sb = new StringBuilder();
    for (int pos : positions) {
      // Fill up to the desired position using whole filler repetitions.
      while (sb.length() < pos) {
        sb.append(filler);
      }
      // Ensure a space before the needle so it tokenizes separately.
      if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
        sb.append(' ');
      }
      sb.append(needle);
      sb.append(' ');
    }
    // Pad to ensure document is long enough.
    while (sb.length() < positions[positions.length - 1] + 500) {
      sb.append(filler);
    }
    return sb.toString();
  }

  @Test
  void excerptRegionsReturnedForDeepMatches() throws Exception {
    // Build a 20KB document with "needle" at positions 5000, 10000, 15000.
    String filler = "lorem ipsum dolor sit amet. ";
    String content = buildContent(filler, "needle", 5000, 10000, 15000);
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Test Document",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("needle");
    assertTrue(response.getResultsCount() >= 1, "Expected at least one hit");

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    assertTrue(regions.size() >= 2, "Expected at least 2 excerpt regions, got " + regions.size());
    assertTrue(regions.size() <= 3, "Expected at most 3 excerpt regions, got " + regions.size());

    // Each region should contain the needle.
    for (ExcerptRegion region : regions) {
      assertTrue(
          region.getText().toLowerCase(Locale.ROOT).contains("needle"),
          "Excerpt region text should contain 'needle': " + region.getText().substring(0, 50));
    }

    // Regions should be sorted by document position.
    for (int i = 1; i < regions.size(); i++) {
      assertTrue(
          regions.get(i).getStartChar() > regions.get(i - 1).getStartChar(),
          "Regions should be sorted by position");
    }
  }

  @Test
  void excerptRegionMatchSpansAreExcerptRelative() throws Exception {
    String filler = "the quick brown fox jumps. ";
    String content = buildContent(filler, "needle", 8000);
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Test Document",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("needle");
    assertTrue(response.getResultsCount() >= 1);

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    assertTrue(regions.size() >= 1, "Expected at least 1 excerpt region");

    ExcerptRegion region = regions.get(0);
    assertTrue(region.getMatchSpansCount() > 0, "Expected match spans within region");

    // Verify match spans are relative to excerpt text, not full document.
    var span = region.getMatchSpans(0);
    assertTrue(span.getStartChar() >= 0, "Span start should be >= 0");
    assertTrue(
        span.getEndChar() <= region.getText().length(),
        "Span end should be <= excerpt text length");
    String highlighted = region.getText().substring(span.getStartChar(), span.getEndChar());
    assertEquals(
        "needle",
        highlighted.toLowerCase(Locale.ROOT),
        "Span should highlight the matched term");
  }

  @Test
  void excerptRegionApproxLineIsReasonable() throws Exception {
    // Build content with known newline positions.
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append("Line ").append(i + 1).append(" filler text here.\n");
    }
    // Insert needle at around line 201.
    sb.append("This line contains the needle term.\n");
    for (int i = 0; i < 50; i++) {
      sb.append("More filler line ").append(i).append(".\n");
    }
    String content = sb.toString();
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Test Document",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("needle");
    assertTrue(response.getResultsCount() >= 1);

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    assertTrue(regions.size() >= 1, "Expected at least 1 excerpt region");

    ExcerptRegion region = regions.get(0);
    // The needle is at ~line 201 (200 filler lines + 1).
    assertTrue(region.getApproxLine() >= 180, "Expected approxLine near 200, got " + region.getApproxLine());
    assertTrue(region.getApproxLine() <= 220, "Expected approxLine near 200, got " + region.getApproxLine());
  }

  @Test
  void multiTermQueryProducesExcerptRegions() throws Exception {
    // Two distinct terms at different positions — both should appear in excerpt regions.
    String filler = "the quick brown fox jumps over the lazy dog. ";
    StringBuilder sb = new StringBuilder();
    while (sb.length() < 6000) sb.append(filler);
    sb.append("needle ");
    while (sb.length() < 12000) sb.append(filler);
    sb.append("haystack ");
    while (sb.length() < 16000) sb.append(filler);
    String content = sb.toString();
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Test Document",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("needle haystack");
    assertTrue(response.getResultsCount() >= 1, "Expected at least one hit");

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    assertTrue(regions.size() >= 2, "Expected at least 2 regions for 2 separate terms, got " + regions.size());

    // Each region's text should contain at least one of the search terms.
    for (ExcerptRegion region : regions) {
      String text = region.getText().toLowerCase(Locale.ROOT);
      assertTrue(
          text.contains("needle") || text.contains("haystack"),
          "Excerpt region should contain 'needle' or 'haystack': " + text.substring(0, Math.min(50, text.length())));
    }
  }

  @Test
  void shortDocumentProducesSingleRegion() throws Exception {
    // Short document (< 400 chars) — all matches cluster into one region.
    String content = "Start. This has needle here and also needle there. End.";
    String preview = content;

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Short Doc",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("needle");
    assertTrue(response.getResultsCount() >= 1);

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    // Short document: all matches fit in one cluster → one region.
    assertEquals(1, regions.size(), "Expected exactly 1 region for short document");
    assertTrue(regions.get(0).getMatchSpansCount() >= 2, "Expected at least 2 match spans in the region");
  }

  @Test
  void noExcerptRegionsWhenFlagDisabled() throws Exception {
    String filler = "lorem ipsum dolor sit amet. ";
    String content = buildContent(filler, "needle", 8000);
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Test Document",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    // Search WITHOUT includeExcerpts flag.
    SearchResponse response =
        service.search(
            SearchRequest.newBuilder()
                .setQuery("needle")
                .setLimit(10)
                .setMode(SearchMode.SEARCH_MODE_TEXT)
                .setIncludeExcerpts(false)
                .build(),
            CallContext.none());
    assertNotNull(response);
    assertTrue(response.getResultsCount() >= 1);
    assertEquals(0, response.getResults(0).getExcerptRegionsCount(),
        "Expected no excerpt regions when includeExcerpts=false");
  }

  @Test
  void matchAtDocumentStartProducesValidRegion() throws Exception {
    // Needle at very beginning of document.
    String content = "needle is here at the start. " + "Filler text. ".repeat(500);
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Start Match Doc",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("needle");
    assertTrue(response.getResultsCount() >= 1);

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    assertTrue(regions.size() >= 1, "Expected at least 1 region");
    assertTrue(regions.get(0).getStartChar() == 0, "First region should start at 0");
    assertTrue(regions.get(0).getApproxLine() == 1, "First line should be 1");
  }

  @Test
  void excerptRegionSnapsToSentenceBoundary() throws Exception {
    // Build content with clear sentence structure. The needle is in a sentence
    // deep in the document, preceded by well-defined sentence boundaries.
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      sb.append("This is filler sentence number ").append(i).append(". ");
    }
    // At this point sb is ~7000+ chars. Insert the target sentence.
    sb.append("The needle appears exactly in this sentence. ");
    for (int i = 0; i < 100; i++) {
      sb.append("More filler sentence number ").append(i).append(". ");
    }
    String content = sb.toString();
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Sentence Boundary Test",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("needle");
    assertTrue(response.getResultsCount() >= 1);

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    assertTrue(regions.size() >= 1, "Expected at least 1 excerpt region");

    ExcerptRegion region = regions.get(0);
    String text = region.getText();

    // The excerpt should start at a sentence boundary — the first character
    // should be uppercase (start of a sentence), not mid-word.
    assertTrue(
        Character.isUpperCase(text.charAt(0)),
        "Excerpt should start at sentence boundary (uppercase), got: '"
            + text.substring(0, Math.min(40, text.length())) + "'");

    // The excerpt should end at or near a sentence boundary (period + space or end of content).
    String trimmed = text.stripTrailing();
    assertTrue(
        trimmed.endsWith(".") || trimmed.endsWith("。"),
        "Excerpt should end near sentence boundary, got: '..."
            + trimmed.substring(Math.max(0, trimmed.length() - 40)) + "'");

    // The excerpt should contain the target sentence.
    assertTrue(
        text.contains("The needle appears exactly in this sentence."),
        "Excerpt should contain the target sentence");
  }

  @Test
  void diverseTermClusterPreferredOverRepeatedSingleTerm() throws Exception {
    // Build a document with:
    //   ~5000: "alpha" repeated 4x within 400 chars (high tf, low diversity)
    //   ~15000: "alpha" + "beta" once each (low tf, high diversity)
    // Scoring should prefer the diverse cluster.
    String filler = "the quick brown fox jumps over the lazy dog. ";
    StringBuilder sb = new StringBuilder();
    while (sb.length() < 5000) sb.append(filler);
    sb.append("alpha alpha alpha alpha ");
    while (sb.length() < 15000) sb.append(filler);
    sb.append("alpha beta ");
    while (sb.length() < 20000) sb.append(filler);
    String content = sb.toString();
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Diversity Test",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("alpha beta");
    assertTrue(response.getResultsCount() >= 1);

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    assertTrue(regions.size() >= 1, "Expected at least 1 excerpt region");

    // The diverse cluster (containing both "alpha" and "beta") must be selected.
    boolean hasDiverseRegion = false;
    for (ExcerptRegion region : regions) {
      String text = region.getText().toLowerCase(Locale.ROOT);
      if (text.contains("alpha") && text.contains("beta")) {
        hasDiverseRegion = true;
        break;
      }
    }
    assertTrue(hasDiverseRegion,
        "Expected a region containing both 'alpha' and 'beta' (diverse cluster)");
  }

  @Test
  void rareTermClusterPreferredOverCommonTermCluster() throws Exception {
    // Index 50 documents containing "common", only 1 containing "rare".
    // Target document has 4 clusters competing for 3 slots:
    //   ~5000, ~9000, ~13000: "common" 1x each (low IDF)
    //   ~17000: "rare" 1x (high IDF)
    // IDF for "rare" >> "common", so the rare cluster should make the top 3
    // despite being last positionally.
    String filler = "the quick brown fox jumps over the lazy dog. ";

    // Index 50 background documents with "common"
    for (int i = 0; i < 50; i++) {
      StringBuilder bgSb = new StringBuilder();
      while (bgSb.length() < 2000) bgSb.append(filler);
      bgSb.append("common ");
      while (bgSb.length() < 4000) bgSb.append(filler);
      lifecycle.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "bg-" + i,
                  SchemaFields.DOC_UID, "bg-" + i + "#0",
                  SchemaFields.TITLE, "Background " + i,
                  SchemaFields.CONTENT, bgSb.toString(),
                  SchemaFields.CONTENT_PREVIEW, bgSb.substring(0, Math.min(4096, bgSb.length())))));
    }

    // Target document: 3 "common" clusters + 1 "rare" cluster (4 total, only 3 fit)
    StringBuilder sb = new StringBuilder();
    while (sb.length() < 5000) sb.append(filler);
    sb.append("common ");
    while (sb.length() < 9000) sb.append(filler);
    sb.append("common ");
    while (sb.length() < 13000) sb.append(filler);
    sb.append("common ");
    while (sb.length() < 17000) sb.append(filler);
    sb.append("rare ");
    while (sb.length() < 22000) sb.append(filler);
    String content = sb.toString();

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "target",
                SchemaFields.DOC_UID, "target#0",
                SchemaFields.TITLE, "Target Document",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW,
                    content.substring(0, Math.min(4096, content.length())))));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("common rare");

    // Find the target document's hit.
    var targetHit =
        response.getResultsList().stream()
            .filter(r -> "target".equals(r.getFieldsOrDefault("doc_id", "")))
            .findFirst()
            .orElse(null);
    assertNotNull(targetHit, "Target document should be in results");

    List<ExcerptRegion> regions = targetHit.getExcerptRegionsList();
    assertEquals(3, regions.size(), "Expected exactly 3 regions (4 clusters, 3 slots)");

    // The region containing "rare" must be among the selected 3 (IDF-boosted over one "common").
    boolean hasRareRegion = false;
    for (ExcerptRegion region : regions) {
      if (region.getText().toLowerCase(Locale.ROOT).contains("rare")) {
        hasRareRegion = true;
        break;
      }
    }
    assertTrue(hasRareRegion,
        "Expected 'rare' region to be selected over one 'common' cluster (higher IDF)");
  }

  @Test
  void positionBiasBreaksTiesForIdenticalClusters() throws Exception {
    // Four identical single-term clusters at ~3000, ~8000, ~13000, ~18000.
    // All have the same term diversity (1 unique term), same IDF, same tf (1).
    // Position bias should prefer the 3 earliest, dropping the one at ~18000.
    String filler = "the quick brown fox jumps over the lazy dog. ";
    StringBuilder sb = new StringBuilder();
    while (sb.length() < 3000) sb.append(filler);
    sb.append("needle ");
    while (sb.length() < 8000) sb.append(filler);
    sb.append("needle ");
    while (sb.length() < 13000) sb.append(filler);
    sb.append("needle ");
    while (sb.length() < 18000) sb.append(filler);
    sb.append("needle ");
    while (sb.length() < 23000) sb.append(filler);
    String content = sb.toString();
    String preview = content.substring(0, Math.min(4096, content.length()));

    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.TITLE, "Position Test",
                SchemaFields.CONTENT, content,
                SchemaFields.CONTENT_PREVIEW, preview)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    SearchResponse response = search("needle");
    assertTrue(response.getResultsCount() >= 1);

    var hit = response.getResults(0);
    List<ExcerptRegion> regions = hit.getExcerptRegionsList();
    assertEquals(3, regions.size(), "Expected exactly 3 regions (4 clusters, 3 slots)");

    // All 3 selected regions should be from the first 3 clusters (position bias).
    // The last region's end should be well before position 18000.
    assertTrue(regions.get(2).getStartChar() < 15000,
        "Last selected region should be before ~18000 (position bias dropped the furthest), got "
            + regions.get(2).getStartChar());
  }
}
