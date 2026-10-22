/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.UiSettingsV2;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExclusionLimitTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

  @Test
  void EX4PreviewAndApplyCountFilesAndRemoveExactMembership() throws Exception {
    var first = java.nio.file.Files.writeString(
        java.nio.file.Files.createDirectory(root.resolve("private")).resolve("a.txt"), "a");
    var second = java.nio.file.Files.writeString(root.resolve("private/b.txt"), "b");
    var keep = java.nio.file.Files.writeString(root.resolve("public.txt"), "public");
    var indexed = new java.util.HashSet<>(List.of(first, second, keep));
    var indexing = org.mockito.Mockito.mock(io.justsearch.app.api.IndexingService.class);
    var context = io.justsearch.app.services.TestEngineContexts.durableInternal();
    org.mockito.Mockito.when(indexing.getWatchedRoots(context)).thenReturn(
        List.of(new io.justsearch.app.api.IndexingService.WatchedRoot("docs", root)));
    org.mockito.Mockito.when(indexing.deleteDocsByPathPrefix(org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.eq(context))).thenAnswer(call -> {
          java.nio.file.Path prefix = call.getArgument(0);
          int before = indexed.size();
          indexed.removeIf(path -> path.startsWith(prefix));
          return before - indexed.size();
        });
    org.mockito.Mockito.when(indexing.deleteDocById(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.eq(context))).thenAnswer(call ->
            indexed.remove(java.nio.file.Path.of((String) call.getArgument(0))));
    var service = new io.justsearch.app.services.excludes.ExcludesServiceImpl(() -> indexing);
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    var installed = new io.justsearch.configuration.resolved.ConfigStore(
        io.justsearch.configuration.resolved.ResolvedConfig.builder()
            .putDefault("justsearch.ui.exclude_patterns", "[\"private/\"]").build());
    io.justsearch.configuration.resolved.ConfigStore.setGlobal(installed);
    try {
      var preview = service.applyExcludes(true, context);
      assertEquals(2, preview.matchedFiles(), "EX4: matchedFiles counts files, not one folder");
      assertEquals(java.util.Set.of(first, second, keep), indexed, "Preview removes nothing");
      var applied = service.applyExcludes(false, context);
      assertEquals(2, applied.matchedFiles());
      assertEquals(java.util.Set.of(keep), indexed, "Apply removes exactly the previewed file membership");
    } finally {
      io.justsearch.configuration.resolved.ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void EX4InputOverflowRefusesWithoutReplacingPolicy() {
    var settings = new UiSettings();
    settings.setExcludePatterns(List.of("private"));
    var tooMany = IntStream.range(0, 513).mapToObj(i -> "file" + i + ".txt").toList();
    assertThrows(IllegalArgumentException.class, () -> SettingsPatch.merge(settings, patch(tooMany), true),
        "EX4: an excessive list must be refused rather than silently truncated");
    assertEquals(List.of("private"), settings.getExcludePatterns());
  }

  @Test
  void EX4ExpandedOverflowRefusesWithoutReplacingPolicy() {
    var settings = new UiSettings();
    settings.setExcludePatterns(List.of("private"));
    var tooMany = IntStream.range(0, 257).mapToObj(i -> "directory" + i).toList();
    assertThrows(IllegalArgumentException.class, () -> SettingsPatch.merge(settings, patch(tooMany), true),
        "EX4: expansion may exceed 512 even when the input list does not");
    assertEquals(List.of("private"), settings.getExcludePatterns());
  }
  @Test
  void EX4MalformedGlobIsRefusedBeforeSaving() {
    var settings = new UiSettings();
    settings.setExcludePatterns(List.of("private"));
    assertThrows(IllegalArgumentException.class,
        () -> SettingsPatch.merge(settings, patch(List.of("**/[broken")), true));
    assertEquals(List.of("private"), settings.getExcludePatterns());
  }

  @Test
  void EN1ResolvedOperatorOverridesPreserveBareNameMatching() {
    String key = "justsearch.ui.exclude_patterns";
    var resolved = io.justsearch.configuration.resolved.ResolvedConfig.builder()
        .putSettings(key, "[\"gui\"]")
        .put(key, 400, "env var", "JUSTSEARCH_UI_EXCLUDE_PATTERNS", "[\"environment\"]")
        .put(key, 500, "jvm_arg", key, "[\"operator\"]").build();
    var matcher = io.justsearch.app.services.indexing.ExcludeGlobs.fromRawJsonArray(
        resolved.ui().excludePatterns());
    org.junit.jupiter.api.Assertions.assertTrue(matcher.isExcludedPath(root, root.resolve("nested/operator/file.txt")));
    org.junit.jupiter.api.Assertions.assertFalse(matcher.isExcludedPath(root, root.resolve("nested/gui/file.txt")));
    var environment = io.justsearch.configuration.resolved.ResolvedConfig.builder()
        .putSettings(key, "[\"gui\"]")
        .put(key, 400, "env var", "JUSTSEARCH_UI_EXCLUDE_PATTERNS", "[\"environment\"]").build();
    org.junit.jupiter.api.Assertions.assertTrue(
        io.justsearch.app.services.indexing.ExcludeGlobs.fromRawJsonArray(environment.ui().excludePatterns())
            .isExcludedPath(root, root.resolve("nested/environment/file.txt")));
  }

  @Test
  void EX4AcceptedMetacharactersMatchExactCorpusMembership() {
    var matcher = io.justsearch.app.services.indexing.ExcludeGlobs.fromPatterns(List.of(
        "*.log", "note?.txt", "[ab].bin", "{take,omit}.txt", "[!x-z]?.cfg", "src/cache/", "private\\"));
    var expected = java.util.Set.of("a.log", "nested/b.log", "note1.txt", "nested/note2.txt",
        "a.bin", "nested/b.bin", "take.txt", "nested/omit.txt", "ab.cfg", "nested/mq.cfg", "src/cache/item.txt", "nested/private/secret.txt");
    var corpus = new java.util.ArrayList<>(expected);
    corpus.addAll(List.of("public.md", "nested/note22.txt", "c.bin", "src/cache-other/item.txt",
        "artifact.log/kept.txt", "private", "xq.cfg", "nested/zq.cfg", "nested/aqq.cfg"));
    var actual = corpus.stream().filter(path -> matcher.isExcludedPath(root, root.resolve(path)))
        .collect(java.util.stream.Collectors.toSet());
    assertEquals(expected, actual, "EX4: compare complete file membership, including classes and alternatives");
  }

  @Test
  void EX4SeparatorsAndNativeCaseRuleArePreserved() {
    var matcher = io.justsearch.app.services.indexing.ExcludeGlobs.fromPatterns(List.of("private\\"));
    org.junit.jupiter.api.Assertions.assertTrue(matcher.isExcludedPath(root, root.resolve("nested/private/notes.txt")));
    org.junit.jupiter.api.Assertions.assertTrue(matcher.isExcludedPath(root, root.resolve("nested\\private\\notes.txt")));
    boolean windows = java.nio.file.FileSystems.getDefault().getSeparator().equals("\\");
    assertEquals(windows, matcher.isExcludedPath(root, root.resolve("nested/PRIVATE/notes.txt")));
  }

  @Test
  void EN1PatternLimitsKeepCompleteSupportedLists() {
    var settings = new UiSettings();
    var bare = IntStream.range(0, 256).mapToObj(i -> "directory" + i).toList();
    SettingsPatch.merge(settings, patch(bare), true);
    assertEquals(bare, settings.getExcludePatterns());
    org.junit.jupiter.api.Assertions.assertTrue(
        io.justsearch.app.services.indexing.ExcludeGlobs.fromPatterns(settings.getExcludePatterns())
            .isExcludedPath(root, root.resolve("directory255/notes.txt")));
    var globs = IntStream.range(0, 512).mapToObj(i -> "*file" + i + ".txt").toList();
    SettingsPatch.merge(settings, patch(globs), true);
    assertEquals(globs, settings.getExcludePatterns());
    org.junit.jupiter.api.Assertions.assertTrue(
        io.justsearch.app.services.indexing.ExcludeGlobs.fromPatterns(settings.getExcludePatterns())
            .isExcludedPath(root, root.resolve("nested/file511.txt")));
  }

  @Test
  @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
  void EX4WindowsCaseRuleIncludesUnicodeNames() {
    String previous = System.getProperty("os.name");
    io.justsearch.app.services.indexing.ExcludeGlobs matcher;
    try {
      System.setProperty("os.name", "Windows 11");
      matcher = io.justsearch.app.services.indexing.ExcludeGlobs.fromPatterns(List.of("Äccount.TXT"));
    } finally {
      if (previous == null) System.clearProperty("os.name");
      else System.setProperty("os.name", previous);
    }
    org.junit.jupiter.api.Assertions.assertTrue(
        matcher.isExcludedPath(root, root.resolve("nested/äccount.txt")),
        "EX4: Windows case-insensitive matching includes Unicode names");
  }

  @Test
  void EX4DoubleStarAloneMatchesAllFiles() {
    var matcher = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> io.justsearch.app.services.indexing.ExcludeGlobs.fromPatterns(List.of("**")),
        "EX4: a supported match-all glob must not fail during directory optimisation");
    org.junit.jupiter.api.Assertions.assertTrue(matcher.isExcludedPath(root, root.resolve("notes.txt")));
    org.junit.jupiter.api.Assertions.assertTrue(matcher.isExcludedPath(root, root.resolve("nested/notes.txt")));
  }

  @Test
  void EX4QualifiedDirectoryPatternsIncludeDescendants() {
    var matcher = io.justsearch.app.services.indexing.ExcludeGlobs.fromPatterns(List.of("**/private/"));
    org.junit.jupiter.api.Assertions.assertTrue(matcher.isExcludedPath(root, root.resolve("private/notes.txt")));
    org.junit.jupiter.api.Assertions.assertTrue(matcher.isExcludedPath(root, root.resolve("nested/private/deep/notes.txt")));
    org.junit.jupiter.api.Assertions.assertFalse(matcher.isExcludedPath(root, root.resolve("private.txt")));
  }

  private static SettingsV2 patch(List<String> patterns) {
    return new SettingsV2(new UiSettingsV2(null, null, null, null, null, null, null, null, patterns, null), null, null, null);
  }
}
