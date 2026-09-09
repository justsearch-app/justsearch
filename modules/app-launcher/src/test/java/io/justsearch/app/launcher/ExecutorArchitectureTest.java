/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * C1-5/C1-16 executor architecture pins.
 *
 * <p>The registry is the runtime authority. This class is only a projection used to catch a raw
 * executor factory added outside that authority and to keep the source census from becoming a
 * vacuous test after centralisation. The census unit is one logical executor registration: an
 * explicit {@code EngineExecutorSpec} expression counts once, while a shared registration helper
 * counts once per production call site.
 */
@AnalyzeClasses(
    packages = "io.justsearch",
    importOptions = {ImportOption.DoNotIncludeTests.class, ExecutorArchitectureTest.NoTestFixtures.class})
class ExecutorArchitectureTest {

  /** Gradle test fixtures are test artifacts even though ArchUnit's default filter omits them. */
  public static final class NoTestFixtures implements ImportOption {
    @Override public boolean includes(com.tngtech.archunit.core.importer.Location location) {
      String uri = location.asURI().toString();
      return !uri.contains("/testFixtures/") && !uri.contains("-test-fixtures.jar");
    }
  }

  private static final String DEFAULT_REGISTRY =
      "io.justsearch.app.engine.DefaultEngineExecutorRegistry";
  private static final String EXTRACTION_CHILD =
      "io.justsearch.indexerworker.extract.ExtractionSandboxChild";
  private static final String EXECUTORS = "java.util.concurrent.Executors";
  private static final Set<String> RAW_CONSTRUCTOR_TYPES = Set.of(
      "java.util.concurrent.ThreadPoolExecutor",
      "java.util.concurrent.ScheduledThreadPoolExecutor",
      "java.util.concurrent.ForkJoinPool");

  /** C1's known pre-centralisation expression count; this is a floor, not a registry mirror. */
  private static final int MIN_EXPLICIT_SPEC_EXPRESSIONS = 58;

  /** C1-5's non-vacuous logical-registration floor after helper expansion. */
  private static final int MIN_LOGICAL_REGISTRATIONS = 63;
  private static final int SHARED_HELPER_DEFINITIONS = 5;

  private static final Pattern RAW_SOURCE_FACTORY = Pattern.compile(
      "\\b(?:Executors\\s*\\.\\s*new[A-Za-z0-9_]*|new\\s+(?:"
          + "(?:java\\.util\\.concurrent\\.)?ThreadPoolExecutor|"
          + "(?:java\\.util\\.concurrent\\.)?ScheduledThreadPoolExecutor|"
          + "(?:java\\.util\\.concurrent\\.)?ForkJoinPool))\\s*\\(");
  private static final Pattern RAW_SOURCE_REFERENCE = Pattern.compile(
      "\\b(?:Executors\\s*::\\s*new[A-Za-z0-9_]+|"
          + "(?:java\\.util\\.concurrent\\.)?(?:ThreadPoolExecutor|ScheduledThreadPoolExecutor|ForkJoinPool)"
          + "\\s*::\\s*new)\\b");

  private static final Pattern SPEC_EXPRESSION = Pattern.compile(
      "\\bnew\\s+(?:io\\.justsearch\\.core\\.execution\\.)?EngineExecutorSpec\\s*\\("
          + "|\\bEngineExecutorSpec\\.virtual\\s*\\(");

