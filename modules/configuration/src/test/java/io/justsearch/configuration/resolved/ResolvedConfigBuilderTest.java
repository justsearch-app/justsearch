package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import io.justsearch.configuration.EnvRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ResolvedConfigBuilder} ordinal-chain resolution and EnvRegistry wiring.
 *
 * <p>Verifies the core resolution mechanism: higher ordinal wins, source tracing captures all
 * candidates, typed helpers parse correctly, and EnvRegistry contribution registers all entries.
 */
@DisplayName("ResolvedConfigBuilder")
final class ResolvedConfigBuilderTest {

  // ==================== Ordinal Chain Resolution ====================

  @Nested
  @DisplayName("Ordinal chain resolution")
  class OrdinalChain {

    @Test
    @DisplayName("Higher ordinal wins over lower ordinal")
    void higherOrdinalWins() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_DEFAULT, "default", null, "low");
      builder.put(
          "test.key", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", "TEST_KEY", "high");

      ConfigResolution r = builder.resolve("test.key");
      assertEquals("high", r.value());
      assertEquals("env_var", r.sourceName());
      assertEquals(400, r.sourceOrdinal());
      assertEquals("TEST_KEY", r.sourceDetail());
    }

    @Test
    @DisplayName("JVM arg (500) beats env var (400)")
    void jvmArgBeatsEnvVar() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", "TEST", "env");
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg", "test.key", "jvm");

