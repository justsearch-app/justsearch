/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import static io.justsearch.configuration.EnvRegistry.LifecycleStage.DEPRECATED;
import static io.justsearch.configuration.EnvRegistry.LifecycleStage.EXPERIMENTAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ConfigLifecycleTest {

    @Test
    void everyDeclarationHasALifecycleStage() {
        for (EnvRegistry entry : EnvRegistry.values()) {
            assertNotNull(entry.lifecycleStage(), entry.name());
        }
        for (ConfigKey entry : ConfigKey.values()) {
            assertNotNull(entry.lifecycleStage(), entry.name());
        }
    }

    @Test
    void nonPermanentDeclarationsMatchTheReviewedLifecycleOverlay() {
        Set<EnvRegistry> experimental = EnumSet.allOf(EnvRegistry.class).stream()
            .filter(entry -> entry.lifecycleStage() == EXPERIMENTAL)
            .collect(Collectors.toSet());

        assertEquals(Set.of(
            EnvRegistry.QU_ENABLED,
            EnvRegistry.FILTER_NORM_ENABLED,
            EnvRegistry.CAPABILITY_CONTRACT_STRICT,
            EnvRegistry.BACKFILL_CHUNK_SLOTS_PER_BATCH,
            EnvRegistry.HYBRID_ADAPTIVE_WEIGHTS_ENABLED,
            EnvRegistry.RERANK_JUDGE_BLEND_ENABLED,
            EnvRegistry.RERANK_JUDGE_BLEND_ALPHA,
            EnvRegistry.RERANK_JUDGE_ARBITRATION_ENABLED,
            EnvRegistry.RERANK_JUDGE_ARBITRATION_ALPHA_DIVERGE,
            EnvRegistry.RERANK_JUDGE_ARBITRATION_SKIP_ENABLED,
            EnvRegistry.SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_ENABLED,
            EnvRegistry.SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_MAX_CHARS,
            EnvRegistry.SEARCH_MCP_FRAMING_CONTINUATION,
            EnvRegistry.SEARCH_MCP_FRAMING_EVIDENCE_NOT_ANSWER,
            EnvRegistry.SEARCH_MCP_FRAMING_CALIBRATED_ABSENCE,
            EnvRegistry.SEARCH_MCP_FRAMING_THIN_RESULT_FLOOR_BYTES,
            EnvRegistry.SEARCH_MCP_FRAMING_WEAK_SCORE_FLOOR,
            EnvRegistry.INDEX_NRT_MODE,
            EnvRegistry.INDEX_NRT_BACKGROUND_REOPEN_MS,
            EnvRegistry.INDEX_NRT_ON_DEMAND_MAX_STALE_MS), experimental);

        Set<EnvRegistry> deprecated = EnumSet.allOf(EnvRegistry.class).stream()
            .filter(entry -> entry.lifecycleStage() == DEPRECATED)
            .collect(Collectors.toSet());
        assertEquals(Set.of(EnvRegistry.VLM_PROFILE), deprecated);

        Set<ConfigKey> yamlExperiments = EnumSet.allOf(ConfigKey.class).stream()
            .filter(entry -> entry.lifecycleStage() == EXPERIMENTAL)
            .collect(Collectors.toSet());
        assertEquals(
            Set.of(
                ConfigKey.SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_ENABLED,
                ConfigKey.SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_MAX_CHARS,
                ConfigKey.SEARCH_MCP_FRAMING_CONTINUATION,
                ConfigKey.SEARCH_MCP_FRAMING_EVIDENCE_NOT_ANSWER,
                ConfigKey.SEARCH_MCP_FRAMING_CALIBRATED_ABSENCE,
                ConfigKey.SEARCH_MCP_FRAMING_THIN_RESULT_FLOOR_BYTES,
                ConfigKey.SEARCH_MCP_FRAMING_WEAK_SCORE_FLOOR),
            yamlExperiments);
        assertEquals(
            0L,
            EnumSet.allOf(ConfigKey.class).stream()
                .filter(entry -> entry.lifecycleStage() == DEPRECATED)
                .count());
    }
}
