package io.justsearch.indexerworker.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 418 B-H.1 + tempdoc 410 §13 Slice B — exercises the shared skip rules directly. The
 * rules used to live inside {@code WorkerIngestionAuthority} and were tested only through it;
 * B-H.1 promoted them to {@link IngestionSkipPolicy}; Slice B made the rule sets operator-
 * configurable via an installable singleton. Tests reset the singleton after each case so an
 * earlier test's override doesn't leak.
 */
final class IngestionSkipPolicyTest {

  @AfterEach
  void resetPolicy() {
    IngestionSkipPolicy.resetToDefaults();
  }

  @Test
  void skipsHiddenFiles() {
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get(".secret-notes")));
  }

  @Test
  void directoryExclusionsAreRootRelativeAndOverrideExemptFileNames() {
    IngestionSkipPolicy.installResolved(new IngestionSkipPolicy(null, null, Set.of("private")));
    Path root = Path.of("private", "watched").toAbsolutePath();
    assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve("private/notes.txt"), root));
    assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve("private/.env"), root));
    assertFalse(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolve("public/notes.txt"), root));
    assertTrue(IngestionSkipPolicy.shouldSkipWithinRoot(root.resolveSibling("outside.txt"), root));
  }

  @Test
  void allowsDotEnv() {
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get(".env")));
  }

  @Test
  void allowsDotGitignore() {
    // observations.md `#181` fix: the hidden-file exemption for ".gitignore" was previously
    // shadowed by the name.contains(".git") rule in SKIP_PATTERNS, leaving .gitignore filtered.
    // The fix routes both .env and .gitignore through an explicit EXEMPT_NAMES set that
    // short-circuits ALL skip rules, so the exemption is now authoritative.
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get(".gitignore")));
  }

  @Test
  void exemptNamesAreCaseInsensitive() {
    // EXEMPT_NAMES is consulted after the path basename is lowercased, so any case form
    // of an exempt name is preserved.
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get(".GitIgnore")));
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get(".ENV")));
  }

  @Test
  void exemptNamesAreNotShadowedByCustomSkipPatterns() {
    // Operator override: even when a custom skipPatterns set redundantly contains ".git",
    // the EXEMPT_NAMES short-circuit still preserves .gitignore. Tied to the #181 fix.
    IngestionSkipPolicy custom =
        new IngestionSkipPolicy(Set.of(".git", "thumbs.db"), null, null);
    IngestionSkipPolicy.installResolved(custom);
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get(".gitignore")));
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get(".env")));
    // Regression coverage: .git itself (the file/directory) is still skipped.
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get(".git")));
  }

  @Test
  void skipsOfficeAutoSaveAndTempFiles() {
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("~$Document.docx")));
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("draft.tmp")));
  }

  @Test
  void skipsBuildOutputExtensions() {
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("module.pyc")));
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("Hello.class")));
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("native.obj")));
  }

  @Test
  void skipsSystemFiles() {
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("Thumbs.db")));
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get(".DS_Store")));
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("desktop.ini")));
  }

  @Test
  void allowsRegularDocuments() {
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get("notes.md")));
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get("Report 2026.docx")));
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get("source.java")));
  }

  @Test
  void shouldSkipHandlesNullDefensively() {
    assertFalse(IngestionSkipPolicy.shouldSkip(null));
  }

  @Test
  void shouldSkipHandlesPathWithoutFileNameDefensively() {
    assertFalse(IngestionSkipPolicy.shouldSkip(Path.of("/")));
  }

  @Test
  void skipsKnownVcsAndCacheDirectoryNames() {
    assertTrue(IngestionSkipPolicy.isSkippedDirectoryName(".git"));
    assertTrue(IngestionSkipPolicy.isSkippedDirectoryName("node_modules"));
    assertTrue(IngestionSkipPolicy.isSkippedDirectoryName("__pycache__"));
    assertTrue(IngestionSkipPolicy.isSkippedDirectoryName("$RECYCLE.BIN"));
    assertTrue(IngestionSkipPolicy.isSkippedDirectoryName("System Volume Information"));
  }

  @Test
  void allowsRegularDirectoryNames() {
    assertFalse(IngestionSkipPolicy.isSkippedDirectoryName("documents"));
    assertFalse(IngestionSkipPolicy.isSkippedDirectoryName("src"));
  }

  @Test
  void isSkippedDirectoryNameHandlesNullDefensively() {
    assertFalse(IngestionSkipPolicy.isSkippedDirectoryName(null));
  }

  @Test
  void installResolvedReplacesActiveInstanceForFileNameRules() {
    IngestionSkipPolicy custom =
        new IngestionSkipPolicy(
            Set.of("forbidden-marker"),
            Set.of("foo", "bar"),
            null);
    IngestionSkipPolicy.installResolved(custom);

    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("contains-forbidden-marker.txt")),
        "Custom skip pattern must match");
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("module.foo")),
        "Custom skip extension must match");
    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("data.bar")),
        "Custom skip extension must match");
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get("module.pyc")),
        "Default .pyc no longer in the active set after install");
    assertFalse(IngestionSkipPolicy.shouldSkip(Paths.get("Thumbs.db")),
        "Default thumbs.db no longer in the active set after install");
  }

  @Test
  void installResolvedReplacesActiveInstanceForDirectoryRules() {
    IngestionSkipPolicy custom =
        new IngestionSkipPolicy(null, null, Set.of("vendor", "third_party"));
    IngestionSkipPolicy.installResolved(custom);

    assertTrue(IngestionSkipPolicy.isSkippedDirectoryName("vendor"));
    assertTrue(IngestionSkipPolicy.isSkippedDirectoryName("third_party"));
    assertFalse(IngestionSkipPolicy.isSkippedDirectoryName("node_modules"),
        "Default node_modules no longer in the active set after install");
  }

  @Test
  void resetToDefaultsRestoresBuiltInBehaviour() {
    IngestionSkipPolicy.installResolved(
        new IngestionSkipPolicy(Set.of("only-this"), Set.of("xyz"), Set.of("nope")));
    IngestionSkipPolicy.resetToDefaults();

    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("module.pyc")),
        "Default .pyc rule is back");
    assertTrue(IngestionSkipPolicy.isSkippedDirectoryName("node_modules"),
        "Default node_modules rule is back");
  }

  @Test
  void installResolvedNullArgumentRestoresDefaults() {
    IngestionSkipPolicy.installResolved(
        new IngestionSkipPolicy(Set.of("only-this"), null, null));
    IngestionSkipPolicy.installResolved(null);

    assertTrue(IngestionSkipPolicy.shouldSkip(Paths.get("module.pyc")),
        "Null install arg restores defaults");
  }

  @Test
  void instanceConstructorTrimsAndLowercasesInputs() {
    IngestionSkipPolicy custom =
        new IngestionSkipPolicy(Set.of("  MARKER  ", "", "Other"), null, null);

    // Path matching is case-insensitive (input lowercased), and the trim removed the spaces
    // around MARKER so it matches as "marker" inside the filename.
    assertTrue(custom.shouldSkipName(Paths.get("file-with-marker-suffix.txt")));
    assertTrue(custom.shouldSkipName(Paths.get("file-with-other.txt")));
    assertFalse(custom.shouldSkipName(Paths.get("clean.txt")));
  }
}
