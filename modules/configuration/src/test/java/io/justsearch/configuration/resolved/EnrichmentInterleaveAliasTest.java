/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static io.justsearch.configuration.resolved.ResolvedConfigBuilder.ORDINAL_ENV_VAR;
import static io.justsearch.configuration.resolved.ResolvedConfigBuilder.ORDINAL_JVM_ARG;
import static io.justsearch.configuration.resolved.ResolvedConfigBuilder.ORDINAL_YAML;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * E4b D6: the canonical enrichment interleave settings and their legacy SPLADE aliases resolve as
 * one ordinal chain before parsing; at equal priority the canonical value wins.
 */
class EnrichmentInterleaveAliasTest {
  private static final String PREFIX = "justsearch.backfill.";
  private static final String[] SUFFIXES = {"batch_size", "interval_ms"};

  // Reads either record spelling, so the before state compiles and fails on the resolved value.
  private static long value(ResolvedConfigBuilder builder, String suffix) throws Exception {
    var pacing = builder.build().ai().backfillPacing();
    String field = suffix.equals("batch_size") ? "InterleaveBatchSize" : "InterleaveIntervalMs";
    try {
      return ((Number) pacing.getClass().getMethod("enrichment" + field).invoke(pacing)).longValue();
    } catch (NoSuchMethodException before) {
      return ((Number) pacing.getClass().getMethod("splade" + field).invoke(pacing)).longValue();
    }
  }

  private static String canonical(String suffix) {
    return PREFIX + "enrichment_interleave_" + suffix;
  }

  private static String legacy(String suffix) {
    return PREFIX + "splade_interleave_" + suffix;
  }

  private static long fallback(String suffix) {
    return suffix.equals("batch_size") ? 10 : 5000;
  }

  @Test
  void canonicalOnlyLegacyOnlyNeitherAndBoth() throws Exception {
    for (String suffix : SUFFIXES) {
      assertEquals(fallback(suffix), value(new ResolvedConfigBuilder(), suffix), "neither");
      assertEquals(17, value(new ResolvedConfigBuilder()
          .put(canonical(suffix), ORDINAL_ENV_VAR, "env_var", "canonical", "17"), suffix), "canonical only");
      assertEquals(19, value(new ResolvedConfigBuilder()
          .put(legacy(suffix), ORDINAL_ENV_VAR, "env_var", "legacy", "19"), suffix), "legacy only keeps its value");
      assertEquals(17, value(new ResolvedConfigBuilder()
          .put(canonical(suffix), ORDINAL_ENV_VAR, "env_var", "canonical", "17")
          .put(legacy(suffix), ORDINAL_ENV_VAR, "env_var", "legacy", "19"), suffix),
          "both at equal priority: canonical wins");
    }
  }

  @Test
  void propertyOutranksEnvironmentAcrossBothNames() throws Exception {
    for (String suffix : SUFFIXES) {
      assertEquals(19, value(new ResolvedConfigBuilder()
          .put(canonical(suffix), ORDINAL_ENV_VAR, "env_var", "canonical", "17")
          .put(legacy(suffix), ORDINAL_JVM_ARG, "jvm_arg", "legacy", "19"), suffix),
          "a legacy property outranks a canonical environment value");
      assertEquals(17, value(new ResolvedConfigBuilder()
          .put(canonical(suffix), ORDINAL_JVM_ARG, "jvm_arg", "canonical", "17")
          .put(legacy(suffix), ORDINAL_ENV_VAR, "env_var", "legacy", "19"), suffix));
      assertEquals(17, value(new ResolvedConfigBuilder()
          .put(canonical(suffix), ORDINAL_YAML, "yaml", "canonical", "13")
          .put(canonical(suffix), ORDINAL_JVM_ARG, "jvm_arg", "canonical", "17")
          .put(legacy(suffix), ORDINAL_ENV_VAR, "env_var", "legacy", "19"), suffix));
    }
  }

  @Test
  void blankWinnerDefersToTheNextSourceIncludingTheAliasAtEqualPriority() throws Exception {
    for (String suffix : SUFFIXES) {
      assertEquals(19, value(new ResolvedConfigBuilder()
          .put(canonical(suffix), ORDINAL_JVM_ARG, "jvm_arg", "canonical", " ")
          .put(legacy(suffix), ORDINAL_ENV_VAR, "env_var", "legacy", "19"), suffix));
      assertEquals(19, value(new ResolvedConfigBuilder()
          .put(legacy(suffix), ORDINAL_JVM_ARG, "jvm_arg", "legacy", "19")
          .put(canonical(suffix), ORDINAL_JVM_ARG, "jvm_arg", "canonical", ""), suffix),
          "a blank canonical value does not hide the legacy value at the same priority");
    }
  }

  @Test
  void invalidWinningValueDefaultsWithoutFallingBackToALowerPriorityAlias() throws Exception {
    for (String suffix : SUFFIXES) {
      for (String invalid : new String[] {"-1", "invalid", "9223372036854775808"}) {
        assertEquals(fallback(suffix), value(new ResolvedConfigBuilder()
            .put(canonical(suffix), ORDINAL_JVM_ARG, "jvm_arg", "canonical", invalid)
            .put(legacy(suffix), ORDINAL_ENV_VAR, "env_var", "legacy", "19"), suffix), invalid);
        assertEquals(fallback(suffix), value(new ResolvedConfigBuilder()
            .put(legacy(suffix), ORDINAL_JVM_ARG, "jvm_arg", "legacy", invalid)
            .put(canonical(suffix), ORDINAL_ENV_VAR, "env_var", "canonical", "17"), suffix), invalid);
      }
    }
    assertEquals(10, value(new ResolvedConfigBuilder().put(canonical("batch_size"),
        ORDINAL_JVM_ARG, "jvm_arg", "canonical", "0"), "batch_size"), "a zero count cap is invalid");
    assertEquals(10, value(new ResolvedConfigBuilder().put(canonical("batch_size"),
        ORDINAL_JVM_ARG, "jvm_arg", "canonical", "2147483648"), "batch_size"), "int overflow");
    assertEquals(0, value(new ResolvedConfigBuilder().put(canonical("interval_ms"),
        ORDINAL_JVM_ARG, "jvm_arg", "canonical", "0"), "interval_ms"),
        "a zero interval keeps its meaning: a busy turn on every primary iteration");
  }
}
