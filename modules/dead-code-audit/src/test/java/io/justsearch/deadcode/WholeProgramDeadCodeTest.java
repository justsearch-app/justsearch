/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.deadcode;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 638 — whole-program closed-world dead-class rule.
 *
 * <p>Unlike {@code UnreferencedCodeTest} (which runs on app-launcher's <em>Head-process</em>
 * classpath and only inspects private/package-private members), this test imports the union of
 * <em>every</em> production module's bytecode (this module depends on all of them) and inspects
 * members of <em>all</em> visibilities. In a closed application a symbol with zero callers across
 * the whole program is dead regardless of visibility — the library-style public exclusion does not
 * apply here.
 *
 * <p><b>Ratcheting (tempdoc 930 chunk F).</b> This used to be a report-only test that wrote
 * {@code tmp/dead-code-jvm-report.json} for a bespoke governance gate to ratchet against
 * {@code gates/dead-code-jvm/baseline.txt}. Both are gone: the rule is now an ordinary
 * {@link ArchRule} wrapped in ArchUnit's own {@link FreezingArchRule}, whose violation store
 * ({@code modules/dead-code-audit/archunit_store/}, committed) IS the baseline. A new dead class
 * fails the build; removing one and re-running shrinks the store automatically. Same ratchet, an
 * upstream implementation, and it fails in the test task that already builds the classpath instead
 * of in a separate CI step reading a JSON side-effect.
 *
 * <p>The deliberate <b>reachability roots</b> (the boundary where callers come from outside the
 * analysed bytecode — framework dispatch, serialization, JNI, entry points, published API) are
 * encoded as the {@code isRoot*} skips below, mirroring GraalVM native-image's
 * reachability-metadata categories (reflection / JNI / serialization / proxies / entry points).
 */
class WholeProgramDeadCodeTest {

  /**
   * Packages whose public API is an external contract (modules published to GitHub Packages via a
   * {@code maven-publish} block: app-api → {@code io.justsearch.app.api}, api-contract-projection-java
   * → {@code io.justsearch.contract}) — treated as roots since callers can be out of repo.
   */
  private static final List<String> PUBLISHED_API_PACKAGES =
      List.of("io.justsearch.app.api.", "io.justsearch.contract.");

  /** Coverage floor — the import must see the whole program, not a silently-truncated classpath. */
  private static final int MIN_EXPECTED_CLASSES = 1000;

  private static final List<String> COVERAGE_SENTINELS =
      List.of(
          "io.justsearch.indexerworker.loop.IndexingLoop", // worker-services (index component)
          "io.justsearch.ui.HeadlessApp", // ui (Head process)
          "io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes"); // adapters-lucene

  /**
   * The production {@code ServiceLoader} interfaces whose {@code META-INF/services} files declare
   * impls reachable only by name (no bytecode caller). Their listed impls are reachability roots.
   * Both files are on this module's classpath (it depends on ai-backend + adapters-lucene).
   */
  private static final List<String> SPI_SERVICE_INTERFACES =
      List.of(
          "io.justsearch.aibackend.backend.BackendProvider", "org.apache.lucene.codecs.Codec");

  /** FQNs declared in the SPI service files above — read once from the classpath. */
  private static final Set<String> SPI_IMPLS = readSpiImpls();

