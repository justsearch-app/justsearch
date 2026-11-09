/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import org.junit.jupiter.api.Test;

final class RerankerSearchDefaultTest {
  @Test
  void automaticPolicyUsesOnlyNullableChoiceOperatorPathAndEffectiveRolePermission() {
    for (String choice : new String[] {null, "true", "false"}) {
      for (boolean gpu : new boolean[] {false, true}) {
        for (int pathOrdinal : new int[] {0, 300, 399, 400, 500}) {
          var builder = new ResolvedConfigBuilder()
              .putDefault("justsearch.rerank.gpu.enabled", Boolean.toString(gpu));
          if (choice != null) builder.putSettings("justsearch.rerank.enabled", choice);
          if (pathOrdinal > 0) builder.put("justsearch.rerank.model_path", pathOrdinal,
              "fixture", "path", "/fixture/reranker");
          var config = builder.build();
          assertEquals(choice == null && !gpu && pathOrdinal < 400,
              RerankerConfig.searchDefaultOff(config),
              "choice=" + choice + ", gpu=" + gpu + ", pathOrdinal=" + pathOrdinal);
          if (pathOrdinal > 0) {
            assertEquals(!"false".equals(choice), RerankerConfig.from(config).isReady(),
                "automatic policy does not change physical readiness");
          }
        }
      }
    }
  }

  @Test
  void capturedNoDevicePolicyVetoAndPlannedCpuQualifyButMasterCpuAndUnknownDoNot() {
    assertTrue(RerankerConfig.searchDefaultOff(new ResolvedConfigBuilder()
        .put("justsearch.gpu.device_available", ResolvedConfigBuilder.ORDINAL_AUTO_DETECT,
            "auto_detect", "fixture", "false").build()));
    assertTrue(RerankerConfig.searchDefaultOff(new ResolvedConfigBuilder()
        .putDefault("policy.gpu_acceleration_enabled", "false").build()));
    assertTrue(RerankerConfig.searchDefaultOff(new ResolvedConfigBuilder()
        .put("justsearch.rerank.gpu.enabled", ResolvedConfigBuilder.ORDINAL_AUTO_DETECT,
            "auto_detect", "plan", "false").build()));
    assertFalse(RerankerConfig.searchDefaultOff(new ResolvedConfigBuilder()
        .putDefault("justsearch.gpu.enabled", "false").build()));
    assertFalse(RerankerConfig.searchDefaultOff(new ResolvedConfigBuilder().build()));
    assertTrue(RerankerConfig.searchDefaultOff(new ResolvedConfigBuilder()
        .putDefault("justsearch.rerank.gpu.enabled", "false")
        .put("justsearch.models.dir", 400, "env_var", "models", "/fixture/models").build()),
        "a model-root override is not a reranker-path operator override");
  }
}
