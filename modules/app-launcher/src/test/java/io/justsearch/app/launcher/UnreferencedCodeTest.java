package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * ArchUnit tests to detect unreferenced (potentially dead) code.
 *
 * <p>Uses bytecode analysis to find private and package-private methods that have zero incoming
 * references from other code units. This helps identify dead code that can be safely removed.
 *
 * <p>Known limitations:
 *
 * <ul>
 *   <li>Reflection-based calls are invisible to bytecode analysis
 *   <li>String constants may be inlined, causing false positives
 *   <li>Framework callbacks (Jackson, gRPC) need explicit exclusions
 *   <li>Test callers are not imported, on purpose: a method only tests call is reported (below)
 *   <li>Inherited method calls may resolve to the subclass rather than the declaring class
 * </ul>
 *
 * <p><b>Code kept alive only by tests (tempdoc 966 D6).</b> Test bytecode is not imported, so a
 * non-public production method whose only callers are tests counts as unreferenced. There is no
 * name-based escape ({@code *ForTest*}, {@code *ForTesting}, {@code install*} and {@code reset*}
 * used to be exempt) and no simple-name exemption list. The methods that were exempt that way
 * when this changed are the seeded contents of the frozen violation store, see {@link
 * #no_unreferenced_non_public_methods}.
 *
 * <p>The only named exemption left is {@link #CROSS_MODULE_PRODUCTION_CALLERS}: a method whose
 * caller is production code in a module that is not on app-launcher's test runtime classpath, so
 * the caller is invisible here. Each entry names that caller.
 *
 * <p>Stated gaps: public and protected methods (no detector today; {@code WholeProgramDeadCodeTest}
 * in {@code modules/dead-code-audit} checks classes, not methods) and frontend exports.
 *
 * @see <a href="https://jdriven.com/blog/2021/01/Detect-delete-unreferenced-code-with-ArchUnit">
 *     JDriven: Detect & delete unreferenced code with ArchUnit</a>
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class UnreferencedCodeTest {

  /**
   * Non-public methods whose callers are production code in a module that is NOT on
   * app-launcher's test runtime classpath, so ArchUnit cannot see the call here. Keyed by
   * {@link JavaMethod#getFullName()} (fully qualified owner plus parameter types); the value names
   * the production caller (class and module).
   *
   * <p>Test callers never qualify: a method whose only callers are tests is a violation and is
   * either removed or frozen in the violation store through an accepted addition (tempdoc 966
   * D1/D6). Empty since tempdoc 966: a whole-program bytecode search plus a source search of every
   * module's {@code src/main} found no production caller outside this classpath for any method
   * the old map or name predicates exempted ({@code docs/tempdocs/966-evidence/d6-classification.md}).
   */
  private static final Map<String, String> CROSS_MODULE_PRODUCTION_CALLERS = Map.of();

  /**
   * Description of the frozen method rule. It is the key of the rule in {@code
   * modules/app-launcher/archunit_store/stored.rules}: changing it would orphan the seeded store, so
   * {@link #assertSeededStoreInUse} fails the build instead of letting ArchUnit re-freeze every
   * current violation under the new name.
   */
  static final String METHOD_RULE_DESCRIPTION =
      "Private/package-private methods should be referenced by other code (potential dead code)";

  /**
   * Classes that ArchUnit's bytecode analysis cannot trace but are verified to have dependents.
   * Keyed by simple class name, value is the reason for exemption.
   */
  private static final Map<String, String> KNOWN_UNREFERENCED_CLASSES = Map.of();

  // =========================================================================
  // Dead class detection
  // =========================================================================

  /**
   * Detect package-private classes that no other class depends on. A class is "referenced" if any
   * other class in the codebase has a dependency on it (field type, method parameter/return type,
   * constructor call, method call, annotation, superclass, interface, etc.).
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>Public and protected classes (may be API surface)
   *   <li>Inner/nested classes (lifecycle tied to enclosing class)
   *   <li>Enums (often used via reflection or switch patterns)
   *   <li>Annotations (used via reflection)
   *   <li>Classes listed in {@link #KNOWN_UNREFERENCED_CLASSES}
   *   <li>Entry points (main classes, gRPC service implementations)
   *   <li>Framework-registered classes (Jackson mixins, module initializers)
   * </ul>
   */
  @ArchTest
  void no_unreferenced_package_private_classes(JavaClasses importedClasses) {
    Set<String> referencedClassNames = collectReferencedClasses(importedClasses);

    classes()
        .that(arePackagePrivateClasses())
        .and(isNotInnerClass())
        .and(isNotEnumOrAnnotation())
        .and(isNotKnownUnreferencedClass())
        .and(isNotEntryPointOrFrameworkClass())
        .should(beReferencedByOtherClasses(referencedClassNames))
        .as("Package-private classes should be referenced by at least one other class (potential dead class)")
        .check(importedClasses);
  }

  // =========================================================================
  // Dead field detection
  // =========================================================================

  /**
   * Fields that ArchUnit's bytecode analysis cannot trace but are verified to have accessors.
   * Keyed by "SimpleClassName.fieldName", value is the reason for exemption.
   */
  private static final Map<String, String> KNOWN_UNREFERENCED_FIELDS = Map.ofEntries();

  /**
   * Detect package-private fields that are never read by any code outside their declaring class.
   * A field is "dead" if it is written (assigned) but never read — write-only fields serve no
   * purpose.
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>Private fields (already covered by PMD UnusedPrivateField)
   *   <li>Public and protected fields (may be API surface)
   *   <li>Synthetic fields (compiler-generated, e.g. $VALUES for enums)
   *   <li>Fields annotated with serialization annotations (Jackson, etc.)
   *   <li>Fields in {@link #KNOWN_UNREFERENCED_FIELDS}
   *   <li>Logger fields (SLF4J pattern)
   *   <li>Constant fields (static final — often used as sentinel values or config)
   * </ul>
   */
  @ArchTest
  void no_unreferenced_package_private_fields(JavaClasses importedClasses) {
    fields()
        .that(arePackagePrivateFields())
        .and(isNotSyntheticField())
        .and(isNotLoggerField())
        .and(isNotConstantField())
        .and(isNotSerializationField())
        .and(isNotKnownUnreferencedField())
        .should(beReadByAtLeastOneCodeUnit())
        .as("Package-private fields should be read by at least one code unit (potential dead field)")
        .check(importedClasses);
  }

  // =========================================================================
  // Unreferenced method detection
  // =========================================================================

  /**
   * Detect private and package-private production methods that no production code calls. Callers
   * in tests do not count (test bytecode is not imported).
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>Lambda synthetic methods (lambda$*)
   *   <li>Bridge methods (generated for generics)
   *   <li>Methods annotated with framework annotations (Jackson, etc.)
   *   <li>Methods with @Override in gRPC service implementations
   *   <li>Methods listed in {@link #CROSS_MODULE_PRODUCTION_CALLERS}
   * </ul>
   *
   * <p><b>Frozen (tempdoc 966 D6).</b> The rule is wrapped in a {@link FreezingArchRule} whose
   * committed store is {@code modules/app-launcher/archunit_store/}. A violation is identified by
   * its text, {@code Method <fully qualified owner>.<name>(<parameter types>) is never referenced};
   * no line number or position is part of it (ArchUnit's default line matcher additionally ignores
   * anonymous-class and lambda numbering). Consequences:
   *
   * <ul>
   *   <li>A new violation fails the build. Accepting one means adding its line to the store, which
   *       needs a test-intent entry and acceptance (tempdoc 966 D1).
   *   <li>A stored violation that disappears (the method was removed, or gained a production
   *       caller) is dropped from the store by the next run ({@code allowStoreUpdate=true}); a
   *       removal needs no other source change.
   *   <li>Normal runs cannot re-freeze: store creation is disabled, and {@link
   *       #assertSeededStoreInUse} rejects a store that lacks this rule or a configuration that
   *       re-freezes. Seeding is the explicit step documented in {@code archunit.properties}.
   * </ul>
   */
  @ArchTest
  void no_unreferenced_non_public_methods(JavaClasses importedClasses) {
    assertSeededStoreInUse();
    Set<String> referencedMethods = collectReferencedMethods(importedClasses);

    ArchRule rule =
        methods()
            .that(arePrivateOrPackagePrivate())
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .and()
            .doNotHaveModifier(JavaModifier.BRIDGE)
            .and(isNotLambdaMethod())
            .and(isNotFrameworkCallback())
            .and(hasNoCrossModuleProductionCaller())
            .should(beReferencedByOtherCodeUnits(referencedMethods))
            .as(METHOD_RULE_DESCRIPTION);

    FreezingArchRule.freeze(rule).check(importedClasses);
  }

  // =========================================================================
  // Frozen store guard
  // =========================================================================

  /**
   * Fails unless the run uses the committed, seeded store for the method rule. ArchUnit itself
   * freezes every current violation whenever it meets a rule it has not stored yet (store
   * update is enabled so that the store can shrink) or when {@code freeze.refreeze=true}; either
   * would silently accept new test-only code. Both are refused here unless store creation was
   * deliberately enabled for the one seeding run documented in {@code archunit.properties}.
   */
  private static void assertSeededStoreInUse() {
    ArchConfiguration config = ArchConfiguration.get();
    if (Boolean.parseBoolean(
        config.getPropertyOrDefault("freeze.store.default.allowStoreCreation", "false"))) {
      return; // the documented seeding run
    }
    if (Boolean.parseBoolean(config.getPropertyOrDefault("freeze.refreeze", "false"))) {
      throw new AssertionError(
          "freeze.refreeze=true would re-freeze every current unreferenced method; it is only"
              + " allowed in the seeding run documented in archunit.properties");
    }
    String storePath = config.getPropertyOrDefault("freeze.store.default.path", "");
    Path storedRules = Path.of(storePath).resolve("stored.rules");
    Properties rules = new Properties();
    try (InputStream in = Files.newInputStream(storedRules)) {
      rules.load(in);
    } catch (IOException e) {
      throw new AssertionError(
          "frozen store not readable at "
              + storedRules.toAbsolutePath()
              + "; the store is committed under modules/app-launcher/archunit_store",
          e);
    }
    if (!rules.containsKey(METHOD_RULE_DESCRIPTION)) {
      throw new AssertionError(
          "the frozen store has no entry for '"
              + METHOD_RULE_DESCRIPTION
              + "'; a renamed rule would re-freeze every current violation. Restore the description"
              + " or re-seed deliberately as documented in archunit.properties");
    }
  }

  // =========================================================================
  // Reference collection
  // =========================================================================

  /** Collects all method signatures that are called or referenced from any code unit. */
  private static Set<String> collectReferencedMethods(JavaClasses allClasses) {
    Set<String> referencedMethods = new HashSet<>();

    for (JavaClass javaClass : allClasses) {
      // Collect method calls from all methods
      javaClass
          .getMethodCallsFromSelf()
          .forEach(call -> referencedMethods.add(call.getTarget().getFullName()));

      // Collect method references (for lambdas and method references)
      javaClass
          .getMethodReferencesFromSelf()
          .forEach(ref -> referencedMethods.add(ref.getTarget().getFullName()));

      // Collect method calls from constructors (Fix #5)
      javaClass
          .getConstructors()
          .forEach(
              ctor ->
                  ctor.getMethodCallsFromSelf()
                      .forEach(call -> referencedMethods.add(call.getTarget().getFullName())));
    }

    return referencedMethods;
  }

  // =========================================================================
  // Custom predicates for filtering
  // =========================================================================

  /** Matches private or package-private methods (Fix #2: extends beyond just private). */
  private static DescribedPredicate<JavaMethod> arePrivateOrPackagePrivate() {
    return new DescribedPredicate<>("are private or package-private") {
      @Override
      public boolean test(JavaMethod method) {
        return method.getModifiers().contains(JavaModifier.PRIVATE)
            || (!method.getModifiers().contains(JavaModifier.PUBLIC)
                && !method.getModifiers().contains(JavaModifier.PROTECTED)
                && !method.getModifiers().contains(JavaModifier.PRIVATE));
      }
    };
  }

  /** Excludes lambda synthetic methods (named lambda$methodName$0, etc.). */
  private static DescribedPredicate<JavaMethod> isNotLambdaMethod() {
    return new DescribedPredicate<>("is not a lambda method") {
      @Override
      public boolean test(JavaMethod method) {
        return !method.getName().startsWith("lambda$");
      }
    };
  }

  /**
   * Excludes methods that are framework callbacks.
   *
   * <p>Excludes:
   *
   * <ul>
   *   <li>Jackson serialization annotations
   *   <li>gRPC service method overrides
   * </ul>
   *
   * <p>Method names do not exempt anything: the former {@code *ForTest*}, {@code *ForTesting},
   * {@code install*} and {@code reset*} exemptions were replaced by the methods they matched,
   * frozen in the violation store (tempdoc 966 D6).
   */
  private static DescribedPredicate<JavaMethod> isNotFrameworkCallback() {
    return new DescribedPredicate<>("is not a framework callback") {
      @Override
      public boolean test(JavaMethod method) {
        // Jackson serialization callbacks
        if (method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonCreator")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonSetter")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonGetter")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonProperty")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonValue")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonAnySetter")
            || method.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonAnyGetter")) {
          return false;
        }

        // gRPC service method overrides
        if (method.isAnnotatedWith(Override.class)) {
          JavaClass owner = method.getOwner();
          if (owner.getAllRawSuperclasses().stream()
              .anyMatch(
                  c -> c.getName().contains("Grpc$") && c.getName().contains("ImplBase"))) {
            return false;
          }
        }

        return true;
      }
    };
  }

  /**
   * Excludes methods listed in {@link #CROSS_MODULE_PRODUCTION_CALLERS}: production callers in
   * modules outside this classpath, matched by fully qualified owner and signature.
   */
  private static DescribedPredicate<JavaMethod> hasNoCrossModuleProductionCaller() {
    return new DescribedPredicate<>("have no named production caller outside this classpath") {
      @Override
      public boolean test(JavaMethod method) {
        return !CROSS_MODULE_PRODUCTION_CALLERS.containsKey(method.getFullName());
      }
    };
  }

  // =========================================================================
  // Custom ArchCondition for reference checking
  // =========================================================================

  /** Checks that a method is referenced by at least one other code unit (method or constructor). */
  private static ArchCondition<JavaMethod> beReferencedByOtherCodeUnits(
      Set<String> referencedMethods) {
    return new ArchCondition<>("be referenced by other code units") {
      @Override
      public void check(JavaMethod method, ConditionEvents events) {
        String signature = method.getFullName();

        if (!referencedMethods.contains(signature)) {
          // The message is the frozen store's identity for this violation: fully qualified owner
          // and full signature, nothing machine-, line- or order-dependent.
          String message = "Method " + signature + " is never referenced";
          events.add(SimpleConditionEvent.violated(method, message));
        }
      }
    };
  }

  // =========================================================================
  // Dead class detection — predicates and conditions
  // =========================================================================

  /**
   * Collects all class names that are depended upon by at least one OTHER class. Uses ArchUnit's
   * {@link JavaClass#getDirectDependenciesFromSelf()} which covers field types, method
   * parameters/return types, constructor calls, method calls, annotations, superclasses, and
   * interfaces.
   */
  private static Set<String> collectReferencedClasses(JavaClasses allClasses) {
    Set<String> referenced = new HashSet<>();
    for (JavaClass javaClass : allClasses) {
      for (Dependency dep : javaClass.getDirectDependenciesFromSelf()) {
        JavaClass target = dep.getTargetClass();
        // Only count references from OTHER classes (not self-references)
        if (!target.getName().equals(javaClass.getName())) {
          referenced.add(target.getName());
        }
      }
    }
    return referenced;
  }

  /** Matches package-private (default visibility) top-level classes. */
  private static DescribedPredicate<JavaClass> arePackagePrivateClasses() {
    return new DescribedPredicate<>("are package-private") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !javaClass.getModifiers().contains(JavaModifier.PUBLIC)
            && !javaClass.getModifiers().contains(JavaModifier.PROTECTED)
            && !javaClass.getModifiers().contains(JavaModifier.PRIVATE);
      }
    };
  }

  /** Excludes inner/nested classes — their lifecycle is tied to the enclosing class. */
  private static DescribedPredicate<JavaClass> isNotInnerClass() {
    return new DescribedPredicate<>("is not an inner/nested class") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !javaClass.getName().contains("$");
      }
    };
  }

  /** Excludes enums and annotation types — often used via reflection or switch patterns. */
  private static DescribedPredicate<JavaClass> isNotEnumOrAnnotation() {
    return new DescribedPredicate<>("is not an enum or annotation") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !javaClass.isEnum() && !javaClass.isAnnotation();
      }
    };
  }

  /** Excludes classes listed in {@link #KNOWN_UNREFERENCED_CLASSES}. */
  private static DescribedPredicate<JavaClass> isNotKnownUnreferencedClass() {
    return new DescribedPredicate<>("is not in the known-unreferenced-classes exclusion list") {
      @Override
      public boolean test(JavaClass javaClass) {
        return !KNOWN_UNREFERENCED_CLASSES.containsKey(javaClass.getSimpleName());
      }
    };
  }

  /**
   * Excludes entry points and framework-registered classes that have no static dependents but are
   * invoked by the runtime.
   */
  private static DescribedPredicate<JavaClass> isNotEntryPointOrFrameworkClass() {
    return new DescribedPredicate<>("is not an entry point or framework-registered class") {
      @Override
      public boolean test(JavaClass javaClass) {
        String name = javaClass.getSimpleName();

        // gRPC service implementations (registered dynamically)
        if (javaClass.getAllRawSuperclasses().stream()
            .anyMatch(c -> c.getName().contains("Grpc$") && c.getName().contains("ImplBase"))) {
          return false;
        }

        // Main entry points
        if (name.endsWith("Main") || name.endsWith("App") || name.endsWith("Application")) {
          return false;
        }

        return true;
      }
    };
  }

  /** Checks that a class is depended upon by at least one other class in the codebase. */
  private static ArchCondition<JavaClass> beReferencedByOtherClasses(
      Set<String> referencedClassNames) {
    return new ArchCondition<>("be referenced by at least one other class") {
      @Override
      public void check(JavaClass javaClass, ConditionEvents events) {
        if (!referencedClassNames.contains(javaClass.getName())) {
          String message =
              String.format(
                  "Class %s in package %s is never referenced by any other class",
                  javaClass.getSimpleName(), javaClass.getPackageName());
          events.add(SimpleConditionEvent.violated(javaClass, message));
        }
      }
    };
  }

  // =========================================================================
  // Dead field detection — predicates and conditions
  // =========================================================================

  /** Matches package-private (default visibility) fields. */
  private static DescribedPredicate<JavaField> arePackagePrivateFields() {
    return new DescribedPredicate<>("are package-private") {
      @Override
      public boolean test(JavaField field) {
        return !field.getModifiers().contains(JavaModifier.PRIVATE)
            && !field.getModifiers().contains(JavaModifier.PUBLIC)
            && !field.getModifiers().contains(JavaModifier.PROTECTED);
      }
    };
  }

  /** Excludes compiler-generated synthetic fields (e.g. enum $VALUES). */
  private static DescribedPredicate<JavaField> isNotSyntheticField() {
    return new DescribedPredicate<>("is not synthetic") {
      @Override
      public boolean test(JavaField field) {
        return !field.getModifiers().contains(JavaModifier.SYNTHETIC)
            && !field.getName().startsWith("$");
      }
    };
  }

  /** Excludes SLF4J logger fields (conventional pattern: static final Logger log/LOG/logger). */
  private static DescribedPredicate<JavaField> isNotLoggerField() {
    return new DescribedPredicate<>("is not a logger field") {
      @Override
      public boolean test(JavaField field) {
        String name = field.getName().toLowerCase();
        return !(name.equals("log") || name.equals("logger"));
      }
    };
  }

  /** Excludes static final fields — constants, sentinel values, shared config. */
  private static DescribedPredicate<JavaField> isNotConstantField() {
    return new DescribedPredicate<>("is not a constant (static final)") {
      @Override
      public boolean test(JavaField field) {
        return !(field.getModifiers().contains(JavaModifier.STATIC)
            && field.getModifiers().contains(JavaModifier.FINAL));
      }
    };
  }

  /** Excludes fields with Jackson or serialization annotations. */
  private static DescribedPredicate<JavaField> isNotSerializationField() {
    return new DescribedPredicate<>("is not annotated with serialization annotations") {
      @Override
      public boolean test(JavaField field) {
        return !field.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonProperty")
            && !field.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonAnySetter")
            && !field.isAnnotatedWith("com.fasterxml.jackson.annotation.JsonAnyGetter");
      }
    };
  }

  /** Excludes fields listed in {@link #KNOWN_UNREFERENCED_FIELDS}. */
  private static DescribedPredicate<JavaField> isNotKnownUnreferencedField() {
    return new DescribedPredicate<>("is not in the known-unreferenced-fields exclusion list") {
      @Override
      public boolean test(JavaField field) {
        String key = field.getOwner().getSimpleName() + "." + field.getName();
        return !KNOWN_UNREFERENCED_FIELDS.containsKey(key);
      }
    };
  }

  /**
   * Checks that a field has at least one read access (GET) from any code unit. Write-only fields
   * are dead — they are assigned but their value is never consumed.
   */
  private static ArchCondition<JavaField> beReadByAtLeastOneCodeUnit() {
    return new ArchCondition<>("be read by at least one code unit") {
      @Override
      public void check(JavaField field, ConditionEvents events) {
        boolean hasRead =
            field.getAccessesToSelf().stream()
                .anyMatch(access -> access.getAccessType() == JavaFieldAccess.AccessType.GET);
        if (!hasRead) {
          String message =
              String.format(
                  "Field %s in %s is never read (write-only)",
                  field.getName(), field.getOwner().getSimpleName());
          events.add(SimpleConditionEvent.violated(field, message));
        }
      }
    };
  }
}