  private static Set<String> readSpiImpls() {
    Set<String> impls = new HashSet<>();
    ClassLoader cl = WholeProgramDeadCodeTest.class.getClassLoader();
    for (String iface : SPI_SERVICE_INTERFACES) {
      try {
        Enumeration<URL> urls = cl.getResources("META-INF/services/" + iface);
        while (urls.hasMoreElements()) {
          try (BufferedReader r =
              new BufferedReader(
                  new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
              int hash = line.indexOf('#');
              String fqn = (hash >= 0 ? line.substring(0, hash) : line).trim();
              if (!fqn.isEmpty()) {
                impls.add(fqn);
              }
            }
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return impls;
  }

  @Test
  void no_new_whole_program_dead_classes() {
    // Shared with SystemAccessFunnelTest so the whole-program import happens once per JVM
    // rather than once per analysis (tempdoc 883: this module gained a second whole-program rule).
    JavaClasses classes = ImportedProgram.classes();

    assertWholeProgramCoverage(classes);

    // Scope: whole-program dead *classes* — the public/cross-module gap UnreferencedCodeTest leaves
    // (it covers private/package-private members on the Head classpath only). Whole-program dead
    // *method* detection was measured at ~6.4k findings, noise-dominated by reflectively-serialized
    // accessors / builders / fluent APIs — that needs GraalVM-metadata-level roots and is the
    // low-value long tail (tempdoc 638 §design); deliberately out of scope here.
    ArchRule rule =
        ArchRuleDefinition.classes()
            .that(areWholeProgramDeadClassCandidates(referencedNestedHolders(classes)))
            .should(beReferencedSomewhereInTheWholeProgram());

    FreezingArchRule.freeze(rule).check(classes);
  }

  // --- the rule ---------------------------------------------------------------------------------

  /**
   * Holder roots: a class whose nested type is referenced is a live namespace shell even though its
   * own outer name has no direct incoming dependency (ArchUnit counts the ref against the nested
   * type). Pre-computed over the whole import because a {@link JavaClass} cannot enumerate its own
   * nested types.
   */
  private static Set<String> referencedNestedHolders(JavaClasses classes) {
    Set<String> holders = new HashSet<>();
    for (JavaClass c : classes) {
      if (c.getEnclosingClass().isPresent() && !c.getDirectDependenciesToSelf().isEmpty()) {
        holders.add(c.getEnclosingClass().get().getName());
      }
    }
    return holders;
  }

  private static DescribedPredicate<JavaClass> areWholeProgramDeadClassCandidates(
      Set<String> referencedNestedHolders) {
    // The description is part of the FreezingArchRule store key. Keep it stable: renaming it
    // orphans modules/dead-code-audit/archunit_store and re-freezes every accepted violation.
    return new DescribedPredicate<>(
        "are top-level io.justsearch classes that are not reachability roots") {
      @Override
      public boolean test(JavaClass c) {
        return c.getPackageName().startsWith("io.justsearch")
            && isDeadClassCandidate(c)
            && !isRootClass(c)
            && !referencedNestedHolders.contains(c.getName());
      }
    };
  }

  private static ArchCondition<JavaClass> beReferencedSomewhereInTheWholeProgram() {
    return new ArchCondition<>("be referenced somewhere in the whole program") {
      @Override
      public void check(JavaClass c, ConditionEvents events) {
        // The violation text is the store's per-violation key, so it must be machine-independent:
        // no source URL (it is an absolute file: path and would differ per checkout).
        events.add(
            new SimpleConditionEvent(
                c,
                !c.getDirectDependenciesToSelf().isEmpty(),
                "class " + c.getName() + " is unreferenced across the whole program"));
      }
    };
  }

  // --- reachability roots (the closed-world boundary) -----------------------------------------

  private static boolean isRootClass(JavaClass c) {
    String name = c.getName();
    if (isPublishedApi(name) || SPI_IMPLS.contains(name)) {
      return true; // external/codegen API, or a ServiceLoader impl reached by name
    }
    if (c.getModifiers().contains(JavaModifier.SYNTHETIC)) {
      return true;
    }
    // Process entry points (main classes) and framework service impls reach in from outside.
    if (hasMainMethod(c) || isGrpcImplBase(c) || hasNativeMethod(c)) {
      return true;
    }
    return false;
  }

  private static boolean isDeadClassCandidate(JavaClass c) {
    // Annotations / enums can be referenced only by name or reflectively; nested classes are noisy.
    // Constant-holder classes (a static-final primitive/String field) are referenced via *inlined*
    // constants — the javac inlining erases the bytecode reference, so they false-flag as dead.
    return c.isTopLevelClass()
        && !c.isAnnotation()
        && !c.isEnum()
        && !c.isInterface()
        && !isConstantHolder(c);
  }

  /**
   * A <em>pure</em> constant holder: a namespace of {@code static final} primitive/String constants
   * with no instance state or behaviour. javac inlines such constants into callers, erasing the
   * bytecode reference, so the holder false-flags as dead. Restricted to PURE holders so a genuinely
   * dead class that merely has one constant field is still flagged (not exempted).
   */
  private static boolean isConstantHolder(JavaClass c) {
    boolean hasInstanceMethod =
        c.getMethods().stream().anyMatch(m -> !m.getModifiers().contains(JavaModifier.STATIC));
    if (hasInstanceMethod) {
      return false;
    }
    var fields =
        c.getFields().stream().filter(f -> !f.getModifiers().contains(JavaModifier.SYNTHETIC)).toList();
    if (fields.isEmpty()) {
      return false;
    }
    return fields.stream()
        .allMatch(
            f ->
                f.getModifiers().contains(JavaModifier.STATIC)
                    && f.getModifiers().contains(JavaModifier.FINAL)
                    && (f.getRawType().isPrimitive()
                        || f.getRawType().getName().equals("java.lang.String")));
  }

  /**
   * Fail loudly if the import did not cover the whole program — a silently-truncated classpath would
   * yield a too-small analysis and let the frozen store drift (review finding F2).
   */
  private static void assertWholeProgramCoverage(JavaClasses classes) {
    long n = classes.stream().filter(c -> c.getPackageName().startsWith("io.justsearch")).count();
    if (n < MIN_EXPECTED_CLASSES) {
      throw new IllegalStateException(
          "whole-program dead-code: imported only "
              + n
              + " io.justsearch classes (< "
              + MIN_EXPECTED_CLASSES
              + ") — classpath likely truncated; the frozen store would be unsound.");
    }
    for (String sentinel : COVERAGE_SENTINELS) {
      if (!classes.contain(sentinel)) {
        throw new IllegalStateException(
            "whole-program dead-code: coverage sentinel " + sentinel + " absent — a module is off the analysis classpath.");
      }
    }
  }

  private static boolean isGrpcImplBase(JavaClass c) {
    return c.getAllRawSuperclasses().stream()
        .anyMatch(s -> s.getName().contains("Grpc$") && s.getName().contains("ImplBase"));
  }

  private static boolean hasMainMethod(JavaClass c) {
    return c.getMethods().stream()
        .anyMatch(
            m ->
                m.getName().equals("main")
                    && m.getModifiers().contains(JavaModifier.STATIC)
                    && m.getRawParameterTypes().size() == 1);
  }

  private static boolean hasNativeMethod(JavaClass c) {
    return c.getMethods().stream().anyMatch(m -> m.getModifiers().contains(JavaModifier.NATIVE));
  }

  private static boolean isPublishedApi(String className) {
    for (String p : PUBLISHED_API_PACKAGES) {
      if (className.startsWith(p)) {
        return true;
      }
    }
    return false;
  }
}
