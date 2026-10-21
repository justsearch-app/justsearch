/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.IndexingService;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.excludes.ExcludesServiceImpl;
import io.justsearch.configuration.excludes.ExclusionPatterns;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Upgrade data was written by f9bd104d2 through SettingsPatch and UiSettingsStore, not this reader. */
class LegacyExclusionUpgradeTest {
  @TempDir Path temp;

  @Test
  void EN1AX1ListWrittenByBeforeVersionKeepsLiteralAndValidMatchesAfterRestart() throws Exception {
    Path saved = temp.resolve("settings.json");
    try (var fixture = getClass().getResourceAsStream("legacy-exclusions-f9bd104d2.json")) {
      assertNotNull(fixture, "settings fixture written by f9bd104d2 must be present");
      Files.copy(fixture, saved);
    }
    byte[] originalBytes = Files.readAllBytes(saved);
    for (int restart = 0; restart < 2; restart++) {
      var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, saved).inspect().settings();
      assertEquals(List.of("[legacy", "private", "{broken", "report[1].txt", "{take,omit}.txt"), settings.getExcludePatterns());
      var previous = ConfigStore.globalOrNull();
      var installed = new ConfigStore(ConfigStoreRebuilder.prepare(settings));
      ConfigStore.setGlobal(installed);
      try {
        var matcher = assertDoesNotThrow(ExclusionPatterns::current,
            "AX1: one invalid legacy entry must never stop admission of other files");
        Path root = temp.resolve("corpus");
        var corpus = List.of("[legacy/file.txt", "nested/[legacy/file.txt", "[legacy",
            "{broken/file.txt", "nested/private/secret.txt", "private/secret.txt",
            "report1.txt", "nested/take.txt", "omit.txt", "keep.txt", "report[1].txt", "legacy.txt");
        var expected = java.util.Set.of("[legacy/file.txt", "nested/[legacy/file.txt", "[legacy",
            "{broken/file.txt", "nested/private/secret.txt", "private/secret.txt", "report1.txt", "nested/take.txt", "omit.txt");
        assertEquals(expected, corpus.stream().filter(p -> matcher.isExcludedPath(root, root.resolve(p)))
            .collect(java.util.stream.Collectors.toSet()), "AX1/AX2: literal invalid names and current glob syntax coexist");
        // Captured lists must compile again: boot and recorded replay carry the matcher's patterns.
        var replay = assertDoesNotThrow(() -> ExclusionPatterns.fromPatterns(matcher.patterns()));
        assertEquals(expected, corpus.stream().filter(p -> replay.isExcludedPath(root, root.resolve(p)))
            .collect(java.util.stream.Collectors.toSet()));
      } finally {
        ConfigStore.restoreGlobal(installed, previous);
      }
    }
    assertArrayEquals(originalBytes, Files.readAllBytes(saved), "Reading a legacy policy never rewrites it");
  }

  @Test
  void AX1EditorProjectionNamesEveryInvalidEntryWithoutReplacingSavedList() throws Exception {
    Path saved = temp.resolve("settings.json");
    try (var fixture = getClass().getResourceAsStream("legacy-exclusions-f9bd104d2.json")) {
      assertNotNull(fixture);
      Files.copy(fixture, saved);
    }
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, saved).inspect().settings();
    var projection = SettingsV2Projection.toSettingsV2(settings, UiSettingsStore.PersistenceMode.READ_WRITE);
    var json = new ObjectMapper().valueToTree(projection);
    assertEquals(List.of("[legacy", "{broken"),
        new ObjectMapper().convertValue(json.path("ui").path("invalidExcludePatterns"),
            new tools.jackson.core.type.TypeReference<List<String>>() {}),
        "AX1: editor diagnostics name each saved invalid entry, from the shared compiler");
    assertEquals(settings.getExcludePatterns(), projection.ui().excludePatterns());
  }

  @Test
  void AX1PreviewApplyKeepTheSavedListAndLiteralMembership() throws Exception {
    Path saved = temp.resolve("settings.json");
    try (var fixture = getClass().getResourceAsStream("legacy-exclusions-f9bd104d2.json")) {
      assertNotNull(fixture);
      Files.copy(fixture, saved);
    }
    var settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, saved).inspect().settings();
    Path root = Files.createDirectory(temp.resolve("corpus"));
    var indexed = new java.util.HashSet<Path>();
    for (String rel : List.of("[legacy/file.txt", "private/secret.txt", "{broken/item.txt", "keep.txt")) {
      Path file = root.resolve(rel);
      Files.createDirectories(file.getParent());
      Files.writeString(file, rel);
      indexed.add(file);
    }
    var context = io.justsearch.app.services.TestEngineContexts.durableInternal();
    IndexingService indexing = mock(IndexingService.class);
    when(indexing.getWatchedRoots(context)).thenReturn(List.of(new IndexingService.WatchedRoot("docs", root)));
    when(indexing.deleteDocsByPathPrefix(any(), eq(context))).thenAnswer(call -> {
      Path prefix = call.getArgument(0);
      int size = indexed.size();
      indexed.removeIf(p -> p.startsWith(prefix));
      return size - indexed.size();
    });
    when(indexing.deleteDocById(anyString(), eq(context))).thenAnswer(call -> indexed.remove(Path.of((String) call.getArgument(0))));
    var previous = ConfigStore.globalOrNull();
    var installed = new ConfigStore(ConfigStoreRebuilder.prepare(settings));
    ConfigStore.setGlobal(installed);
    try {
      var service = new ExcludesServiceImpl(() -> indexing);
      var preview = assertDoesNotThrow(() -> service.applyExcludes(true, context));
      assertEquals(3, preview.matchedFiles());
      assertEquals(settings.getExcludePatterns(), preview.perPattern().stream().map(p -> p.pattern()).toList(),
          "AX1: Preview shows the saved list as it is, without losing good entries or displaying expansions");
      assertEquals(4, indexed.size());
      var applied = assertDoesNotThrow(() -> service.applyExcludes(false, context));
      assertEquals(3, applied.matchedFiles());
      assertEquals(java.util.Set.of(root.resolve("keep.txt")), indexed);
    } finally {
      ConfigStore.restoreGlobal(installed, previous);
    }
  }
}
