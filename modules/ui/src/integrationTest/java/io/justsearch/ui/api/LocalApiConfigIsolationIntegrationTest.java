/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A retained engine fixture's env-only store must not become an API fixture's settings store. */
@Timeout(60)
final class LocalApiConfigIsolationIntegrationTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "foreignConfigDoesNotBreakAiPolicyImport(installPolicy={0})")
  @ValueSource(booleans = {true, false})
  void foreignConfigDoesNotBreakAiPolicyImport(boolean installPolicy) throws Exception {
    var previous = ConfigStore.globalOrNull();
    // Match RuntimeReloadProducersHttpIntegrationTest.Fixture's private env-only snapshot.
    var source = ResolvedConfig.builder();
    source.putDefault(EnvRegistry.DATA_DIR.configKey(), directory.resolve("foreign-data").toString());
    source.putDefault(EnvRegistry.INDEX_BASE_PATH.configKey(), directory.resolve("foreign-index").toString());
    source.putDefault("justsearch.ssot.path", directory.resolve("foreign-ssot").toString());
    source.putDefault(EnvRegistry.EXTRACTION_SANDBOX_MODE.configKey(), "in_process");
    source.putDefault(EnvRegistry.RERANK_CHUNKS_ENABLED.configKey(), "false");
    for (var key : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
        EnvRegistry.NER_ENABLED, EnvRegistry.BGE_M3_ENABLED, EnvRegistry.RERANK_ENABLED,
        EnvRegistry.CITATION_SCORER_ENABLED}) source.putDefault(key.configKey(), "false");
    var foreign = new ConfigStore(source.build());
    var foreignSnapshot = foreign.get();
    ConfigStore.setGlobal(foreign);
    try {
      LocalApiIntegrationTestBase fixture = installPolicy
          ? new AiInstallPolicyIntegrationTest() : new AiPackPolicyIntegrationTest();
      fixture.tmp = Files.createDirectory(directory.resolve("api"));
      try {
        fixture.startServer();
        if (fixture instanceof AiInstallPolicyIntegrationTest policy) {
          policy.allowlistedOfflineImportStillWorksWithDownloadsDisabled();
        } else {
          ((AiPackPolicyIntegrationTest) fixture).machineAbsentUserAllowlistAllowsImport();
        }
        assertNotSame(foreign, ConfigStore.globalOrNull(), "the API must publish its own store");
      } finally {
        fixture.stopServer();
      }
      assertSame(foreign, ConfigStore.globalOrNull(), "teardown must restore the retained store");
      assertSame(foreignSnapshot, foreign.get(), "the foreign owner's snapshot must stay untouched");
    } finally {
      ConfigStore.restoreGlobal(foreign, previous);
    }
  }
}