  /**
   * The only raw factories permitted in production are the registry implementation and the
   * external parser process's one child-local OCR factory. Source checks also catch a producer before its module reaches the test runtime classpath.
   */
  @Test
  void rawExecutorFactoriesStayInsideTheExactOwnerAllowlist() {
    List<String> violations = new ArrayList<>();
    for (SourceFile source : productionSources()) {
      List<Integer> rawSites = new ArrayList<>();
      Matcher matcher = RAW_SOURCE_FACTORY.matcher(source.maskedText());
      while (matcher.find()) rawSites.add(matcher.start());
      Matcher references = RAW_SOURCE_REFERENCE.matcher(source.maskedText());
      while (references.find()) rawSites.add(references.start());
      Matcher imported = Pattern.compile(
          "import\\s+static\\s+java\\.util\\.concurrent\\.Executors\\.(new[A-Za-z0-9_]+|\\*)\\s*;")
          .matcher(source.maskedText());
      while (imported.find()) {
        String name = imported.group(1).equals("*") ? "new[A-Za-z0-9_]+" : imported.group(1);
        Matcher bare = Pattern.compile("\\b" + name + "\\s*\\(").matcher(source.maskedText());
        while (bare.find()) rawSites.add(bare.start());
      }
      for (int rawSite : rawSites) {
        String relative = source.relativePath();
        boolean registryOwner = relative.equals(
            "modules/app-engine/src/main/java/io/justsearch/app/engine/"
                + "DefaultEngineExecutorRegistry.java");
        boolean childOwner = relative.equals(
            "modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/"
                + "ExtractionSandboxChild.java")
            && isInsideMethod(source.maskedText(), "openOcrPool", rawSite);
        if (!registryOwner && !childOwner) {
          violations.add(relative + " at source offset " + rawSite);
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "Raw executor factories outside DefaultEngineExecutorRegistry or "
            + "ExtractionSandboxChild.openOcrPool: " + violations);
  }

  /** JDK HttpClient otherwise creates a hidden cached pool outside the raw-constructor census. */
  @Test
  void engineHttpClientFactoriesAlwaysInjectTheirExecutor() {
    int clients = 0;
    List<String> violations = new ArrayList<>();
    Pattern factories = Pattern.compile("\\bHttpClient\\s*\\.\\s*(newBuilder|newHttpClient)\\s*\\(");
    for (SourceFile source : productionSources()) {
      if (source.relativePath().equals(
          "modules/system-tests/src/main/java/io/justsearch/systemtests/chaos/ExternalLlamaServerClient.java")) {
        continue; // Named external system-test harness, outside the Engine process.
      }
      Matcher factory = factories.matcher(source.maskedText());
      while (factory.find()) {
        clients++;
        int end = source.maskedText().indexOf(';', factory.end());
        String construction = end < 0 ? "" : source.maskedText().substring(factory.end(), end);
        if (factory.group(1).equals("newHttpClient")
            || !Pattern.compile("\\.executor\\s*\\(").matcher(construction).find()) {
          violations.add(source.relativePath() + " at source offset " + factory.start());
        }
      }
    }
    assertTrue(clients >= 5, "five current Engine HttpClient factories must remain covered");
    assertTrue(violations.isEmpty(), () -> "Hidden HttpClient executor factories: " + violations);
  }

  /**
   * Counts logical owners rather than merely counting the implementation's central factory. The
   * four existing bundles are intentionally captured by their real helper call sites: five
   * EngineKnowledgeClient owners, nine inference owners, four HeadAssembly owners and two
   * OpenAi owners.
   */
  @Test
  void logicalRegistrationCensusHasANonVacuousFloor() {
    List<SourceFile> sources = productionSources();
    int explicit = 0;
    for (SourceFile source : sources) {
      if (source.relativePath().equals(
          "modules/core/src/main/java/io/justsearch/core/execution/EngineExecutorSpec.java")) {
        continue;
      }
      explicit += count(SPEC_EXPRESSION, source.maskedText());
    }

    int helperExpansion = helperCallSites(sources,
        "modules/app-engine/src/main/java/io/justsearch/app/engine/EngineKnowledgeClient.java",
        List.of(Pattern.compile("\\bplatform\\s*\\("), Pattern.compile("\\bscheduled\\s*\\(")),
        2);
    helperExpansion += helperCallSites(sources,
        "modules/app-inference/src/main/java/io/justsearch/app/inference/"
            + "InferenceExecutorRegistrations.java",
        List.of(Pattern.compile("\\bregister\\s*\\(\\s*registry\\s*,\\s*acquired\\s*,")),
        0);
    helperExpansion += helperCallSites(sources,
        "modules/app-services/src/main/java/io/justsearch/app/services/HeadAssembly.java",
        List.of(Pattern.compile("\\bdocumentExecutorOwner\\s*\\(")), 1);
    helperExpansion += helperCallSites(sources,
        "modules/ui/src/main/java/io/justsearch/ui/api/OpenAiCompatController.java",
        List.of(Pattern.compile("\\bhttpOwner\\s*\\(")), 1);

    int logical = explicit - SHARED_HELPER_DEFINITIONS + helperExpansion;
    assertTrue(
        explicit >= MIN_EXPLICIT_SPEC_EXPRESSIONS,
        "Explicit EngineExecutorSpec expression count fell below "
            + MIN_EXPLICIT_SPEC_EXPRESSIONS + ": " + explicit);
    assertTrue(
        logical >= MIN_LOGICAL_REGISTRATIONS,
        "Logical executor registration census fell below "
            + MIN_LOGICAL_REGISTRATIONS + ": explicit=" + explicit
            + ", helperExpansion=" + helperExpansion + ", logical=" + logical);
  }

  /** Bytecode companion to the source scan; this catches compiled classes when they are present. */
  private static final ArchCondition<JavaClass> rawFactoriesHaveAnAllowedOrigin =
      new ArchCondition<>("construct or obtain executors only through the named owners") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
          for (var call : item.getCodeUnitAccessesFromSelf()) {
            if (call.getName().equals("<init>") && RAW_CONSTRUCTOR_TYPES.contains(call.getTargetOwner().getFullName())
                && !allowedOrigin(call.getOrigin().getOwner().getFullName(),
                    call.getOrigin().getName())) {
              events.add(SimpleConditionEvent.violated(item,
                  item.getFullName() + " constructs " + call.getTargetOwner().getFullName()
                      + " at " + call.getSourceCodeLocation()
                      + " outside the executor owner allowlist"));
            }
          }
          for (var call : item.getCodeUnitAccessesFromSelf()) {
            if (EXECUTORS.equals(call.getTargetOwner().getFullName())
                && call.getName().startsWith("new")
                && !allowedOrigin(call.getOrigin().getOwner().getFullName(),
                    call.getOrigin().getName())) {
              events.add(SimpleConditionEvent.violated(item,
                  item.getFullName() + " calls Executors." + call.getName() + " at "
                      + call.getSourceCodeLocation()
                      + " outside the executor owner allowlist"));
            }
          }
        }
      };

