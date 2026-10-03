/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.QualitySignals;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.knowledge.SearchTrace.Degradation;
import io.justsearch.app.api.knowledge.SearchTrace.HitStage;
import io.justsearch.app.api.knowledge.SearchTrace.Qpp;
import io.justsearch.app.api.knowledge.SearchTrace.StageId;
import io.justsearch.app.api.knowledge.SearchTrace.StageStatus;
import io.justsearch.app.api.knowledge.SearchTrace.TraceStage;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 658 — conformance test for {@link McpEvidenceProjection} (the execution-surface register
 * guard for the MCP evidence projection). Asserts the projection is a total, correctly-typed view of
 * the canonical {@link SearchTrace} and {@code ContextCitation}/{@code ContextResult} records: every
 * evidence field surfaces at the agent altitude, enum values serialize by their stable
 * {@code wireId}/{@code wireValue} (not the Java enum name), and the numeric detail tier appears only
 * when populated. Pure-function projection → no backend needed (mirrors SearchTraceSpanProjectionTest).
 */
@DisplayName("McpEvidenceProjection: agent-surface evidence is a total projection of the canonical records")
final class McpEvidenceProjectionTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return (Map<String, Object>) o;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object o) {
    return (List<Object>) o;
  }

  /**
   * Reflective totality guard — the Java analogue of the FE's {@code assertFieldRoles} pattern
   * (evidenceProjection.ts). Asserts every record component of {@code type} is a key in
   * {@code projectedSlice} unless it is declared intentionally {@code elided}. This makes the
   * silent-field-drop class unrepresentable: adding a field to a canonical evidence record both breaks
   * the maximal-fixture constructor arity below (forcing a fixture update) AND trips this guard until
   * the projection actually surfaces the field (tempdoc 658 post-review hardening).
   */
  private static void assertCovers(
      Class<? extends Record> type, Map<String, Object> projectedSlice, Set<String> elided) {
    for (RecordComponent rc : type.getRecordComponents()) {
      if (elided.contains(rc.getName())) {
        continue;
      }
      assertTrue(
          projectedSlice.containsKey(rc.getName()),
          type.getSimpleName()
              + " field '"
              + rc.getName()
              + "' is not projected into the MCP evidence — either project it or add it to the"
              + " declared elided set with a reason.");
    }
  }

  @Test
  @DisplayName("search: projects query-level trace (mode/decision/qpp/degradation + stages by wireId)")
  void searchProjectsQueryLevelTrace() {
    SearchTrace trace =
        new SearchTrace(
            SearchTrace.SCHEMA_VERSION,
            "HYBRID",
            "multi_leg",
            new Qpp(1.5f, 2.0f, 3.0f),
            new Degradation(true, "FINGERPRINT_MISMATCH", true, "NO_EMBEDDING_SERVICE", false, "absent"),
            List.of(
                new TraceStage(StageId.SPARSE_RETRIEVAL, StageStatus.EXECUTED, null, 5L, null, 42L),
                new TraceStage(
                    StageId.DENSE_RETRIEVAL, StageStatus.SKIPPED, "vector_blocked", null, null, null)));
    KnowledgeSearchResponse resp =
        new KnowledgeSearchResponse(
            1L, 1L, 5L, List.of(), null, null, null, null, null, null, null, trace, null);

    Map<String, Object> evidence = McpEvidenceProjection.searchEvidence(resp, false);
    Map<String, Object> t = asMap(evidence.get("searchTrace"));

    assertEquals("HYBRID", t.get("effectiveMode"));
    assertEquals("multi_leg", t.get("decisionKind"));
    Map<String, Object> qpp = asMap(t.get("qpp"));
    assertEquals(1.5f, qpp.get("maxIdf"));
    assertEquals(2.0f, qpp.get("avgIctf"));
    assertEquals(3.0f, qpp.get("queryScope"));

    Map<String, Object> deg = asMap(t.get("degradation"));
    assertEquals(true, deg.get("vectorBlocked"));
    assertEquals("FINGERPRINT_MISMATCH", deg.get("vectorBlockedReason"));
    assertEquals(true, deg.get("hybridFallback"));
    assertEquals("NO_EMBEDDING_SERVICE", deg.get("hybridFallbackReason"));
    assertEquals(false, deg.get("spladeExecuted"));
    assertEquals("absent", deg.get("spladeSkipReason"));

    List<Object> stages = asList(t.get("stages"));
    assertEquals(2, stages.size());
    Map<String, Object> s0 = asMap(stages.get(0));
    // The stable wireId, NOT the Java enum name — this is the projection's load-bearing correctness.
    assertEquals("sparse-retrieval", s0.get("id"));
    assertEquals("executed", s0.get("status"));
    assertEquals(5L, s0.get("ms"));
    assertEquals(42L, s0.get("cardinality"));
    Map<String, Object> s1 = asMap(stages.get(1));
    assertEquals("dense-retrieval", s1.get("id"));
    assertEquals("skipped", s1.get("status"));
    assertEquals("vector_blocked", s1.get("reason"));
    assertNull(s1.get("ms"));
  }

  @Test
  void rebuildPauseKeepsBothTraceReasons() {
    SearchTrace trace =
        new SearchTrace(
            SearchTrace.SCHEMA_VERSION,
            "HYBRID",
            null,
            null,
            new Degradation(true, "REBUILD_IN_PROGRESS", true, "REBUILD_IN_PROGRESS", false, null),
            List.of());
    KnowledgeSearchResponse response =
        new KnowledgeSearchResponse(
            0L, 0L, 1L, List.of(), null, null, null, null, null, null, null, trace, null);
    Map<String, Object> evidence = McpEvidenceProjection.searchEvidence(response, false);
    Map<String, Object> degradation = asMap(asMap(evidence.get("searchTrace")).get("degradation"));
    assertEquals("REBUILD_IN_PROGRESS", degradation.get("vectorBlockedReason"));
    assertEquals("REBUILD_IN_PROGRESS", degradation.get("hybridFallbackReason"));
  }

  @Test
  @DisplayName(
      "search: per-hit trace + fusion legScores under detail=true; numeric detail only when present")
  void searchProjectsPerHitTraceAndLegScores() {
    HitStage sparse = new HitStage(StageId.SPARSE_RETRIEVAL, 1, 3.3f, null);
    HitStage fused = new HitStage(StageId.FUSION, 1, 0.9f, Map.of("cc_weight_sparse", 0.6f));
    HitStage splade = new HitStage(StageId.SPLADE_RETRIEVAL, 2, 1.7f, null);
    KnowledgeSearchResponse.Hit hit =
        new KnowledgeSearchResponse.Hit(
            "doc-1",
            0.9d,
            Map.of("title", "Troubleshooting", "path", "help/troubleshooting.md"),
            List.of(),
            List.of(),
            List.of(),
            List.of(sparse, fused, splade));
    KnowledgeSearchResponse resp =
        new KnowledgeSearchResponse(
            1L, 1L, 5L, List.of(hit), null, null, null, null, null, null, null, null, null);

    Map<String, Object> evidence = McpEvidenceProjection.searchEvidence(resp, true);
    List<Object> results = asList(evidence.get("results"));
    assertEquals(1, results.size());
    Map<String, Object> h = asMap(results.get(0));
    assertEquals("doc-1", h.get("id"));
    assertEquals("Troubleshooting", h.get("title"));
    assertEquals("help/troubleshooting.md", h.get("path"));
    assertEquals(0.9d, h.get("score"));

    List<Object> hitStages = asList(h.get("trace"));
    assertEquals(3, hitStages.size());
    Map<String, Object> hsSparse = asMap(hitStages.get(0));
    assertEquals("sparse-retrieval", hsSparse.get("id"));
    assertEquals(1, hsSparse.get("rank"));
    assertEquals(3.3f, hsSparse.get("score"));
    // Numeric detail tier absent on the sparse stage (no detail map), present on the fusion stage.
    assertFalse(hsSparse.containsKey("detail"));
    assertTrue(asMap(hitStages.get(1)).containsKey("detail"));

    Map<String, Object> legs = asMap(h.get("legScores"));
    assertEquals(3.3f, legs.get("sparse"));
    assertEquals(0f, legs.get("dense"));
    // splade must be projected + carry the SPLADE leg score (previously never asserted anywhere).
    assertEquals(1.7f, legs.get("splade"));
    assertEquals(0.9f, legs.get("fused"));
  }

  @Test
  @DisplayName("answer: projects every ContextCitation field + the quality/degradation signals")
  void answerProjectsCitationsAndQuality() {
    ContextCitation cite =
        new ContextCitation(
            "doc-42", 2, 5, 100, 260, 0.87f, "an excerpt", 12, 18, "Overview", 2,
            ContextInclusion.ABSENT);
    ContextResult result =
        new ContextResult(
            "assembled context",
            3,
            7,
            0,
            List.of(cite),
            "HYBRID",
            "HYBRID_AVAILABLE",
            false,
            List.of(),
            new QualitySignals(0.87f, 0.1f, 0.42f, 7, 3));

    Map<String, Object> evidence = McpEvidenceProjection.answerEvidence(result);

    List<Object> citations = asList(evidence.get("citations"));
    assertEquals(1, citations.size());
    Map<String, Object> c = asMap(citations.get(0));
    assertEquals("doc-42", c.get("parentDocId"));
    assertEquals(2, c.get("chunkIndex"));
    assertEquals(5, c.get("chunkTotal"));
    assertEquals(100, c.get("startChar"));
    assertEquals(260, c.get("endChar"));
    assertEquals(0.87f, c.get("score"));
    assertEquals("an excerpt", c.get("excerpt"));
    assertEquals(12, c.get("startLine"));
    assertEquals(18, c.get("endLine"));
    assertEquals("Overview", c.get("headingText"));
    assertEquals(2, c.get("headingLevel"));
    // Tempdoc 849 (review F2): this citation is ABSENT, so the map must carry NO inclusion key.
    // Without this, deleting the emitter's absent-guard passes the whole suite — the totality guard
    // only ever sees the maximal (resolved) fixture, so it cannot notice absence being fabricated.
    assertFalse(
        c.containsKey("inclusion"),
        "an unresolved inclusion must project as absence, not as a state: " + c);

    // Every quality field is projected — the full ContextResult counts + all five QualitySignals
    // fields (guards against a silent-drop regression like the one this test was strengthened for).
    Map<String, Object> quality = asMap(evidence.get("quality"));
    assertEquals(7, quality.get("chunksFound"));
    assertEquals(3, quality.get("chunksUsed"));
    assertEquals("HYBRID", quality.get("retrievalMode"));
    assertEquals("HYBRID_AVAILABLE", quality.get("retrievalModeReason"));
    assertEquals(false, quality.get("contextTruncated"));
    assertEquals(0.42f, quality.get("retrievalCoverage"));
    assertEquals(0.87f, quality.get("bestChunkScore"));
    assertEquals(0.1f, quality.get("scoreGap"));
    assertEquals(7, quality.get("chunksConsidered"));
    assertEquals(3, quality.get("chunksIncluded"));
  }

  @Test
  @DisplayName("answer: full-doc fallback (empty citations) projects an empty citation list, not null")
  void answerFallbackEmptyCitations() {
    ContextResult fallback =
        new ContextResult(
            "full doc", 0, 0, 1, List.of(), "FULLTEXT_FALLBACK", "NO_CHUNKS_FOUND", false, List.of());
    Map<String, Object> evidence = McpEvidenceProjection.answerEvidence(fallback);
    assertTrue(asList(evidence.get("citations")).isEmpty());
    assertEquals("FULLTEXT_FALLBACK", asMap(evidence.get("quality")).get("retrievalMode"));
  }

  /**
   * Tempdoc 770 — the maximal fixture shared by the two halves of the totality guard: EVERY
   * per-hit component non-null and non-empty, so the projection's null/empty-omission never hides
   * a component from the reflective check.
   *
   * <p>This matters for the subset half specifically: if {@code matchedFields}, {@code matchSpans}
   * (→ {@code matchedTerms}) or {@code excerptRegions} were left empty here, they would be absent
   * from BOTH tiers, the computed omitted-set would still be exactly {@code {trace, legScores}},
   * and a future change gating one of them behind {@code detail} would leave the guard green while
   * the field silently stopped shipping by default. Populating them is what makes the guard bite.
   *
   * <p>{@code id} is deliberately distinct from {@code path} (the projection elides an {@code id}
   * equal to the path), so both identity fields are present to be covered.
   */
  private static KnowledgeSearchResponse maximalSearchResponse() {
    TraceStage stage =
        new TraceStage(StageId.FUSION, StageStatus.EXECUTED, "reason", 5L, "fusion-detail", 12L);
    SearchTrace trace =
        new SearchTrace(
            SearchTrace.SCHEMA_VERSION,
            "HYBRID",
            "multi_leg",
            new Qpp(1.5f, 2.0f, 3.0f),
            new Degradation(true, "R1", true, "R2", true, "R3"),
            List.of(stage));
    HitStage hitStage = new HitStage(StageId.FUSION, 1, 0.9f, Map.of("cc", 0.9f));
    // Terms long enough and non-stopword, so McpSearchResultFormatter#filterInformative keeps them
    // and `matchedTerms` is genuinely non-empty in the projection.
    KnowledgeSearchResponse.MatchSpan span =
        new KnowledgeSearchResponse.MatchSpan("content", 4, 13, "diagnostic");
    KnowledgeSearchResponse.ExcerptRegion region =
        new KnowledgeSearchResponse.ExcerptRegion(
            "a diagnostic excerpt body", 0, 25, 3, List.of(span));
    KnowledgeSearchResponse.Hit hit =
        new KnowledgeSearchResponse.Hit(
            "doc-1",
            0.9d,
            Map.of("title", "T", "path", "P"),
            List.of("content", "title"),
            List.of(span),
            List.of(region),
            List.of(hitStage));
    return new KnowledgeSearchResponse(
        1L, 1L, 5L, List.of(hit), null, null, null, null, null, null, null, trace, null);
  }

  @Test
  @DisplayName(
      "the maximal fixture really is maximal — every per-hit component the projection can emit is"
          + " present in BOTH tiers, so the subset guard can bite (tempdoc 770 §F)")
  void maximalFixtureProjectsEveryPerHitComponent() {
    KnowledgeSearchResponse resp = maximalSearchResponse();
    for (boolean includeDetail : new boolean[] {false, true}) {
      Map<String, Object> hit =
          asMap(asList(McpEvidenceProjection.searchEvidence(resp, includeDetail).get("results")).get(0));
      for (String key : List.of("id", "path", "title", "score", "matchedTerms", "matchedFields",
          "excerpts")) {
        assertTrue(
            hit.containsKey(key),
            "maximal fixture must project '" + key + "' (detail=" + includeDetail + ") — an empty"
                + " component here would silently exempt it from the subset guard");
      }
    }
  }

  @Test
  @DisplayName(
      "totality (b): the DEFAULT tier omits exactly {trace, legScores} relative to the detail tier"
          + " — nothing else silently stops shipping (tempdoc 770)")
  void defaultTierOmitsExactlyTheProvenanceBlock() {
    KnowledgeSearchResponse resp = maximalSearchResponse();

    Map<String, Object> withDetail = McpEvidenceProjection.searchEvidence(resp, true);
    Map<String, Object> byDefault = McpEvidenceProjection.searchEvidence(resp, false);

    // Response-level shape is identical between tiers — only the per-hit block is tiered.
    assertEquals(withDetail.keySet(), byDefault.keySet());

    Map<String, Object> detailHit = asMap(asList(withDetail.get("results")).get(0));
    Map<String, Object> defaultHit = asMap(asList(byDefault.get("results")).get(0));

    Set<String> omitted = new java.util.LinkedHashSet<>(detailHit.keySet());
    omitted.removeAll(defaultHit.keySet());
    assertEquals(
        Set.of("trace", "legScores"),
        omitted,
        "the default tier must omit the ranking-provenance block and nothing else");
    assertTrue(
        defaultHit.keySet().containsAll(
            detailHit.keySet().stream().filter(k -> !omitted.contains(k)).toList()),
        "the default tier must add no field the detail tier lacks");

    // Every retained field is byte-identical between tiers — the gate elides, it does not reshape.
    for (String key : defaultHit.keySet()) {
      assertEquals(detailHit.get(key), defaultHit.get(key), "field '" + key + "' differs by tier");
    }
  }

  @Test
  @DisplayName(
      "search: excerpts survive BOTH tiers (the only document text the agent receives); `path` is"
          + " always emitted and `id` only when it differs from it (tempdoc 770)")
  void excerptsAreUngatedAndIdIsElidedWhenEqualToPath() {
    KnowledgeSearchResponse.ExcerptRegion region =
        new KnowledgeSearchResponse.ExcerptRegion("the excerpt body", 0, 16, 1, List.of());
    // Hit A: id differs from path (a non-filesystem source class) → id is informative, keep both.
    KnowledgeSearchResponse.Hit distinctId =
        new KnowledgeSearchResponse.Hit(
            "doc-1", 0.9d, Map.of("path", "C:/corpus/a.md"),
            List.of(), List.of(), List.of(region),
            List.of(new HitStage(StageId.FUSION, 1, 0.9f, null)));
    // Hit B: id IS the path (the measured case — 14,617/14,617 hits) → duplicate. `path` is the
    // field kept: it is the affordance-bearing name the agent can act on.
    KnowledgeSearchResponse.Hit idEqualsPath =
        new KnowledgeSearchResponse.Hit(
            "C:/corpus/b.md", 0.8d, Map.of("path", "C:/corpus/b.md"),
            List.of(), List.of(), List.of(region),
            List.of(new HitStage(StageId.FUSION, 2, 0.8f, null)));
    // Hit C: no path at all → `id` is the only identity available, so it must still ship.
    KnowledgeSearchResponse.Hit noPath =
        new KnowledgeSearchResponse.Hit(
            "urn:source:c", 0.7d, Map.of(),
            List.of(), List.of(), List.of(region),
            List.of(new HitStage(StageId.FUSION, 3, 0.7f, null)));
    KnowledgeSearchResponse resp =
        new KnowledgeSearchResponse(
            3L, 3L, 5L, List.of(distinctId, idEqualsPath, noPath),
            null, null, null, null, null, null, null, null, null);

    for (boolean includeDetail : new boolean[] {false, true}) {
      List<Object> results =
          asList(McpEvidenceProjection.searchEvidence(resp, includeDetail).get("results"));

      Map<String, Object> a = asMap(results.get(0));
      assertEquals("C:/corpus/a.md", a.get("path"), "path is always emitted when present");
      assertEquals("doc-1", a.get("id"), "an id that is not the path is informative — keep it");
      assertEquals(
          "the excerpt body",
          asMap(asList(a.get("excerpts")).get(0)).get("text"),
          "excerpts must never be gated (detail=" + includeDetail + ")");

      Map<String, Object> b = asMap(results.get(1));
      assertEquals("C:/corpus/b.md", b.get("path"), "path survives — it is the actionable name");
      assertFalse(b.containsKey("id"), "id equal to path is a verbatim duplicate — elide it");
      assertFalse(asList(b.get("excerpts")).isEmpty(), "excerpts must never be gated");

      Map<String, Object> c = asMap(results.get(2));
      assertEquals("urn:source:c", c.get("id"), "with no path, id is the only identity — keep it");
      assertFalse(c.containsKey("path"), "no path to emit");
    }
  }

  @Test
  @DisplayName(
      "totality (a): with detail=true, every field of every canonical evidence record is projected"
          + " (reflective guard)")
  void projectionCoversEveryEvidenceField() {
    // Tempdoc 770: totality is asserted over the DETAIL tier — the union of what ships — with the
    // default tier's exact subset relationship pinned separately by
    // defaultTierOmitsExactlyTheProvenanceBlock(). A totality guard must describe what actually
    // ships, not a test-only path (770 §G).
    Map<String, Object> searchEvidence =
        McpEvidenceProjection.searchEvidence(maximalSearchResponse(), true);

    Map<String, Object> traceMap = asMap(searchEvidence.get("searchTrace"));
    // `version` is the structural-compat hint the FE explain panel also elides (searchTraceExplain.ts).
    assertCovers(SearchTrace.class, traceMap, Set.of("version"));
    assertCovers(Qpp.class, asMap(traceMap.get("qpp")), Set.of());
    assertCovers(Degradation.class, asMap(traceMap.get("degradation")), Set.of());
    assertCovers(TraceStage.class, asMap(asList(traceMap.get("stages")).get(0)), Set.of());
    Map<String, Object> hitMap = asMap(asList(searchEvidence.get("results")).get(0));
    assertCovers(HitStage.class, asMap(asList(hitMap.get("trace")).get(0)), Set.of());
    // LegScores is a canonical record projected into the per-hit `legScores` map — the exact 4-field
    // hand-mapping shape that caused Defect 2, so it is guarded reflectively too (all four legs incl.
    // splade must be a key).
    assertCovers(SearchTrace.LegScores.class, asMap(hitMap.get("legScores")), Set.of());

    // Tempdoc 849: the MAXIMAL fixture carries a RESOLVED inclusion. Absence is expressed by
    // omitting the key (the same discipline `headingText` already follows), so a fixture built
    // ABSENT would let the totality guard pass while the field is never projected at all.
    ContextCitation cite =
        new ContextCitation(
            "doc-42", 2, 5, 100, 260, 0.87f, "excerpt", 12, 18, "Overview", 2,
            ContextInclusion.partial(120));
    ContextResult result =
        new ContextResult(
            "ctx", 3, 7, 0, List.of(cite), "HYBRID", "HYBRID_AVAILABLE", false, List.of(),
            new QualitySignals(0.87f, 0.1f, 0.42f, 7, 3));
    Map<String, Object> answerEvidence = McpEvidenceProjection.answerEvidence(result);
    Map<String, Object> citationMap = asMap(asList(answerEvidence.get("citations")).get(0));
    assertCovers(ContextCitation.class, citationMap, Set.of());
    assertCovers(ContextInclusion.class, asMap(citationMap.get("inclusion")), Set.of());
    // The `quality` map is a SUPERSET (QualitySignals fields + ContextResult counts) — assert it covers
    // every QualitySignals component.
    assertCovers(QualitySignals.class, asMap(answerEvidence.get("quality")), Set.of());

    // Intentionally NOT reflectively guarded: KnowledgeSearchResponse.Hit and ContextResult are
    // selective carriers (they surface identity + the nested evidence records above, not every field —
    // e.g. ContextResult.sections is not ranking-evidence). Their evidence-bearing content is the
    // nested records this test already covers. (Tempdoc 725 W1: Hit.matchSpans/excerptRegions ARE now
    // projected — as matchedTerms/matchedFields/excerpts, see McpSearchTraceLegibilityTest — but Hit
    // stays a selective, non-reflectively-guarded carrier overall.)
  }
}
