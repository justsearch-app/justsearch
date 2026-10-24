/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.deadcode;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The one repo-wide system-access funnel (tempdoc 883 decision 5).
 *
 * <p><b>What it asserts.</b> Every read of process-global state — {@code System.getenv},
 * {@code System.getProperty/setProperty/clearProperty}, and their two disguises
 * {@code Boolean.getBoolean} / {@code Integer.getInteger}, which are {@code System.getProperty}
 * with a parse — goes through {@code io.justsearch.configuration}, whose {@code EnvRegistry} /
 * {@code SystemAccess} / {@code ResolvedConfigBuilder} are the sanctioned funnel. Every site
 * outside that package must be listed in {@code gates/config-surface/sysaccess-allowlist.txt},
 * which is a RATCHET: a new site fails, and an entry whose site is gone must be deleted.
 *
 * <p><b>Why here.</b> This replaces six per-module ArchUnit rules
 * ({@code app-api/ArchitectureRulesTest}, {@code core/ArchUnitSanityTest},
 * {@code app-services/AppServicesWorkerGuardrailsTest}, {@code indexer-worker/IndexerWorkerGuardrailsTest},
 * {@code ui/UiApiGuardrailsTest}, {@code adapters-lucene/AdaptersLuceneGuardrailsTest}) whose
 * union left {@code telemetry}, {@code app-inference}, {@code gpu-bridge}, {@code ai-backend},
 * {@code app-launcher}, {@code ort-common}, {@code worker-services}, {@code benchmarks} and
 * {@code ssot-tools} uncovered — the modules holding a large share of the actual call sites. Six
 * rules that each had to be remembered when a module was added is the shape that produced that
 * gap; this module already imports every production module for the dead-code analysis, so it is
 * the one place where "repo-wide" is a property of the classpath rather than of a list someone
 * maintains.
 *
 * <p><b>Coverage is deliberately WIDER than the six rules it replaces.</b> Only
 * {@code UiApiGuardrailsTest} covered {@code clearProperty}, none covered
 * {@code Boolean.getBoolean} / {@code Integer.getInteger}, and one missed the no-arg
 * {@code System.getenv()}. Those gaps are closed here rather than reproduced; the extra sites are
 * seeded into the allowlist like the rest, so the widening costs entries, not red builds.
 *
 * <p><b>Honest limits.</b> (1) The allowlist RECORDS sites; it does not bless them. Shrinking it is
 * the point — every entry is a consumer that should be reading {@code ResolvedConfig} instead.
 * (2) Reflective access ({@code Method.invoke} onto {@code System}) is invisible to a bytecode
 * scan; none exists in this repo today. (3) The scan sees only classes the audited-module list in
 * {@code build.gradle.kts} puts on the classpath, so a NEW module is covered only once it is added
 * there — the same dependency the whole-program dead-code analysis already has.
 */
class SystemAccessFunnelTest extends ImportedProgram {

  /** The sanctioned funnel. A call whose ORIGIN is in this package is the funnel, not a bypass. */
  private static final String FUNNEL_PACKAGE_PREFIX = "io.justsearch.configuration.";

  private static final String ALLOWLIST_RELATIVE_PATH = "gates/config-surface/sysaccess-allowlist.txt";

  /** Owner class -> the members on it that read or write process-global state. */
  private static final Map<String, Set<String>> BANNED =
      Map.of(
          "java.lang.System",
              Set.of("getenv", "getProperty", "setProperty", "clearProperty", "getProperties",
                  "setProperties"),
          // Boolean.getBoolean(String) and Integer.getInteger(String) are System.getProperty with
          // a parse attached. Banning getProperty while leaving these open would be a funnel with
          // a documented hole in it.
          "java.lang.Boolean", Set.of("getBoolean"),
          "java.lang.Integer", Set.of("getInteger"));