  @ArchTest
  static final ArchRule rawExecutorFactoriesHaveNamedOwners =
      classes().should(rawFactoriesHaveAnAllowedOrigin).as(
          "raw executor constructors/factories are confined to the registry and the one child OCR"
              + " method (C1-5/C1-16)");

  private static boolean allowedOrigin(String owner, String method) {
    return owner.equals(DEFAULT_REGISTRY)
        || owner.startsWith(DEFAULT_REGISTRY + "$")
        || (owner.equals(EXTRACTION_CHILD) && method.equals("openOcrPool"));
  }

  private static int helperCallSites(
      List<SourceFile> sources, String relativePath, List<Pattern> patterns, int definitions) {
    SourceFile source = sources.stream()
        .filter(candidate -> candidate.relativePath().equals(relativePath))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing executor owner source: " + relativePath));
    int matches = 0;
    for (Pattern pattern : patterns) matches += count(pattern, source.maskedText());
    return matches - definitions;
  }

  private static int count(Pattern pattern, String text) {
    int count = 0;
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) count++;
    return count;
  }

  private static boolean isInsideMethod(String text, String methodName, int position) {
    Matcher declaration = Pattern.compile(
        "\\b" + Pattern.quote(methodName) + "\\s*\\([^)]*\\)\\s*\\{").matcher(text);
    while (declaration.find()) {
      int open = text.indexOf('{', declaration.start());
      int close = matchingBrace(text, open);
      if (open >= 0 && close >= 0 && position > open && position < close) return true;
    }
    return false;
  }

  private static int matchingBrace(String text, int open) {
    if (open < 0) return -1;
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '{') depth++;
      if (c == '}' && --depth == 0) return i;
    }
    return -1;
  }

  private static List<SourceFile> productionSources() {
    Path root = repositoryRoot();
    Path modules = root.resolve("modules");
    try (var paths = Files.walk(modules)) {
      return paths.filter(path -> path.toString().replace('\\', '/').contains("/src/main/"))
          .filter(path -> path.toString().endsWith(".java"))
          .map(path -> readSource(root, path))
          .toList();
    } catch (IOException e) {
      throw new AssertionError("Cannot scan production sources below " + modules, e);
    }
  }

  private static SourceFile readSource(Path root, Path path) {
    try {
      String text = Files.readString(path, StandardCharsets.UTF_8);
      return new SourceFile(root.relativize(path).toString().replace('\\', '/'),
          maskCommentsAndLiterals(text));
    } catch (IOException e) {
      throw new AssertionError("Cannot read production source " + path, e);
    }
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (candidate != null && !Files.isDirectory(candidate.resolve("modules"))) {
      candidate = candidate.getParent();
    }
    if (candidate == null) throw new AssertionError("Cannot locate repository root from user.dir");
    return candidate;
  }

  /** Masks comments and literals while preserving offsets, so source findings have stable sites. */
  private static String maskCommentsAndLiterals(String source) {
    StringBuilder masked = new StringBuilder(source);
    int state = 0; // 0 normal, 1 line comment, 2 block comment, 3 string, 4 character
    boolean escaped = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
      if (state == 0) {
        if (c == '/' && next == '/') { masked.setCharAt(i, ' '); state = 1; }
        else if (c == '/' && next == '*') { masked.setCharAt(i, ' '); state = 2; }
        else if (c == '"') { masked.setCharAt(i, ' '); state = 3; escaped = false; }
        else if (c == '\'') { masked.setCharAt(i, ' '); state = 4; escaped = false; }
      } else if (state == 1) {
        if (c == '\n' || c == '\r') state = 0; else masked.setCharAt(i, ' ');
      } else if (state == 2) {
        if (c == '*' && next == '/') { masked.setCharAt(i, ' '); state = 0; }
        else if (c != '\n' && c != '\r') masked.setCharAt(i, ' ');
      } else {
        if (c == '\n' || c == '\r') { state = 0; escaped = false; }
        else { masked.setCharAt(i, ' '); if (!escaped && ((state == 3 && c == '"')
            || (state == 4 && c == '\''))) state = 0; escaped = !escaped && c == '\\'; }
      }
    }
    return masked.toString();
  }

  private record SourceFile(String relativePath, String maskedText) {}
}