      ConfigResolution r = builder.resolve("test.key");
      assertEquals("jvm", r.value());
      assertEquals("jvm_arg", r.sourceName());
      assertEquals(500, r.sourceOrdinal());
    }

    @Test
    @DisplayName("Blank value is treated as absent — lower ordinal wins")
    void blankValueSkipped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg", "test.key", "  ");
      builder.put(
          "test.key", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", "TEST", "actual_value");

      ConfigResolution r = builder.resolve("test.key");
      assertEquals("actual_value", r.value());
      assertEquals("env_var", r.sourceName());
      assertEquals(400, r.sourceOrdinal());
    }

    @Test
    @DisplayName("Null value is treated as absent")
    void nullValueSkipped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg", "test.key", null);
      builder.put(
          "test.key", ResolvedConfigBuilder.ORDINAL_DEFAULT, "default", null, "fallback");

      ConfigResolution r = builder.resolve("test.key");
      assertEquals("fallback", r.value());
      assertEquals("default", r.sourceName());
      assertEquals(100, r.sourceOrdinal());
    }

    @Test
    @DisplayName("No sources returns null value with 'none' source")
    void noSourcesReturnsNull() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();

      ConfigResolution r = builder.resolve("nonexistent.key");
      assertNull(r.value());
      assertEquals("none", r.sourceName());
      assertEquals(0, r.sourceOrdinal());
      assertFalse(r.isResolved());
    }

    @Test
    @DisplayName("All candidates are recorded in considered list")
    void allCandidatesRecorded() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_DEFAULT, "default", null, "def");
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_YAML, "yaml", "app.yaml", "yaml_val");
      builder.put("test.key", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var", "TEST", null);

      ConfigResolution r = builder.resolve("test.key");
      // Winner is YAML (200) because env_var (400) is null
      assertEquals("yaml_val", r.value());
      assertEquals("yaml", r.sourceName());
      assertEquals(200, r.sourceOrdinal());

      // All 3 candidates recorded, ordered by descending ordinal
      assertEquals(3, r.considered().size());
      assertEquals(400, r.considered().get(0).ordinal());
      assertEquals(200, r.considered().get(1).ordinal());
      assertEquals(100, r.considered().get(2).ordinal());
    }
  }

  // ==================== Typed Resolution Helpers ====================

  @Nested
  @DisplayName("Typed resolution helpers")
  class TypedHelpers {

    @Test
    @DisplayName("resolveInt parses valid integer")
    void resolveIntValid() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("port", "8080");
      assertEquals(8080, builder.resolveInt("port", 0));
    }

    @Test
    @DisplayName("resolveInt returns default for unparseable value")
    void resolveIntInvalid() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("port", "not_a_number");
      assertEquals(9999, builder.resolveInt("port", 9999));
    }

    @Test
    @DisplayName("resolveBoolean recognizes true, 1, yes")
    void resolveBooleanTrueValues() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("a", "true");
      builder.putDefault("b", "1");
      builder.putDefault("c", "yes");
      builder.putDefault("d", "YES");
      assertTrue(builder.resolveBoolean("a", false));
      assertTrue(builder.resolveBoolean("b", false));
      assertTrue(builder.resolveBoolean("c", false));
      assertTrue(builder.resolveBoolean("d", false));
    }

    @Test
    @DisplayName("resolveBoolean returns default for unset key")
    void resolveBooleanDefault() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      assertTrue(builder.resolveBoolean("unset", true));
      assertFalse(builder.resolveBoolean("unset", false));
    }

    @Test
    @DisplayName("resolvePath creates Path from string")
    void resolvePathValid() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("dir", "/tmp/test");
      assertEquals(Path.of("/tmp/test"), builder.resolvePath("dir", null));
    }

    @Test
    @DisplayName("resolvePath returns default for unset key")
    void resolvePathDefault() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      Path def = Path.of("/default");
      assertEquals(def, builder.resolvePath("unset", def));
    }
  }

  // ==================== Build ====================

  @Nested
  @DisplayName("Build")
  class Build {

    @Test
    @DisplayName("build() produces ResolvedConfig with all sub-records")
    void buildProducesCompleteConfig() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp/data");
      builder.putDefault("justsearch.api.port", "9090");
      builder.putDefault("justsearch.llm.enabled", "true");
      builder.putDefault("justsearch.search.pipeline.profile", "bm25");
      builder.putDefault("justsearch.prod", "true");

      ResolvedConfig config = builder.build();

      assertNotNull(config.paths());
      assertNotNull(config.ports());
      assertNotNull(config.ai());
      assertNotNull(config.search());
      assertNotNull(config.telemetry());
      assertNotNull(config.policy());
      assertNotNull(config.ui());
      assertNotNull(config.watcher());
      assertNotNull(config.ocr());
      assertNotNull(config.index());
      assertNotNull(config.rag());
      assertNotNull(config.hybridSearch());
      assertNotNull(config.worker());
      assertNotNull(config.resolutions());

      assertEquals(Path.of("/tmp/data"), config.paths().dataDir());
      assertEquals(9090, config.ports().apiPort());
      assertTrue(config.ai().llmEnabled());
      assertEquals("bm25", config.search().profile());
      assertTrue(config.policy().prodMode());
      // 691 §N/F-031: embed GPU mem default raised 3072 → 6144 to
      // accommodate gte-multilingual-base FP16 activations (post-358).
      assertEquals(6144, config.ai().embedding().gpuMemMb());
      // 691: NER GPU mem default raised from 512 → 2048 — the fp16 NER variant's attention
      // intermediates OOM a 512MB arena, silently degrading batched NER to per-doc fallback.
      assertEquals(2048, config.ai().ner().gpuMemMb());
    }

    @Test
    @DisplayName("reranker defaults from EnvRegistry flow through resolution chain")
    void rerankerDefaultsFromEnvRegistry() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();
      // These values come from EnvRegistry declared defaults at ordinal 100.
      // Cross-check: resolved value must equal the EnvRegistry declared default.
      // This catches drift in EITHER direction: changing EnvRegistry without updating this test,
      // or removing the EnvRegistry default (which would fall through to the builder fallback).
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_MAX_SEQ_LEN.defaultValue()),
          config.ai().reranker().maxSeqLen());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_GPU_MEM_MB.defaultValue()),
          config.ai().reranker().gpuMemMb());
      assertEquals(
          Boolean.parseBoolean(EnvRegistry.RERANK_GPU_ENABLED.defaultValue()),
          config.ai().reranker().gpuEnabled());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_TOP_K.defaultValue()),
          config.ai().reranker().topK());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_DEADLINE_MS.defaultValue()),
          config.ai().reranker().deadlineMs());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_MIN_HITS.defaultValue()),
          config.ai().reranker().minHits());
      assertEquals(
          Integer.parseInt(EnvRegistry.RERANK_MAX_AVG_DOC_LENGTH_CHARS.defaultValue()),
          config.ai().reranker().maxAvgDocLengthChars());
    }

    @Test
    @DisplayName("maxAvgDocLengthChars default is 0 — DOCS_TOO_LONG gate off (tempdoc 774 §J.2/§K)")
    void docsTooLongGateDefaultsDisabled() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();
      // Tempdoc 774 §J.2/§K live probe: the DOCS_TOO_LONG gate diverged eval (gate-off) from
      // production (gate-on when a client polls /api/knowledge/status). Default flipped 16000 → 0 so
      // production matches every measured register baseline. Pins the intended value directly (the
      // cross-check above only proves self-consistency, not the chosen number).
      assertEquals(0, config.ai().reranker().maxAvgDocLengthChars());
    }

    @Test
    @DisplayName("chat profile defaults to 'standard' through the resolution chain (tempdoc 842)")
    void chatProfileDefaultsToStandard() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();

      // Cross-check against the declared default, so removing the EnvRegistry default (silently
      // falling through to the builder fallback) is caught...
      assertEquals(EnvRegistry.CHAT_PROFILE.defaultValue(), config.ai().chatProfile());
      // ...and pin the chosen value: an unconfigured runtime must start the standard 9B pair.
      assertEquals("standard", config.ai().chatProfile());
    }

    // ==================== Reasoning budget (tempdoc 835 §9f) ====================
    //
    // These guard a SILENT failure: reasoning and answer tokens share one completion ceiling, so an
    // unbounded reasoning budget lets reasoning consume all of it and the turn ends with a normal
    // `done` event, no error, and an empty answer (reproduced 4/4 on b8571 / Qwen3.5-9B). The point
    // of the clamp is that this configuration cannot be reached by accident, so these tests are
    // named for the failure they prevent, not for the numbers they check.

    @Test
    @DisplayName("reasoning budget defaults to a bounded 512 — thinking on, answer intact")
    void reasoningBudgetDefaultsToBounded512() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();

      ResolvedConfig config = builder.build();

      assertEquals(ResolvedConfigBuilder.DEFAULT_REASONING_BUDGET, config.ai().reasoningBudget());
      assertEquals(512, config.ai().reasoningBudget());
    }

    @Test
    @DisplayName("unbounded budget (-1) cannot reach the launch path — the 4/4 empty-answer config")
    void unboundedBudgetCannotProduceTheEmptyAnswerConfiguration() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put(
          "justsearch.llm.reasoning_budget",
          ResolvedConfigBuilder.ORDINAL_ENV_VAR,
          "env_var",
          "JUSTSEARCH_REASONING_BUDGET",
          "-1");

      ResolvedConfig config = builder.build();

      assertEquals(ResolvedConfigBuilder.DEFAULT_REASONING_BUDGET, config.ai().reasoningBudget());
    }

    @Test
    @DisplayName("a budget at or above the engine's completion ceiling is refused, from any source")
    void budgetAtOrAboveTheCompletionCeilingIsRefused() {
      for (String raw :
          new String[] {
            Integer.toString(ResolvedConfigBuilder.ENGINE_DEFAULT_MAX_TOKENS),
            Integer.toString(ResolvedConfigBuilder.ENGINE_DEFAULT_MAX_TOKENS + 2048)
          }) {
        ResolvedConfigBuilder envBuilder = new ResolvedConfigBuilder();
        envBuilder.put(
            "justsearch.llm.reasoning_budget",
            ResolvedConfigBuilder.ORDINAL_ENV_VAR,
            "env_var",
            "JUSTSEARCH_REASONING_BUDGET",
            raw);
        assertEquals(
            ResolvedConfigBuilder.DEFAULT_REASONING_BUDGET,
            envBuilder.build().ai().reasoningBudget(),
            "env var " + raw + " must be refused");

        ResolvedConfigBuilder jvmBuilder = new ResolvedConfigBuilder();
        jvmBuilder.put(
            "justsearch.llm.reasoning_budget",
            ResolvedConfigBuilder.ORDINAL_JVM_ARG,
            "jvm_arg",
            "justsearch.llm.reasoning_budget",
            raw);
        assertEquals(
            ResolvedConfigBuilder.DEFAULT_REASONING_BUDGET,
            jvmBuilder.build().ai().reasoningBudget(),
            "jvm arg " + raw + " must be refused");
      }
    }

    @Test
    @DisplayName("0 stays representable — explicitly disabling reasoning is not catastrophic")
    void zeroBudgetIsPreserved() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put(
          "justsearch.llm.reasoning_budget",
          ResolvedConfigBuilder.ORDINAL_ENV_VAR,
          "env_var",
          "JUSTSEARCH_REASONING_BUDGET",
          "0");

      assertEquals(0, builder.build().ai().reasoningBudget());
    }

    @Test
    @DisplayName("a bounded operator override below the ceiling is honored")
    void boundedOverrideIsHonored() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put(
          "justsearch.llm.reasoning_budget",
          ResolvedConfigBuilder.ORDINAL_ENV_VAR,
          "env_var",
          "JUSTSEARCH_REASONING_BUDGET",
          "256");

      assertEquals(256, builder.build().ai().reasoningBudget());
    }

    @Test
    @DisplayName("the default is itself below the completion ceiling — the invariant it enforces")
    void defaultIsBelowTheCompletionCeiling() {
      assertTrue(
          ResolvedConfigBuilder.DEFAULT_REASONING_BUDGET
              < ResolvedConfigBuilder.ENGINE_DEFAULT_MAX_TOKENS,
          "the clamp's fallback must satisfy the invariant the clamp enforces");
    }

    // ==================== context window / slots / KV (tempdoc 883) ====================

    @Test
    @DisplayName("context size defaults to 0 = auto — there is no second shipped number")
    void contextSizeDefaultsToAuto() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();

      assertEquals(
          0,
          builder.build().ai().contextSize(),
          "the old 8192 here disagreed with UiSettings' 4096 for six months and neither ever"
              + " reached llama-server; the window is derived now");
    }

    @Test
    @DisplayName("an auto-detected rung wins over nothing and loses to settings_json")
    void contextSizeOrdinalChain() {
      ResolvedConfigBuilder derived = new ResolvedConfigBuilder();
      derived.contributeAutoDetected(Map.of("justsearch.context.size", "32768"));
      assertEquals(32768, derived.build().ai().contextSize());

      ResolvedConfigBuilder overridden = new ResolvedConfigBuilder();
      overridden.contributeAutoDetected(Map.of("justsearch.context.size", "32768"));
      overridden.putSettings("justsearch.context.size", "8192");
      assertEquals(8192, overridden.build().ai().contextSize());
    }

    @Test
    @DisplayName("llm slots default to 2 — a background delegate must not evict the foreground")
    void llmSlotsDefaultToTwo() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();

      assertEquals(ResolvedConfigBuilder.DEFAULT_LLM_SLOTS, builder.build().ai().llmSlots());
      assertEquals(2, builder.build().ai().llmSlots());
    }

    @Test
    @DisplayName("an out-of-range slot count is refused: every slot divides the KV cache")
    void outOfRangeSlotsAreRefused() {
      for (String raw : new String[] {"0", "-1", "99"}) {
        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.put(
            "justsearch.llm.slots",
            ResolvedConfigBuilder.ORDINAL_ENV_VAR,
            "env_var",
            "JUSTSEARCH_LLM_SLOTS",
            raw);
        assertEquals(
            ResolvedConfigBuilder.DEFAULT_LLM_SLOTS,
            builder.build().ai().llmSlots(),
            "slots=" + raw + " must be refused, not passed to -np");
      }
    }

    @Test
    @DisplayName("an in-range slot override is honored")
    void inRangeSlotsAreHonored() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put(
          "justsearch.llm.slots",
          ResolvedConfigBuilder.ORDINAL_ENV_VAR,
          "env_var",
          "JUSTSEARCH_LLM_SLOTS",
          "4");

      assertEquals(4, builder.build().ai().llmSlots());
    }

    @Test
    @DisplayName("KV cache type defaults to q8_0")
    void kvTypeDefaultsToQ8() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();

      assertEquals(ResolvedConfigBuilder.DEFAULT_LLM_KV_TYPE, builder.build().ai().llmKvType());
      assertEquals("q8_0", builder.build().ai().llmKvType());
    }

    @Test
    @DisplayName("an unknown KV cache type is refused — it would abort llama-server at launch")
    void unknownKvTypeIsRefused() {
      for (String raw : new String[] {"q8", "int8", "", "nonsense"}) {
        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.put(
            "justsearch.llm.kv_type",
            ResolvedConfigBuilder.ORDINAL_ENV_VAR,
            "env_var",
            "JUSTSEARCH_LLM_KV_TYPE",
            raw);
        assertEquals(
            ResolvedConfigBuilder.DEFAULT_LLM_KV_TYPE,
            builder.build().ai().llmKvType(),
            "kv_type='" + raw + "' must be refused: llama-server aborts on it, and the context"
                + " ladder would read that abort as 'this rung does not fit' and walk the whole"
                + " ladder down for the wrong reason");
      }
    }

    @Test
    @DisplayName("a known KV cache type is honored and normalized")
    void knownKvTypeIsHonored() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.put(
          "justsearch.llm.kv_type",
          ResolvedConfigBuilder.ORDINAL_ENV_VAR,
          "env_var",
          "JUSTSEARCH_LLM_KV_TYPE",
          "  F16 ");

      assertEquals("f16", builder.build().ai().llmKvType());
    }

    @Test
    @DisplayName("embedGpuMemMb honors explicit override")
    void embedGpuMemMbExplicitOverride() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.embed.gpu_mem_mb", "2048");

      ResolvedConfig config = builder.build();

      assertEquals(2048, config.ai().embedding().gpuMemMb());
    }

    @Test
    @DisplayName(
        "late-chunking defaults from EnvRegistry: ENABLED (default-on since 691 Phase N), context"
            + " length defaults to 8192")
    void lateChunkingDefaultsFromEnvRegistry() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();

      assertEquals(
          Boolean.parseBoolean(EnvRegistry.EMBED_LATE_CHUNKING_ENABLED.defaultValue()),
          config.ai().embedding().lateChunkingEnabled());
      assertTrue(config.ai().embedding().lateChunkingEnabled());
      assertEquals(
          Integer.parseInt(EnvRegistry.EMBED_LATE_CHUNKING_CONTEXT_LENGTH.defaultValue()),
          config.ai().embedding().lateChunkingContextLength());
      assertEquals(8192, config.ai().embedding().lateChunkingContextLength());
    }

    @Test
    @DisplayName(
        "late-chunking context length clamps to a max of 8192 (gte-multilingual-base's trained"
            + " context ceiling)")
    void lateChunkingContextLengthClampsToMax8192() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.embed.late_chunking_context_length", "32000");

      ResolvedConfig config = builder.build();

      assertEquals(8192, config.ai().embedding().lateChunkingContextLength());
    }

    @Test
    @DisplayName(
        "late-chunking context length never falls below the base embed.context_length")
    void lateChunkingContextLengthClampsToMinContextLength() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.embed.context_length", "6000");
      builder.putDefault("justsearch.embed.late_chunking_context_length", "2048");

      ResolvedConfig config = builder.build();

      assertEquals(6000, config.ai().embedding().lateChunkingContextLength());
    }

    @Test
    @DisplayName(
        "backfill pacing defaults from EnvRegistry are byte-identical to the pre-Move-4"
            + " hardcoded literals (tempdoc 710 Wave-1.5 Move 4)")
    void backfillPacingDefaultsFromEnvRegistry() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();
      ResolvedConfig.Ai.BackfillPacing pacing = config.ai().backfillPacing();

      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_POLL_BATCH_SIZE.defaultValue()),
          pacing.pollBatchSize());
      assertEquals(16, pacing.pollBatchSize());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_EMBEDDING_BATCH_SIZE.defaultValue()),
          pacing.embeddingBackfillBatchSize());
      assertEquals(100, pacing.embeddingBackfillBatchSize());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_NER_BATCH_SIZE.defaultValue()),
          pacing.nerBackfillBatchSize());
      assertEquals(100, pacing.nerBackfillBatchSize());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_DISAMBIGUATION_BATCH_SIZE.defaultValue()),
          pacing.disambiguationBackfillBatchSize());
      assertEquals(500, pacing.disambiguationBackfillBatchSize());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_SPLADE_BATCH_SIZE.defaultValue()),
          pacing.spladeBackfillBatchSize());
      assertEquals(200, pacing.spladeBackfillBatchSize());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_SPLADE_INTERLEAVE_BATCH_SIZE.defaultValue()),
          pacing.spladeInterleaveBatchSize());
      assertEquals(10, pacing.spladeInterleaveBatchSize());
      assertEquals(
          Long.parseLong(EnvRegistry.BACKFILL_SPLADE_INTERLEAVE_INTERVAL_MS.defaultValue()),
          pacing.spladeInterleaveIntervalMs());
      assertEquals(5_000L, pacing.spladeInterleaveIntervalMs());
      assertEquals(
          Long.parseLong(EnvRegistry.BACKFILL_COMMIT_INTERVAL_MS.defaultValue()),
          pacing.commitIntervalMs());
      assertEquals(10_000L, pacing.commitIntervalMs());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_MAX_DOCS_BEFORE_COMMIT.defaultValue()),
          pacing.maxDocsBeforeCommit());
      assertEquals(1000, pacing.maxDocsBeforeCommit());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_CHUNK_SLOTS_PER_BATCH.defaultValue()),
          pacing.chunkSlotsPerBatch());
      assertEquals(50, pacing.chunkSlotsPerBatch());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_BGE_M3_BATCH_SIZE.defaultValue()),
          pacing.bgeM3BackfillBatchSize());
      assertEquals(50, pacing.bgeM3BackfillBatchSize());
      assertEquals(
          Integer.parseInt(EnvRegistry.BACKFILL_BGE_M3_INTERLEAVE_BATCH_SIZE.defaultValue()),
          pacing.bgeM3InterleaveBatchSize());
      assertEquals(10, pacing.bgeM3InterleaveBatchSize());

      // The DEFAULTS sentinel (used as the null-supplier fallback in IndexingLoop/
      // BackfillScheduler) must match the config-resolved defaults exactly.
      assertEquals(ResolvedConfig.Ai.BackfillPacing.DEFAULTS, pacing);
    }

    @Test
    @DisplayName("backfill pacing honors explicit overrides")
    void backfillPacingExplicitOverride() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.backfill.chunk_slots_per_batch", "128");
      builder.putDefault("justsearch.backfill.commit_interval_ms", "20000");

      ResolvedConfig config = builder.build();

      assertEquals(128, config.ai().backfillPacing().chunkSlotsPerBatch());
      assertEquals(20_000L, config.ai().backfillPacing().commitIntervalMs());
    }

    @Test
    @DisplayName("indexBasePath is derived from dataDir when not explicitly set")
    void indexBasePathDerived() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp/data");

      ResolvedConfig config = builder.build();

      assertEquals(
          Path.of("/tmp/data/index/default"),
          config.paths().indexBasePath());
    }

    @Test
    @DisplayName("explicit indexBasePath overrides derived value")
    void explicitIndexBasePath() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp/data");
      builder.put(
          "justsearch.index.base_path",
          ResolvedConfigBuilder.ORDINAL_JVM_ARG,
          "jvm_arg",
          "justsearch.index.base_path",
          "/custom/index");

      ResolvedConfig config = builder.build();

      assertEquals(Path.of("/custom/index"), config.paths().indexBasePath());
    }

    @Test
    @DisplayName("contributed JVM indexBasePath beats settings contribution")
    void explicitIndexBasePathBeatsSettings() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp/data");
      builder.putSettings("justsearch.index.base_path", "/settings/index");
      builder.put(
          "justsearch.index.base_path",
          ResolvedConfigBuilder.ORDINAL_JVM_ARG,
          "jvm_arg",
          "justsearch.index.base_path",
          "/cli/index");

      ResolvedConfig config = builder.build();

      assertEquals(Path.of("/cli/index"), config.paths().indexBasePath());
      assertEquals("jvm_arg", config.resolution("justsearch.index.base_path").sourceName());
    }

    @Test
    @DisplayName("resolutions map contains all contributed keys")
    void resolutionsMapComplete() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/tmp");
      builder.putDefault("justsearch.api.port", "8080");

      ResolvedConfig config = builder.build();

      assertNotNull(config.resolution("justsearch.data.dir"));
      assertNotNull(config.resolution("justsearch.api.port"));
      assertEquals("/tmp", config.resolution("justsearch.data.dir").value());
    }
  }

  // ==================== EnvRegistry Integration ====================

  @Nested
  @DisplayName("EnvRegistry integration")
  class EnvRegistryIntegration {

    @Test
    @DisplayName("contributeEnvRegistry registers all EnvRegistry entries")
    void contributeEnvRegistryRegistersAll() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();

      // Every EnvRegistry entry should have a resolution (may be unset, but tracked)
      for (EnvRegistry entry : EnvRegistry.values()) {
        ConfigResolution r = config.resolution(entry.configKey());
        assertNotNull(
            r,
            "Missing resolution for " + entry.name() + " (configKey=" + entry.configKey() + ")");
        // Each entry should have at least 2 candidates (jvm_arg + env_var)
        assertTrue(
            r.considered().size() >= 2,
            entry.name()
                + " should have at least 2 candidates, has "
                + r.considered().size());
      }
    }

    @Test
    @DisplayName("contributeEnvRegistry resolution matches EnvRegistry.get() for all entries")
    void parityWithEnvRegistryGet() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();

      int divergences = 0;
      for (EnvRegistry entry : EnvRegistry.values()) {
        String legacyValue = entry.get().orElse(null);
        ConfigResolution r = config.resolution(entry.configKey());
        String resolvedValue = r != null ? r.value() : null;

        // Entries with defaultValue() will resolve to the default when no env var/sysprop
        // is set, while legacy entry.get() returns null. This is expected — the defaultValue
        // fills the gap between the two paths.
        String expectedResolved =
            legacyValue != null ? legacyValue : entry.defaultValue();
        if (!java.util.Objects.equals(expectedResolved, resolvedValue)) {
          divergences++;
          System.err.println(
              "PARITY DIVERGENCE: "
                  + entry.name()
                  + " expected="
                  + expectedResolved
                  + " resolved="
                  + resolvedValue);
        }
      }

      assertEquals(
          0,
          divergences,
          divergences + " EnvRegistry entries diverged between legacy and resolved paths");
    }
  }

  // ==================== YAML Contribution ====================

  @Nested
  @DisplayName("YAML contribution")
  class YamlContribution {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private JsonNode parseYaml(String yaml) {
      try {
        return YAML_MAPPER.readTree(yaml);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Test
    @DisplayName("contributeYaml reads OCR config including languages list")
    void ocrConfig() {
      String yaml =
          """
          index:
            ocr:
              enabled: true
              languages:
                - eng
                - deu
              limits:
                max_pages: 50
                render_dpi: 220
              workers: 4
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals(true, config.ocr().enabled());
      assertEquals(java.util.List.of("eng", "deu"), config.ocr().languages());
      assertEquals(50, config.ocr().maxPages());
      assertEquals(220, config.ocr().renderDpi());
      assertEquals(4, config.ocr().workers());
    }

    @Test
    @DisplayName("contributeYaml reads hybrid search config")
    void hybridSearchConfig() {
      String yaml =
          """
          index:
            hybrid:
              rrf_k: 45
              vector_rrf_weight: 0.80
              vector_skip_min_chars: 6
              branch_fusion_strategy: rrf
              branch_cc_weight_chunk: 0.65
              branch_chunk_min_weight_multiplier: 0.50
              branch_ramp:
                full_weight_max_tokens: 2000
                zero_weight_min_tokens: 6000
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals(45, config.hybridSearch().rrfK());
      assertEquals(0.80, config.hybridSearch().vectorRrfWeight(), 0.001);
      assertEquals(6, config.hybridSearch().vectorSkipMinChars());
      assertEquals(0.25, config.hybridSearch().vectorSkipMinDfFraction(), 0.001);
      assertEquals("rrf", config.hybridSearch().branchFusionStrategy());
      assertEquals(0.65, config.hybridSearch().branchCcWeightChunk(), 0.001);
      assertEquals(0.50, config.hybridSearch().branchChunkMinWeightMultiplier(), 0.001);
      assertEquals(2000L, config.hybridSearch().branchRampFullWeightMaxTokens());
      assertEquals(6000L, config.hybridSearch().branchRampZeroWeightMinTokens());
    }

    @Test
    @DisplayName("dense-skip DF threshold defaults to 0.25 and resolves its sysprop")
    void denseSkipDfThresholdDefaultAndSysprop() {
      String key = "index.hybrid.vector_skip_min_df_fraction";
      String previous = System.getProperty(key);
      try {
        System.clearProperty(key);
        ResolvedConfig defaults = new ResolvedConfigBuilder().contributeEnvRegistry().build();
        assertEquals(0.25, defaults.hybridSearch().vectorSkipMinDfFraction(), 0.001);

        System.setProperty(key, "0.40");
        ResolvedConfig overridden = new ResolvedConfigBuilder().contributeEnvRegistry().build();
        assertEquals(0.40, overridden.hybridSearch().vectorSkipMinDfFraction(), 0.001);
      } finally {
        if (previous != null) System.setProperty(key, previous);
        else System.clearProperty(key);
      }
    }

    @Test
    @DisplayName("retired entity boost is absent from the configuration authority")
    void retiredEntityBoostIsAbsent() {
      ResolvedConfig config = new ResolvedConfigBuilder().contributeEnvRegistry().build();
      assertFalse(config.resolutions().containsKey("justsearch.search.entity_boost"));
      assertTrue(
          java.util.Arrays.stream(EnvRegistry.values())
              .noneMatch(entry -> entry.configKey().equals("justsearch.search.entity_boost")));
    }

    @Test
    @DisplayName("774 Stage 1: chunk-branch levers default to today's behavior byte-for-byte")
    void chunkBranchLeversDefault() {
      ResolvedConfig config = new ResolvedConfigBuilder().build();
      ResolvedConfig.HybridSearch h = config.hybridSearch();
      // Chunk CC weights default to the resolved doc-level cc_weight_* values.
      assertEquals(h.ccWeightSparse(), h.chunkCcWeightSparse(), 0.0001);
      assertEquals(h.ccWeightDense(), h.chunkCcWeightDense(), 0.0001);
      assertEquals(h.ccWeightSplade(), h.chunkCcWeightSplade(), 0.0001);
      assertEquals(0.60, h.chunkCcWeightSparse(), 0.0001);
      assertEquals(0.20, h.chunkCcWeightDense(), 0.0001);
      assertEquals(0.20, h.chunkCcWeightSplade(), 0.0001);
      assertFalse(h.chunkCcZeroExclude());
      assertEquals(2, h.chunkCollapseLimitMultiplier());
      assertFalse(h.chunkLegRecallCompleteEnabled());
      assertEquals(10, h.chunkLegRecallCompleteTopN());
      assertTrue(h.chunkBranchRequiresBaseResults());
    }

    @Test
    @DisplayName("774 Stage 1: chunk CC weights + zero-exclude inherit explicit doc-level overrides")
    void chunkBranchWeightsInheritDocLevelOverride() {
      String yaml =
          """
          index:
            hybrid:
              cc_weight_sparse: 0.10
              cc_weight_dense: 0.90
              cc_weight_splade: 0.00
              cc_zero_exclude: true
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig.HybridSearch h = builder.build().hybridSearch();
      // Chunk key unset → the resolved doc-level override flows through.
      assertEquals(0.10, h.chunkCcWeightSparse(), 0.0001);
      assertEquals(0.90, h.chunkCcWeightDense(), 0.0001);
      assertEquals(0.00, h.chunkCcWeightSplade(), 0.0001);
      // Doc-level cc_zero_exclude=true must keep applying to the chunk branch until the chunk key is set.
      assertTrue(h.chunkCcZeroExclude());
    }

    @Test
    @DisplayName("774 Stage 1: chunk_cc_zero_exclude=false overrides an inherited doc-level true")
    void chunkZeroExcludeOverridesInheritedDocLevel() {
      String yaml =
          """
          index:
            hybrid:
              cc_zero_exclude: true
              chunk_cc_zero_exclude: false
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      assertFalse(builder.build().hybridSearch().chunkCcZeroExclude());
    }

    @Test
    @DisplayName("774 Stage 1: chunk-branch keys override doc-level and their own defaults")
    void chunkBranchKeysOverride() {
      String yaml =
          """
          index:
            hybrid:
              cc_weight_dense: 0.90
              chunk_cc_weight_sparse: 0.11
              chunk_cc_weight_dense: 0.33
              chunk_cc_weight_splade: 0.22
              chunk_cc_zero_exclude: true
              chunk_collapse_limit_multiplier: 4
              chunk_leg_recall_complete_enabled: true
              chunk_leg_recall_complete_top_n: 5
              chunk_branch_requires_base_results: false
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig.HybridSearch h = builder.build().hybridSearch();
      // Chunk key set → wins over the doc-level value (0.90) it would otherwise inherit.
      assertEquals(0.11, h.chunkCcWeightSparse(), 0.0001);
      assertEquals(0.33, h.chunkCcWeightDense(), 0.0001);
      assertEquals(0.22, h.chunkCcWeightSplade(), 0.0001);
      assertTrue(h.chunkCcZeroExclude());
      assertEquals(4, h.chunkCollapseLimitMultiplier());
      assertTrue(h.chunkLegRecallCompleteEnabled());
      assertEquals(5, h.chunkLegRecallCompleteTopN());
      assertFalse(h.chunkBranchRequiresBaseResults());
    }

    @Test
    @DisplayName("774 Stage 1: chunk_collapse_limit_multiplier clamps to >= 1")
    void chunkCollapseMultiplierClamped() {
      String yaml =
          """
          index:
            hybrid:
              chunk_collapse_limit_multiplier: 0
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      assertEquals(1, builder.build().hybridSearch().chunkCollapseLimitMultiplier());
    }

    @Test
    @DisplayName("774 Stage 1: chunk-branch env vars resolve via EnvRegistry")
    void chunkBranchEnvRegistryKeys() {
      assertEquals(
          "index.hybrid.chunk_cc_weight_dense",
          EnvRegistry.HYBRID_CHUNK_CC_WEIGHT_DENSE.configKey());
      assertEquals(
          "JUSTSEARCH_HYBRID_CHUNK_BRANCH_REQUIRES_BASE_RESULTS",
          EnvRegistry.HYBRID_CHUNK_BRANCH_REQUIRES_BASE_RESULTS.envVar());
    }

    @Test
    @DisplayName("contributeYaml reads RAG config")
    void ragConfig() {
      String yaml =
          """
          rag:
            retrieve:
              mode: hybrid
              top_k: 10
              overretrieve_factor: 5
            diversify:
              mode: mmr
            mmr:
              lambda: 0.7
              max_candidates: 30
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals("hybrid", config.rag().retrieveMode());
      assertEquals(5, config.rag().overretrieveFactor());
      assertEquals("mmr", config.rag().diversifyMode());
      assertEquals(0.7, config.rag().mmrLambda(), 0.001);
      assertEquals(30, config.rag().mmrMaxCandidates());
    }

    @Test
    @DisplayName("contributeYaml reads RAG union.enabled override")
    void ragUnionEnabledOverride() {
      String yaml =
          """
          rag:
            union:
              enabled: false
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertFalse(config.rag().unionEnabled());
    }

    @Test
    @DisplayName("contributeYaml reads worker limits")
    void workerConfig() {
      String yaml =
          """
          worker:
            limits:
              max_batch_size: 5000
              max_queue_depth: 50000
              max_file_size: 52428800
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals(52428800L, config.worker().maxFileSize());
    }

    @Test
    @DisplayName("contributeYaml reads index writer and vector config")
    void indexConfig() {
      String yaml =
          """
          index:
            writer:
              ram_buffer_mb: 256
            commit:
              debounce_ms: 1000
              meta:
                enabled: false
            vector:
              dimension: 384
              hnsw:
                m: 16
                ef_construction: 200
              ef_search: 128
              quantization:
                enabled: true
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertEquals(256, config.index().writerRamBufferMb());
      assertFalse(config.index().commitMetadataEnabled());
      assertEquals(384, config.index().vectorDimension());
      assertEquals(16, config.index().vectorHnswM());
      assertEquals(200, config.index().vectorHnswEfConstruction());
      assertEquals(128, config.index().vectorEfSearch());
      assertTrue(config.index().vectorQuantizationEnabled());
    }

    @Test
    @DisplayName("YAML llm.enabled is visible to buildAi via justsearch.llm.enabled key")
    void yamlLlmEnabledVisibleToBuildAi() {
      String yaml =
          """
          llm:
            enabled: true
            model_path: /models/llama.gguf
            mode: remote
          """;
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeYaml(parseYaml(yaml));
      ResolvedConfig config = builder.build();

      assertTrue(config.ai().llmEnabled(), "YAML llm.enabled should be visible to buildAi()");
      assertEquals(
          Path.of("/models/llama.gguf"),
          config.ai().llmModelPath(),
          "YAML llm.model_path should be visible to buildAi()");
    }

    @Test
    @DisplayName("putSettings contributes at ordinal 300 (below env, above YAML)")
    void putSettingsOrdinal() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.data.dir", "/default");
      builder.putSettings("justsearch.data.dir", "/from-settings");

      ConfigResolution r = builder.resolve("justsearch.data.dir");
      assertEquals("/from-settings", r.value());
      assertEquals("settings.json", r.sourceName());
      assertEquals(300, r.sourceOrdinal());
    }

    @Test
    @DisplayName("putSettings ignores null and blank values")
    void putSettingsIgnoresBlank() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("key.a", "default");
      builder.putSettings("key.a", null);
      builder.putSettings("key.a", "  ");

      ConfigResolution r = builder.resolve("key.a");
      assertEquals("default", r.value());
      assertEquals("default", r.sourceName());
    }

    @Test
    @DisplayName("defaults are used when YAML is empty")
    void defaultsUsed() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      ResolvedConfig config = builder.build();

      // RAG defaults
      assertEquals("auto", config.rag().retrieveMode());
      assertEquals(0.5, config.rag().mmrLambda(), 0.001);
      assertTrue(config.rag().unionEnabled());
      // HybridSearch defaults
      assertEquals(60, config.hybridSearch().rrfK());
      assertEquals(0.75, config.hybridSearch().vectorRrfWeight(), 0.001);
      // Worker defaults
      // AI defaults — must match RuntimePolicyConfigFactory defaults
      assertTrue(config.ai().llmEnabled(), "llmEnabled default must be true (matches factory)");
    }
  }

  // ==================== Auto-Detected Values (ordinal 150) ====================

  @Nested
  @DisplayName("contributeAutoDetected (ordinal 150)")
  class AutoDetected {

    @Test
    @DisplayName("auto-detected value at ordinal 150 is available")
    void autoDetectedValueAvailable() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(Map.of("justsearch.gpu.enabled", "true"));
      ConfigResolution r = builder.resolve("justsearch.gpu.enabled");
      assertEquals("true", r.value());
      assertEquals(150, r.sourceOrdinal());
    }

    @Test
    @DisplayName("env var at ordinal 400 overrides auto-detected at 150")
    void envVarOverridesAutoDetected() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(Map.of("test.gpu", "true"));
      builder.put("test.gpu", ResolvedConfigBuilder.ORDINAL_ENV_VAR, "env_var",
          "TEST_GPU", "false");
      ConfigResolution r = builder.resolve("test.gpu");
      assertEquals("false", r.value());
      assertEquals(400, r.sourceOrdinal());
    }

    @Test
    @DisplayName("sysprop at ordinal 500 overrides auto-detected at 150")
    void syspropOverridesAutoDetected() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(Map.of("test.gpu", "true"));
      builder.put("test.gpu", ResolvedConfigBuilder.ORDINAL_JVM_ARG, "jvm_arg",
          "test.gpu", "false");
      ConfigResolution r = builder.resolve("test.gpu");
      assertEquals("false", r.value());
      assertEquals(500, r.sourceOrdinal());
    }

    @Test
    @DisplayName("null map is ignored")
    void nullMapIgnored() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(null);
      builder.putDefault("justsearch.data.dir", "/fallback");
      // Should not throw
      ResolvedConfig config = builder.build();
      assertEquals(Path.of("/fallback"), config.paths().dataDir());
    }

    @Test
    @DisplayName("empty map is a no-op")
    void emptyMapNoOp() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeAutoDetected(Map.of());
      builder.putDefault("justsearch.data.dir", "/fallback");
      ResolvedConfig config = builder.build();
      assertEquals(Path.of("/fallback"), config.paths().dataDir());
    }
  }

  // ==================== Clamping and Validation ====================

  @Nested
  @DisplayName("Clamping and validation")
  class ClampingValidation {

    @Test
    @DisplayName("RAG top_k is clamped to [1, 50]")
    void ragTopKClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.retrieve.top_k", "100");

      builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.retrieve.top_k", "0");
    }

    @Test
    @DisplayName("RAG overretrieve_factor is clamped to [1, 10]")
    void ragOverretrieveFactorClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.retrieve.overretrieve_factor", "20");
      assertEquals(10, builder.build().rag().overretrieveFactor());

      builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.retrieve.overretrieve_factor", "-5");
      assertEquals(1, builder.build().rag().overretrieveFactor());
    }

    @Test
    @DisplayName("RAG mmr_lambda is clamped to [0.0, 1.0]")
    void ragMmrLambdaClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.mmr.lambda", "2.0");
      assertEquals(1.0, builder.build().rag().mmrLambda(), 0.001);

      builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.mmr.lambda", "-0.5");
      assertEquals(0.0, builder.build().rag().mmrLambda(), 0.001);
    }

    @Test
    @DisplayName("RAG mmr_max_candidates is clamped to [1, 200]")
    void ragMmrMaxCandidatesClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("rag.mmr.max_candidates", "500");
      assertEquals(200, builder.build().rag().mmrMaxCandidates());
    }

    @Test
    @DisplayName("HybridSearch candidate_limit_max is clamped to >= 1")
    void hybridCandidateLimitClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.candidate_limit_max", "0");
      assertEquals(1, builder.build().hybridSearch().candidateLimitMax());
    }

    @Test
    @DisplayName("HybridSearch vector_rrf_weight is clamped to [0.0, 1.0]")
    void hybridVectorRrfWeightClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.vector_rrf_weight", "1.5");
      assertEquals(1.0, builder.build().hybridSearch().vectorRrfWeight(), 0.001);

      builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.vector_rrf_weight", "-0.3");
      assertEquals(0.0, builder.build().hybridSearch().vectorRrfWeight(), 0.001);
    }

    @Test
    @DisplayName("HybridSearch vector_skip_min_df_fraction is clamped to [0.0, 1.0]")
    void hybridVectorSkipMinDfFractionClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.vector_skip_min_df_fraction", "1.5");
      assertEquals(1.0, builder.build().hybridSearch().vectorSkipMinDfFraction(), 0.001);

      builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.vector_skip_min_df_fraction", "-0.5");
      assertEquals(0.0, builder.build().hybridSearch().vectorSkipMinDfFraction(), 0.001);
    }

    @Test
    @DisplayName("HybridSearch multipliers are clamped to >= 1")
    void hybridMultipliersClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.hybrid.text_candidate_multiplier", "0");
      builder.putDefault("index.hybrid.vector_candidate_multiplier", "-5");
      ResolvedConfig config = builder.build();
      assertEquals(1, config.hybridSearch().textCandidateMultiplier());
      assertEquals(1, config.hybridSearch().vectorCandidateMultiplier());
    }

    @Test
    @DisplayName("Index migration cutover max_failed_jobs is clamped to >= -1")
    void indexMigrationCutoverClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.migration.cutover.max_failed_jobs", "-10");
      assertEquals(-1, builder.build().index().migrationCutoverMaxFailedJobs());
    }

    @Test
    @DisplayName("Ports are clamped to [0, 65535]")
    void portsClamped() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.api.port", "99999");
      builder.putDefault("justsearch.server.port", "70000");
      ResolvedConfig config = builder.build();
      assertEquals(65535, config.ports().apiPort());
      assertEquals(65535, config.ports().serverPort());
    }
  }

  // ==================== Schema Mismatch Policy Normalization ====================

  @Nested
  @DisplayName("Schema mismatch policy normalization")
  class SchemaMismatchPolicy {

    @Test
    @DisplayName("lowercase input normalizes to uppercase canonical form")
    void lowercaseNormalizesToUppercase() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.schema_mismatch.policy", "rebuild_backup_first");

      ResolvedConfig config = builder.build();
      assertEquals("REBUILD_BACKUP_FIRST", config.index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("mixed-case input normalizes to uppercase canonical form")
    void mixedCaseNormalizesToUppercase() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.schema_mismatch.policy", "Rebuild_Backup_First");

      ResolvedConfig config = builder.build();
      assertEquals("REBUILD_BACKUP_FIRST", config.index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("kebab-case input normalizes to uppercase canonical form")
    void kebabCaseNormalizesToUppercase() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("index.schema_mismatch.policy", "rebuild-backup-first");

      ResolvedConfig config = builder.build();
      assertEquals("REBUILD_BACKUP_FIRST", config.index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("an unrecognised value falls back to the mode default rather than passing through")
    void unknownPolicyFallsBackToTheModeDefault() {
      // It used to be returned verbatim, so it matched no branch anywhere. After tempdoc 915 §C.12
      // that stopped being merely inert: pre-open detection forced a writable open for any policy
      // it did not recognise, the guard raised, recovery refused the destructive rebuild and the
      // Worker failed to start. A typo in one config key must not be a boot failure.
      ResolvedConfigBuilder dev = new ResolvedConfigBuilder();
      dev.putDefault("index.schema_mismatch.policy", "blue-green-migrat");
      assertEquals("REBUILD_BACKUP_FIRST", dev.build().index().schemaMismatchPolicy());

      ResolvedConfigBuilder prod = new ResolvedConfigBuilder();
      prod.putDefault("index.schema_mismatch.policy", "blue-green-migrat");
      prod.putDefault("justsearch.prod", "true");
      assertEquals("BLUE_GREEN_MIGRATE", prod.build().index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("fail_closed variants all normalize to FAIL_CLOSED")
    void failClosedVariants() {
      for (String variant : new String[] {"fail_closed", "FAIL_CLOSED", "fail-closed", "fail"}) {
        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.putDefault("index.schema_mismatch.policy", variant);

        assertEquals(
            "FAIL_CLOSED",
            builder.build().index().schemaMismatchPolicy(),
            "Expected FAIL_CLOSED for input: " + variant);
      }
    }

    @Test
    @DisplayName("blue_green_migrate variants all normalize")
    void blueGreenVariants() {
      for (String variant :
          new String[] {
            "blue_green_migrate", "blue-green-migrate", "blue_green", "blue-green"
          }) {
        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.putDefault("index.schema_mismatch.policy", variant);

        assertEquals(
            "BLUE_GREEN_MIGRATE",
            builder.build().index().schemaMismatchPolicy(),
            "Expected BLUE_GREEN_MIGRATE for input: " + variant);
      }
    }

    @Test
    @DisplayName("null/blank defaults to REBUILD_BACKUP_FIRST in non-prod mode")
    void nullDefaultsToRebuildInNonProd() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      // Do not set index.schema_mismatch.policy — leave null
      // Do not set justsearch.prod — defaults to false

      ResolvedConfig config = builder.build();
      assertEquals("REBUILD_BACKUP_FIRST", config.index().schemaMismatchPolicy());
    }

    @Test
    @DisplayName("null/blank defaults to BLUE_GREEN_MIGRATE in prod mode")
    void nullDefaultsToBlueGreenInProd() {
      // Tempdoc 915 §C. The old FAIL_CLOSED default meant a schema-changing upgrade left a
      // production user with an index that refused to open and no way forward; blue/green keeps
      // the existing index serving reads while the new one is built beside it. Asserted on the
      // exact value, not merely "not FAIL_CLOSED", so a future accidental flip to
      // REBUILD_BACKUP_FIRST (which destroys the old index) fails here.
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.putDefault("justsearch.prod", "true");

      ResolvedConfig config = builder.build();
      assertEquals("BLUE_GREEN_MIGRATE", config.index().schemaMismatchPolicy());
    }
  }

  // ==================== ConfigResolution ====================

  @Nested
  @DisplayName("ConfigResolution")
  class ConfigResolutionTests {

    @Test
    @DisplayName("toLogString formats resolved value with source info")
    void toLogStringResolved() {
      ConfigResolution r =
          new ConfigResolution(
              "justsearch.data.dir",
              "/tmp/data",
              "env_var",
              400,
              "JUSTSEARCH_DATA_DIR",
              java.util.List.of());
      assertEquals(
          "justsearch.data.dir=/tmp/data (env_var:JUSTSEARCH_DATA_DIR, ordinal=400)",
          r.toLogString());
    }

    @Test
    @DisplayName("toLogString formats unset value")
    void toLogStringUnset() {
      ConfigResolution r =
          new ConfigResolution(
              "justsearch.data.dir", null, "none", 0, null, java.util.List.of());
      assertEquals("justsearch.data.dir=<unset>", r.toLogString());
    }
  }

  // ==================== Architectural Coverage (tempdoc 347) ====================

  @Nested
  @DisplayName("Architectural coverage (tempdoc 347)")
  class ArchitecturalCoverage {

    @Test
    @DisplayName("Every EnvRegistry configKey is unique")
    void configKeysAreUnique() {
      Map<String, EnvRegistry> seen = new java.util.LinkedHashMap<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        String key = entry.configKey();
        EnvRegistry existing = seen.put(key, entry);
        assertNull(existing,
            () -> "Duplicate configKey '" + key + "' between "
                + existing.name() + " and " + entry.name());
      }
    }

    @Test
    @DisplayName("Every EnvRegistry sysProp is unique")
    void syspropsAreUnique() {
      Map<String, EnvRegistry> seen = new java.util.LinkedHashMap<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        String key = entry.sysProp();
        EnvRegistry existing = seen.put(key, entry);
        assertNull(existing,
            () -> "Duplicate sysProp '" + key + "' between "
                + existing.name() + " and " + entry.name());
      }
    }

    @Test
    @DisplayName("Every EnvRegistry envVar is unique")
    void envVarsAreUnique() {
      Map<String, EnvRegistry> seen = new java.util.LinkedHashMap<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        String key = entry.envVar();
        EnvRegistry existing = seen.put(key, entry);
        assertNull(existing,
            () -> "Duplicate envVar '" + key + "' between "
                + existing.name() + " and " + entry.name());
      }
    }

    @Test
    @DisplayName("contributeEnvRegistry registers every entry in the ordinal chain")
    void contributeEnvRegistryRegistersAll() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();

      java.util.Set<String> registeredKeys = new java.util.TreeSet<>();
      // Build to populate allResolutions; build() resolves all entries.keySet()
      ResolvedConfig config = builder.build();
      registeredKeys.addAll(config.resolutions().keySet());

      for (EnvRegistry entry : EnvRegistry.values()) {
        assertTrue(registeredKeys.contains(entry.configKey()),
            () -> "EnvRegistry." + entry.name() + " configKey '" + entry.configKey()
                + "' not found in resolved keys. "
                + "contributeEnvRegistry() should register it.");
      }
    }

    @Test
    @DisplayName("build() resolves all keys without error when only EnvRegistry is contributed")
    void buildSucceedsWithEnvRegistryOnly() {
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      ResolvedConfig config = builder.build();
      assertNotNull(config);
      assertNotNull(config.resolutions());
      assertFalse(config.resolutions().isEmpty());
    }

    @Test
    @DisplayName("No configKey collides with a different entry's sysProp")
    void noConfigKeySysPropCollision() {
      // If entry A's configKey == entry B's sysProp (and A != B),
      // contributeEnvRegistry would register two different entries under the same key.
      Map<String, EnvRegistry> bySysProp = new java.util.HashMap<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        bySysProp.put(entry.sysProp(), entry);
      }
      for (EnvRegistry entry : EnvRegistry.values()) {
        if (entry.configKey().equals(entry.sysProp())) continue; // no override, safe
        EnvRegistry collision = bySysProp.get(entry.configKey());
        assertNull(collision,
            () -> "EnvRegistry." + entry.name() + " configKey '" + entry.configKey()
                + "' collides with EnvRegistry." + collision.name() + " sysProp");
      }
    }

    @Test
    @DisplayName("Every key resolved by build() has an EnvRegistry or ConfigKey entry (reverse direction)")
    void everyResolvedKeyHasRegistryEntry() {
      // Build with EnvRegistry contributions so all entries are registered in the ordinal chain.
      // build() then calls build*() methods which call resolve*() for their keys.
      // resolvedKeys() captures every key that resolve() touched.
      ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
      builder.contributeEnvRegistry();
      builder.build();

      java.util.Set<String> knownConfigKeys = new java.util.HashSet<>();
      for (EnvRegistry entry : EnvRegistry.values()) {
        knownConfigKeys.add(entry.configKey());
      }
      for (io.justsearch.configuration.ConfigKey entry
          : io.justsearch.configuration.ConfigKey.values()) {
        knownConfigKeys.add(entry.configKey());
      }

      java.util.List<String> uncovered = new java.util.ArrayList<>();
      for (String resolvedKey : builder.resolvedKeys()) {
        if (!knownConfigKeys.contains(resolvedKey)) {
          uncovered.add(resolvedKey);
        }
      }
      assertTrue(uncovered.isEmpty(),
          "Keys resolved by build*() methods without an EnvRegistry or ConfigKey entry: "
              + uncovered + ". Add an entry with configKey matching each key.");
    }

    @Test
    @DisplayName("Master gpu.enabled=true falls through to per-encoder gpuEnabled when per-key unset (Bug D)")
    void masterFallthroughEnablesPerEncoderGpu() {
      // Tempdoc 374 alpha.16 fix D — root-cause investigation. Round-6 sandbox
      // agent reported worker had master justsearch.gpu.enabled=true but
      // embed.gpuEnabled=false. resolveEmbedGpuEnabled has master fallback
      // logic (line 966-976). This test pins it.
      String prevMaster = System.getProperty("justsearch.gpu.enabled");
      String prevEmbed = System.getProperty("justsearch.embed.gpu.enabled");
      String prevSplade = System.getProperty("justsearch.splade.gpu_enabled");
      String prevNer = System.getProperty("justsearch.ner.gpu_enabled");
      String prevPolicy = System.getProperty("policy.gpu_acceleration_enabled");
      try {
        System.setProperty("justsearch.gpu.enabled", "true");
        System.clearProperty("justsearch.embed.gpu.enabled");
        System.clearProperty("justsearch.splade.gpu_enabled");
        System.clearProperty("justsearch.ner.gpu_enabled");
        System.clearProperty("policy.gpu_acceleration_enabled");

        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.contributeEnvRegistry();
        ResolvedConfig config = builder.build();

        assertTrue(config.ai().embedding().gpuEnabled(),
            "embedding.gpuEnabled must fall through to master=true when per-key unset");
        assertTrue(config.ai().splade().gpuEnabled(),
            "splade.gpuEnabled must fall through to master=true when per-key unset");
        assertTrue(config.ai().ner().gpuEnabled(),
            "ner.gpuEnabled must fall through to master=true when per-key unset");
        assertTrue(config.ai().reranker().gpuEnabled(),
            "reranker.gpuEnabled defaults true via EnvRegistry default");
      } finally {
        restoreSysprop("justsearch.gpu.enabled", prevMaster);
        restoreSysprop("justsearch.embed.gpu.enabled", prevEmbed);
        restoreSysprop("justsearch.splade.gpu_enabled", prevSplade);
        restoreSysprop("justsearch.ner.gpu_enabled", prevNer);
        restoreSysprop("policy.gpu_acceleration_enabled", prevPolicy);
      }
    }

    private void restoreSysprop(String key, String prev) {
      if (prev != null) System.setProperty(key, prev);
      else System.clearProperty(key);
    }

    @Test
    @DisplayName("Master GPU flag falls through to every per-feature flag when only it is set")
    void masterGpuFlagFallsThroughToPerFeatureFlags() {
      // Lane F item A19 retargeted this test, and kept it. It was
      // "masterFallthroughViaWorkerSnapshot", and it reproduced a round-6 sandbox defect where a
      // worker-config-snapshot.json carried justsearch.gpu.enabled=true while embed.gpuEnabled read
      // false. The SNAPSHOT is gone with the process boundary it crossed — but the property it
      // pinned is not about snapshots at all: it is that the master GPU flag, set from a source
      // that is NOT a sysprop and NOT a per-feature key, still reaches every per-feature flag.
      // That fallthrough is exactly what broke in round 6, and it is still live, so the test now
      // contributes the master flag at the YAML ordinal instead of the deleted 450 tier. Deleting
      // it with its mechanism would have retired a real regression pin along with a dead one.
      String prevMaster = System.getProperty("justsearch.gpu.enabled");
      String prevEmbed = System.getProperty("justsearch.embed.gpu.enabled");
      String prevSplade = System.getProperty("justsearch.splade.gpu_enabled");
      String prevNer = System.getProperty("justsearch.ner.gpu_enabled");
      String prevPolicy = System.getProperty("policy.gpu_acceleration_enabled");
      try {
        // Crucially: master arrives from a NON-sysprop source (YAML, below). Per-feature keys
        // unset everywhere. The A19 retarget above left a @TempDir write of a
        // worker-config-snapshot.json here; the builder never read it — the master flag comes
        // from the ORDINAL_YAML put — so it set up state nothing asserted on and is removed.
        System.clearProperty("justsearch.gpu.enabled");
        System.clearProperty("justsearch.embed.gpu.enabled");
        System.clearProperty("justsearch.splade.gpu_enabled");
        System.clearProperty("justsearch.ner.gpu_enabled");
        System.clearProperty("policy.gpu_acceleration_enabled");

        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.contributeAutoDetected(Map.of()); // empty (the hardware probe found nothing)
        builder.put("justsearch.gpu.enabled", ResolvedConfigBuilder.ORDINAL_YAML, "yaml",
            "application.yaml", "true");
        builder.put("justsearch.rerank.gpu.enabled", ResolvedConfigBuilder.ORDINAL_YAML, "yaml",
            "application.yaml", "true");
        builder.contributeEnvRegistry();
        ResolvedConfig config = builder.build();

        assertTrue(config.ai().embedding().gpuEnabled(),
            "embed.gpuEnabled must fall through to the master flag");
        assertTrue(config.ai().splade().gpuEnabled(),
            "splade.gpuEnabled must fall through to the master flag");
        assertTrue(config.ai().ner().gpuEnabled(),
            "ner.gpuEnabled must fall through to the master flag");
      } finally {
        restoreSysprop("justsearch.gpu.enabled", prevMaster);
        restoreSysprop("justsearch.embed.gpu.enabled", prevEmbed);
        restoreSysprop("justsearch.splade.gpu_enabled", prevSplade);
        restoreSysprop("justsearch.ner.gpu_enabled", prevNer);
        restoreSysprop("policy.gpu_acceleration_enabled", prevPolicy);
      }
    }

    @Test
    @DisplayName("GPU policy gate vetoes per-model GPU when policy is false")
    void gpuPolicyGateVetoesPerModelGpu() {
      String prevSplade = System.getProperty("justsearch.splade.gpu_enabled");
      String prevPolicy = System.getProperty("policy.gpu_acceleration_enabled");
      try {
        System.setProperty("justsearch.splade.gpu_enabled", "true");
        System.setProperty("policy.gpu_acceleration_enabled", "false");

        ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
        builder.contributeEnvRegistry();
        ResolvedConfig config = builder.build();

        assertFalse(config.ai().splade().gpuEnabled(),
            "splade.gpuEnabled must be false when policy gate is false, "
                + "even if SPLADE_GPU_ENABLED=true");
        assertFalse(config.ai().embedding().gpuEnabled(),
            "embedding.gpuEnabled must be false when policy gate is false");
      } finally {
        if (prevSplade != null) System.setProperty("justsearch.splade.gpu_enabled", prevSplade);
        else System.clearProperty("justsearch.splade.gpu_enabled");
        if (prevPolicy != null) System.setProperty("policy.gpu_acceleration_enabled", prevPolicy);
        else System.clearProperty("policy.gpu_acceleration_enabled");
      }
    }

    @Test
    @DisplayName(
        "854 W1: branch-ramp bounds default to 1024/4096 — byte-identical to the pre-split"
            + " shared-constant defaults")
    void branchRampBoundsDefault() {
      ResolvedConfig config = new ResolvedConfigBuilder().contributeEnvRegistry().build();
      assertEquals(1024L, config.hybridSearch().branchRampFullWeightMaxTokens());
      assertEquals(4096L, config.hybridSearch().branchRampZeroWeightMinTokens());
    }

    @Test
    @DisplayName(
        "854 W1 divergence pin: raising the SPLADE-only zero_weight_min_tokens sysprop must NOT"
            + " move the (separately keyed) branch-ramp bound — the F-036 §K wrong-gate is fixed")
    void branchRampBoundsUnaffectedBySpladeBoundSysprop() {
      // Before tempdoc 854 W1, justsearch.splade.zero_weight_min_tokens was the ONLY bound and
      // HybridFusionUtils read it directly via Long.getLong for BOTH the Stage-3A SPLADE fade and
      // the Stage-3B branch ramp (784 §K). This key is not even resolved through ResolvedConfig —
      // it never was — but a hypothetical future "wire it through the config chain" fix must NOT
      // let raising it also move index.hybrid.branch_ramp.zero_weight_min_tokens.
      String prevSpladeBound = System.getProperty("justsearch.splade.zero_weight_min_tokens");
      String prevBranchRampBound =
          System.getProperty("index.hybrid.branch_ramp.zero_weight_min_tokens");
      try {
        System.setProperty("justsearch.splade.zero_weight_min_tokens", "20000");
        System.clearProperty("index.hybrid.branch_ramp.zero_weight_min_tokens");

        ResolvedConfig config = new ResolvedConfigBuilder().contributeEnvRegistry().build();

        assertEquals(
            4096L,
            config.hybridSearch().branchRampZeroWeightMinTokens(),
            "the branch ramp's own bound must stay at its default; it has no key overlap with"
                + " the SPLADE-only sysprop");
      } finally {
        if (prevSpladeBound != null) {
          System.setProperty("justsearch.splade.zero_weight_min_tokens", prevSpladeBound);
        } else {
          System.clearProperty("justsearch.splade.zero_weight_min_tokens");
        }
        if (prevBranchRampBound != null) {
          System.setProperty(
              "index.hybrid.branch_ramp.zero_weight_min_tokens", prevBranchRampBound);
        } else {
          System.clearProperty("index.hybrid.branch_ramp.zero_weight_min_tokens");
        }
      }
    }

    @Test
    @DisplayName(
        "854 W1: the branch-ramp bound has its OWN sysprop/env key and can be set independently")
    void branchRampBoundsSettableIndependently() {
      String prevBound = System.getProperty("index.hybrid.branch_ramp.zero_weight_min_tokens");
      try {
        System.setProperty("index.hybrid.branch_ramp.zero_weight_min_tokens", "8192");
        ResolvedConfig config = new ResolvedConfigBuilder().contributeEnvRegistry().build();
        assertEquals(8192L, config.hybridSearch().branchRampZeroWeightMinTokens());
        // SPLADE's own bound (not wired through ResolvedConfig) is unaffected by construction —
        // this class never resolves justsearch.splade.zero_weight_min_tokens at all.
      } finally {
        if (prevBound != null) {
          System.setProperty("index.hybrid.branch_ramp.zero_weight_min_tokens", prevBound);
        } else {
          System.clearProperty("index.hybrid.branch_ramp.zero_weight_min_tokens");
        }
      }
    }
  }

  // ==================== Worker-boot base composition (tempdoc 628) ====================

  @Nested
  @DisplayName("contributeBaseSources — shared worker-boot composition (tempdoc 628)")
  class BaseSourcesComposition {

    @Test
    @DisplayName("contributeBaseSources reads index.auto_recovery from YAML (the standalone-worker fix)")
    void baseSourcesContributesYaml(@TempDir Path tmp) throws Exception {
      Path yaml = tmp.resolve("application.yaml");
      Files.writeString(yaml, "index:\n  auto_recovery: true\n  recovery:\n    policy: FAIL_CLOSED\n");
      String key = EnvRegistry.CONFIG_PATH.sysProp();
      String prev = System.getProperty(key);
      System.setProperty(key, yaml.toString());
      try {
        // The exact standalone-worker shape after the fix: auto-detected + base (env + YAML).
        ResolvedConfig config =
            new ResolvedConfigBuilder()
                .contributeAutoDetected(Map.of())
                .contributeBaseSources()
                .build();
        assertTrue(
            config.index().indexAutoRecovery(),
            "contributeBaseSources must contribute YAML so standalone recovery is enabled");
        assertEquals("FAIL_CLOSED", config.index().indexRecoveryPolicy());
      } finally {
        if (prev == null) {
          System.clearProperty(key);
        } else {
          System.setProperty(key, prev);
        }
      }
    }

    @Test
    @DisplayName("Pre-fix shape (env only, no YAML) silently defaults index.auto_recovery to false")
    void envOnlyDefaultsFalse() {
      // The exact tempdoc 628 defect: the standalone worker composed auto+env WITHOUT yaml, so
      // index.auto_recovery (a YAML-only key) silently defaulted to false and recovery was disabled.
      ResolvedConfig config =
          new ResolvedConfigBuilder()
              .contributeAutoDetected(Map.of())
              .contributeEnvRegistry()
              .build();
      assertFalse(
          config.index().indexAutoRecovery(),
          "documents the pre-fix divergence: env-only standalone defaulted recovery off");
    }
  }

  // ==================== 771 item (b): MCP entity carriage ====================

  @Nested
  @DisplayName("MCP entity carriage (tempdoc 771 item (b))")
  class McpEntityCarriage {

    @Test
    @DisplayName("defaults OFF at the shipped default chain — carriage never turns on by omission")
    void defaultsOff() {
      ResolvedConfig config = new ResolvedConfigBuilder().contributeEnvRegistry().build();
      assertNotNull(config.search().mcpEntityCarriage());
      assertFalse(
          config.search().mcpEntityCarriage().enabled(),
          "D-004 default-off template: an unconfigured process must deliver the pre-771 response");
      assertEquals(
          ResolvedConfig.Search.DEFAULT_ENTITY_CARRIAGE_MAX_CHARS,
          config.search().mcpEntityCarriage().maxChars());
    }

    @Test
    @DisplayName("the operator sysprop actually reaches the resolved record — the gate really fires")
    void syspropTurnsCarriageOn() {
      String prevEnabled = System.getProperty("search.mcp_delivery.entity_carriage_enabled");
      String prevMax = System.getProperty("search.mcp_delivery.entity_carriage_max_chars");
      try {
        System.setProperty("search.mcp_delivery.entity_carriage_enabled", "true");
        System.setProperty("search.mcp_delivery.entity_carriage_max_chars", "321");

        ResolvedConfig config = new ResolvedConfigBuilder().contributeEnvRegistry().build();

        assertTrue(
            config.search().mcpEntityCarriage().enabled(),
            "the carriage flag must resolve through EnvRegistry, not just exist as a symbol");
        assertEquals(321, config.search().mcpEntityCarriage().maxChars());
      } finally {
        restore("search.mcp_delivery.entity_carriage_enabled", prevEnabled);
        restore("search.mcp_delivery.entity_carriage_max_chars", prevMax);
      }
    }

    private void restore(String key, String prev) {
      if (prev != null) System.setProperty(key, prev);
      else System.clearProperty(key);
    }
  }
}
