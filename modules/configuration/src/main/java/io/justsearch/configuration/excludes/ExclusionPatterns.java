/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.excludes;

import io.justsearch.configuration.resolved.ConfigStore;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ExclusionPatterns
 *
 * <p>Small helper to interpret UI-provided exclude patterns (glob strings) against paths under a watched root.
 *
 * <p>v1 semantics:
 * - Patterns are matched against the root-relative path (with forward slashes).
 * - Users can use {@code ** /} (double-star + slash) to match at any depth (gitignore-like).
 * - Matching is case-insensitive on Windows.
 *
 * <p>Source of truth: the resolved {@code justsearch.ui.exclude_patterns} (JSON string array),
 * reached via {@code ResolvedConfig.Ui#excludePatterns} — the user's settings.json contributes it
 * at ordinal 300, an operator's env var / {@code -D} at 400 / 500. Explicit factories compile
 * captured plans; {@link #current()} reads the live policy for admission and processing.
 */
public final class ExclusionPatterns {

  private static final ObjectMapper JSON = new ObjectMapper();
  private record Cached(String raw, ExclusionPatterns patterns) {}
  private static volatile Cached cached = new Cached("", empty());

  /** Reads the live resolved policy, including operator overrides, on each admission. */
  public static ExclusionPatterns current() {
    ConfigStore store = ConfigStore.globalOrNull();
    String raw = store == null ? "" : store.get().ui().excludePatterns();
    Cached observed = cached;
    if (java.util.Objects.equals(raw, observed.raw())) return observed.patterns();
    ExclusionPatterns compiled = fromRawJsonStrict(raw, isWindows());
    cached = new Cached(raw, compiled);
    return compiled;
  }

  /** Validate new saves without changing how older settings files deserialize. */
  public static void validateSavedPatterns(List<String> patterns) {
    if (patterns == null) return;
    if (patterns.size() > 512) throw new IllegalArgumentException("At most 512 exclude patterns are allowed");
    ExclusionPatterns compiled = fromPatterns(patterns);
    if (!compiled.invalidPatterns().isEmpty()) {
      throw new IllegalArgumentException("Invalid exclude pattern: " + compiled.invalidPatterns().getFirst());
    }
    if (compiled.patterns().size() > 512) {
      throw new IllegalArgumentException("Exclude patterns expand beyond the limit of 512");
    }
  }

  public static ExclusionPatterns fromRawJsonStrict(String raw, boolean windows) {
    if (raw == null || raw.isBlank()) return fromPatterns(List.of(), windows);
    var array = JSON.readTree(raw);
    if (!array.isArray()) throw new IllegalArgumentException("Exclude policy must be a string array");
    List<String> patterns = new ArrayList<>();
    for (var entry : array) {
      if (!entry.isString()) throw new IllegalArgumentException("Exclude policy must be a string array");
      patterns.add(entry.asString());
    }
    return fromPatterns(patterns, windows);
  }

  private final List<String> patterns;
  private final List<Pattern> fileMatchers;
  private final List<String> savedPatterns;
  private final List<Integer> savedPatternIndices;
  private final List<String> invalidPatterns;

  private ExclusionPatterns(List<String> patterns, List<Pattern> fileMatchers,
      List<String> savedPatterns, List<Integer> savedPatternIndices, List<String> invalidPatterns) {
    this.patterns = patterns;
    this.fileMatchers = fileMatchers;
    this.savedPatterns = savedPatterns;
    this.savedPatternIndices = savedPatternIndices;
    this.invalidPatterns = invalidPatterns;
  }

  /** Parses an optional operator-provided JSON list for observational callers. */
  public static ExclusionPatterns fromRawJsonArray(String raw) {
    if (raw == null || raw.isBlank()) {
      return empty();
    }
    try {
      List<String> patterns = JSON.readValue(raw, new TypeReference<List<String>>() {});
      return fromPatterns(patterns);
    } catch (Exception e) {
      // Fail open: bad sysprop should not break indexing.
      return empty();
    }
  }

  public static ExclusionPatterns empty() {
    return new ExclusionPatterns(List.of(), List.of(), List.of(), List.of(), List.of());
  }

  public static ExclusionPatterns fromPatterns(List<String> patterns) {
    return fromPatterns(patterns, isWindows());
  }

  public static ExclusionPatterns fromPatterns(List<String> patterns, boolean win) {
    if (patterns == null || patterns.isEmpty()) {
      return new ExclusionPatterns(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    List<String> saved = patterns.stream().filter(java.util.Objects::nonNull).toList();
    LinkedHashSet<String> cleaned = new LinkedHashSet<>();
    List<Pattern> matchers = new ArrayList<>();
    List<Integer> indices = new ArrayList<>();
    List<String> invalid = new ArrayList<>();
    int flags = win ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
    for (int index = 0; index < saved.size(); index++) {
      String original = saved.get(index);
      String normalized = original.trim().replace('\\', '/');
      if (normalized.isBlank()) continue;
      List<String> expanded = expandBarePattern(normalized);
      List<Pattern> compiled = new ArrayList<>();
      try {
        for (String glob : expanded) compiled.add(Pattern.compile(globToRegex(glob), flags));
      } catch (IllegalArgumentException syntax) {
        // Keep the original invalid entry in captured plans: recompiling an expanded invalid glob
        // would turn the injected **/ into part of a literal name on boot or recorded replay.
        invalid.add(original);
        expanded = List.of(normalized);
        compiled = List.of(Pattern.compile(literalNameRegex(normalized), flags));
      }
      for (int i = 0; i < expanded.size(); i++) {
        if (cleaned.add(expanded.get(i))) {
          matchers.add(compiled.get(i));
          indices.add(index);
        }
      }
    }
    return new ExclusionPatterns(List.copyOf(cleaned), List.copyOf(matchers), saved,
        List.copyOf(indices), List.copyOf(invalid));
  }

  /** Diagnostics and labels come from the same compiler used by every admission path. */
  public List<String> invalidPatterns() { return invalidPatterns; }
  public List<String> savedPatterns() { return savedPatterns; }

  public int matchingSavedPatternIndex(Path root, Path path) {
    int index = matchingPatternIndex(root, path);
    return index < 0 ? -1 : savedPatternIndices.get(index);
  }

  private static String literalNameRegex(String name) {
    boolean directory = name.endsWith("/");
    String base = directory ? name.substring(0, name.length() - 1) : name;
    return "^" + (base.contains("/") ? "" : "(?:.*/)?") + Pattern.quote(base)
        + (directory ? "/.*" : "(?:/.*)?") + "$";
  }

  public boolean isEmpty() {
    return patterns.isEmpty();
  }

  public List<String> patterns() {
    return patterns;
  }

  /**
   * Returns true if the given directory should be treated as excluded for the purpose of skipping traversal.
   *
   * <p>Note: full exclusion matching is done via {@link #isExcludedPath(Path, Path)}. This method is only used
   * as an optimization for common directory patterns (e.g., {@code ** /node_modules/**}).
   */
  public boolean isExcludedDirectory(Path root, Path dir) {
    if (isEmpty() || root == null || dir == null) return false;
    try {
      Path normalizedRoot = root.toAbsolutePath().normalize();
      Path normalizedDir = dir.toAbsolutePath().normalize();
      if (!normalizedDir.startsWith(normalizedRoot) || normalizedDir.equals(normalizedRoot)) return false;
      String descendant = normalizeRel(normalizedRoot.relativize(normalizedDir).toString()) + "/_";
      for (int i = 0; i < patterns.size(); i++) {
        // Only a descendant wildcard authorises skipping/deleting an entire subtree.
        // Reuse the file matcher so glob syntax and Unicode case rules stay identical.
        if (patterns.get(i).endsWith("/**") && fileMatchers.get(i).matcher(descendant).matches()) return true;
      }
      return false;
    } catch (Exception invalid) {
      return false;
    }
  }

  /** Returns true if the given path (file) is excluded by any configured glob. */
  public boolean isExcludedPath(Path root, Path path) {
    return matchingPatternIndex(root, path) >= 0;
  }

  /** Returns the index of the first pattern that would match files inside this directory, or -1. */
  public int matchingDirectoryPatternIndex(Path root, Path dir) {
    return matchingPatternIndex(root, dir.resolve("_"));
  }

  /** Returns the index of the first matching pattern for this path, or -1 if none match. */
  public int matchingPatternIndex(Path root, Path path) {
    if (isEmpty() || root == null || path == null) return -1;
    try {
      Path normalizedRoot = root.toAbsolutePath().normalize();
      Path normalizedPath = path.toAbsolutePath().normalize();
      if (!normalizedPath.startsWith(normalizedRoot)) return -1;
      Path rel = normalizedRoot.equals(normalizedPath)
          ? normalizedPath.getFileName() : normalizedRoot.relativize(normalizedPath);
      if (rel == null) return -1;
      String relStr = normalizeRel(rel.toString());
      if (relStr.isBlank()) return -1;
      for (int i = 0; i < fileMatchers.size(); i++) {
        if (fileMatchers.get(i).matcher(relStr).matches()) return i;
      }
      return -1;
    } catch (Exception e) {
      return -1;
    }
  }

  private static boolean isWindows() {
    try {
      String os = System.getProperty("os.name", "");
      return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean containsMeta(String s) {
    return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0 || s.indexOf('{') >= 0;
  }

  private static String normalizeRel(String rel) {
    if (rel == null) return "";
    String s = rel.replace('\\', '/');
    // Avoid accidental leading "./"
    while (s.startsWith("./")) {
      s = s.substring(2);
    }
    // No trailing slash for matching
    if (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  /**
   * Expands bare patterns to match at any depth, following .gitignore-like conventions.
   *
   * <ul>
   *   <li>{@code dist} (no slash, no meta) → {@code ** /dist/**} + {@code ** /dist}
   *   <li>{@code dist/} (trailing slash only) → {@code ** /dist/**}
   *   <li>{@code *.log} (no slash, has meta) → {@code ** /*.log}
   *   <li>{@code ** /dist/**} (already qualified) → unchanged
   *   <li>{@code dist/**} (has slash) → unchanged (anchored to root)
   * </ul>
   */
  public static List<String> expandBarePattern(String pattern) {
    // Trailing slash only, no other slashes: "dist/" → "**/dist/**"
    if (pattern.endsWith("/")) {
      String base = pattern.substring(0, pattern.length() - 1);
      if (base.equals("**")) return List.of("**/**");
      if (!base.contains("/")) {
        return List.of("**/" + base + "/**");
      }
      // Internal slashes like "src/dist/" → "src/dist/**"
      return List.of(base + "/**");
    }
    if (pattern.startsWith("**/")) return List.of(pattern);
    // No slash at all
    if (!pattern.contains("/")) {
      if (containsMeta(pattern)) {
        // *.log → **/*.log
        return List.of("**/" + pattern);
      }
      // dist → **/dist/** + **/dist
      return List.of("**/" + pattern + "/**", "**/" + pattern);
    }
    // Has slashes (e.g., dist/**, src/vendor/**) — anchored, leave as-is
    return List.of(pattern);
  }

  /** Converts a subset of glob syntax into a regex anchored to the whole string. */
  private static String globToRegex(String glob) {
    String g = glob == null ? "" : glob;
    java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + g);
    boolean group = false;
    // Always match the entire relative path string.
    StringBuilder out = new StringBuilder(g.length() * 2);
    out.append('^');
    for (int i = 0; i < g.length(); i++) {
      char c = g.charAt(i);
      if (c == '*') {
        boolean isDouble = (i + 1 < g.length()) && g.charAt(i + 1) == '*';
        if (isDouble) {
          // Special case: "**/" should match zero or more directories (gitignore-like).
          boolean hasSlash = (i + 2 < g.length()) && g.charAt(i + 2) == '/';
          if (hasSlash) {
            out.append("(?:.*/)?");
            i += 2; // Skip both '*' and the following '/'
          } else {
            out.append(".*");
            i++;
          }
        } else {
          out.append("[^/]*");
        }
        continue;
      }
      if (c == '?') {
        out.append("[^/]");
        continue;
      }
      if (c == '[') {
        int end = g.indexOf(']', i + 1);
        if (end < 0) throw new IllegalArgumentException("Unclosed character class in exclude pattern");
        String characters = g.substring(i + 1, end);
        out.append("(?=[^/])[");
        if (characters.startsWith("!")) {
          out.append('^');
          characters = characters.substring(1);
        } else if (characters.startsWith("^")) {
          out.append("\\^");
          characters = characters.substring(1);
        }
        out.append(characters.replace("&", "\\&")).append(']');
        i = end;
        continue;
      }
      if (c == '{') { out.append("(?:"); group = true; continue; }
      if (c == '}' && group) { out.append(')'); group = false; continue; }
      if (c == ',' && group) { out.append('|'); continue; }
      // Escape regex meta characters.
      if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
        out.append('\\');
      }
      out.append(c);
    }
    out.append('$');
    return out.toString();
  }
}
