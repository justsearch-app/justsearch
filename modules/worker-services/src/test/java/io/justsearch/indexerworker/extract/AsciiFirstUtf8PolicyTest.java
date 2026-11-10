/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AsciiFirstUtf8PolicyTest {
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void blankOrNullIdentityUsesCurrentGeneration(String identity) {
    assertEquals("tika-default-v9", new TikaExtractionPolicy(identity, 1000, 1000, 1000,
        128, 128, 4096, 256, 8, 100, true, Set.of(), Set.of()).policyId());
  }

  @Test
  void defaultAndConfiguredIdentitiesUseCurrentGeneration() {
    assertEquals("tika-default-v9", TikaExtractionPolicy.defaults().policyId());
    assertEquals("tika-default-v9", TikaExtractionPolicy.fromWorkerLimits(null).policyId());
    assertEquals("tika-config-v9-1000-2000", TikaExtractionPolicy.fromWorkerLimits(
        new ResolvedConfig.Worker(1000, 2000)).policyId());
  }
  @Test
  void explicitCustomIdentityRemainsCallerOwned() {
    assertEquals("tika-default-v8", new TikaExtractionPolicy("  tika-default-v8  ",
        1000, 1000, 1000, 128, 128, 4096, 256, 8, 100, true, Set.of(), Set.of()).policyId());
  }
}
