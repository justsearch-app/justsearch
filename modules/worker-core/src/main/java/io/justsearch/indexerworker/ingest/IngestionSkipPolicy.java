/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Skip rules shared by every ingestion entry point — Head walkers, Worker scans, sync-directory
 * passes, and the IndexingLoop admission gate. Centralising the rules eliminates the prior
 * duplication between {@code WorkerIngestionAuthority} and {@code SyncDirectoryOps}, and gives
 * Worker-owned scans the same admission filter the in-process loop applies.
 *
 * <p>Operator override: the static methods route through an installable singleton so Worker boot
 * (in {@code DefaultWorkerAppServices}) can publish a runtime-resolved instance built from
 * {@code EnvRegistry.INGESTION_SKIP_PATTERNS} / {@code INGESTION_SKIP_EXTENSIONS} /
 * {@code INGESTION_SKIP_DIRECTORY_NAMES}. Tests reset to the built-in defaults via
 * {@link #installResolved} {@code (defaultPolicy())} or {@link #resetToDefaults()}. The
 * worker-core module deliberately does not depend on {@code configuration}; the resolved values
 * are pushed in from {@code worker-services}.
 */
public final class IngestionSkipPolicy {

  /** Default file-name fragments that mark a path as system/junk. Compared lowercase. */
  private static final Set<String> DEFAULT_SKIP_PATTERNS =
      Set.of("thumbs.db", ".ds_store", "desktop.ini", ".git", ".svn", "$recycle.bin");

  /**
   * File names that are explicitly preserved even when other rules would skip them.
   * observations.md `#181` fix: the dot-prefix branch at the top of
   * {@link #shouldSkipName} exempts {@code .gitignore} (and {@code .env}), but the
   * trailing {@code skipPatterns.contains(name::contains)} substring check would
   * subsequently re-skip {@code .gitignore} because the {@code .git} pattern is a
   * substring of the file name. Centralizing the exempt set and short-circuiting
   * before any skip rule fires makes the exemption authoritative.
   */
  private static final Set<String> EXEMPT_NAMES = Set.of(".env", ".gitignore");

  /** Default file extensions that mark a path as build/cache output. Lowercase, no leading dot. */
  private static final Set<String> DEFAULT_SKIP_EXTENSIONS =
      Set.of("pyc", "pyo", "class", "o", "obj");

  /** Default directory basenames a tree walk should never descend into. Compared lowercase. */
  private static final Set<String> DEFAULT_SKIP_DIRECTORY_NAMES =
      Set.of(
          "$recycle.bin",
          "system volume information",
          ".git",
          ".svn",
          ".hg",
          ".bzr",
          "cvs",
          "node_modules",
          "bower_components",
          "__pycache__",
          ".tox",
          ".pytest_cache",
          ".mypy_cache");

  private static final IngestionSkipPolicy DEFAULT =
      new IngestionSkipPolicy(
          DEFAULT_SKIP_PATTERNS, DEFAULT_SKIP_EXTENSIONS, DEFAULT_SKIP_DIRECTORY_NAMES);

  private static final AtomicReference<IngestionSkipPolicy> INSTANCE = new AtomicReference<>(DEFAULT);

  private final Set<String> skipPatterns;
  private final Set<String> skipExtensions;
  private final Set<String> skipDirectoryNames;

  /**
   * Constructs an instance with explicit pattern sets. All inputs are lowercased + deduplicated;
   * null inputs fall back to the built-in defaults so partial-override callers don't need to
   * carry the full default set themselves.
   */
  public IngestionSkipPolicy(
      Set<String> skipPatterns,
      Set<String> skipExtensions,
      Set<String> skipDirectoryNames) {
    this.skipPatterns = normalise(skipPatterns, DEFAULT_SKIP_PATTERNS);
    this.skipExtensions = normalise(skipExtensions, DEFAULT_SKIP_EXTENSIONS);
    this.skipDirectoryNames = normalise(skipDirectoryNames, DEFAULT_SKIP_DIRECTORY_NAMES);
  }

  /** The built-in default policy (pre-Slice-B behaviour). Useful for tests + as a base. */
  public static IngestionSkipPolicy defaultPolicy() {
    return DEFAULT;
  }

  /** Replaces the active singleton. {@code DefaultWorkerAppServices} calls this at boot. */
  public static void installResolved(IngestionSkipPolicy policy) {
    INSTANCE.set(policy == null ? DEFAULT : policy);
  }

  /** Restores the built-in defaults. Tests should call this in {@code @AfterEach}. */
  public static void resetToDefaults() {
    INSTANCE.set(DEFAULT);
  }

  /** Returns the active singleton (for callers that want to inspect the current config). */
  public static IngestionSkipPolicy active() {
    return INSTANCE.get();
  }

  /**
   * Returns {@code true} when the file name matches a hidden / temp / system / build-output rule.
   * The check is name-only — it does not stat the file or look at its parent directories.
   */
  public static boolean shouldSkip(Path path) {
    return INSTANCE.get().shouldSkipName(path);
  }

  /** Applies directory exclusions below the admitting root through the file's parent. */
  public static boolean shouldSkipWithinRoot(Path path, Path root) {
    if (shouldSkip(path)) return true;
    if (path == null || root == null) return false;
    Path absolute = path.toAbsolutePath().normalize();
    Path boundary = root.toAbsolutePath().normalize();
    if (!absolute.startsWith(boundary)) return true;
    for (Path parent = absolute.getParent();
        parent != null && parent.startsWith(boundary) && !parent.equals(boundary);
        parent = parent.getParent()) {
      if (parent.getFileName() != null
          && isSkippedDirectoryName(parent.getFileName().toString())) return true;
    }
    return false;
  }

  /**
   * Returns {@code true} when a directory basename should not be descended into. The argument is
   * the basename only (not a full path) so callers can pass {@code dir.getFileName().toString()}
   * directly.
   */
  public static boolean isSkippedDirectoryName(String directoryName) {
    return INSTANCE.get().matchesDirectory(directoryName);
  }

  /** Instance form — useful for tests that want to assert against a specific policy without
   * touching the global singleton. */
  public boolean shouldSkipName(Path path) {
    if (path == null || path.getFileName() == null) {
      return false;
    }
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    // observations.md `#181`: explicit-exempt set short-circuits ALL skip rules,
    // including the substring `skipPatterns` check below. Without this, `.gitignore`
    // would be exempt at the dot-prefix branch but re-skipped because `.git` is a
    // substring of the name.
    if (EXEMPT_NAMES.contains(name)) {
      return false;
    }
    if (name.startsWith(".")) {
      return true;
    }
    if (name.startsWith("~$") || name.startsWith("~") || name.endsWith(".tmp")) {
      return true;
    }
    int dot = name.lastIndexOf('.');
    if (dot >= 0 && skipExtensions.contains(name.substring(dot + 1))) {
      return true;
    }
    return skipPatterns.stream().anyMatch(name::contains);
  }

  /** Instance form for the directory-name check. */
  public boolean matchesDirectory(String directoryName) {
    if (directoryName == null) {
      return false;
    }
    return skipDirectoryNames.contains(directoryName.toLowerCase(Locale.ROOT));
  }

  /** Read-only view of the active skip-pattern set. */
  public Set<String> skipPatterns() {
    return skipPatterns;
  }

  /** Read-only view of the active skip-extension set. */
  public Set<String> skipExtensions() {
    return skipExtensions;
  }

  /** Read-only view of the active skip-directory-name set. */
  public Set<String> skipDirectoryNames() {
    return skipDirectoryNames;
  }

  private static Set<String> normalise(Set<String> input, Set<String> fallback) {
    if (input == null) {
      return fallback;
    }
    Set<String> out = new LinkedHashSet<>();
    for (String value : input) {
      if (value == null) continue;
      String trimmed = value.trim().toLowerCase(Locale.ROOT);
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out.isEmpty() ? fallback : Set.copyOf(out);
  }
}
