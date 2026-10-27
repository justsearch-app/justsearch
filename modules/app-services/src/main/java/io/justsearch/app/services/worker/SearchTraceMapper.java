/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.knowledge.CrossEncoderSkipReason;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.QueryType;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.ipc.SearchResult;
import java.util.concurrent.TimeUnit;

/**
 * Tempdoc 556 (F-C4.2): the search-execution trace projection, extracted verbatim from {@code
 * KnowledgeHttpApiAdapter} so the adapter stays a thin orchestrator. Pure, stateless mapping of the
 * worker proto trace + head-side stage state onto the canonical app-api {@code SearchTrace}, plus the
 * OpenInference doc projections used by the head's rerank spans.
 *
 * <p>All methods are package-private statics (the unit tests live in the same package and call them
 * directly). Behaviour is unchanged by the move — see tempdoc 549 for the trace design these implement.
 */
final class SearchTraceMapper {

  private SearchTraceMapper() {}

  /**
   * Tempdoc 549 (full thesis, Phase B/C per-hit): maps the worker's proto per-hit stage slice ({@code
   * SearchResult.trace}) onto the app-api {@code Hit.trace}, and appends the head's own per-hit
   * cross-encoder stage when this hit was reranked. Returns null only when neither contributed.
   */
  static java.util.List<io.justsearch.app.api.knowledge.SearchTrace.HitStage> mapHitStages(
      SearchResult sr, Float crossEncoderScore) {
    if (sr.getTraceCount() == 0 && crossEncoderScore == null) {
      return null;
    }
    java.util.List<io.justsearch.app.api.knowledge.SearchTrace.HitStage> out =
        new java.util.ArrayList<>(sr.getTraceCount() + 1);
    for (io.justsearch.ipc.HitStage ps : sr.getTraceList()) {
      out.add(
          new io.justsearch.app.api.knowledge.SearchTrace.HitStage(
              io.justsearch.app.api.knowledge.SearchTrace.StageId.fromWireId(ps.getId()),
              ps.hasRank() ? ps.getRank() : null,
              ps.hasScore() ? ps.getScore() : null,
              ps.getDetailMap().isEmpty() ? null : new java.util.HashMap<>(ps.getDetailMap())));
    }
    if (crossEncoderScore != null) {
      out.add(
          new io.justsearch.app.api.knowledge.SearchTrace.HitStage(
              io.justsearch.app.api.knowledge.SearchTrace.StageId.CROSS_ENCODER,
              null,
              crossEncoderScore,
              null));
    }
    return out;
  }

  // 549 Phase D1 NPE guard (unit-pinned): null-tolerant lookup — null map = cross-encoder skipped.
  static Float ceScoreFor(java.util.Map<String, Float> ceScoresByDocId, String docId) {
    return ceScoresByDocId == null ? null : ceScoresByDocId.get(docId);
  }

