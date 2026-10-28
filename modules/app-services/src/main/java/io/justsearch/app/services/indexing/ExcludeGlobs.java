/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.indexing;

import io.justsearch.configuration.excludes.ExclusionPatterns;
import java.nio.file.Path;
import java.util.List;

/** Preview/Apply projection of the shared root-relative exclusion matcher. */
public final class ExcludeGlobs {
  private final ExclusionPatterns patterns;

  private ExcludeGlobs(ExclusionPatterns patterns) { this.patterns = patterns; }

  public static ExcludeGlobs fromRawJsonArray(String raw) {
    return new ExcludeGlobs(ExclusionPatterns.fromRawJsonArray(raw));
  }

  public static ExcludeGlobs fromPatterns(List<?> patterns) {
    return new ExcludeGlobs(ExclusionPatterns.fromPatterns(patterns));
  }

  public static ExcludeGlobs empty() { return new ExcludeGlobs(ExclusionPatterns.empty()); }
  public boolean isEmpty() { return patterns.isEmpty(); }
  public List<Object> entries() { return patterns.savedPatterns(); }
  public List<String> patterns() { return patterns.savedPatterns().stream().map(entry ->
      entry instanceof String glob ? glob
          : "Exact " + ((io.justsearch.configuration.excludes.LiteralExclusion) entry).literal().scope()
              .name().toLowerCase(java.util.Locale.ROOT) + ": "
              + ((io.justsearch.configuration.excludes.LiteralExclusion) entry).literal().path()).toList(); }
  public boolean isExcludedDirectory(Path root, Path dir) { return patterns.isExcludedDirectory(root, dir); }
  public boolean isExcludedPath(Path root, Path path) { return patterns.isExcludedPath(root, path); }
  public int matchingDirectoryPatternIndex(Path root, Path dir) { return patterns.matchingSavedPatternIndex(root, dir.resolve("_")); }
  public int matchingPatternIndex(Path root, Path path) { return patterns.matchingSavedPatternIndex(root, path); }
}
