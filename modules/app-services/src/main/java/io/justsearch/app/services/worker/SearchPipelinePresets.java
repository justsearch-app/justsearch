/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.ipc.SearchSort;
import io.justsearch.reranker.RerankerConfig;
import java.util.ArrayList;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 556 (F-C4.2): request-parameter parsing and {@link SearchMode} preset → {@link
 * PipelineConfig} expansion, plus proto pipeline-config marshalling. Extracted verbatim from {@code
 * KnowledgeHttpApiAdapter} so the adapter stays a thin orchestrator. All pure statics.
 */
final class SearchPipelinePresets {

  private static final Logger log = LoggerFactory.getLogger(SearchPipelinePresets.class);

  private SearchPipelinePresets() {}

  public static SearchMode parseModeOrDefault(String mode) {
    if (mode == null || mode.isBlank()) {
      return SearchMode.SEARCH_MODE_TEXT;
    }
    String m = mode.trim().toLowerCase(Locale.ROOT);
    return switch (m) {
      case "text", "lexical" -> SearchMode.SEARCH_MODE_TEXT;
      case "vector" -> SearchMode.SEARCH_MODE_VECTOR;
      case "hybrid" -> SearchMode.SEARCH_MODE_HYBRID;
      case "splade" -> SearchMode.SEARCH_MODE_SPLADE;
      default -> {
        log.warn("Unknown search mode '{}'; defaulting to TEXT preset", m);
        yield SearchMode.SEARCH_MODE_TEXT;
      }
    };
  }

  /**
   * Expands a {@link SearchMode} preset into a {@link PipelineConfig} (256: Phase A). The
   * backwards-compatibility translation layer for searches that arrive with only a {@code mode}
   * string. Package-private for contract testing.
   */
  static PipelineConfig expandPreset(SearchMode mode, RerankerConfig rerankConfig) {
    boolean ce = rerankConfig.enabled();
    return switch (mode) {
      case SEARCH_MODE_TEXT -> new PipelineConfig(true, false, false, "none", true, ce, 0, true, true);
      case SEARCH_MODE_VECTOR ->
          new PipelineConfig(false, true, false, "none", false, ce, 0, false, true);
      case SEARCH_MODE_HYBRID ->
          new PipelineConfig(true, true, false, "rrf", true, ce, 0, false, true);
      case SEARCH_MODE_SPLADE ->
          new PipelineConfig(false, false, true, "none", false, ce, 0, true, true);
      default -> PipelineConfig.TEXT;
    };
  }

  /**
   * Tempdoc 598 R1: the capability-derived AUTO preset — the default when a request expresses no
   * pipeline AND no mode. Sparse on; the dense leg is NOT asserted here (it is carried as the proto
   * {@code dense_auto} marker and resolved Worker-side from the embedding-compat boundary); {@code rrf}
   * fusion so the resolved-hybrid case fuses; cross-encoder per config; freshness on (interactive search).
   *
   * <p>Tempdoc 598 review Fix C: {@code expansion} is ON — matching the {@link #expandPreset TEXT preset}
   * — so that when AUTO degrades to keyword (index not dense-serviceable) the morphological/LLM query
   * expansion that benefits sparse-only search is preserved; without it, AUTO's fallback would be a
   * regression vs the prior TEXT default exactly in the degraded state where keyword is the only option.
   * Query expansion is gated/executed Head-side ({@code KnowledgeSearchEngine}) from this flag, before the
   * Worker resolves the dense leg, so it must be decided here. When AUTO instead resolves to hybrid,
   * expansion also runs (the explicit HYBRID preset disables it as a dense-carries-recall optimization) —
   * a minor extra cost that only occurs when AI is online; not incorrect.
   *
   * <p>{@code dense_enabled} stays false on the app-api record; the proto marker is set via
   * {@link #toProtoPipelineConfig(PipelineConfig, boolean)}.
   */
  static PipelineConfig autoPreset(RerankerConfig rerankConfig) {
    boolean ce = rerankConfig.enabled();
    return new PipelineConfig(true, false, false, "rrf", true, ce, 0, true, true);
  }

  /** Converts an app-api {@link PipelineConfig} to the proto wire type (no AUTO marker). */
  static io.justsearch.ipc.PipelineConfig toProtoPipelineConfig(PipelineConfig cfg) {
    return toProtoPipelineConfig(cfg, false);
  }

