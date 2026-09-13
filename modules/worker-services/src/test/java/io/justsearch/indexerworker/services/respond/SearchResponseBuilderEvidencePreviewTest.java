package io.justsearch.indexerworker.services.respond;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 774 Stage 2 (Item 1): the {@code search.evidence_preview.enabled} flag. When OFF (default
 * before the 775 §I flip, 2026-07-22) the builder is byte-identical to pre-774 — chunk-sourced hits
 * carry no {@code content_preview} and a merged hit keeps its parent head-of-doc preview. When ON
 * (the current default) the winning chunk's text (capped at 4096) REPLACES any head preview on
 * chunk-sourced hits, so the CE snippet source and the delivered preview are evidence-coherent
 * (§F.1-5). Each case here sets the flag explicitly, so the assertions are default-independent.
 */
final class SearchResponseBuilderEvidencePreviewTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  private static final FieldCatalogDef CATALOG = FieldCatalogDef.forChunkTesting(0);
  private static final int CAP = 4096;
  // Chunk text longer than the cap; "needle" up front keeps it excerpt-/span-eligible.
  private static final String LONG_CHUNK = "needle " + "x".repeat(6000);
  private static final String HEAD_PREVIEW = "head-of-doc preview for the merged parent";
  private static final String REPORT_CLUSTER =
      "report report report findings report report report data report analysis report.";
  private static final String REPORT_GAP = " padding padding padding padding ".repeat(20);
  private static final String ENTITY_CONTENT =
      REPORT_CLUSTER
          + REPORT_GAP
          + REPORT_CLUSTER
          + REPORT_GAP
          + REPORT_CLUSTER
          + REPORT_GAP
          + "the report was authored by Zorptannicus in chambers.";

  private RunningRuntime lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    lifecycle = IndexSchema.fromCatalog(CATALOG).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    lifecycle
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "parent-1",
                    SchemaFields.DOC_UID, "stable-parent-uid",
                    SchemaFields.TITLE, "Parent Title",
                    SchemaFields.CONTENT, ENTITY_CONTENT)));
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();
  }

  @AfterEach
  void tearDown() {
    if (lifecycle != null) lifecycle.close();
  }

  /** A bare-builder config with only the evidence-preview flag defaulted to the given value. */
  private static Supplier<ResolvedConfig> configWith(boolean evidencePreviewOn) {
    ResolvedConfigBuilder b = new ResolvedConfigBuilder();
    b.putDefault("search.evidence_preview.enabled", Boolean.toString(evidencePreviewOn));
    b.putDefault("search.evidence_span.enabled", "true");
    b.putDefault("search.evidence_span.entity_signal", "ner_membership");
    ResolvedConfig cfg = b.build();
    return () -> cfg;
  }

  private SearchResponseBuilder builderWith(boolean evidencePreviewOn) {
    return new SearchResponseBuilder(
        lifecycle.indexCountOps(),
        lifecycle.documentFieldOps(),
        lifecycle.textQueryOps(),
        lifecycle.facetingEngine(),
        lifecycle::indexAnalyzerOrNull,
        configWith(evidencePreviewOn));
  }

  /** Chunk-only hit (no head preview) + merged hit (carries parent head preview). */
  private static LuceneRuntimeTypes.SearchResult twoChunkHits() {
    List<LuceneRuntimeTypes.SearchHit> hits = new ArrayList<>();
    // Chunk-only hit.
    hits.add(
        new LuceneRuntimeTypes.SearchHit(
            "chunk:parent-1#0",
            1.0f,
            Map.of(
                SchemaFields.PARENT_DOC_ID, "parent-1",
                SchemaFields.IS_CHUNK, "true",
                SchemaFields.CHUNK_CONTENT, LONG_CHUNK)));
    // Merged hit: present in both branches, so it carries the parent head-of-doc preview.
    hits.add(
        new LuceneRuntimeTypes.SearchHit(
            "chunk:parent-1#1",
            0.9f,
            Map.of(
                SchemaFields.PARENT_DOC_ID, "parent-1",
                SchemaFields.IS_CHUNK, "true",
                SchemaFields.CONTENT_PREVIEW, HEAD_PREVIEW,
                SchemaFields.CHUNK_CONTENT, LONG_CHUNK)));
    return new LuceneRuntimeTypes.SearchResult(hits, hits.size(), 5L);
  }

  private SearchResponse build(SearchResponseBuilder builder) {
    PipelineConfig pipeline = PipelineConfig.newBuilder().setSparseEnabled(true).build();
    return builder
        .toGrpcResponseBuilder(
            twoChunkHits(), 5L, "needle", pipeline, null, /* includeExcerpts= */ true,
            /* includeDetail= */ false)
        .build();
  }

  @Test
  void offIsByteIdenticalToToday() {
    SearchResponse response = build(builderWith(false));

    SearchResult chunkOnly = response.getResults(0);
    assertFalse(
        chunkOnly.getFieldsMap().containsKey(SchemaFields.CONTENT_PREVIEW),
        "OFF: a chunk-only hit must carry NO content_preview (byte-identical to pre-774)");

    SearchResult merged = response.getResults(1);
    assertEquals(
        HEAD_PREVIEW,
        merged.getFieldsMap().get(SchemaFields.CONTENT_PREVIEW),
        "OFF: a merged hit keeps its parent head-of-doc preview unchanged");
  }

  @Test
  void onEmitsCappedChunkTextForChunkOnlyHit() {
    SearchResponse response = build(builderWith(true));

    SearchResult chunkOnly = response.getResults(0);
    String preview = chunkOnly.getFieldsMap().get(SchemaFields.CONTENT_PREVIEW);
    assertEquals(
        LONG_CHUNK.substring(0, CAP),
        preview,
        "ON: chunk-only hit's content_preview is the winning chunk's text capped at 4096");
    assertEquals(CAP, preview.length(), "ON: preview is capped at 4096 chars");
    assertEquals(
        "stable-parent-uid",
        chunkOnly.getFieldsMap().get(SchemaFields.DOC_UID),
        "a chunk-only result must carry its stable parent UID for Head-side feedback");
  }

  @Test
  void onOverwritesMergedHeadPreviewWithChunkText() {
    SearchResponse response = build(builderWith(true));

    SearchResult merged = response.getResults(1);
    String preview = merged.getFieldsMap().get(SchemaFields.CONTENT_PREVIEW);
    assertEquals(
        LONG_CHUNK.substring(0, CAP),
        preview,
        "ON: the chunk text REPLACES a merged whole-branch head preview");
    assertFalse(preview.startsWith(HEAD_PREVIEW), "ON: the head preview must not survive");
  }

  @Test
  void onKeepsSpansConsistentWithDeliveredPreview() {
    // The excerpt/span path runs against the SAME capped text delivered on the wire, so any
    // content_preview match span must fall within the delivered preview's length.
    SearchResponse response = build(builderWith(true));
    SearchResult chunkOnly = response.getResults(0);
    int previewLen = chunkOnly.getFieldsMap().get(SchemaFields.CONTENT_PREVIEW).length();
    for (var span : chunkOnly.getMatchSpansList()) {
      if (SchemaFields.CONTENT_PREVIEW.equals(span.getField())) {
        assertTrue(
            span.getEndChar() <= previewLen,
            "ON: a content_preview span must stay within the delivered (capped) preview");
      }
    }
  }

  @Test
  void rawMultiValuedEntitiesFeedNerMembershipEvidenceSelection() {
    // Four report clusters compete for the three excerpt slots. The final, otherwise weakest
    // cluster must survive because the retained raw NER field names its entity. Stored
    // multi-valued fields use " | "; the selector tokenizes the aggregate without splitting names.
    var hit =
        new LuceneRuntimeTypes.SearchHit(
            "parent-1",
            1.0f,
            Map.of(SchemaFields.ENTITY_PERSONS_RAW, "Zorptannicus | Another Person"));
    var result = new LuceneRuntimeTypes.SearchResult(List.of(hit), 1, 5L);
    PipelineConfig pipeline = PipelineConfig.newBuilder().setSparseEnabled(true).build();

    SearchResponse response =
        builderWith(true)
            .toGrpcResponseBuilder(
                result, 5L, "report", pipeline, null, /* includeExcerpts= */ true,
                /* includeDetail= */ false)
            .build();

    assertTrue(
        response.getResults(0).getExcerptRegionsList().stream()
            .anyMatch(region -> region.getText().contains("Zorptannicus")),
        "the retained raw entity field must keep the answer-bearing report cluster");
  }
}
