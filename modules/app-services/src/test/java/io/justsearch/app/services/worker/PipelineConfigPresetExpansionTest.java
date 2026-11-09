package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.ipc.SearchMode;
import io.justsearch.reranker.RerankerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link KnowledgeHttpApiAdapter#expandPreset} (256: Phase A).
 *
 * <p>Each SearchMode preset must expand to the correct PipelineConfig flags so that Phase-B
 * eligibility checks produce identical behavior to the old mode-based guards.
 */
@DisplayName("KnowledgeHttpApiAdapter expandPreset: SearchMode → PipelineConfig")
class PipelineConfigPresetExpansionTest {

  private static final RerankerConfig ENABLED =
      new RerankerConfig(
          true, null, 20, 200L, 5, 512, false, 0, 16_000, false, 0.5, false, 0.85, false);

  private static io.justsearch.ipc.PipelineConfig toProtoPipelineConfig(PipelineConfig cfg) {
    // Tempdoc 556: toProtoPipelineConfig moved to SearchPipelinePresets (package-private static);
    // call it directly instead of via reflection.
    return SearchPipelinePresets.toProtoPipelineConfig(cfg);
  }

  @Nested
  @DisplayName("TEXT preset")
  class TextPreset {

    @Test
    @DisplayName("enables sparse retrieval, disables dense and SPLADE")
    void retrieval() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertTrue(cfg.sparseEnabled());
      assertFalse(cfg.denseEnabled());
      assertFalse(cfg.spladeEnabled());
    }

