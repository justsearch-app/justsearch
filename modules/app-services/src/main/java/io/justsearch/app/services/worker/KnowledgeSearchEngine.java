/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.context.EngineContext;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.app.api.knowledge.CrossEncoderSkipReason;
import io.justsearch.app.api.knowledge.FolderBrowseRequest;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesRequest;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.app.api.knowledge.KnowledgeIngestResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.QueryType;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.EntityFacetVariants;
import io.justsearch.ipc.FacetCounts;
import io.justsearch.ipc.FacetFieldSpec;
import io.justsearch.ipc.FolderEntry;
import io.justsearch.ipc.FolderFileEntry;
import io.justsearch.ipc.ListFolderFilesResponse;
import io.justsearch.ipc.ListFoldersResponse;
import io.justsearch.ipc.FacetSpec;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.SearchSort;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.TimeRangeMs;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.ipc.RerankResponse;
import io.justsearch.reranker.RerankerConfig;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.SamplingParams;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that maps the Knowledge HTTP API contract (app-api DTOs) to/from gRPC proto DTOs.
 *
 * <p>UI controllers should not import proto DTOs directly; this class is the intended boundary.
 */
final class KnowledgeSearchEngine {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeHttpApiAdapter.class);
  private static final Tracer tracer =
      GlobalOpenTelemetry.getTracer("io.justsearch.search");


  /** Target length for query-focused snippets passed to the reranker (~512 tokens). */
  private static final int RERANK_SNIPPET_LENGTH = 1500;

  /** Max tokens the LLM may generate for query expansion. */
  private static final int EXPANSION_MAX_TOKENS = 64;

  /** Hard budget (ms) for LLM expansion before falling back to base search results. */
  private static final long EXPANSION_BUDGET_MS = 1500L;

  /** Compiled pattern for validating expansion tokens (alphabetic-only, no digits or punctuation). */
  private static final Pattern ALPHA_ONLY = Pattern.compile("[a-zA-Z]+");

  /** System prompt for morphological query expansion. */
  private static final String EXPANSION_SYSTEM_PROMPT =
      "Given a search query, list morphological variants of each word: plural, past tense, "
          + "past participle, gerund, and nominalization forms. Output only the variant words, "
          + "space-separated. Include only inflectional variants sharing the same root word — "
          + "no semantic synonyms, no explanations.";

  // 250 Phase 5a: Head-side pipeline fallback counters.
  // Lazy-init via GlobalOpenTelemetry (not set at class-load time).
  private static volatile LongCounter rerankerSkippedCounter;
  private static volatile LongCounter lambdamartSkippedCounter;

  private static LongCounter rerankerSkipped() {
    LongCounter c = rerankerSkippedCounter;
    if (c == null) {
      Meter m = GlobalOpenTelemetry.get().getMeter("io.justsearch.search");
      c = m.counterBuilder("search.reranker_skipped.total").build();
      rerankerSkippedCounter = c;
    }
    return c;
  }

  private static LongCounter lambdamartSkipped() {
    LongCounter c = lambdamartSkippedCounter;
    if (c == null) {
      Meter m = GlobalOpenTelemetry.get().getMeter("io.justsearch.search");
      c = m.counterBuilder("search.lambdamart_skipped.total").build();
      lambdamartSkippedCounter = c;
    }
    return c;
  }


  /**
   * Returns true when query expansion should be started for this request.
   *
   * <p>Expansion fires when the pipeline's {@link PipelineConfig#expansionEnabled()} flag is set,
   * with SIMPLE syntax, a non-blank non-paginated query, and an available AI service. The flag is
   * independent of retrieval mode — presets set it based on whether semantic recall is already
   * provided (TEXT/SPLADE=true, HYBRID/VECTOR=false), but custom configs can override.
   *
   * <p>Package-private for contract testing.
   */
  static boolean isExpansionEligible(
      PipelineConfig config,
      SearchQuerySyntax syntax,
      String query,
      String cursor,
      boolean aiAvailable,
      QueryType queryType) {
    // 306: skip expansion for navigational/exact queries — adding morphological variants
    // to a filename or quoted phrase dilutes precision with no recall benefit.
    if (queryType == QueryType.NAVIGATIONAL || queryType == QueryType.EXACT_MATCH) return false;
    // 256-I2: expansion gated by explicit PipelineConfig flag instead of !denseEnabled()
    return config.expansionEnabled()
        && syntax == SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE
        && !query.isBlank()
        && (cursor == null || cursor.isBlank())
        && aiAvailable;
  }

  /**
   * Returns true when cross-encoder reranking should be applied to the result set.
   *
   * <p>Cross-encoder eligibility is driven by the {@link PipelineConfig#crossEncoderEnabled()} flag,
   * which is set uniformly for all presets in {@link SearchPipelinePresets#expandPreset} based on {@link
   * RerankerConfig#enabled()} (256-F1). Custom PipelineConfigs can override independently.
   *
   * <p>258-B1: When the index average document length exceeds {@link
   * RerankerConfig#maxAvgDocLengthChars()}, cross-encoder is auto-disabled. MiniLM-L6-v2 truncates
   * at 512 tokens; longer documents lose most content, causing catastrophic ranking degradation.
   *
   * <p>Package-private for contract testing.
   */
  static boolean isRerankerEligible(
      PipelineConfig pipeline, RerankerConfig config, int resultCount,
      long avgContentLengthChars, QueryType queryType) {
    // 306: skip cross-encoder for navigational queries — the user is searching for a known
    // file by name/path; BM25 term matching is sufficient and CE adds 60-100ms for no gain.
    if (queryType == QueryType.NAVIGATIONAL) return false;
    if (!config.enabled() || resultCount < config.minHitsThreshold()) return false;
    if (!pipeline.crossEncoderEnabled()) return false;
    // 258-B1: auto-disable cross-encoder when documents are too long for the model.
    // TOMBSTONE (tempdoc 774 §J.2/§K): the default of maxAvgDocLengthChars was flipped 16000 → 0
    // (gate disabled) — its input (avgContentLengthChars) is a Head-side session-average cache
    // populated only by GET /api/knowledge/status, so it diverged eval (gate-off) from production.
    // The gate mechanism + cache plumbing are retained (operator override); their teardown belongs
    // to the later default-flip sweep, not tempdoc 774 Stage 2.
    long maxLen = config.maxAvgDocLengthChars();
    if (maxLen > 0 && avgContentLengthChars > maxLen) return false;
    return true;
  }

  /**
   * Builds the Head cross-encoder's per-candidate input text: the hit's title followed by a
   * query-focused snippet cut from its {@code content_preview} field, centered on the first match
   * span ({@link SearchResultMapper#extractQueryFocusedSnippet}). A hit with no {@code
   * content_preview} (e.g. a chunk-only hit before tempdoc 774 Stage 2's evidence-preview flag)
   * yields title-only text — the judge-blindness defect documented in §F.1-5. Package-private for
   * unit coverage of the CE input assembly (tempdoc 774 §J.2 test gap).
   */
  static String buildCrossEncoderDocText(SearchResult sr) {
    String title = sr.getFieldsMap().getOrDefault("title", "");
    String preview = sr.getFieldsMap().getOrDefault("content_preview", "");
    // Query-focused snippet extraction centers the snippet on the first query match instead of the
    // document start, giving the reranker more relevant context.
    var spans = SearchResultMapper.extractMatchSpans(sr);
    String snippet =
        SearchResultMapper.extractQueryFocusedSnippet(preview, spans, RERANK_SNIPPET_LENGTH);
    return title + " " + snippet;
  }

  @SuppressWarnings("unused") // Called from KnowledgeHttpApiAdapterHarmfulCombinationsTest
  static boolean isLambdaMartEligible(
      PipelineConfig pipeline, RerankerService rerankerService, int resultCount) {
    if (!pipeline.lambdamartEnabled()) return false;
    if (rerankerService == null || !rerankerService.isLoaded()) return false;
    return resultCount > 0;
  }

  /**
   * Tempdoc 643: judge-stage refinement floor. Blends each candidate's pre-rerank (fusion /
   * LambdaMART) score with its cross-encoder score, both min-max normalized within the CE window,
   * instead of letting the cross-encoder replace that order outright (today's behavior). Bounds
   * the CE's influence so a low-confidence/wrong CE call cannot regress a hit further than the
   * blend weight allows — the CE-side instance of D-004's per-query arbitration shape
   * ({@code HybridSearchOps.computeArbitrationAlpha}), applied to the judge stage instead of fusion.
   *
   * <p>{@code alpha=0.0} reduces to today's CE-only order (min-max normalization is monotonic, so
   * sorting by normalized CE score is identical to sorting by raw CE score). {@code alpha=1.0}
   * ignores the CE entirely (pure pre-rerank order).
   *
   * <p>Pure + static for unit testing, mirroring the {@code computeArbitrationAlpha} testability
   * pattern. Both arrays must have equal length, indexed by the pre-rerank position (the same
   * indexing {@code RerankResponse.getScores(i)} already uses).
   *
   * @param preRerankScores preRerankScores[i] = pre-rerank (fusion-stage) score at position i.
   * @param crossEncoderScores crossEncoderScores[i] = cross-encoder score at position i.
   * @param alpha weight on the pre-rerank score in [0,1].
   * @return positions 0..n-1, reordered descending by the blended score.
   */
  static List<Integer> blendPreRerankAndCrossEncoder(
      float[] preRerankScores, float[] crossEncoderScores, double alpha) {
    int n = preRerankScores.length;
    Integer[] order = new Integer[n];
    for (int i = 0; i < n; i++) order[i] = i;
    if (n == 0) return List.of();

    double[] preNorm = minMaxNormalize(preRerankScores);
    double[] ceNorm = minMaxNormalize(crossEncoderScores);
    double[] blended = new double[n];
    for (int i = 0; i < n; i++) {
      blended[i] = alpha * preNorm[i] + (1 - alpha) * ceNorm[i];
    }
    // Ties (incl. the flat-range case, where every candidate normalizes to the same value) are
    // broken by Arrays.sort's stability, preserving the pre-rerank position order — the "no
    // information to act on" case.
    Arrays.sort(order, (a, b) -> Double.compare(blended[b], blended[a]));
    return Arrays.asList(order);
  }

  /**
   * Min-max normalizes {@code scores} to [0,1]; a flat (zero-range) input normalizes every entry
   * to {@code 1.0} (matching {@link #blendPreRerankAndCrossEncoder}'s "no information to act on"
   * convention — ties fall through to whatever stable order the caller started with).
   */
  private static double[] minMaxNormalize(float[] scores) {
    int n = scores.length;
    double[] norm = new double[n];
    if (n == 0) return norm;
    double min = scores[0];
    double max = scores[0];
    for (int i = 1; i < n; i++) {
      min = Math.min(min, scores[i]);
      max = Math.max(max, scores[i]);
    }
    double range = max - min;
    for (int i = 0; i < n; i++) {
      norm[i] = range > 0 ? (scores[i] - min) / range : 1.0;
    }
    return norm;
  }

  /**
   * Tempdoc 821 §L.3: applies a rerank order to the candidate list without ever changing how many
   * candidates come out — count in equals count out, always.
   *
   * <p>The judge-blend branch defends against a short cross-encoder response by construction: it
   * sizes its blend window at {@code window} (not {@code getScoresCount()}) and min-fills the
   * missing tail, so its returned order is always a full permutation of {@code 0..window-1}. The
   * default (judge-blend-off) branch has no such guarantee — it applies the Worker's
   * {@code sorted_indices} verbatim, and a list shorter than the window silently dropped the
   * uncovered candidates from the result set entirely. The LambdaMART branch had the same gap
   * against its own {@link io.justsearch.app.api.gpl.RerankerService} contract. This helper is the
   * shared seam that makes all three reorder sites equally defensive.
   *
   * <p>The given order is authoritative for the window positions it covers; any in-window position
   * it omits is appended after that prefix in original (pre-rerank) order, and candidates beyond
   * the window — which were never sent to the cross-encoder — follow in original order. An
   * out-of-window or duplicate index is discarded rather than emitted twice, since the position it
   * names is already placed by one of the two passes. A well-formed order (a permutation already
   * covering the window, which includes every judge-blend output) reproduces the pre-821 result
   * element for element.
   *
   * <p>A candidate recovered by the fill pass carries no CROSS_ENCODER HitStage even though the
   * response reports the cross-encoder as applied — correct, since the reranker never judged it,
   * but a trace-coverage consumer should not read stage presence as "every hit was scored".
   *
   * @param results the pre-rerank candidates, in pre-rerank order.
   * @param order window positions in their reranked order; may be short, empty, or malformed.
   * @param window the number of leading candidates that were offered to the cross-encoder.
   * @return the reordered candidates — always exactly {@code results.size()} entries.
   */
  static List<SearchResult> applyRerankOrder(
      List<SearchResult> results, List<Integer> order, int window) {
    int w = Math.max(0, Math.min(window, results.size()));
    List<SearchResult> reordered = new ArrayList<>(results.size());
    boolean[] placed = new boolean[w];
    for (int idx : order) {
      if (idx < 0 || idx >= w || placed[idx]) continue;
      placed[idx] = true;
      reordered.add(results.get(idx));
    }
    for (int i = 0; i < w; i++) {
      if (!placed[i]) reordered.add(results.get(i));
    }
    for (int i = w; i < results.size(); i++) {
      reordered.add(results.get(i));
    }
    return reordered;
  }

  /** Top-K doc-ids (within the CE window) at/below the given per-leg HitStage rank, for judge-arbitration
   * leg agreement (tempdoc 643 E1). Only counts a candidate whose stage rank is PRESENT (proto3 {@code
   * optional}) and {@code <= topK} — mirrors {@code HybridSearchOps.topKDocIds}'s rank-based selection. */
  private static Set<String> judgeArbitrationTopKDocIds(
      List<SearchResult> window, String stageWireId, int topK) {
    Set<String> ids = new HashSet<>();
    for (SearchResult sr : window) {
      for (io.justsearch.ipc.HitStage hs : sr.getTraceList()) {
        if (hs.getId().equals(stageWireId) && hs.hasRank() && hs.getRank() <= topK) {
          ids.add(sr.getId());
        }
      }
    }
    return ids;
  }

  /** Min CE margin (normalized top1-top2 gap) at/above which the CE's top pick counts as confident. */
  private static final double JUDGE_ARBITRATION_MARGIN_CONFIDENT_MIN = 0.2;
  /** Cross-leg top-K doc-id Jaccard at/above which the legs "agree" (fusion is decisive). */
  private static final double JUDGE_ARBITRATION_OVERLAP_MIN = 0.5;
  /** Top-K hits per leg used to measure cross-leg rank overlap (mirrors {@code ARBITRATION_TOP_K}). */
  private static final int JUDGE_ARBITRATION_TOP_K = 10;

  /**
   * Tempdoc 643 (E1/E2, critical-analysis-pass correction): per-query judge-arbitration gate — the
   * CE-side instance of D-004's fusion-side per-query arbitration
   * ({@code HybridSearchOps.computeArbitrationAlpha}), same 3-condition-gate shape, applied to the
   * judge stage. Computes the {@code alpha} to pass to {@link #blendPreRerankAndCrossEncoder} from two
   * runtime signals over the CE window, instead of reading a static operator-configured value:
   *
   * <ul>
   *   <li><b>CE margin</b> — normalized (not raw-logit) top1-top2 gap of the CE's own scores in this
   *       window: how decisively it prefers its top pick. Normalized because raw CE logits are not
   *       comparable in absolute terms across queries/corpora (observed range e.g. -0.86 to +0.30).
   *   <li><b>Leg agreement</b> — top-K doc-id Jaccard between the {@code sparse-retrieval} and
   *       {@code dense-retrieval} HitStages within the window: do the legs concur on what's relevant?
   * </ul>
   *
   * <p><b>Structurally bounded to never do MORE than today's baseline</b> (unconditional CE trust,
   * {@code alpha=0}): when the CE is confident AND the legs disagree (it is arbitrating a real
   * disagreement, not noise) AND chunk-branch status is known, {@code alpha} stays at
   * {@code baseAlpha} — today's behavior, unchanged. The only NEW behavior is the reverse case: when
   * the CE margin is thin AND the legs strongly agree (fusion is already decisive), {@code alpha}
   * moves toward {@code fusionProtectAlpha} — protect via fusion. This mirrors D-004's
   * {@code divergeAlpha} but in the opposite direction (D-004 raises alpha toward dense on leg
   * disagreement; this raises alpha toward fusion on judge uncertainty).
   *
   * <p>When chunk-branch fusion ran on this window (a {@code "chunk-merge"} HitStage is present on
   * any candidate), the top-level leg stages reflect only the whole-doc branch and are not a reliable
   * agreement signal (tempdoc 643 critical-analysis-pass finding — the same staleness class as the
   * fusion-score bug fixed in {@code protoStageScoreAny}). Treated as "unknown" and degrades to
   * {@code baseAlpha} — the same "can't act on an untrustworthy signal, don't intervene" philosophy as
   * D-004's {@code topKDocIdOverlap} empty-leg case (returns 1.0 / "agree, don't act").
   *
   * <p>Pure + static for unit testing, mirroring {@code computeArbitrationAlpha}'s testability pattern.
   *
   * @param window the CE window's candidates, same indexing as {@code crossEncoderScores}.
   * @param crossEncoderScores window's raw CE scores, same indexing as
   *     {@link #blendPreRerankAndCrossEncoder}.
   * @param baseAlpha the operator-configured alpha (today's behavior when no condition fires).
   * @param fusionProtectAlpha the alpha to use when fusion is decisive and the CE is not confident.
   * @return the alpha to pass to {@link #blendPreRerankAndCrossEncoder}.
   */
  static double computeJudgeArbitrationAlpha(
      List<SearchResult> window,
      float[] crossEncoderScores,
      double baseAlpha,
      double fusionProtectAlpha) {
    int n = crossEncoderScores.length;
    if (n < 2) {
      return baseAlpha; // no margin signal with <2 candidates
    }

    double[] ceNorm = minMaxNormalize(crossEncoderScores);
    // Top-2 scan: sentinels must start below any possible normalized score ([0,1]), NOT at
    // ceNorm[0] -- seeding top2 with ceNorm[0] silently zeroes the margin whenever the window's
    // true top score happens to sit at position 0 (position 0 is the pre-rerank rank, not sorted
    // by CE score, so this is not a rare case).
    double top1 = Double.NEGATIVE_INFINITY;
    double top2 = Double.NEGATIVE_INFINITY;
    for (double v : ceNorm) {
      if (v > top1) {
        top2 = top1;
        top1 = v;
      } else if (v > top2) {
        top2 = v;
      }
    }
    double ceMargin = top1 - top2;

    if (chunkBranchActive(window)) {
      return baseAlpha; // chunk-branch active → leg signal untrustworthy → don't intervene
    }

    double jaccard = legAgreementJaccard(window);
    // No comparable signal (either leg empty) → treat as "agree" → don't intervene (mirrors
    // D-004's topKDocIdOverlap empty-leg case, which returns 1.0 for the same reason).
    boolean legsAgree = jaccard < 0 || jaccard >= JUDGE_ARBITRATION_OVERLAP_MIN;

    boolean ceConfident = ceMargin >= JUDGE_ARBITRATION_MARGIN_CONFIDENT_MIN;
    if (!ceConfident && legsAgree) {
      return Math.max(baseAlpha, fusionProtectAlpha); // CE unsure + fusion decisive → protect via fusion
    }
    return baseAlpha; // CE confident and/or legs disagree → today's behavior, unchanged
  }

  /**
   * True if a {@code "chunk-merge"} HitStage is present on any candidate in {@code window} — the
   * top-level {@code sparse-retrieval}/{@code dense-retrieval} leg stages then reflect only the
   * whole-doc branch and are not a reliable leg-agreement signal (tempdoc 643
   * critical-analysis-pass finding).
   */
  private static boolean chunkBranchActive(List<SearchResult> window) {
    for (SearchResult sr : window) {
      for (io.justsearch.ipc.HitStage hs : sr.getTraceList()) {
        if (hs.getId().equals("chunk-merge")) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Top-K doc-id Jaccard between the {@code sparse-retrieval} and {@code dense-retrieval} legs
   * within {@code window}, or {@code -1.0} if either leg has no rank-bearing candidates (no
   * comparable signal — callers decide what "no signal" means for their own safe default).
   */
  private static double legAgreementJaccard(List<SearchResult> window) {
    Set<String> sparseTopK =
        judgeArbitrationTopKDocIds(window, "sparse-retrieval", JUDGE_ARBITRATION_TOP_K);
    Set<String> denseTopK =
        judgeArbitrationTopKDocIds(window, "dense-retrieval", JUDGE_ARBITRATION_TOP_K);
    if (sparseTopK.isEmpty() || denseTopK.isEmpty()) {
      return -1.0;
    }
    int inter = 0;
    for (String id : sparseTopK) {
      if (denseTopK.contains(id)) {
        inter++;
      }
    }
    int union = sparseTopK.size() + denseTopK.size() - inter;
    return union == 0 ? 1.0 : (double) inter / union;
  }

  /**
   * Tempdoc 643 (perf-skip): true when the leg-agreement signal ALONE (no CE score exists yet —
   * this runs before the RPC, so the CE-margin half of {@link #computeJudgeArbitrationAlpha}'s
   * gate is unavailable) is decisively confident that fusion order is already right, allowing the
   * CE call to be skipped rather than just re-weighted afterward.
   *
   * <p>Deliberately a STRICTER bar than the blend gate: an inconclusive signal (chunk-branch
   * active, or either leg empty — {@link #legAgreementJaccard} returns {@code -1.0}) means "not
   * decisive" here, so the default is to call the CE as normal — the opposite mapping from the
   * blend gate's "unknown → don't intervene" (which, for the blend, means "keep baseAlpha", i.e.
   * still call the CE). Skipping a query the CE would have fixed is a sharper risk than
   * re-weighting it, so it needs its own strict evidence, not a free ride on the blend gate's own
   * convention.
   *
   * @param window the pre-RPC candidate window (same slice that would become the CE's docTexts).
   * @return true if the CE RPC can be safely skipped for this query.
   */
  static boolean isFusionDecisiveForSkip(List<SearchResult> window) {
    if (chunkBranchActive(window)) {
      return false;
    }
    return legAgreementJaccard(window) >= JUDGE_ARBITRATION_OVERLAP_MIN;
  }

  /**
   * Tempdoc 643 (critical-analysis-pass): resolves the alpha to pass to
   * {@link #blendPreRerankAndCrossEncoder}, extracted out of the inline wiring so the exact gate
   * (arbitration enabled -> computed per-query signal; disabled -> the static config value) is a
   * pure, directly-testable decision that does not require mocking the cross-encoder RPC to
   * exercise. A wrong flag or a `||` typo here would silently change ranking behavior without
   * touching {@link #computeJudgeArbitrationAlpha} itself, so this gate needs its own test.
   */
  static double resolveBlendAlpha(
      RerankerConfig config,
      List<SearchResult> window,
      float[] crossEncoderScores) {
    return config.judgeArbitrationEnabled()
        ? computeJudgeArbitrationAlpha(
            window, crossEncoderScores, config.judgeBlendAlpha(), config.judgeArbitrationAlphaDiverge())
        : config.judgeBlendAlpha();
  }

  /**
   * Tempdoc 643 (perf-skip, critical-analysis-pass): resolves whether the cross-encoder RPC can be
   * skipped entirely, extracted out of the inline wiring for the same reason as
   * {@link #resolveBlendAlpha} — the two-flag-plus-signal gate is the actual "wrong gate" risk
   * here (independent of RPC/reranker correctness), so it should be unit-testable on its own.
   */
  static boolean shouldSkipCrossEncoder(
      RerankerConfig config, List<SearchResult> window) {
    return config.judgeArbitrationEnabled()
        && config.judgeArbitrationSkipEnabled()
        && isFusionDecisiveForSkip(window);
  }

  /** 306: reads query classification enabled flag from ConfigStore (default: true). */
  private static boolean isQueryClassificationEnabled() {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs == null || cs.get().search().queryClassificationEnabled();
  }

  private final KnowledgeServerBootstrap knowledgeServer;
  private final RerankerConfig rerankConfig;
  private final OnlineAiService onlineAiService;
  private final RerankerService lambdaMartReranker;
  private final QueryUnderstandingService quService;
  private final FilterNormalizationService normService;
  private final WorkerStatusCache statusCache;

  KnowledgeSearchEngine(KnowledgeServerBootstrap knowledgeServer) {
    this(knowledgeServer, OnlineAiService.unavailable(), null);
  }

  KnowledgeSearchEngine(
      KnowledgeServerBootstrap knowledgeServer, OnlineAiService onlineAiService) {
    this(knowledgeServer, onlineAiService, null);
  }

  KnowledgeSearchEngine(
      KnowledgeServerBootstrap knowledgeServer,
      OnlineAiService onlineAiService,
      RerankerService lambdaMartReranker) {
    this.knowledgeServer = Objects.requireNonNull(knowledgeServer, "knowledgeServer");
    this.onlineAiService = Objects.requireNonNull(onlineAiService, "onlineAiService");
    this.lambdaMartReranker = lambdaMartReranker; // nullable
    this.rerankConfig = RerankerConfig.fromEnv();
    this.quService = new QueryUnderstandingService(onlineAiService);
    this.normService = new FilterNormalizationService(onlineAiService);
    this.statusCache = new WorkerStatusCache(knowledgeServer);
    if (rerankConfig.enabled()) {
      log.info("Reranker enabled: topK={}, deadline={}ms, modelPath={}",
          rerankConfig.topK(), rerankConfig.deadlineBudgetMs(), rerankConfig.modelPath());
    }
  }

  // Tempdoc 556: status + facet-snapshot cache live in WorkerStatusCache; delegate.
  public KnowledgeStatus status(EngineContext engineContext) {
    return statusCache.status(engineContext);
  }

  public String getCachedFacetSnapshot() {
    return statusCache.getCachedFacetSnapshot();
  }

  public void setWorkerCapability(io.justsearch.app.services.lifecycle.WorkerCapability cap) {
    statusCache.setWorkerCapability(cap);
  }

  /**
   * 360: Returns whether the reranker config is ready. Used by
   * {@link io.justsearch.app.services.gpl.GplJobCoordinator} to check if remote reranking
   * is available (the Worker hosts the model).
   */
  public boolean isRerankerConfigured() {
    return RerankerConfig.fromEnv().isReady();
  }



  public KnowledgeSearchResponse search(KnowledgeSearchRequest req, EngineContext engineContext) {
    Objects.requireNonNull(req, "req");

    // 250 Phase 5c: Root span for the entire search pipeline
    Span searchSpan =
        tracer
            .spanBuilder("search")
            .setAttribute(
                "search.query_length",
                (long) (req.query() == null ? 0 : req.query().length()))
            .startSpan();
    Scope searchScope = searchSpan.makeCurrent();
    try {
      KnowledgeSearchResponse resp = doSearch(req, searchSpan, engineContext);
      // 553 Phase 4a: project the canonical trace onto the root span (telemetry = a projection).
      searchSpan.setAllAttributes(SearchTraceSpanProjection.attributesOf(resp.searchTrace()));
      return resp;
    } catch (Exception e) {
      searchSpan.setStatus(StatusCode.ERROR, e.getMessage());
      searchSpan.recordException(e);
      throw e;
    } finally {
      searchScope.close();
      searchSpan.end();
    }
  }

  private KnowledgeSearchResponse doSearch(
      KnowledgeSearchRequest req, Span searchSpan, EngineContext engineContext) {
    long doSearchStartNs = System.nanoTime();
    RerankerConfig rerankConfig = RerankerConfig.fromEnv();

    // 363: Refresh facet snapshot for QU grounding (non-blocking, cached with TTL)
    statusCache.refreshFacetSnapshotIfStale(engineContext);

    KnowledgeClient client = knowledgeServer.client();

    int requestedLimit = req.limit() == null ? 10 : Math.max(1, req.limit());
    // When reranking is enabled, fetch more candidates to improve reranking quality
    int searchLimit = rerankConfig.isReady()
        ? Math.max(requestedLimit, rerankConfig.topK())
        : requestedLimit;

    // Parse mode and query syntax early for reranking and expansion decisions
    SearchMode searchMode = SearchPipelinePresets.parseModeOrDefault(req.mode());
    SearchQuerySyntax querySyntax = SearchPipelinePresets.parseQuerySyntaxOrDefault(req.querySyntax());
    String queryText = req.query() == null ? "" : req.query();

    // 256: Phase A — derive PipelineConfig from preset or use explicit config from request.
    // Tempdoc 598 R1: when the request expresses NEITHER an explicit pipeline NOR an explicit mode,
    // the default is capability-derived AUTO (the dense leg runs iff the index is embedding-COMPATIBLE),
    // not a static keyword preset — so the main search UI reaches semantic search by default. An
    // explicit mode (incl. "text") or an explicit pipeline is an override and is honored unchanged.
    boolean denseAuto = req.pipeline() == null && (req.mode() == null || req.mode().isBlank());
    PipelineConfig pipelineConfig =
        req.pipeline() != null
            ? req.pipeline()
            : denseAuto
                ? SearchPipelinePresets.autoPreset(rerankConfig)
                : SearchPipelinePresets.expandPreset(searchMode, rerankConfig);

    // 306: Pre-retrieval query classification for CE/expansion gating.
    // Gated by config for A/B eval. When disabled, all queries are INFORMATIONAL (full pipeline).
    // Only apply query-type gating for preset pipelines — explicit PipelineConfig bypasses gating.
    boolean classificationEnabled = isQueryClassificationEnabled();
    boolean explicitPipeline = req.pipeline() != null;
    QueryType queryType = classificationEnabled
        ? QueryClassifier.classify(queryText) : QueryType.INFORMATIONAL;
    QueryType effectiveQueryType = explicitPipeline ? QueryType.INFORMATIONAL : queryType;

    // 385: Deterministic source extraction for structured queries (#10).
    // Synchronous (~0ms, regex against cached vocabulary). Results used by Phase 2/3 items.
    StructuredQueryAnalyzer.StructuredQueryAnalysis structuredAnalysis =
        StructuredQueryAnalyzer.analyze(queryText, statusCache.sourceVocabulary());
    if (log.isDebugEnabled() && !structuredAnalysis.detectedSources().isEmpty()) {
      log.debug("385: source detection: sources={}, vocabSize={}",
          structuredAnalysis.detectedSources(), statusCache.sourceVocabulary().size());
    }

    // 385: Temporal date extraction from query text (#9).
    // Synchronous (~0ms, regex). Dates fed into boost filters and answer type classification.
    TemporalQueryExtractor.TemporalExtraction temporalExtraction =
        TemporalQueryExtractor.extract(queryText);

    // 385: Answer type classification (#8). Uses date count from #9 for TEMPORAL detection.
    AnswerTypeClassifier.AnswerType answerType =
        AnswerTypeClassifier.classify(queryText, temporalExtraction.dates().size());
    String answerTypeName = answerType.name();

    // 256-I2: LLM query expansion gated by PipelineConfig.expansionEnabled().
    // 256-I4: structured skip reason tracking for pipeline execution report.
    // Fires before base search so LLM latency overlaps with gRPC round-trip.
    CompletableFuture<String> expansionFuture = null;
    boolean expansionApplied = false;
    String expansionSkipReason = null;
    long searchStartNs = System.nanoTime();
    if (isExpansionEligible(
        pipelineConfig, querySyntax, queryText, req.cursor(), onlineAiService.isAvailable(),
        effectiveQueryType)) {
      expansionFuture = startExpansionAsync(queryText);
    } else if (effectiveQueryType == QueryType.NAVIGATIONAL || effectiveQueryType == QueryType.EXACT_MATCH) {
      expansionSkipReason = "QUERY_TYPE_" + effectiveQueryType.name();
    } else if (!pipelineConfig.expansionEnabled()) {
      expansionSkipReason = "DISABLED";
    } else if (!onlineAiService.isAvailable()) {
      expansionSkipReason = "AI_UNAVAILABLE";
    } else if (querySyntax != SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE) {
      expansionSkipReason = "LUCENE_SYNTAX";
    } else if (queryText.isBlank()) {
      expansionSkipReason = "BLANK_QUERY";
    } else {
      expansionSkipReason = "PAGINATED";
    }

    // 363: Query Understanding — fire async LLM extraction for boost filters.
    // Bypass when: explicit filters/boostFilters present, blank query, paginated, AI unavailable,
    // or navigational/exact-match queries (which skip the full pipeline anyway).
    CompletableFuture<QueryUnderstandingService.QuResult> quFuture = null;
    boolean hasExplicitFilters = req.filters() != null || req.boostFilters() != null;
    if (!hasExplicitFilters
        && !queryText.isBlank()
        && (req.cursor() == null || req.cursor().isBlank())
        && effectiveQueryType != QueryType.NAVIGATIONAL
        && effectiveQueryType != QueryType.EXACT_MATCH
        && quService.isAvailable()) {
      quFuture = quService.extract(queryText, statusCache.getCachedFacetSnapshot());
    }

    // 366: Fire filter normalization async when explicit filters are present (mutually exclusive with QU)
    CompletableFuture<FilterNormalizationService.NormResult> normFuture = null;
    if (hasExplicitFilters && normService.isAvailable()) {
      normFuture = normService.normalize(req.filters(), statusCache.getCachedFacetSnapshot());
    }

    // 256-G3: PipelineConfig is the sole pipeline control on wire. Deprecated mode field no longer set.
    SearchRequest.Builder b =
        SearchRequest.newBuilder()
            .setQuery(queryText)
            .setLimit(searchLimit)
            // Tempdoc 549 Phase D2: the REST `debug` flag requests the numeric per-hit detail
            // tier — map it to include_detail (the deprecated gRPC debug field retires in E1).
            .setIncludeDetail(Boolean.TRUE.equals(req.debug()))
            .setSort(SearchPipelinePresets.parseSortOrDefault(req.sort()))
            .setQuerySyntax(querySyntax)
            .setIncludeExcerpts(Boolean.TRUE.equals(req.includeExcerpts()))
            .setPipeline(SearchPipelinePresets.toProtoPipelineConfig(pipelineConfig, denseAuto));

    if (req.cursor() != null && !req.cursor().isBlank()) {
      b.setCursor(req.cursor());
    }

    for (String p : req.projection()) {
      if (p != null && !p.isBlank()) {
        b.addProjection(p);
      }
    }

    // 366: Collect filter normalization result (if pending)
    KnowledgeSearchRequest.Filters filters = req.filters();
    FilterNormalizationService.NormResult normResultForResponse = null;
    if (normFuture != null) {
      try {
        FilterNormalizationService.NormResult normResult = normFuture.get();
        if (normResult != null && normResult.normalizedFilters() != null) {
          filters = normResult.normalizedFilters();
          normResultForResponse = normResult;
          log.debug("Filter normalization applied (source={}, latency={}ms)",
              normResult.source(), normResult.latencyMs());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        log.debug("Filter normalization failed: {}", e.getCause().getMessage());
      }
    }

    if (filters != null) {
      SearchRequestMapper.applyFilters(b, filters);
    }

    // Soft-boost filters (363): explicit boostFilters from request, OR QU-extracted boostFilters.
    KnowledgeSearchRequest.Filters boost = req.boostFilters();
    QueryUnderstandingService.QuResult quResultForResponse = null;  // 366 §3a: surface in response
    if (boost == null && quFuture != null) {
      // Collect QU result — blocks up to the QU deadline (2s), but the LLM call was
      // already running in parallel with filter/facet setup above.
      try {
        QueryUnderstandingService.QuResult quResult = quFuture.get();
        if (quResult != null && quResult.boostFilters() != null) {
          boost = quResult.boostFilters();
          quResultForResponse = quResult;
          log.debug("QU applied boost filters in {}ms", quResult.latencyMs());
        } else if (quResult != null) {
          log.debug("QU passthrough (no filters extracted) in {}ms", quResult.latencyMs());
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.debug("QU interrupted");
      } catch (ExecutionException e) {
        log.debug("QU extraction failed: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
      }
    }
    if (boost != null) {
      SearchRequestMapper.applyBoostFilters(b, boost, temporalExtraction);
    }

    // 385: Temporal range injection when no QU boost is active (#9)
    if (boost == null && !hasExplicitFilters) {
      SearchRequestMapper.applyTemporalOnlyBoost(b, temporalExtraction);
    }

    KnowledgeSearchRequest.Facets facets = req.facets();
    if (facets != null) {
      SearchRequestMapper.applyFacets(b, facets);
    }

    SearchRequest baseReq = b.build();

    // 385: Per-source retrieval for queries with 2+ detected sources (#6 + #1).
    // Gate: any query mentioning 2+ sources benefits from balanced per-source retrieval,
    // not just COMPARISON queries (FeB4RAG research). Answer type remains a separate hint.
    SearchResponse resp;
    boolean perSourceRetrieval = false;
    if (structuredAnalysis.detectedSources().size() >= 2) {
      resp = SearchPerSourceExecutor.execute(
          client, baseReq, structuredAnalysis.detectedSources(), searchLimit, engineContext);
      perSourceRetrieval = true;
    } else {
      // Single-source: inject detected sources as boost if no boost already set
      if (!structuredAnalysis.detectedSources().isEmpty() && !b.hasBoostFilters()) {
        SearchFilters.Builder srcBoost = SearchFilters.newBuilder();
        for (String src : structuredAnalysis.detectedSources()) {
          srcBoost.addMetaSource(src.toLowerCase(Locale.ROOT));
        }
        resp = client.search(baseReq.toBuilder().setBoostFilters(srcBoost.build()).build(), engineContext);
      } else {
        resp = client.search(baseReq, engineContext);
      }
    }

    // If LLM expansion is in flight, wait remaining budget then re-search with expanded terms.
    // Falls back to base results on timeout or error — user always gets a result.
    // 385: Skip expansion re-search when per-source retrieval was used — the topic remainder
    // already strips source name noise, and re-searching would discard the interleaved result.
    if (expansionFuture != null && !perSourceRetrieval) {
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - searchStartNs);
      long remainingMs = EXPANSION_BUDGET_MS - elapsedMs;
      if (remainingMs > 0) {
        try {
          String expansionText = expansionFuture.get(remainingMs, TimeUnit.MILLISECONDS);
          String expandedQuery = mergeExpansion(queryText, expansionText);
          if (expandedQuery != null) {
            // LUCENE syntax for expanded search: avoids withPrefixExpansion() applying to the
            // LLM-appended last token instead of the user's actual last word.
            SearchRequest expandedReq =
                baseReq.toBuilder()
                    .setQuery(expandedQuery)
                    .setQuerySyntax(SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE)
                    .build();
            resp = client.search(expandedReq, engineContext);
            expansionApplied = true;
            log.debug("LLM expansion applied to query");
          }
        } catch (TimeoutException e) {
          expansionFuture.cancel(true);
          expansionSkipReason = "TIMEOUT";
          log.debug(
              "LLM expansion timed out ({}ms budget), using base results", EXPANSION_BUDGET_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          expansionSkipReason = "FAILED";
        } catch (ExecutionException e) {
          expansionSkipReason = "FAILED";
          log.debug("LLM expansion failed: {}", e.getCause().getMessage());
        } catch (RuntimeException e) {
          // The expansion re-search is an OPTIONAL enhancement over an answer we already hold, and
          // this block's contract (line 779) is "falls back to base results on timeout or error".
          // Only the checked failures were caught, so a failing re-search took the whole search
          // down with it. Reachable since tempdoc 821 §P: the multi-leg path now honours this
          // request's LUCENE syntax and rejects a malformed parse with INVALID_ARGUMENT (as the
          // sparse-only path always did) — and `expandedQuery` embeds the RAW user query text, so
          // any Lucene metacharacter the user typed can produce one. `resp` still holds the base
          // response here, so the fallback is simply not overwriting it.
          expansionSkipReason = "FAILED";
          log.warn("LLM expansion re-search failed, using base results: {}", e.getMessage());
        }
      } else {
        expansionFuture.cancel(true);
        expansionSkipReason = "TIMEOUT";
      }
    }

    // 256-F1: Cross-encoder reranking fires for all search modes when enabled.
    // Eligibility guard: isRerankerEligible checks pipeline.crossEncoderEnabled() + min hits threshold.
    List<SearchResult> results = resp.getResultsList();

    // LambdaMART reranking: fires first when a trained model is loaded.
    // 256-F2: cross-encoder now runs after LambdaMART (2-stage cascaded reranking).
    boolean lambdaMartApplied = false;
    String lambdaMartSkipReason = null;
    long lambdaMartNs = 0;
    if (!pipelineConfig.lambdamartEnabled()) {
      lambdaMartSkipReason = "PIPELINE_NOT_ELIGIBLE";
    } else if (lambdaMartReranker == null) {
      lambdaMartSkipReason = "NO_MODEL";
    } else if (!lambdaMartReranker.isLoaded()) {
      lambdaMartSkipReason = "MODEL_NOT_LOADED";
    } else if (results.isEmpty()) {
      lambdaMartSkipReason = "NO_RESULTS";
    } else {
      Span lmSpan =
          tracer.spanBuilder("search/lambdamart").setParent(Context.current()).startSpan();
      try (Scope lmScope = lmSpan.makeCurrent()) { // NOPMD - scope used for auto-close
        long lmStart = System.nanoTime();
        int n = results.size();
        float[] sparseScores = new float[n];
        float[] vectors = new float[n];
        float[] spladeScores = new float[n];
        for (int i = 0; i < n; i++) {
          SearchResult sr = results.get(i);
          // Tempdoc 549 Phase E1: LambdaMART inference reads leg scores from the always-on
          // structural per-hit trace (sparse/dense/splade-retrieval stage scores), not the retired
          // debug_scores map. Emitted regardless of include_detail, so LTR is unaffected by the
          // detail-tier gate. Tempdoc 580 §17 P5 V2: splade is now a distinct third feature.
          sparseScores[i] = SearchTraceMapper.protoStageScore(sr, "sparse-retrieval");
          vectors[i] = SearchTraceMapper.protoStageScore(sr, "dense-retrieval");
          spladeScores[i] = SearchTraceMapper.protoStageScore(sr, "splade-retrieval");
        }
        List<Integer> order = lambdaMartReranker.rerank(sparseScores, vectors, spladeScores, n);
        if (order != null) {
          // Tempdoc 821 §L.3: same candidate-drop class as the cross-encoder branch below. The
          // RerankerService contract promises only "indices in descending relevance order" — an
          // implementation returning a short or out-of-range list would drop candidates or throw.
          // applyRerankOrder keeps every candidate; n == results.size(), so nothing sits beyond
          // the window and the whole list is the reorder scope.
          results = applyRerankOrder(results, order, n);
          lambdaMartApplied = true;
          log.debug("LambdaMART reranked {} results", n);
        } else {
          lambdaMartSkipReason = "INFERENCE_FAILED";
        }
        lambdaMartNs = System.nanoTime() - lmStart;
        // Tempdoc 553 Phase D (head): OpenInference RERANKER projection of the LTR-reordered output.
        lmSpan.setAllAttributes(
            io.justsearch.telemetry.OpenInferenceSpans.reranker(
                "lambdamart", n, SearchTraceMapper.oiDocsOf(results)));
      } finally {
        lmSpan.end();
      }
    }

    // 250: Cross-encoder execution tracking
    boolean crossEncoderApplied = false;
    // Register F-052: the skip reason is a closed head-owned vocabulary, not a raw string — a
    // drop-class member (deadline / RPC / model-absent) is worded at the user tier by the FE.
    CrossEncoderSkipReason crossEncoderSkipReason = null;
    Map<String, Float> ceScoresByDocId = null;
    long crossEncoderMs = -1;

    // 256-F2: LambdaMART + cross-encoder co-execution — cross-encoder runs on LambdaMART's
    // reordered output (standard 2-stage cascaded reranking pattern).
    if (!isRerankerEligible(
        pipelineConfig, rerankConfig, results.size(), statusCache.avgContentLengthChars(), effectiveQueryType)) {
      if (effectiveQueryType == QueryType.NAVIGATIONAL) {
        crossEncoderSkipReason = CrossEncoderSkipReason.NAVIGATIONAL_QUERY;
      } else if (!rerankConfig.enabled()) {
        crossEncoderSkipReason = CrossEncoderSkipReason.DISABLED;
      } else if (results.size() < rerankConfig.minHitsThreshold()) {
        crossEncoderSkipReason = CrossEncoderSkipReason.BELOW_MIN_THRESHOLD;
      } else if (rerankConfig.maxAvgDocLengthChars() > 0
          && statusCache.avgContentLengthChars() > rerankConfig.maxAvgDocLengthChars()) {
        crossEncoderSkipReason = CrossEncoderSkipReason.DOCS_TOO_LONG;
      } else {
        crossEncoderSkipReason = CrossEncoderSkipReason.PIPELINE_NOT_ELIGIBLE;
      }
    } else if (!rerankConfig.isReady()) {
      // 360: model not configured — skip without RPC round-trip
      crossEncoderSkipReason = CrossEncoderSkipReason.MODEL_NOT_CONFIGURED;
    } else {
      // 360: Remote reranking via Worker's GPU-capable cross-encoder
      // 256-F4: PipelineConfig window overrides global config default
      int configWindow = pipelineConfig.crossEncoderWindow();
      int topK = Math.min(configWindow > 0 ? configWindow : rerankConfig.topK(), results.size());

      RerankResponse reranked;
      // Tempdoc 643 (perf-skip): when arbitration + skip are both enabled and the leg-agreement
      // signal ALONE (no CE score exists yet — this runs before the RPC) is decisively confident
      // that fusion is already right, skip the CE RPC entirely instead of paying its latency only
      // to blend it back down toward fusion afterward. A stricter bar than the blend gate: an
      // inconclusive signal (chunk-branch active, or either leg empty) means "not decisive" here,
      // so the default stays "call the CE" — skipping a query the CE would have fixed is a
      // sharper risk than re-weighting it, so it needs its own evidence, not a free ride on the
      // blend gate's own "unknown -> don't intervene" convention. Kept behind its own flag.
      if (shouldSkipCrossEncoder(rerankConfig, results.subList(0, topK))) {
        crossEncoderSkipReason = CrossEncoderSkipReason.FUSION_CONFIDENT;
        reranked = null;
      } else {
        List<String> docTexts = new ArrayList<>(topK);
        for (int i = 0; i < topK; i++) {
          docTexts.add(buildCrossEncoderDocText(results.get(i)));
        }

        Span ceSpan =
            tracer.spanBuilder("search/cross_encoder").setParent(Context.current()).startSpan();
        try (Scope ceScope = ceSpan.makeCurrent()) { // NOPMD - scope used for auto-close
          reranked = knowledgeServer.client().rerank(
              req.query(), docTexts, rerankConfig.deadlineBudgetMs(), engineContext);
          // Tempdoc 553 Phase D (head): OpenInference RERANKER projection of the CE-scored
          // output — the reranked docs (id + CE score + content), in the cross-encoder's chosen
          // order.
          if (reranked != null && !reranked.getSkipped()) {
            ceSpan.setAllAttributes(
                io.justsearch.telemetry.OpenInferenceSpans.reranker(
                    "cross-encoder", topK, SearchTraceMapper.ceOutputDocs(results, reranked)));
          }
        } catch (Exception e) {
          log.warn("Remote rerank failed, using original order: {}", e.getMessage());
          crossEncoderSkipReason = CrossEncoderSkipReason.RPC_FAILED;
          reranked = null;
        } finally {
          ceSpan.end();
        }
      }

      if (reranked != null && !reranked.getSkipped()) {
        // 250 Phase 2: capture CE scores by docId before reordering (the TRUE raw CE score,
        // independent of the judge-blend floor below — ceScoresByDocId feeds the canonical
        // SearchTrace CROSS_ENCODER HitStage and must reflect what the CE actually scored).
        ceScoresByDocId = new HashMap<>();
        for (int origIdx : reranked.getSortedIndicesList()) {
          if (origIdx < results.size() && origIdx < reranked.getScoresCount()) {
            ceScoresByDocId.put(
                results.get(origIdx).getId(), reranked.getScores(origIdx));
          }
        }
        // Tempdoc 643: judge-stage refinement floor — when enabled, blend the CE's reorder with
        // the pre-rerank (fusion/LambdaMART) order instead of letting the CE replace it outright.
        // Default off (judgeBlendEnabled=false) is a strict no-op: blendPreRerankAndCrossEncoder
        // with alpha effectively unused reduces to the CE-only order (see its javadoc).
        List<Integer> orderToApply;
        if (rerankConfig.judgeBlendEnabled()) {
          // Window = topK (not getScoresCount()) so every pre-rerank candidate the CE was asked
          // to score stays in the blended order — a short getScoresCount() (defensively handled,
          // not expected in normal operation) fills the missing tail rather than silently
          // dropping those candidates from the result set entirely (see the min-fill below).
          //
          // Critical-analysis-pass fix (2026-07-01): a single "fusion" stage read is not a
          // reliable pre-rerank score across all pipeline shapes — it is null'd out entirely for
          // single-leg presets and stale (pre-branch-merge) whenever chunk-branch fusion ran.
          // protoStageScoreAny tries the true final score first, falling back in priority order.
          float[] preRerankScores = new float[topK];
          float[] crossEncoderScores = new float[topK];
          float ceMinObserved = Float.MAX_VALUE;
          for (int i = 0; i < topK; i++) {
            if (i < reranked.getScoresCount()) {
              float ce = reranked.getScores(i);
              crossEncoderScores[i] = ce;
              ceMinObserved = Math.min(ceMinObserved, ce);
            }
          }
          // A missing CE score defaults to the worst OBSERVED score, not a literal 0f — real CE
          // scores are raw logits and frequently negative, so 0f would read as an artificially
          // high (favorable) score for a candidate the CE never actually judged.
          float missingCeDefault = ceMinObserved == Float.MAX_VALUE ? 0f : ceMinObserved;
          for (int i = 0; i < topK; i++) {
            preRerankScores[i] = SearchTraceMapper.protoStageScoreAny(
                results.get(i), "branch-fusion", "fusion",
                "sparse-retrieval", "dense-retrieval", "splade-retrieval");
            if (i >= reranked.getScoresCount()) {
              crossEncoderScores[i] = missingCeDefault;
            }
          }
          // Tempdoc 643 (E1/E2): when arbitration is enabled, compute alpha per query from a
          // runtime confidence signal (CE margin + leg agreement) instead of reading the static
          // judgeBlendAlpha unconditionally. Off (default) is a strict no-op — resolveBlendAlpha
          // returns exactly judgeBlendAlpha(), byte-identical to the pre-arbitration wiring above.
          double alphaToApply = resolveBlendAlpha(
              rerankConfig, results.subList(0, topK), crossEncoderScores);
          orderToApply = blendPreRerankAndCrossEncoder(
              preRerankScores, crossEncoderScores, alphaToApply);
        } else {
          // Tempdoc 821 §L.3: the Worker's sorted_indices are applied as-is for the window
          // positions they cover, but a short list no longer drops the positions it omits —
          // applyRerankOrder passes those through in original order, the same "fill, don't drop"
          // defense the judge-blend branch above gets from its topK-sized window.
          orderToApply = reranked.getSortedIndicesList();
        }
        // Reorder the CE window by the (possibly blended) order, then append the candidates
        // beyond topK that were never reranked. Count in equals count out.
        results = applyRerankOrder(results, orderToApply, topK);
        crossEncoderApplied = true;
        crossEncoderMs = reranked.getElapsedMs();
        log.debug("Reranked {} docs in {}ms (remote)", topK, reranked.getElapsedMs());
      } else if (reranked != null) {
        // Register F-052: normalise the Worker's skip_reason into the closed vocabulary — an
        // unrecognised string becomes UNKNOWN instead of reaching the FE as an unworded code.
        crossEncoderSkipReason =
            CrossEncoderSkipReason.fromWorkerSkipReason(reranked.getSkipReason());
        crossEncoderMs = reranked.getElapsedMs();
        // A drop means the relevance model was expected to run and did not — the ranking the user
        // gets is fusion/LambdaMART order. That is a degradation, so it logs at WARN; a by-design
        // skip stays at DEBUG.
        if (crossEncoderSkipReason.isDrop()) {
          // Register F-054: INFERENCE_FAILED is not a latency problem, so the WARN must not send
          // the reader to the deadline knob. The Worker log at the failure site names the exact
          // remedy; the one that a measured campaign had to find by hand is repeated here.
          String remedy =
              crossEncoderSkipReason == CrossEncoderSkipReason.INFERENCE_FAILED
                  ? " — the relevance model failed to run (not a deadline miss); if the Worker log"
                      + " shows an ONNX Runtime arena allocation failure, raise"
                      + " JUSTSEARCH_RERANK_GPU_MEM_MB"
                  : "";
          log.warn(
              "Cross-encoder dropped: {} after {}ms — results keep their fusion order{}",
              crossEncoderSkipReason,
              reranked.getElapsedMs(),
              remedy);
        } else {
          log.debug("Rerank skipped: {} ({}ms)", crossEncoderSkipReason, reranked.getElapsedMs());
        }
      }
    }

    // Trim results to the originally requested limit (we may have fetched more for reranking)
    if (results.size() > requestedLimit) {
      results = results.subList(0, requestedLimit);
    }

    List<KnowledgeSearchResponse.Hit> hits =
        SearchResultMapper.toHits(results, ceScoresByDocId, pipelineConfig.freshnessEnabled());

    Map<String, Map<String, Long>> facetsOut = Map.of();
    if (!resp.getFacetsMap().isEmpty()) {
      Map<String, Map<String, Long>> m = new HashMap<>();
      for (var entry : resp.getFacetsMap().entrySet()) {
        FacetCounts counts = entry.getValue();
        m.put(entry.getKey(), counts == null ? Map.of() : counts.getCountsMap());
      }
      facetsOut = Map.copyOf(m);
    }

    // Phase C: extract entity facet variant breakdowns
    Map<String, List<KnowledgeSearchResponse.EntityVariantBreakdown>> variantsOut = Map.of();
    if (!resp.getEntityFacetVariantsMap().isEmpty()) {
      Map<String, List<KnowledgeSearchResponse.EntityVariantBreakdown>> vm = new HashMap<>();
      for (var vEntry : resp.getEntityFacetVariantsMap().entrySet()) {
        EntityFacetVariants proto = vEntry.getValue();
        if (proto == null || proto.getEntriesCount() == 0) continue;
        List<KnowledgeSearchResponse.EntityVariantBreakdown> breakdowns = new ArrayList<>();
        for (var bd : proto.getEntriesList()) {
          breakdowns.add(
              new KnowledgeSearchResponse.EntityVariantBreakdown(
                  bd.getCanonicalForm(), bd.getTotalCount(), bd.getVariantsMap()));
        }
        vm.put(vEntry.getKey(), breakdowns);
      }
      variantsOut = Map.copyOf(vm);
    }

    String nextCursor = resp.getNextCursor();
    if (nextCursor != null && nextCursor.isBlank()) {
      nextCursor = null;
    }
    // Tempdoc 549 Phase E5: the flat query-trace fields (effective_mode, degradation reasons,
    // qpp) are retired — read the effective mode (telemetry span) + QPP (DEBUG log) from the
    // unified trace, the single source. The degradation-reason flat locals were already dead
    // (their consumer, buildPipelineExecution, retired in E3).
    io.justsearch.ipc.SearchTrace traceScalars = resp.hasSearchTrace() ? resp.getSearchTrace() : null;
    String effectiveMode =
        traceScalars != null && !traceScalars.getEffectiveMode().isBlank()
            ? traceScalars.getEffectiveMode()
            : null;

    // P1-D: Log QPP signals at DEBUG. Use a stable hash of query text — never log raw query.
    if (log.isDebugEnabled() && traceScalars != null && traceScalars.hasQpp()) {
      io.justsearch.ipc.TraceQpp qpp = traceScalars.getQpp();
      if (qpp.getMaxIdf() > 0f || qpp.getQueryScope() > 0f) {
        log.debug(
            "qpp query_hash={} mode={} max_idf={} avg_ictf={} query_scope={}",
            Integer.toHexString(queryText.hashCode()),
            searchMode,
            qpp.getMaxIdf(),
            qpp.getAvgIctf(),
            qpp.getQueryScope());
      }
    }

    // 250 Phase 5a: Record head-side fallback counters
    if (rerankConfig.enabled() && !crossEncoderApplied) rerankerSkipped().add(1);
    if (lambdaMartReranker != null && lambdaMartReranker.isLoaded() && !lambdaMartApplied) {
      lambdamartSkipped().add(1);
    }

    // Total Head-side search time: Worker RPC + CE RPC + all overhead.
    // This is the true client-visible search latency.
    long totalSearchMs = (System.nanoTime() - doSearchStartNs) / 1_000_000;

    // 250 Phase 5c: Enrich root span with result attributes
    searchSpan.setAttribute("search.total_hits", resp.getTotalHits());
    searchSpan.setAttribute("search.took_ms", totalSearchMs);
    searchSpan.setAttribute("search.mode", effectiveMode != null ? effectiveMode : "");

    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(resp.getTotalHits())
        // Tempdoc 597: the true matched-document count (distinct from the union totalHits).
        .matchCount(resp.getMatchCount())
        .tookMs(totalSearchMs)
        .results(hits)
        .nextCursor(nextCursor)
        .facets(facetsOut)
        .facetsTruncated(resp.getFacetsTruncated())
        // Tempdoc 549 U4 (Slice 6): the 15 flat query-trace fields were removed from the response
        // record. The canonical `introspection` trace (built below from the same `resp` proto via
        // KnowledgeIntrospectionMapper + buildHeadStages) is now the single source.
        .entityFacetVariants(variantsOut)
        .indexCapabilities(buildIndexCapabilities())
        // Tempdoc 549 Phase E3: pipelineExecution retired — per-stage timing + component statuses
        // are on the unified trace (TraceStage.ms / status), composed below in mapSearchTrace.
        .queryUnderstanding(buildQueryUnderstanding(quResultForResponse, answerTypeName))
        .filterNormalization(buildFilterNormalization(normResultForResponse))
        // Tempdoc 549 Phase E4: SearchIntrospection retired — the unified stage-keyed trace is the
        // single source. Compose the worker's proto stages (Phase B) + the head's stages into one
        // app-api SearchTrace.
        .searchTrace(
            SearchTraceMapper.mapSearchTrace(
                resp,
                SearchTraceMapper.buildHeadStages(
                    pipelineConfig,
                    lambdaMartNs,
                    crossEncoderMs,
                    lambdaMartApplied,
                    lambdaMartSkipReason,
                    crossEncoderApplied,
                    crossEncoderSkipReason,
                    expansionApplied,
                    expansionSkipReason,
                    queryType)))
        // Tempdoc 366 §1b: echo the filters the request carried, so a client can tell a scoped
        // search from one whose scope was dropped without inferring it from the rows.
        .appliedFilters(
            KnowledgeSearchResponse.AppliedFilters.of(req.filters(), req.boostFilters()))
        .build();
  }


  private static KnowledgeSearchResponse.FilterNormalization buildFilterNormalization(
      FilterNormalizationService.NormResult normResult) {
    if (normResult == null) return null;
    return new KnowledgeSearchResponse.FilterNormalization(
        normResult.original(), normResult.normalized(), normResult.latencyMs(), normResult.source());
  }

  private static KnowledgeSearchResponse.QueryUnderstanding buildQueryUnderstanding(
      QueryUnderstandingService.QuResult quResult, String answerType) {
    Map<String, List<String>> boosts = null;
    long latencyMs = 0L;

    if (quResult != null && quResult.boostFilters() != null) {
      var filters = quResult.boostFilters();
      var b = new java.util.LinkedHashMap<String, List<String>>();
      if (!filters.metaSource().isEmpty()) b.put("meta_source", filters.metaSource());
      if (!filters.metaAuthor().isEmpty()) b.put("meta_author", filters.metaAuthor());
      if (!filters.metaCategory().isEmpty()) b.put("meta_category", filters.metaCategory());
      if (!filters.entityPersons().isEmpty()) b.put("entity_persons", filters.entityPersons());
      if (!filters.entityOrganizations().isEmpty()) b.put("entity_organizations", filters.entityOrganizations());
      if (!filters.entityLocations().isEmpty()) b.put("entity_locations", filters.entityLocations());
      if (!b.isEmpty()) boosts = b;
      latencyMs = quResult.latencyMs();
    }

    // 385: Surface answer type even when QU didn't run, but only if non-default (#8)
    boolean hasBoosts = boosts != null;
    boolean hasAnswerType = answerType != null && !"INFERENCE".equals(answerType);
    if (!hasBoosts && !hasAnswerType) return null;

    return new KnowledgeSearchResponse.QueryUnderstanding(
        boosts, latencyMs, hasAnswerType ? answerType : null);
  }


  /**
   * Fires an asynchronous LLM call to generate morphological variants of the query terms.
   *
   * <p>Uses {@link SamplingParams#DETERMINISTIC} to minimize hallucination risk. The caller
   * must wait on the returned future within {@link #EXPANSION_BUDGET_MS} and cancel on timeout.
   */
  private CompletableFuture<String> startExpansionAsync(String query) {
    CompletableFuture<String> future = new CompletableFuture<>();
    List<Map<String, Object>> messages =
        List.of(
            Map.of("role", "system", "content", EXPANSION_SYSTEM_PROMPT),
            Map.of("role", "user", "content", query));
    StringBuilder buf = new StringBuilder();
    onlineAiService.streamChat(
        messages,
        EXPANSION_MAX_TOKENS,
        buf::append,
        fr -> future.complete(buf.toString().strip()),
        future::completeExceptionally,
        SamplingParams.DETERMINISTIC);
    return future;
  }

  /**
   * Validates LLM expansion output and returns a merged query string, or {@code null} if the
   * expansion should be discarded.
   *
   * <p>Truncates expansion tokens to 3× the original query's token count (length guard), then
   * rejects if any remaining token is non-alphabetic (hallucination guard), or if no new terms
   * were added. Truncating rather than rejecting handles the common case where the model produces
   * valid morphological variants for each word but exceeds the cap due to the 5-form system prompt.
   *
   * <p>The merged string is re-issued as a LUCENE-syntax request (the {@code ^0.3} boosts have to
   * parse), but expansion only runs for SIMPLE requests ({@code :644-645}) — users who never asked
   * for Lucene semantics. So the user's half is ESCAPED here: without it, {@code covid -vaccine}
   * would silently invert to a NOT and {@code error:timeout} would become a query against a
   * non-existent field, with {@code expansionApplied=true} and no degradation signal. The escape
   * set is exactly the one the SIMPLE path applies before parsing, so the user's half means what it
   * would have meant unexpanded; only the appended (alphabetic-guarded) variants carry syntax.
   */
  static String mergeExpansion(String originalQuery, String expansionText) {
    if (expansionText == null || expansionText.isBlank()) {
      return null;
    }
    String[] origTokens = originalQuery.strip().split("\\s+");
    String[] expandTokens = expansionText.strip().split("\\s+");
    // Length guard: truncate to 3× original token count rather than rejecting entirely.
    // The system prompt requests up to 5 variant forms per word; capping preserves valid
    // partial expansions instead of silently discarding everything.
    int maxExpand = origTokens.length * 3;
    if (expandTokens.length > maxExpand) {
      expandTokens = Arrays.copyOf(expandTokens, maxExpand);
    }
    // Hallucination guard: all expansion tokens must be pure alphabetic
    for (String t : expandTokens) {
      if (!ALPHA_ONLY.matcher(t).matches()) {
        return null;
      }
    }
    // Merge original query with new tokens (deduplicated, case-insensitive)
    Set<String> seen = new HashSet<>();
    for (String t : origTokens) {
      seen.add(t.toLowerCase(Locale.ROOT));
    }
    StringBuilder sb = new StringBuilder(escapeLuceneSyntax(originalQuery.strip()));
    boolean added = false;
    for (String t : expandTokens) {
      if (seen.add(t.toLowerCase(Locale.ROOT))) {
        sb.append(' ').append(t).append("^0.3");
        added = true;
      }
    }
    return added ? sb.toString() : null;
  }

  /**
   * Escapes the Lucene query-syntax metacharacters, so text a user typed as plain words stays plain
   * words when it is embedded in a LUCENE-syntax request ({@link #mergeExpansion}).
   *
   * <p>The character set is restated rather than imported: the Head never touches Lucene (hard
   * invariant 1) and lucene-core is {@code runtimeOnly} in this module. It is the same set the
   * Worker's SIMPLE path escapes before parsing, which is what makes the escaped half equivalent to
   * an unexpanded SIMPLE query. Expansion variants are appended AFTER this call because their
   * {@code ^0.3} boost must survive as syntax.
   */
  static String escapeLuceneSyntax(String text) {
    StringBuilder out = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\'
          || c == '+'
          || c == '-'
          || c == '!'
          || c == '('
          || c == ')'
          || c == ':'
          || c == '^'
          || c == '['
          || c == ']'
          || c == '"'
          || c == '{'
          || c == '}'
          || c == '~'
          || c == '*'
          || c == '?'
          || c == '|'
          || c == '&'
          || c == '/') {
        out.append('\\');
      }
      out.append(c);
    }
    return out.toString();
  }

  // Tempdoc 549 Phase E2: the leg-keyed per-hit HitProvenance (and its head-side
  // mapWorkerProvenance mapper) is RETIRED. Per-hit ranking provenance is now the per-doc slice
  // of the unified stage vocabulary (mapHitStages → Hit.trace), incl. the head's cross-encoder
  // stage. The earlier debug_scores reconstruction (assembleProvenance) was retired before it.


  /**
   * Builds an IndexCapabilities snapshot from the cached Worker operational view. Returns null if
   * no cached view is available yet (before first status poll).
   */
  private KnowledgeSearchResponse.IndexCapabilities buildIndexCapabilities() {
    if (!statusCache.isWorkerReady()) {
      return null;
    }
    var view = knowledgeServer.client().cachedOperationalView();
    if (view == null) {
      return null;
    }
    Double embCoverage =
        view.enrichment().embeddingDocCount() > 0 ? view.enrichment().embeddingCoveragePercent() / 100.0 : null;
    Double spladeCoverage =
        view.enrichment().spladeDocCount() > 0 ? view.enrichment().spladeCoveragePercent() / 100.0 : null;
    Double chunkCoverage =
        view.enrichment().chunk().chunkDocCount() > 0 ? view.enrichment().chunk().chunkVectorCoveragePercent() / 100.0 : null;
    Boolean ceAvailable = rerankConfig.enabled() ? true : null;
    return new KnowledgeSearchResponse.IndexCapabilities(
        embCoverage, spladeCoverage, chunkCoverage, ceAvailable);
  }
}
