/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.excludes;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.ObjectMapper;

class LiteralExclusionPolicyTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TOKEN = "00000000-0000-4000-8000-000000000001";

  private static Map<String, Object> literal(String path, String scope) {
    return Map.of("literal", Map.of("version", 1, "id", TOKEN, "path", path, "scope", scope));
  }

  private static ExclusionPatterns compile(List<?> entries, boolean windows) {
    String raw = JSON.writeValueAsString(entries);
    return assertDoesNotThrow(() -> ExclusionPatterns.fromRawJsonStrict(raw, windows),
        "typed exact paths must compile without interpreting their bytes as a glob");
  }

  @Test @EnabledOnOs(OS.LINUX)
  void RF55FilePreservesUnixPunctuationBackslashAndTrailingSpace() {
    Path root = Path.of("/docs");
    Path exact = root.resolve("report\\[ab]*?{x}.txt ");
    var policy = compile(List.of(literal(exact.toString(), "FILE")), false);
    assertTrue(policy.isExcludedPath(root, exact));
    for (Path sibling : List.of(root.resolve("report/[ab]*?{x}.txt "),
        root.resolve("report\\[ab]*?{x}.txt"), root.resolve("report\\[ab]*?{x}.txt .bak"),
        Path.of("/other/report\\[ab]*?{x}.txt "))) {
      assertFalse(policy.isExcludedPath(root, sibling), sibling.toString());
    }
    assertFalse(policy.isExcludedPath(root, exact.resolve("child")));
  }

  @Test @EnabledOnOs(OS.LINUX)
  void RF55FolderUsesSeparatorPrefixOutsideRecordedRoot() {
    Path folder = Path.of("/outside/b ");
    var policy = compile(List.of(literal(folder.toString(), "FOLDER")), false);
    assertTrue(policy.isExcludedPath(Path.of("/watched"), folder.resolve("file.txt")));
    assertTrue(policy.isExcludedDirectory(null, folder));
    assertFalse(policy.isExcludedPath(Path.of("/outside"), Path.of("/outside/b/file.txt")));
    assertFalse(policy.isExcludedPath(Path.of("/outside"), Path.of("/outside/b c/file.txt")));
    assertEquals(0, policy.matchingSavedPatternIndex(null, folder.resolve("file.txt")));
  }

  @Test
  void RF55OperatingSystemCaseRulesRemainExplicit() {
    String target = Path.of("/docs/Report.txt").toAbsolutePath().normalize().toString();
    Path alternate = Path.of("/DOCS/report.TXT");
    assertTrue(compile(List.of(literal(target, "FILE")), true).isExcludedPath(null, alternate));
    assertFalse(compile(List.of(literal(target, "FILE")), false).isExcludedPath(null, alternate));
  }

  @Test
  void RF55LegacyStringsNeverBecomeLiteralTokens() {
    var legacy = compile(List.of("literal:report", "[legacy", "*.log"), false);
    Path root = Path.of("/docs");
    assertTrue(legacy.isExcludedPath(root, root.resolve("nested/literal:report")));
    assertTrue(legacy.isExcludedPath(root, root.resolve("nested/[legacy")));
    assertTrue(legacy.isExcludedPath(root, root.resolve("nested/file.log")));
    assertEquals(List.of("[legacy"), legacy.invalidPatterns());
  }

  @Test
  void RF55RecordedProjectionOmitsLongLiteralsAndRetainsLegacyGlobs() {
    String path = Path.of("/outside/" + "segment/".repeat(600) + "file.txt ")
        .toAbsolutePath().normalize().toString();
    var policy = compile(List.of("*.log", literal(path, "FILE")), false);
    assertEquals(List.of("**/*.log"), policy.patterns(), "root-plan.v1 remains legacy glob strings");
    assertTrue(policy.isExcludedPath(null, Path.of(path)));
    assertEquals(1, policy.matchingSavedPatternIndex(null, Path.of(path)));
  }

  @Test
  void RF55CapacityCountsLiteralsAndGlobExpansionTogether() {
    List<Object> atLimit = new ArrayList<>();
    for (int i = 0; i < 511; i++) atLimit.add("**/file" + i + ".tmp");
    atLimit.add(literal(Path.of("/docs/exact").toAbsolutePath().toString(), "FILE"));
    assertDoesNotThrow(() -> ExclusionPatterns.validateSavedPatterns(atLimit));
    atLimit.add("**/one-more.tmp");
    assertThrows(IllegalArgumentException.class, () -> ExclusionPatterns.validateSavedPatterns(atLimit));
  }

  @Test
  void RF55MalformedObjectNeverWidensToGlobOrRoot() {
    for (Object bad : List.of(Map.of("literal", "/docs/file"),
        Map.of("literal", Map.of("version", 2, "id", TOKEN, "path", "/docs/file", "scope", "FILE")),
        Map.of("literal", Map.of("version", 1.0, "id", TOKEN, "path", "/docs/file", "scope", "FILE")),
        Map.of("literal", Map.of("version", 1, "id", "caller-token", "path", "/docs/file", "scope", "FILE")),
        literal("relative.txt", "FILE"), literal("/docs/file", "UNKNOWN"))) {
      String raw = JSON.writeValueAsString(List.of(bad));
      assertThrows(IllegalArgumentException.class, () -> ExclusionPatterns.fromRawJsonStrict(raw, false));
    }
  }
}
