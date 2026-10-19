/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 549 — the single canonical, stage-keyed search trace.
 *
 * <p>The query-level trace is an ordered list of {@link TraceStage} nodes over one closed
 * {@link StageId} vocabulary spanning both processes (worker retrieval/fusion/chunk-merge/
 * correction + head query-classification/expansion/rerank/freshness). The per-hit "why this
 * result" is the per-document slice of the same vocabulary ({@link HitStage} on each
 * {@code KnowledgeSearchResponse.Hit}).
 *
 * <p>This is the target structure that replaces the fragmented set (the
 * {@code SearchIntrospection} field-keyed companions + {@code headStages}, {@code PipelineExecution},
 * the stringly-typed {@code debugScores} map, and the leg-keyed {@code HitProvenance}). Phase A
 * introduces it additively; population (Phase B/C), consumer migration (Phase D), and retirement
 * of the legacy representations (Phase E) follow.
 *
 * <p>Stability: evolving (tempdoc 549 campaign). {@link #SCHEMA_VERSION} bumps on
 * backwards-incompatible shape changes.
 */
@RecordBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchTrace(
    int version,
    // Query-level scalars carried on the trace so the FE explain + eval consumers read ONLY the
    // trace (principle 7) and `SearchIntrospection` can retire (principle 5 / Phase E). These are
    // genuinely whole-query (not per-stage): the effective retrieval mode, the decision-kind
    // classification (empty_query/blocked/sparse_shortcut/multi_leg, used for eval stratification),
    // and the query-performance-prediction signals.
    String effectiveMode,
    String decisionKind,
    Qpp qpp,
    Degradation degradation,
    List<TraceStage> stages) {

  /** Schema version of the trace record. Bump on backwards-incompatible shape changes. */
  public static final int SCHEMA_VERSION = 1;

  public SearchTrace {
    stages = stages == null ? List.of() : List.copyOf(stages);
  }

  /** Query-performance-prediction signals (mirrors the legacy {@code SearchIntrospection.Qpp}). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Qpp(float maxIdf, float avgIctf, float queryScope) {}

  /**
   * Degradation signals as trace-level booleans (so FE explain + eval read them cleanly without
   * a stage-reason taxonomy; the per-stage view still carries the same info as dense/splade
   * retrieval-stage status+reason). Mirrors the legacy {@code SearchIntrospection.Degradation}.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Degradation(
      boolean vectorBlocked,
      String vectorBlockedReason,
      boolean hybridFallback,
      String hybridFallbackReason,
      boolean spladeExecuted,
      String spladeSkipReason,
      // Spec D §6.1: why a leg missed its semantic sub-budget ("contended", "loading" or
      // "stuck"); present only with the reason SEMANTIC_BUDGET_EXCEEDED.
      String hybridFallbackCause,
      String spladeSkipCause) {

    /** A degradation without semantic budget causes. */
    public Degradation(
        boolean vectorBlocked,
        String vectorBlockedReason,
        boolean hybridFallback,
        String hybridFallbackReason,
        boolean spladeExecuted,
        String spladeSkipReason) {
      this(vectorBlocked, vectorBlockedReason, hybridFallback, hybridFallbackReason,
          spladeExecuted, spladeSkipReason, null, null);
    }
  }

  /**
   * The closed, core-owned vocabulary of pipeline stages (tempdoc 549 principle 2; closed per
   * the 521 finding that plugins contribute conversation shapes / host APIs, not Lucene retrieval
   * stages). The {@code wireId} is the stable serialized identifier consumers read.
   */
  public enum StageId {
    QUERY_UNDERSTANDING("query-understanding"),
    EXPANSION("expansion"),
    CORRECTION("correction"),
    SPARSE_RETRIEVAL("sparse-retrieval"),
    DENSE_RETRIEVAL("dense-retrieval"),
    SPLADE_RETRIEVAL("splade-retrieval"),
    FUSION("fusion"),
    CHUNK_MERGE("chunk-merge"),
    BRANCH_FUSION("branch-fusion"),
    LAMBDAMART("lambdamart"),
    CROSS_ENCODER("cross-encoder"),
    FRESHNESS("freshness");

    private final String wireId;

    StageId(String wireId) {
      this.wireId = wireId;
    }

    @JsonValue
    public String wireId() {
      return wireId;
    }

    @JsonCreator
    public static StageId fromWireId(String wireId) {
      for (StageId s : values()) {
        if (s.wireId.equals(wireId)) {
          return s;
        }
      }
      throw new IllegalArgumentException("Unknown StageId wireId: " + wireId);
    }
  }

  /** Whether a stage ran for this query/hit. */
  public enum StageStatus {
    EXECUTED,
    SKIPPED,
    DISABLED,
    FAILED;

    @JsonValue
    public String wireValue() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    @JsonCreator
    public static StageStatus fromWire(String v) {
      return valueOf(v.toUpperCase(java.util.Locale.ROOT));
    }
  }

  /**
   * A query-level stage node: did the stage run, why-not if skipped, how long, and a short
   * stage-specific structured detail (e.g. the fusion method, the chosen leg set, the corrected
   * query).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TraceStage(
      StageId id,
      StageStatus status,
      String reason,
      Long ms,
      String detail,
      // Tempdoc 597: the document cardinality at this stage (the funnel rung) — matched docs on the
      // lexical retrieval stage, the ranked-union size on a fusion/merge stage. Null when unknown.
      Long cardinality) {}

  /**
   * A per-hit slice of one stage: this document's placement in that stage (1-based {@code rank},
   * raw {@code score}) plus an optional numeric {@code detail} tier carrying the long-tail signals
   * (the former {@code debugScores} keys — fusion weights/modifiers/norms, parent token counts).
   * The detail tier is elided unless requested. {@code rank}/{@code score} are nullable for stages
   * that produce neither (e.g. a pure gate stage).
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HitStage(StageId id, Integer rank, Float score, Map<String, Float> detail) {
    public HitStage {
      detail = (detail == null || detail.isEmpty()) ? null : Map.copyOf(detail);
    }
  }

  /** The four fusion-leg scores derived from a hit's stages (tempdoc 580 §17). */
  public record LegScores(float sparse, float dense, float splade, float fused) {}

  /**
   * Tempdoc 580 §17 — the ONE extractor of per-leg fusion scores from a hit's trace stages, shared by
   * the HTTP {@code FeatureSnapshots.capture} path and the agent {@code SearchTool.buildSearchEvidence}
   * feedback path. The FUSION score falls back to {@code fusedFallback} (the hit's overall score) when
   * that stage is absent; legs with no executed stage stay 0.
   */
  public static LegScores legScores(List<HitStage> trace, float fusedFallback) {
    float sparse = 0f;
    float dense = 0f;
    float splade = 0f;
    float fused = fusedFallback;
    if (trace != null) {
      for (HitStage st : trace) {
        Float s = st.score();
        if (s == null) {
          continue;
        }
        switch (st.id()) {
          case SPARSE_RETRIEVAL -> sparse = s;
          case DENSE_RETRIEVAL -> dense = s;
          case SPLADE_RETRIEVAL -> splade = s;
          case FUSION -> fused = s;
          default -> {
            // other stages (rerank, freshness, …) are not part of the V1 fusion-feature set
          }
        }
      }
    }
    return new LegScores(sparse, dense, splade, fused);
  }
}
