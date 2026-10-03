/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.respond;

import io.justsearch.indexerworker.services.SearchOutcome;
import io.justsearch.indexerworker.services.SearchReasonCode;
import io.justsearch.indexerworker.services.plan.LegSet;
import io.justsearch.indexerworker.services.plan.SearchDecision;
import io.justsearch.ipc.SearchTrace;
import io.justsearch.ipc.TraceStage;

/**
 * Tempdoc 549 (full thesis, Phase B) — projects the worker's slice of the unified stage-keyed
 * {@link SearchTrace} from the typed {@code (SearchDecision, SearchOutcome)} values (the same
 * authoritative source {@link SearchIntrospectionProjector} reads). The worker owns the
 * retrieval/fusion/chunk-merge/branch-fusion/correction stages; the head composes its own
 * stages (query-classification, expansion, LambdaMART, cross-encoder, freshness) onto this in
 * {@code KnowledgeHttpApiAdapter} (Phase C).
 *
 * <p>Stage ids are the stable {@code SearchTrace.StageId} wireIds (closed vocabulary, owned by
 * the app-api enum). Status strings match {@code SearchTrace.StageStatus} wire values
 * ({@code executed|skipped|disabled|failed}). Privacy-safe by construction (no query text/filter
 * values; the correction stage's detail is the corrected query, already user-visible).
 *
 * <p>Phase B dual-emits this alongside the legacy {@code SearchIntrospection}; Phase E retires
 * the latter.
 */
final class SearchTraceProjector {

  private SearchTraceProjector() {}

  // Wire ids — must match SearchTrace.StageId#wireId in app-api.
  private static final String SPARSE = "sparse-retrieval";
  private static final String DENSE = "dense-retrieval";
  private static final String SPLADE = "splade-retrieval";
  private static final String FUSION = "fusion";
  private static final String CORRECTION = "correction";
  private static final String CHUNK_MERGE = "chunk-merge";
  private static final String BRANCH_FUSION = "branch-fusion";

  private static final String EXECUTED = "executed";
  private static final String SKIPPED = "skipped";
  private static final String DISABLED = "disabled";

  /** Per-leg execution derived from the decision (which retrieval legs ran + why-not). */
  private record LegExec(
      String sparseStatus,
      String sparseReason,
      String denseStatus,
      String denseReason,
      String spladeStatus,
      String spladeReason,
      boolean fusion,
      String fusionMethod) {}

  /** Test-convenience overload (no funnel cardinality). Production passes the real matchCount. */
  static SearchTrace project(
      SearchDecision decision,
      SearchOutcome outcome,
      io.justsearch.indexerworker.services.input.SearchInputs inputs) {
    return project(decision, outcome, inputs, 0L);
  }

  /**
   * Builds the worker's stage nodes + query-level scalars from the typed decision/outcome/inputs.
   *
   * <p>Tempdoc 597: {@code matchCount} is the true matched-document total (computed by the response
   * builder). It is attached as the funnel cardinality of the lexical retrieval stage ("matched M");
   * the ranked-union size ({@code outcome.result().totalHits()}) is attached to the first executed
   * fusion/merge stage ("ranked R"). Together with the returned page size (the headline's N) these
   * are the matched → ranked → shown funnel the explain panel renders.
   */
  static SearchTrace project(
      SearchDecision decision,
      SearchOutcome outcome,
      io.justsearch.indexerworker.services.input.SearchInputs inputs,
      long matchCount) {
    SearchTrace.Builder b =
        SearchTrace.newBuilder()
            .setVersion(1)
            .setEffectiveMode(effectiveModeFor(decision))
            .setDecisionKind(decisionKindFor(decision));
    if (inputs != null && inputs.qpp() != null) {
      b.setQpp(
          io.justsearch.ipc.TraceQpp.newBuilder()
              .setMaxIdf(inputs.qpp().maxIdf())
              .setAvgIctf(inputs.qpp().avgIctf())
              .setQueryScope(inputs.qpp().queryScope())
              .build());
    }
    b.setDegradation(projectDegradation(decision, outcome));

    // Correction (worker-side zero-hit / per-term correction).
    if (outcome.correctionApplied()) {
      b.addStages(
          stage(CORRECTION, EXECUTED, null, null, nullToEmpty(outcome.correctedQuery())));
    } else {
      b.addStages(stage(CORRECTION, SKIPPED, "not-triggered", null, ""));
    }

    // Retrieval legs + top-level fusion. The FUSION node carries the orchestrator-measured
    // retrieval-phase elapsed (outcome.retrievalMs) — fusion is the retrieval-phase completion
    // point, so its ms is the phase timing the legacy IntrospectionTiming.retrievalMs reported.
    // Carried unconditionally (even when fusion is skipped for a single leg, the retrieval phase
    // still ran and took time) so the trace is a lossless superset of the legacy timing record
    // (tempdoc 549 Phase D0: the prerequisite for PipelineExecution retirement in Phase E3).
    LegExec legs = legsOf(decision);
    Long retrievalMs = outcome.retrievalMs() > 0 ? outcome.retrievalMs() : null;

    // Tempdoc 597 — the funnel rungs. "matched" = the lexical match total (matchCount), shown on the
    // sparse stage when it ran. "ranked" = the fused-candidate-union (outcome.result().totalHits()),
    // shown ONCE on the first executed narrowing stage (fusion → chunk-merge → branch-fusion), so the
    // explain panel reads "Sparse matched M · … · ranked R" without repeating R on every stage.
    Long matched = EXECUTED.equals(legs.sparseStatus()) ? matchCount : null;
    long ranked = outcome.result() != null ? outcome.result().totalHits() : 0L;
    boolean rankedShown = false;

    b.addStages(retrieverStage(SPARSE, legs.sparseStatus(), legs.sparseReason(), matched));
    b.addStages(retrieverStage(DENSE, legs.denseStatus(), legs.denseReason()));
    b.addStages(retrieverStage(SPLADE, legs.spladeStatus(), legs.spladeReason()));
    if (legs.fusion()) {
      b.addStages(stage(FUSION, EXECUTED, null, retrievalMs, nullToEmpty(legs.fusionMethod()), ranked));
      rankedShown = true;
    } else {
      b.addStages(stage(FUSION, SKIPPED, "single-leg-or-no-retrieval", retrievalMs, ""));
    }

    // Chunk merge + branch fusion (from the decision + runtime outcome).
    Long chunkCard = (!rankedShown && outcome.chunkMergeApplied()) ? ranked : null;
    if (chunkCard != null) {
      rankedShown = true;
    }
    b.addStages(chunkMergeStage(decision, outcome, chunkCard));
    Long branchCard = (!rankedShown && outcome.branchFusionContributed()) ? ranked : null;
    b.addStages(branchFusionStage(outcome, branchCard));

    return b.build();
  }

