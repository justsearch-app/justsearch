package io.justsearch.app.services.indexing;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.excludes.ExclusionPatterns;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for .gitignore-like bare pattern normalization in {@link ExcludeGlobs}.
 *
 * <p>Bare patterns (without explicit {@code ** /} prefix) are expanded so that average users who
 * type {@code node_modules} instead of {@code ** /node_modules/**} get the expected behavior.
 */
class ExcludeGlobsBarePatternTest {

  // --- expandBarePattern unit tests ---

  @Test
  void bareName_expandsToDirectoryAndFile() {
    List<String> result = ExclusionPatterns.expandBarePattern("dist");
    assertEquals(List.of("**/dist/**", "**/dist"), result);
  }

  @Test
  void bareNameTrailingSlash_expandsToDirectoryOnly() {
    List<String> result = ExclusionPatterns.expandBarePattern("dist/");
    assertEquals(List.of("**/dist/**"), result);
  }

  @Test
  void bareGlobWithoutSlash_prependsDoublestar() {
    List<String> result = ExclusionPatterns.expandBarePattern("*.log");
    assertEquals(List.of("**/*.log"), result);
  }

  @Test
  void alreadyQualifiedPattern_unchanged() {
    List<String> result = ExclusionPatterns.expandBarePattern("**/node_modules/**");
    assertEquals(List.of("**/node_modules/**"), result);
  }

  @Test
  void anchoredPathPattern_unchanged() {
    List<String> result = ExclusionPatterns.expandBarePattern("dist/**");
    assertEquals(List.of("dist/**"), result);
  }

  @Test
  void trailingSlashWithInternalSlash_anchored() {
    List<String> result = ExclusionPatterns.expandBarePattern("src/dist/");
    assertEquals(List.of("src/dist/**"), result);
  }

  @Test
  void dotPrefix_expandsCorrectly() {
    List<String> result = ExclusionPatterns.expandBarePattern(".git");
    assertEquals(List.of("**/.git/**", "**/.git"), result);
  }

  @Test
  void questionMarkGlob_prependsDoublestar() {
    List<String> result = ExclusionPatterns.expandBarePattern("?.tmp");
    assertEquals(List.of("**/?.tmp"), result);
  }

  // --- Integration tests: fromPatterns + matching ---

  @Test
  void bareNodeModules_matchesAtAnyDepth() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("node_modules"));
    Path root = Path.of("/project");

    assertTrue(globs.isExcludedPath(root, root.resolve("node_modules/lodash/index.js")));
    assertTrue(globs.isExcludedPath(root, root.resolve("sub/node_modules/lodash/index.js")));
    assertTrue(globs.isExcludedPath(root, root.resolve("deep/sub/node_modules/pkg/file.js")));
  }

  @Test
  void bareDistSlash_matchesDirectoryContentsAtAnyDepth() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("dist/"));
    Path root = Path.of("/project");

    assertTrue(globs.isExcludedPath(root, root.resolve("dist/bundle.js")));
    assertTrue(globs.isExcludedPath(root, root.resolve("sub/dist/bundle.js")));
  }

  @Test
  void bareStarLog_matchesAtAnyDepth() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("*.log"));
    Path root = Path.of("/project");

    assertTrue(globs.isExcludedPath(root, root.resolve("error.log")));
    assertTrue(globs.isExcludedPath(root, root.resolve("logs/error.log")));
    assertFalse(globs.isExcludedPath(root, root.resolve("src/main.java")));
  }

  @Test
  void bareGit_matchesHiddenDirAtAnyDepth() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of(".git"));
    Path root = Path.of("/project");

    assertTrue(globs.isExcludedPath(root, root.resolve(".git/config")));
    assertTrue(globs.isExcludedPath(root, root.resolve("submod/.git/config")));
  }

  @Test
  void explicitPatterns_stillWork() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("**/vendor/**", "**/*.tmp"));
    Path root = Path.of("/project");

    assertTrue(globs.isExcludedPath(root, root.resolve("vendor/lib/file.php")));
    assertTrue(globs.isExcludedPath(root, root.resolve("sub/vendor/lib/file.php")));
    assertTrue(globs.isExcludedPath(root, root.resolve("cache/data.tmp")));
  }

  @Test
  void nonExcludedFiles_notMatched() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("node_modules", "*.log", "dist/"));
    Path root = Path.of("/project");

    assertFalse(globs.isExcludedPath(root, root.resolve("src/main.java")));
    assertFalse(globs.isExcludedPath(root, root.resolve("README.md")));
    assertFalse(globs.isExcludedPath(root, root.resolve("build.gradle")));
  }

  @Test
  void directoryOptimization_worksWithBarePatterns() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("node_modules"));
    Path root = Path.of("/project");

    // The expanded **/node_modules/** should populate dirNamesAnyDepth optimization
    assertTrue(globs.isExcludedDirectory(root, root.resolve("node_modules")));
    assertTrue(globs.isExcludedDirectory(root, root.resolve("sub/node_modules")));
  }

  // --- matchingPatternIndex tests ---

  @Test
  void matchingPatternIndex_returnsCorrectIndex() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("**/node_modules/**", "**/*.log"));
    Path root = Path.of("/project");

    assertEquals(0, globs.matchingPatternIndex(root, root.resolve("node_modules/x.js")));
    assertEquals(1, globs.matchingPatternIndex(root, root.resolve("src/debug.log")));
    assertEquals(-1, globs.matchingPatternIndex(root, root.resolve("src/main.java")));
  }

  @Test
  void matchingPatternIndex_consistentWithIsExcludedPath() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("node_modules", "*.log"));
    Path root = Path.of("/project");
    Path excluded = root.resolve("node_modules/pkg/index.js");
    Path normal = root.resolve("src/main.java");

    assertTrue(globs.isExcludedPath(root, excluded));
    assertTrue(globs.matchingPatternIndex(root, excluded) >= 0);
    assertFalse(globs.isExcludedPath(root, normal));
    assertEquals(-1, globs.matchingPatternIndex(root, normal));
  }

  // --- matchingDirectoryPatternIndex tests ---

  @Test
  void matchingDirectoryPatternIndex_returnsCorrectIndexForExcludedDir() {
    ExcludeGlobs globs =
        ExcludeGlobs.fromPatterns(List.of("**/node_modules/**", "**/*.log", "**/dist/**"));
    Path root = Path.of("/project");

    // Each directory should resolve to the pattern that would match its contents.
    assertEquals(0, globs.matchingDirectoryPatternIndex(root, root.resolve("node_modules")));
    assertEquals(0, globs.matchingDirectoryPatternIndex(root, root.resolve("sub/node_modules")));
    assertEquals(2, globs.matchingDirectoryPatternIndex(root, root.resolve("dist")));
  }

  @Test
  void matchingDirectoryPatternIndex_returnsMinusOneForNonExcludedDir() {
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("**/node_modules/**", "**/*.log"));
    Path root = Path.of("/project");

    assertEquals(-1, globs.matchingDirectoryPatternIndex(root, root.resolve("src")));
    assertEquals(-1, globs.matchingDirectoryPatternIndex(root, root.resolve("docs")));
  }

  @Test
  void matchingDirectoryPatternIndex_consistentWithBarePatternExpansion() {
    // Bare pattern "build" expands to "**/build/**" + "**/build" — the directory
    // form (index 0 of the expanded list) is what matchingDirectoryPatternIndex
    // should attribute matches to.
    ExcludeGlobs globs = ExcludeGlobs.fromPatterns(List.of("build", "*.log"));
    Path root = Path.of("/project");

    assertEquals(0, globs.matchingDirectoryPatternIndex(root, root.resolve("build")));
    assertEquals(0, globs.matchingDirectoryPatternIndex(root, root.resolve("nested/build")));
    // *.log is a file pattern; it should not match a directory.
    assertEquals(-1, globs.matchingDirectoryPatternIndex(root, root.resolve("logs")));
  }
}
