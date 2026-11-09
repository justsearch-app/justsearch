package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.gpl.LambdaMartTrainingStatus;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.QueryType;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchQuerySyntax;
import io.justsearch.reranker.RerankerConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for harmful search-pipeline combinations blocked in KnowledgeHttpApiAdapter.
 *
 * <p>These tests document the three blocked combinations from the Harmful Combinations Registry
 * (tempdoc 234) and verify that each guard condition correctly prevents the combination. Guards are
 * silent drops (not HTTP 400 errors) — the affected feature is skipped and the search proceeds
 * without it.
 *
 * <p>Enforcement points:
 *
 * <ul>
 *   <li>HYBRID + reranker: {@link KnowledgeSearchEngine#isRerankerEligible}
 *   <li>HYBRID + expansion: {@link KnowledgeSearchEngine#isExpansionEligible}
 *   <li>Stemming + fuzzy: co-gated on {@code SEARCH_QUERY_SYNTAX_SIMPLE} in SearchOrchestrator;
 *       fuzzy fires only as a zero-hit fallback, never concurrently with the stemmed query
 * </ul>
 */
@DisplayName("KnowledgeHttpApiAdapter harmful-combination guards")
class KnowledgeHttpApiAdapterHarmfulCombinationsTest {

  private static final RerankerConfig ENABLED =
      new RerankerConfig(
          true, null, 20, 200L, 5, 512, false, 0, 16_000, false, 0.5, false, 0.85, false);

  private static final class FakeRerankerService implements RerankerService {
    private final boolean loaded;

    private FakeRerankerService(boolean loaded) {
      this.loaded = loaded;
    }

    @Override
    public boolean isLoaded() {
      return loaded;
    }

    @Override
    public List<Integer> rerank(float[] sparseScores, float[] vectors, float[] spladeScores, int n) {
      return List.of();
    }

    @Override
    public LambdaMartTrainingStatus getTrainingStatus() {
      return new LambdaMartTrainingStatus(
          LambdaMartTrainingStatus.Phase.PENDING, null, null, null, null, null, null);
    }
  }

  @Nested
  @DisplayName("HYBRID + cross-encoder reranker")
  class HybridReranker {

    @Test
    @DisplayName(
        "256-F1: reranker is eligible for HYBRID mode when enabled and threshold met"
            + " — 2-stage reranking (LambdaMART → cross-encoder) is now the standard pattern")
    void hybridMode_rerankerEligible() {
      PipelineConfig hybrid =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, ENABLED.enabled());
      assertTrue(KnowledgeSearchEngine.isRerankerEligible(hybrid, ENABLED, 100, 0, QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("reranker is applied for TEXT mode when enabled and result count >= threshold")
    void textMode_appliesReranker() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertTrue(KnowledgeSearchEngine.isRerankerEligible(text, ENABLED, 100, 0, QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("reranker is skipped when result count is below minHitsThreshold")
    void belowThreshold_skipsReranker() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertFalse(KnowledgeSearchEngine.isRerankerEligible(text, ENABLED, 3, 0, QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("disabled reranker config always skips reranking regardless of mode")
    void disabledConfig_alwaysSkips() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(
              SearchMode.SEARCH_MODE_TEXT, RerankerConfig.DISABLED.enabled());
      assertFalse(KnowledgeSearchEngine.isRerankerEligible(text, RerankerConfig.DISABLED, 100, 0, QueryType.INFORMATIONAL));
    }
  }

  @Nested
  @DisplayName("HYBRID + LLM query expansion")
  class HybridExpansion {

    @Test
    @DisplayName(
        "expansion is silently skipped for HYBRID mode even when AI is available"
            + " — expansion addresses sparse recall gaps, not needed when dense leg is active")
    void hybridMode_silentlySkipsExpansion() {
      PipelineConfig hybrid =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, ENABLED.enabled());
      assertFalse(
          KnowledgeSearchEngine.isExpansionEligible(
              hybrid,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE,
              "test query",
              null,
              true /* aiAvailable */,
              QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("expansion fires for TEXT + SIMPLE mode with non-blank query when AI is available")
    void textSimpleMode_aiAvailable_fires() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertTrue(
          KnowledgeSearchEngine.isExpansionEligible(
              text,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE,
              "test query",
              null,
              true,
              QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName(
        "LUCENE syntax blocks expansion in TEXT mode — avoids interfering with explicit operators")
    void luceneSyntax_blocksExpansion() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertFalse(
          KnowledgeSearchEngine.isExpansionEligible(
              text,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE,
              "field:value",
              null,
              true,
              QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("blank query blocks expansion")
    void blankQuery_blocksExpansion() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertFalse(
          KnowledgeSearchEngine.isExpansionEligible(
              text,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE,
              "   ",
              null,
              true,
              QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("paginated request (cursor present) blocks expansion")
    void cursorPresent_blocksExpansion() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertFalse(
          KnowledgeSearchEngine.isExpansionEligible(
              text,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE,
              "test query",
              "cursor-token",
              true,
              QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("unavailable AI service blocks expansion regardless of mode")
    void aiUnavailable_blocksExpansion() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertFalse(
          KnowledgeSearchEngine.isExpansionEligible(
              text,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE,
              "test query",
              null,
              false /* aiAvailable */,
              QueryType.INFORMATIONAL));
    }
  }

  @Nested
  @DisplayName("Stemming + fuzzy correction — sequential, not concurrent")
  class StemmingAndFuzzy {

    /**
     * Documents the invariant: stemming (via SIMPLE text query building in SearchOrchestrator) and
     * fuzzy correction are both gated on {@code SEARCH_QUERY_SYNTAX_SIMPLE}. They are NOT applied
     * simultaneously; fuzzy fires ONLY as a zero-hit fallback after the stemmed query returns 0
     * results. LUCENE syntax disables both paths entirely.
     *
     * <p>Worker-side enforcement: {@code SearchOrchestrator} maps {@code
     * SEARCH_QUERY_SYNTAX_LUCENE} to {@code LuceneRuntimeTypes.QuerySyntax.LUCENE} (lines 285–288)
     * and guards both the stemming path and the fuzzy zero-hit retry on {@code runtimeSyntax ==
     * SIMPLE} (lines 294, 311, 339). There is no extractable static method in Head for this guard;
     * the full behavioral test lives in the {@code SearchOrchestrator} integration test suite.
     *
     * <p>Head-side proxy: {@code isExpansionEligible} is also gated on SIMPLE syntax (same
     * transport direction), confirming that LUCENE syntax disables all term-expansion features.
     */
    @Test
    @DisplayName(
        "LUCENE syntax disables query expansion in Head (proxy for stemming+fuzzy Worker gate)")
    void luceneSyntax_disablesExpansionAndStemmingFeatures() {
      PipelineConfig text =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertFalse(
          KnowledgeSearchEngine.isExpansionEligible(
              text,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_LUCENE,
              "test query",
              null,
              true /* aiAvailable */,
              QueryType.INFORMATIONAL),
          "LUCENE syntax should disable expansion — same gate blocks stemming+fuzzy in Worker");
    }

    @Test
    @DisplayName(
        "HYBRID preset has expansionEnabled=false"
            + " — expansion blocked (same semantic as stemming+fuzzy Worker gate)")
    void hybridPreset_expansionDisabled_blocksExpansionAndStemmingFeatures() {
      PipelineConfig hybrid =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, ENABLED.enabled());
      assertFalse(
          KnowledgeSearchEngine.isExpansionEligible(
              hybrid,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE,
              "test query",
              null,
              true /* aiAvailable */,
              QueryType.INFORMATIONAL),
          "HYBRID mode should disable expansion"
              + " — same mode check disables stemming+fuzzy in Worker");
    }
  }

  @Nested
  @DisplayName("Direct PipelineConfig combinations (beyond presets)")
  class DirectPipelineConfig {

    @Test
    @DisplayName("custom config with expansion enabled is expansion-eligible")
    void expansionEnabled_expansionEligible() {
      PipelineConfig cfg = new PipelineConfig(true, false, false, "none", true, false, 0, true, false);
      assertTrue(
          KnowledgeSearchEngine.isExpansionEligible(
              cfg,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE,
              "query",
              null,
              true,
              QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("custom config with expansion disabled blocks expansion")
    void expansionDisabled_expansionBlocked() {
      PipelineConfig cfg = new PipelineConfig(true, true, false, "rrf", true, false, 0, false, false);
      assertFalse(
          KnowledgeSearchEngine.isExpansionEligible(
              cfg,
              SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SIMPLE,
              "query",
              null,
              true,
              QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("custom config with cross-encoder enabled is reranker-eligible")
    void crossEncoderEnabled_rerankerEligible() {
      PipelineConfig cfg = new PipelineConfig(true, true, false, "rrf", true, true, 0, false, false);
      assertTrue(KnowledgeSearchEngine.isRerankerEligible(cfg, ENABLED, 100, 0, QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("custom config with cross-encoder disabled is not reranker-eligible")
    void crossEncoderDisabled_notRerankerEligible() {
      PipelineConfig cfg = new PipelineConfig(true, false, false, "none", true, false, 0, true, false);
      assertFalse(KnowledgeSearchEngine.isRerankerEligible(cfg, ENABLED, 100, 0, QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName(
        "258-B1: cross-encoder is auto-disabled when avg doc length exceeds threshold")
    void longDocs_autoDisablesCrossEncoder() {
      PipelineConfig hybrid =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, ENABLED.enabled());
      // 25000 chars (~6K tokens) exceeds the 16000 char threshold
      assertFalse(KnowledgeSearchEngine.isRerankerEligible(hybrid, ENABLED, 100, 25_000, QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName(
        "258-B1: cross-encoder remains eligible when avg doc length is below threshold")
    void shortDocs_crossEncoderStaysEligible() {
      PipelineConfig hybrid =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, ENABLED.enabled());
      // 5000 chars (~1.2K tokens) is below the 16000 char threshold
      assertTrue(KnowledgeSearchEngine.isRerankerEligible(hybrid, ENABLED, 100, 5_000, QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName(
        "258-B1: document-length gate is disabled when maxAvgDocLengthChars is 0")
    void gateDisabled_crossEncoderAlwaysEligible() {
      RerankerConfig noGate =
          new RerankerConfig(
              true, null, 20, 200L, 5, 512, false, 0, 0, false, 0.5, false, 0.85, false);
      PipelineConfig hybrid =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, noGate.enabled());
      // Even very long docs should pass when gate is disabled (maxAvgDocLengthChars=0)
      assertTrue(KnowledgeSearchEngine.isRerankerEligible(hybrid, noGate, 100, 100_000, QueryType.INFORMATIONAL));
    }

    @Test
    @DisplayName("request-time LambdaMART disable is authoritative even when model is loaded")
    void lambdamartDisabled_notEligible() {
      PipelineConfig cfg = new PipelineConfig(true, true, false, "rrf", false, true, 0, false, false);
      assertFalse(
          KnowledgeSearchEngine.isLambdaMartEligible(
              cfg, new FakeRerankerService(true), 10));
    }

    @Test
    @DisplayName("request-time LambdaMART enable requires loaded model and non-empty results")
    void lambdamartEnabled_loadedModelAndResults_required() {
      PipelineConfig cfg = new PipelineConfig(true, true, false, "rrf", true, true, 0, false, false);
      assertTrue(
          KnowledgeSearchEngine.isLambdaMartEligible(
              cfg, new FakeRerankerService(true), 10));
      assertFalse(
          KnowledgeSearchEngine.isLambdaMartEligible(
              cfg, new FakeRerankerService(false), 10));
      assertFalse(
          KnowledgeSearchEngine.isLambdaMartEligible(
              cfg, new FakeRerankerService(true), 0));
    }
  }
}