  private static LegExec legsOf(SearchDecision decision) {
    return switch (decision) {
      case SearchDecision.EmptyQueryDecision e ->
          new LegExec(DISABLED, "empty-query", DISABLED, "empty-query", DISABLED, "empty-query",
              false, null);
      case SearchDecision.BlockedDecision blocked -> {
        String reason = blocked.encodingFailure().reason().name();
        // Vector-intent query whose encoding failed and was blocked (no fallback).
        yield new LegExec(SKIPPED, "vector-blocked", SKIPPED, reason, SKIPPED, "vector-blocked",
            false, null);
      }
      case SearchDecision.SparseShortcut s ->
          new LegExec(EXECUTED, null, SKIPPED, "sparse-shortcut", SKIPPED, "sparse-shortcut",
              false, null);
      case SearchDecision.MultiLegDecision multi -> legsOfMultiLeg(multi);
    };
  }

  private static LegExec legsOfMultiLeg(SearchDecision.MultiLegDecision multi) {
    String hybridFallback =
        multi.hybridFallback().map(f -> f.reason().name()).orElse("not-selected");
    String denseUnavailable =
        multi.denseSkipReason().map(SearchReasonCode::name).orElse(hybridFallback);
    String spladeSkip = multi.spladeSkip().map(f -> f.reason().name()).orElse("not-selected");
    return switch (multi.legs()) {
      case LegSet.Bm25Only b ->
          new LegExec(EXECUTED, null, SKIPPED, denseUnavailable, SKIPPED, spladeSkip, false, null);
      case LegSet.DenseOnly d ->
          new LegExec(SKIPPED, "dense-only", EXECUTED, null, SKIPPED, spladeSkip, false, null);
      case LegSet.SpladeOnly p ->
          new LegExec(SKIPPED, "splade-only", SKIPPED, denseUnavailable, EXECUTED, null, false, null);
      case LegSet.Bm25Dense bd ->
          new LegExec(EXECUTED, null, EXECUTED, null, SKIPPED, spladeSkip, true, "hybrid");
      case LegSet.Bm25Splade bs ->
          new LegExec(EXECUTED, null, SKIPPED, denseUnavailable, EXECUTED, null, true, "rrf");
      case LegSet.DenseSplade ds ->
          new LegExec(SKIPPED, "dense-splade", EXECUTED, null, EXECUTED, null, true, "rrf");
      case LegSet.ThreeWay tw ->
          new LegExec(EXECUTED, null, EXECUTED, null, EXECUTED, null, true, "cc");
    };
  }