  // Tempdoc 553 Phase D (head): map head rerank results to the shared OpenInference Doc list.
  static java.util.List<io.justsearch.telemetry.OpenInferenceSpans.Doc> oiDocsOf(
      java.util.List<SearchResult> results) {
    int n = Math.min(results.size(), io.justsearch.telemetry.OpenInferenceSpans.MAX_DOCUMENTS);
    java.util.List<io.justsearch.telemetry.OpenInferenceSpans.Doc> docs = new java.util.ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      SearchResult sr = results.get(i);
      docs.add(
          new io.justsearch.telemetry.OpenInferenceSpans.Doc(
              sr.getId(), sr.getScore(), oiContent(sr)));
    }
    return docs;
  }

  // CE-scored output: the reranked docs in the cross-encoder's chosen order, carrying the CE score.
  static java.util.List<io.justsearch.telemetry.OpenInferenceSpans.Doc> ceOutputDocs(
      java.util.List<SearchResult> results,
      RerankResponse reranked) {
    java.util.List<io.justsearch.telemetry.OpenInferenceSpans.Doc> docs = new java.util.ArrayList<>();
    for (int origIdx : reranked.getSortedIndicesList()) {
      if (docs.size() >= io.justsearch.telemetry.OpenInferenceSpans.MAX_DOCUMENTS) {
        break;
      }
      if (origIdx >= 0 && origIdx < results.size() && origIdx < reranked.getScoresCount()) {
        SearchResult sr = results.get(origIdx);
        docs.add(
            new io.justsearch.telemetry.OpenInferenceSpans.Doc(
                sr.getId(), reranked.getScores(origIdx), oiContent(sr)));
      }
    }
    return docs;
  }

  private static String oiContent(SearchResult sr) {
    String title = sr.getFieldsMap().getOrDefault("title", "");
    String preview = sr.getFieldsMap().getOrDefault("content_preview", "");
    String c = (title + " " + preview).trim();
    return c.isEmpty() ? null : c;
  }

  // Package-private for unit test (the worker→head trace composition + StageId/StageStatus
  // wire mapping must not throw on the real wireIds/statuses the producers emit).
  static io.justsearch.app.api.knowledge.SearchTrace mapSearchTrace(
      io.justsearch.ipc.SearchResponse resp,
      java.util.List<io.justsearch.app.api.knowledge.SearchTrace.TraceStage> headStages) {
    java.util.List<io.justsearch.app.api.knowledge.SearchTrace.TraceStage> stages =
        new java.util.ArrayList<>();
    if (resp.hasSearchTrace()) {
      for (io.justsearch.ipc.TraceStage ps : resp.getSearchTrace().getStagesList()) {
        stages.add(
            new io.justsearch.app.api.knowledge.SearchTrace.TraceStage(
                io.justsearch.app.api.knowledge.SearchTrace.StageId.fromWireId(ps.getId()),
                io.justsearch.app.api.knowledge.SearchTrace.StageStatus.fromWire(ps.getStatus()),
                ps.getReason().isBlank() ? null : ps.getReason(),
                ps.hasMs() ? ps.getMs() : null,
                ps.getDetail().isBlank() ? null : ps.getDetail(),
                // Tempdoc 597: carry the per-stage funnel cardinality (matched / ranked-union).
                ps.hasCardinality() ? ps.getCardinality() : null));
      }
    }
    // Tempdoc 549 Phase E4: head stages are now SearchTrace.TraceStage directly.
    stages.addAll(headStages);
    // Tempdoc 549 Phase D: carry the query-level scalars the worker projected.
    io.justsearch.ipc.SearchTrace wt = resp.hasSearchTrace() ? resp.getSearchTrace() : null;
    String effectiveMode = wt != null && !wt.getEffectiveMode().isBlank() ? wt.getEffectiveMode() : null;
    String decisionKind = wt != null && !wt.getDecisionKind().isBlank() ? wt.getDecisionKind() : null;
    io.justsearch.app.api.knowledge.SearchTrace.Qpp qpp =
        wt != null && wt.hasQpp()
            ? new io.justsearch.app.api.knowledge.SearchTrace.Qpp(
                wt.getQpp().getMaxIdf(), wt.getQpp().getAvgIctf(), wt.getQpp().getQueryScope())
            : null;
    io.justsearch.app.api.knowledge.SearchTrace.Degradation degradation = null;
    if (wt != null && wt.hasDegradation()) {
      io.justsearch.ipc.TraceDegradation d = wt.getDegradation();
      degradation =
          new io.justsearch.app.api.knowledge.SearchTrace.Degradation(
              d.getVectorBlocked(),
              d.getVectorBlockedReason().isBlank() ? null : d.getVectorBlockedReason(),
              d.getHybridFallback(),
              d.getHybridFallbackReason().isBlank() ? null : d.getHybridFallbackReason(),
              d.getSpladeExecuted(),
              d.getSpladeSkipReason().isBlank() ? null : d.getSpladeSkipReason(),
              d.getHybridFallbackCause().isBlank() ? null : d.getHybridFallbackCause(),
              d.getSpladeSkipCause().isBlank() ? null : d.getSpladeSkipCause());
    }
    return new io.justsearch.app.api.knowledge.SearchTrace(
        io.justsearch.app.api.knowledge.SearchTrace.SCHEMA_VERSION,
        effectiveMode,
        decisionKind,
        qpp,
        degradation,
        stages);
  }

  /**
   * Tempdoc 549 Slice 5 (U5) — the closed, core-owned vocabulary of head-process pipeline stages.
   * Modeled as an enum consumed by an exhaustive {@code switch} in {@link #buildHeadStages}: adding a
   * stage forces a compile error until it is emitted (the producer half of the "every stage emits a
   * trace node" invariant). {@code wireId} is the stable wire contract the FE renderer reads.
   */
  enum HeadStage {
    QUERY_UNDERSTANDING("query-understanding"),
    EXPANSION("expansion"),
    LAMBDAMART("lambdamart"),
    CROSS_ENCODER("cross-encoder"),
    FRESHNESS("freshness");

    final String wireId;

    HeadStage(String wireId) {
      this.wireId = wireId;
    }
  }

  /**
   * Tempdoc 549 — head-side pipeline stages folded into the query trace so it is stage-complete from a
   * single canonical source. Iterates the closed {@link HeadStage} vocabulary through an exhaustive
   * {@code switch} (no {@code default}), so every declared stage is emitted.
   */
  // Package-private for the U5 exhaustiveness regression test (every HeadStage emits a node).
  static java.util.List<io.justsearch.app.api.knowledge.SearchTrace.TraceStage> buildHeadStages(
      PipelineConfig pipelineConfig,
      long lambdaMartNs,
      long crossEncoderMs,
      boolean lambdaMartApplied,
      String lambdaMartSkipReason,
      boolean crossEncoderApplied,
      CrossEncoderSkipReason crossEncoderSkipReason,
      boolean expansionApplied,
      String expansionSkipReason,
      QueryType queryType,
      String classificationBypassReason) {
    var stages = new java.util.ArrayList<io.justsearch.app.api.knowledge.SearchTrace.TraceStage>();
    for (HeadStage stage : HeadStage.values()) {
      stages.add(
          switch (stage) {
            case QUERY_UNDERSTANDING ->
                stageNode(
                    stage.wireId,
                    // Historical classifier projection, not evidence of an LLM QU dispatch.
                    queryType != null ? "executed" : "disabled",
                    classificationBypassReason,
                    queryType != null ? String.valueOf(queryType) : null,
                    null);
            case EXPANSION ->
                stageNode(
                    stage.wireId,
                    expansionApplied ? "executed" : "skipped",
                    expansionApplied ? null : expansionSkipReason,
                    null,
                    null);
            case LAMBDAMART ->
                stageNode(
                    stage.wireId,
                    lambdaMartApplied ? "executed" : "skipped",
                    lambdaMartApplied ? null : lambdaMartSkipReason,
                    null,
                    lambdaMartApplied && lambdaMartNs > 0
                        ? TimeUnit.NANOSECONDS.toMillis(lambdaMartNs)
                        : null);
            case CROSS_ENCODER ->
                stageNode(
                    stage.wireId,
                    crossEncoderApplied ? "executed" : "skipped",
                    crossEncoderApplied || crossEncoderSkipReason == null
                        ? null
                        : crossEncoderSkipReason.wire(),
                    null,
                    crossEncoderApplied && crossEncoderMs > 0 ? crossEncoderMs : null);
            case FRESHNESS ->
                stageNode(
                    stage.wireId,
                    pipelineConfig.freshnessEnabled() ? "executed" : "disabled",
                    null,
                    null,
                    null);
          });
    }
    return java.util.List.copyOf(stages);
  }

  /**
   * Tempdoc 549 Phase E1: read a stage's structural score from the proto per-hit trace (the always-on
   * id/rank/score slice). Used by LambdaMART inference. Returns 0 when the stage is absent.
   */
  static float protoStageScore(SearchResult sr, String wireId) {
    for (io.justsearch.ipc.HitStage hs : sr.getTraceList()) {
      if (hs.getId().equals(wireId)) {
        return hs.getScore();
      }
    }
    return 0f;
  }

  /**
   * Tempdoc 643 (critical-analysis pass): like {@link #protoStageScore}, but tries each candidate
   * wire id in order and returns the first one that is PRESENT — presence, not value, is
   * the fallback signal, so a real score of {@code 0.0} on an earlier-priority stage is never
   * mistaken for "absent, try the next one". Needed because no single stage id is a reliable
   * "the pre-rerank score" across all pipeline shapes: {@code "fusion"} is null'd out entirely for
   * single-leg presets ({@code HitProvenanceProjector.attachSingleLeg}) and is stale (pre-branch-
   * merge) whenever chunk-branch fusion ran ({@code HitProvenanceProjector.attachBranchFusion} —
   * the true final score there is {@code "branch-fusion"}). Returns 0f if none of the ids are
   * present (should not happen for an eligible-for-reranking result, but degrades safely).
   */
  static float protoStageScoreAny(SearchResult sr, String... wireIds) {
    for (String wireId : wireIds) {
      for (io.justsearch.ipc.HitStage hs : sr.getTraceList()) {
        if (hs.getId().equals(wireId)) {
          return hs.getScore();
        }
      }
    }
    return 0f;
  }

  private static io.justsearch.app.api.knowledge.SearchTrace.TraceStage stageNode(
      String id, String status, String reason, String detail, Long ms) {
    String r = (reason == null || reason.isBlank()) ? null : reason;
    String d = (detail == null || detail.isBlank()) ? null : detail;
    return new io.justsearch.app.api.knowledge.SearchTrace.TraceStage(
        io.justsearch.app.api.knowledge.SearchTrace.StageId.fromWireId(id),
        io.justsearch.app.api.knowledge.SearchTrace.StageStatus.fromWire(status),
        // Tempdoc 597: head-composed stages (query-understanding/expansion/rerankers/freshness)
        // carry no document cardinality — the funnel rungs are the worker's retrieval/fusion stages.
        r, ms, d, null);
  }
}
