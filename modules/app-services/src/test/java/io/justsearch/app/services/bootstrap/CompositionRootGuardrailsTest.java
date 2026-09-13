package io.justsearch.app.services.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 541 §4.3 — composition-root discipline gate. Single consolidated ArchUnit/JUnit
 * test class per §9.1 A4: 530's discipline-gate kernel is design-only, so this is the primary
 * landing site for composition-root rules. When 530's kernel scaffolding lands, these rules
 * migrate to a {@code composition-root} gate-kind with SARIF v2.1.0 output + changeset-bound
 * escape valves; until then this class IS the enforcement.
 *
 * <p>Rules shipped:
 *
 * <ul>
 *   <li>4a {@code outputCardinalityCeiling} — Phase Output records carry ≤ {@value
 *       #MAX_OUTPUT_FIELDS} record components.
 *   <li>4b {@code phaseCountCeiling} — process-local phase-class count ≤ {@value #MAX_PHASES}.
 *   <li>4c {@code lateBindingsHolderCap} — {@code BootstrapLateBindings} has ≤ {@value
 *       #MAX_LATE_BINDINGS} instance fields.
 *   <li>4g {@code serviceImplsConstructedOnlyInBootstrapPhases} — service construction lives
 *       in {@code bootstrap.phases..} (lifted from §31 Rule 2 in fix-pass B.3).
 * </ul>
 *
 * <p>Phase discovery (fix-pass B.1): rules 4a and 4b discover phase classes via ArchUnit
 * classpath scan of {@code io.justsearch.app.services.bootstrap.phases} at static-init time,
 * filtering by {@code getSimpleName().endsWith("Phase")}. A new phase added to the package
 * is automatically caught by both rules — no manual list update needed. Reflection-based
 * record-component introspection ({@link RecordComponent}) is preserved because ArchUnit
 * 1.4.1 lacks a native equivalent.
 *
 * <p>Deferred rules: 4d, 4e, 4f land as {@link Disabled @Disabled} stubs (fix-pass B.2) so
 * they appear in test reports as known-disabled. Each stub names its specific blocker;
 * re-enabling is a small edit when the blocker resolves.
 */
@DisplayName("Tempdoc 541 §4.3 — composition-root discipline gate")
class CompositionRootGuardrailsTest {

  /** Ceiling, not a live count — ServicePhase.Output currently has 21 components (tempdoc 868
   * §B.2 bundled the six agent-tool fields into one {@code AgentToolFactory.Output} component,
   * dropping it below this pin). The ceiling itself stays at 26, set after tempdoc 542's
   * OperationLeaseService addition (the always-available op-lease SPI for the dev-runner
   * ownership model); reductions welcome, growth requires a 530-kernel changeset row. The next
   * addition should trigger a decomposition pass (bundling settings/policy/diagnostics into
   * CoreServices in Output) rather than another ceiling bump. */
  static final int MAX_OUTPUT_FIELDS = 26;

  /** Pin — Head process currently has 5 phases. Pinned at 8. */
  static final int MAX_PHASES = 8;

  /** Pin — BootstrapLateBindings currently has 3 holders per §31 Phase 2/3. */
  static final int MAX_LATE_BINDINGS = 5;

  /** Package scanned for phase classes (B.1 — classpath discovery). */
  private static final String PHASES_PACKAGE = "io.justsearch.app.services.bootstrap.phases";

  /**
   * Fix-pass B.1: discover phase classes via ArchUnit at static-init. Filters to top-level
   * classes in {@link #PHASES_PACKAGE} whose simple name ends with {@code "Phase"}. Loads
   * each via {@link Class#forName} so reflection-based {@link RecordComponent} introspection
   * (rule 4a) can operate on actual class objects.
   */
  private static final List<Class<?>> HEAD_PHASE_CLASSES = discoverPhases();

  private static List<Class<?>> discoverPhases() {
    var imported = new ClassFileImporter().importPackages(PHASES_PACKAGE);
    List<Class<?>> phases = new ArrayList<>();
    for (JavaClass jc : imported) {
      if (!jc.isTopLevelClass()) continue;
      if (!jc.getSimpleName().endsWith("Phase")) continue;
      try {
        phases.add(Class.forName(jc.getName()));
      } catch (ClassNotFoundException e) {
        throw new AssertionError("Phase class on ArchUnit classpath missing from JVM: " + jc.getName(), e);
      }
    }
    return List.copyOf(phases);
  }

  /** 4a — output cardinality ceiling. */
  @Test
  @DisplayName("4a — Phase Output records have ≤ MAX_OUTPUT_FIELDS record components")
  void outputCardinalityCeiling() {
    assertTrue(HEAD_PHASE_CLASSES.size() > 0, "Phase discovery returned 0 classes — classpath scan misconfigured?");
    List<String> offenders = new ArrayList<>();
    for (Class<?> phase : HEAD_PHASE_CLASSES) {
      for (Class<?> nested : phase.getDeclaredClasses()) {
        if (!"Output".equals(nested.getSimpleName())) continue;
        if (!nested.isRecord()) continue;
        RecordComponent[] components = nested.getRecordComponents();
        if (components.length > MAX_OUTPUT_FIELDS) {
          offenders.add(nested.getName() + " (" + components.length + " components)");
        }
      }
    }
    if (!offenders.isEmpty()) {
      fail(
          "Phase Output records exceeding MAX_OUTPUT_FIELDS="
              + MAX_OUTPUT_FIELDS
              + " (god-record guard): "
              + offenders);
    }
  }

  /** 4b — phase count ceiling per composition root. Fix-pass B.1: count is classpath-derived. */
  @Test
  @DisplayName("4b — head process has ≤ MAX_PHASES discovered phase classes")
  void phaseCountCeiling() {
    int phaseCount = HEAD_PHASE_CLASSES.size();
    assertTrue(
        phaseCount > 0,
        "Phase discovery returned 0 — classpath scan misconfigured (expected to find Infra,"
            + " Capability, Service, Substrate, Orchestration in "
            + PHASES_PACKAGE
            + ").");
    assertTrue(
        phaseCount <= MAX_PHASES,
        "Discovered phase count "
            + phaseCount
            + " > MAX_PHASES="
            + MAX_PHASES
            + ". Phases found: "
            + HEAD_PHASE_CLASSES.stream().map(Class::getSimpleName).toList()
            + ". Growth requires a 530-kernel changeset row when that gate lands.");
  }

  /** 4c — late-binding holder cap. */
  @Test
  @DisplayName("4c — BootstrapLateBindings has ≤ MAX_LATE_BINDINGS instance fields")
  void lateBindingsHolderCap() {
    Class<?> holder = BootstrapLateBindings.class;
    assertNotNull(holder, "BootstrapLateBindings class must load");
    long instanceFields = 0;
    for (Field f : holder.getDeclaredFields()) {
      if (!Modifier.isStatic(f.getModifiers())) {
        instanceFields++;
      }
    }
    assertTrue(
        instanceFields <= MAX_LATE_BINDINGS,
        "BootstrapLateBindings instance-field count "
            + instanceFields
            + " > MAX_LATE_BINDINGS="
            + MAX_LATE_BINDINGS
            + ". The diagnostic holders (debugStateProvider,"
            + " statusSnapshotProvider) remain. Growth requires a 530-kernel"
            + " changeset row.");
  }

  /**
   * 4d — Output immutability deep-check. **Disabled** until concrete violation evidence
   * surfaces. Records enforce {@code final} fields by language; a deeper "no mutable
   * collections / no mutable nested types" check needs a per-component-type allowlist + a
   * custom {@code DescribedPredicate} that scans each component's declared type. Cost is
   * non-trivial; defer pending evidence of a real bug.
   */
  @Test
  @Disabled(
      "541 §4.3 rule 4d — deferred; specific blocker: no concrete violation evidence yet."
          + " Re-enable when a phase Output ships with a mutable-collection component that"
          + " bites in production.")
  @DisplayName("4d — Phase Output records have no mutable-collection components (deferred)")
  void outputImmutabilityDeepCheck() {
    fail("rule 4d is disabled — see @Disabled annotation for the blocker");
  }

  /**
   * 4e — Lazy-phase conformance. **Disabled** until phases declare their {@link Eagerness}
   * explicitly. The {@link Eagerness} enum ships in fix-pass Tier 2 (A.2); a future Phase&lt;I,O&gt;
   * marker interface or per-phase {@code Eagerness} static field would let this rule enforce
   * "LAZY ⇔ {@code Memoized<T>} return". Implementable once §5.2 sites carry the
   * declaration.
   */
  @Test
  @Disabled(
      "541 §4.3 rule 4e — deferred; specific blocker: phase classes do not yet declare their"
          + " Eagerness statically. Re-enable when phases adopt a Phase<I,O> marker or an"
          + " EAGERNESS field that the rule can introspect.")
  @DisplayName("4e — LAZY phases return Memoized<T> / Supplier<T> (deferred)")
  void lazyPhaseConformance() {
    fail("rule 4e is disabled — see @Disabled annotation for the blocker");
  }

  /**
   * 4f — Supplier-escape detector. **Disabled** per §9.3 residual #2: a clean detector
   * needs either a marker annotation ({@code @PhaseSuppliedField}) on legitimate Supplier
   * sites OR a hand-rolled allowlist of origin classes (BootstrapLateBindings, phase Input
   * records, etc.). Both are real design work; defer until the substrate accumulates enough
   * Supplier-typed fields that the false-positive rate of a naive rule would be acceptable.
   */
  @Test
  @Disabled(
      "541 §4.3 rule 4f — deferred; specific blocker: §9.3 residual #2. Re-enable after"
          + " either (a) shipping @PhaseSuppliedField marker annotation or (b) the Supplier"
          + " origin-class allowlist stabilizes enough for a low-false-positive rule.")
  @DisplayName("4f — No Supplier<T> field escape outside late-binding holders (deferred)")
  void supplierEscapeDetector() {
    fail("rule 4f is disabled — see @Disabled annotation for the blocker");
  }

  /**
   * 4g — service construction site (fix-pass B.3: lifted from §31 Rule 2 in {@code
   * AppServicesWorkerGuardrailsTest}). Service construction lives in {@code
   * bootstrap.phases..} or in the service-impl's own package. Catches LateBoundServices-style
   * drift — if a class outside the allowlist ever constructs a {@code *ServiceImpl} in
   * {@code app-services}, the build fails. After §31 Phase 3, ServicePhase is the sole
   * production construction site.
   *
   * <p>Uses a custom {@link JavaConstructorCall} predicate (rather than the broader
   * {@code accessClassesThat}) to match constructor calls only, and skips self-construction
   * (delegating constructors within the same impl class) to avoid false positives.
   *
   * <p>{@code LocalApiServer} is exempted because it retains defensive fallback
   * constructions for the {@code headAssembly == null} legacy test seam. Production reads
   * come from {@code headAssembly.workers()/core()/inference()}.
   */
  private static final DescribedPredicate<JavaConstructorCall> SERVICE_IMPL_CONSTRUCTOR_CALL =
      new DescribedPredicate<>("call constructor of a *ServiceImpl class") {
        @Override
        public boolean test(JavaConstructorCall call) {
          String targetClass = call.getTargetOwner().getName();
          if (!targetClass.startsWith("io.justsearch.app.services.")) return false;
          if (!targetClass.endsWith("ServiceImpl")) return false;
          String originClass = call.getOriginOwner().getName();
          return !originClass.equals(targetClass);
        }
      };

  @ArchTest
  static final ArchRule serviceImplsConstructedOnlyInBootstrapPhases =
      noClasses()
          .that()
          .resideOutsideOfPackage("io.justsearch.app.services.bootstrap.phases..")
          .and()
          .doNotHaveFullyQualifiedName("io.justsearch.ui.api.LocalApiServer")
          .should(
              new ArchCondition<JavaClass>("not call a *ServiceImpl constructor") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                  for (JavaConstructorCall call : item.getConstructorCallsFromSelf()) {
                    if (SERVICE_IMPL_CONSTRUCTOR_CALL.test(call)) {
                      events.add(
                          SimpleConditionEvent.violated(
                              call,
                              item.getName()
                                  + " calls constructor "
                                  + call.getTargetOwner().getSimpleName()
                                  + " at "
                                  + call.getSourceCodeLocation()));
                    }
                  }
                }
              });
}
