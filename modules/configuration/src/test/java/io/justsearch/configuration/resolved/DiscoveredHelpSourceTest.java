/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DiscoveredHelpSourceTest {
  @TempDir Path directory;

  @Test
  void normalAssemblyRetainsCwdDiscoveredSsotAndHelpWithoutOverrides() {
    String oldSsot = System.getProperty(EnvRegistry.SSOT_PATH.sysProp());
    String oldRepo = System.getProperty(EnvRegistry.REPO_ROOT.sysProp());
    try {
      System.clearProperty(EnvRegistry.SSOT_PATH.sysProp());
      System.clearProperty(EnvRegistry.REPO_ROOT.sysProp());
      assertNull(EnvRegistry.SSOT_PATH.getPath(), "fixture must have no SSOT override");
      Path discovered = new JustSearchConfigurationLoader().ssotRoot().orElseThrow();
      var config = ResolvedConfig.builder().contributeBaseSources().build();
      assertEquals(discovered, config.paths().ssotPath(),
          "normal assembly must retain the loader's discovered SSOT path");
      assertNotNull(config.collections().bundledHelp());
      assertEquals(new ResolvedConfig.FileSource(discovered.resolve("docs/help"), "justsearch-help"),
          config.collections().bundledHelp());
    } finally {
      restore(EnvRegistry.SSOT_PATH.sysProp(), oldSsot);
      restore(EnvRegistry.REPO_ROOT.sysProp(), oldRepo);
    }
  }

  @Test
  void explicitSsotOverridesDiscovery() throws Exception {
    Path explicit = Files.createDirectory(directory.resolve("custom-ssot"));
    String previous = System.getProperty(EnvRegistry.SSOT_PATH.sysProp());
    try {
      System.setProperty(EnvRegistry.SSOT_PATH.sysProp(), explicit.toString());
      var config = ResolvedConfig.builder().contributeBaseSources().build();
      assertEquals(explicit, config.paths().ssotPath());
      assertEquals(explicit.resolve("docs/help"), config.collections().bundledHelp().path());
    } finally {
      restore(EnvRegistry.SSOT_PATH.sysProp(), previous);
    }
  }

  @Test
  void settingsSsotOverridesDiscoveredPath() {
    Path explicit = directory.resolve("settings-ssot");
    String previous = System.getProperty(EnvRegistry.SSOT_PATH.sysProp());
    try {
      System.clearProperty(EnvRegistry.SSOT_PATH.sysProp());
      var config = ResolvedConfig.builder().contributeBaseSources()
          .putSettings("justsearch.ssot.path", explicit.toString()).build();
      assertEquals(explicit, config.paths().ssotPath());
      assertEquals(explicit.resolve("docs/help"), config.collections().bundledHelp().path());
    } finally {
      restore(EnvRegistry.SSOT_PATH.sysProp(), previous);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }
}
