/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.configuration.ConfigKey;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexRuntimeConfigurationTest extends RuntimeTestBase {

  @Test
  void deferredUpgradePreservesAppliedConfiguration() {
    ResolvedConfig config = config(Map.of(
        "index.writer.ram_buffer_mb", "96",
        "index.writer.max_buffered_docs", "2000",
        "index.validation.mode", "warn",
        "index.vector.ef_search", "37",
        "index.vector.exhaustive_search", "true",
        "index.hybrid.candidate_limit_max", "123"));
    Path index = tempDir.resolve("deferred-equality");
    IndexSchema schema = IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4));

    try (RunningRuntime seed = runtime(schema, index, config)) {
      // Seed the durable index required by a strict deferred/read-only open.
      assertEquals(96.0, seed.appliedConfigurationValues().get("index.writer.ram_buffer_mb"));
    }

    try (DeferredRuntime deferred =
        schema.atPath(index)
            .withExecutorRegistrations(testLuceneExecutors())
            .withConfig(config)
            .withoutRecovery()
            .openDeferred()) {
      Map<String, Object> before = deferred.appliedConfigurationValues();
      var preparation = deferred.prepareWriterUpgrade();
      RunningRuntime upgraded = preparation.runtime();
      preparation.markPublished();
      preparation.retireReader();
      try (upgraded) {
        assertEquals(before, upgraded.appliedConfigurationValues());
        assertEquals(IndexRuntimeConfiguration.dependencies(), before.keySet());
        assertEquals(20_000L, before.get(ConfigKey.INDEX_QUEUE_MAX_DEPTH.configKey()));
        assertEquals("warn", before.get(ConfigKey.INDEX_VALIDATION_MODE.configKey()));
        assertEquals(37, before.get(EnvRegistry.INDEX_VECTOR_EF_SEARCH.configKey()));
        assertEquals(true, before.get(EnvRegistry.INDEX_VECTOR_EXHAUSTIVE_SEARCH.configKey()));
        assertEquals(123, before.get(EnvRegistry.HYBRID_CANDIDATE_LIMIT_MAX.configKey()));
        assertThrows(UnsupportedOperationException.class, () -> before.put("x", "y"));
      }
    }
  }

  @Test
  void explicitEffectiveDefaultsEqualUnsetDefaults() {
    ResolvedConfig unset = config(Map.of());
    ResolvedConfig explicit =
        config(
            Map.ofEntries(
                Map.entry("index.directory.type", "mmap"),
                Map.entry("index.writer.ram_buffer_mb", "64"),
                Map.entry("index.writer.max_buffered_docs", "-1"),
                Map.entry("index.soft_deletes.field", "_soft_delete"),
                Map.entry("index.soft_deletes.retention.enabled", "false"),
                Map.entry("index.soft_deletes.retention.days", "7"),
                Map.entry("index.vector.hnsw.m", "16"),
                Map.entry("index.vector.hnsw.ef_construction", "200"),
                Map.entry("index.vector.quantization.enabled", "false"),
                Map.entry("index.nrt.target_max_stale_ms", "500"),
                Map.entry("index.nrt.max_stale_ms", "50"),
                Map.entry("index.nrt.mode", "continuous"),
                Map.entry("index.queue.max_depth", "10000"),
                Map.entry("index.validation.mode", "fail"),
                Map.entry("index.commit.timer_interval_ms", "0")));

    try (RunningRuntime left = runtime(
            IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
            tempDir.resolve("defaults-unset"), unset);
        RunningRuntime right = runtime(
            IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
            tempDir.resolve("defaults-explicit"), explicit)) {
      assertEquals(left.appliedConfigurationValues(), right.appliedConfigurationValues());
    }
  }

  @Test
  void directoryAliasesAndMapperDerivedSortAreProjectedFromActualObjects() {
    ResolvedConfig niofs = config(Map.of("index.directory.type", "niofs"));
    ResolvedConfig simplefs = config(Map.of("index.directory.type", "simplefs"));
    ResolvedConfig sorted =
        config(
            Map.of(
                "index.sort",
                "[{\"field\":\"modified_at\",\"type\":\"STRING\",\"reverse\":true}]"));

    try (RunningRuntime nio = runtime(
            IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
            tempDir.resolve("niofs"), niofs);
        RunningRuntime simple = runtime(
            IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
            tempDir.resolve("simplefs"), simplefs);
        RunningRuntime sort = runtime(
            IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
            tempDir.resolve("sort"), sorted)) {
      String directoryKey = ConfigKey.INDEX_DIRECTORY_TYPE.configKey();
      assertEquals("NIOFS", nio.appliedConfigurationValues().get(directoryKey));
      assertEquals("NIOFS", simple.appliedConfigurationValues().get(directoryKey));

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> appliedSort =
          (List<Map<String, Object>>)
              sort.appliedConfigurationValues().get(ConfigKey.INDEX_SORT.configKey());
      assertEquals(
          List.of(Map.of("field", "modified_at", "type", "LONG", "reverse", true)),
          appliedSort);
      @SuppressWarnings("unchecked")
      Map<String, Object> dimension =
          (Map<String, Object>)
              sort.appliedConfigurationValues().get(ConfigKey.INDEX_VECTOR_DIMENSION.configKey());
      assertNull(dimension.get("configuredGuard"));
      assertEquals(Map.of("vector", 4), dimension.get("mapperDimensions"));
    }
  }

  @Test
  void inactiveFusionSettingsRemainNull() {
    ResolvedConfig cc = config(Map.of("index.hybrid.fusion_strategy", "cc"));
    ResolvedConfig rrf = config(Map.of("index.hybrid.fusion_strategy", "rrf"));

    try (RunningRuntime ccRuntime = runtime(
            IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)), tempDir.resolve("cc"), cc);
        RunningRuntime rrfRuntime = runtime(
            IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)), tempDir.resolve("rrf"), rrf)) {
      Map<String, Object> ccValues = ccRuntime.appliedConfigurationValues();
      Map<String, Object> rrfValues = rrfRuntime.appliedConfigurationValues();
      assertNull(ccValues.get(EnvRegistry.HYBRID_RRF_K.configKey()));
      assertNull(rrfValues.get(EnvRegistry.HYBRID_CC_ALPHA.configKey()));
      assertNull(rrfValues.get(EnvRegistry.HYBRID_LEG_ARBITRATION_ENABLED.configKey()));

      ResolvedConfig ccWithoutArbitration =
          config(
              Map.of(
                  "index.hybrid.fusion_strategy", "cc",
                  "index.hybrid.leg_arbitration_enabled", "false",
                  "index.hybrid.leg_arbitration_alpha_diverge", "0.99"));
      try (RunningRuntime disabled = runtime(
          IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
          tempDir.resolve("cc-disabled"), ccWithoutArbitration)) {
        assertEquals(
            false,
            disabled.appliedConfigurationValues()
                .get(EnvRegistry.HYBRID_LEG_ARBITRATION_ENABLED.configKey()));
        assertNull(
            disabled.appliedConfigurationValues()
                .get(EnvRegistry.HYBRID_LEG_ARBITRATION_ALPHA_DIVERGE.configKey()));
      }
    }
  }

  private RunningRuntime runtime(IndexSchema schema, Path path, ResolvedConfig config) {
    return schema.atPath(path)
        .withExecutorRegistrations(testLuceneExecutors())
        .withConfig(config)
        .withoutRecovery()
        .open();
  }

  @Test
  void onDemandRefreshRetainsTheSeparateCommitRefreshThresholds() {
    var settings = config(Map.of(
        "index.nrt.mode", "on_demand",
        "index.nrt.background_reopen_ms", "1000",
        "index.nrt.target_max_stale_ms", "23",
        "index.nrt.max_stale_ms", "7"));
    try (var opened = runtime(IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
        tempDir.resolve("on-demand-thresholds"), settings)) {
      var values = opened.appliedConfigurationValues();
      assertEquals(23L, values.get(ConfigKey.INDEX_NRT_TARGET_MAX_STALE_MS.configKey()));
      assertEquals(7L, values.get(ConfigKey.INDEX_NRT_MAX_STALE_MS.configKey()));
      assertEquals(1000L, values.get(EnvRegistry.INDEX_NRT_BACKGROUND_REOPEN_MS.configKey()));
    }
  }

  @Test
  void inactiveRefreshAndRecallPoolSettingsDoNotChangeAppliedValues() {
    var baseline = config(Map.of(
        "index.nrt.mode", "continuous", "index.hybrid.leg_recall_complete_enabled", "false"));
    var inactiveChange = config(Map.of(
        "index.nrt.mode", "continuous",
        "index.nrt.background_reopen_ms", "37",
        "index.nrt.on_demand_max_stale_ms", "19",
        "index.hybrid.leg_recall_complete_enabled", "false",
        "index.hybrid.leg_recall_complete_top_n", "42"));
    try (var left = runtime(IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
            tempDir.resolve("inactive-baseline"), baseline);
        var right = runtime(IndexSchema.fromCatalog(FieldCatalogDef.forTesting(4)),
            tempDir.resolve("inactive-change"), inactiveChange)) {
      assertEquals(left.appliedConfigurationValues(), right.appliedConfigurationValues());
      assertNull(right.appliedConfigurationValues().get(EnvRegistry.HYBRID_RERANK_POOL_TOP_N.configKey()));
    }
  }

  private static ResolvedConfig config(Map<String, String> values) {
    ResolvedConfigBuilder builder = new ResolvedConfigBuilder();
    values.forEach(builder::putDefault);
    return builder.build();
  }
}