  /**
   * Tempdoc 735 G5: single authority for the default agent-path pipeline request — the explicit
   * HYBRID preset with {@code denseAuto=false}, matching what {@code justsearch_search}
   * (McpToolSurface's {@code mode="hybrid"} default) resolves to via {@code
   * KnowledgeSearchEngine#doSearch}'s generic mode dispatch. Fuses {@link #expandPreset} +
   * {@link #toProtoPipelineConfig(PipelineConfig, boolean)} for call sites with no intervening use
   * for the intermediate app-api {@link PipelineConfig} — {@code KnowledgeSearchEngine#doSearch}
   * itself keeps the two calls separate because it threads the intermediate object through
   * query-classification gating logic between them, so it is not a consumer of this method.
   *
   * <p>F-037: {@code RemoteDocumentService#preSearchForDocIds} previously hand-built a divergent,
   * bare search request instead of calling {@link #expandPreset}/{@link
   * #toProtoPipelineConfig(PipelineConfig, boolean)} at all, silently landing on a different pipeline
   * than {@code justsearch_search}'s default and forking the evidence pack's document universe from
   * the doc-ranking surface the tool exposes (fixed tempdoc 731 I1). Single-sourcing the two literal
   * call-site arguments here (rather than each call site re-typing {@code SEARCH_MODE_HYBRID} and
   * {@code false}) prevents that drift from silently reoccurring.
   */
  static io.justsearch.ipc.PipelineConfig defaultHybridProtoConfig() {
    return toProtoPipelineConfig(
        expandPreset(SearchMode.SEARCH_MODE_HYBRID, RerankerConfig.fromEnv()), false);
  }

  /**
   * Converts an app-api {@link PipelineConfig} to the proto wire type, setting the tempdoc 598 R1
   * {@code dense_auto} marker. When {@code denseAuto} is true the Worker resolves the dense leg from
   * the embedding-compat boundary at query time (run dense iff the index is COMPATIBLE, else keyword).
   */
  static io.justsearch.ipc.PipelineConfig toProtoPipelineConfig(PipelineConfig cfg, boolean denseAuto) {
    return io.justsearch.ipc.PipelineConfig.newBuilder()
        .setSparseEnabled(cfg.sparseEnabled())
        .setDenseEnabled(cfg.denseEnabled())
        .setSpladeEnabled(cfg.spladeEnabled())
        .setFusionAlgorithm(cfg.fusionAlgorithm() != null ? cfg.fusionAlgorithm() : "none")
        .setLambdamartEnabled(cfg.lambdamartEnabled())
        .setCrossEncoderEnabled(cfg.crossEncoderEnabled())
        .setCrossEncoderWindow(cfg.crossEncoderWindow())
        .setExpansionEnabled(cfg.expansionEnabled())
        .setFreshnessEnabled(cfg.freshnessEnabled())
        .setDenseAuto(denseAuto)
        .setPipelineName(derivePipelineName(cfg))
        .build();
  }

  /** Derives a human-readable pipeline name from activation flags for log correlation (298). */
  private static String derivePipelineName(PipelineConfig cfg) {
    var parts = new ArrayList<String>();
    if (cfg.sparseEnabled()) parts.add("sparse");
    if (cfg.denseEnabled()) parts.add("dense");
    if (cfg.spladeEnabled()) parts.add("splade");
    if (parts.isEmpty()) return "none";
    String base = String.join("+", parts);
    if (cfg.crossEncoderEnabled()) base += "+ce";
    if (cfg.lambdamartEnabled()) base += "+lmart";
    return base;
  }

  public static SearchSort parseSortOrDefault(String sort) {
    if (sort == null || sort.isBlank()) {
      return SearchSort.SEARCH_SORT_RELEVANCE;
    }
    String s = sort.trim().toLowerCase(Locale.ROOT);
    return switch (s) {
      case "modified_desc", "modified-desc", "modified", "newest" -> SearchSort.SEARCH_SORT_MODIFIED_DESC;
      case "modified_asc", "modified-asc", "oldest" -> SearchSort.SEARCH_SORT_MODIFIED_ASC;
      case "size_desc", "size-desc", "largest" -> SearchSort.SEARCH_SORT_SIZE_DESC;
      case "size_asc", "size-asc", "smallest" -> SearchSort.SEARCH_SORT_SIZE_ASC;
      case "path_asc", "path-asc", "path" -> SearchSort.SEARCH_SORT_PATH_ASC;
      case "path_desc", "path-desc" -> SearchSort.SEARCH_SORT_PATH_DESC;
      default -> SearchSort.SEARCH_SORT_RELEVANCE;
    };
  }

  public static SearchQuerySyntax parseQuerySyntaxOrDefault(String querySyntax) {
    if (querySyntax == null || querySyntax.isBlank()) {
      return SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE;
    }
    String s = querySyntax.trim().toLowerCase(Locale.ROOT);
    return switch (s) {
      case "search" -> SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SEARCH;
      case "lucene", "advanced" -> SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE;
      default -> SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE;
    };
  }
}
