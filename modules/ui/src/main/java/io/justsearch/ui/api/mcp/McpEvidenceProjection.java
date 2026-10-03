/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 658 — projects the canonical retrieval-evidence records onto the agent-facing MCP
 * {@code structuredContent} channel.
 *
 * <p>The evidence records — {@link SearchTrace} (ranking "why") and {@link ContextCitation}
 * (RAG-answer citations) — are already produced and projected to REST, the FE explain panel, OTel
 * spans, and eval, and are governed by the execution-surface register (tempdoc 553). The MCP tool
 * surface holds the fully-populated records but historically dropped them, formatting text only. This
 * is the missing projection: it re-authors nothing, it reads the canonical records and shapes the
 * agent-facing view.
 *
 * <p>Deliberately builds explicit {@code Map}/{@code List} structures via record accessors
 * ({@code StageId.wireId()}, {@code StageStatus.wireValue()}) rather than emitting the record objects
 * directly. This makes the wire shape independent of the MCP response serializer's Jackson-annotation
 * processing (the handler uses a different Jackson generation than the {@code com.fasterxml}
 * annotations on the records), and it is what gives the projection full control of the agent altitude.
 *
 * <p>Altitude (tempdoc 770): the query-level {@code searchTrace} and {@code degradation} summary are
 * always present. The PER-HIT ranking provenance — {@code trace} + {@code legScores}, measured at
 * 19.9% of the delivered search payload and carrying no document content — is a declared opt-in tier
 * gated by the MCP {@code detail} tool argument, which also gates the numeric
 * {@link SearchTrace.HitStage#detail()} sub-map upstream. Per-hit {@code excerpts} are NOT gated:
 * they are the only document text the agent receives.
 *
 * <p>Registered as a {@code projection} surface in {@code governance/execution-surfaces.v1.json};
 * guarded by {@code McpEvidenceProjectionTest}.
 */
public final class McpEvidenceProjection {

  private McpEvidenceProjection() {}

  /**
   * Tempdoc 735 W6 — {@code justsearch_search}'s structured evidence PLUS the tier-equivalence
   * fields ({@code hints}/{@code facets}/{@code coverage}/{@code truncated}) sourced from the
   * SAME {@link McpSearchResponseContent} instance the text renderer consumes, so the two tiers
   * cannot silently diverge. Per-hit {@code matchedTerms}/{@code matchedFields} are also read
   * from {@code content} rather than independently re-derived (the pre-735 duplication with
   * {@code McpToolSurface}'s own {@code filterInformative} call). This is the overload production
   * code ({@code McpToolSurface#callSearch}) calls; the response-only overload below is kept for the
   * reflective-totality guard ({@code McpEvidenceProjectionTest}), which asserts the projection's
   * coverage of the canonical evidence record independent of the response-level content-model
   * fields this increment adds.
   *
   * <p>{@code includeDetail} is the MCP {@code detail} tool argument: when false (the default) the
   * per-hit ranking-provenance tier ({@code trace}/{@code legScores}) is omitted (tempdoc 770).
   */
  public static Map<String, Object> searchEvidence(
      KnowledgeSearchResponse resp, McpSearchResponseContent content, boolean includeDetail) {
    Map<String, Object> out = new LinkedHashMap<>();
    SearchTrace trace = resp.searchTrace();
    if (trace != null) {
      out.put("searchTrace", projectTrace(trace));
      SearchTrace.Degradation degradation = trace.degradation();
      if (degradation != null) {
        out.put("degradation", projectDegradationSummary(degradation));
      }
    }
    List<Map<String, Object>> results = new ArrayList<>();
    List<KnowledgeSearchResponse.Hit> respHits = resp.results();
    List<McpSearchResponseContent.HitContent> hitContents = content.hits();
    for (int i = 0; i < respHits.size(); i++) {
      KnowledgeSearchResponse.Hit hit = respHits.get(i);
      McpSearchResponseContent.HitContent hc = hitContents.get(i);
      Map<String, Object> h = new LinkedHashMap<>();
      putIdentity(h, hit.id(), hc.path());
      if (!hc.title().isBlank()) {
        h.put("title", hc.title());
      }
      h.put("score", hit.score());
      if (!hc.matchedTerms().isEmpty()) {
        h.put("matchedTerms", hc.matchedTerms());
      }
      if (!hc.matchedFields().isEmpty()) {
        h.put("matchedFields", hc.matchedFields());
      }
      projectHitExcerptsAndTrace(hit, h, includeDetail);
      // Tempdoc 789 Phase 2 (F1): the per-hit continuation line on the structured tier, from the
      // same content instance the text renderer consumed.
      if (hc.continuation() != null) {
        h.put("continuation", hc.continuation());
      }
      // Tempdoc 771 item (b): the per-hit entity-carriage line on the structured tier, from the same
      // content instance the text renderer consumed — a client that delivers structuredContent
      // instead of text must receive the same entity names (735 G3), otherwise carriage would fix
      // hop-2 on one tier only.
      if (hc.entityCarriage() != null) {
        h.put("entityCarriage", hc.entityCarriage());
      }
      results.add(h);
    }
    out.put("results", results);

    // Tempdoc 789 Phase 2 (F2/F3): the response-level framings on the structured tier — a client
    // that delivers structuredContent instead of text must see the same framing (735 G3).
    if (content.evidenceHeader() != null) {
      out.put("evidenceHeader", content.evidenceHeader());
    }
    if (content.absenceNote() != null) {
      out.put("absenceNote", content.absenceNote());
    }

    // Tempdoc 735 W6: the tier-equivalence additions — previously text-only facts, now delivered
    // on structuredContent too, from the SAME content instance the text renderer consumed.
    out.put("hints", content.hints());
    out.put("facets", content.facets());
    Map<String, Object> coverage = new LinkedHashMap<>();
    coverage.put("totalHits", content.totalHits());
    coverage.put("shown", content.shownCount());
    coverage.put("tookMs", content.tookMs());
    out.put("coverage", coverage);
    out.put("truncated", content.truncated());
    // Facets-truncation MCP relay (tempdoc 821 §L.3): distinct from `truncated` above (that flag is
    // the RESULT-LIST truncation — totalHits > shownCount). This one is the facet SCAN's own
    // incompleteness (resp.facetsTruncated(), sourced via content.facetsTruncated() so both tiers
    // read the same computed fact per 735 G3) — true when per-value facet counts are a lower bound
    // rather than exact. Cause-neutral deliberately: the set of causes is not fixed — today the
    // maxDocsScanned cap, and 821's facets-engine work extends the flag to a mid-scan failure too —
    // so this relay states the effect (incomplete scan), not a specific cause. Previously read
    // nowhere in
    // the MCP layer even though the tool description tells the agent to trust facet counts for
    // filter discovery.
    out.put("facetsTruncated", content.facetsTruncated());
    putAppliedFilters(out, resp);
    return out;
  }

  /**
   * Structured evidence for {@code justsearch_search}: the query-level {@link SearchTrace} plus per-hit
   * ranking provenance (stage participation + fusion-leg scores).
   *
   * <p>Kept for {@code McpEvidenceProjectionTest}'s reflective totality guard over the canonical
   * {@link SearchTrace} record; production calls {@link #searchEvidence(KnowledgeSearchResponse,
   * McpSearchResponseContent, boolean)} (tempdoc 735 W6). Carries the same {@code includeDetail}
   * tier gate as the production overload, so the guard asserts totality over what actually ships
   * rather than over a test-only path (tempdoc 770 §G).
   */
  public static Map<String, Object> searchEvidence(
      KnowledgeSearchResponse resp, boolean includeDetail) {
    Map<String, Object> out = new LinkedHashMap<>();
    SearchTrace trace = resp.searchTrace();
    if (trace != null) {
      out.put("searchTrace", projectTrace(trace));
      // Tempdoc 725 W1: a response-level summary of SearchTrace.Degradation, alongside the
      // structural detail already nested under searchTrace.degradation — the agent-legible
      // "was this degraded" answer without navigating the full trace.
      SearchTrace.Degradation degradation = trace.degradation();
      if (degradation != null) {
        out.put("degradation", projectDegradationSummary(degradation));
      }
    }
    List<Map<String, Object>> results = new ArrayList<>();
    for (KnowledgeSearchResponse.Hit hit : resp.results()) {
      Map<String, Object> h = new LinkedHashMap<>();
      putIdentity(h, hit.id(), hit.fields().getOrDefault("path", ""));
      String title = hit.fields().getOrDefault("title", "");
      if (!title.isBlank()) {
        h.put("title", title);
      }
      h.put("score", hit.score());

      // Tempdoc 725 W1: the same informative-term filter that drives the text-block "Matched:"
      // line, projected onto structuredContent — matchedTerms/matchedFields/excerpts.
      List<KnowledgeSearchResponse.MatchSpan> informative =
          McpSearchResultFormatter.filterInformative(hit.matchSpans());
      if (!informative.isEmpty()) {
        h.put("matchedTerms", McpSearchResultFormatter.informativeTerms(informative));
      }
      if (hit.matchedFields() != null && !hit.matchedFields().isEmpty()) {
        h.put("matchedFields", hit.matchedFields());
      }
      projectHitExcerptsAndTrace(hit, h, includeDetail);
      results.add(h);
    }
    out.put("results", results);
    putAppliedFilters(out, resp);
    return out;
  }

  /**
   * The {@code appliedFilters} echo (366 §1b) on the agent-facing tier. REST has carried it since
   * 366 ({@code KnowledgeSearchController}) and the delivery governor preserves it through
   * truncation ({@code McpToolSurface.truncateResults}), but the MCP projection dropped it — so an
   * agent that scoped a search could not distinguish "the scope was honoured" from "the scope was
   * silently dropped" without inferring it from the returned rows.
   *
   * <p>Emitted on BOTH evidence tiers, and only when the response carries it (the response-level
   * record is null unless the request actually had filters). Projected to explicit maps for the
   * same reason the rest of this class is (see the class javadoc): the MCP serializer does not run
   * the records' Jackson annotations. Empty filter members are omitted — an agent reads "what was
   * scoped", not a wall of empty lists.
   */
  private static void putAppliedFilters(Map<String, Object> out, KnowledgeSearchResponse resp) {
    KnowledgeSearchResponse.AppliedFilters applied = resp.appliedFilters();
    if (applied == null) {
      return;
    }
    Map<String, Object> m = new LinkedHashMap<>();
    Map<String, Object> filters = projectFilters(applied.filters());
    if (!filters.isEmpty()) {
      m.put("filters", filters);
    }
    Map<String, Object> boost = projectFilters(applied.boostFilters());
    if (!boost.isEmpty()) {
      m.put("boostFilters", boost);
    }
    if (!m.isEmpty()) {
      out.put("appliedFilters", m);
    }
  }

  /** One {@code Filters} record as a map of only its set members. */
  private static Map<String, Object> projectFilters(KnowledgeSearchRequest.Filters f) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (f == null) {
      return m;
    }
    putIfPresent(m, "mime", f.mime());
    putIfPresent(m, "mimeBase", f.mimeBase());
    putIfPresent(m, "language", f.language());
    putIfPresent(m, "fileKind", f.fileKind());
    putIfPresent(m, "collection", f.collection());
    putIfPresent(m, "docIds", f.docIds());
    putIfPresent(m, "entityPersons", f.entityPersons());
    putIfPresent(m, "entityOrganizations", f.entityOrganizations());
    putIfPresent(m, "entityLocations", f.entityLocations());
    putIfPresent(m, "metaSource", f.metaSource());
    putIfPresent(m, "metaAuthor", f.metaAuthor());
    putIfPresent(m, "metaCategory", f.metaCategory());
    if (f.pathPrefix() != null && !f.pathPrefix().isBlank()) {
      m.put("pathPrefix", f.pathPrefix());
    }
    if (f.includeChunks() != null) {
      m.put("includeChunks", f.includeChunks());
    }
    putTimeRange(m, "modifiedAt", f.modifiedAt());
    putTimeRange(m, "metaPublishedAt", f.metaPublishedAt());
    return m;
  }

  private static void putIfPresent(Map<String, Object> m, String key, List<String> values) {
    if (values != null && !values.isEmpty()) {
      m.put(key, values);
    }
  }

  private static void putTimeRange(
      Map<String, Object> m, String key, KnowledgeSearchRequest.TimeRangeMs range) {
    if (range == null || (range.fromMs() == null && range.toMs() == null)) {
      return;
    }
    Map<String, Object> r = new LinkedHashMap<>();
    if (range.fromMs() != null) {
      r.put("fromMs", range.fromMs());
    }
    if (range.toMs() != null) {
      r.put("toMs", range.toMs());
    }
    m.put(key, r);
  }

  /**
   * Tempdoc 770 — the per-hit identity fields. Measured across 14,617 v5 hits, the worker doc-id
   * and the path were byte-identical in every one, so one of the two is a verbatim duplicate.
   *
   * <p>{@code path} is the one that survives: it is the affordance-bearing name (44.7% of
   * post-search Reads in the measured cohort target a path from the preceding search), and nothing
   * in the delivered channel tells a model that an opaque {@code id} happens to be a filesystem
   * path. {@code id} is emitted only when it carries information {@code path} does not — a source
   * class whose doc-id is not a path, or a hit with no path at all — so non-filesystem sources are
   * unaffected.
   */
  private static void putIdentity(Map<String, Object> h, String id, String path) {
    if (!path.isBlank()) {
      h.put("path", path);
    }
    if (path.isBlank() || !path.equals(id)) {
      h.put("id", id);
    }
  }

  /**
   * Per-hit excerpt + ranking-provenance projection shared by both {@code searchEvidence}
   * overloads — the mechanically identical half of the two per-hit loops, extracted so the
   * response-only overload (test-only) and the production content-model overload cannot
   * drift on excerpt/trace/legScores shape (tempdoc 735 W6 review MINOR-3).
   *
   * <p>{@code excerpts} is unconditional — it is the only document text the agent receives.
   * {@code trace}/{@code legScores} are the {@code detail}-gated provenance tier (tempdoc 770).
   */
  private static void projectHitExcerptsAndTrace(
      KnowledgeSearchResponse.Hit hit, Map<String, Object> h, boolean includeDetail) {
    if (hit.excerptRegions() != null && !hit.excerptRegions().isEmpty()) {
      List<Map<String, Object>> excerpts = new ArrayList<>();
      for (KnowledgeSearchResponse.ExcerptRegion region : hit.excerptRegions()) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("text", region.text());
        e.put("startChar", region.startChar());
        e.put("endChar", region.endChar());
        excerpts.add(e);
      }
      h.put("excerpts", excerpts);
    }

    List<SearchTrace.HitStage> hitTrace = hit.trace();
    if (includeDetail && hitTrace != null && !hitTrace.isEmpty()) {
      h.put("trace", projectHitStages(hitTrace));
      SearchTrace.LegScores legs = SearchTrace.legScores(hitTrace, (float) hit.score());
      Map<String, Object> ls = new LinkedHashMap<>();
      ls.put("sparse", legs.sparse());
      ls.put("dense", legs.dense());
      ls.put("splade", legs.splade());
      ls.put("fused", legs.fused());
      h.put("legScores", ls);
    }
  }

  /**
   * Structured evidence for {@code justsearch_answer}: the {@link ContextCitation} provenance list plus
   * the retrieval-quality/degradation signals from the {@link ContextResult}.
   */
  public static Map<String, Object> answerEvidence(ContextResult r) {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Map<String, Object>> citations = new ArrayList<>();
    for (ContextCitation c : r.citations()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("parentDocId", c.parentDocId());
      m.put("chunkIndex", c.chunkIndex());
      m.put("chunkTotal", c.chunkTotal());
      m.put("startChar", c.startChar());
      m.put("endChar", c.endChar());
      m.put("score", c.score());
      m.put("excerpt", c.excerpt());
      m.put("startLine", c.startLine());
      m.put("endLine", c.endLine());
      if (!c.headingText().isBlank()) {
        m.put("headingText", c.headingText());
      }
      m.put("headingLevel", c.headingLevel());
      // Tempdoc 849 §5.1 — retrieved is not received. Emitted ONLY when the head's cut resolved it;
      // an absent state emits no key at all, because saying nothing is what absence means (saying
      // "included" on the producer's behalf is the fabrication the record exists to remove).
      if (!c.inclusion().absent()) {
        Map<String, Object> inclusion = new LinkedHashMap<>();
        inclusion.put("state", c.inclusion().wireName());
        inclusion.put("includedChars", c.inclusion().includedChars());
        m.put("inclusion", Map.copyOf(inclusion));
      }
      citations.add(m);
    }
    out.put("citations", citations);

    Map<String, Object> quality = new LinkedHashMap<>();
    quality.put("chunksFound", r.chunksFound());
    quality.put("chunksUsed", r.chunksUsed());
    quality.put("retrievalMode", r.retrievalMode());
    quality.put("retrievalModeReason", r.retrievalModeReason());
    quality.put("contextTruncated", r.contextTruncated());
    // The full CRAG-style confidence signal set (tempdoc 658): all five QualitySignals fields, so an
    // agent can judge retrieval confidence, not just coverage.
    quality.put("retrievalCoverage", r.quality().retrievalCoverage());
    quality.put("bestChunkScore", r.quality().bestChunkScore());
    quality.put("scoreGap", r.quality().scoreGap());
    quality.put("chunksConsidered", r.quality().chunksConsidered());
    quality.put("chunksIncluded", r.quality().chunksIncluded());
    out.put("quality", quality);
    return out;
  }

  /**
   * Tempdoc 735 W6 — {@code justsearch_answer}'s structured evidence PLUS the tier-equivalence
   * fields ({@code hints}/{@code coverage}/{@code truncated}) sourced from the
   * SAME {@link McpAnswerResponseContent} instance the text renderer consumes. This is the
   * overload production code ({@code McpToolSurface#callAnswer}) calls; {@link
   * #answerEvidence(ContextResult)} is reused internally for the citation/quality projection.
   */
  public static Map<String, Object> answerEvidence(ContextResult r, McpAnswerResponseContent content) {
    Map<String, Object> out = answerEvidence(r);
    out.put("hints", content.hints());
    Map<String, Object> coverage = new LinkedHashMap<>();
    coverage.put("passages", content.passages());
    coverage.put("documents", content.distinctDocs());
    out.put("coverage", coverage);
    out.put("truncated", content.contextTruncated());
    // Tempdoc 789 Phase 2 (F2): the evidence-not-answer header reaches the structured tier too — a
    // client that delivers structuredContent instead of text must see the same framing, or the
    // probe arm would silently not apply to it (the 735 G3 tier-equivalence rule).
    if (content.evidenceHeader() != null) {
      out.put("evidenceHeader", content.evidenceHeader());
    }
    return out;
  }

  /**
   * Tempdoc 725 W1: response-level {vectorBlocked, hybridFallback, reasons} summary of {@link
   * SearchTrace.Degradation}. {@code reasons} collects vectorBlockedReason/hybridFallbackReason
   * only for the flags that are actually {@code true} (gated the same way as the text-block
   * degradation note in {@code McpToolSurface#appendDegradationNote}), so this summary never lists
   * a reason for a degradation mode that did not occur.
   */
  private static Map<String, Object> projectDegradationSummary(SearchTrace.Degradation d) {
    Map<String, Object> deg = new LinkedHashMap<>();
    deg.put("vectorBlocked", d.vectorBlocked());
    deg.put("hybridFallback", d.hybridFallback());
    List<String> reasons = new ArrayList<>();
    if (d.vectorBlocked() && d.vectorBlockedReason() != null && !d.vectorBlockedReason().isBlank()) {
      reasons.add(d.vectorBlockedReason());
    }
    if (d.hybridFallback()
        && d.hybridFallbackReason() != null
        && !d.hybridFallbackReason().isBlank()
        && !reasons.contains(d.hybridFallbackReason())) {
      reasons.add(d.hybridFallbackReason());
    }
    deg.put("reasons", reasons);
    return deg;
  }

  private static Map<String, Object> projectTrace(SearchTrace t) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (t.effectiveMode() != null) {
      m.put("effectiveMode", t.effectiveMode());
    }
    if (t.decisionKind() != null) {
      m.put("decisionKind", t.decisionKind());
    }
    SearchTrace.Qpp qpp = t.qpp();
    if (qpp != null) {
      Map<String, Object> q = new LinkedHashMap<>();
      q.put("maxIdf", qpp.maxIdf());
      q.put("avgIctf", qpp.avgIctf());
      q.put("queryScope", qpp.queryScope());
      m.put("qpp", q);
    }
    SearchTrace.Degradation d = t.degradation();
    if (d != null) {
      Map<String, Object> deg = new LinkedHashMap<>();
      deg.put("vectorBlocked", d.vectorBlocked());
      if (d.vectorBlockedReason() != null) {
        deg.put("vectorBlockedReason", d.vectorBlockedReason());
      }
      deg.put("hybridFallback", d.hybridFallback());
      if (d.hybridFallbackReason() != null) {
        deg.put("hybridFallbackReason", d.hybridFallbackReason());
      }
      deg.put("spladeExecuted", d.spladeExecuted());
      if (d.spladeSkipReason() != null) {
        deg.put("spladeSkipReason", d.spladeSkipReason());
      }
      m.put("degradation", deg);
    }
    List<Map<String, Object>> stages = new ArrayList<>();
    for (SearchTrace.TraceStage s : t.stages()) {
      Map<String, Object> sm = new LinkedHashMap<>();
      sm.put("id", s.id().wireId());
      sm.put("status", s.status().wireValue());
      if (s.reason() != null) {
        sm.put("reason", s.reason());
      }
      if (s.ms() != null) {
        sm.put("ms", s.ms());
      }
      if (s.detail() != null) {
        sm.put("detail", s.detail());
      }
      if (s.cardinality() != null) {
        sm.put("cardinality", s.cardinality());
      }
      stages.add(sm);
    }
    m.put("stages", stages);
    return m;
  }

  private static List<Map<String, Object>> projectHitStages(List<SearchTrace.HitStage> trace) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (SearchTrace.HitStage hs : trace) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", hs.id().wireId());
      if (hs.rank() != null) {
        m.put("rank", hs.rank());
      }
      if (hs.score() != null) {
        m.put("score", hs.score());
      }
      // The numeric detail tier — present only when the request set debug=true (the MCP `detail` arg).
      if (hs.detail() != null) {
        m.put("detail", hs.detail());
      }
      out.add(m);
    }
    return out;
  }
}