    @Test
    @DisplayName("enables cross-encoder when reranker config is enabled")
    void crossEncoderEnabled() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertTrue(cfg.crossEncoderEnabled());
    }

    @Test
    @DisplayName("disables cross-encoder when reranker config is disabled")
    void crossEncoderDisabledWhenConfigDisabled() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(
              SearchMode.SEARCH_MODE_TEXT, RerankerConfig.DISABLED.enabled());
      assertFalse(cfg.crossEncoderEnabled());
    }

    @Test
    @DisplayName("sets fusion to none and lambdamart to true")
    void fusionAndLambdamart() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_TEXT, ENABLED.enabled());
      assertEquals("none", cfg.fusionAlgorithm());
      assertTrue(cfg.lambdamartEnabled());
    }
  }

  @Nested
  @DisplayName("VECTOR preset")
  class VectorPreset {

    @Test
    @DisplayName("enables dense only, cross-encoder eligible when reranker enabled")
    void retrieval() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_VECTOR, ENABLED.enabled());
      assertFalse(cfg.sparseEnabled());
      assertTrue(cfg.denseEnabled());
      assertFalse(cfg.spladeEnabled());
      assertTrue(cfg.crossEncoderEnabled(), "256-F1: all presets get CE when reranker enabled");
      assertFalse(cfg.lambdamartEnabled());
      assertEquals("none", cfg.fusionAlgorithm());
    }

    @Test
    @DisplayName("cross-encoder off when reranker globally disabled")
    void crossEncoderOffWhenDisabled() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(
              SearchMode.SEARCH_MODE_VECTOR, RerankerConfig.DISABLED.enabled());
      assertFalse(cfg.crossEncoderEnabled());
    }
  }

  @Nested
  @DisplayName("HYBRID preset")
  class HybridPreset {

    @Test
    @DisplayName("enables sparse + dense retrieval with RRF fusion")
    void retrieval() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, ENABLED.enabled());
      assertTrue(cfg.sparseEnabled());
      assertTrue(cfg.denseEnabled());
      assertFalse(cfg.spladeEnabled());
      assertEquals("rrf", cfg.fusionAlgorithm());
      assertTrue(cfg.lambdamartEnabled());
    }

    @Test
    @DisplayName("256-F1: cross-encoder enabled when reranker config is enabled")
    void crossEncoderEnabled() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, ENABLED.enabled());
      assertTrue(cfg.crossEncoderEnabled(), "256-F1: all presets get CE when reranker enabled");
    }

    @Test
    @DisplayName("cross-encoder off when reranker globally disabled")
    void crossEncoderOffWhenGloballyDisabled() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(
              SearchMode.SEARCH_MODE_HYBRID, RerankerConfig.DISABLED.enabled());
      assertFalse(cfg.crossEncoderEnabled());
    }
  }

  @Nested
  @DisplayName("SPLADE preset")
  class SpladePreset {

    @Test
    @DisplayName("enables SPLADE only, cross-encoder eligible when reranker enabled")
    void retrieval() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_SPLADE, ENABLED.enabled());
      assertFalse(cfg.sparseEnabled());
      assertFalse(cfg.denseEnabled());
      assertTrue(cfg.spladeEnabled());
      assertTrue(cfg.crossEncoderEnabled(), "256-F1: all presets get CE when reranker enabled");
      assertFalse(cfg.lambdamartEnabled());
      assertEquals("none", cfg.fusionAlgorithm());
    }

    @Test
    @DisplayName("cross-encoder off when reranker globally disabled")
    void crossEncoderOffWhenDisabled() {
      PipelineConfig cfg =
          SearchPipelinePresets.expandPreset(
              SearchMode.SEARCH_MODE_SPLADE, RerankerConfig.DISABLED.enabled());
      assertFalse(cfg.crossEncoderEnabled());
    }
  }

  @Nested
  @DisplayName("Round-trip: mode string → parseModeOrDefault → expandPreset")
  class RoundTrip {

    @Test
    @DisplayName("'hybrid' string produces HYBRID preset with dense + sparse")
    void hybridRoundTrip() {
      SearchMode mode = SearchPipelinePresets.parseModeOrDefault("hybrid");
      PipelineConfig cfg = SearchPipelinePresets.expandPreset(mode, ENABLED.enabled());
      assertTrue(cfg.sparseEnabled());
      assertTrue(cfg.denseEnabled());
      assertEquals("rrf", cfg.fusionAlgorithm());
    }

    @Test
    @DisplayName("null mode string defaults to TEXT preset")
    void nullDefaultsToText() {
      SearchMode mode = SearchPipelinePresets.parseModeOrDefault(null);
      PipelineConfig cfg = SearchPipelinePresets.expandPreset(mode, ENABLED.enabled());
      assertTrue(cfg.sparseEnabled());
      assertFalse(cfg.denseEnabled());
    }

    @Test
    @DisplayName("unknown mode string defaults to TEXT preset")
    void unknownDefaultsToText() {
      SearchMode mode = SearchPipelinePresets.parseModeOrDefault("bogus");
      PipelineConfig cfg = SearchPipelinePresets.expandPreset(mode, ENABLED.enabled());
      assertTrue(cfg.sparseEnabled());
      assertFalse(cfg.denseEnabled());
    }

    @Test
    @DisplayName("'lexical' string remains a TEXT alias for compatibility callers")
    void lexicalAliasDefaultsToText() {
      SearchMode mode = SearchPipelinePresets.parseModeOrDefault("lexical");
      PipelineConfig cfg = SearchPipelinePresets.expandPreset(mode, ENABLED.enabled());
      assertTrue(cfg.sparseEnabled());
      assertFalse(cfg.denseEnabled());
      assertTrue(cfg.expansionEnabled());
    }
  }

  @Nested
  @DisplayName("Explicit PipelineConfig wire propagation")
  class ExplicitPipelineWirePropagation {

    @Test
    @DisplayName("request-time LambdaMART disable stays disabled on the proto request")
    void lambdamartDisableStaysDisabledOnWire() {
      PipelineConfig cfg = new PipelineConfig(true, true, false, "rrf", false, true, 12, false, false);
      io.justsearch.ipc.PipelineConfig proto = toProtoPipelineConfig(cfg);
      assertFalse(proto.getLambdamartEnabled());
      assertTrue(proto.getCrossEncoderEnabled());
      assertEquals(12, proto.getCrossEncoderWindow());
    }

    @Test
    @DisplayName("request-time cross-encoder disable stays disabled on the proto request")
    void crossEncoderDisableStaysDisabledOnWire() {
      PipelineConfig cfg = new PipelineConfig(true, true, false, "rrf", true, false, 0, false, false);
      io.justsearch.ipc.PipelineConfig proto = toProtoPipelineConfig(cfg);
      assertTrue(proto.getLambdamartEnabled());
      assertFalse(proto.getCrossEncoderEnabled());
    }

    @Test
    @DisplayName("explicit pipeline preserves independent LM and CE flags on the proto request")
    void explicitPipelinePreservesIndependentLmAndCeFlags() {
      PipelineConfig cfg = new PipelineConfig(false, true, true, "rrf", true, true, 25, true, false);
      io.justsearch.ipc.PipelineConfig proto = toProtoPipelineConfig(cfg);
      assertFalse(proto.getSparseEnabled());
      assertTrue(proto.getDenseEnabled());
      assertTrue(proto.getSpladeEnabled());
      assertEquals("rrf", proto.getFusionAlgorithm());
      assertTrue(proto.getLambdamartEnabled());
      assertTrue(proto.getCrossEncoderEnabled());
      assertEquals(25, proto.getCrossEncoderWindow());
      assertTrue(proto.getExpansionEnabled());
    }
  }

  @Nested
  @DisplayName("Tempdoc 598 R1 — capability-derived AUTO preset")
  class AutoPreset {

    @Test
    @DisplayName("autoPreset: sparse on, dense NOT asserted on the record, rrf fusion, expansion ON (Fix C)")
    void autoPresetFlags() {
      PipelineConfig cfg = SearchPipelinePresets.autoPreset(ENABLED.enabled());
      assertTrue(cfg.sparseEnabled());
      // Dense is deferred to the Worker (carried as the proto dense_auto marker), so the app-api
      // record does NOT assert dense_enabled.
      assertFalse(cfg.denseEnabled());
      assertFalse(cfg.spladeEnabled());
      assertEquals("rrf", cfg.fusionAlgorithm());
      // Review Fix C: expansion ON (mirrors TEXT) so the keyword fallback keeps query expansion;
      // without it AUTO's degraded path regresses vs the prior TEXT default.
      assertTrue(cfg.expansionEnabled());
      assertTrue(cfg.crossEncoderEnabled());
    }

    @Test
    @DisplayName("toProtoPipelineConfig(cfg, true) sets dense_auto on the wire, dense_enabled stays false")
    void autoMarkerOnWire() {
      io.justsearch.ipc.PipelineConfig proto =
          SearchPipelinePresets.toProtoPipelineConfig(SearchPipelinePresets.autoPreset(ENABLED.enabled()), true);
      assertTrue(proto.getDenseAuto());
      assertFalse(proto.getDenseEnabled());
      assertTrue(proto.getSparseEnabled());
      assertEquals("rrf", proto.getFusionAlgorithm());
    }

    @Test
    @DisplayName("non-auto presets leave dense_auto false on the wire")
    void nonAutoLeavesMarkerFalse() {
      io.justsearch.ipc.PipelineConfig proto =
          toProtoPipelineConfig(SearchPipelinePresets.expandPreset(SearchMode.SEARCH_MODE_HYBRID, ENABLED.enabled()));
      assertFalse(proto.getDenseAuto());
      assertTrue(proto.getDenseEnabled());
    }
  }
}
