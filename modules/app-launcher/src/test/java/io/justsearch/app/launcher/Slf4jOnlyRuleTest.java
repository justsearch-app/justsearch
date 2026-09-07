package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;

/**
 * ArchUnit rule: production code must route through SLF4J for logging output rather than
 * {@code System.out} / {@code System.err}. Tempdoc 400 LR6-d closes tempdoc 289 RC1 A16 ("no
 * automated enforcement of logging conventions") for the {@code System.*} branch of that RC.
 *
 * <p>Exempted classes (transparent {@code KNOWN_EXCEPTIONS} map per tempdoc 325's freeze-store
 * removal pattern): launcher bootstrap (pre-logging-init), crash reporting (last-resort when
 * SLF4J itself may be dead), and IPC handshake paths that must write to stdout for contract
 * reasons.
 *
 * <p>Companion rule (LR6-d-A15, OpenRewrite CompleteExceptionLogging) lands in a separate commit
 * to keep tooling-boundaries clean.
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class Slf4jOnlyRuleTest {

  /**
   * Classes allowed to reference {@code System.out} / {@code System.err} directly. Each entry
   * documents why. Any NEW addition should be rare and discussed in PR.
   */
  private static final Set<String> KNOWN_EXCEPTIONS =
      Set.of(
          // Launcher bootstrap — runs before logging is initialized.
          "Launcher",
          "LauncherBootstrap",
          "LauncherEnvironment",
          "HeadlessApp", // CLI stdout contract + boot output
          // Crash reporter — SLF4J may itself be in a failed state when this fires.
          "CrashReporter",
          // Worker IPC handshake / port emission — stdout is the contract with the Head.
          "WorkerProcessMain",
          // Build-time tool output (diagnostic-only mains):
          "RunMatrixPrinter",
          "ReportWorkflowAttribution");

  @ArchTest
  static final ArchRule productionCodeUsesSlf4jNotSystemStreams =
      noClasses()
          .that(new DescribedPredicate<JavaClass>("are not known-exceptions for System.out/err") {
            @Override
            public boolean test(JavaClass c) {
              return !KNOWN_EXCEPTIONS.contains(c.getSimpleName());
            }
          })
          .should(
              new ArchCondition<JavaClass>(
                  "not access System.out or System.err — use SLF4J via LoggerFactory.getLogger") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                  for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    if (!(access instanceof JavaFieldAccess fa)) {
                      continue;
                    }
                    String owner = fa.getTargetOwner().getFullName();
                    String name = fa.getTarget().getName();
                    if ("java.lang.System".equals(owner)
                        && ("out".equals(name) || "err".equals(name))) {
                      events.add(
                          SimpleConditionEvent.violated(
                              item,
                              item.getSimpleName()
                                  + " accesses System."
                                  + name
                                  + " at "
                                  + access.getSourceCodeLocation()
                                  + " — use an SLF4J Logger instead"
                                  + " (tempdoc 400 LR6-d closes 289 RC1 A16)."));
                    }
                  }
                }
              });
}
