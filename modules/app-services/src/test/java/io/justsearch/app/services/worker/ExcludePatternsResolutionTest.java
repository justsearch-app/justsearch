/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.excludes.ExcludesServiceImpl;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The user's exclude patterns reach their consumers through the RESOLVER with NO system property
 * set anywhere (tempdoc 883 decision 4 slice 2).
 *
 * <p>Before slice 2, {@code justsearch.ui.exclude_patterns} was contributed at ordinal 300 but
 * never {@code resolve*()}d, so every reader went to the sysprop that {@code SettingsController}
 * promoted the list into — which is why the key reported as {@code jvm_arg}. Each case below
 * asserts the sysprop is absent before asserting the value arrived, so a regression that quietly
 * restores the promotion cannot make them pass.
 */
@DisplayName("exclude patterns resolve from settings.json, not from a sysprop")
final class ExcludePatternsResolutionTest {

  private static final String KEY = "justsearch.ui.exclude_patterns";

  private ConfigStore previousGlobal;
  private String savedSysprop;

  @BeforeEach
  void captureGlobals() {
    previousGlobal = ConfigStore.globalOrNull();
    savedSysprop = System.getProperty(KEY);
    System.clearProperty(KEY);
  }

  @AfterEach
  void restoreGlobals() {
    TestResolvedConfigHelper.restoreGlobal(previousGlobal);
    if (savedSysprop == null) {
      System.clearProperty(KEY);
    } else {
      System.setProperty(KEY, savedSysprop);
    }
  }

  /** Publishes a ConfigStore whose only source for the key is UiSettings at ordinal 300. */
  private static void publishSettings(List<String> patterns) {
    UiSettings settings = new UiSettings();
    settings.setExcludePatterns(new ArrayList<>(patterns));
    ResolvedConfigBuilder builder = ResolvedConfig.builder();
    builder.contributeEnvRegistry(); // 500/400 — deliberately empty for this key
    ConfigStoreRebuilder.contributeUiSettings(builder, settings);
    ConfigStore.setGlobal(new ConfigStore(builder.build()));
  }

  @Test
  @DisplayName("ResolvedConfig.Ui exposes the settings list at ordinal 300")
  void resolvedConfigExposesTheList() {
    publishSettings(List.of("**/node_modules/**", "**/*.log"));

    ResolvedConfig rc = ConfigStore.global().get();
    assertNull(System.getProperty(KEY), "no promotion may have written a system property");
    assertEquals("[\"**/node_modules/**\",\"**/*.log\"]", rc.ui().excludePatterns());
    assertEquals("settings.json", rc.resolution(KEY).sourceName());
  }

  @Test
  @DisplayName("KnowledgeClient reads the resolved list, not the sysprop")
  void remoteKnowledgeClientReadsTheResolvedList() {
    publishSettings(List.of("**/*.tmp"));

    assertNull(System.getProperty(KEY));
    assertEquals("[\"**/*.tmp\"]", KnowledgeClient.resolvedExcludePatterns());
  }

  @Test
  @DisplayName("KnowledgeClient tolerates a store that is not published yet")
  void remoteKnowledgeClientToleratesNoStore() {
    TestResolvedConfigHelper.restoreGlobal(null);

    assertEquals("", KnowledgeClient.resolvedExcludePatterns());
  }

  @Test
  @DisplayName("ExcludesServiceImpl applies the resolved list end to end")
  void excludesServiceAppliesTheResolvedList(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("keep.txt"), "x");
    Files.writeString(root.resolve("noisy.log"), "x");

    publishSettings(List.of("**/*.log"));
    assertNull(System.getProperty(KEY));

    RecordingIndexingService indexing =
        new RecordingIndexingService(List.of(new IndexingService.WatchedRoot("default", root)));
    ExcludesService.ExcludesResult result =
        new ExcludesServiceImpl(() -> indexing).applyExcludes(true);

    assertEquals(1, result.patterns(), "the settings pattern must have been picked up");
    assertEquals(1, result.matchedFiles());
    assertTrue(
        result.perPattern().stream().anyMatch(p -> "**/*.log".equals(p.pattern())),
        "and it must be the user's own pattern, not a default");
  }

  private static final class RecordingIndexingService implements IndexingService {
    private final List<WatchedRoot> roots;

    RecordingIndexingService(List<WatchedRoot> roots) {
      this.roots = roots;
    }

    @Override
    public List<Path> getWatchedPaths() {
      return roots.stream().map(WatchedRoot::path).toList();
    }

    @Override
    public List<WatchedRoot> getWatchedRoots() {
      return roots;
    }

    @Override
    public void addWatchedPath(Path path) {
      throw new UnsupportedOperationException("not needed");
    }

    @Override
    public int removeWatchedPath(Path path) {
      throw new UnsupportedOperationException("not needed");
    }

    @Override
    public void flush() {
      // no-op
    }

    @Override
    public int deleteDocsByPathPrefix(Path pathPrefix) {
      throw new UnsupportedOperationException("dry run must not delete");
    }

    @Override
    public boolean deleteDocById(String docId) {
      throw new UnsupportedOperationException("dry run must not delete");
    }
  }
}