  private static TraceStage chunkMergeStage(
      SearchDecision decision, SearchOutcome outcome, Long cardinality) {
    if (outcome.chunkMergeApplied()) {
      return stage(CHUNK_MERGE, EXECUTED, null, outcome.chunkMergeMs(), "", cardinality);
    }
    // Decision-derived skip reasons (parity with the retired flat chunk_merge_reason, Phase E5):
    // blocked / empty-query decisions skip chunk-merge for a decision reason the outcome (an empty
    // outcome for those paths) doesn't carry. Other paths use the outcome's chunk-merge reason.
    String reason;
    if (decision instanceof SearchDecision.BlockedDecision) {
      reason = SearchReasonCode.SKIPPED_VECTOR_BLOCKED.name();
    } else if (decision instanceof SearchDecision.EmptyQueryDecision) {
      reason = SearchReasonCode.SKIPPED_EMPTY_QUERY.name();
    } else {
      SearchReasonCode rc = outcome.chunkMergeReason();
      reason = rc != null ? rc.name() : null;
    }
    return stage(CHUNK_MERGE, SKIPPED, reason, null, "");
  }

  private static TraceStage branchFusionStage(SearchOutcome outcome, Long cardinality) {
    if (outcome.branchFusionContributed()) {
      long ms = outcome.branchFusionNs() / 1_000_000L;
      return stage(
          BRANCH_FUSION, EXECUTED, null, ms, nullToEmpty(outcome.branchFusionStrategy()), cardinality);
    }
    return stage(BRANCH_FUSION, SKIPPED, "no-chunk-branch-contribution", null, "");
  }

  private static TraceStage retrieverStage(String id, String status, String reason) {
    return retrieverStage(id, status, reason, null);
  }

  private static TraceStage retrieverStage(String id, String status, String reason, Long cardinality) {
    return stage(id, status, reason, null, "", cardinality);
  }

  private static TraceStage stage(String id, String status, String reason, Long ms, String detail) {
    return stage(id, status, reason, ms, detail, null);
  }

  // Tempdoc 597: the 6-arg form carries the per-stage funnel cardinality (matched docs on the
  // lexical retrieval stage, the ranked-union size on a fusion/merge stage). Null ⇒ unknown/omitted.
  private static TraceStage stage(
      String id, String status, String reason, Long ms, String detail, Long cardinality) {
    TraceStage.Builder b = TraceStage.newBuilder().setId(id).setStatus(status);
    if (reason != null && !reason.isBlank()) {
      b.setReason(reason);
    }
    if (ms != null) {
      b.setMs(ms);
    }
    if (detail != null && !detail.isBlank()) {
      b.setDetail(detail);
    }
    if (cardinality != null) {
      b.setCardinality(cardinality);
    }
    return b.build();
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String effectiveModeFor(SearchDecision decision) {
    return switch (decision) {
      case SearchDecision.EmptyQueryDecision e -> e.effectiveModeLabel();
      case SearchDecision.BlockedDecision b -> b.effectiveModeLabel();
      case SearchDecision.SparseShortcut s -> "TEXT";
      case SearchDecision.MultiLegDecision m -> m.legs().effectiveModeLabel();
    };
  }

  private static io.justsearch.ipc.TraceDegradation projectDegradation(
      SearchDecision decision, SearchOutcome outcome) {
    io.justsearch.ipc.TraceDegradation.Builder b =
        io.justsearch.ipc.TraceDegradation.newBuilder().setSpladeExecuted(outcome.spladeExecuted());
    switch (decision) {
      case SearchDecision.BlockedDecision blocked -> {
        b.setVectorBlocked(true);
        b.setVectorBlockedReason(blocked.encodingFailure().reason().name());
      }
      case SearchDecision.MultiLegDecision multi -> {
        multi.denseSkipReason()
            .ifPresent(
                reason -> {
                  b.setVectorBlocked(true);
                  b.setVectorBlockedReason(reason.name());
                });
        multi.hybridFallback()
            .ifPresent(
                f -> {
                  b.setHybridFallback(true);
                  b.setHybridFallbackReason(f.reason().name());
                  if (f.reason() == SearchReasonCode.REBUILD_IN_PROGRESS) {
                    b.setVectorBlocked(true);
                    b.setVectorBlockedReason(f.reason().name());
                  }
                });
        multi.spladeSkip().ifPresent(f -> b.setSpladeSkipReason(f.reason().name()));
      }
      case SearchDecision.SparseShortcut s -> {
        // No degradation; proto3 defaults stand.
      }
      case SearchDecision.EmptyQueryDecision e -> {
        // Request-shape decision, not degradation.
      }
    }
    return b.build();
  }

  private static String decisionKindFor(SearchDecision decision) {
    return switch (decision) {
      case SearchDecision.EmptyQueryDecision e -> "empty_query";
      case SearchDecision.BlockedDecision b -> "blocked";
      case SearchDecision.SparseShortcut s -> "sparse_shortcut";
      case SearchDecision.MultiLegDecision m -> "multi_leg";
    };
  }
}