  @Test
  void every_system_access_outside_the_configuration_funnel_is_on_the_allowlist() {
    Set<String> observed = observedSites(ImportedProgram.classes());
    Set<String> allowed = readAllowlist();

    List<String> newSites = new ArrayList<>(observed);
    newSites.removeAll(allowed);

    List<String> staleEntries = new ArrayList<>(allowed);
    staleEntries.removeAll(observed);

    // Written unconditionally: when the assertion below fails, the fix is nearly always "this line
    // belongs on the list" or "this line no longer does", and having the full observed set on disk
    // makes that a diff rather than a transcription exercise.
    writeObserved(observed);

    if (!newSites.isEmpty()) {
      fail(
          "New direct system-access site(s) outside "
              + FUNNEL_PACKAGE_PREFIX
              + " ("
              + newSites.size()
              + "):\n  "
              + String.join("\n  ", newSites)
              + "\n\nRoute the value through io.justsearch.configuration: declare it in EnvRegistry"
              + " and read it from ResolvedConfig (or SystemAccess for a genuinely process-global"
              + " one). Adding a line to "
              + ALLOWLIST_RELATIVE_PATH
              + " is NOT the fix — that file is a ratchet that only shrinks, and growing it is"
              + " reviewed as a regression (config-surface/sysaccess-allowlist-growth)."
              + "\nThe full observed set was written to tmp/sysaccess-observed.txt.");
    }

    if (!staleEntries.isEmpty()) {
      fail(
          "Allowlist entr(ies) with no matching call site ("
              + staleEntries.size()
              + ") — delete these lines from "
              + ALLOWLIST_RELATIVE_PATH
              + ":\n  "
              + String.join("\n  ", staleEntries)
              + "\n\nA stale entry is residue that outlives its reason: it silently pre-authorises"
              + " a future call site at that exact coordinate, which is the opposite of a ratchet.");
    }

    // A green that could also be produced by an importer returning nothing is not a green. The
    // repo has well over a hundred of these sites; a collapse to zero means the scan broke.
    assertTrue(
        observed.size() > 50,
        "Observed only "
            + observed.size()
            + " system-access sites — the whole-program import probably failed rather than the"
            + " repo suddenly becoming clean.");
  }

  /** Every `owner#member` outside the funnel that touches process-global state. */
  private static Set<String> observedSites(JavaClasses classes) {
    Set<String> sites = new TreeSet<>();
    for (JavaClass clazz : classes) {
      for (JavaAccess<?> access : clazz.getAccessesFromSelf()) {
        Set<String> banned = BANNED.get(access.getTargetOwner().getFullName());
        if (banned == null || !banned.contains(access.getTarget().getName())) {
          continue;
        }
        JavaCodeUnit origin = access.getOrigin();
        String owner = origin.getOwner().getFullName();
        if (owner.startsWith(FUNNEL_PACKAGE_PREFIX)) {
          continue;
        }
        sites.add(owner + "#" + normalizeMember(origin.getName()));
      }
    }
    return sites;
  }

  /**
   * Collapse a synthetic lambda body back onto the method that declares it.
   *
   * <p>javac names a lambda body {@code lambda$enclosingMethod$3}, and that index shifts whenever
   * an unrelated lambda is added or removed earlier in the class. Pinning the raw name would make
   * the ratchet fail on edits that changed nothing about system access — a ratchet that cries wolf
   * gets deleted, which is the failure mode this whole gate family exists to avoid.
   */
  private static String normalizeMember(String member) {
    if (!member.startsWith("lambda$")) {
      return member;
    }
    int lastDollar = member.lastIndexOf('$');
    String enclosing = member.substring("lambda$".length(), lastDollar < 0 ? member.length() : lastDollar);
    // `lambda$new$0` in a constructor, `lambda$static$0` in a static initialiser.
    return enclosing.isEmpty() ? member : enclosing;
  }

  /**
   * The allowlist path, preferring the one Gradle resolved.
   *
   * <p>`build.gradle.kts` declares the file as a task input (so a change to it re-runs this test —
   * without that, Gradle called the task up to date and the ratchet silently stopped running) and
   * passes the absolute path here, which also removes any dependence on the working directory. The
   * walk-up below is the fallback for running the test outside Gradle.
   */
  private static Path allowlistPath() {
    String fromGradle = System.getProperty("sysaccess.allowlistPath");
    if (fromGradle != null && !fromGradle.isBlank()) {
      return Paths.get(fromGradle);
    }
    return repoRoot().resolve(ALLOWLIST_RELATIVE_PATH);
  }

  private static Set<String> readAllowlist() {
    Path path = allowlistPath();
    Set<String> entries = new LinkedHashSet<>();
    try {
      for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        entries.add(line);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + path, e);
    }
    return entries;
  }

  private static void writeObserved(Set<String> observed) {
    try {
      Path out = repoRoot().resolve("tmp").resolve("sysaccess-observed.txt");
      Files.createDirectories(out.getParent());
      Files.writeString(out, String.join("\n", observed) + "\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Walk up from the working directory to the tree that contains the allowlist.
   *
   * <p>Deliberately NOT {@code RepoRootLocator.findRepoRoot()}: that consults
   * {@code JUSTSEARCH_REPO_ROOT} / {@code JUSTSEARCH_SSOT_PATH} first, so in a parallel-agent
   * worktree it can resolve to a DIFFERENT checkout and ratchet this branch against another
   * branch's allowlist. Anchoring on the file itself makes the answer worktree-local by
   * construction.
   */
  private static Path repoRoot() {
    Path p = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 10 && p != null; i++) {
      if (Files.exists(p.resolve(ALLOWLIST_RELATIVE_PATH))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException(
        "repo root with " + ALLOWLIST_RELATIVE_PATH + " not found from "
            + Paths.get("").toAbsolutePath());
  }
}
